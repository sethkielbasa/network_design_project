package network_design_project;
import java.io.*;
import java.net.*;

import network_design_project.NetworkAgent.State;


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
		byte[] sendData;
		byte[] receivedData; 	//unpacked received data 
		DatagramPacket receiveDatagram;
		int receivedAckNumber;
		int packet_length;
		int tcp_flags;
		
		InetAddress IPAddress = null;
		String data;
		receiveDatagram = null;
		data = null;
		IPAddress = null;
		
		int sequence_number = 0;;
		int ack_number = 0;
		
		
				
		while(true){
			switch( Server_State ){
			case INIT:
				log("####### Server STATE: INIT");
				sendPacket = null;
				receivePacket = null;
				sendData = null;
				receivedData = null;
				receiveDatagram = null;
		
				receivedAckNumber = 1;
				sequence_number = (int) (Math.random() * 1024);
				ack_number = 0;
				tcp_flags = 0;
				
				Server_State = State.LISTEN;
				break;
				
				
			case LISTEN:
				log("###### SERVER STATE: LISTEN");
				boolean flag = true;
				while(flag){				
					receivePacket = new byte[TCP_HEADER_BYTES];
					receiveDatagram = new DatagramPacket(receivePacket, receivePacket.length);
					try{
						datagramSocket.receive(receiveDatagram);
					} catch (SocketException e) {
						log("Socket port closed externally");
						break;
					}
					
					if(!checkTCPFlags(receivePacket, Server_State )){
						log("Received packet that wasn't SYN");
						break;
					} else {
						log("Server got SYN packet");
						log("Server received packet with SN: " + extractSequenceNumber(receivePacket) + " and AK: " + extractAckNumber(receivePacket));
						Server_State = State.SYN_RCVD;
						flag = false;
					}			
				}
				break;
							
			case SYN_RCVD:
				log("###### SERVER STATE: SYN_RCVD");
				
				tcp_flags = getTCPFlags(Server_State);
				ack_number = extractSequenceNumber(receivePacket) + 1;
				
				sendData = new byte[0];
				sendPacket = addTCPPacketHeader(
						sendData, src_port, dst_port, sequence_number, 
						ack_number,	tcp_flags, windowSize, 0, 
						sendData.length + TCP_HEADER_BYTES);
				
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
						Server_State = State.ESTABLISHED;
						break;
					}
				}			
				break;
				
			
				
			case ESTABLISHED:
				log("###### Server STATE: ESTABLISHED");
				while(true){
					
					if(false)
						break;
				}
				
				break;
				
			case FIN_WAIT_1:
				break;
				
			case FIN_WAIT_2:
				break;
				
			case CLOSE_WAIT:
				break;
				
			case CLOSING:
				break;
				
			case LAST_ACK:
				break;
				
			case TIME_WAIT:
				break;
				
			case CLOSED:	
				break;
				
			case OPEN:
				log("###### Server STATE: OPEN");
				System.exit(0);
			
			case SYN_SENT:
				log("###### SERVER STATE: SYN_SENT");
				System.exit(0);		
				
			default:
				log("###### SERVER STATE: DEFAULT");
				System.exit(0);		
			}
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
