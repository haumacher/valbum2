/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.jxl;

import static de.haumacher.imageServer.heif.TestAvifDecoder.assertWhite;
import static de.haumacher.imageServer.heif.TestHeifDecoder.RED;
import static de.haumacher.imageServer.heif.TestHeifDecoder.assertColour;
import static de.haumacher.imageServer.heif.TestHeifDecoder.assertQuadrants;

import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.xmp.XmpDirectory;
import de.haumacher.imageServer.PictureReader;
import de.haumacher.imageServer.PictureTooLargeException;
import de.haumacher.imageServer.coded.CodedPictures;
import de.haumacher.imageServer.shared.model.Orientation;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Test case for reading and decoding the JPEG XL fixtures of issue #193, see
 * <code>src/test/fixtures/jxl/README.md</code>.
 */
@SuppressWarnings("javadoc")
public class TestJxlFile extends TestCase {

	/** Where the JPEG XL fixtures lie. */
	public static final File FIXTURES = new File("src/test/fixtures/jxl");

	private static final int GREEN = 0x00FF00;

	private static final int BLUE = 0x0000FF;

	private static final int YELLOW = 0xFFFF00;

	@Override
	protected void tearDown() throws Exception {
		PictureReader.setBudget(Math.max(1024 * 1024, Runtime.getRuntime().maxMemory() / 2));
		super.tearDown();
	}

	public void testLossy() throws IOException {
		File file = new File(FIXTURES, "lossy.jxl");
		JxlFile jxl = JxlFile.read(file);
		assertEquals(96, jxl.getRawWidth());
		assertEquals(64, jxl.getRawHeight());
		assertEquals(Orientation.IDENTITY, jxl.getOrientation());
		assertFalse("VarDCT", jxl.isModular());
		assertFalse(jxl.hasAlpha());
		assertEquals(96L * 64 * JxlFile.VARDCT_BYTES_PER_PIXEL, jxl.decodeBytes());
		assertDescribed(jxl.metadata());
		assertNotNull("The xml box is read.", jxl.metadata().getFirstDirectoryOfType(XmpDirectory.class));

		assertQuadrants(jxl.decodeRaw(file, 96, 64));
		// Scaled down, the quadrants stay where they are.
		assertQuadrants(jxl.decodeRaw(file, 48, 32));
		// The bottom right quarter is yellow only.
		BufferedImage region = jxl.decodeRaw(file, 60, 40, 30, 20, 15, 10);
		assertEquals(15, region.getWidth());
		assertEquals(10, region.getHeight());
		assertColour(YELLOW, region, 1, 1);
		assertColour(YELLOW, region, 13, 8);
	}

	public void testLosslessWithCompressedMetadata() throws IOException {
		File file = new File(FIXTURES, "lossless.jxl");
		JxlFile jxl = JxlFile.read(file);
		assertTrue("Modular", jxl.isModular());
		assertEquals(96L * 64 * JxlFile.MODULAR_BYTES_PER_PIXEL, jxl.decodeBytes());
		// The Exif box is Brotli-compressed in a brob box.
		assertDescribed(jxl.metadata());
		assertQuadrants(jxl.decodeRaw(file, 96, 64));
	}

	public void testRotatedOnceByTheCodestream() throws IOException {
		File file = new File(FIXTURES, "rotated.jxl");
		JxlFile jxl = JxlFile.read(file);
		// The raw raster is the stored one, on its side; the EXIF orientation 6 inside is not read on top.
		assertEquals(64, jxl.getRawWidth());
		assertEquals(96, jxl.getRawHeight());
		assertEquals(Orientation.ROT_L, jxl.getOrientation());
		assertEquals(96, jxl.getDisplayWidth());
		assertEquals(64, jxl.getDisplayHeight());

		BufferedImage raw = jxl.decodeRaw(file, 64, 96);
		// Orientation 6 turns the stored raster a quarter clockwise: the shown top left (red) is
		// the stored bottom left.
		assertColour(RED, raw, 2, 93);
		assertColour(GREEN, raw, 2, 2);
		assertColour(BLUE, raw, 61, 93);
		assertColour(YELLOW, raw, 61, 2);

		File jpeg = File.createTempFile("display", ".jpg");
		try {
			jxl.writeUprightJpeg(file, 96, 64, jpeg);
			BufferedImage shown = ImageIO.read(jpeg);
			assertEquals(96, shown.getWidth());
			assertEquals(64, shown.getHeight());
			assertQuadrants(shown);
		} finally {
			jpeg.delete();
		}
	}

