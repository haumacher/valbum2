/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.imageio.ImageIO;

/**
 * Tiny generated raw files for the tests of issue #191 &mdash; no real camera file.
 *
 * <p>
 * A raw file is a container of a sensor stream nobody here decodes and of JPEG previews the server
 * shows, so a fixture needs only the container: a TIFF/EP structure (a DNG and a CR2), Canon's
 * ISO-BMFF (a CR3) and Fujifilm's header (a RAF), each holding JPEGs written by ImageIO. Every
 * preview shows four quadrants once upright &mdash; red top left, green top right, blue bottom left,
 * yellow bottom right ({@link de.haumacher.imageServer.heif.TestHeifDecoder#assertQuadrants}) &mdash;
 * stored on its side where the raw's EXIF says orientation 6, so that a probe of each corner tells
 * a missing, a doubled and a wrong turn apart. The sensor stream is a lossless-JPEG header
 * (<code>SOF3</code>) followed by noise, which no preview must ever be taken for.
 * </p>
 *
 * <p>
 * Every file says <code>DateTimeOriginal</code> 2024:05:17 14:30:00 at <code>OffsetTimeOriginal</code>
 * +02:00, <code>Make</code> "Fixture", <code>Model</code> "Fixture RAW" and the GPS position
 * 48&deg;07'30" N 11&deg;34'12" E (48.125, 11.57).
 * </p>
 */
public final class RawFixtures {

	/** The camera label every fixture says. */
	public static final String CAMERA = "Fixture RAW";

	/** 2024-05-17T12:30:00Z, the fixtures' recording time. */
	public static final long TAKEN = java.time.Instant.parse("2024-05-17T12:30:00Z").toEpochMilli();

	private RawFixtures() {
		// Static utility.
	}

	// --- Pictures. ---

	/** A JPEG of the four quadrants, upright, of the given size. */
	public static byte[] quadrants(int width, int height) throws IOException {
		return jpeg(uprightQuadrants(width, height));
	}

	/**
	 * A JPEG whose raster, turned by the EXIF orientation of the given code (1 or 6), shows the four
	 * quadrants upright at the given size.
	 */
	public static byte[] stored(int uprightWidth, int uprightHeight, int orientation) throws IOException {
		return jpeg(storedRaster(uprightWidth, uprightHeight, orientation));
	}

	/** The raster {@link #stored(int, int, int)} encodes. */
	private static BufferedImage storedRaster(int uprightWidth, int uprightHeight, int orientation) {
		BufferedImage upright = uprightQuadrants(uprightWidth, uprightHeight);
		if (orientation == 1) {
			return upright;
		}
		if (orientation != 6) {
			throw new IllegalArgumentException("Orientation " + orientation);
		}
		// Code 6: shown = the raster turned a quarter clockwise, so raw(v, W-1-u) = shown(u, v).
		BufferedImage raw = new BufferedImage(uprightHeight, uprightWidth, BufferedImage.TYPE_INT_RGB);
		for (int v = 0; v < uprightHeight; v++) {
			for (int u = 0; u < uprightWidth; u++) {
				raw.setRGB(v, uprightWidth - 1 - u, upright.getRGB(u, v));
			}
		}
		return raw;
	}

