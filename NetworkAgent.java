package network_design_project;

import java.io.FileWriter;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.LinkedList;
import java.util.concurrent.locks.Lock;

/*
 * Superclass for UDPClient and UDPServer
 * 
 * Holds functions that we use in both classes
 */
public abstract class NetworkAgent implements Runnable {
	
	
	public enum State{
		INIT, OPEN, LISTEN, CLOSED, SYN_SENT, SYN_RCVD, ESTABLISHED, FIN_WAIT_1, FIN_WAIT_2, 
		CLOSE_WAIT, CLOSING, LAST_ACK, TIME_WAIT
	}
	
	//////////Constants		 
	
	final int TCP_HEADER_BYTES = 24;
	final int TCP_HEADER_WORDS = 6;
	final int HEADER_SIZE = 6;
	final int PACKET_SIZE = 1024;
	final int DATA_SIZE = PACKET_SIZE - TCP_HEADER_BYTES;
	
	
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
	DatagramSocket datagramSocket;
	
	//GBN/SR/TCP variables. All protected by a lock
	Lock windowLock;
	LinkedList<byte[]> window;
	int windowSize;
	int windowBase; //sequence number at the base of the window
	int nextSeqNum; //sequence number of the next packet in the window to get handled.
	
	//////////shared functions
	
	NetworkAgent(String logPrefix, String logFn, String imageName, int port, 
			boolean packetLogging, double corruptionChance, double dropChance, int windowSize)
	{
		this.logPrefix = logPrefix;
		this.port = port;
		this.imageName = imageName;
		this.packetLogging = packetLogging;
		this.corruptionChance = corruptionChance;
		this.dropChance = dropChance;
		this.windowSize = windowSize;
		
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
				System.out.println(logmsg);
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
		if(datagramSocket != null && !datagramSocket.isClosed())
			datagramSocket.close();
		
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
		if(datagramSocket != null)
			datagramSocket.close();
		killMe = true;
	}
	
	/*
	 * Returns the sequence number field of the packet.
	 */
	int getSequenceNumber(byte[] packet)
	{		
		int temp = ((packet[0] & 0xFF) << 8) + (packet[1] & 0xFF);
		return temp;
	}	
	
