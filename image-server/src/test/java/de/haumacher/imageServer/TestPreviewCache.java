/*
 * Copyright (c) 2022 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.TimeZone;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Test case for {@link PreviewCache}.
 */
@SuppressWarnings("javadoc")
public class TestPreviewCache extends TestCase {

	/**
	 * The generated video fixture, see
	 * <code>test.de.haumacher.valbum.GenerateTestAlbum#recordTinyVideo(File)</code>.
	 */
	private static final File VIDEO_FIXTURE =
		new File("src/test/fixtures/test-album/2005-08-24 Blumen und Fliegen/MVI_0450.mp4");

	private static final File IMAGE_FIXTURE =
		new File("src/test/fixtures/test-album/2005-08-24 Blumen und Fliegen/IMG_0417.JPG");

	/** 2022-05-15 13:30:00 Europe/Berlin. */
	private static final long LAST_UPDATE = 1652614200000L;

	/**
	 * The cut-off is a constant, not something parsed in the runtime's locale: the former literal
	 * <code>"2022-05-15 13:30:00 MEST"</code> was unparseable wherever the zone name was unknown,
	 * and the fallback 0 invalidated every cached preview (issue #67).
	 */
	public void testUpdateDate() {
		assertEquals(LAST_UPDATE, PreviewCache.lastUpdate());

		Locale locale = Locale.getDefault();
		TimeZone zone = TimeZone.getDefault();
		try {
			Locale.setDefault(new Locale("th", "TH"));
			TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
			assertEquals("The cut-off must not depend on the locale or the default time zone.",
				LAST_UPDATE, PreviewCache.lastUpdate());
		} finally {
			Locale.setDefault(locale);
			TimeZone.setDefault(zone);
		}
	}

	/**
	 * The factor the original is sampled with, which bounds the decoded raster (issue #68).
	 */
	public void testSubsampling() {
		// A 6000x4500 original for a 800x600 preview.
		assertEquals(7, PreviewCache.subsampling(6000, 4500, 800, 600));
		// A 3000x6000 portrait original for a 600x1200 preview.
		assertEquals(5, PreviewCache.subsampling(3000, 6000, 600, 1200));
		// Nothing to save: the original is the preview.
		assertEquals(1, PreviewCache.subsampling(800, 600, 800, 600));
		// Smaller than the preview: never sampled away, never up-scaled here.
		assertEquals(1, PreviewCache.subsampling(601, 450, 801, 600));
		// A 50 megapixel phone photo (8160x6120) for a 800x600 preview.
		assertEquals(10, PreviewCache.subsampling(8160, 6120, 800, 600));
	}

	/**
	 * Whatever the original's size, the decoded raster covers the preview and stays below twice
	 * its size in each dimension.
	 */
	public void testDecodedRasterBounded() {
		assertBounded(6000, 4500, 800, 600);
		assertBounded(3000, 6000, 600, 1200);
		assertBounded(8160, 6120, 800, 600);
		assertBounded(800, 600, 800, 600);
	}

	private static void assertBounded(int origWidth, int origHeight, int previewWidth, int previewHeight) {
		int n = PreviewCache.subsampling(origWidth, origHeight, previewWidth, previewHeight);
		int decodedWidth = decoded(origWidth, n);
		int decodedHeight = decoded(origHeight, n);
		String what = origWidth + "x" + origHeight + " sampled by " + n + " is "
			+ decodedWidth + "x" + decodedHeight;
		assertTrue(what + ", which does not cover the preview width " + previewWidth,
			decodedWidth >= previewWidth);
		assertTrue(what + ", which does not cover the preview height " + previewHeight,
			decodedHeight >= previewHeight);
		assertTrue(what + ", which is more than twice the preview width " + previewWidth,
			decodedWidth <= 2 * previewWidth);
		assertTrue(what + ", which is more than twice the preview height " + previewHeight,
			decodedHeight <= 2 * previewHeight);
	}

	/** The raster size a reader produces when sampling every <code>n</code>-th pixel. */
	private static int decoded(int orig, int n) {
		return (orig + n - 1) / n;
	}

	/**
	 * A photo of the size a current phone produces: the preview must come out exactly as before,
	 * and the original must be untouched. The heap this needs is bounded by the preview, not by
	 * the original's pixel count (issue #68).
	 */
	public void testLargeLandscapePreview() throws Exception {
		Path dir = Files.createTempDirectory("valbum-preview-large");
		try {
			File original = new File(dir.toFile(), "large.jpg");
			writeJpeg(quadrants(6000, 4500), original);
			byte[] before = Files.readAllBytes(original.toPath());

			BufferedImage preview = readPreview(original);
			assertEquals(800, preview.getWidth());
			assertEquals(600, preview.getHeight());
			assertQuadrants(preview);

			assertTrue("The original must not be modified.",
				Arrays.equals(before, Files.readAllBytes(original.toPath())));
		} finally {
			delete(dir);
		}
	}

