package network_design_project;
import java.io.*;
import java.net.*;


public class UDPServer implements Runnable {	
	
	String imageName;
	int port;
	boolean packetLogging;
	FileWriter out;
	
	/*
	 * If packetLogging is enabled, log messages to file and timestamps them.
	 * Otherwise, puts them out to System.out
	 */
	private void log(String logmsg) throws IOException
	{
		if(packetLogging)
		{
			long nowTime = System.currentTimeMillis();
			out.write(Long.toString(nowTime) + ": " +logmsg + "\r\n");
		}
		System.out.println(logmsg);
	}
	
	@Override
	protected void finalize()
	{
		if(packetLogging)
		{
			try {
				out.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
	
	/*
	 * Craetes a new server
	 * if logging is enabled, creates a new log file and writes packet messages to them.
	 */
	public UDPServer(String image, int portNum, boolean logging) 
	{
		imageName = image;
		port = portNum;
		packetLogging = logging;
		if(packetLogging)
		{
			try {
				out = new FileWriter("ServerLog.txt");
				out.write("Logging Server packet traffic:\r\n\r\n");
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
	
	public static void main(String args[]) throws Exception 
	{
		UDPServer s = new UDPServer("server_image.jpg", 9878, false);
		s.receiveImage();
	}
	
	public void receiveImage() throws Exception
	{
		/*
		 * 
		 *  Following code taken from
		 *  https://lowell.umassonline.net/bbcswebdav/pid-305360-dt-content-rid-977475_1/courses/L2710-11029/Sockets.pdf
		 * 
		 */
		@SuppressWarnings("resource")
		DatagramSocket serverSocket = new DatagramSocket(port);
		byte[] receiveData = new byte[1024];
		byte[] sendData = new byte[1024];
		
		while(true) {
			
			/*
			 * 
			 *  Following code taken from
			 *  https://lowell.umassonline.net/bbcswebdav/pid-305360-dt-content-rid-977475_1/courses/L2710-11029/Sockets.pdf
			 * 
			 */
			receiveData = new byte[1024];
			DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
			serverSocket.receive(receivePacket);	
			String sentence = new String(receivePacket.getData());
			
			//pull first of data that contains packets to expect
			int substring = 0;
			for( int i = 0; i<1024; i++)
			{
				if ( Character.isDigit(sentence.charAt(i)) )
					substring++;
				else
					break;
			}	
			sentence = sentence.substring(0, substring );
			
			int packets_expected = Integer.parseInt(sentence, 10);
			int packets_received = 0;
			
			FileOutputStream fos = new FileOutputStream(imageName); //Open output file
			log("SERVER: Waiting for " + packets_expected + " packets");
			while ( packets_received < packets_expected){
				receiveData = new byte[1024];
				receivePacket = new DatagramPacket(receiveData, receiveData.length);
				serverSocket.receive(receivePacket);				
				fos.write(receiveData);
				packets_received++;
			}
			log("SERVER: Got " + packets_received + " packets\n");
			if(packetLogging)
				out.close();
			fos.close();
			
			/*
			 * 
			 *  Following code taken from
			 *  https://lowell.umassonline.net/bbcswebdav/pid-305360-dt-content-rid-977475_1/courses/L2710-11029/Sockets.pdf
			 * 
			 */
			InetAddress IPAddress = receivePacket.getAddress();
			int ports = receivePacket.getPort();
			String receivedData = String.valueOf(packets_received + " packets received");
			sendData = receivedData.getBytes();
			DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, IPAddress, ports);
			serverSocket.send(sendPacket);
		}
	}

	@Override
	public void run() {
		try {
			receiveImage();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}