	/*
	 * Given a packet, assuming its checksum is good,
	 * return the next sequence number to expect from the client
	 */
	int getIncrementedSequenceNumber(byte[] packet)
	{
		int seq = getSequenceNumber(packet);
		log("seq =" + seq);
		return seq + 1;
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
	byte[] addTCPPacketHeader(byte[] readData,
			int source_port, int destination_port,
			int sequence_number, int ack_number,
			int flags, int window_size, int urgent_pointer,
			int length){
		int packetSize = readData.length;
		byte[] packet = new byte[packetSize + TCP_HEADER_BYTES];
		
		
		//copies maybe-corrupted into the new packet
		for ( int i = 0; i < packetSize; i++){
			packet[i + HEADER_SIZE] = readData[i];
		}
		
		packet[0] = (byte) ((source_port >> 8) & 0xFF);
		packet[1] = (byte) (source_port & 0xFF);
		
		packet[2] = (byte) ((destination_port >> 8) & 0xFF);
		packet[3] = (byte) (destination_port & 0xFF);
		
		packet[4] = (byte) ((sequence_number >> 24) & 0xFF);
		packet[5] = (byte) ((sequence_number >> 16) & 0xFF);
		packet[6] = (byte) ((sequence_number >> 8) & 0xFF);
		packet[7] = (byte) (sequence_number & 0xFF);
		
		packet[8] = (byte)  ((ack_number >> 24) & 0xFF);
		packet[9] = (byte)  ((ack_number >> 16) & 0xFF);
		packet[10] = (byte) ((ack_number >> 8) & 0xFF);
		packet[11] = (byte) (ack_number & 0xFF);
		
		byte[] tcp_flags = convertTCPFlags( flags );
		packet[12] = tcp_flags[0];
		packet[13] = tcp_flags[1];
		
		packet[14] = (byte) ((window_size >> 8) & 0xFF);
		packet[15] = (byte) (window_size & 0xFF);
		
		byte[] checksum = new byte[2];
		checksum = calculateChecksum( readData , true );
		packet[16] = checksum[0];
		packet[17] = checksum[1];

		packet[18] = (byte) ((urgent_pointer >> 8) & 0xFF);
		packet[19] = (byte) (urgent_pointer & 0xFF);

		packet[20] = (byte) ((length >> 24) & 0xFF);
		packet[21] = (byte) ((length >> 16) & 0xFF);
		packet[22] = (byte) ((length >> 8) & 0xFF);
		packet[23] = (byte) (length & 0xFF);
		
		return packet;
	}
	
	byte[] convertTCPFlags(int flags){
		byte[] tcp_flags = new byte[2];
		
		tcp_flags[0] = (byte) ((flags >> 8) & 0xFF);
		tcp_flags[1] = (byte) (flags & 0xFF);
		
		return tcp_flags;
	}
	
	int getTCPFlags(State state){
		int flags = TCP_HEADER_WORDS;
		flags = flags << 12;
		switch(state){
		case OPEN:
			flags = flags | 0x2; //Turn SYN flag on
			break;
		case SYN_RCVD:
			flags = flags | 0x12; //Turn SYN-ACK flag on
			break;
		case SYN_SENT:
			flags = flags | 0x10; //Turn ACK flag on
			break;
		case ESTABLISHED:
			flags = flags | 0x00;
			break;
		default:
			log("Hit default state in getTCPFlags()");
			System.exit(0);
		}
		return flags;
	}
	
	int extractSequenceNumber(byte[] packet){
		int temp = packet[4] & 0xFF;
		temp = (temp << 8) | (packet[5] & 0xFF);
		temp = (temp << 8) | (packet[6] & 0xFF);
		temp = (temp << 8) | (packet[7] & 0xFF);
		
		return temp;
	}
		
	int extractAckNumber(byte[] packet){
		
		int temp = packet[8] & 0xFF;
		temp = (temp << 8) | (packet[9] & 0xFF);
		temp = (temp << 8) | (packet[10] & 0xFF);
		temp = (temp << 8) | (packet[11] & 0xFF);
		
		return temp;
	}
		
		
	boolean checkTCPFlags(byte[] packet, State state){
		//Pull tcp flags from packet
		int rcvd_tcp_flags = 0;
		rcvd_tcp_flags = ((packet[12] & 0xFF) << 8 ) + (packet[13] & 0xFF);
		
		int flags = TCP_HEADER_WORDS;
		flags = flags << 12;
		
		switch(state){
		case SYN_SENT:
			flags = flags | 0x12; // SYN-ACK
			if( rcvd_tcp_flags == flags )
				return true;
			else
				return false;
			
		case LISTEN:
			flags = flags | 0x2; // SYN
			if( rcvd_tcp_flags == flags )
				return true;
			else
				return false;
			
		case SYN_RCVD:
			flags = flags | 0x10;
			if( rcvd_tcp_flags == flags )
				return true;
			else
				return false;
		default:
			log("--------------------------  Hit Default in checkTCPFlags() : " + state );
			System.exit(0);
			return false;
		}
	}
	
	int getPacketLength(State state){
		switch(state){
		case OPEN:
		case SYN_SENT:
		case SYN_RCVD:
			return 0;
		default:
			log("Hit default state in getPacketLength()");
			System.exit(0);
			return -1;		
		}
	}
	
	int getAvailableDataSize(int data_size){
		if (data_size > DATA_SIZE){
			data_size = DATA_SIZE; //max 1024 at a time
		}
		else if (data_size == 0){
			log("End of data available. Break");
		}
		return data_size;
	}

	void transmitPacket(byte[] packet, DatagramSocket socket, InetAddress IPAddress ) throws Exception{
		DatagramPacket sendPacket = new DatagramPacket(packet, packet.length, IPAddress, port);
		socket.send(sendPacket);
	}
	void transmitPacket(byte[] packet, DatagramSocket socket, int dst_port ) throws Exception{
		InetAddress IPAddress = InetAddress.getByName("localhost");		
		DatagramPacket sendPacket = new DatagramPacket(packet, packet.length, IPAddress, dst_port);
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
	
	//maybe send a packet on the dataGram socket depending on drop Chance
	void unreliableSendPacket(byte[] sendPacket, int dst_port) throws Exception
	{
		if(dropPacket(dropChance)){
			log("URDropped packet: " + getSequenceNumber(sendPacket));
		} else {
			transmitPacket(corruptDataMaybe(sendPacket, corruptionChance), datagramSocket, dst_port);
			//log("URSent packet: " + extractSequenceNumber(sendPacket));
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
