/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Writing a JPEG orientation into a file, for the tests.
 *
 * <p>
 * Nothing in this server ever writes EXIF — the originals are never modified — so this lives here
 * and nowhere else. It is what the orientation test of issue #124 needs: a photograph whose pixels
 * lie on their side and whose file says so, which is what every phone produces and what no image
 * library on hand can write.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
final class Exif {

	private Exif() {
		// Static utility.
	}

	/**
	 * Writes the given JPEG with an EXIF orientation of the given code.
	 *
	 * <p>
	 * The minimal thing that is a valid EXIF: an <code>APP1</code> segment right behind the start
	 * of the image, holding a big-endian TIFF header and one IFD with the single tag
	 * <code>0x0112</code>. The pixels are copied untouched.
	 * </p>
	 *
	 * @param source
	 *        The JPEG to read; it must not carry an <code>APP1</code> segment of its own.
	 * @param target
	 *        Where to write it.
	 * @param code
	 *        The JPEG orientation code, <code>1</code> to <code>8</code>.
	 */
	static void writeOrientation(File source, File target, int code) throws IOException {
		byte[] jpeg = Files.readAllBytes(source.toPath());
		if (jpeg.length < 2 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
			throw new IOException("Not a JPEG: " + source);
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(jpeg, 0, 2);
		out.write(app1(code));
		out.write(jpeg, 2, jpeg.length - 2);
		Files.write(target.toPath(), out.toByteArray());
	}

	/** The <code>APP1</code> segment carrying nothing but the orientation. */
	private static byte[] app1(int code) {
		byte[] tiff = new byte[] {
			// "MM", the big-endian marker, and the TIFF magic 42.
			'M', 'M', 0x00, 0x2A,
			// The offset of the first directory, counted from the start of the TIFF header.
			0x00, 0x00, 0x00, 0x08,
			// One entry.
			0x00, 0x01,
			// Tag 0x0112 (orientation), type 3 (short), count 1, the value in the first two bytes.
			0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, (byte) ((code >> 8) & 0xFF), (byte) (code & 0xFF),
			0x00, 0x00,
			// No further directory.
			0x00, 0x00, 0x00, 0x00 };
		byte[] header = new byte[] { 'E', 'x', 'i', 'f', 0x00, 0x00 };
		int length = 2 + header.length + tiff.length;
		byte[] result = new byte[2 + length];
		result[0] = (byte) 0xFF;
		result[1] = (byte) 0xE1;
		result[2] = (byte) ((length >> 8) & 0xFF);
		result[3] = (byte) (length & 0xFF);
		System.arraycopy(header, 0, result, 4, header.length);
		System.arraycopy(tiff, 0, result, 4 + header.length, tiff.length);
		return result;
	}
}
