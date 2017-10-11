package network_design_project;
import java.io.*;
import java.net.*;


public class UDPServer implements Runnable {	
	
	String imageName;
	int port;
	boolean packetLogging;
	FileWriter out;
	
	private final int DATA_SIZE = 1024;
	private final int HEADER_SIZE = 6;
	private final int PACKET_SIZE = DATA_SIZE + HEADER_SIZE;
	
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
	
/*	
	public static void main(String args[]) throws Exception 
	{
		UDPServer s = new UDPServer("server_image.jpg", 9878, false);
		s.receiveImage();
	}
*/
	
	private byte[] destructPacket ( byte[] packet ){
		
		byte[] ackNumber = new byte[2];
		byte[] checksum = new byte[2];
		byte[] length = new byte[2];
		
		int packetLength = packet[4] & 0xFF;
		packetLength = packetLength << 8;
		packetLength = packetLength | (packet [5] & 0xFF);
		
		byte[] data = new byte[packetLength];	
		for( int i = 0; i < packetLength; i++){
			data[i] = packet[ i + HEADER_SIZE ];
		}
		
		return data;	
	}
	
	private byte[] addPacketHeader(int packetSize, byte[] readData ){
		
		byte[] packet = new byte[packetSize + HEADER_SIZE];
		
		for ( int i = 0; i < packetSize; i++){
			packet[i + HEADER_SIZE] = readData[i];
		}
		
		//Not doing checksum on way back
		packet[2] = 0;
		packet[3] = 0;
		
		byte[] ackNumber = new byte[2];
		ackNumber = calculateAckNumber();
		packet[0] = ackNumber[0];
		packet[1] = ackNumber[1];
		
		assert ( packetSize > 1024 );
		packet[5] = (byte) (packetSize & 0xFF);
		packet[4] = (byte) ((packetSize >> 8) & 0xFF);
		
		return packet;
	}
	
	private byte[] calculateAckNumber(){
		byte[] ackNum = new byte[2];
		ackNum[0] = 0;
		ackNum[1] = 0;
		
		return ackNum;
	}
	
	
	private void transmitPacket(byte[] packet, DatagramSocket socket, InetAddress IPAddress ) throws Exception{

		DatagramPacket sendPacket = new DatagramPacket(packet, packet.length, IPAddress, port);
		socket.send(sendPacket);
		
	}
	
	public void receiveImage() throws Exception
	{
		/*
		 * 
		 *  Following code taken from
		 *  https://lowell.umassonline.net/bbcswebdav/pid-305360-dt-content-rid-977475_1/courses/L2710-11029/Sockets.pdf
		 * 
		 */
			
		DatagramSocket serverSocket = new DatagramSocket(port);
		byte[] packet = new byte[PACKET_SIZE];
		
		DatagramPacket receivePacket = null;
		InetAddress IPAddress = null;
		String data;
		
		while(true) {
			
			packet = null;
			receivePacket = null;
			data = null;
			IPAddress = null;
			
			if(packetLogging)
			{
				try {
					out = new FileWriter("ServerLog.txt");
					out.write("Logging Server packet traffic:\r\n\r\n");
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			/*
			 * 
			 *  Following code taken from
			 *  https://lowell.umassonline.net/bbcswebdav/pid-305360-dt-content-rid-977475_1/courses/L2710-11029/Sockets.pdf
			 * 
			 */
			
			
			packet = new byte[1028];
			receivePacket = new DatagramPacket(packet, packet.length);
			serverSocket.receive(receivePacket);	
			data = new String(receivePacket.getData());
			
			int packetLength = packet[4] & 0xFF;
			packetLength = packetLength << 8;
			packetLength = packetLength | (packet [5] & 0xFF);
			
			data = data.substring( HEADER_SIZE , HEADER_SIZE + packetLength );
						
			int packets_expected = Integer.parseInt(data, 10);
			int packets_received = 0;
			log("SERVER: Waiting for " + packets_expected + " packets");
			
			IPAddress = receivePacket.getAddress();
			port = receivePacket.getPort();
			
			String sendString = "Ready";
			byte[] sendData = sendString.getBytes();
			packet = new byte[sendData.length + HEADER_SIZE];
			packet = addPacketHeader( sendData.length , sendData );			
			transmitPacket( packet, serverSocket, IPAddress); 
			
			
			FileOutputStream fos = new FileOutputStream(imageName); //Open output file
			
			log("SERVER: Ready for packets");
			while ( packets_received < packets_expected){
				packet = new byte[PACKET_SIZE];
				receivePacket = new DatagramPacket(packet, packet.length);
				serverSocket.receive(receivePacket);
				byte[] packetData = destructPacket( packet );
				
				if ( packetData != null){
					fos.write(packetData);
				} else {
					System.out.println("SERVER: Null packet :( ");
					//TODO
				}
				packets_received++;
			}
			
			log("SERVER: Got " + packets_received + " packets");
			if(packetLogging)
				out.close();
			fos.close();
			
			/*
			 * 
			 *  Following code taken from
			 *  https://lowell.umassonline.net/bbcswebdav/pid-305360-dt-content-rid-977475_1/courses/L2710-11029/Sockets.pdf
			 * 
			 */
			IPAddress = receivePacket.getAddress();
			port = receivePacket.getPort();
			
			
			sendString = String.valueOf(packets_received + " packets received");
			byte[] endData;
			endData = sendString.getBytes();
			packet = new byte[endData.length + HEADER_SIZE];
			packet = addPacketHeader( endData.length, endData );
			transmitPacket( packet, serverSocket, IPAddress);
			
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