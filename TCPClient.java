package network_design_project;

import java.io.*;
import java.net.*;
import java.util.LinkedList;
import java.util.concurrent.locks.ReentrantLock;
public class TCPClient extends NetworkAgent{
		
	long startTime;
	long endTime;
	
	int CLIENT_TIMEOUT;	
	int INIT = 0;
	int SEND_PACKET = 1;
	int WAIT = 2;
	
	int src_port = 10000;
	int dst_port = 10001;
	
	boolean stopListening;
	
	int sequence_number = 0;
	int ack_number = 0;
	
	public TCPClient(String imageName, int port, boolean packetLogging, double corruptionChance, double dropChance, int timeOut, int windowSize)
	{
		super("CLIENT: ", "ClientLog.txt", imageName, port, packetLogging, corruptionChance, dropChance, windowSize);
		CLIENT_TIMEOUT = timeOut;		
		windowLock = new ReentrantLock();
		window = new LinkedList<byte[]>();
		windowBase = 0;
		nextSeqNum = 0;
		//System.out.println(timeOut);
	}

	@Override
	public void run() {
		try {
			transferImage();
		} catch (Exception e) {
			e.printStackTrace();
		}		
	}
	
	
	public void transferImage() throws Exception
	{
		
		
		State Client_State = State.INIT;
		startTime = System.currentTimeMillis();
		datagramSocket = new DatagramSocket(src_port);	
		byte[] sendPacket;	//packet (with header) sent to the server
		byte[] receivePacket; 	//packet (with header) received from the server
		byte[] sendData;
		byte[] lastPacket = null;
		DatagramPacket receiveDatagram;
		int tcp_flags;
		

		
		
		
		while(!killMe){
			switch( Client_State ){
			case INIT:
				log("######################################################## CLIENT STATE: INIT");
				sendPacket = null;
				receivePacket = null;
				sendData = null;
				receiveDatagram = null;
				sequence_number = 0;
				ack_number = 0;
				tcp_flags = 0;				
				Client_State = State.OPEN;
				break;
				
				
			case OPEN:
				log("####################################################### CLIENT STATE: OPEN");
				
				tcp_flags = getTCPFlags(Client_State);
			
				sendData = new byte[0];
				sendPacket = addTCPPacketHeader(
						sendData, src_port, dst_port, sequence_number, 
						ack_number,	tcp_flags, maxWindowSize, 0, 
						sendData.length + TCP_HEADER_BYTES);
				log("Client sending packet with SN: " + sequence_number + " and AK: " + ack_number);
				unreliableSendPacket(sendPacket, dst_port);
				lastPacket = sendPacket;
				Client_State = State.SYN_SENT;
				break;
				
			case SYN_SENT:
				log("####################################################### CLIENT STATE: SYN_SENT");
				
				datagramSocket.setSoTimeout(CLIENT_TIMEOUT);
				receivePacket = new byte[TCP_HEADER_BYTES];
				receiveDatagram = new DatagramPacket(receivePacket, TCP_HEADER_BYTES);
				try{
					datagramSocket.receive(receiveDatagram);
				} catch (SocketException e) {
					log("Socket port closed externally");
				} catch (InterruptedIOException e){
					//Go back to OPEN and re-send packet
					log("CLIENT: SYN_SENT Timeout");
					Client_State = State.OPEN;
					break;
				}
				
				//If received packet is not SYN-ACK
				if( !checkTCPFlags(receivePacket, Client_State) ){
					if( compareChecksum( receivePacket ) ){
						log("CLIENT: Don't know what this is");
						Client_State = State.OPEN;
						break;
					}
				}

				log("Client got SYN-ACK");
				log("Client received packet with SN: " + extractSequenceNumber(receivePacket) + " and AK: " + extractAckNumber(receivePacket));
				
				tcp_flags = getTCPFlags(Client_State);
				sequence_number = sequence_number + 1;
				ack_number = ack_number + 1;
				
				sendData = new byte[0];
				sendPacket = addTCPPacketHeader(
						sendData, src_port, dst_port, sequence_number, 
						ack_number,	tcp_flags, maxWindowSize, 0, 
						sendData.length + TCP_HEADER_BYTES);
				
				log("Client sending packet with SN: " + sequence_number + " and AK: " + ack_number);
				unreliableSendPacket(sendPacket, dst_port);
				lastPacket = sendPacket;
				while(true){
					datagramSocket.setSoTimeout(CLIENT_TIMEOUT);
					receivePacket = new byte[TCP_HEADER_BYTES];
					receiveDatagram = new DatagramPacket(receivePacket, TCP_HEADER_BYTES);
					try{
						datagramSocket.receive(receiveDatagram);
					} catch (SocketException e) {
						log("Socket port closed externally");
					} catch (InterruptedIOException e){
						//Go back and resend last packet
						log("CLIENT: Ack timed out");
					}
					log("SERVER : " + extractSequenceNumber(receivePacket) + " : " + extractAckNumber(lastPacket));
					if( compareChecksum( receivePacket ) && ( extractSequenceNumber(receivePacket) == extractAckNumber(lastPacket))){
						break;						
					} else
						unreliableSendPacket(sendPacket, dst_port);
				}
				Client_State = State.ESTABLISHED;
				sequence_number = sequence_number + 1;
				break;
				
			case ESTABLISHED:
				log("####################################################### CLIENT STATE: ESTABLISHED");
				
				//start GBN window
				windowBase = sequence_number;
				
				FileInputStream fis = new FileInputStream( imageName );		
				
				//start receiver thread to continuously receive ACKS as they come.
				stopListening = false;
				Thread receiverThread = new Thread(new ReceiverRunner());
				receiverThread.start();
				
				boolean flag = true;
				while(flag){
					//make packet
					sendData = new byte[getAvailableDataSize( fis.available() )];
					if ((fis.read(sendData) == -1) || (sendData.length == 0)) //if end of file is reached
					{
						log("End of file reached. Stop sending");
						flag = false;
						fis.close();
						log("Kill the receiver thread and wait for it to join before moving on.");
						stopListening = true;
						receiverThread.join();
						Client_State = State.FIN_WAIT_1;
						break;
					} 
					tcp_flags = getTCPFlags(Client_State);
					sendPacket = addTCPPacketHeader(
							sendData, src_port, dst_port, sequence_number, 
							ack_number,	tcp_flags, maxWindowSize, 0, 
							sendData.length + TCP_HEADER_BYTES);					
										
					while(!rdtSend(sendPacket, sendData.length))
					{
						Thread.sleep(0, 1000*500); //sleep 500us to not use all of the CPu
					}
					sequence_number += sendData.length;
				}
				
				break;
				
			case FIN_WAIT_1:
				log("####################################################### CLIENT STATE: FIN_WAIT_1");
				
				tcp_flags = getTCPFlags(Client_State);
				
				sendData = new byte[0];
				sendPacket = addTCPPacketHeader(
						sendData, src_port, dst_port, sequence_number, 
						ack_number,	tcp_flags, maxWindowSize, 0, 
						sendData.length + TCP_HEADER_BYTES);
				log("Client sending packet with SN: " + sequence_number + " and AK: " + ack_number);
				unreliableSendPacket(sendPacket, dst_port);
				lastPacket = sendPacket;
				
				Client_State = State.FIN_WAIT_2;
				break;
				
			case FIN_WAIT_2:
				log("####################################################### CLIENT STATE: FIN_WAIT_2");
				
				datagramSocket.setSoTimeout(CLIENT_TIMEOUT);
				receivePacket = new byte[TCP_HEADER_BYTES];
				receiveDatagram = new DatagramPacket(receivePacket, TCP_HEADER_BYTES);
				try{
					datagramSocket.receive(receiveDatagram);
				} catch (SocketException e) {
					log("Socket port closed externally");
				} catch (InterruptedIOException e){
					//Go back to OPEN and re-send packet
					log("CLIENT: FIN_WAIT_2 Timeout");
					Client_State = State.FIN_WAIT_1;
					break;
				}
				
				//If received packet is not SYN-ACK
				if( !checkTCPFlags(receivePacket, Client_State) ){
					if( compareChecksum( receivePacket) ){
						Client_State = State.TIME_WAIT;
						break;
					}
				}
				break;
								
			case TIME_WAIT:
				log("####################################################### CLIENT STATE: TIME_WAIT");
				
				//Wait for FIN packet from Server
				while(true){
					datagramSocket.setSoTimeout(CLIENT_TIMEOUT);
					receivePacket = new byte[TCP_HEADER_BYTES];
					receiveDatagram = new DatagramPacket(receivePacket, TCP_HEADER_BYTES);
					try{
						datagramSocket.receive(receiveDatagram);
					} catch (SocketException e) {
						log("Socket port closed externally");
					} catch (InterruptedIOException e){
						//Go back to OPEN and re-send packet
						log("CLIENT: SYN_SENT Timeout");
						Client_State = State.TIME_WAIT;
						break;
					}
					if( !checkTCPFlags(receivePacket, Client_State) ){
						if( compareChecksum( receivePacket )){
							Client_State = State.CLOSING;
							break;
						}
					}
				}
				break;
				
			case CLOSING:
				log("####################################################### CLIENT STATE: CLOSING");
				tcp_flags = getTCPFlags(Client_State);		
				sendData = new byte[0];
				sendPacket = addTCPPacketHeader(
						sendData, src_port, dst_port, sequence_number, 
						ack_number,	tcp_flags, maxWindowSize, 0, 
						sendData.length + TCP_HEADER_BYTES);
				unreliableSendPacket(sendPacket, dst_port);
				lastPacket = sendPacket;
				
				//Send FIN-ACK, wait substantial amount of time for response, then close
				while(true){
					datagramSocket.setSoTimeout(500);
					receivePacket = new byte[TCP_HEADER_BYTES];
					receiveDatagram = new DatagramPacket(receivePacket, TCP_HEADER_BYTES);
					try{
						datagramSocket.receive(receiveDatagram);
					} catch (SocketException e) {
						log("Socket port closed externally");
					} catch (InterruptedIOException e){
						//Go back to OPEN and re-send packet
						log("CLIENT: No response from Server. Close connection");
						Client_State = State.CLOSED;
						break;
					}		
				}
				break;
				
			case CLOSED:
				log("####################################################### CLIENT STATE: CLOSED");
				log("####################################################### CLIENT Connection teardown complete");
				log("####################################################### CLIENT exiting");
				killThisAgent();
				break;
				
			case LAST_ACK:
				log("####################################################### CLIENT STATE: LAST_ACK");
				killThisAgent();
				break;
				
			case CLOSE_WAIT:
				log("####################################################### CLIENT STATE: CLOSE_WAIT");
				killThisAgent();
				break;
				
			case LISTEN:
				log("####################################################### CLIENT STATE: LISTEN");
				killThisAgent();
				break;
				
			case SYN_RCVD:
				log("####################################################### CLIENT STATE: SYN_RCVD");
				killThisAgent();
				break;
				
			default:
				log("####################################################### CLIENT STATE: DEFAULT");
				killThisAgent();
				break;
			}
		}	
		finalize();
	}	

