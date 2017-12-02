package network_design_project;

import java.io.FileWriter;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.LinkedList;

/*
 * Superclass for UDPClient and UDPServer
 * 
 * Holds functions that we use in both classes
 */
public abstract class NetworkAgent implements Runnable {
	
	
	//////////Constants		 
	
	final int TCP_HEADER_SIZE = 20;
	final int HEADER_SIZE = 6;
	final int PACKET_SIZE = 1024;
	final int DATA_SIZE = PACKET_SIZE - HEADER_SIZE;
	
	
	//////////instance variables
	
	
	String imageName;
	int port;
	int corruptedCounter;
	double corruptionChance;
	double dropChance;
	boolean packetLogging;
	String logPrefix;
	FileWriter out;
	volatile boolean killMe; //set true to exit as fast as possible
	DatagramSocket myDatagramSocket;
	
	//GBN/SR/TCP variables
	LinkedList<byte[]> window;
	int windowSize;
	int windowBase; //sequence number at the base of the window
	int nextSeqNum; //sequence number of the next packet in the window to get handled.
	
	//////////shared functions
	
	NetworkAgent(String logPrefix, String logFn, String imageName, int port, 
			boolean packetLogging, double corruptionChance, double dropChance)
	{
		this.logPrefix = logPrefix;
		this.port = port;
		this.imageName = imageName;
		this.packetLogging = packetLogging;
		this.corruptionChance = corruptionChance;
		this.dropChance = dropChance;
		
		corruptedCounter = 0;
		
		killMe = false;

		if(packetLogging)
		{
			try {
				out = new FileWriter(logFn);
				out.write("Writing " + logPrefix + " packet traffic:\r\n\r\n");
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
	
	/*
	 * If packetLogging is enabled, log messages to file and timestamps them.
	 * Otherwise, puts them out to System.out
	 */
    void log(String logmsg) 
	{
		if(packetLogging)
		{
			long nowTime = System.currentTimeMillis();
			try {
				out.write(Long.toString(nowTime) + ": " + logPrefix + logmsg + "\r\n");
				//System.out.println(logmsg);
			} catch (IOException e) {
				//System.out.println("Couldn't write to log file. Logging disabled");
				packetLogging = false;
			}
		}
	}
	
	/*
	 * Called automatically whenever the object is destroyed
	 */
	@Override
	protected void finalize()
	{
		if(myDatagramSocket != null && !myDatagramSocket.isClosed())
			myDatagramSocket.close();
		
		if(packetLogging)
		{
			try {
				out.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		System.out.println("Finished " + logPrefix);
	}
	
	/*
	 * Returns the packetLength field of the packet header
	 */
	int getPacketLength(byte[] packet)
	{
		int packetLength = packet[4] & 0xFF;
		packetLength = packetLength << 8;
		packetLength = packetLength | (packet [5] & 0xFF);
		return packetLength;
	}
		
	/*
	 * Tell the server to stop listening to the port and die asap
	 */
	public void killThisAgent()
	{
		if(myDatagramSocket != null)
			myDatagramSocket.close();
		killMe = true;
	}
	
	/*
	 * Returns the sequence number field of the packet.
	 */
	int getSequenceNumber(byte[] packet)
	{
		return packet[1] + (packet[0] << 8);
	}	
	
	/*
	 * Given a packet, assuming its checksum is good,
	 * return the next sequence number to expect from the client
	 */
	int getIncrementedSequenceNumber(byte[] packet)
	{
		int seq = getSequenceNumber(packet);
		return seq++;
	}
	
	/*
	 * Calculates the checksum over a given data array.
	 * If invertFlag is true, do the one's compliment over the checksum
	 */
	byte[] calculateChecksum( byte[] readData, boolean invertFlag ){
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
	 * Given packet data and an ACK number,
	 * make a new packet
	 */
	
	//TODO: add this in later
	public byte[] addTCPHeader(byte[] readData, 
			int sourcePort, int destinationPort,
			int sequenceNumber, int ackNumber,
			int TCPflags, int windowSize, int urgent){
		
		int packetSize = readData.length;
		byte[] packet = new byte[packetSize + TCP_HEADER_SIZE];
		
		
		//set source port, 0:1
		packet[0] = (byte) ((sourcePort >> 8) & 0xFF); //msbFirst
		packet[1] = (byte) (sourcePort & 0xFF);
		
		//set destination port, 2:3
		packet[2] = (byte) ((destinationPort >> 8) & 0xFF); //msbFirst
		packet[3] = (byte) (destinationPort & 0xFF);
		
		//sequnce number 4:7
		packet[4] = (byte) ((sequenceNumber >> 8) & 0xFF); //msbFirst
		packet[5] = (byte) ((sequenceNumber >> 8) & 0xFF); //msbFirst
		packet[6] = (byte) ((sequenceNumber >> 8) & 0xFF); //msbFirst
		packet[7] = (byte) (sequenceNumber & 0xFF);

		//ack number 8:11
		packet[8] = (byte) ((ackNumber >> 8) & 0xFF); //msbFirst
		packet[9] = (byte) ((ackNumber >> 8) & 0xFF); //msbFirst
		packet[10] = (byte) ((ackNumber >> 8) & 0xFF); //msbFirst
		packet[11] = (byte) (ackNumber & 0xFF);
		
		
		byte[] flags = new byte[2];					 // 12:13
		flags = doFlagStuff(TCPflags);
		packet[12] = flags[0];
		packet[13] = flags[1];
		
		 // set window size, 14:15
		packet[14] = (byte) ((windowSize >> 8) & 0xFF); //msbFirst
		packet[15] = (byte) (windowSize & 0xFF);	
		byte[] checksum = new byte[2];		
		
		// checksum, 16:17
		checksum = calculateChecksum( readData , true );
		packet[16] = checksum[0];
		packet[17] = checksum[1];
		
		//set urgent 18:19
		packet[18] = (byte) ((urgent >> 8) & 0xFF); //msbFirst
		packet[19] = (byte) (urgent & 0xFF);
		
		
		return packet;
	}
	
	byte[] doFlagStuff(int flags){
		byte[] flagStuff = new byte[2];		
		flagStuff[0] = 0x50; // 0x0101000X, set upper 7bits, offset = 5	
		flagStuff[0] = (byte) (flagStuff[0] | ((flags >> 8) & 0x1)); //set lower bit
		
		flagStuff[1] = 0;
		flagStuff[1] = (byte)( flags & 0xFF); //8 lower bits of flags
		
		return flagStuff;
	}
	
	byte[] addPacketHeader(byte[] readData, int ackNumber){
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
	
	/*
	 * Send a packet
	 */
	void transmitPacket(byte[] packet, DatagramSocket socket, InetAddress IPAddress ) throws Exception{
		DatagramPacket sendPacket = new DatagramPacket(packet, packet.length, IPAddress, port);
		socket.send(sendPacket);
	}
	void transmitPacket(byte[] packet, DatagramSocket socket ) throws Exception{
		InetAddress IPAddress = InetAddress.getByName("localhost");		
		DatagramPacket sendPacket = new DatagramPacket(packet, packet.length, IPAddress, port);
		socket.send(sendPacket);
	}
	
	/*
	 * Extract the data from a packet.
	 * Also compute its checksum and calculate if it is bad.
	 * Returns null if the checksum is bad
	 */
	byte[] destructPacket (byte[] packet){
		
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
	 * Rolls the dice and corrupts the data (adds a random bit flip) percentChance% of the time
	 * Returns the a new copy of data that might have a bit error
	 */
	byte[] corruptDataMaybe(byte[] data, double percentChance){
		byte[] newData = data.clone();
		if( Math.random()*100 < percentChance ){
			log("Corrupting this packet");
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
	
	boolean dropPacket(double percentChance){
		if(Math.random()*100 < percentChance){
			return true;
		} else {
			return false;
		}
	}
}