	public void testAlphaIsShownOnWhite() throws IOException {
		File file = new File(FIXTURES, "alpha.jxl");
		JxlFile jxl = JxlFile.read(file);
		assertTrue(jxl.hasAlpha());
		assertEquals(96L * 64 * (JxlFile.MODULAR_BYTES_PER_PIXEL + JxlFile.ALPHA_BYTES_PER_PIXEL), jxl.decodeBytes());
		BufferedImage raw = jxl.decodeRaw(file, 96, 64);
		assertColour(RED, raw, 2, 2);
		assertColour(GREEN, raw, 93, 2);
		assertColour(BLUE, raw, 2, 61);
		assertWhite(raw, 93, 61);
	}

	public void testABareCodestreamHasNoMetadata() throws IOException {
		File file = new File(FIXTURES, "bare.jxl");
		JxlFile jxl = JxlFile.read(file);
		assertNull(jxl.getExif());
		assertNull(jxl.getXmp());
		assertQuadrants(jxl.decodeRaw(file, 96, 64));
	}

	public void testAPictureLargerThanTheBudgetIsRefusedWithAReason() throws IOException {
		File file = new File(FIXTURES, "large.jxl");
		JxlFile jxl = JxlFile.read(file);
		assertEquals(512L * 512 * JxlFile.MODULAR_BYTES_PER_PIXEL, jxl.decodeBytes());
		assertQuadrants(jxl.decodeRaw(file, 64, 64));

		PictureReader.setBudget(1024 * 1024);
		// 96 x 64 x 128 bytes is 0.75 MB: within the budget.
		assertQuadrants(JxlFile.read(new File(FIXTURES, "lossless.jxl")).decodeRaw(new File(FIXTURES, "lossless.jxl"),
			96, 64));
		try {
			jxl.decodeRaw(file, 64, 64);
			fail("32 MB is more than the budget of 1 MB.");
		} catch (PictureTooLargeException ex) {
			assertTrue(ex.getMessage(), ex.getMessage().startsWith("'large.jxl' is a lossless JPEG XL picture of 512 × 512"));
			assertTrue(ex.getMessage(), ex.getMessage().contains("-Xmx"));
		}
	}

	public void testNotAJxl() {
		try {
			JxlFile.read(new File("src/test/fixtures/test-album/generated/image-78.jpg"));
			fail("A JPEG is no JPEG XL file.");
		} catch (IOException ex) {
			assertTrue(ex.getMessage(), ex.getMessage().contains("image-78.jpg"));
		}
	}

	public void testTheFacadeKnowsIt() throws IOException {
		assertTrue(CodedPictures.handles("x.JXL"));
		assertTrue(CodedPictures.isJxl("x.jxl"));
		assertEquals("image/jxl", CodedPictures.contentType("x.Jxl"));
		assertEquals("image/avif", CodedPictures.contentType("x.avif"));
		assertNull("A JPEG XL picture is always decodable: the decoder is Java.", CodedPictures.unavailability("x.jxl"));
		assertTrue(CodedPictures.read(new File(FIXTURES, "lossy.jxl")) instanceof JxlFile);
	}

	static void assertDescribed(Metadata metadata) {
		ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
		assertNotNull(ifd0);
		assertEquals("Fixture JXL", ifd0.getString(ExifIFD0Directory.TAG_MODEL));
		ExifSubIFDDirectory sub = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
		assertNotNull(sub);
		assertEquals("2024:05:17 14:30:00", sub.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL));
	}
}