	/** A portrait original gets the double preview height, see MAX_PORTRAIT_UNIT_WIDTH. */
	public void testLargePortraitPreview() throws Exception {
		Path dir = Files.createTempDirectory("valbum-preview-portrait");
		try {
			File original = new File(dir.toFile(), "portrait.jpg");
			writeJpeg(quadrants(3000, 6000), original);
			byte[] before = Files.readAllBytes(original.toPath());

			BufferedImage preview = readPreview(original);
			assertEquals(600, preview.getWidth());
			assertEquals(1200, preview.getHeight());
			assertQuadrants(preview);

			assertTrue("The original must not be modified.",
				Arrays.equals(before, Files.readAllBytes(original.toPath())));
		} finally {
			delete(dir);
		}
	}

	/** The PNG path reads its size from the PNG header and samples just the same. */
	public void testPngPreview() throws Exception {
		Path dir = Files.createTempDirectory("valbum-preview-png");
		try {
			File original = new File(dir.toFile(), "large.png");
			assertTrue("Cannot write a PNG.", ImageIO.write(quadrants(2000, 1500), "png", original));
			byte[] before = Files.readAllBytes(original.toPath());

			assertEquals(2, PreviewCache.subsampling(2000, 1500, 800, 600));

			BufferedImage preview = readPreview(original);
			assertEquals(800, preview.getWidth());
			assertEquals(600, preview.getHeight());
			assertQuadrants(preview);

			assertTrue("The original must not be modified.",
				Arrays.equals(before, Files.readAllBytes(original.toPath())));
		} finally {
			delete(dir);
		}
	}

	/**
	 * An image whose EXIF orientation turns it upright: the preview is built in display
	 * orientation, and the sampled decode must place the pixels exactly where the full decode did.
	 *
	 * <p>
	 * Both cases have the same shape and therefore the same preview; only the first is sampled
	 * (factor 3), the second is decoded whole (factor 1) as the former code always did. Same
	 * expectation for both, so the sampled path is pinned against the unsampled one.
	 * </p>
	 */
	public void testOrientationPreview() throws Exception {
		assertOrientedPreview(4000, 3000, 3);
		assertOrientedPreview(1600, 1200, 1);
	}

	private void assertOrientedPreview(int rawWidth, int rawHeight, int expectedFactor) throws Exception {
		// The display size is the raw size swapped, see PreviewCache#getImageDimension.
		assertEquals(expectedFactor, PreviewCache.subsampling(rawHeight, rawWidth, 900, 1200));

		Path dir = Files.createTempDirectory("valbum-preview-oriented");
		try {
			File original = new File(dir.toFile(), "oriented.jpg");
			// Orientation 6 is Orientation.ROT_L, a quarter turn clockwise in device coordinates.
			writeJpegWithOrientation(quadrants(rawWidth, rawHeight), original, 6);
			byte[] before = Files.readAllBytes(original.toPath());

			BufferedImage preview = readPreview(original);
			assertEquals(900, preview.getWidth());
			assertEquals(1200, preview.getHeight());

			// The raw quadrants rotate: raw bottom-left becomes the preview's top-left and so on.
			assertQuadrant(preview, 0, 0, BOTTOM_LEFT);
			assertQuadrant(preview, 1, 0, TOP_LEFT);
			assertQuadrant(preview, 1, 1, TOP_RIGHT);
			assertQuadrant(preview, 0, 1, BOTTOM_RIGHT);

			assertTrue("The original must not be modified.",
				Arrays.equals(before, Files.readAllBytes(original.toPath())));
		} finally {
			delete(dir);
		}
	}

	private static final Color TOP_LEFT = new Color(220, 20, 20);

	private static final Color TOP_RIGHT = new Color(20, 200, 20);

	private static final Color BOTTOM_LEFT = new Color(20, 20, 220);

	private static final Color BOTTOM_RIGHT = new Color(230, 230, 20);

	/** The quadrants of an un-rotated original, where they were. */
	private static void assertQuadrants(BufferedImage preview) {
		assertQuadrant(preview, 0, 0, TOP_LEFT);
		assertQuadrant(preview, 1, 0, TOP_RIGHT);
		assertQuadrant(preview, 0, 1, BOTTOM_LEFT);
		assertQuadrant(preview, 1, 1, BOTTOM_RIGHT);
	}

