/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.heif;

import static de.haumacher.imageServer.heif.TestHeifDecoder.BLUE;
import static de.haumacher.imageServer.heif.TestHeifDecoder.GREEN;
import static de.haumacher.imageServer.heif.TestHeifDecoder.RED;
import static de.haumacher.imageServer.heif.TestHeifDecoder.YELLOW;
import static de.haumacher.imageServer.heif.TestHeifDecoder.assertColour;
import static de.haumacher.imageServer.heif.TestHeifDecoder.assertQuadrants;

import de.haumacher.imageServer.coded.CodedPictures;
import de.haumacher.imageServer.shared.model.Orientation;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Test case for reading and decoding the AVIF fixtures of issue #193, see
 * <code>src/test/fixtures/avif/README.md</code>.
 *
 * <p>
 * The structure is read without a decoder. The pixels need a software AV1 decoder in the FFmpeg
 * program, which the bundled FFmpeg 5.1 does not have (see {@link HeifDecoder}); those tests run
 * where one is there — the program named by the system property {@value #PROGRAM_PROPERTY}, e.g.
 * the <code>ffmpeg</code> of an unpacked <code>org.bytedeco:ffmpeg:6.0-1.5.9</code> platform jar —
 * and say loudly that they were skipped otherwise.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestAvifDecoder extends TestCase {

	/** Where the AVIF fixtures lie. */
	public static final File FIXTURES = new File("src/test/fixtures/avif");

	/** The system property naming an FFmpeg program with a software AV1 decoder. */
	public static final String PROGRAM_PROPERTY = "valbum.test.av1Ffmpeg";

	@Override
	protected void tearDown() throws Exception {
		HeifDecoder.setProgramLocator(null);
		super.tearDown();
	}

	/**
	 * Whether AV1 can be decoded in this test: the program of {@value #PROGRAM_PROPERTY} put in place
	 * where it is set; a loud line where nothing can.
	 */
	public static boolean av1Decoder(String test) {
		String program = System.getProperty(PROGRAM_PROPERTY);
		if (program != null && !program.isBlank()) {
			HeifDecoder.setProgramLocator(() -> program);
		}
		String unavailable = HeifDecoder.av1Unavailability();
		if (unavailable != null) {
			System.err.println("SKIPPED " + test + ": " + unavailable + " Set -D" + PROGRAM_PROPERTY
				+ "=<an ffmpeg with libdav1d or libaom-av1> to run it.");
			return false;
		}
		return true;
	}

	public void testTheBundledProgramSaysWhyItCannotDecodeAv1() {
		String unavailable = HeifDecoder.av1Unavailability();
		if (unavailable != null) {
			assertTrue(unavailable, unavailable.startsWith("AVIF pictures cannot be decoded on this server"));
			assertTrue(unavailable, unavailable.contains("AV1 decoder"));
		}
		// HEVC is not affected.
		assertNull(HeifDecoder.unavailability());
		assertEquals(unavailable, CodedPictures.unavailability("x.AVIF"));
		assertNull(CodedPictures.unavailability("x.heic"));
	}

	public void testTheStructureIsReadWithoutADecoder() throws IOException {
		HeifDecoder.setProgramLocator(() -> {
			throw new IOException("no program here");
		});
		HeifFile single = HeifFile.read(new File(FIXTURES, "single.avif"));
		assertTrue(single.isAv1());
		assertEquals(96, single.getRawWidth());
		assertEquals(64, single.getRawHeight());
		assertEquals(Orientation.IDENTITY, single.getOrientation());
		assertEquals(1, single.getTiles().size());
		assertNull(single.getAlpha());
		assertEquals(Boolean.TRUE, single.getFullRange());
		assertEquals(6, single.getMatrix());
		assertNotNull("The EXIF block is found.", single.getExif());

		HeifFile grid = HeifFile.read(new File(FIXTURES, "grid.avif"));
		assertEquals(180, grid.getRawWidth());
		assertEquals(120, grid.getRawHeight());
		assertEquals(3, grid.getColumns());
		assertEquals(2, grid.getRows());
		assertEquals(6, grid.getTiles().size());

		HeifFile rotated = HeifFile.read(new File(FIXTURES, "rotated.avif"));
		assertEquals("irot 1, not the EXIF orientation 8 inside", Orientation.ROT_R, rotated.getOrientation());
		assertEquals(120, rotated.getDisplayWidth());
		assertEquals(180, rotated.getDisplayHeight());

		HeifFile alpha = HeifFile.read(new File(FIXTURES, "alpha.avif"));
		assertNotNull("The auxl item of the alpha type is the alpha channel.", alpha.getAlpha());
		assertTrue(alpha.getAlpha().isAv1());
		assertFalse(alpha.isPremultiplied());

		HeifFile tenBit = HeifFile.read(new File(FIXTURES, "ten-bit.avif"));
		assertTrue(tenBit.isAv1());
		assertEquals(96, tenBit.getDisplayWidth());

		// Without a decoder every decode says why.
		try {
			single.decodeRaw(new File(FIXTURES, "single.avif"), 10, 10);
			fail("Nothing can be decoded.");
		} catch (IOException ex) {
			assertTrue(ex.getMessage(), ex.getMessage().contains("AVIF pictures cannot be decoded"));
			assertTrue(ex.getMessage(), ex.getMessage().contains("no program here"));
		}
	}

	public void testTheAv1StreamIsATemporalUnitPerTile() throws IOException {
		HeifFile grid = HeifFile.read(new File(FIXTURES, "grid.avif"));
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		HeifDecoder.feed(new File(FIXTURES, "grid.avif"), grid.getImage(), out);
		byte[] stream = out.toByteArray();
		// Walk the OBUs: six temporal delimiters, each followed by a sequence header.
		int delimiters = 0;
		int at = 0;
		int previous = -1;
		while (at < stream.length) {
			int header = stream[at] & 0xFF;
			int type = (header >> 3) & 0xF;
			assertTrue("Every OBU carries its size.", (header & 2) != 0);
			int extension = (header >> 2) & 1;
			at += 1 + extension;
			long size = 0;
			int shift = 0;
			int b;
			do {
				b = stream[at++] & 0xFF;
				size |= (long) (b & 0x7F) << shift;
				shift += 7;
			} while ((b & 0x80) != 0);
			if (type == 2) {
				delimiters++;
			}
			if (previous == 2) {
				assertEquals("A sequence header behind every delimiter.", 1, type);
			}
			previous = type;
			at += (int) size;
		}
		assertEquals(stream.length, at);
		assertEquals(6, delimiters);
	}

	public void testSingle() throws IOException {
		if (!av1Decoder(getName())) {
			return;
		}
		File file = new File(FIXTURES, "single.avif");
		assertQuadrants(HeifDecoder.decodeRaw(file, HeifFile.read(file), 96, 64));
	}

	public void testGridAndRegion() throws IOException {
		if (!av1Decoder(getName())) {
			return;
		}
		File file = new File(FIXTURES, "grid.avif");
		HeifFile heif = HeifFile.read(file);
		assertQuadrants(HeifDecoder.decodeRaw(file, heif, 180, 120));
		assertQuadrants(HeifDecoder.decodeRaw(file, heif, 90, 60));
		BufferedImage region = HeifDecoder.decodeRaw(file, heif, 100, 70, 80, 50, 40, 25);
		assertColour(YELLOW, region, 1, 1);
		assertColour(YELLOW, region, 38, 23);
	}

	public void testRotatedOnce() throws IOException {
		if (!av1Decoder(getName())) {
			return;
		}
		File file = new File(FIXTURES, "rotated.avif");
		HeifFile heif = HeifFile.read(file);
		BufferedImage raw = HeifDecoder.decodeRaw(file, heif, 180, 120);
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

	public void testAlphaIsShownOnWhite() throws IOException {
		if (!av1Decoder(getName())) {
			return;
		}
		File file = new File(FIXTURES, "alpha.avif");
		HeifFile heif = HeifFile.read(file);
		BufferedImage raw = HeifDecoder.decodeRaw(file, heif, 96, 64);
		assertColour(RED, raw, 2, 2);
		assertColour(GREEN, raw, 93, 2);
		assertColour(BLUE, raw, 2, 61);
		assertWhite(raw, 93, 61);

		File jpeg = File.createTempFile("display", ".jpg");
		try {
			HeifDecoder.writeUprightJpeg(file, heif, 96, 64, jpeg);
			BufferedImage shown = ImageIO.read(jpeg);
			assertColour(RED, shown, 2, 2);
			assertWhite(shown, 93, 61);
		} finally {
			jpeg.delete();
		}
	}

	public void testTenBit() throws IOException {
		if (!av1Decoder(getName())) {
			return;
		}
		File file = new File(FIXTURES, "ten-bit.avif");
		assertQuadrants(HeifDecoder.decodeRaw(file, HeifFile.read(file), 96, 64));
	}

	public void testOnBackground() {
		byte[] bgr = { 0, 0, (byte) 255, 0, 0, (byte) 255, 0, 0, (byte) 128 };
		HeifDecoder.onBackground(bgr, new byte[] { (byte) 255, 0, (byte) 128 }, false);
		// Opaque red stays red, transparent is white, half covered is between.
		assertEquals(255, bgr[2] & 0xFF);
		assertEquals(0, bgr[0] & 0xFF);
		assertEquals(255, bgr[3] & 0xFF);
		assertEquals(255, bgr[5] & 0xFF);
		assertEquals(127, bgr[6] & 0xFF);
		assertEquals(191, bgr[8] & 0xFF);
	}

	/** Asserts that the given pixel is white, the background a transparent pixel is shown on. */
	public static void assertWhite(BufferedImage image, int x, int y) {
		int rgb = image.getRGB(x, y);
		for (int shift = 0; shift <= 16; shift += 8) {
			assertTrue("White at " + x + "," + y + ": " + Integer.toHexString(rgb & 0xFFFFFF),
				((rgb >> shift) & 0xFF) > 235);
		}
	}
}
