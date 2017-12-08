package network_design_project;
import java.io.*;
import java.net.*;


public class TCPServer extends NetworkAgent {

	public TCPServer(String imageName, int port, boolean packetLogging, double corruptionChance, double dropChance)
	{
		super("SERVER: ", "ServerLog.txt", imageName, port, packetLogging, corruptionChance, dropChance,0);
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
						ack_number,	tcp_flags, maxWindowSize, 0, 
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
							sequence_number = sequence_number + 1;
							sendData = new byte[0];
							sendPacket = addTCPPacketHeader(
									sendData, src_port, dst_port, sequence_number, 
									ack_number,	tcp_flags, maxWindowSize, 0, 
									sendData.length + TCP_HEADER_BYTES);
							lastPacket = sendPacket;
							log("Server sending packet with SN: " + sequence_number + " and AK: " + ack_number);
							unreliableSendPacket(sendPacket, dst_port);
							Server_State = State.ESTABLISHED;
							break;
						}
					}
				}			
				break;
				
			case ESTABLISHED:
				log("####################################################### SERVER STATE: ESTABLISHED");
				FileOutputStream fos = new FileOutputStream(imageName);
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
							fos.close();
							break;
						}
					}
					
					//check flags, checksum and Seq number all make sense.
					//if so, make and send the next packet. If not, send the last ACK sent
					if (checkTCPFlags( receivePacket, State.ESTABLISHED)){
						log("SERVER : " + extractSequenceNumber(receivePacket) + " : " + extractAckNumber(lastPacket));
						if( compareChecksum(receivePacket) && ( extractSequenceNumber(receivePacket) ==  ack_number)){
							log("Server received packet with SN: " + extractSequenceNumber(receivePacket) + " and AK: " + extractAckNumber(receivePacket));
							int packetLength = getReceivedPacketLength(receivePacket) - 24;
							byte[] data = new byte[ packetLength ];
							data = getReceivedPacketData(receivePacket, packetLength);
							fos.write(data);
							
							tcp_flags = getTCPFlags(Server_State);
							//ack_number = extractSequenceNumber(receivePacket) + packetLength; // I think this is wrong
							ack_number = extractSequenceNumber(receivePacket);
							sequence_number = sequence_number + 1;
							sendData = new byte[0];
							sendPacket = addTCPPacketHeader(
									sendData, src_port, dst_port, sequence_number, 
									ack_number,	tcp_flags, maxWindowSize, 0, 
									sendData.length + TCP_HEADER_BYTES);
							ack_number = extractSequenceNumber(receivePacket) + packetLength; //set expected ack_number for the next packet
							lastPacket = sendPacket;
							unreliableSendPacket(sendPacket, dst_port);
							log("Server sending packet with SN: " + sequence_number + " and AK: " + ack_number);						
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
						ack_number,	tcp_flags, maxWindowSize, 0, 
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
						ack_number,	tcp_flags, maxWindowSize, 0, 
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
	
	@Override
	public void run() {
		try {
			receiveImage();
		} catch (Exception e) {
			e.printStackTrace();
		}		
	}

}