	/*
 	* Sends the packet given using GB.
 	* 
 	* Returns true if it could send it off, and false if it can't do anything with the data right now because the window is full.
 	*/
	boolean rdtSend(byte[] sendPacket, int dataLength) throws Exception  
	{
		windowLock.lock(); //protect the window from mutual access w/ receiver
		try{	
			if(window.size() < maxWindowSize)
			{
				//make packet and add it to the window
				log("Send packet with seq Number: " + extractSequenceNumber(sendPacket));
				window.add(sendPacket);
				//if sending first in the window, start the timer
				if(windowBase == nextSeqNum)
				{
					datagramSocket.setSoTimeout(CLIENT_TIMEOUT);
					log("Started reset timer");
				}
				nextSeqNum = extractSequenceNumber(sendPacket) + dataLength; //increment sequence number
		
				unreliableSendPacket(sendPacket, dst_port);
				return true;
			}	
			else
			{
				if(killMe)
					return true; //pretend it sent just so you can die
				return false;
			}
		} finally {
			windowLock.unlock(); //unlock the lock no matter what
		}	
	}
	
	/* call on receiver timeout.
	 * resends all of the packets in the window up to nextSeqNum
	 */
	void onReceiverTimeout() 
	{
		log("HandleTimeout Called");
		try {
			datagramSocket.setSoTimeout(CLIENT_TIMEOUT);
		} catch (SocketException e) {
			log("Error resetting timer after timeout");
			killThisAgent();
			e.printStackTrace();
		}
			
		windowLock.lock();
		try{
			//walk through the window and send everything from the base up to the next sequence number if appropriate
			if(window.isEmpty()){
				log("Window is empty");
				return;
			}
			log("Handling receiver timeout.-- \t windowBase: " + windowBase + " nextSeq:" + nextSeqNum);
			if( maxWindowSize > 1){
				int targetIndex = 0;
				//find the window index of the packet just before nextSeqNum
				for(int i = 0; i< window.size(); i++)
				{
					log("TargetIndex = " + targetIndex + " nextSeq = " + nextSeqNum + " seqNum at i = " + extractSequenceNumber(window.get(i)) + " i = " + i + " seq = " + sequence_number);
					if(extractSequenceNumber(window.get(i)) >= nextSeqNum)
					{
						break;
					}
					log("TargetIndex = " + targetIndex);
					targetIndex = i;
				}
				for(int i = 0; i<=targetIndex; i++) //TODO fix end condition.
				{
					try {
						byte[] thisPacket = window.get(i);
						log("goBN-ing, in window: " + (windowBase + i));
						log("goBN-ing, sequence number: " + (extractSequenceNumber(thisPacket)));
						unreliableSendPacket(thisPacket, dst_port);
					} catch (Exception e) {        
						log("issues sending all the packets in the window on timeout" + nextSeqNum);
						e.printStackTrace();
						return;
					}
				}
			} else {
				try{
					byte[] thisPacket = window.get(0);
					unreliableSendPacket(thisPacket, dst_port);
				} catch (Exception e){
					e.printStackTrace();
					return;
				}
			}
		} finally {
			windowLock.unlock(); //release the lock no matter what
		}
	}
	
