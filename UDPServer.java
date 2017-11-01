package network_design_project;
import java.io.*;
import java.net.*;


public class UDPServer implements Runnable {	
	
	//instance variables
	String imageName;
	int port;
	DatagramSocket serverSocket;
	boolean packetLogging;
	FileWriter out;
	//Chance that an outgoing packet gets corrupted
	double corruptionChance;
	//if this ever goes true, kill the server asap
	volatile boolean killMe = false; 
	
	//Constants 
	private final int HEADER_SIZE = 6;
	private final int PACKET_SIZE = 1024;
	private final int DATA_SIZE = PACKET_SIZE - HEADER_SIZE;
	private int corruptedCounter = 0;
	
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
	public UDPServer(String image, int portNum, boolean logging, double corruptionChance) 
	{
		imageName = image;
		port = portNum;
		packetLogging = logging;
		this.corruptionChance = corruptionChance;
		killMe = false;
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
	 * Tell the server to stop listening to the port and finish asap
	 */
	public void killServer()
	{
		if(serverSocket != null)
			serverSocket.close();
		killMe = true;
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
		
		boolean checksumHealthy;
		
		checksum = calculateChecksum( data, false );
		if( (~(packet[2] ^ checksum[0]) == 0) && (~(packet[3] ^ checksum[1]) == 0) ){
			checksumHealthy = true;
		} else {
			corruptedCounter++;
			checksumHealthy = false;
		}
		
		
		if(checksumHealthy){
			return data;
		} else {
			//TODO handle a bad checksum
			return data;
		}
			
	}
	
	
	private byte[] addPacketHeader(byte[] readData ){
		int packetSize = readData.length;
		byte[] packet = new byte[packetSize + HEADER_SIZE];
		
		byte[] maybeCorruptedData = corruptDataMaybe(readData, corruptionChance);
		//copies maybe-corrupted into the new packet
		for ( int i = 0; i < packetSize; i++){
			packet[i + HEADER_SIZE] = maybeCorruptedData[i];
		}
		
		//puts appropriate fields into the header.
		//calculates the checksum based off of the definitely-not-corrupted readData
		byte[] checksum = new byte[2];
		checksum = calculateChecksum( readData , true );
		packet[2] = checksum[0];
		packet[3] = checksum[1];
		
		byte[] ackNumber = new byte[2];
		ackNumber = calculateAckNumber();
		packet[0] = ackNumber[0];
		packet[1] = ackNumber[1];
		
		assert ( packetSize > PACKET_SIZE );
		packet[5] = (byte) (packetSize & 0xFF);
		packet[4] = (byte) ((packetSize >> 8) & 0xFF);
		
		return packet;
	}
	
	/*
	 * returns a byte[2] containing the current ACK number.
	 */
	private byte[] calculateAckNumber(){
		byte[] ackNum = new byte[2];
		ackNum[0] = 0;
		ackNum[1] = 0;
		
		return ackNum;
	}
	
	private byte[] calculateChecksum( byte[] readData, boolean invertFlag ){
		byte[] checksum = new byte[2];
		checksum[0] = 0;
		checksum[1] = 0;
		
		int checksum16bit = 0;	
		for( int i = 0; i < readData.length; i++){
			int temp = 0;
			temp = readData[i] & 0xFF;
			temp = temp << 8;
			if(i < readData.length - 1){
				temp = temp | (readData[++i] & 0xFF);
			} else {
				temp = temp | 0 & 0xFF;
			}
			checksum16bit = checksum16bit + temp;
			if( checksum16bit > 65535 ){
				checksum16bit = checksum16bit - 65534;
			}
		}
		if(invertFlag){
			checksum16bit = ~checksum16bit;
		}
		
		checksum[0] = (byte) (checksum16bit & 0xFF);
		checksum[1] = (byte) ((checksum16bit >> 8) & 0xFF);
			
		return checksum;
	}
	
	
	private void transmitPacket(byte[] packet, DatagramSocket socket, InetAddress IPAddress ) throws Exception{
		DatagramPacket sendPacket = new DatagramPacket(packet, packet.length, IPAddress, port);
		socket.send(sendPacket);
	}
	
	/*
	 * Rolls the dice and corrupts the data (adds a random bit flip) percentChance% of the time
	 * Returns the a *new* copy of data that might have a bit error
	 */
	private byte[] corruptDataMaybe(byte[] data, double percentChance){
		byte[] newData = data.clone();
		if( Math.random()*100 < percentChance ){
			//find a random bit to flip
			int index = (int) Math.floor(Math.random() * newData.length);
			int bit = (int) Math.floor(Math.random() * 8.0);
			//actually flips the bit
			newData[index] = (byte) (newData[index] ^ (1 << bit));
			return newData;
		} else {
			return newData;
		}
	}
		
	public void receiveImage() throws Exception
	{
		/*
		 * 
		 *  Following code taken from
		 *  https://lowell.umassonline.net/bbcswebdav/pid-305360-dt-content-rid-977475_1/courses/L2710-11029/Sockets.pdf
		 * 
		 */
			
		serverSocket = new DatagramSocket(port);
		byte[] packet = new byte[PACKET_SIZE];
		
		DatagramPacket receivePacket = null;
		InetAddress IPAddress = null;
		String data;
		
		while(!killMe) {
			
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
			try{
				serverSocket.receive(receivePacket);
			} catch (SocketException e) {
				log("Socket port closed externally");
				break;
			}
		
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
			packet = addPacketHeader(sendData);			
			transmitPacket( packet, serverSocket, IPAddress); 
			
			
			FileOutputStream fos = new FileOutputStream(imageName); //Open output file
			
			log("SERVER: Ready for packets");
			while ( packets_received < packets_expected & !killMe){
				packet = new byte[PACKET_SIZE];
				receivePacket = new DatagramPacket(packet, packet.length);
				try{
					serverSocket.receive(receivePacket);
				} catch (SocketException e) {
					log("Socket port closed externally");
					break;
				}
				byte[] packetData = destructPacket( packet );
				
				if ( packetData != null){
					fos.write(packetData);
				} else {
					System.out.println("SERVER: Null packet :( ");
					//TODO
				}
				packets_received++;
				
				//TODO send ACK packet or not.
			}
			
			log("SERVER: Got " + packets_received + " packets");
			if(packetLogging)
				out.close();
			fos.close();
			

			System.out.println(corruptedCounter + " checksums corrupted :'(");
			
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
			packet = addPacketHeader(endData);
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