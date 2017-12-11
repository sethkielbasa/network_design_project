package network_design_project;
import java.io.*;
import java.net.*;
import java.util.LinkedList;
import java.util.concurrent.locks.Lock;


public class TCPServer extends NetworkAgent {
	
	public TCPServer(String imageName, int port, boolean packetLogging, double corruptionChance, double dropChance, int maxRcvBuffer, int applicationWorkTime)
	{
		super("SERVER: ", "ServerLog.txt", imageName, port, packetLogging, corruptionChance, dropChance,0);
		
		//init flow control
		maxRcvBufferSize = maxRcvBuffer;
		receiveBuffer = new LinkedList<byte[]>();
		isLastPacketReceived = false;
		
		//start the application that writes the image.
		Thread a = new Thread(new ApplicationLayer(applicationWorkTime));
		a.start();
	}
	
	public void receiveImage() throws Exception
	{
		int src_port = 10001;
		int dst_port = 10000;
		
		datagramSocket = new DatagramSocket(src_port);
		State Server_State = State.INIT;
		int SERVER_TIMEOUT = 30;
		
		byte[] sendPacket = new byte[0];	//packet (with header) sent to the server
		byte[] receivePacket = new byte[0]; 	//packet (with header) received from the server
		byte[] lastPacket = null;
		byte[] sendData;
		DatagramPacket receiveDatagram;
		int tcp_flags;

		receiveDatagram = null;
		
		int sequence_number = 0;;
		int ack_number = 0;
		
		
				
		while(!killMe){
			switch( Server_State ){
			case INIT:
				log("####################################################### Server STATE: INIT");
				sendPacket = null;
				receivePacket = null;
				sendData = null;
				receiveDatagram = null;
				sequence_number = 0;
				ack_number = 0;
				tcp_flags = 0;
				
				Server_State = State.LISTEN;
				break;
				
				
			case LISTEN:
				log("####################################################### SERVER STATE: LISTEN");
				boolean flag = true;
				while(flag){				
					receivePacket = new byte[TCP_HEADER_BYTES];
					try{
						datagramSocket.setSoTimeout(0);
						receiveDatagram = new DatagramPacket(receivePacket, receivePacket.length);
						datagramSocket.receive(receiveDatagram);
					} catch (SocketException e) {
						log("Socket port closed externally");
						break;
					}
					
					if(!checkTCPFlags(receivePacket, Server_State )){
						log("Received packet that wasn't SYN");
						break;
					} else {
						if( compareChecksum( receivePacket)){
							log("Server got SYN packet");
							log("Server received packet with SN: " + extractSequenceNumber(receivePacket) + " and AK: " + extractAckNumber(receivePacket));
							Server_State = State.SYN_RCVD;
							flag = false;
						}
					}			
				}
				break;
							
			case SYN_RCVD:
				log("####################################################### SERVER STATE: SYN_RCVD");
				
				tcp_flags = getTCPFlags(Server_State);
				ack_number = extractSequenceNumber(receivePacket) + 1;
				sendData = new byte[0];
				sendPacket = addTCPPacketHeader(
						sendData, src_port, dst_port, sequence_number, 
						ack_number,	tcp_flags, (int)maxRcvBufferSize, 0, 
						sendData.length + TCP_HEADER_BYTES);
				lastPacket = sendPacket;
				
				while(true){
					log("Server sending packet with SN: " + sequence_number + " and AK: " + ack_number);
					unreliableSendPacket(sendPacket, dst_port);
					
					receivePacket = new byte[TCP_HEADER_BYTES];
					receiveDatagram = new DatagramPacket(receivePacket, TCP_HEADER_BYTES);
					datagramSocket.setSoTimeout(SERVER_TIMEOUT);
					try{
						datagramSocket.receive(receiveDatagram);
					} catch (SocketException e) {
						log("Socket port closed externally");
					} catch (InterruptedIOException e){
						//Go back to OPEN and re-send packet
						log("SERVER: SYN_RCVD Timeout");
					}
					log("Server received packet with SN: " + extractSequenceNumber(receivePacket) + " and AK: " + extractAckNumber(receivePacket));
					if( checkTCPFlags( receivePacket, Server_State)){
						if( compareChecksum( receivePacket )){
							tcp_flags = getTCPFlags(Server_State);
							ack_number = extractSequenceNumber(receivePacket) + 1;
							sequence_number = extractAckNumber(receivePacket);
							sendData = new byte[0];
							sendPacket = addTCPPacketHeader(
									sendData, src_port, dst_port, sequence_number, 
									ack_number,	tcp_flags, (int)maxRcvBufferSize, 0, 
									sendData.length + TCP_HEADER_BYTES);
							lastPacket = sendPacket;
							log("Server sending packet with SN: " + sequence_number + " and AK: " + ack_number);
							unreliableSendPacket(sendPacket, dst_port);
							Server_State = State.ESTABLISHED;
							//ack_number++;
							System.out.println("server ack " + ack_number);
							break;
						}
					}
				}			
				break;
				
			case ESTABLISHED:
				log("####################################################### SERVER STATE: ESTABLISHED");
				System.out.println("server ack " + ack_number);
				//receive first packet and check flags
				while(true){
					
					datagramSocket.setSoTimeout(SERVER_TIMEOUT);
					receivePacket = new byte[PACKET_SIZE];
					receiveDatagram = new DatagramPacket(receivePacket, PACKET_SIZE);
					try{
						datagramSocket.receive(receiveDatagram);
					} catch (SocketException e) {
						log("Socket port closed externally");
						break;
					} catch (InterruptedIOException e){
						//Go back to OPEN and re-send packet
						log("SERVER: ESTABLISHED Timeout");
						log("Server sending packet with SN: " + sequence_number + " and AK: " + ack_number);						
						unreliableSendPacket(lastPacket, dst_port);
					}
					if( checkTCPFlags( receivePacket, State.FIN_WAIT_1)){
						if( compareChecksum( receivePacket )){
							Server_State = State.CLOSE_WAIT;
							//tell the app layer to close its file
							isLastPacketReceived = true;
							break;
						}
					}
					
					//check flags, checksum and Seq number all make sense.
					//if so, make and send the next packet. If not, send the last ACK sent
					if (checkTCPFlags( receivePacket, State.ESTABLISHED)){
						if( compareChecksum(receivePacket) && ( extractSequenceNumber(receivePacket) ==  ack_number)){
							log("Server received packet with SN: " + extractSequenceNumber(receivePacket) + " and AK: " + extractAckNumber(receivePacket));
							int packetLength = getReceivedPacketLength(receivePacket) - 24;
							byte[] data = new byte[ packetLength ];
							data = getReceivedPacketData(receivePacket, packetLength);
							int rWinSize = deliverDataToApp(data, extractSequenceNumber(receivePacket));
							
							//if the transport layer could successfully insert this data into the receive window..
							if(rWinSize > 0)
							{
								tcp_flags = getTCPFlags(Server_State);
								ack_number = extractSequenceNumber(receivePacket) + getReceivedPacketData(receivePacket, receivePacket.length - 24).length;
								sequence_number = sequence_number + 1;
								sendData = new byte[0];
								sendPacket = addTCPPacketHeader(
									sendData, src_port, dst_port, sequence_number, 
									ack_number,	tcp_flags, rWinSize, 0, 
									sendData.length + TCP_HEADER_BYTES);
								log("Server sending packet with SN: " + sequence_number + " and AK: " + ack_number);
								//ack_number = extractSequenceNumber(receivePacket) + packetLength; //set expected ack_number for the next packet
								lastPacket = sendPacket;
								unreliableSendPacket(sendPacket, dst_port);
							}
							else
							{
								log("Receive Window full");
								log("\tRbuffer status: Max = " + maxRcvBufferSize + " last read = " + lastByteRead + " last rcvd = " + lastByteRcvd);
								unreliableSendPacket(lastPacket, dst_port);
							}
						} else {
							unreliableSendPacket(lastPacket, dst_port);
						}
					}
				}
				
				break;

			case CLOSE_WAIT:
				log("####################################################### SERVER STATE: CLOSE_WAIT");
				tcp_flags = getTCPFlags(Server_State);
				ack_number = extractSequenceNumber(receivePacket) + 1;
				
				sendData = new byte[0];
				sendPacket = addTCPPacketHeader(
						sendData, src_port, dst_port, sequence_number, 
						ack_number,	tcp_flags, (int)maxRcvBufferSize, 0, 
						sendData.length + TCP_HEADER_BYTES);
				unreliableSendPacket(sendPacket, dst_port);
				lastPacket = sendPacket;
				
				Server_State = State.LAST_ACK;
				break;
				
			case LAST_ACK:
				log("####################################################### SERVER STATE: LAST_ACK");
				tcp_flags = getTCPFlags(Server_State);
				ack_number = extractSequenceNumber(receivePacket) + 1;
				
				sendData = new byte[0];
				sendPacket = addTCPPacketHeader(
						sendData, src_port, dst_port, sequence_number, 
						ack_number,	tcp_flags, (int)maxRcvBufferSize, 0, 
						sendData.length + TCP_HEADER_BYTES);
				unreliableSendPacket(sendPacket, dst_port);
				lastPacket = sendPacket;
				
				while(true){
					
					datagramSocket.setSoTimeout(SERVER_TIMEOUT);
					try{
						datagramSocket.receive(receiveDatagram);
					} catch (SocketException e) {
						log("Socket port closed externally");
						killThisAgent();
					} catch (InterruptedIOException e){
						//Go back to OPEN and re-send packet
						log("SERVER: SYN_RCVD Timeout");
					}
					if( checkTCPFlags( receivePacket, State.FIN_WAIT_1)){
						Server_State = State.CLOSE_WAIT;
						break;
					} else if( checkTCPFlags( receivePacket, Server_State)){
						Server_State = State.CLOSED;
						break;
					}
				}
				break;
							
			case CLOSED:
				log("####################################################### SERVER STATE: CLOSED");
				log("####################################################### SERVER Connection teardown complete");
				
				log("####################################################### SERVER exiting");
				killThisAgent();
				break;
				
			case TIME_WAIT:
				log("####################################################### SERVER STATE: CLOSED");
				killThisAgent();
				break;
				
			case FIN_WAIT_1:
				log("####################################################### SERVER STATE: FIN_WAIT_1");
				killThisAgent();
				break;
				
			case FIN_WAIT_2:
				log("####################################################### SERVER STATE: FIN_WAIT_1");
				killThisAgent();
				break;
				
			case OPEN:
				log("####################################################### SERVER STATE: OPEN");
				killThisAgent();
			
			case SYN_SENT:
				log("####################################################### SERVER STATE: SYN_SENT");
				killThisAgent();		
				
			default:
				log("####################################################### SERVER STATE: DEFAULT");
				killThisAgent();		
			}
		}
		finalize();
	}
	
