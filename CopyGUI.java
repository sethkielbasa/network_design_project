package network_design_project;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.JOptionPane;


//Follows tutorial from https://www3.ntu.edu.sg/home/ehchua/programming/java/j4a_gui.html
public class CopyGUI extends Frame {

	//GUI elements
	Label portLabel; //label and text field for input port #
	TextField portField;
	
	Label serverIm;  //label and text field for received image name
	TextField serverField;
	
	Label clientIm; //label and fc for picking image to send.
	Button fcButton;
	String clientFile;
	FileDialog fc;
	
	Button startServer; //buttons to start server and client
	Button startClient;
	
	//client-server logic 
	UDPClient client;
	Thread clientThread;
	UDPServer server;
	Thread serverThread;
	
	/*
	 * Make a new UDPClient with portNumber and image file from the Gui's inputs
	 * Start it in a new thread. 
	 */
	void startClientThread()
	{
		//make client
		int port = 9999;
		if(clientFile == null)
		{
			new JOptionPane("No client file selected ", JOptionPane.ERROR_MESSAGE);
			return;
		}
		try
		{
			 port = Integer.parseInt(portField.getText());
		} catch (NumberFormatException e) {
			new JOptionPane("Port number could not be parsed to Int. Default value used (9999)", JOptionPane.ERROR_MESSAGE);
		}
		
		client = new UDPClient(clientFile, port);
		//make the thread
		clientThread = new Thread(client);
		clientThread.start();
	}
	
	/*
	 * Make a new UDPClient with portNumber and image name from the Gui's inputs
	 * Start it in a new thread. 
	 */
	void startServerThread()
	{
		//make the UDPServer
		int port = 9999;
		try
		{
			port = Integer.parseInt(portField.getText());
		} catch (NumberFormatException e) {
			new JOptionPane("Port number could not be parsed to Int. Default value used (9999).", JOptionPane.ERROR_MESSAGE);
		}
		server = new UDPServer(serverField.getText(), port);
		
		//make the thread
		serverThread = new Thread(server);
		serverThread.start();
	}
	/*
	 * Setup GUI components
	 */
	public CopyGUI()
	{
		//create objects	
		portLabel = new Label("Port #");
		portField = new TextField("9878", 4);
		
		serverIm = new Label("Server Image Name");
		serverField = new TextField("server_image.jpg");
		
		clientIm = new Label("Image name:");
		fcButton = new Button("Choose file to Send");
		fc = new FileDialog(this, "Choose an image", FileDialog.LOAD);
		clientFile = null;
		
		startServer = new Button("Start Server");
		startClient = new Button("Start Client");
		
		//set layout
		setLayout(new GridLayout(0,2));
		add(portLabel);
		add(portField);
		add(serverIm);
		add(serverField);
		add(clientIm);
		add(fcButton);
		add(startServer);
		add(startClient);
		
		//set listeners for buttons
		//Anonymous class code used from S.O. https://stackoverflow.com/questions/9569700/java-call-method-via-jbutton
		startServer.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				startServerThread();
			}
		}); 
		
		startClient.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				startClientThread();
			}
		}); 
		//Used from example about how to use a FileDialog
		fcButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				fc.setVisible(true);
				String fn = fc.getFile();
				if(fn != null)
				{
					clientFile = fn;
					clientIm.setText("Image name: " + fn);
				}
			}
		});
		
		//set window parameters and set visible
		//close on exit. More S.O. code https://stackoverflow.com/questions/5281262/how-to-close-the-window-in-awt
		addWindowListener(new WindowAdapter(){
			public void windowClosing(WindowEvent we){
				System.exit(0);
			}
		});
		setTitle("Image Transfer-er");
		setSize(450,200);
		setVisible(true);
	}
	
	/**
	 * Entry point for the test GUI
	 */
	public static void main(String[] args)
	{
		new CopyGUI();
	}
}
