package network_design_project;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.JOptionPane;


//Follows tutorial from https://www3.ntu.edu.sg/home/ehchua/programming/java/j4a_gui.html
@SuppressWarnings("serial")
public class CopyGUI extends Frame {

	//GUI elements
	Label portLabel; //label and text field for input port #
	TextField portField;
	
	Label serverIm;  //label and text field for received image name
	TextField serverField;
	
	Checkbox clientLogging; //check to enable client/server logging to file
	Checkbox serverLogging;
	
	Label clientIm; //label and fc for picking image to send.
	Button fcButton;
	String clientFile;
	FileDialog fc;
	
	CheckboxGroup lossGroup;
	Container lossContainer;
	Checkbox noLoss;
	Checkbox ackError;
	Checkbox dataError;
	Checkbox ackDrop;
	Checkbox dataDrop;
	Label errorLabel;
	TextField errorPercentage;
	Label timeoutLabel;
	TextField timeoutField;
	Label windowSizeLabel;
	TextField windowSizeField;
	
	Button startServer; //buttons to start server and client
	Button startClient;
	
	//client-server logic 
	UDPClient client;
	Thread clientThread;
	boolean startClientThread = true; //state whether to start or stop the client thread
	UDPServer server;
	Thread serverThread;
	boolean startServerThread = true; //state whether to start or stop the servert thread
	
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
		
		//determine what to send to the Client as the error level
		double error = 0;
		double dropChance = 0;
		if(noLoss.getState())
		{
			error = 0;
			dropChance = 0;
		}
		else if(dataError.getState())
		{
			error = Double.parseDouble(errorPercentage.getText());
			dropChance = 0;
		}
		else if(ackError.getState())
		{
			error = 0;
			dropChance = 0;
		}
		else if (dataDrop.getState()){
			error = 0;
			dropChance = Double.parseDouble(errorPercentage.getText());
		} 
		else if (ackDrop.getState()){
			error = 0;
			dropChance = 0;
		}
		
		System.out.println(clientFile);
		client = new UDPClient(clientFile, port, clientLogging.getState(), error, dropChance, Integer.parseInt(timeoutField.getText()),  Integer.parseInt(windowSizeField.getText()));
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
		
		//determine what to send to the Server as the error level
		double error = 0;
		double dropChance = 0;
		if(noLoss.getState())
		{
			error = 0;
			dropChance = 0;
		}
		else if(dataError.getState())
		{
			error = 0;
			dropChance = 0;
		}
		else if(ackError.getState())
		{
			error = Double.parseDouble(errorPercentage.getText());
			dropChance = 0;
		}
		else if (dataDrop.getState()){
			error = 0;
			dropChance = 0;
		} 
		else if (ackDrop.getState()){
			error = 0;
			dropChance = Double.parseDouble(errorPercentage.getText());
		}
		
		server = new UDPServer(serverField.getText(), port, serverLogging.getState(), error, dropChance);
		
		//make the thread
		serverThread = new Thread(server);
		serverThread.start();
	}
	
	/*
	 * Kill the server thread
	 */
	void stopServerThread()
	{
		if(serverThread != null)
		{
			try{
				server.killThisAgent();
				serverThread.join();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}
	
	/*
	 * Kill the client thread
	 */
	void stopClientThread()
	{
		if(clientThread != null)
		{
			try{
				client.killThisAgent();
				clientThread.join();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}
	/*
	 * Setup GUI components
	 */
	public CopyGUI()
	{
		//create GUI widgets	
		portLabel = new Label("Port #");
		portField = new TextField("9878", 4);
		
		serverIm = new Label("Server Image Name");
		serverField = new TextField("server_image.jpg");
		
		clientLogging = new Checkbox("Client Logging", true);
		serverLogging = new Checkbox("Server Logging", true);
		
		clientIm = new Label("Image name:");
		fcButton = new Button("Choose file to Send");
		fc = new FileDialog(this, "Choose an image", FileDialog.LOAD);
		clientFile = null;
		
		lossGroup = new CheckboxGroup();
		lossContainer = new Panel();
		noLoss = new Checkbox("No Loss", lossGroup, true);
		ackError = new Checkbox("ACK error", lossGroup, false);
		dataError = new Checkbox("Data error", lossGroup, false);
		ackDrop = new Checkbox("Ack Drop", lossGroup, false);
		dataDrop = new Checkbox("Data Drop", lossGroup, false);
		
		lossContainer.add(noLoss);
		lossContainer.add(ackError);
		lossContainer.add(dataError);
		lossContainer.add(ackDrop);
		lossContainer.add(dataDrop);
		
		errorLabel = new Label("Error rate (%%)");
		errorPercentage = new TextField("00", 2);
		
		timeoutLabel = new Label("Timeout (ms)");
		timeoutField = new TextField("30", 4);
		
		windowSizeLabel = new Label("Window size");
		windowSizeField = new TextField("5", 4);
		
		startServer = new Button("Start Server");		
		startClient = new Button("Start Client");
		
		//set layout
		setLayout(new GridLayout(0,2));
		add(portLabel);
		add(portField);
		add(serverIm);
		add(serverField);
		add(clientLogging);
		add(serverLogging);
		add(clientIm);
		add(fcButton);
		add(lossContainer);
		add(new Panel()); //spacer
		add(errorLabel);
		add(errorPercentage);
		add(timeoutLabel);
		add(timeoutField);
		add(windowSizeLabel);
		add(windowSizeField);
		add(startServer);
		add(startClient);
	
		
		//set listeners for buttons
		//Anonymous class code used from S.O. https://stackoverflow.com/questions/9569700/java-call-method-via-jbutton
		startServer.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				if(startServerThread)
				{
					startServerThread();
					
					startServer.setLabel("Stop Server");
					startServerThread = false;
					
				}
				else
				{
					//stop the server
					stopServerThread();
					if(clientFile != null)
						startClient.setEnabled(true);
					startServer.setLabel("Start Server");
					startServerThread = true;
				}
			}
		}); 
		
		startClient.addActionListener(new ActionListener() {
			
			public void actionPerformed(ActionEvent e) {
				if(startClientThread)
				{
					startClientThread();
					startClient.setLabel("Stop Client");
					startClientThread = false;
				}
				else
				{
					stopClientThread();
					startClient.setLabel("Start Client");
					startClientThread = true;
				}
			}
		}); 
		//Used from example about how to use a FileDialog
		fcButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				fc.setVisible(true);
				String fn = fc.getDirectory() + fc.getFile(); //a workaround to get the absolute path to the file
				fn = fn.replaceAll("\\\\", "/");//convert windows to java path format: https://stackoverflow.com/questions/3059383/file-path-windows-format-to-java-format
				
				if(fn != null)
				{
					//update state and label text
					clientFile = fn;
					clientIm.setText("Image name: " + fc.getFile());
					
					//if the server is running and the file looks good
					if(fn != null && serverThread != null && serverThread.isAlive())
						startClient.setEnabled(true);
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
		setSize(800,230);
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
