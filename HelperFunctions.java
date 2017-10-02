package network_design_project;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;

import javax.imageio.ImageIO;


public class HelperFunctions{

	public static ArrayDeque<byte[]> makePacket(BufferedImage im, int packetSize, String imType) throws IOException
	{
		//convert the image into a byte[]
		ByteArrayOutputStream o = new ByteArrayOutputStream();
		ImageIO.write(im, imType, o);
		byte[] fullImage = o.toByteArray();
		
		//break the byte[] into a queue of packets
		ArrayDeque<byte[]> d = new ArrayDeque<byte[]>(); 
		for(int i = 0; i < fullImage.length; i+= packetSize)
		{
			if(i + packetSize < fullImage.length)
			{
				//copy a packet size chunk of the image for regular packets
				d.add(Arrays.copyOfRange(fullImage,  i, i + packetSize));
			} else {
				//copy only the last remaining bytes of the image for the last packet
				byte[] b = new byte[packetSize];
				for(int j = 0; j < fullImage.length - i; j++)
					b[j] = fullImage[i + j];
				d.add(b);
			}
		}
		
		return d;
	}
}