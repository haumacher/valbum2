/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.faces;

import de.haumacher.imageServer.PreviewCache;
import de.haumacher.imageServer.heif.TestHeifDecoder;
import de.haumacher.imageServer.shared.model.Orientation;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Test case that a WebP turned by its EXIF chunk and a GIF are one frame for the face index, see
 * issues #142 and #190, and that their originals are decoded by region and subsampled.
 */
@SuppressWarnings("javadoc")
public class TestWebpFaceFrame extends TestCase {

	private static final File FIXTURES = new File("src/test/fixtures/webp-gif");

	private Path _dir;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_dir = Files.createTempDirectory("valbum-webp-faces");
	}

	@Override
	protected void tearDown() throws Exception {
		try (java.util.stream.Stream<Path> files = Files.walk(_dir)) {
			files.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		super.tearDown();
	}

	public void testTheRawBoxAndThePreviewAgree() throws Exception {
		File file = copy("exif.webp");

		Orientation exif = FaceIndex.exifOrientation(file);
		assertEquals("The EXIF chunk's orientation 6", Orientation.ROT_L, exif);
		int[] raw = FaceIndex.rasterOf(file);
		assertEquals(64, raw[0]);
		assertEquals(96, raw[1]);
		assertTrue(FaceIndex.isPhotograph(file));

		// The red quadrant, shown at the top left, lies at the bottom left of the raw raster.
		double[] rawBox = { 0.05, 0.55, 0.4, 0.4 };
		double[] upright = Faces.toUpright(exif, rawBox[0], rawBox[1], rawBox[2], rawBox[3]);
		BufferedImage preview = ImageIO.read(PreviewCache.createPreview(file));
		int cx = (int) ((upright[0] + upright[2] / 2) * preview.getWidth());
		int cy = (int) ((upright[1] + upright[3] / 2) * preview.getHeight());
		assertTrue("The upright box is in the top left quadrant: " + cx + "," + cy,
			cx < preview.getWidth() / 2 && cy < preview.getHeight() / 2);
		TestHeifDecoder.assertColour(TestHeifDecoder.RED, preview, cx, cy);

		Originals.Region region = Originals.decodeLongSide(file, rawBox[0] * raw[0], rawBox[1] * raw[1],
			(rawBox[0] + rawBox[2]) * raw[0], (rawBox[1] + rawBox[3]) * raw[1], 32);
		BufferedImage crop = region.getImage();
		TestHeifDecoder.assertColour(TestHeifDecoder.RED, crop, crop.getWidth() / 2, crop.getHeight() / 2);
		assertEquals(raw[0], region.getRawWidth());
		assertEquals(raw[1], region.getRawHeight());
	}

	public void testARegionIsDecodedSubsampled() throws Exception {
		for (String name : new String[] { "lossy.webp", "alpha.webp", "still.gif", "animated.gif" }) {
			File file = copy(name);
			// The bottom right quarter, at most 12 pixels on its long side: subsampled by four.
			Originals.Region region = Originals.decodeLongSide(file, 48, 32, 96, 64, 12);
			BufferedImage image = region.getImage();
			assertEquals(name, 12, image.getWidth());
			assertEquals(name, 8, image.getHeight());
			int rgb = image.getRGB(6, 4) & 0xFFFFFF;
			if (name.startsWith("alpha")) {
				// Transparent there: white, as on the preview.
				assertEquals(name, 0xFFFFFF, rgb);
				assertFalse(image.getColorModel().hasAlpha());
			} else {
				TestHeifDecoder.assertColour(0xFFFF00, image, 6, 4);
			}
		}
	}

	/**
	 * The raw raster of a GIF is its canvas, and a region of it shows the frame where it lies on the
	 * canvas and white elsewhere, as the preview does (issue #207).
	 */
	public void testAGifFrameSmallerThanItsCanvasIsReadOnTheCanvas() throws Exception {
		File file = copy("offset.gif");
		int[] raw = FaceIndex.rasterOf(file);
		assertEquals(96, raw[0]);
		assertEquals(64, raw[1]);

		// The whole canvas, every fourth pixel: 24 x 16.
		Originals.Region whole = Originals.decodeLongSide(file, 0, 0, 96, 64, 24);
		assertEquals(4, whole.getSampling());
		assertEquals(24, whole.getImage().getWidth());
		assertEquals(16, whole.getImage().getHeight());
		de.haumacher.imageServer.TestWebpGif.assertOffsetGif(whole.getImage(), 4);

		// A region straddling the frame's left edge, every third pixel: white, then red.
		Originals.Region edge = Originals.decodeLongSide(file, 20, 18, 44, 30, 8);
		assertEquals(3, edge.getSampling());
		BufferedImage image = edge.getImage();
		assertEquals(8, image.getWidth());
		assertEquals(4, image.getHeight());
		// Canvas columns 20, 23, ..., 41: the frame begins at 32, the fifth sample (32) is red.
		de.haumacher.imageServer.TestWebpGif.assertWhite(image, 3, 1);
		TestHeifDecoder.assertColour(TestHeifDecoder.RED, image, 4, 1);
		TestHeifDecoder.assertColour(TestHeifDecoder.RED, image, 7, 1);

		// Outside the frame altogether.
		Originals.Region outside = Originals.decodeLongSide(file, 0, 0, 30, 14, 30);
		de.haumacher.imageServer.TestWebpGif.assertWhite(outside.getImage(), 10, 5);
		assertEquals("The face index's preview look sees the same picture.", 96,
			ImageIO.read(PreviewCache.createPreview(file)).getWidth());

		// An animated WebP's first frame on its canvas, the same picture.
		File webp = copy("offset.webp");
		int[] canvas = FaceIndex.rasterOf(webp);
		assertEquals(96, canvas[0]);
		assertEquals(64, canvas[1]);
		de.haumacher.imageServer.TestWebpGif.assertOffsetGif(
			Originals.decodeLongSide(webp, 0, 0, 96, 64, 24).getImage(), 4);
	}

	private File copy(String name) throws Exception {
		File file = _dir.resolve(name).toFile();
		Files.copy(new File(FIXTURES, name).toPath(), file.toPath());
		return file;
	}
}
