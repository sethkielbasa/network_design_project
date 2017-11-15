package network_design_project;

import java.io.FileWriter;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/*
 * Superclass for UDPClient and UDPServer
 * 
 * Holds functions that we use in both classes
 */
public abstract class NetworkAgent implements Runnable {
	
	
	//////////Constants		 
	
	
	final int HEADER_SIZE = 6;
	final int PACKET_SIZE = 1024;
	final int DATA_SIZE = PACKET_SIZE - HEADER_SIZE;
	
	
	//////////instance variables
	
	
	String imageName;
	int port;
	int corruptedCounter;
	double corruptionChance;
	boolean packetLogging;
	String logPrefix;
	FileWriter out;
	volatile boolean killMe; //set true to exit as fast as possible
	DatagramSocket myDatagramSocket;
	
	
	//////////shared functions
	
	NetworkAgent(String logPrefix, String logFn, String imageName, int port, boolean packetLogging, double corruptionChance)
	{
		this.logPrefix = logPrefix;
		this.port = port;
		this.imageName = imageName;
		this.packetLogging = packetLogging;
		this.corruptionChance = corruptionChance;
		
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
			log("Weird sequence number found: " + Integer.toString(seq));
		}
		return 0;
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
}