	/*
	 * What to do if a good packet is received.
	 */
	void onReceivedGoodPacket(byte[] receivePacket)
	{
		//protect window variables
		windowLock.lock();
		try{
			//update ack_number to send back in next TCP message
			ack_number = extractAckNumber(receivePacket);
			//move the window up to the new window base by removing packets from the beginning			
			if(!window.isEmpty())
			{
				windowBase = extractAckNumber(receivePacket) + 1;
				log("window length is: " + window.size() + ". Moving windowBase up to " + windowBase);
				byte[] p = window.peekFirst();
				boolean flag = true;
				while( flag )
				{
					if(!window.isEmpty())
					{
						p = window.getFirst();
						if( extractSequenceNumber(p) < windowBase){
							p = window.removeFirst();
							log("\t deleting packet: " + extractSequenceNumber(p) + " from window");
						} else {
							flag = false;
						}
					} else {
						log("Window emptied");
						flag = false;
						break;
					}
					
				}
				
				//stop the timer if there are no packets in flight, reset otherwise.
				if(windowBase == nextSeqNum)
				{
					//stop the timer
					//myDatagramSocket.setSoTimeout(0);
				}
				else
				{
					//reset the timer
					datagramSocket.setSoTimeout(CLIENT_TIMEOUT);
				}
			}
		} catch (SocketException e) {
			log("SocketException resetting timer after good packet received");
			killThisAgent();
		} finally {
			windowLock.unlock(); //unlock no matter what
		}
	}
	
