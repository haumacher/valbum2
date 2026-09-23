/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Probe for the subsampled preview decoding of #68, composed with colour models, EXIF
 * orientations and sizes the delivery did not see.
 */
@SuppressWarnings("javadoc")
public class TestPreviewCacheProbe extends TestCase {

	private Path _dir;

	@Override
	protected void setUp() throws Exception {
		_dir = Files.createTempDirectory("valbum-preview-probe");
	}

	@Override
	protected void tearDown() throws Exception {
		try (Stream<Path> files = Files.walk(_dir)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
	}

	/** A grayscale JPEG, rotated by EXIF into a portrait: the dark half of the file is the top of the preview. */
	public void testGrayscaleRotatedPortrait() throws Exception {
		BufferedImage image = new BufferedImage(3000, 2000, BufferedImage.TYPE_BYTE_GRAY);
		Graphics2D g = image.createGraphics();
		g.setColor(Color.BLACK);
		g.fillRect(0, 0, 1500, 2000);
		g.setColor(Color.WHITE);
		g.fillRect(1500, 0, 1500, 2000);
		g.dispose();

		File file = new File(_dir.toFile(), "gray.jpg");
		writeJpegWithOrientation(image, file, 6);
		byte[] before = Files.readAllBytes(file.toPath());

		BufferedImage preview = ImageIO.read(PreviewCache.createPreview(file));
		assertEquals(800, preview.getWidth());
		assertEquals(1200, preview.getHeight());
		assertTrue("Top must be the file's left (dark) half.", gray(preview, 400, 200) < 60);
		assertTrue("Bottom must be the file's right (light) half.", gray(preview, 400, 1000) > 195);
		assertTrue("Original changed.", java.util.Arrays.equals(before, Files.readAllBytes(file.toPath())));
	}

	/**
	 * An image smaller than the preview box is not upscaled — and since issue #165 not padded
	 * either: the preview is the picture at its own size, so a tile that draws the rendition into
	 * its row shows the picture, not a canvas with margins around it.
	 */
	public void testTinyImageIsNotUpscaled() throws Exception {
		BufferedImage image = new BufferedImage(100, 75, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(new Color(20, 200, 20));
		g.fillRect(0, 0, 100, 75);
		g.dispose();

		File file = new File(_dir.toFile(), "tiny.jpg");
		assertTrue(ImageIO.write(image, "jpg", file));

		BufferedImage preview = ImageIO.read(PreviewCache.createPreview(file));
		assertEquals("The picture's own size: nothing upscaled, nothing padded (#165).", 100, preview.getWidth());
		assertEquals(75, preview.getHeight());
		Color centre = new Color(preview.getRGB(50, 37));
		assertTrue("Centre carries the image.", centre.getGreen() > 150 && centre.getRed() < 80);
		Color corner = new Color(preview.getRGB(2, 2));
		assertTrue("The corner is the picture too: there is no canvas around it.", corner.getGreen() > 150 && corner.getRed() < 80);
	}

	/** A PNG with an alpha channel keeps the PNG path working under subsampling (factor 3). */
	public void testPngWithAlpha() throws Exception {
		BufferedImage image = new BufferedImage(2400, 1800, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setColor(new Color(220, 20, 20, 255));
		g.fillRect(0, 0, 2400, 1800);
		g.dispose();

		File file = new File(_dir.toFile(), "alpha.png");
		assertTrue(ImageIO.write(image, "png", file));

		File previewFile = PreviewCache.createPreview(file);
		assertEquals("preview-alpha.png", previewFile.getName());
		BufferedImage preview = ImageIO.read(previewFile);
		assertEquals(800, preview.getWidth());
		assertEquals(600, preview.getHeight());
		Color c = new Color(preview.getRGB(400, 300));
		assertTrue("Colour survives the PNG path: " + c, c.getRed() > 180 && c.getGreen() < 60);
		assertEquals("Second request must be served from the cache.", previewFile.getAbsolutePath(),
			PreviewCache.createPreview(file).getAbsolutePath());
	}

	/** Odd dimensions (a non-integer sampling ratio) with a 180° EXIF rotation land the quadrants swapped. */
	public void testOddDimensionsRotated180() throws Exception {
		BufferedImage image = new BufferedImage(4001, 3001, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(new Color(220, 20, 20));
		g.fillRect(0, 0, 2000, 1500);
		g.setColor(new Color(20, 20, 220));
		g.fillRect(2000, 1500, 2001, 1501);
		g.setColor(new Color(20, 200, 20));
		g.fillRect(2000, 0, 2001, 1500);
		g.fillRect(0, 1500, 2000, 1501);
		g.dispose();

		File file = new File(_dir.toFile(), "odd.jpg");
		writeJpegWithOrientation(image, file, 3);

		BufferedImage preview = ImageIO.read(PreviewCache.createPreview(file));
		assertEquals(800, preview.getWidth());
		assertEquals(600, preview.getHeight());
		Color bottomRight = new Color(preview.getRGB(700, 500));
		assertTrue("Red top-left quadrant must land bottom-right: " + bottomRight,
			bottomRight.getRed() > 150 && bottomRight.getBlue() < 80);
		Color topLeft = new Color(preview.getRGB(100, 100));
		assertTrue("Blue bottom-right quadrant must land top-left: " + topLeft,
			topLeft.getBlue() > 150 && topLeft.getRed() < 80);
	}

	private static int gray(BufferedImage image, int x, int y) {
		Color c = new Color(image.getRGB(x, y));
		return (c.getRed() + c.getGreen() + c.getBlue()) / 3;
	}

	private static void writeJpegWithOrientation(BufferedImage image, File file, int orientation)
			throws Exception {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		assertTrue(ImageIO.write(image, "jpg", buffer));
		byte[] jpeg = buffer.toByteArray();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(jpeg, 0, 2);
		out.write(0xFF);
		out.write(0xE1);
		out.write(0);
		out.write(34);
		out.write(new byte[] { 'E', 'x', 'i', 'f', 0, 0 });
		out.write(new byte[] { 'M', 'M', 0, 42, 0, 0, 0, 8 });
		out.write(new byte[] { 0, 1 });
		out.write(new byte[] { 0x01, 0x12, 0, 3, 0, 0, 0, 1 });
		out.write(new byte[] { 0, (byte) orientation, 0, 0 });
		out.write(new byte[] { 0, 0, 0, 0 });
		out.write(jpeg, 2, jpeg.length - 2);
		Files.write(file.toPath(), out.toByteArray());
	}
}
