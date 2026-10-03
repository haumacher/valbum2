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
 * program, which the bundled FFmpeg 6.0 of the presets 1.5.9 has on every packaged platform
 * (<code>libaom-av1</code>, issue #210); a machine whose program has none fails those tests with
 * the server's own sentence, see {@link #assertAv1Decoder()}.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestAvifDecoder extends TestCase {

	/** Where the AVIF fixtures lie. */
	public static final File FIXTURES = new File("src/test/fixtures/avif");

	@Override
	protected void tearDown() throws Exception {
		HeifDecoder.setProgramLocator(null);
		super.tearDown();
	}

	/**
	 * Fails with the server's own sentence where the bundled program cannot decode AV1: since the
	 * presets 1.5.9 it can on every packaged platform (issue #210), so a machine where it cannot is
	 * a machine the packages would not serve AVIF on either.
	 */
	public static void assertAv1Decoder() {
		String unavailable = HeifDecoder.av1Unavailability();
		if (unavailable != null) {
			fail("The bundled FFmpeg must decode AV1 (libdav1d or libaom-av1, issue #210): " + unavailable);
		}
	}

	/**
	 * Puts an FFmpeg "program" in place that has an HEVC decoder and no AV1 decoder, the bundled
	 * FFmpeg 5.1 of the presets 1.5.8 as far as {@link HeifDecoder} asks: a shell script answering
	 * <code>-decoders</code>.
	 */
	public static void installProgramWithoutAv1(java.nio.file.Path directory) throws IOException {
		java.nio.file.Path program = directory.resolve("ffmpeg-without-av1");
		java.nio.file.Files.writeString(program, "#!/bin/sh\n"
			+ "echo ' V....D av1                  Alliance for Open Media AV1'\n"
			+ "echo ' VFS..D hevc                 HEVC (High Efficiency Video Coding)'\n");
		program.toFile().setExecutable(true);
		HeifDecoder.setProgramLocator(() -> program.toString());
	}

	public void testTheBundledProgramDecodesAv1() {
		assertAv1Decoder();
		assertNull(HeifDecoder.unavailability());
		assertNull(CodedPictures.unavailability("x.AVIF"));
		assertNull(CodedPictures.unavailability("x.heic"));
	}

	/**
	 * A program without a software AV1 decoder (the native <code>av1</code> one needs a hardware
	 * accelerator and does not count) says why, and HEIC is not affected.
	 */
	public void testAProgramWithoutAnAv1DecoderSaysWhy() throws IOException {
		java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("no-av1");
		try {
			installProgramWithoutAv1(directory);
			String unavailable = HeifDecoder.av1Unavailability();
			assertNotNull(unavailable);
			assertTrue(unavailable, unavailable.startsWith("AVIF pictures cannot be decoded on this server"));
			assertTrue(unavailable, unavailable.contains("no software AV1 decoder"));
			assertNull(HeifDecoder.unavailability());
			assertEquals(unavailable, CodedPictures.unavailability("x.AVIF"));
			assertNull(CodedPictures.unavailability("x.heic"));
		} finally {
			HeifDecoder.setProgramLocator(null);
			java.nio.file.Files.delete(directory.resolve("ffmpeg-without-av1"));
			java.nio.file.Files.delete(directory);
		}
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
		assertAv1Decoder();
		File file = new File(FIXTURES, "single.avif");
		assertQuadrants(HeifDecoder.decodeRaw(file, HeifFile.read(file), 96, 64));
	}

	public void testGridAndRegion() throws IOException {
		assertAv1Decoder();
		File file = new File(FIXTURES, "grid.avif");
		HeifFile heif = HeifFile.read(file);
		assertQuadrants(HeifDecoder.decodeRaw(file, heif, 180, 120));
		assertQuadrants(HeifDecoder.decodeRaw(file, heif, 90, 60));
		BufferedImage region = HeifDecoder.decodeRaw(file, heif, 100, 70, 80, 50, 40, 25);
		assertColour(YELLOW, region, 1, 1);
		assertColour(YELLOW, region, 38, 23);
	}

	public void testRotatedOnce() throws IOException {
		assertAv1Decoder();
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
		assertAv1Decoder();
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
		assertAv1Decoder();
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