	private static void assertQuadrant(BufferedImage preview, int column, int row, Color expected) {
		int x = preview.getWidth() * (2 * column + 1) / 4;
		int y = preview.getHeight() * (2 * row + 1) / 4;
		Color actual = new Color(preview.getRGB(x, y));
		String what = "Quadrant (" + column + "," + row + ") is " + actual + ", expected " + expected + ".";
		assertTrue(what, Math.abs(actual.getRed() - expected.getRed()) < 48);
		assertTrue(what, Math.abs(actual.getGreen() - expected.getGreen()) < 48);
		assertTrue(what, Math.abs(actual.getBlue() - expected.getBlue()) < 48);
	}

	/** An image of the given size, each quadrant in its own colour. */
	private static BufferedImage quadrants(int width, int height) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
		Graphics2D g = (Graphics2D) image.getGraphics();
		int w = width / 2;
		int h = height / 2;
		g.setColor(TOP_LEFT);
		g.fillRect(0, 0, w, h);
		g.setColor(TOP_RIGHT);
		g.fillRect(w, 0, width - w, h);
		g.setColor(BOTTOM_LEFT);
		g.fillRect(0, h, w, height - h);
		g.setColor(BOTTOM_RIGHT);
		g.fillRect(w, h, width - w, height - h);
		g.dispose();
		return image;
	}

	private static void writeJpeg(BufferedImage image, File file) throws Exception {
		assertTrue("Cannot write a JPEG.", ImageIO.write(image, "jpg", file));
	}

	/**
	 * Writes the image as a JPEG carrying nothing but an EXIF orientation.
	 *
	 * <p>
	 * Hand-built, because nothing on the class path writes EXIF: an <code>APP1</code> segment
	 * with the <code>Exif</code> preamble, a big-endian TIFF header and a single IFD0 entry
	 * (tag 0x0112, SHORT) is inserted right behind the start-of-image marker.
	 * </p>
	 */
	private static void writeJpegWithOrientation(BufferedImage image, File file, int orientation)
			throws Exception {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		assertTrue("Cannot write a JPEG.", ImageIO.write(image, "jpg", buffer));
		byte[] jpeg = buffer.toByteArray();
		assertEquals("Not a JPEG.", 0xFFD8, ((jpeg[0] & 0xFF) << 8) | (jpeg[1] & 0xFF));

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(jpeg, 0, 2);
		// APP1 marker and the segment length (everything but the marker).
		out.write(0xFF);
		out.write(0xE1);
		out.write(0);
		out.write(34);
		out.write(new byte[] { 'E', 'x', 'i', 'f', 0, 0 });
		// Big-endian TIFF header, IFD0 at offset 8.
		out.write(new byte[] { 'M', 'M', 0, 42, 0, 0, 0, 8 });
		// One entry: tag 0x0112 (orientation), type 3 (SHORT), count 1, value in the value field.
		out.write(new byte[] { 0, 1 });
		out.write(new byte[] { 0x01, 0x12, 0, 3, 0, 0, 0, 1 });
		out.write(new byte[] { 0, (byte) orientation, 0, 0 });
		// No next IFD.
		out.write(new byte[] { 0, 0, 0, 0 });
		out.write(jpeg, 2, jpeg.length - 2);

		Files.write(file.toPath(), out.toByteArray());
	}

	private static BufferedImage readPreview(File original) throws Exception {
		File preview = PreviewCache.createPreview(original);
		assertTrue("No preview created.", preview.exists());
		assertEquals("The preview belongs beside the original, in the cache directory.",
			PreviewCache.CACHE_DIRECTORY_NAME, preview.getParentFile().getName());
		assertEquals("preview-" + original.getName(), preview.getName());
		BufferedImage image = ImageIO.read(preview);
		assertNotNull("Unreadable preview.", image);
		return image;
	}

	private static void delete(Path dir) throws Exception {
		try (Stream<Path> files = Files.walk(dir)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
	}

	public void testVideoPreview() throws Exception {
		assertPreview(VIDEO_FIXTURE);
	}

	public void testImagePreview() throws Exception {
		assertPreview(IMAGE_FIXTURE);
	}

	/**
	 * Creates a preview for a copy of the given fixture in a temporary folder.
	 *
	 * <p>
	 * The copy keeps the preview cache out of the git-tracked fixture album.
	 * </p>
	 */
	private static void assertPreview(File fixture) throws Exception {
		assertTrue("Missing fixture: " + fixture.getAbsolutePath(), fixture.exists());

		Path dir = Files.createTempDirectory("valbum-preview-test");
		try {
			File copy = new File(dir.toFile(), fixture.getName());
			Files.copy(fixture.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING);

			File preview = PreviewCache.createPreview(copy);
			assertTrue("No preview created for " + fixture.getName() + ".", preview.exists());
			assertTrue("Empty preview created for " + fixture.getName() + ".", preview.length() > 0);
			assertEquals("The original must not be modified.", fixture.length(), copy.length());
		} finally {
			try (Stream<Path> files = Files.walk(dir)) {
				files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
	}

}
