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
		int src_port = 10000;
		int dst_port = 10001;
		
		State Client_State = State.INIT;
		startTime = System.currentTimeMillis();
		datagramSocket = new DatagramSocket(src_port);	
		byte[] sendPacket;	//packet (with header) sent to the server
		byte[] receivePacket; 	//packet (with header) received from the server
		byte[] sendData;
		byte[] receivedData; 	//unpacked received data 
		DatagramPacket receiveDatagram;
		int receivedAckNumber;
		int packet_length;
		int tcp_flags;
		
		int sequence_number = 0;;
		int ack_number = 0;
		
		
		
		while(true){
			switch( Client_State ){
			case INIT:
				log("####### CLIENT STATE: INIT");
				sendPacket = null;
				receivePacket = null;
				sendData = null;
				receivedData = null;
				receiveDatagram = null;
				
				sequence_number = (int) (Math.random() * 1024);
				ack_number = 0;
				tcp_flags = 0;
				
				Client_State = State.OPEN;
				break;
				
				
			case OPEN:
				log("###### CLIENT STATE: OPEN");
				
				tcp_flags = getTCPFlags(Client_State);
			
				sendData = new byte[0];
				sendPacket = addTCPPacketHeader(
						sendData, src_port, dst_port, sequence_number, 
						ack_number,	tcp_flags, windowSize, 0, 
						sendData.length + TCP_HEADER_BYTES);
				log("Client sending packet with SN: " + sequence_number + " and AK: " + ack_number);
				unreliableSendPacket(sendPacket, dst_port);
				
				Client_State = State.SYN_SENT;
				break;
				
				
			case CLOSED:	
				break;
				
			case SYN_SENT:
				log("###### CLIENT STATE: SYN_SENT");
				
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
					Client_State = State.ESTABLISHED;
					break;
				}

				log("Client got SYN-ACK");
				log("Client received packet with SN: " + extractSequenceNumber(receivePacket) + " and AK: " + extractAckNumber(receivePacket));
				
				tcp_flags = getTCPFlags(Client_State);
				sequence_number = sequence_number + 1;
				ack_number = extractSequenceNumber(receivePacket) + 1;
				
				sendData = new byte[0];
				sendPacket = addTCPPacketHeader(
						sendData, src_port, dst_port, sequence_number, 
						ack_number,	tcp_flags, windowSize, 0, 
						sendData.length + TCP_HEADER_BYTES);
				
				log("Client sending packet with SN: " + sequence_number + " and AK: " + ack_number);
				unreliableSendPacket(sendPacket, dst_port);
				
				Client_State = State.ESTABLISHED;
				break;
				
			case ESTABLISHED:
				log("###### CLIENT STATE: ESTABLISHED");
				
				FileInputStream fis = new FileInputStream( imageName );		
				
				boolean flag = true;
				while(flag){
					sendData = new byte[getAvailableDataSize( fis.available() )];
					if ((fis.read(sendData) == -1) || (sendData.length == 0)) //if end of file is reached
					{
						log("End of file reached. Stop sending");
						flag = false;
						Client_State = State.FIN_WAIT_1;
						break;
					} 
					tcp_flags = getTCPFlags(Client_State);
					sequence_number = sequence_number + sendData.length;
					//ack_number = extractSequenceNumber(receivePacket) + 1;
					sendPacket = addTCPPacketHeader(
							sendData, src_port, dst_port, sequence_number, 
							ack_number,	tcp_flags, windowSize, 0, 
							sendData.length + TCP_HEADER_BYTES);
					log("Client sending packet with SN: " + sequence_number + " and AK: " + ack_number);
					unreliableSendPacket(sendPacket, dst_port);					
				}
				
				break;
				
			case FIN_WAIT_1:
				log("###### CLIENT STATE: FIN_WAIT_1");
				break;
				
			case FIN_WAIT_2:
				log("###### CLIENT STATE: FIN_WAIT_2");
				break;
				
			case CLOSE_WAIT:
				break;
				
			case CLOSING:
				break;
				
			case LAST_ACK:
				break;
				
			case TIME_WAIT:
				break;
				
				
			case LISTEN:
				log("###### CLIENT STATE: LISTEN");
				System.exit(0);
				
			case SYN_RCVD:
				log("###### CLIENT STATE: SYN_RCVD");
				System.exit(0);
				
			default:
				log("###### CLIENT STATE: DEFAULT");
				System.exit(0);		
			}
		}		
	}	
}
