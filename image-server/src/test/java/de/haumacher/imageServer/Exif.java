/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Writing a JPEG orientation, and the recording time of a photograph, into a file, for the tests.
 *
 * <p>
 * Nothing in this server ever writes EXIF — the originals are never modified — so this lives here
 * and nowhere else. It is what the orientation test of issue #124 needs: a photograph whose pixels
 * lie on their side and whose file says so, which is what every phone produces and what no image
 * library on hand can write. And it is what the dating of issue #183 needs: a photograph whose
 * wall clock comes with an offset, with a GPS time, or with nothing at all, see {@link Dates}.
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

	/**
	 * The recording time of a photograph as its EXIF data carries it, see issue #183.
	 *
	 * <p>
	 * Every field is optional; what is not set is not written. {@link #tiff()} answers the
	 * big-endian TIFF block: IFD0 holding the pointer to the EXIF IFD (<code>0x8769</code>) and,
	 * where a GPS time is set, the pointer to the GPS IFD (<code>0x8825</code>); the EXIF IFD holding
	 * <code>DateTimeOriginal</code> (<code>0x9003</code>), <code>OffsetTimeOriginal</code>
	 * (<code>0x9011</code>) and <code>SubSecTimeOriginal</code> (<code>0x9291</code>); the GPS IFD
	 * holding <code>GPSTimeStamp</code> (<code>0x0007</code>, three rationals) and
	 * <code>GPSDateStamp</code> (<code>0x001D</code>).
	 * </p>
	 */
	static final class Dates {

		private String _original;

		private String _offset;

		private String _subSeconds;

		private String _gpsDate;

		private int[] _gpsTime;

		/** The wall clock, <code>yyyy:MM:dd HH:mm:ss</code>. */
		Dates original(String value) {
			_original = value;
			return this;
		}

		/** The offset of the wall clock from UTC, <code>±HH:MM</code>. */
		Dates offset(String value) {
			_offset = value;
			return this;
		}

		/** The digits of the fraction of the second, e.g. <code>250</code>. */
		Dates subSeconds(String value) {
			_subSeconds = value;
			return this;
		}

		/** The GPS time, always UTC: the day <code>yyyy:MM:dd</code> and the time of day. */
		Dates gps(String date, int hours, int minutes, int seconds) {
			_gpsDate = date;
			_gpsTime = new int[] { hours, minutes, seconds };
			return this;
		}

		/** The TIFF block, see the class comment. */
		byte[] tiff() {
			List<Entry> exif = new ArrayList<>();
			if (_original != null) {
				exif.add(Entry.ascii(0x9003, _original));
			}
			if (_offset != null) {
				exif.add(Entry.ascii(0x9011, _offset));
			}
			if (_subSeconds != null) {
				exif.add(Entry.ascii(0x9291, _subSeconds));
			}
			List<Entry> gps = new ArrayList<>();
			if (_gpsTime != null) {
				gps.add(Entry.rationals(0x0007, _gpsTime));
				gps.add(Entry.ascii(0x001D, _gpsDate));
			}

			// IFD0 holds nothing but the pointers, whose values are known once the sizes are.
			int ifd0Entries = (exif.isEmpty() ? 0 : 1) + (gps.isEmpty() ? 0 : 1);
			int exifAt = 8 + size(ifd0Entries, List.of());
			int gpsAt = exifAt + (exif.isEmpty() ? 0 : size(exif.size(), exif));
			List<Entry> ifd0 = new ArrayList<>();
			if (!exif.isEmpty()) {
				ifd0.add(Entry.pointer(0x8769, exifAt));
			}
			if (!gps.isEmpty()) {
				ifd0.add(Entry.pointer(0x8825, gpsAt));
			}

			ByteArrayOutputStream out = new ByteArrayOutputStream();
			out.writeBytes(new byte[] { 'M', 'M', 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08 });
			writeIfd(out, 8, ifd0);
			if (!exif.isEmpty()) {
				writeIfd(out, exifAt, exif);
			}
			if (!gps.isEmpty()) {
				writeIfd(out, gpsAt, gps);
			}
			return out.toByteArray();
		}
	}

	/**
	 * Writes a generated 40&times;30 JPEG carrying the given recording time to the given file.
	 */
	static void writeDates(File target, Dates dates) throws Exception {
		TestGeoLocation.writeJpeg(target, dates.tiff());
	}

	/** One entry of an IFD: its tag, its type, its count and its value bytes. */
	private static final class Entry {
		final int _tag;

		final int _type;

		final int _count;

		final byte[] _value;

		private Entry(int tag, int type, int count, byte[] value) {
			_tag = tag;
			_type = type;
			_count = count;
			_value = value;
		}

		/** An ASCII value, NUL-terminated as TIFF wants it. */
		static Entry ascii(int tag, String value) {
			byte[] bytes = (value + "\u0000").getBytes(StandardCharsets.US_ASCII);
			return new Entry(tag, 2, bytes.length, bytes);
		}

		/** Whole numbers as RATIONALs, each over one. */
		static Entry rationals(int tag, int[] values) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			for (int value : values) {
				writeInt(out, value);
				writeInt(out, 1);
			}
			return new Entry(tag, 5, values.length, out.toByteArray());
		}

		/** A LONG pointing at a further IFD. */
		static Entry pointer(int tag, int offset) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			writeInt(out, offset);
			return new Entry(tag, 4, 1, out.toByteArray());
		}
	}

	/** The bytes an IFD of the given entries takes, its out-of-line values included. */
	private static int size(int count, List<Entry> entries) {
		int result = 2 + 12 * count + 4;
		for (Entry entry : entries) {
			if (entry._value.length > 4) {
				result += even(entry._value.length);
			}
		}
		return result;
	}

	private static int even(int length) {
		return length % 2 == 0 ? length : length + 1;
	}

	/** Writes an IFD that starts at the given offset of the TIFF block, the entries sorted by tag. */
	private static void writeIfd(ByteArrayOutputStream out, int at, List<Entry> unsorted) {
		if (out.size() != at) {
			throw new IllegalStateException("The IFD lands at " + out.size() + ", not at " + at + ".");
		}
		List<Entry> entries = new ArrayList<>(unsorted);
		entries.sort(Comparator.comparingInt(e -> e._tag));
		int data = at + 2 + 12 * entries.size() + 4;
		ByteArrayOutputStream values = new ByteArrayOutputStream();
		writeShort(out, entries.size());
		for (Entry entry : entries) {
			writeShort(out, entry._tag);
			writeShort(out, entry._type);
			writeInt(out, entry._count);
			if (entry._value.length <= 4) {
				out.writeBytes(entry._value);
				for (int n = entry._value.length; n < 4; n++) {
					out.write(0);
				}
			} else {
				writeInt(out, data + values.size());
				values.writeBytes(entry._value);
				if (entry._value.length % 2 != 0) {
					values.write(0);
				}
			}
		}
		// No further IFD in this chain.
		writeInt(out, 0);
		out.writeBytes(values.toByteArray());
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
