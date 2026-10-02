/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.heif;

import de.haumacher.imageServer.shared.model.Orientation;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Test case for reading and decoding the HEIC fixtures of issue #186, see
 * <code>src/test/fixtures/heic/README.md</code>.
 *
 * <p>
 * Every fixture shows four quadrants — red top left, green top right, blue bottom left, yellow
 * bottom right — once it is upright, so a probe of each corner tells a wrongly stitched grid, a
 * wrong crop and a wrong turn apart.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestHeifDecoder extends TestCase {

	/** Where the HEIC fixtures lie. */
	public static final File FIXTURES = new File("src/test/fixtures/heic");

	public static final int RED = 0xFF0000;

	static final int GREEN = 0x00FF00;

	static final int BLUE = 0x0000FF;

	static final int YELLOW = 0xFFFF00;

	@Override
	protected void tearDown() throws Exception {
		HeifDecoder.setProgramLocator(null);
		super.tearDown();
	}

	public void testTheBundledProgramDecodesHevc() {
		assertNull(HeifDecoder.unavailability());
	}

	public void testSingle() throws IOException {
		HeifFile heif = HeifFile.read(new File(FIXTURES, "single.heic"));
		assertEquals(96, heif.getRawWidth());
		assertEquals(64, heif.getRawHeight());
		assertEquals(Orientation.IDENTITY, heif.getOrientation());
		assertEquals(1, heif.getTiles().size());
		assertNotNull("The EXIF block is found.", heif.getExif());
		assertEquals("The TIFF header comes first.", 'M', (char) heif.getExif()[0]);
		assertEquals(Boolean.TRUE, heif.getFullRange());
		assertEquals(6, heif.getMatrix());

		assertQuadrants(HeifDecoder.decodeRaw(new File(FIXTURES, "single.heic"), heif, 96, 64));
	}

	public void testGrid() throws IOException {
		File file = new File(FIXTURES, "grid.heic");
		HeifFile heif = HeifFile.read(file);
		assertEquals(180, heif.getRawWidth());
		assertEquals(120, heif.getRawHeight());
		assertEquals(3, heif.getColumns());
		assertEquals(2, heif.getRows());
		assertEquals(6, heif.getTiles().size());
		assertEquals(Orientation.IDENTITY, heif.getOrientation());

		assertQuadrants(HeifDecoder.decodeRaw(file, heif, 180, 120));
		// Scaled down, the quadrants stay where they are.
		assertQuadrants(HeifDecoder.decodeRaw(file, heif, 90, 60));
	}

	public void testRegion() throws IOException {
		File file = new File(FIXTURES, "grid.heic");
		HeifFile heif = HeifFile.read(file);
		// The bottom right quarter of the picture is yellow only.
		BufferedImage region = HeifDecoder.decodeRaw(file, heif, 100, 70, 80, 50, 40, 25);
		assertEquals(40, region.getWidth());
		assertEquals(25, region.getHeight());
		assertColour(YELLOW, region, 1, 1);
		assertColour(YELLOW, region, 38, 23);
	}

	public void testRotatedByIrot() throws IOException {
		File file = new File(FIXTURES, "rotated.heic");
		HeifFile heif = HeifFile.read(file);
		// The raw raster is the stored one, on its side; the EXIF orientation inside is not read.
		assertEquals(180, heif.getRawWidth());
		assertEquals(120, heif.getRawHeight());
		assertEquals(Orientation.ROT_R, heif.getOrientation());
		assertEquals(120, heif.getDisplayWidth());
		assertEquals(180, heif.getDisplayHeight());

		BufferedImage raw = HeifDecoder.decodeRaw(file, heif, 180, 120);
		// The shown top left (red) is the stored top right.
		assertColour(RED, raw, 177, 2);
		assertColour(GREEN, raw, 177, 117);
		assertColour(BLUE, raw, 2, 2);
		assertColour(YELLOW, raw, 2, 117);

		File jpeg = File.createTempFile("display", ".jpg");
		try {
			HeifDecoder.writeUprightJpeg(file, heif, 120, 180, jpeg);
			BufferedImage shown = ImageIO.read(jpeg);
			assertEquals(120, shown.getWidth());
			assertEquals(180, shown.getHeight());
			assertQuadrants(shown);
		} finally {
			jpeg.delete();
		}
	}

	public void testMirroredByImir() throws IOException {
		File file = new File(FIXTURES, "mirrored.heic");
		HeifFile heif = HeifFile.read(file);
		assertEquals(Orientation.FLIP_H, heif.getOrientation());

		BufferedImage raw = HeifDecoder.decodeRaw(file, heif, 96, 64);
		assertColour(GREEN, raw, 2, 2);
		assertColour(RED, raw, 93, 2);

		File jpeg = File.createTempFile("display", ".jpg");
		try {
			HeifDecoder.writeUprightJpeg(file, heif, 96, 64, jpeg);
			assertQuadrants(ImageIO.read(jpeg));
		} finally {
			jpeg.delete();
		}
	}

	public void testEveryOrientationIsTurnedLikeTheJpegPath() throws IOException {
		// The display rendition turns by FFmpeg's filters, the preview by PreviewCache's transform;
		// both must say the same, for every orientation.
		File file = new File(FIXTURES, "single.heic");
		HeifFile heif = HeifFile.read(file);
		BufferedImage raw = HeifDecoder.decodeRaw(file, heif, 96, 64);
		for (Orientation orientation : Orientation.values()) {
			BufferedImage expected = de.haumacher.imageServer.faces.Originals.upright(raw, orientation);
			File jpeg = File.createTempFile("turn", ".jpg");
			try {
				HeifDecoder.writeUprightJpeg(file, heif, orientation, expected.getWidth(), expected.getHeight(), jpeg);
				BufferedImage actual = ImageIO.read(jpeg);
				assertEquals(orientation.name(), expected.getWidth(), actual.getWidth());
				assertEquals(orientation.name(), expected.getHeight(), actual.getHeight());
				for (int[] p : new int[][] { { 2, 2 }, { expected.getWidth() - 3, 2 }, { 2, expected.getHeight() - 3 },
					{ expected.getWidth() - 3, expected.getHeight() - 3 } }) {
					assertColour(orientation.name(), nearest(expected.getRGB(p[0], p[1])), actual, p[0], p[1]);
				}
			} finally {
				jpeg.delete();
			}
		}
	}

	public void testUnavailableProgram() {
		HeifDecoder.setProgramLocator(() -> {
			throw new IOException("no program here");
		});
		String reason = HeifDecoder.unavailability();
		assertNotNull(reason);
		assertTrue(reason, reason.contains("no program here"));
		try {
			HeifDecoder.decodeRaw(new File(FIXTURES, "single.heic"), HeifFile.read(new File(FIXTURES, "single.heic")),
				10, 10);
			fail("Nothing can be decoded.");
		} catch (IOException ex) {
			assertTrue(ex.getMessage(), ex.getMessage().contains("cannot be decoded"));
		}
	}

	public void testNotAHeif() {
		try {
			HeifFile.read(new File("src/test/fixtures/test-album/generated/image-78.jpg"));
			fail("A JPEG is no HEIF.");
		} catch (IOException ex) {
			// Expected.
		}
	}

	public static void assertQuadrants(BufferedImage image) {
		int w = image.getWidth();
		int h = image.getHeight();
		assertColour(RED, image, 2, 2);
		assertColour(GREEN, image, w - 3, 2);
		assertColour(BLUE, image, 2, h - 3);
		assertColour(YELLOW, image, w - 3, h - 3);
	}

	public static void assertColour(int expected, BufferedImage image, int x, int y) {
		assertColour("", expected, image, x, y);
	}

	static void assertColour(String context, int expected, BufferedImage image, int x, int y) {
		int actual = image.getRGB(x, y) & 0xFFFFFF;
		assertEquals(context + " at " + x + "," + y + ": " + Integer.toHexString(actual), expected, nearest(actual));
	}

	/** The one of the four colours the given pixel is nearest to. */
	static int nearest(int rgb) {
		int best = 0;
		long distance = Long.MAX_VALUE;
		for (int candidate : new int[] { RED, GREEN, BLUE, YELLOW, 0xFF00FF, 0 }) {
			long d = 0;
			for (int shift = 0; shift <= 16; shift += 8) {
				int a = (rgb >> shift) & 0xFF;
				int b = (candidate >> shift) & 0xFF;
				d += (a - b) * (a - b);
			}
			if (d < distance) {
				distance = d;
				best = candidate;
			}
		}
		return best;
	}
}
