import java.awt.EventQueue;
import java.util.Scanner;

import javax.swing.JLabel;
import javax.swing.JOptionPane;

import java.io.IOException;

public class Client {

	// Client connection to the server
	static Connection c = null;
	static String id = "toto";
	final static String USAGE = "java Client [serverAddress] [Node]";
	static Thread listen = null;
	final static MessageList  messages = new MessageList();
	
	/**
	 * Thread listening for incoming messages
	 * For user sanity read only one message a second.
	 */
	public static void listen(final MessageList messages, final ClientGui window){
		listen = new Thread(){
			public void run(){
				try{
					while(true){
						if (c != null && c.ready()){
							final Notification message = new Notification(c.read());
							EventQueue.invokeLater(new Runnable() {
								public void run() {
							    switch(message.getType()) {
								    case STATUS:
								    	JLabel status = window.getStatusField();
											if (status != null) {
  									    status.setText(message.getText());
								    	}
									    break;
								    case MESSAGE:
								    	messages.addMessage(message.getText(), c.node);
								    	break;
							    }
								}
							});
						}
						Thread.sleep(1000L);
						//System.out.print('.');
					 }
				 }
				 catch (InterruptedException e){
				 }
			 }
		 };
		 listen.start();
	}
	
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		/** Define a host server */
		String host = "stafflinux.cs.rhul.ac.uk";
		/** Define a port */
		int port = 10000;
		int node = 0;
		
		/** Each client has a group id */
		try{
			if (args.length > 1){
				host = args[0];
				port = Integer.parseInt(args[1]);
			}
			if (args.length > 2){
				node = Integer.parseInt(args[2]);
			}
		} catch (Exception e){
                        System.err.println(USAGE);
			System.exit(0);
		}

		/** Connect */
		String [] possibleGroups = {"A", "B", "C"};
		String group = (String) JOptionPane.showInputDialog(null, "What is your group?", "Choose Your Network", JOptionPane.DEFAULT_OPTION, null, possibleGroups, possibleGroups[0]);
		if (group == null) {
			group = "A";
		}
		c = new Connection(host, port, group, node);
		
		/** Receive messages */
		final ClientController controller = new ClientController(c, messages);
		final ClientGui window = new ClientGui();
		
		EventQueue.invokeLater(new Runnable() {
			public void run() {
				try {
					window.initialize(c, messages, controller);
					window.setVisible(true);
				} catch (Exception e) {
					// Close resources - will cause read input loop to terminate gracefully
					e.printStackTrace();
					c.close();
				}
			}
		});
		
		final Scanner scanner = new Scanner(System.in);
		listen(messages, window);
		boolean connected = true;
		while(connected && listen.getState() != Thread.State.TERMINATED){
			String message = scanner.nextLine();
			String to = scanner.nextLine();
			connected = c.write(to + (char) 13 + message);
		}
		scanner.close();
	}	

}
