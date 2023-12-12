/*
 * Copyright (c) 2023 Bernhard Haumacher et al. All Rights Reserved.
 */
package test.de.haumacher.valbum;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Random;

import javax.imageio.ImageIO;

import junit.framework.TestCase;

/**
 * Test creating a test album.
 */
public class GenerateTestAlbum extends TestCase {
	public void testGenerate() throws IOException {
		File dir = new File("./target/test-album");
		dir.mkdirs();
		
		Random rnd = new Random();
		double aspects[] = {4./3, 16./9, 3./2, 16./10}; 

		for (int n = 0; n < 100; n++) {
			int width = 800;
			int height;
			if (rnd.nextDouble() < 0.03) {
				// Panorama
				height = 400;
				width = (int) (height * (4 + rnd.nextDouble() * 2));
			} else {
				double aspect = aspects[rnd.nextInt(aspects.length)];
				if (rnd.nextDouble() < 0.25) {
					aspect = 1 / aspect;
				}
				height = (int) (width / aspect);
			}
			
			BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
			Graphics2D graphics = (Graphics2D) image.getGraphics();
			
			graphics.setStroke(new BasicStroke(3));
			graphics.setColor(Color.red);
			graphics.drawRect(1, 1, width-2, height-2);
			graphics.drawLine(0, 0, width, height);
			graphics.drawLine(width, 0, 0, height);
			
			graphics.setFont(new Font("SansSerif", 0, 60));
			
			String text = "Image " + (n+1);
			FontMetrics fontMetrics = graphics.getFontMetrics();
			int textWidth = fontMetrics.stringWidth(text);
			int textHeight = fontMetrics.getHeight();
			int x = (width - textWidth) / 2;
			int y = (height - textHeight) / 2;

			int padding = 8;
			graphics.setColor(Color.black);
			graphics.fillRect(x - padding, y - padding, textWidth + 2*padding, textHeight+2*padding);

			graphics.setColor(Color.white);
			graphics.drawRect(x - padding, y - padding, textWidth + 2*padding, textHeight+2*padding);
			
			graphics.drawString(text, x, y + fontMetrics.getLeading() / 2 + fontMetrics.getAscent());
			
			ImageIO.write(image, "jpg", new File(dir, "image-" + n + ".jpg"));
		}
	}

}
