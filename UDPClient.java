package network_design_project;

import java.io.*;
import java.net.*;
public class UDPClient extends NetworkAgent{
		
	long startTime;
	long endTime;

	public UDPClient(String imageName, int port, boolean packetLogging, double corruptionChance)
	{
		super("CLIENT: ", "ClientLog.txt", imageName, port, packetLogging, corruptionChance);
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
		myDatagramSocket = new DatagramSocket();	
		byte[] sendPacket = null;		//packet (with header) sent to the server
		byte[] receivePacket = null; 	//packet (with header) received from the server
		byte[] receivedData = null; 	//unpacked received data 
		
		DatagramPacket receiveDatagram = null;
		int sequenceNumber = 0;
		int receivedAckNumber = 1;
		
		//Send amount packets to expect to the server
		int num_packets = getNumberOfPacketsToSend( imageName ); //get number of packets in the image
		
		
		int packet_length = String.valueOf(num_packets).getBytes("US-ASCII").length; //length of string version of number of packets
		byte[] data = new byte[packet_length];
		data = String.valueOf(num_packets).getBytes("US-ASCII");
		
		//stay in this state until the condition to advance is met
		do
		{
			log( "Going to send " + num_packets + " packets");
			sendPacket = addPacketHeader(data, sequenceNumber);
			
			transmitPacket(sendPacket, myDatagramSocket);
		
			receivePacket = new byte[PACKET_SIZE];
		
			//check to see if ACK received ok
			receiveDatagram = new DatagramPacket(receivePacket, receivePacket.length);
			try{
				myDatagramSocket.receive(receiveDatagram);
			} catch (SocketException e) {
				log("Socket port closed externally");
				
			}
			
			receivedAckNumber = getSequenceNumber(receivePacket);
			log("Received First ACK");
			
			receivedData = destructPacket(receivePacket);
		}while(receivedData == null || receivedAckNumber != sequenceNumber); 
		
		sequenceNumber = getIncrementedSequenceNumber(sendPacket);
		
		
		FileInputStream fis = new FileInputStream( imageName );		
		log( "Sending all data packets");
		
		
		
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
				log("End of file reached. Stop sending");
				break;
			}

			//repeat making/sending this packet until condition is met to advance
			do
			{
				sendPacket = new byte[data_size + HEADER_SIZE];
				sendPacket = addPacketHeader(readData, sequenceNumber);
				
				transmitPacket(sendPacket, myDatagramSocket);
				log("Sent packet: " + sequenceNumber);
				
				//check to see if ACK received ok
				receiveDatagram = new DatagramPacket(receivePacket, receivePacket.length);
				try{
					myDatagramSocket.receive(receiveDatagram);
				} catch (SocketException e) {
					log("Socket port closed externally");
					
				}
				
				receivedAckNumber = getSequenceNumber(receivePacket);
				receivedData = destructPacket(receivePacket);
				log("Received ACK: " + receivedAckNumber);
				if(receivedData !=null){
					//System.out.println(receivedData.length);
				}
				else{
					//System.out.println("Null data");
				}
			}while(receivedData == null || receivedAckNumber != sequenceNumber);
			
			//increment state and continue
			sequenceNumber = getIncrementedSequenceNumber(sendPacket);
			log("Incrementing State");
			
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