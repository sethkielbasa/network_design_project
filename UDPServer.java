package network_design_project;
import java.io.*;
import java.net.*;


public class UDPServer extends NetworkAgent{	
		
	
	/*
	 * Creates a new server
	 * if logging is enabled, creates a new log file and writes packet messages to them.
	 */
	public UDPServer(String imageName, int port, boolean packetLogging, double corruptionChance)
	{
		super("SERVER: ", "ServerLog.txt", imageName, port, packetLogging, corruptionChance);
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
					myDatagramSocket.receive(receivePacket);
				} catch (SocketException e) {
					log("Socket port closed externally");
					break;
				}
			
				seqNum = getSequenceNumber(packet);
				expectedSeqNum = seqNum;
				oldSeqNum = getIncrementedSequenceNumber(packet);
				
				//converts the received packet into a String
				if(destructPacket(receivePacket.getData()) != null){
					data = new String(destructPacket(receivePacket.getData()), "US-ASCII");
					packets_expected = Integer.parseInt(data, 10);
				}
				log("Received " + data);
							
				
				packets_received = 0;
				log("Waiting for " + packets_expected + " packets");
				
				IPAddress = receivePacket.getAddress();
				port = receivePacket.getPort();
				
				if(data != null && seqNum == expectedSeqNum)
				{
					//make a new packet with right ACK num and send it
					byte[] sendPacket = addPacketHeader(new byte[DATA_SIZE], seqNum);
					transmitPacket(sendPacket, myDatagramSocket, IPAddress);
					oldSeqNum = seqNum;
					expectedSeqNum = getIncrementedSequenceNumber(packet);
					break;
				} else {
					log("bad checksum on first packet");
					byte[] sendPacket = addPacketHeader(new byte[DATA_SIZE], oldSeqNum);
					transmitPacket(sendPacket, myDatagramSocket, IPAddress);
					//repeat(don't break) without incrementing state if bad
					
				}
			}
			
			//packet is good, open image file for writing.
			FileOutputStream fos = new FileOutputStream(imageName); //Open output file


			log("Ready for packets");
			while ( packets_received < packets_expected+1 && !killMe){
				
				//wait for the client to send something
				packet = new byte[PACKET_SIZE];
				receivePacket = new DatagramPacket(packet, packet.length);
				try{
					myDatagramSocket.receive(receivePacket);
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
					//deliver packet and increment state
					fos.write(packetData);
					//make a new ACK with seqnum= ACK
					log("Packet was good, send ACK with " + seqNum);
					byte[] sendPacket = addPacketHeader(new byte[DATA_SIZE], seqNum);
					transmitPacket(sendPacket, myDatagramSocket, IPAddress);
					//Increment state
					oldSeqNum = seqNum;
					expectedSeqNum = getIncrementedSequenceNumber(packet);
					packets_received++; //increment the good packet count
					log("packet number " + packets_received);
				} else {
					log("Bad Checksum or Bad Sequence num :(. Send ACK with " + oldSeqNum);
					//send the old sendPacket with the previous sequence number
					byte[] sendPacket = addPacketHeader(new byte[DATA_SIZE], oldSeqNum);
					transmitPacket(sendPacket, myDatagramSocket, IPAddress);
				}
				
			}
			
			log("Got " + packets_received + " packets");
			log(corruptedCounter + " checksums corrupted :'(");
			
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