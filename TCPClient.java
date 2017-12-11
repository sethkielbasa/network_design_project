package network_design_project;

import java.io.*;
import java.net.*;
import java.util.LinkedList;
import java.util.concurrent.locks.ReentrantLock;
public class TCPClient extends NetworkAgent{
		
	long startTime;
	long endTime;
	
	int INIT = 0;
	int SEND_PACKET = 1;
	int WAIT = 2;
	
	int src_port = 10000;
	int dst_port = 10001;
	
	boolean stopListening;
	
	int sequence_number = 0;
	int send_ack_number = 0;
	
	public TCPClient(String imageName, int port, boolean packetLogging, double corruptionChance, double dropChance, int startingWindowSize, int ssthresh)
	{
		super("CLIENT: ", "ClientLog.txt", imageName, port, packetLogging, corruptionChance, dropChance, startingWindowSize);
		sendWindowLock = new ReentrantLock();
		sendWindow = new LinkedList<byte[]>();
		sendWindowBase = 0;
		nextSendSeqNum = 0;
		slowStartThresh = ssthresh;
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
				send_ack_number = 0;
				tcp_flags = 0;				
				Client_State = State.OPEN;
				break;
				
				
			case OPEN:
				log("####################################################### CLIENT STATE: OPEN");
				
				tcp_flags = getTCPFlags(Client_State);
			
				sendData = new byte[0];
				sendPacket = addTCPPacketHeader(
						sendData, src_port, dst_port, sequence_number, 
						send_ack_number,	tcp_flags, (int)maxSendWindowSize, 0, 
						sendData.length + TCP_HEADER_BYTES);
				log("Client sending packet with SN: " + sequence_number + " and AK: " + send_ack_number);
				unreliableSendPacket(sendPacket, dst_port);
				lastPacket = sendPacket;
				Client_State = State.SYN_SENT;
				break;
				
			case SYN_SENT:
				log("####################################################### CLIENT STATE: SYN_SENT");
				
				datagramSocket.setSoTimeout(getTimeoutInterval());
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
						updateRTTTrackerIfAppropriate(receivePacket);
						log("CLIENT: Don't know what this is");
						Client_State = State.OPEN;
						break;
					}
				}

				log("Client got SYN-ACK");
				log("Client received packet with SN: " + extractSequenceNumber(receivePacket) + " and AK: " + extractAckNumber(receivePacket));
				
				tcp_flags = getTCPFlags(Client_State);
				sequence_number = extractAckNumber(receivePacket);
				send_ack_number = extractSequenceNumber(receivePacket) + 1;
				
				sendData = new byte[0];
				sendPacket = addTCPPacketHeader(
						sendData, src_port, dst_port, sequence_number, 
						send_ack_number,	tcp_flags, (int)maxSendWindowSize, 0, 
						sendData.length + TCP_HEADER_BYTES);
				
				log("Client sending packet with SN: " + sequence_number + " and AK: " + send_ack_number);
				unreliableSendPacket(sendPacket, dst_port);
				lastPacket = sendPacket;
				while(true){
					datagramSocket.setSoTimeout(getTimeoutInterval());
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
					log("CLIENT : " + extractSequenceNumber(receivePacket) + " : " + extractAckNumber(lastPacket));
					if( compareChecksum( receivePacket ) && ( extractSequenceNumber(receivePacket) == extractAckNumber(lastPacket))){
						updateRTTTrackerIfAppropriate(receivePacket);
						break;						
					} else{
						log("Client sending packet with SN: " + sequence_number + " and AK: " + send_ack_number);
						unreliableSendPacket(sendPacket, dst_port);
					}
				}
				Client_State = State.ESTABLISHED;
				sequence_number = extractAckNumber(receivePacket);
				send_ack_number = extractSequenceNumber(receivePacket) + 1;
				break;
				
			case ESTABLISHED:
				log("####################################################### CLIENT STATE: ESTABLISHED");
				
				//start GBN window
				sendWindowBase = sequence_number;
				
				FileInputStream fis = new FileInputStream( imageName );		
				
				//start receiver thread to continuously receive ACKS as they come.
				stopListening = false;
				Thread receiverThread = new Thread(new ReceiverRunner());
				receiverThread.start();
				
				boolean flag = true;
				while(flag & !killMe){
					sendData = new byte[getAvailableDataSize( fis.available() )];
					if ((fis.read(sendData) == -1) || (sendData.length == 0)) //if end of file is reached
					{
						if(sendWindow.isEmpty())
						{
							log("All packets successfully sent. Kill the receiver thread and wait for it to join before moving on.");
							
							fis.close();
							flag = false;
							stopListening = true;
							receiverThread.join();
							Client_State = State.FIN_WAIT_1;
							break;
						}
						//don't make new packet, but let window get emptied out before moving on
					} 
					else
					{
						//make new packet and send it
						tcp_flags = getTCPFlags(Client_State);
						sendPacket = addTCPPacketHeader(
							sendData, src_port, dst_port, sequence_number, 
							send_ack_number,	tcp_flags, (int)maxSendWindowSize, 0, 
							sendData.length + TCP_HEADER_BYTES);
						
						while(!rdtSend(sendPacket, sendData.length))
						{
							Thread.sleep(0, 1000*500); //sleep 500us to not use all of the CPu
						}
						sequence_number += sendData.length;
					}
				}
				
				break;
				
			case FIN_WAIT_1:
				log("####################################################### CLIENT STATE: FIN_WAIT_1");
				
				tcp_flags = getTCPFlags(Client_State);
				
				sendData = new byte[0];
				sendPacket = addTCPPacketHeader(
						sendData, src_port, dst_port, sequence_number, 
						send_ack_number,	tcp_flags, (int)maxSendWindowSize, 0, 
						sendData.length + TCP_HEADER_BYTES);
				log("Client sending packet with SN: " + sequence_number + " and AK: " + send_ack_number);
				unreliableSendPacket(sendPacket, dst_port);
				lastPacket = sendPacket;
				
				Client_State = State.FIN_WAIT_2;
				break;
				
			case FIN_WAIT_2:
				log("####################################################### CLIENT STATE: FIN_WAIT_2");
				
				datagramSocket.setSoTimeout(getTimeoutInterval());
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
						updateRTTTrackerIfAppropriate(receivePacket);
						Client_State = State.TIME_WAIT;
						break;
					}
				}
				break;
								
			case TIME_WAIT:
				log("####################################################### CLIENT STATE: TIME_WAIT");
				
				//Wait for FIN packet from Server
				while(true){
					datagramSocket.setSoTimeout(getTimeoutInterval());
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
							updateRTTTrackerIfAppropriate(receivePacket);
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
						send_ack_number,	tcp_flags, (int)maxSendWindowSize, 0, 
						sendData.length + TCP_HEADER_BYTES);
				log("Client sending packet with SN: " + sequence_number + " and AK: " + send_ack_number);
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
		int cpyRwindSize;
		//copy lock-protecetd variable into an unshared one.
		rcvBufferLock.lock();
		cpyRwindSize = otherRwindSize;
		rcvBufferLock.unlock();
		
		sendWindowLock.lock(); //protect the window from mutual access w/ receiver
		try{
			//check if we're flooding the server receive buffer first
			if(cpyRwindSize < nextSendSeqNum - sendWindowBase && cpyRwindSize != 0)
			{
				log("FLOW CONTROL: flooding prevented. GBN: " + cpyRwindSize + " available receiver space." + " Unacked PIF: " + (nextSendSeqNum - sendWindowBase));
				
				//if we are, pretend that everything got messed up.
				 retransmitWindow();
			} //normal rdt_Send
			else if(sendWindow.size() < maxSendWindowSize)
			{
				//make packet and add it to the window
				log("Make new packet with seq Number: " + extractSequenceNumber(sendPacket));
				sendWindow.add(sendPacket);
				//if sending first in the window, start the timer
				if(sendWindowBase == nextSendSeqNum)
				{
					datagramSocket.setSoTimeout(getTimeoutInterval());
					log("Started reset timer");
				}
				nextSendSeqNum = extractSequenceNumber(sendPacket) + dataLength; //increment sequence number
				log("Client sending packet with SN: " + sequence_number + " and AK: " + send_ack_number);
				unreliableSendPacket(sendPacket, dst_port);
				return true;
			}	
			
			
			//window full: refuse data
			if(killMe)
				return true; //pretend it sent just so you can die
			return false;

		} finally {
			sendWindowLock.unlock(); //unlock the lock no matter what
		}	
	}
	
	/* call on receiver timeout.
	 * resends all of the packets in the window up to nextSeqNum
	 */
	void onReceiverTimeout() 
	{
		log("HandleTimeout Called");
		try {
			datagramSocket.setSoTimeout(getTimeoutInterval());
		} catch (SocketException e) {
			log("Error resetting timer after timeout");
			killThisAgent();
			e.printStackTrace();
		}
			
		sendWindowLock.lock();
		try{
			//walk through the window and send everything from the base up to the next sequence number if appropriate
			if(sendWindow.isEmpty()){
				log("Window is empty");
				return;
			}
			log("Handling receiver timeout.-- \t windowBase: " + sendWindowBase + " nextSeq:" + nextSendSeqNum);
			
			//Update congestion window size
			slowStartThresh = (int) Math.ceil(maxSendWindowSize / 2.0);
			maxSendWindowSize = 1;
			dupAckCount = 0;
			retransmitWindow();
			
			//manage congestion control fsm
			if(ccState == CCState.SLOW_START)
			{
				//no state change
			} 
			else if(ccState == CCState.CON_AVO)
			{
				ccState = CCState.SLOW_START;
			}
			else if(ccState == CCState.FAST_REC)
			{
				ccState = CCState.SLOW_START;
			}
			log("\tCCState: " + ccState.toString() + " cwind: " + maxSendWindowSize + " ssthresh: " + slowStartThresh); //cc summary

		} finally {
			sendWindowLock.unlock(); //release the lock no matter what
		}
	}
	
	
	/*
	 * Sends all packets in the window up to NextSeqNum.
	 * Needs to have the window locked
	 */
	void retransmitWindow()
	{
		//trigger retransmit
		if( maxSendWindowSize > 1){
			
			int targetIndex = 0;
			//find the window index of the packet just before nextSeqNum
			for(int i = 0; i< sendWindow.size(); i++)
			{
				if(extractSequenceNumber(sendWindow.get(i)) >= nextSendSeqNum)
				{
					break;
				}
				targetIndex = i;
			}
			log("TargetIndex = " + targetIndex + " for GBN");
			for(int i = 0; i<=targetIndex; i++) 
			{
				try {
					byte[] thisPacket = sendWindow.get(i);
					log("goBN-ing, sequence number: " + (extractSequenceNumber(thisPacket)));
					log("Client sending packet with SN: " + extractSequenceNumber(thisPacket) + " and AK: " + extractAckNumber(thisPacket));
					unreliableSendPacket(thisPacket, dst_port);
					
					//disableRTT tracking if a packet is sent twice
					if(extractSequenceNumber(thisPacket) == trackingSeqNum)
						trackingSeqNum = -1;
					
				} catch (Exception e) {        
					log("issues sending all the packets in the window on timeout" + nextSendSeqNum);
					e.printStackTrace();
					return;
				}
			}
		} else {
			try{
				byte[] thisPacket = sendWindow.get(0);
				log("goBN-ing, sequence number: " + (extractSequenceNumber(thisPacket)));
				log("Client sending packet with SN: " + sequence_number + " and AK: " + send_ack_number);
				unreliableSendPacket(thisPacket, dst_port);
			} catch (Exception e){
				e.printStackTrace();
				return;
			}
		}
	}
	
	/*
	 * What to do if a good packet is received.
	 */
	void onReceivedGoodPacket(byte[] receivePacket)
	{
		//update RTT calculation
		updateRTTTrackerIfAppropriate(receivePacket);
		
		//update server window tracking
		rcvBufferLock.lock();
		otherRwindSize = extractRwindField(receivePacket);
		rcvBufferLock.unlock();
		
		//protect send window variables
		sendWindowLock.lock();
		try{
			//update ack_number to send back in next TCP message
			send_ack_number = extractSequenceNumber(receivePacket) + 1;
			//ack_number = extractAckNumber(receivePacket);
			
			//update congestion control variables according to state machine on p275
			if(lastAckReceived != extractAckNumber(receivePacket))
			{	//new ACK
				dupAckCount = 0;
				
				if(ccState == CCState.SLOW_START)
				{
					maxSendWindowSize++;
					if(maxSendWindowSize >= slowStartThresh)
						ccState = CCState.CON_AVO;
				} 
				else if(ccState == CCState.CON_AVO)
				{			
					//cwind += 1/cwind (avoid divideby0
					maxSendWindowSize += 1/Math.ceil(maxSendWindowSize);
				}
				else if(ccState == CCState.FAST_REC)
				{
					maxSendWindowSize = slowStartThresh;
					ccState = CCState.CON_AVO;
				}
			}
			else
			{ 	//duplicate ACK
				
				if(ccState == CCState.FAST_REC)
				{
					maxSendWindowSize++; //this line caused ridiculous window growth in high error conditions
					//keep sending normal packets
				}
				else //SLOW_START and CON_AVO
				{
					dupAckCount++;
					if(dupAckCount == 3)  //soft error recovery, retransmit dup-ACK'd packet with FR
					{
						ccState = CCState.FAST_REC;
						slowStartThresh = (int) Math.ceil(maxSendWindowSize / 2.0);
						maxSendWindowSize = slowStartThresh + 3;
						
						retransmitWindow();
					}
				}
			}
			lastAckReceived = extractAckNumber(receivePacket);
			log("CCState: " + ccState.name() + " cwind: " + maxSendWindowSize + " ssthresh: " + slowStartThresh + " dupACKs: " + dupAckCount); //cc summary	
			
			//move the window up to the new window base by removing packets from the beginning			
			if(!sendWindow.isEmpty())
			{
				sendWindowBase = extractAckNumber(receivePacket);
				log("window length is: " + sendWindow.size() + ". Moving windowBase up to " + sendWindowBase);
				byte[] p = sendWindow.peekFirst();
				boolean flag = true;
				while( flag )
				{
					if(!sendWindow.isEmpty())
					{
						p = sendWindow.getFirst();
						if( extractSequenceNumber(p) < sendWindowBase){
							p = sendWindow.removeFirst();
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
				if(sendWindowBase == nextSendSeqNum)
				{
					//stop the timer
					//myDatagramSocket.setSoTimeout(0);
				}
				else
				{
					//reset the timer
					datagramSocket.setSoTimeout(getTimeoutInterval());
				}
			}
		} catch (SocketException e) {
			log("SocketException resetting timer after good packet received");
			killThisAgent();
		} finally {
			sendWindowLock.unlock(); //unlock no matter what
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
				datagramSocket.setSoTimeout(getTimeoutInterval());
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

