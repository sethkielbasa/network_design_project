package network_design_project;

import java.io.*;
import java.net.*;
public class UDPClient implements Runnable{
	
	
	private final int HEADER_SIZE = 6;
	private final int PACKET_SIZE = 1024;
	private final int DATA_SIZE = PACKET_SIZE - HEADER_SIZE; //set packet size
	String imageName;
	int port;
	double corruptionChance;
	
	DatagramSocket clientSocket;
	boolean packetLogging;
	volatile boolean killMe; //set true to exit as fast as possible
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
	
	/*
	 * Tell the client to stop listening and die
	 */
	public void killClient()
	{
		if(clientSocket != null)
			clientSocket.close();
		killMe = true;
	}
	
	/*
	 * Called automatically whenever the object is destroyed
	 */
	@Override
	protected void finalize()
	{
		if(clientSocket != null && !clientSocket.isClosed())
			clientSocket.close();
		if(packetLogging)
		{
			try {
				out.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
	
	public UDPClient(String image, int portNum, boolean logging, double corruptionChance)
	{
		port = portNum;
		imageName = image;
		packetLogging = logging;
		this.corruptionChance = corruptionChance;
		
		killMe = false;
		if(logging)
		{
			try {
				out = new FileWriter("ClientLog.txt");
				out.write("Writing Client packet traffic:\r\n\r\n");
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
	
	private int getNumberOfPacketsToSend(String file_to_send) throws IOException{
		int number_of_packets = 0;
		System.out.println(file_to_send);
		FileInputStream fis = new FileInputStream( file_to_send ); //Open file to send
		number_of_packets = (fis.available() / DATA_SIZE); //size of file divided by packet size
		if ( fis.available() % DATA_SIZE > 0){ //if there are bytes leftover
			number_of_packets++; 
		}
		fis.close();
		return number_of_packets;
	}
	
	private void transmitPacket(byte[] packet, DatagramSocket socket ) throws Exception{
		
		InetAddress IPAddress = InetAddress.getByName("localhost");		
		DatagramPacket sendPacket = new DatagramPacket(packet, packet.length, IPAddress, port);
		socket.send(sendPacket);
		
	}
	
	private byte[] addPacketHeader(byte[] readData ){
		int packetSize = readData.length;
		byte[] packet = new byte[packetSize + HEADER_SIZE];
		
		//copies over data from maybe corrupted data
		byte[] maybeCorrupted = corruptDataMaybe(readData, corruptionChance);
		for ( int i = 0; i < packetSize; i++){
			packet[i + HEADER_SIZE] = maybeCorrupted[i];
		}
		
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
	
	private byte[] calculateAckNumber(){
		byte[] ackNum = new byte[2];
		ackNum[0] = 0;
		ackNum[1] = 0;
		
		return ackNum;
	}
	
	public void transferImage() throws Exception
	{
		/*
		 * 
		 *  Following code taken from
		 *  https://lowell.umassonline.net/bbcswebdav/pid-305360-dt-content-rid-977475_1/courses/L2710-11029/Sockets.pdf
		 * 
		 */
		
		//Socket setup 
		
		clientSocket = new DatagramSocket();	
		byte[] packet;

				
		//Send amount packets to expect to the server
		int num_packets = getNumberOfPacketsToSend( imageName ); //get number of packets in the image
		log( "CLIENT: Going to send " + num_packets + " packets");
		
		//TODO	Make this standard packet size
		//TODO add standard header and sequence number to this packet
		int packet_length = String.valueOf(num_packets).getBytes().length; //length of string version of number of packets
		packet = new byte[packet_length];
		packet = String.valueOf(num_packets).getBytes();
		packet = addPacketHeader(packet);
		transmitPacket(packet, clientSocket);
				
		
		packet = new byte[PACKET_SIZE];
		DatagramPacket receivePacket = new DatagramPacket(packet, packet.length);
		try{
			clientSocket.receive(receivePacket);
		} catch (SocketException e) {
			log("Socket port closed externally");
		}
		//TODO check to see if ACK received OK 
		
		FileInputStream fis = new FileInputStream( imageName );		
		log( "CLIENT: Sending packets");
		
		while(true && !killMe){
			int data_size = fis.available(); //get bytes left to read
			if (data_size > DATA_SIZE){
				data_size = DATA_SIZE; //max 1024 at a time
			}
			else if (data_size == 0){
				break;
			}
			
			byte[] readData = new byte[data_size];
			
			int flag = fis.read(readData); //read data
			if (flag == -1){ //if end of file is reached
				break;
			}

			packet = new byte[data_size + HEADER_SIZE];
			//TODO keep track of sequenceNumber
			//TODO Add seqNum to Header
			packet = addPacketHeader(readData);
			transmitPacket(packet, clientSocket);
			
			//TODO wait for ACK
			//TODO if wrong seq. num in ACK/checksum is corrupt, retransmit, else continue
		}
		
		
		fis.close();
		
		finalize();
}

	@Override
	public void run() {
		try {
			transferImage();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}