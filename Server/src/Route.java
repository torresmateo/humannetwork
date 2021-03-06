import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import javax.json.JsonObject;
import javax.swing.SwingUtilities;

public class Route extends Thread {

    private long startTime = -1;
    private boolean started = false;
    private ConnectionList connections;
    private PacketList messages;
    private LinkList links;
    private Random rand = new Random();
    List<DelayedMessage> queue = new ArrayList<DelayedMessage>();
    private int session;
    private static final int MAX_PER_NET = 10;
    private static final int ONE_NET_SIZE = 12; // If fewer than this then just one net.
    private static final int SMALLEST_NET_SIZE = 5; // Cannot play with fewer than this number of nodes.
    private Map<String, Network> cycles = new HashMap<String, Network>();

    public Route(ConnectionList connections, LinkList links,
            PacketList messages, int session) {
        this.connections = connections;
        this.messages = messages;
        this.links = links;
        this.session = session;
    }

    public void addConnection(BufferedOutputStream os, InputStream isr,
            InetAddress a) throws HandshakeException {
        final Connection c = new Connection(os, isr);

        int setNode = c.getNode();
        boolean keep = false;
        System.out.println("+++++++++++++++ Session: " + c.getSession() + " +++++++++++++++++");
        System.out.println("+++++++++++++++    Node: " + setNode + " +++++++++++++++++");
        if ((session == c.getSession() || c.getSession() == 999) && setNode != 0) {
            // Loop through the connections and find the node number
            for (int i = 0; i < connections.size(); i++) {
                Connection cc = connections.get(i);
                System.out.println("++++++++++++ Checking the Old Node :" + cc.getNode() + " +++++++++++++++++");
                if (cc.getNode() == setNode) {
                    // if session is 999 check that the node is actually broken
                    if (c.getSession() == 999 && cc.isGood()) {
                        System.out.println("Cannot restore session 999 node - not broken - is the nodenum correct?");
                        continue;
                    }
                    // Keep old node Name
                    c.setHostname(cc.getHostname());
                    c.setNetwork(cc.getNetwork());
                    c.setLastTask(cc.getLastTask());
                    System.out.println("Restored the node.  It had a last task? " + cc.hasLastTask());
                    final int actualNode = i;
                    SwingUtilities.invokeLater(() -> {
                        // This is not a structural modification so will not
                        // cause Thread problems
                        connections.set(actualNode, c);
                    });
                    keep = true;
                    break;
                }
            }
        }
        if (!keep) {
            // If we are not keeping the session this is bad if we were asked to, even if the game has not started.
            if (c.getSession() == 999) {
                c.close();
                throw new HandshakeException(
                        "Cannot reconnect to (session=999), either it is still running, or the node numbder is wrong");
            }
            if (started) {
                // Should create a FAIL messge and send it out.
                c.close();
                throw new HandshakeException(
                        "Cannot add new connections after the game has started - try URL: session=999 and node=num");
            }
            c.setup(session);
            try {
                SwingUtilities.invokeAndWait(() -> {
                    connections.addElement(c);
                });
            } catch (InvocationTargetException | InterruptedException e) {
                throw new HandshakeException("Failed to add new connection");
            }
        }
        // add the appropriate status reply
        DelayedMessage msg = new ConnectMessage(c, -1, keep);
        synchronized (queue) {
            if (started) {
                // Must be keeping an old connection.
                if (c.hasLastTask()) {
                    System.out.println("Sending out the restored task" + c.getLastTask());
                    queue.add(c.getLastTask());
                }
                System.out.println("Queueing message: " + msg);
                queue.add(msg);
            } else {
                System.out.println("Sending message out: " + msg);
                msg.send();
            }
        }
    }

    private Connection getByNode(int node) {
        for (int i = 0; i < connections.size(); i++) {
            Connection c = connections.get(i);
            if (c.getNode() == node) {
                return c;
            }
        }
        return null;
    }

    private int nodeToIndex(int node) {
        int l = connections.size();
        for (int j = 0; j < l; j++) {
            if (connections.get(j).getNode() == node) {
                return j;
            }
        }
        return 0; // Should not happen
    }

    private void route(JsonObject message, int fromNode) {
        String text = message.getString("text", null);
        int from = message.getInt("from", -1);
        int to = message.getInt("to", -1);

        if (fromNode == from && to >= 0 && text != null) {
            SwingUtilities.invokeLater(() -> {
                messages.addPacket(from, to, text,
                        getByNode(from).getNetwork());
            });
            if (to == 0) {
                for (int i = 0; i < connections.size(); i++) {
                    Connection c = connections.get(i);
                    if (c != null && links.isNeighbour(fromNode, c.getNode())) {
                        send(c, text, from, to);
                    }
                }
            } else {
                Connection c = getByNode(to);
                if (c != null && links.isNeighbour(from, to)) {
                    send(c, text, from, to);
                }
            }
        }
    }

