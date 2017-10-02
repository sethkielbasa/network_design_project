package network_design_project;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Queue;

import javax.imageio.ImageIO;

public class TestHelpers {
	public static void main(String[] args)
	{
		try{
			testMakePackets();
		} catch(IOException e) {
			e.printStackTrace();
		}
	}
	
	public static void testMakePackets() throws IOException
	{
		String imName = "JustATest.jpg";
		File imFile = new File(imName);
		BufferedImage im = ImageIO.read(imFile);
		
		
		Queue<byte[]> q = HelperFunctions.makePacket(im, 1024, "jpg");
		System.out.println("Make breakpoint here and look at q");
	}
}