	private static BufferedImage uprightQuadrants(int width, int height) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		try {
			int w = width / 2;
			int h = height / 2;
			g.setColor(new Color(0xFF0000));
			g.fillRect(0, 0, w, h);
			g.setColor(new Color(0x00FF00));
			g.fillRect(w, 0, width - w, h);
			g.setColor(new Color(0x0000FF));
			g.fillRect(0, h, w, height - h);
			g.setColor(new Color(0xFFFF00));
			g.fillRect(w, h, width - w, height - h);
		} finally {
			g.dispose();
		}
		return image;
	}

	private static byte[] jpeg(BufferedImage image) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		if (!ImageIO.write(image, "jpg", out)) {
			throw new IOException("No JPEG writer.");
		}
		return out.toByteArray();
	}

	/** The given JPEG with an EXIF <code>APP1</code> segment of the given TIFF behind its SOI. */
	public static byte[] withExif(byte[] jpeg, byte[] tiff) {
		byte[] header = "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1);
		int length = 2 + header.length + tiff.length;
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(jpeg, 0, 2);
		out.write(0xFF);
		out.write(0xE1);
		out.write(length >> 8);
		out.write(length & 0xFF);
		out.write(header, 0, header.length);
		out.write(tiff, 0, tiff.length);
		out.write(jpeg, 2, jpeg.length - 2);
		return out.toByteArray();
	}

	/** A lossless-JPEG header followed by noise: the sensor stream of a CR2 or a DNG, never a preview. */
	public static byte[] sensorStream() {
		byte[] result = new byte[2048];
		new java.util.Random(191).nextBytes(result);
		byte[] header = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xC3, 0x00, 0x0B, 0x0E, 0x10, 0x00, 0x20,
			0x00, 0x01, 0x01, 0x11, 0x00 };
		System.arraycopy(header, 0, result, 0, header.length);
		return result;
	}

	// --- The metadata. ---

	/** The EXIF IFD0 every fixture carries, with the given orientation; sub-IFDs added by the caller. */
	public static Ifd ifd0(int orientation) {
		return new Ifd().ascii(0x010F, "Fixture").ascii(0x0110, CAMERA).shorts(0x0112, orientation)
			.ascii(0x0132, "2024:05:17 14:30:00");
	}

	/** The EXIF IFD of every fixture: the recording time and its offset. */
	public static Ifd exif() {
		return new Ifd().ascii(0x9003, "2024:05:17 14:30:00").ascii(0x9011, "+02:00");
	}

	/** The GPS IFD of every fixture. */
	public static Ifd gps() {
		return new Ifd().bytes(0x0000, BYTE, new byte[] { 2, 3, 0, 0 }).ascii(0x0001, "N")
			.rationals(0x0002, 48, 1, 7, 1, 30, 1).ascii(0x0003, "E").rationals(0x0004, 11, 1, 34, 1, 12, 1);
	}

	/** An EXIF TIFF for a JPEG: IFD0 with the given orientation, the EXIF and the GPS IFD. */
	public static byte[] exifTiff(int orientation) {
		return Ifd.write(new byte[0], ifd0(orientation).ifd(0x8769, exif()).ifd(0x8825, gps()));
	}

	// --- The containers. ---

	/**
	 * A DNG: IFD0 a 24 px JPEG thumbnail carrying the metadata, one SubIFD the JPEG preview of the
	 * given upright size stored for the given orientation, a second SubIFD the sensor stream.
	 *
	 * @param previewExifOrientation
	 *        An EXIF orientation written into the preview JPEG itself, which must not be applied;
	 *        <code>0</code> for none.
	 */
	public static byte[] dng(int width, int height, int orientation, int previewExifOrientation) throws IOException {
		byte[] preview = stored(width, height, orientation);
		if (previewExifOrientation != 0) {
			preview = withExif(preview, Ifd.write(new byte[0], new Ifd().shorts(0x0112, previewExifOrientation)));
		}
		Blob thumbnail = new Blob(stored(24, 16, 1));
		Blob large = new Blob(preview);
		Blob sensor = new Blob(sensorStream());
		boolean swapped = orientation >= 5;
		Ifd previewIfd = new Ifd().longs(0x00FE, 1).longs(0x0100, swapped ? height : width)
			.longs(0x0101, swapped ? width : height).shorts(0x0103, 7).blob(0x0111, large)
			.longs(0x0117, preview.length);
		Ifd sensorIfd = new Ifd().longs(0x00FE, 0).longs(0x0100, 64).longs(0x0101, 32).shorts(0x0103, 7)
			.blob(0x0111, sensor).longs(0x0117, sensor._data.length);
		Ifd first = ifd0(orientation).longs(0x00FE, 1).longs(0x0100, 24).longs(0x0101, 16).shorts(0x0103, 6)
			.blob(0x0201, thumbnail).longs(0x0202, thumbnail._data.length).ifd(0x014A, previewIfd, sensorIfd)
			.ifd(0x8769, exif()).ifd(0x8825, gps()).bytes(0xC612, BYTE, new byte[] { 1, 4, 0, 0 });
		return Ifd.write(new byte[0], first);
	}

	/**
	 * A DNG as Android's <code>DngCreator</code> writes it: IFD0 an uncompressed 8-bit RGB thumbnail
	 * of the given upright size, stored for the given orientation, carrying the metadata, and a SubIFD
	 * the sensor stream; no JPEG anywhere.
	 */
	public static byte[] dngWithRgbThumbnail(int width, int height, int orientation) {
		BufferedImage raster = storedRaster(width, height, orientation);
		int w = raster.getWidth();
		int h = raster.getHeight();
		byte[] rgb = new byte[3 * w * h];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int pixel = raster.getRGB(x, y);
				int at = 3 * (y * w + x);
				rgb[at] = (byte) (pixel >> 16);
				rgb[at + 1] = (byte) (pixel >> 8);
				rgb[at + 2] = (byte) pixel;
			}
		}
		Blob thumbnail = new Blob(rgb);
		Blob sensor = new Blob(sensorStream());
		Ifd sensorIfd = new Ifd().longs(0x00FE, 0).longs(0x0100, 64).longs(0x0101, 32).shorts(0x0103, 7)
			.shorts(0x0106, 32803).blob(0x0111, sensor).longs(0x0117, sensor._data.length);
		Ifd first = ifd0(orientation).longs(0x00FE, 1).longs(0x0100, w).longs(0x0101, h).shorts(0x0102, 8, 8, 8)
			.shorts(0x0103, 1).shorts(0x0106, 2).blob(0x0111, thumbnail).shorts(0x0115, 3).longs(0x0116, h)
			.longs(0x0117, rgb.length).shorts(0x011C, 1).ifd(0x014A, sensorIfd).ifd(0x8769, exif())
			.ifd(0x8825, gps()).bytes(0xC612, BYTE, new byte[] { 1, 4, 0, 0 });
		return Ifd.write(new byte[0], first);
	}

	/** A DNG that carries no JPEG at all: the sensor stream and nothing else. */
	public static byte[] dngWithoutPreview() {
		Blob sensor = new Blob(sensorStream());
		Ifd first = ifd0(1).longs(0x00FE, 0).longs(0x0100, 300).longs(0x0101, 200).shorts(0x0103, 7)
			.blob(0x0111, sensor).longs(0x0117, sensor._data.length).ifd(0x8769, exif())
			.bytes(0xC612, BYTE, new byte[] { 1, 4, 0, 0 });
		return Ifd.write(new byte[0], first);
	}

	/**
	 * A CR2: IFD0 the full-size JPEG as one strip with the metadata, IFD1 a small JPEG thumbnail, IFD3
	 * the sensor stream, and the <code>CR</code> marker behind the TIFF header.
	 */
	public static byte[] cr2(int width, int height, int orientation) throws IOException {
		byte[] full = stored(width, height, orientation);
		Blob large = new Blob(full);
		Blob thumbnail = new Blob(stored(32, 24, 1));
		Blob sensor = new Blob(sensorStream());
		Ifd ifd3 = new Ifd().shorts(0x0103, 6).blob(0x0111, sensor).longs(0x0117, sensor._data.length);
		Ifd ifd2 = new Ifd().longs(0x0100, 8).longs(0x0101, 8).shorts(0x0103, 1);
		ifd2._next = ifd3;
		Ifd ifd1 = new Ifd().shorts(0x0103, 6).blob(0x0201, thumbnail).longs(0x0202, thumbnail._data.length);
		ifd1._next = ifd2;
		Ifd first = ifd0(orientation).shorts(0x0103, 6).blob(0x0111, large).longs(0x0117, full.length)
			.ifd(0x8769, exif()).ifd(0x8825, gps());
		first._next = ifd1;
		// "CR", major 2, minor 0, and the offset of the raw IFD (left empty here).
		return Ifd.write(new byte[] { 'C', 'R', 2, 0, 0, 0, 0, 0 }, first);
	}

	/**
	 * A CR3: <code>ftyp crx</code>, a <code>moov</code> holding Canon's <code>uuid</code> (CMT1, CMT2,
	 * CMT4 and a 32 px THMB) and one track whose first sample is the full-size JPEG in
	 * <code>mdat</code>, and the preview <code>uuid</code> holding a PRVW of half the size.
	 */
	public static byte[] cr3(int width, int height) throws IOException {
		byte[] full = stored(width, height, 1);
		byte[] prvw = stored(width / 2, height / 2, 1);
		byte[] thmb = stored(32, 24, 1);

		byte[] cmt1 = Ifd.write(new byte[0], ifd0(1));
		byte[] cmt2 = Ifd.write(new byte[0], exif());
		byte[] cmt4 = Ifd.write(new byte[0], gps());
		ByteArrayOutputStream thmbBody = new ByteArrayOutputStream();
		// Version and flags, width, height, JPEG size, two unknown shorts.
		thmbBody.write(new byte[] { 0, 0, 0, 0, 0, 32, 0, 24 }, 0, 8);
		thmbBody.write(be32(thmb.length), 0, 4);
		thmbBody.write(new byte[] { 0, 1, 0, 0 }, 0, 4);
		thmbBody.write(thmb, 0, thmb.length);
		byte[] canon = concat(box("CMT1", cmt1), box("CMT2", cmt2), box("CMT4", cmt4),
			box("THMB", thmbBody.toByteArray()));
		byte[] canonUuid = uuidBox("85c0b687820f11e08111f4ce462b6a48", canon);

		byte[] ftyp = box("ftyp", concat("crx ".getBytes(StandardCharsets.ISO_8859_1), new byte[] { 0, 0, 0, 1 },
			"crx isom".getBytes(StandardCharsets.ISO_8859_1)));

		ByteArrayOutputStream prvwBody = new ByteArrayOutputStream();
		prvwBody.write(new byte[] { 0, 0, 0, 0, 0, 1, (byte) ((width / 2) >> 8), (byte) (width / 2),
			(byte) ((height / 2) >> 8), (byte) (height / 2), 0, 1 }, 0, 12);
		prvwBody.write(be32(prvw.length), 0, 4);
		prvwBody.write(prvw, 0, prvw.length);
		byte[] previewUuid = uuidBox("eaf42b5e1c984b88b9fbb7dc406e4d16",
			concat(new byte[] { 0, 0, 0, 0, 0, 0, 0, 1 }, box("PRVW", prvwBody.toByteArray())));

		// The track: its first sample is the full JPEG, placed at the start of mdat's content.
		int moovWithoutTrack = 8 + canonUuid.length;
		byte[] trakTemplate = trak(0, full.length);
		int mdatContent = ftyp.length + moovWithoutTrack + trakTemplate.length + previewUuid.length + 8;
		byte[] moov = box("moov", concat(canonUuid, trak(mdatContent, full.length)));
		byte[] mdat = box("mdat", full);
		return concat(ftyp, moov, previewUuid, mdat);
	}

	private static byte[] trak(long sampleOffset, int sampleSize) {
		byte[] stsz = box("stsz", concat(new byte[4], be32(sampleSize), be32(1)));
		byte[] co64 = box("co64", concat(new byte[4], be32(1), be64(sampleOffset)));
		byte[] stbl = box("stbl", concat(stsz, co64));
		return box("trak", box("mdia", box("minf", stbl)));
	}

	/** A RAF: Fujifilm's header naming the JPEG at its offset 84, and the JPEG carrying the EXIF. */
	public static byte[] raf(int width, int height, int orientation) throws IOException {
		byte[] jpeg = withExif(stored(width, height, orientation), exifTiff(orientation));
		byte[] header = new byte[160];
		byte[] magic = "FUJIFILMCCD-RAW 0201FF383501".getBytes(StandardCharsets.ISO_8859_1);
		System.arraycopy(magic, 0, header, 0, magic.length);
		byte[] model = "Fixture RAW".getBytes(StandardCharsets.ISO_8859_1);
		System.arraycopy(model, 0, header, 28, model.length);
		System.arraycopy(be32(header.length), 0, header, 84, 4);
		System.arraycopy(be32(jpeg.length), 0, header, 88, 4);
		return concat(header, jpeg, sensorStream());
	}

	// --- ISO-BMFF. ---

	private static byte[] box(String type, byte[] content) {
		return concat(be32(8 + content.length), type.getBytes(StandardCharsets.ISO_8859_1), content);
	}

	private static byte[] uuidBox(String uuid, byte[] content) {
		byte[] id = new byte[16];
		for (int n = 0; n < 16; n++) {
			id[n] = (byte) Integer.parseInt(uuid.substring(2 * n, 2 * n + 2), 16);
		}
		return box("uuid", concat(id, content));
	}

	private static byte[] be32(long value) {
		return new byte[] { (byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value };
	}

	private static byte[] be64(long value) {
		return concat(be32(value >>> 32), be32(value & 0xFFFFFFFFL));
	}

	private static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] part : parts) {
			out.write(part, 0, part.length);
		}
		return out.toByteArray();
	}

	// --- TIFF. ---

	static final int BYTE = 1;

	static final int ASCII = 2;

	static final int SHORT = 3;

	static final int LONG = 4;

	static final int RATIONAL = 5;

	/** Data placed behind the IFDs, which an entry points at. */
	public static final class Blob {

		final byte[] _data;

		int _offset;

		Blob(byte[] data) {
			_data = data;
		}
	}

	/** One IFD of a little-endian TIFF, its entries by tag. */
	public static final class Ifd {

		private final Map<Integer, Entry> _entries = new TreeMap<>();

		Ifd _next;

		private int _offset;

		Ifd ascii(int tag, String value) {
			byte[] bytes = (value + "\0").getBytes(StandardCharsets.ISO_8859_1);
			return put(tag, new Entry(ASCII, bytes.length, bytes));
		}

		Ifd shorts(int tag, int... values) {
			byte[] bytes = new byte[2 * values.length];
			for (int n = 0; n < values.length; n++) {
				bytes[2 * n] = (byte) values[n];
				bytes[2 * n + 1] = (byte) (values[n] >> 8);
			}
			return put(tag, new Entry(SHORT, values.length, bytes));
		}

		Ifd longs(int tag, long... values) {
			byte[] bytes = new byte[4 * values.length];
			for (int n = 0; n < values.length; n++) {
				le32(bytes, 4 * n, values[n]);
			}
			return put(tag, new Entry(LONG, values.length, bytes));
		}

		Ifd rationals(int tag, long... numeratorsAndDenominators) {
			byte[] bytes = new byte[4 * numeratorsAndDenominators.length];
			for (int n = 0; n < numeratorsAndDenominators.length; n++) {
				le32(bytes, 4 * n, numeratorsAndDenominators[n]);
			}
			return put(tag, new Entry(RATIONAL, numeratorsAndDenominators.length / 2, bytes));
		}

		Ifd bytes(int tag, int type, byte[] value) {
			return put(tag, new Entry(type, value.length, value));
		}

		Ifd ifd(int tag, Ifd... targets) {
			Entry entry = new Entry(LONG, targets.length, new byte[4 * targets.length]);
			entry._ifds = targets;
			return put(tag, entry);
		}

		Ifd blob(int tag, Blob blob) {
			Entry entry = new Entry(LONG, 1, new byte[4]);
			entry._blob = blob;
			return put(tag, entry);
		}

		private Ifd put(int tag, Entry entry) {
			_entries.put(Integer.valueOf(tag), entry);
			return this;
		}

		/**
		 * A little-endian TIFF: the header, the given bytes behind it (a CR2's marker), the IFDs reachable
		 * from the given one with their values, then every blob.
		 */
		static byte[] write(byte[] afterHeader, Ifd first) {
			List<Ifd> ifds = new ArrayList<>();
			List<Blob> blobs = new ArrayList<>();
			collect(first, ifds, blobs);
			int pos = 8 + afterHeader.length;
			for (Ifd ifd : ifds) {
				ifd._offset = pos;
				pos += 2 + 12 * ifd._entries.size() + 4;
				for (Entry entry : ifd._entries.values()) {
					if (entry._data.length > 4) {
						entry._valueOffset = pos;
						pos += entry._data.length + (entry._data.length & 1);
					}
				}
			}
			for (Blob blob : blobs) {
				blob._offset = pos;
				pos += blob._data.length + (blob._data.length & 1);
			}
			byte[] out = new byte[pos];
			out[0] = 'I';
			out[1] = 'I';
			out[2] = 42;
			le32(out, 4, first._offset);
			System.arraycopy(afterHeader, 0, out, 8, afterHeader.length);
			for (Ifd ifd : ifds) {
				int at = ifd._offset;
				out[at] = (byte) ifd._entries.size();
				out[at + 1] = (byte) (ifd._entries.size() >> 8);
				at += 2;
				for (Map.Entry<Integer, Entry> e : ifd._entries.entrySet()) {
					Entry entry = e.getValue();
					if (entry._ifds != null) {
						for (int n = 0; n < entry._ifds.length; n++) {
							le32(entry._data, 4 * n, entry._ifds[n]._offset);
						}
					}
					if (entry._blob != null) {
						le32(entry._data, 0, entry._blob._offset);
					}
					int tag = e.getKey().intValue();
					out[at] = (byte) tag;
					out[at + 1] = (byte) (tag >> 8);
					out[at + 2] = (byte) entry._type;
					out[at + 3] = 0;
					le32(out, at + 4, entry._count);
					if (entry._data.length > 4) {
						le32(out, at + 8, entry._valueOffset);
						System.arraycopy(entry._data, 0, out, entry._valueOffset, entry._data.length);
					} else {
						System.arraycopy(entry._data, 0, out, at + 8, entry._data.length);
					}
					at += 12;
				}
				le32(out, at, ifd._next == null ? 0 : ifd._next._offset);
			}
			for (Blob blob : blobs) {
				System.arraycopy(blob._data, 0, out, blob._offset, blob._data.length);
			}
			return out;
		}

		private static void collect(Ifd ifd, List<Ifd> ifds, List<Blob> blobs) {
			if (ifd == null || ifds.contains(ifd)) {
				return;
			}
			ifds.add(ifd);
			for (Entry entry : ifd._entries.values()) {
				if (entry._blob != null && !blobs.contains(entry._blob)) {
					blobs.add(entry._blob);
				}
				if (entry._ifds != null) {
					for (Ifd target : entry._ifds) {
						collect(target, ifds, blobs);
					}
				}
			}
			collect(ifd._next, ifds, blobs);
		}
	}

	private static final class Entry {

		final int _type;

		final int _count;

		final byte[] _data;

		Ifd[] _ifds;

		Blob _blob;

		int _valueOffset;

		Entry(int type, int count, byte[] data) {
			_type = type;
			_count = count;
			_data = data;
		}
	}

	private static void le32(byte[] bytes, int at, long value) {
		bytes[at] = (byte) value;
		bytes[at + 1] = (byte) (value >> 8);
		bytes[at + 2] = (byte) (value >> 16);
		bytes[at + 3] = (byte) (value >> 24);
	}
}