    private void send(Connection c, String message, int fromNode, int toNode) {
        int drop = rand.nextInt(100);
        if (drop >= links.getDropRate() || links.getDropRate() == 0) {
            String content = message;
            // TODO Change to only allowed matched responses.
            boolean shouldSend = (links.getOffset() == 0)
                    || (!links.isCheckwhois())
                    || ((content.length() > 12)
                            && (content.substring(0, 12).equals("WHOIS(Query,")
                                    || content.subSequence(0, 13)
                                            .equals("WHOIS(Answer,")));
            if (shouldSend) {
                // Corruption
                int corr = rand.nextInt(100);
                if (corr < links.getCorruptionRate()) {
                    content = Texts.corrupt(content);
                }
                // Network delay
                int delay = (links.getDelay() > 0)
                        ? rand.nextInt(links.getDelay()) : 0;
                synchronized (queue) {
                    queue.add(new PacketMessage(c, delay, fromNode, toNode,
                            content));
                }
            }
        }
    }

    public void sendNow(List<DelayedMessage> messageList) {
        for (int i = messageList.size() - 1; i >= 0; i--) {
            DelayedMessage m = messageList.get(i);
            System.out.println("Attempting to send out: " + m);
            if (m.ready()) {
                if (m.current(startTime)) {
                    m.send();
                }
                messageList.remove(i);
            } else {
                m.decr();
            }
        }
    }

    @Override
    public void run() {
        try {
            while (true) {
                synchronized (queue) {
                    sendNow(queue);
                }
                int l = connections.size();
                for (int i = 0; i < l; i++) {
                    Connection c = connections.get(i);
                    if (c != null && c.ready()) {
                        JsonObject message = c.read();
                        if (message != null) {
                            route(message, c.getNode());
                        }
                    }
                }
                Thread.sleep(1000L);
                // System.out.print('+');
            }
        } catch (InterruptedException e) {
            System.out.println("Closing server!");
        }
    }

    // This method can only be executed in the EDT so is safe from Connections
    // updates
    public void updateStatus() {
        // The first update triggers network building.
        if (!started) {
            started = true;

            final int nodes = this.connections.size();
            int numNets = 1;
            if (nodes < SMALLEST_NET_SIZE) {
                System.err.println("Cannot play with fewer than: " + SMALLEST_NET_SIZE + " nodes");
                System.exit(1);
            } else if (nodes > ONE_NET_SIZE) {
                numNets = (nodes + MAX_PER_NET - 1) / MAX_PER_NET;
            }
            createCycles(numNets);
            // Begin to check for messages.
            synchronized (queue) {
                this.start();
            }
        }
        int l = connections.size();
        List<String> texts = null;
        if (links.getOffset() != 0 && !links.isCheckwhois()) {
            texts = new ArrayList<String>();
            Texts.choose_messages(texts, l, links.getCorruptionRate() > 0);
        }
        // mark any current messages out of date
        startTime = System.currentTimeMillis();

        synchronized (queue) {
            for (int i = 0; i < l; i++) {
                Connection c = connections.get(i);
                if (c != null) {
                    TaskMessage tm ;
                    if (links.getOffset() == 0) {
                        tm = new TaskMessage(c, -1, links);
                    } else {
                        Network n = cycles.get(c.getNetwork());
                        int recipient = n.offsetNode(c.getNode(), links.getOffset());
                        System.out.println("Node:" + c.getNode()
                                + "sending to: " + recipient);
                        if (links.isCheckwhois()) {
                            String unknown = connections
                                    .get(nodeToIndex(recipient)).getHostname();
                            tm = new TaskMessage(c, -1, links, unknown);
                        } else {
                            tm = new TaskMessage(c, -1, links, recipient, texts.get(i));
                        }
                    }
                    // Store current task in the connection and send it out
                    c.setLastTask(tm);
                    queue.add(tm);
                }
            }
        }
    }

    private void addLink(int from, int to, String network) {
        int nodeA = connections.get(from).getNode();
        int nodeB = connections.get(to).getNode();
        System.out.println("Create link between " + nodeA + " and " + nodeB);
        if (!links.isNeighbour(nodeA, nodeB)) {
            links.addElement(new Link(nodeA, nodeB, network));
        }
    }

    private void createCycle(final String netName, final int[] nodeIndexes, final int first, final int last) {
         for (int index = first; index < last; index++) {
             this.connections.get(nodeIndexes[index]).setNetwork(netName);
             this.addLink(nodeIndexes[index], nodeIndexes[index + 1], netName);
         }
         this.addLink(nodeIndexes[first], nodeIndexes[last], netName);
         this.connections.get(nodeIndexes[last]).setNetwork(netName);
         this.cycles.put(netName, new Network(this.links, netName, this.connections));
    }

    private void createCycles(int count) {

        int[] nodeIndexes = new int[connections.size()];
        for (int index = 0; index < connections.size(); index++) {
            nodeIndexes[index] = index;
        }
        // Shuffle the list of nodes.
        for (int index = connections.size() - 1; index > 0; index--) {
            int other = rand.nextInt(index);
            int temp = nodeIndexes[other];
            nodeIndexes[other] = nodeIndexes[index];
            nodeIndexes[index] = temp;
        }
        // Make the cycles.
        float netSize = connections.size() / (float) count;
        int first = 0;
        for (int network = 1; network <= count; ++network) {
            final int last = Math.round((netSize * network - 1.0f));
            this.createCycle("Network" + network, nodeIndexes, first, last);
            first = last + 1;
        }
    }
}
