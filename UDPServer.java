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
	private void log(String logmsg) 
	{
		if(packetLogging)
		{
			long nowTime = System.currentTimeMillis();
			try {
				out.write(Long.toString(nowTime) + ": " +logmsg + "\r\n");
			} catch (IOException e) {
				System.out.println("Couldn't write to log file. Logging disabled");
				packetLogging = false;
			}
		}
		System.out.println(logmsg);
	}
	
	/*
	 * Called automatically whenever the object is destroyed
	 */
	@Override
	protected void finalize()
	{
		serverSocket.close();
		if(packetLogging)
		{
			try {
				out.close();
			} catch (IOException e) {
				System.out.println("Exception closing log");
			}
		}
	}
	
	/*
	 * Creates a new server
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
	 * Tell the server to stop listening to the port and die asap
	 */
	public void killServer()
	{
		if(serverSocket != null)
			serverSocket.close();
		killMe = true;
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
			corruptedCounter++;
			return null;
		}
			
	}

	
	/*
	 * Returns the sequence number field of the packet.
	 */
	private int getSequenceNumber(byte[] packet)
	{
		return packet[1] + (packet[0] << 8);
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
			log("SERVER: Weird sequence number found: " + Integer.toString(seq));
		}
		return 0;
	}
	
	/*
	 * Given packet data and an ACK number,
	 * make a new packet
	 */
	private byte[] addPacketHeader(byte[] readData, int ackNumber){
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
		
		packet[0] = (byte) ((ackNumber >> 8) & 0xFF); //msbFirst
		packet[1] = (byte) (ackNumber & 0xFF);
		
		assert ( packetSize > PACKET_SIZE );
		packet[4] = (byte) ((packetSize >> 8) & 0xFF); //msbFirst
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
			
			
			/*
			 * 
			 *  Following code taken from
			 *  https://lowell.umassonline.net/bbcswebdav/pid-305360-dt-content-rid-977475_1/courses/L2710-11029/Sockets.pdf
			 * 
			 */
			
			//Receives the first packet
			packet = new byte[PACKET_SIZE];
			int seqNum = 0;
			int expectedSeqNum = 0;
			int oldSeqNum = 0;
			int packets_received = 0;
			int packets_expected = 0;
						
			while(true)
			{
				receivePacket = new DatagramPacket(packet, packet.length);
				try{
					serverSocket.receive(receivePacket);
				} catch (SocketException e) {
					log("Socket port closed externally");
					break;
				}
			
				seqNum = getSequenceNumber(packet);
				expectedSeqNum = seqNum;
				oldSeqNum = getIncrementedSequenceNumber(packet);
				
				//converts the received packet into a String
				data = new String(destructPacket(receivePacket.getData()), "US-ASCII");
				log("SERVER: Received " + data);
							
				packets_expected = Integer.parseInt(data, 10);
				packets_received = 0;
				log("SERVER: Waiting for " + packets_expected + " packets");
				
				IPAddress = receivePacket.getAddress();
				port = receivePacket.getPort();
				
				if(data != null && seqNum == expectedSeqNum)
				{
					//make a new packet with right ACK num and send it
					byte[] sendPacket = addPacketHeader(new byte[DATA_SIZE], seqNum);
					transmitPacket(sendPacket, serverSocket, IPAddress);
					oldSeqNum = seqNum;
					expectedSeqNum = getIncrementedSequenceNumber(packet);
					break;
				} else {
					log("SERVER: back checksum on first packet");
					byte[] sendPacket = addPacketHeader(new byte[DATA_SIZE], oldSeqNum);
					transmitPacket(sendPacket, serverSocket, IPAddress);
					//repeat(don't break) without incrementing state if bad
					
				}
			}
			
			//packet is good, open image file for writing.
			FileOutputStream fos = new FileOutputStream(imageName); //Open output file


			log("SERVER: Ready for packets");
			while ( packets_received < packets_expected && !killMe){
				
				//wait for the client to send something
				packet = new byte[PACKET_SIZE];
				receivePacket = new DatagramPacket(packet, packet.length);
				try{
					serverSocket.receive(receivePacket);
				} catch (SocketException e) {
					log("Socket port closed externally");
					break;
				}
				
				//extracts data. Data is null if checksum is bad.
				seqNum = getSequenceNumber(packet);
				byte[] packetData = destructPacket( packet );
				
				log("SERVER: Got packet:" + seqNum);
				
				//data is not corrupt and has expected sequence number
				if ( packetData != null && seqNum == expectedSeqNum){
					//deliver packet and increment state
					fos.write(packetData);
					//make a new ACK with seqnum= ACK
					log("SERVER: Packet was good, send ACK with " + seqNum);
					byte[] sendPacket = addPacketHeader(new byte[DATA_SIZE], seqNum);
					transmitPacket(sendPacket, serverSocket, IPAddress);
					//Increment state
					oldSeqNum = seqNum;
					expectedSeqNum = getIncrementedSequenceNumber(packet);
					packets_received++; //increment the good packet count
				} else {
					log("SERVER: Bad Checksum or Bad Sequence num:(. Send ACK with " +oldSeqNum);
					//send the old sendPacket with the previous sequence number
					byte[] sendPacket = addPacketHeader(new byte[DATA_SIZE], oldSeqNum);
					transmitPacket(sendPacket, serverSocket, IPAddress);
				}
				
			}
			
			log("SERVER: Got " + packets_received + " packets");
			log("SERVER: " + corruptedCounter + " checksums corrupted :'(");
			
			if(packetLogging)
				out.close();
			fos.close();
			
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