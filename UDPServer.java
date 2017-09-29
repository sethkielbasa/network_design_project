import java.io.*;
import java.net.*;

public class UDPServer {	
	
	public static void main(String args[]) throws Exception {
		
		String imageName = "server_image.jpg";
		int port = 9878;

		/*
		 * 
		 *  Following code taken from
		 *  https://lowell.umassonline.net/bbcswebdav/pid-305360-dt-content-rid-977475_1/courses/L2710-11029/Sockets.pdf
		 * 
		 */
		@SuppressWarnings("resource")
		DatagramSocket serverSocket = new DatagramSocket(port);
		byte[] receiveData = new byte[1024];
		byte[] sendData = new byte[1024];
		
		while(true) {
			
			/*
			 * 
			 *  Following code taken from
			 *  https://lowell.umassonline.net/bbcswebdav/pid-305360-dt-content-rid-977475_1/courses/L2710-11029/Sockets.pdf
			 * 
			 */
			receiveData = new byte[1024];
			DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
			serverSocket.receive(receivePacket);	
			String sentence = new String(receivePacket.getData());
			
			//pull first of data that contains packets to expect
			int substring = 0;
			for( int i = 0; i<1024; i++)
			{
				if ( Character.isDigit(sentence.charAt(i)) )
					substring++;
				else
					break;
			}	
			sentence = sentence.substring(0, substring );
			
			int packets_expected = Integer.parseInt(sentence, 10);
			int packets_received = 0;
			
			FileOutputStream fos = new FileOutputStream(imageName); //Open output file
			System.out.println("SERVER: Waiting for " + packets_expected + " packets");
			while ( packets_received < packets_expected){
				receiveData = new byte[1024];
				receivePacket = new DatagramPacket(receiveData, receiveData.length);
				serverSocket.receive(receivePacket);				
				fos.write(receiveData);
				packets_received++;
			}
			System.out.println("SERVER: Got " + packets_received + " packets\n");
			fos.close();
			
			/*
			 * 
			 *  Following code taken from
			 *  https://lowell.umassonline.net/bbcswebdav/pid-305360-dt-content-rid-977475_1/courses/L2710-11029/Sockets.pdf
			 * 
			 */
			InetAddress IPAddress = receivePacket.getAddress();
			int ports = receivePacket.getPort();
			String receivedData = String.valueOf(packets_received + " packets received");
			sendData = receivedData.getBytes();
			DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, IPAddress, ports);
			serverSocket.send(sendPacket);
		}
	}
}