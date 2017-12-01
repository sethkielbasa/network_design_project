package network_design_project;
import java.io.*;
import java.net.*;


public class UDPServer extends NetworkAgent{	
		
	
	/*
	 * Creates a new server
	 * if logging is enabled, creates a new log file and writes packet messages to them.
	 */
	public UDPServer(String imageName, int port, boolean packetLogging, double corruptionChance, double dropChance)
	{
		super("SERVER: ", "ServerLog.txt", imageName, port, packetLogging, corruptionChance, dropChance);
	}
		
	public void receiveImage() throws Exception
	{
		/*
		 * 
		 *  Following code taken from
		 *  https://lowell.umassonline.net/bbcswebdav/pid-305360-dt-content-rid-977475_1/courses/L2710-11029/Sockets.pdf
		 * 
		 */
			
		myDatagramSocket = new DatagramSocket(port);
		byte[] packet = new byte[PACKET_SIZE];
		
		DatagramPacket receiveDatagram = null;
		InetAddress IPAddress = null;
		String data;
			
		packet = null;
		receiveDatagram = null;
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
		byte[] sendPacket = null;
		int seqNum = 0;
		int expectedSeqNum = 0;
		int packets_received = 0;
		int packets_expected = 0;
				
		//keep trying to receive the first packet
		while(killMe == false)
		{
			receiveDatagram = new DatagramPacket(packet, packet.length);
			try{
				myDatagramSocket.receive(receiveDatagram);
			} catch (SocketException e) {
				log("Socket port closed externally");
				break;
			}
		
			seqNum = getSequenceNumber(packet);
			expectedSeqNum = seqNum;
			
			//converts the received packet into a String
			if(destructPacket(receiveDatagram.getData()) != null){
				data = new String(destructPacket(receiveDatagram.getData()), "US-ASCII");
				packets_expected = Integer.parseInt(data, 10);
			}
			log("Received " + data);
						
			
			packets_received = 0;
			log("Waiting for " + packets_expected + " packets");
			
			IPAddress = receiveDatagram.getAddress();
			port = receiveDatagram.getPort();
			
			if(data != null && seqNum == expectedSeqNum)
			{
				//make a new packet with right ACK num and send it
				sendPacket = addPacketHeader(new byte[DATA_SIZE], seqNum);
				unreliableSendPacket(sendPacket);
				expectedSeqNum = getIncrementedSequenceNumber(packet);
				break;
			} else {
				log("bad checksum on first packet");
				sendPacket = addPacketHeader(new byte[DATA_SIZE], getIncrementedSequenceNumber(packet));
				if(dropPacket(dropChance)){
					log("ACK packet dropped");
				} else {
					unreliableSendPacket(sendPacket);
				}
				//repeat(don't break) without incrementing state if bad
				
			}
		}
			
		//packet is good, open image file for writing.
		FileOutputStream fos = new FileOutputStream(imageName); //Open output file

		log("Ready for packets"); //+1 is crucial.... If the last ACK message the server sends is corrupt, the client may ping it back
		while ( packets_received < packets_expected+1 && !killMe){
			
			
			//wait for the client to send something
			packet = new byte[PACKET_SIZE];
			receiveDatagram = new DatagramPacket(packet, packet.length);
			try{
				myDatagramSocket.receive(receiveDatagram);
			} catch (SocketException e) {
				log("Socket port closed externally");
				break;
			}
				
			//extracts data. Data is null if checksum is bad.
			seqNum = getSequenceNumber(packet);
			byte[] packetData = destructPacket( packet );

			log("Got packet:" + seqNum);
				
			//data is not corrupt and has expected sequence number
			if ( packetData != null && seqNum == expectedSeqNum){
				//deliver packet 
				fos.write(packetData);
				//make a new ACK with seqnum= ACK
				log("Packet was good, send ACK with " + seqNum);
				sendPacket = addPacketHeader(new byte[DATA_SIZE], seqNum);
					
				if(dropPacket(dropChance)){
						log("ACK packet dropped");
				} else {
					unreliableSendPacket(sendPacket);
				}
					
				//Increment state
				expectedSeqNum = getIncrementedSequenceNumber(packet);
				packets_received++; //increment the good packet count
				log("packet number " + packets_received);
			} else {
				if(packetData == null)
					log("Corrupt packetData");
				log("Bad Checksum or Bad Sequence num :(. Send ACK with " + getSequenceNumber(sendPacket));
				//send the old sendPacket with the previous sequence number
				if(dropPacket(dropChance)){
					log("ACK packet dropped");
				} else {
					unreliableSendPacket(sendPacket);
				}
			}
				
		}
			
		log("Got " + packets_received + " packets");
		log(corruptedCounter + " checksums corrupted :'(");
		//save the image
		fos.close();
		finalize();
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