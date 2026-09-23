/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Issue #165: the preview of a picture smaller than its preview box is the picture, not the
 * picture in the middle of a larger black canvas — a tile draws the rendition into its row, and
 * margins baked into the rendition would show as a picture that does not fill its place.
 */
@SuppressWarnings("javadoc")
public class TestPreviewSmallPicture extends TestCase {

	private static final Color FILL = new Color(0x20, 0x80, 0xE0);

	private Path _dir;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_dir = Files.createTempDirectory("valbum-preview-small");
	}

	@Override
	protected void tearDown() throws Exception {
		try (Stream<Path> files = Files.walk(_dir)) {
			files.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		super.tearDown();
	}

	public void testASmallLandscapeGetsAPreviewOfItsOwnSize() throws Exception {
		BufferedImage preview = preview(picture("small.jpg", 300, 200));
		assertEquals(300, preview.getWidth());
		assertEquals(200, preview.getHeight());
		assertFilled(preview);
	}

	public void testASmallPanoramaGetsAPreviewOfItsOwnSize() throws Exception {
		// The demo album's shape: 2237 x 400 used to become a 3356 x 600 canvas with the picture
		// drawn 1:1 in its middle.
		BufferedImage preview = preview(picture("panorama.jpg", 2237, 400));
		assertEquals(2237, preview.getWidth());
		assertEquals(400, preview.getHeight());
		assertFilled(preview);
	}

	public void testAPictureWiderThanItsBoxButLowerIsCutToItsHeight() throws Exception {
		// 1200 x 300: the box is 2400 x 600, the picture is drawn 1:1 — the canvas is its size.
		BufferedImage preview = preview(picture("wide.jpg", 1200, 300));
		assertEquals(1200, preview.getWidth());
		assertEquals(300, preview.getHeight());
		assertFilled(preview);
	}

	public void testALargePictureKeepsThePreviewBox() throws Exception {
		BufferedImage preview = preview(picture("large.jpg", 3000, 2000));
		assertEquals(900, preview.getWidth());
		assertEquals(600, preview.getHeight());
		assertFilled(preview);
	}

	public void testASmallTurnedPictureIsUprightAtItsOwnSize() throws Exception {
		// Stored 200 x 300 with EXIF 6 (a quarter turn): shown 300 x 200.
		File plain = picture("turned.plain.jpg", 200, 300);
		File turned = new File(_dir.toFile(), "turned.jpg");
		Exif.writeOrientation(plain, turned, 6);
		BufferedImage preview = preview(turned);
		assertEquals(300, preview.getWidth());
		assertEquals(200, preview.getHeight());
		assertFilled(preview);
	}

	private BufferedImage preview(File file) throws Exception {
		File preview = PreviewCache.createPreview(file);
		BufferedImage image = ImageIO.read(preview);
		assertNotNull("No preview for " + file, image);
		return image;
	}

	private File picture(String name, int width, int height) throws Exception {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		try {
			g.setColor(FILL);
			g.fillRect(0, 0, width, height);
		} finally {
			g.dispose();
		}
		File file = new File(_dir.toFile(), name);
		assertTrue(ImageIO.write(image, "jpg", file));
		return file;
	}

	/** The picture fills the rendition: every corner is the picture's colour, not the canvas's black. */
	private static void assertFilled(BufferedImage preview) {
		int w = preview.getWidth();
		int h = preview.getHeight();
		for (int[] corner : new int[][] { { 1, 1 }, { w - 2, 1 }, { 1, h - 2 }, { w - 2, h - 2 } }) {
			Color at = new Color(preview.getRGB(corner[0], corner[1]));
			assertTrue("Corner " + corner[0] + "," + corner[1] + " is " + at + ", a margin rather than the picture.",
				Math.abs(at.getBlue() - FILL.getBlue()) < 40 && at.getRed() < 90);
		}
	}
}