	/*
	 * Tried to put data in the receiveBuffer to deliver to the application layer. 
	 * 	Returns available window space
	 * 	Failure if returns less than 0
	 */
	public int deliverDataToApp(byte[] data, int seqNum)
	{
		rcvBufferLock.lock();
		int available_space = maxRcvBufferSize - (lastByteRcvd - lastByteRead);
		//if there is space in the buffer for this data. slip it in
		if(data.length <= available_space)
		{
			receiveBuffer.addLast(data);
			lastByteRcvd = seqNum + data.length;
			available_space = maxRcvBufferSize - (lastByteRcvd - lastByteRead);
			rcvBufferLock.unlock(); //unlock the buffer no matter what
			
			if(available_space > 0xFFFF)
				available_space = 0xFFFF; //make it fit into 16 bytes.

			return available_space;
		} else {
			rcvBufferLock.unlock(); //unlock the buffer no matter what
			return -1;
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

	class ApplicationLayer implements Runnable
	{
		FileOutputStream fos;
		boolean stopListening;
		int appWorkTime;
		
		public ApplicationLayer(int timeToWork)
		{
			appWorkTime = timeToWork;
		}
		
		@Override
		public void run() {
			//init global variables
			try {
				fos = new FileOutputStream(imageName);
			} catch (FileNotFoundException e) {
				log("File not found: " + imageName);
			}
			isLastPacketReceived = false;
			stopListening = false;
			
			//repeatedly receive packets
			while(!killMe && !stopListening)
			{
				//Simulate doing some work.
				try {
					Thread.sleep(appWorkTime);
				} catch (InterruptedException e) {
					log("Application sleep interrupted");
				}
				
				//read all of the data from the buffer
				try {
					takeTcpData();
				} catch (IOException e) {
					log("IO exception in the application layer");
				}

			}
		}	
		
		/*
		 * Take all available data from the receive buffer and write it to file.
		 * If signaled that the file is empty, close the file.
		 */
		public void takeTcpData() throws IOException
		{
			byte[] d;
			int dataCount = 0;
			rcvBufferLock.lock();
			
			//pull data and write it to file
			while(!receiveBuffer.isEmpty())
			{
				d = receiveBuffer.removeFirst();
				lastByteRead += d.length;
				fos.write(d);
				dataCount++;
			}
			if(dataCount > 0)
			{
				log("Application Wrote " + dataCount + " data chunks to file");
				log("\tRbuffer status: Max = " + maxRcvBufferSize + " last read = " + lastByteRead + " last rcvd = " + lastByteRcvd);
			}
			
			if(isLastPacketReceived)
			{
				log("Last packet received... Stop listening");
				fos.close();
				stopListening = true; //let this thread end.
			}
			
			rcvBufferLock.unlock();
		}
	} //\ReceiverThread
}
