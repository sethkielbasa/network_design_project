package network_design_project;

import java.io.*;
import java.net.*;
public class UDPClient implements Runnable{
	
	public static final int BYTES_PER_PACKET = 1024; //set packet size
	String imageName;
	int port;
	
	public UDPClient(String image, int portNum)
	{
		port = portNum;
		imageName = image;
	}
	
	public static int getNumberOfPacketsToSend(String file_to_send) throws IOException{
		
		int number_of_packets = 0;		
		FileInputStream fis = new FileInputStream( file_to_send ); //Open file to send
		number_of_packets = (fis.available() / BYTES_PER_PACKET); //size of file divided by packet size
		if ( fis.available() % BYTES_PER_PACKET > 0){ //if there are bytes leftover
			number_of_packets++; 
		}
		fis.close();
		return number_of_packets;
	}
	
	public static void main(String args[]) throws Exception {
		UDPClient c = new UDPClient("client_image.jpg", 9878);
		c.transferImage();
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
		DatagramSocket clientSocket = new DatagramSocket();
		InetAddress IPAddress = InetAddress.getByName("localhost");
		byte[] sendSize;
		byte[] receiveData = new byte[BYTES_PER_PACKET];
		int num_packets = getNumberOfPacketsToSend( imageName ); //get number of packets
		
		System.out.println( "CLIENT: Sending " + num_packets + " packets");
		
		//Send amount packets to expect to the server
		sendSize = new byte[String.valueOf(num_packets).getBytes().length];
		sendSize = String.valueOf(num_packets).getBytes();
		DatagramPacket sendPacket = new DatagramPacket(sendSize, sendSize.length, IPAddress, port);
		clientSocket.send(sendPacket);
		
		FileInputStream fis = new FileInputStream( imageName );
				
		while( true ){
			int data_size = fis.available(); //get bytes left to read
			if (data_size > 1024){
				data_size = 1024; //max 1024 at a time
			}
			else if (data_size == 0){
				break;
			}
			byte[] sendData = new byte[data_size]; // create buffer for data
			int flag = fis.read(sendData); //read data
			if (flag == -1){ //if end of file is reached
				break;
			}
			sendPacket = new DatagramPacket(sendData, sendData.length, IPAddress, port);
			clientSocket.send(sendPacket);
		}
		
		fis.close();
		
		/*
		 * 
		 *  Following code taken from
		 *  https://lowell.umassonline.net/bbcswebdav/pid-305360-dt-content-rid-977475_1/courses/L2710-11029/Sockets.pdf
		 * 
		 */
		System.out.println("CLIENT: Waiting for server response");
		DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
		clientSocket.receive(receivePacket);
		String serverResponse = new String(receivePacket.getData());
		
		int substring = 0;
		for( int i = 0; i<1024; i++)
		{
			if ( Character.isDigit(serverResponse.charAt(i)) )
				substring++;
			else
				break;
		}	
		
		System.out.println("FROM SERVER: " + serverResponse.substring(0, substring ) + " packets received");
		clientSocket.close();
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