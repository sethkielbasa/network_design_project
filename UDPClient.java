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
	
	long startTime;
	long endTime;
	
	DatagramSocket clientSocket;
	boolean packetLogging;
	volatile boolean killMe; //set true to exit as fast as possible
	FileWriter out;
	int corruptedCounter;
	
	/*
	 * If packetLogging is enabled, log messages to file and timestamps them.
	 * Otherwise, puts them out to System.out
	 */
	private void log(String logmsg) 
	{
		if(packetLogging)
		{
			long nowTime = System.currentTimeMillis();
			try {
				out.write(Long.toString(nowTime) + ": " +logmsg + "\r\n");
			} catch (IOException e) {
				//System.out.println("Couldn't write to log file. Logging disabled");
				packetLogging = false;
				e.printStackTrace();
			}
		}
		
		//System.out.println(logmsg);
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
	
	/*
	 * Given a packet, assuming its checksum is good,
	 * return the next sequence number to expect from the client
	 */
	private int getIncrementedSequenceNumber(byte[] packet)
	{
		int seq = getSequenceNumber(packet);
		if(seq == 0)
		{
			return 1;
		}
		else if(seq == 1)
		{
			return 0;
		}
		else
		{
			log("CLIENT: Weird sequence number found: " + Integer.toString(seq));
		}
		return 0;
	}
	
	public UDPClient(String image, int portNum, boolean logging, double corruptionChance)
	{
		port = portNum;
		imageName = image;
		packetLogging = logging;
		this.corruptionChance = corruptionChance;
		
		corruptedCounter = 0;
		
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
	
	/*
	 * Returns the sequence number field of the packet.
	 */
	private int getSequenceNumber(byte[] packet)
	{
		return packet[1] + (packet[0] << 8);
	}	
	
	private int getNumberOfPacketsToSend(String file_to_send) throws IOException{
		int number_of_packets = 0;
		//System.out.println(file_to_send);
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
	
	private byte[] addPacketHeader(byte[] readData, int ackNumber ){
		int packetSize = readData.length;
		byte[] packet = new byte[packetSize + HEADER_SIZE];
		
		//copies over data from maybe corrupted data
		log(String.valueOf(corruptionChance));
		byte[] maybeCorrupted = corruptDataMaybe(readData, corruptionChance);
		for ( int i = 0; i < packetSize; i++){
			packet[i + HEADER_SIZE] = maybeCorrupted[i];
		}
		
		byte[] checksum = new byte[2];
		checksum = calculateChecksum( readData , true );
		packet[2] = checksum[0];
		packet[3] = checksum[1];
		
		packet[0] = (byte) ((ackNumber >> 8) & 0xFF); //msbFirst
		packet[1] = (byte) (ackNumber & 0xFF);
		
		assert ( packetSize > PACKET_SIZE );
		packet[4] = (byte) ((packetSize >> 8) & 0xFF);
		packet[5] = (byte) (packetSize & 0xFF);
		
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
	
	/*
	 * Send a packet 
	 */
	private void transmitPacket(byte[] packet, DatagramSocket socket, InetAddress IPAddress ) throws Exception{
		DatagramPacket sendPacket = new DatagramPacket(packet, packet.length, IPAddress, port);
		socket.send(sendPacket);
	}
	
	/*
	 * Extract the data from a packet.
	 * Also compute its checksum and calculate if it is bad.
	 * Returns null if the checksum is bad
	 */
	private byte[] destructPacket ( byte[] packet ){
		
		byte[] checksum = new byte[2];
		int packetLength = getPacketLength( packet);
		
		byte[] data = new byte[packetLength];	
		for( int i = 0; i < packetLength; i++){
			data[i] = packet[ i + HEADER_SIZE ];
		}
		
		checksum = calculateChecksum( data, false );
		if( (~(packet[2] ^ checksum[0]) == 0) && (~(packet[3] ^ checksum[1]) == 0) ){
			return data;
		} else {
			log("CLIENT: Checksum failed");
			corruptedCounter++;
			return null;
		}
			
	}
	
	/*
	 * Returns the packetLength field of the packet header
	 */
	private int getPacketLength(byte[] packet)
	{
		int packetLength = packet[4] & 0xFF;
		packetLength = packetLength << 8;
		packetLength = packetLength | (packet [5] & 0xFF);
		return packetLength;
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

	
	public void transferImage() throws Exception
	{
		/*
		 * 
		 *  Following code taken from
		 *  https://lowell.umassonline.net/bbcswebdav/pid-305360-dt-content-rid-977475_1/courses/L2710-11029/Sockets.pdf
		 * 
		 */
		
		//Socket setup 
		startTime = System.currentTimeMillis();
		clientSocket = new DatagramSocket();	
		byte[] sendPacket = null;		//packet (with header) sent to the server
		byte[] receivePacket = null; 	//packet (with header) received from the server
		byte[] receivedData = null; 	//unpacked received data 
		
		DatagramPacket receiveDatagram = null;
		int sequenceNumber = 0;
		int oldSequenceNumber = 1;
		int receivedAckNumber = 1;
		
		//Send amount packets to expect to the server
		int num_packets = getNumberOfPacketsToSend( imageName ); //get number of packets in the image
		
		
		int packet_length = String.valueOf(num_packets).getBytes("US-ASCII").length; //length of string version of number of packets
		byte[] data = new byte[packet_length];
		data = String.valueOf(num_packets).getBytes("US-ASCII");
		
		//stay in this state until the condition to advance is met
		do
		{
			log( "CLIENT: Going to send " + num_packets + " packets");
			sendPacket = addPacketHeader(data, sequenceNumber);
			oldSequenceNumber = getIncrementedSequenceNumber(sendPacket);
			
			transmitPacket(sendPacket, clientSocket);
		
			receivePacket = new byte[PACKET_SIZE];
		
			//check to see if ACK received ok
			receiveDatagram = new DatagramPacket(receivePacket, receivePacket.length);
			try{
				clientSocket.receive(receiveDatagram);
			} catch (SocketException e) {
				log("Socket port closed externally");
				
			}
			
			receivedAckNumber = getSequenceNumber(receivePacket);
			log("CLIENT: Received First ACK");
			
			receivedData = destructPacket(receivePacket);
		}while(receivedData == null || receivedAckNumber != sequenceNumber); 
		
		oldSequenceNumber = sequenceNumber;
		sequenceNumber = getIncrementedSequenceNumber(sendPacket);
		
		
		FileInputStream fis = new FileInputStream( imageName );		
		log( "CLIENT: Sending all data packets");
		
		
		
		while(true && !killMe){
			int data_size = fis.available(); //get bytes left to read
			if (data_size > DATA_SIZE){
				data_size = DATA_SIZE; //max 1024 at a time
			}
			else if (data_size == 0){
				log("End of data available. Break");
				break;
			}
			
			byte[] readData = new byte[data_size];
			
			//read data
			if (fis.read(readData) == -1) //if end of file is reached
			{
				log("CLIENT: End of file reached. Stop sending");
				break;
			}

			//repeat making/sending this packet until condition is met to advance
			do
			{
				sendPacket = new byte[data_size + HEADER_SIZE];
				sendPacket = addPacketHeader(readData, sequenceNumber);
				
				transmitPacket(sendPacket, clientSocket);
				log("CLIENT: Sent packet: " + sequenceNumber);
				
				//check to see if ACK received ok
				receiveDatagram = new DatagramPacket(receivePacket, receivePacket.length);
				try{
					clientSocket.receive(receiveDatagram);
				} catch (SocketException e) {
					log("Socket port closed externally");
					
				}
				
				receivedAckNumber = getSequenceNumber(receivePacket);
				receivedData = destructPacket(receivePacket);
				log("CLIENT: Received ACK: " + receivedAckNumber);
				if(receivedData !=null){
					//System.out.println(receivedData.length);
				}
				else{
					//System.out.println("Null data");
				}
			}while(receivedData == null || receivedAckNumber != sequenceNumber);
			
			//increment state and continue
			oldSequenceNumber = sequenceNumber;
			sequenceNumber = getIncrementedSequenceNumber(sendPacket);
			log("CLIENT: Incrementing State");
			
		}
		
		
		fis.close();
		endTime = System.currentTimeMillis() - startTime;
		System.out.println("Time : " + endTime);
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