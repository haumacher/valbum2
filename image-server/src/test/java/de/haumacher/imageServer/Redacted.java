/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * An original and the copy Android redacted from it, for the tests of issue #167.
 *
 * <p>
 * What issue #166 found in a sample: the camera, the recording time and the compressed picture as
 * they were, and the GPS block zero-filled &mdash; <code>GPSLatitude 0/0,0/0,0/0</code>, the same
 * for the longitude, and blank reference letters. The picture is encoded once and wrapped twice,
 * so the scan data of both files is the same bytes, exactly as a redaction leaves it.
 * </p>
 */
final class Redacted {

	/** What the original says about where it was taken. */
	static final double LATITUDE = 48.123456;

	static final double LONGITUDE = 8.654321;

	static final String MAKE = "Canon";

	static final String MODEL = "Canon EOS 5D";

	static final String TAKEN = "2024:05:01 10:00:00";

	private Redacted() {
		// Static utility.
	}

	/** A 40x30 picture, encoded once; the colour tells two pictures apart. */
	static byte[] picture(Color color, boolean progressive) throws IOException {
		BufferedImage image = new BufferedImage(40, 30, BufferedImage.TYPE_3BYTE_BGR);
		Graphics2D g = image.createGraphics();
		g.setColor(color);
		g.fillRect(0, 0, 40, 30);
		g.setColor(Color.BLUE);
		g.fillRect(5, 5, 10, 10);
		g.dispose();
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
		try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(buffer)) {
			writer.setOutput(out);
			ImageWriteParam param = writer.getDefaultWriteParam();
			if (progressive) {
				param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
			}
			writer.write(null, new IIOImage(image, null, null), param);
		} finally {
			writer.dispose();
		}
		return buffer.toByteArray();
	}

	/** The picture with the position, as the camera wrote it. */
	static void writeOriginal(File file, byte[] picture) throws IOException {
		wrap(file, picture, tiff(TAKEN, true));
	}

	/** The picture with the GPS block zero-filled, as Android handed it to the app. */
	static void writeRedacted(File file, byte[] picture) throws IOException {
		wrap(file, picture, tiff(TAKEN, false));
	}

	/** The picture with the position and another recording time. */
	static void writeOriginalTakenAt(File file, byte[] picture, String taken) throws IOException {
		wrap(file, picture, tiff(taken, true));
	}

	/** Writes the given JPEG with an <code>APP1</code> carrying the given TIFF block. */
	static void wrap(File file, byte[] jpeg, byte[] tiff) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(jpeg, 0, 2);
		int length = 2 + 6 + tiff.length;
		out.write(0xFF);
		out.write(0xE1);
		out.write((length >> 8) & 0xFF);
		out.write(length & 0xFF);
		out.write(new byte[] { 'E', 'x', 'i', 'f', 0, 0 });
		out.write(tiff);
		out.write(jpeg, 2, jpeg.length - 2);
		Files.write(file.toPath(), out.toByteArray());
	}

	// --- A minimal big-endian TIFF writer. ---

	/** One IFD entry: its value is either the bytes or an IFD the entry points to. */
	private static final class Entry {
		final int _tag;

		final int _type;

		final int _count;

		final byte[] _value;

		final Ifd _child;

		Entry(int tag, int type, int count, byte[] value, Ifd child) {
			_tag = tag;
			_type = type;
			_count = count;
			_value = value;
			_child = child;
		}
	}

	private static final class Ifd {
		final List<Entry> _entries = new ArrayList<>();

		int _offset;

		Ifd ascii(int tag, String text) {
			byte[] bytes = (text + "\u0000").getBytes(StandardCharsets.US_ASCII);
			_entries.add(new Entry(tag, 2, bytes.length, bytes, null));
			return this;
		}

		Ifd rationals(int tag, long... values) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			for (long value : values) {
				writeInt(out, (int) value);
			}
			_entries.add(new Entry(tag, 5, values.length / 2, out.toByteArray(), null));
			return this;
		}

		Ifd pointer(int tag, Ifd child) {
			_entries.add(new Entry(tag, 4, 1, null, child));
			return this;
		}

		int size() {
			return 2 + 12 * _entries.size() + 4;
		}
	}

	/**
	 * IFD0 with make, model and the pointers, the EXIF IFD with the recording time, and the GPS
	 * IFD: a position, or the zeroes and blanks of a redaction.
	 */
	static byte[] tiff(String taken, boolean position) {
		Ifd exif = new Ifd().ascii(0x9003, taken);
		Ifd gps = new Ifd();
		if (position) {
			gps.ascii(0x0001, "N").rationals(0x0002, dms(LATITUDE)).ascii(0x0003, "E").rationals(0x0004,
				dms(LONGITUDE));
		} else {
			// Blank reference letters: the two bytes of an empty ASCII value.
			gps._entries.add(new Entry(0x0001, 2, 2, new byte[] { 0, 0 }, null));
			gps.rationals(0x0002, 0, 0, 0, 0, 0, 0);
			gps._entries.add(new Entry(0x0003, 2, 2, new byte[] { 0, 0 }, null));
			gps.rationals(0x0004, 0, 0, 0, 0, 0, 0);
		}
		Ifd ifd0 = new Ifd().ascii(0x010F, MAKE).ascii(0x0110, MODEL).pointer(0x8769, exif).pointer(0x8825, gps);

		Ifd[] ifds = { ifd0, exif, gps };
		int pos = 8;
		for (Ifd ifd : ifds) {
			ifd._offset = pos;
			pos += ifd.size();
		}
		int data = pos;

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(new byte[] { 'M', 'M', 0, 42, 0, 0, 0, 8 });
		ByteArrayOutputStream blobs = new ByteArrayOutputStream();
		for (Ifd ifd : ifds) {
			writeShort(out, ifd._entries.size());
			for (Entry entry : ifd._entries) {
				writeShort(out, entry._tag);
				writeShort(out, entry._type);
				writeInt(out, entry._count);
				if (entry._child != null) {
					writeInt(out, entry._child._offset);
				} else if (entry._value.length <= 4) {
					byte[] inline = new byte[4];
					System.arraycopy(entry._value, 0, inline, 0, entry._value.length);
					out.writeBytes(inline);
				} else {
					writeInt(out, data + blobs.size());
					blobs.writeBytes(entry._value);
					if (blobs.size() % 2 != 0) {
						blobs.write(0);
					}
				}
			}
			writeInt(out, 0);
		}
		out.writeBytes(blobs.toByteArray());
		return out.toByteArray();
	}

	/** Degrees, minutes and seconds as three rationals, the seconds in ten-thousandths. */
	private static long[] dms(double value) {
		int degrees = (int) value;
		double rest = (value - degrees) * 60;
		int minutes = (int) rest;
		double seconds = (rest - minutes) * 60;
		return new long[] { degrees, 1, minutes, 1, Math.round(seconds * 10000.0), 10000 };
	}

	private static void writeShort(ByteArrayOutputStream out, int value) {
		out.write((value >> 8) & 0xFF);
		out.write(value & 0xFF);
	}

	private static void writeInt(ByteArrayOutputStream out, int value) {
		out.write((value >> 24) & 0xFF);
		out.write((value >> 16) & 0xFF);
		out.write((value >> 8) & 0xFF);
		out.write(value & 0xFF);
	}
}
