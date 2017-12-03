package network_design_project;

import java.io.*;
import java.net.*;
import java.util.LinkedList;
import java.util.concurrent.locks.ReentrantLock;
public class UDPClient extends NetworkAgent{
		
	long startTime;
	long endTime;
	
	int CLIENT_TIMEOUT;
	
	int INIT = 0;
	int SEND_PACKET = 1;
	int WAIT = 2;
	
	int CLIENT_STATE = INIT;
	

	public UDPClient(String imageName, int port, boolean packetLogging, double corruptionChance, double dropChance, int timeOut, int windowSize)
	{
		super("CLIENT: ", "ClientLog.txt", imageName, port, packetLogging, corruptionChance, dropChance, windowSize);
		CLIENT_TIMEOUT = timeOut;
		
		windowLock = new ReentrantLock();
		window = new LinkedList<byte[]>();
		windowBase = 0;
		nextSeqNum = 0;
		System.out.println(timeOut);
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
		int receivedAckNumber = 1;
		
		//Send amount packets to expect to the server
		int num_packets = getNumberOfPacketsToSend( imageName ); //get number of packets in the image

		int packet_length = String.valueOf(num_packets).getBytes("US-ASCII").length; //length of string version of number of packets
		byte[] data = new byte[DATA_SIZE];
		data = String.valueOf(num_packets).getBytes("US-ASCII");
		
		//keep sending the first packet until it is ack'd
		//no GBN here
		do
		{
			log( "Going to send " + num_packets + " packets");
			sendPacket = addPacketHeader(data, nextSeqNum);
					
			unreliableSendPacket(sendPacket);
			
			//check to see if ACK received ok
			receivePacket = new byte[PACKET_SIZE];
			myDatagramSocket.setSoTimeout(CLIENT_TIMEOUT);
			receiveDatagram = new DatagramPacket(receivePacket, receivePacket.length);
			try{
				myDatagramSocket.receive(receiveDatagram);
			} catch (SocketException e) {
				log("Socket port closed externally");
			} catch (InterruptedIOException e){
				log("Client timeout");
			}
			
			receivedAckNumber = getSequenceNumber(receivePacket);
			log("Received First ACK");
			
			receivedData = destructPacket(receivePacket);
		}while(receivedData == null || receivedAckNumber != nextSeqNum); 
		
		//start doing GBN. Init the window, start the receiver thread.
		log( "Sending all data packets");
		
		windowLock.lock();
		nextSeqNum = getIncrementedSequenceNumber(sendPacket);
		windowBase = nextSeqNum; //start the window
		windowLock.unlock();
		
		FileInputStream fis = new FileInputStream( imageName );		
		
		Thread receiverThread = new Thread(new ReceiverRunner()); //thread to receive packets concurrently
		receiverThread.start();
		
		//make packets and send until I'm out of data
		while(true && !killMe){
			//make a new packet
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
			
			//try sending it. If the window is full, wait and try again
			while(!rdtSend(readData))
			{
				Thread.sleep(1); 
			}
			
		}
		
		killThisAgent();
		receiverThread.join();
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
	
	/*
	 * sends the data given.
	 * Returns true if it could send it off, and false if it can't do anything with the data right now.
	 */
	boolean rdtSend(byte[] data) throws Exception  
	{
		windowLock.lock(); //protect the window from mutual access w/ receiver
		try{	
			if(window.size() < windowSize)
			{
				//make packet and add it to the window
				byte[] sendPacket = new byte[data.length + HEADER_SIZE];
				sendPacket = addPacketHeader(data, nextSeqNum);
				
				window.add(sendPacket);
				//if sending first in the window, start the timer
				if(windowBase == nextSeqNum)
				{
					myDatagramSocket.setSoTimeout(CLIENT_TIMEOUT);
					log("Started reset timer");
				}
				nextSeqNum = getIncrementedSequenceNumber(sendPacket);
			
				//send the packet
				unreliableSendPacket(sendPacket);
				return true;
			}
			else
			{
				log("Had to refuse data. Window full");
				if(killMe)
					return true; //pretend it send just so you can die
				return false;
			}
		} finally {
			windowLock.unlock(); //unlock the lock no matter what
		}
		
	}
	
	//call on receiver timeout.
	//resends all of the packets in the window up to nextSeqNum
	void handleTimeout() 
	{
		log("HandleTimeout Called");
		try {
			myDatagramSocket.setSoTimeout(CLIENT_TIMEOUT);
		} catch (SocketException e) {
			log("Error resetting timer after timeout");
			killThisAgent();
			e.printStackTrace();
		}
		
		windowLock.lock();
		try{
			//walk through the window and send everything from the base up to the next sequence number if appropriate
			if(window.isEmpty())
				return;
			log("Handling receiver timeout.-- \n\t windowBase: " + windowBase + " nextSeq:" + nextSeqNum);
			for(int i = 0; getSequenceNumber(window.get(i)) < nextSeqNum-1; i++)
			{
				try {
					byte[] thisPacket = window.get(i);
					log("goBN-ing, in window: " + (windowBase + i));
					log("goBN-ing, sequence number: " + (getSequenceNumber(thisPacket)));
					unreliableSendPacket(thisPacket);
				} catch (Exception e) {        
					log("issues sending all the packets in the window on timeout" + nextSeqNum);
					e.printStackTrace();
					return;
				}
			}
		} finally {
			windowLock.unlock(); //release the lock no matter what
		}
	}
	
	//Action to perform after a good packet reception
	void receivedGoodPacket(byte[] packet)
	{
		//protect window variables
		windowLock.lock();
		//move the window up to the new window base by removing packets from the beginning
		try{
			if(!window.isEmpty())
			{
				windowBase = getSequenceNumber(packet);
				log("Received a good ACK packet with seq: " + getSequenceNumber(packet) + "\n\t window length is: " + window.size());
				log("Moving windowBase up to " + windowBase);
				byte[] p = window.peekFirst();
				while(getSequenceNumber(p) <= windowBase)
				{
					if(!window.isEmpty())
					{
						p = window.removeFirst();
						log("\t deleting packet: " + getSequenceNumber(p) + " from window");
					} else {
						log("Window emptied");
						break;
					}
					
				}
				
				//stop the timer if there are no packets in flight, reset otherwise.
		
				if(windowBase == nextSeqNum)
				{
					//stop the timer
					myDatagramSocket.setSoTimeout(0);
				}
				else
				{
					//reset hte timer
					myDatagramSocket.setSoTimeout(CLIENT_TIMEOUT);
				}
			} else {
				log("Received a good packet with seq: " + getSequenceNumber(packet) + " but window empty");
			}
		} catch (SocketException e) {
			log("SocketException resetting timer after good packet received");
			killThisAgent();
		} finally {
			windowLock.unlock(); //unlock no matter what
		}
	}
	

	
	class ReceiverRunner implements Runnable
	{
		@Override
		public void run() {
			//initialize variables and set timeout
			byte[] receivePacket = new byte[PACKET_SIZE];
			DatagramPacket receiveDatagram = new DatagramPacket(receivePacket, receivePacket.length);
			try {
				myDatagramSocket.setSoTimeout(CLIENT_TIMEOUT);
			} catch (SocketException e1) {
				log("error setting Timeout.");
				e1.printStackTrace();
			}
			
			//repeatedly receive packets
			while(!killMe)
			{
				try{
					myDatagramSocket.receive(receiveDatagram);
				} catch (InterruptedIOException e){
					log("Client timeout");
					handleTimeout();
				} catch (SocketException e) {
					log("Socket port closed externally");
					return;
				} catch (Exception e) {
					return;
				}
				
				//pull the data out of the packet and check if it is good.
				byte[] packetData = destructPacket(receivePacket);
				
				//only process packet if it is good.
				//otherwise skip processing and wait for other packets or a timeout.
				if(packetData != null)
				{
					receivedGoodPacket(receivePacket);
				} else {
					log("Received Bad ACK packet with seq: " + getSequenceNumber(receivePacket));
				}
			}
		}	
	} //\ReceiverThread
}