	/*
	 * Separate thread to receive packets and populate the GBN window during TCP Established state
	 */
	class ReceiverRunner implements Runnable
	{
		@Override
		public void run() {
			//initialize variables and set timeout
			byte[] receivePacket = new byte[TCP_HEADER_BYTES];
			DatagramPacket receiveDatagram = new DatagramPacket(receivePacket, TCP_HEADER_BYTES);
			try {
				datagramSocket.setSoTimeout(CLIENT_TIMEOUT);
			} catch (SocketException e1) {
				log("error setting Timeout during ReceiverRunner Initialization.");
			}
			
			//repeatedly receive packets
			while(!killMe && !stopListening)
			{
				try{
					datagramSocket.receive(receiveDatagram);
				} catch (InterruptedIOException e){
					log("Client timeout");
					onReceiverTimeout();
				} catch (SocketException e) {
					log("Socket port closed externally");
					return;
				} catch (Exception e) {
					return;
				}
				
				log("Client received packet with SN: " + extractSequenceNumber(receivePacket) + " and AK: " + extractAckNumber(receivePacket));
				if( compareChecksum( receivePacket )){
					onReceivedGoodPacket(receivePacket);				
				} else {
					log("ACK packet was bad");
				}

			}
		}	
	} //\ReceiverThread
	
}

