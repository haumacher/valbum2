/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.raw;

import com.drew.imaging.ImageProcessingException;
import com.drew.imaging.jpeg.JpegMetadataReader;
import com.drew.imaging.jpeg.JpegProcessingException;
import com.drew.imaging.tiff.TiffMetadataReader;
import com.drew.imaging.tiff.TiffProcessingException;
import com.drew.imaging.tiff.TiffReader;
import com.drew.lang.ByteArrayReader;
import com.drew.lang.RandomAccessFileReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.ExifTiffHandler;
import com.drew.metadata.exif.GpsDirectory;
import de.haumacher.imageServer.PreviewCache;
import de.haumacher.util.servlet.Util;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;

/**
 * A raw photograph, shown through the JPEG preview it carries, see issue #191.
 *
 * <p>
 * The sensor data of a raw file is never decoded. Nearly every raw format carries a JPEG of the
 * picture beside it &mdash; full size in most cameras, a few hundred pixels in some &mdash; and that
 * JPEG is what the server shows: the preview, the thumbnail, the faces and the viewer's display
 * rendition of issue #186 are all made from it, while the original a caller downloads stays the raw
 * file as it came.
 * </p>
 *
 * <h2>Which JPEG</h2>
 *
 * <p>
 * Every JPEG the container points at is a candidate, and the one with the most pixels wins
 * ({@link #embedded(File)}); a candidate counts only where a decodable frame header
 * ({@link JpegHeader}) stands at its offset, which keeps the lossless sensor stream of a CR2 or a
 * DNG out. Per container:
 * </p>
 * <ul>
 * <li><b>TIFF-based</b> (<code>.dng</code>, <code>.cr2</code>, <code>.nef</code>,
 * <code>.arw</code>, <code>.orf</code>, <code>.rw2</code>): every IFD of the main chain and of every
 * <code>SubIFDs</code> list, with its <code>JPEGInterchangeFormat</code>/<code>Length</code> pair,
 * its strips where they form one contiguous stream, and Panasonic's <code>JpgFromRaw</code>
 * (<code>0x002E</code>); and an uncompressed 8-bit RGB picture of the main chain, which is the only
 * preview Android's <code>DngCreator</code> writes, read by the TIFF reader of the JDK
 * ({@link EmbeddedPreview#getTiffIndex()}).</li>
 * <li><b>CR3</b> (ISO-BMFF): the <code>THMB</code> box in Canon's <code>moov/uuid</code>, the
 * <code>PRVW</code> box in the preview <code>uuid</code>, and the first sample of every track, the
 * first of which is the full-size JPEG.</li>
 * <li><b>RAF</b>: the JPEG at the offset and length the header names at bytes 84 and 88.</li>
 * </ul>
 * <p>
 * Where none of that yields a picture of at least {@value #SCAN_BELOW} pixels on its long side
 * (an Olympus ORF keeps its large preview in the maker note, which no structure above reaches), the
 * file is scanned once for JPEG streams and the largest one found wins. What was found is held in
 * memory per file, length and modification time.
 * </p>
 *
 * <h2>Orientation</h2>
 *
 * <p>
 * The preview is stored the way the sensor saw the scene, and the raw's own metadata say how to
 * turn it: the EXIF orientation of the TIFF IFD0 (the <code>CMT1</code> TIFF of a CR3, the EXIF of a
 * RAF's JPEG, which is that file's only EXIF). That one orientation is applied to the preview's
 * pixels, once; what the embedded JPEG might say about itself is never read (ImageIO does not apply
 * it either). So the <em>raw raster</em> of a raw photograph &mdash; the frame a face box is stored
 * in, issue #142 &mdash; is the embedded JPEG's picture as stored, and its upright frame is that
 * picture turned by the raw's orientation.
 * </p>
 *
 * <p>
 * Nothing here ever writes: an original is read and never touched.
 * </p>
 */
public final class RawFile {

	private static final Logger LOG = Logger.getLogger(RawFile.class.getName());

	/**
	 * The long side below which the structured candidates of a file are not trusted to be its best
	 * preview, and the file is scanned for one, see the class comment.
	 */
	public static final int SCAN_BELOW = 1024;

	private static final ConcurrentHashMap<String, Found> FOUND = new ConcurrentHashMap<>();

	private static final int MAX_REMEMBERED = 1024;

	private RawFile() {
		// Static utility.
	}

	/** Whether the given file name is that of a raw photograph, by its extension in any case. */
	public static boolean isRawName(String name) {
		if (name == null) {
			return false;
		}
		String suffix = Util.suffix(name);
		return suffix != null && PreviewCache.RAW_EXTENSIONS.contains(suffix);
	}

	/** Whether the given file is a raw photograph, by its extension. */
	public static boolean isRaw(File file) {
		return isRawName(file.getName());
	}

	/**
	 * The content type a raw original is delivered with: the names of the freedesktop.org
	 * shared MIME database, which is what a desktop recognises the downloaded file by.
	 */
	public static String contentType(String name) {
		switch (Util.suffix(name)) {
			case "dng":
				return "image/x-adobe-dng";
			case "cr2":
				return "image/x-canon-cr2";
			case "cr3":
				return "image/x-canon-cr3";
			case "nef":
				return "image/x-nikon-nef";
			case "arw":
				return "image/x-sony-arw";
			case "orf":
				return "image/x-olympus-orf";
			case "rw2":
				return "image/x-panasonic-rw2";
			case "raf":
				return "image/x-fuji-raf";
			default:
				return "application/octet-stream";
		}
	}

	/** Forgets what was found in the files; tests only. */
	static void forget() {
		FOUND.clear();
	}

	/**
	 * The largest JPEG preview the given raw file carries, see the class comment; <code>null</code>
	 * where it carries none that can be decoded.
	 */
	public static EmbeddedPreview embedded(File file) throws IOException {
		String key = file.getAbsolutePath();
		long length = file.length();
		long modified = file.lastModified();
		Found found = FOUND.get(key);
		if (found != null && found._length == length && found._modified == modified) {
			return found._jpeg;
		}
		EmbeddedPreview jpeg;
		try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
			jpeg = locate(in, Util.suffix(file.getName()));
		}
		if (FOUND.size() >= MAX_REMEMBERED) {
			FOUND.clear();
		}
		FOUND.put(key, new Found(length, modified, jpeg));
		return jpeg;
	}

	/**
	 * The largest JPEG preview of the given raw file, refusing with a sentence that says so where
	 * there is none.
	 */
	public static EmbeddedPreview require(File file) throws NoEmbeddedPreviewException {
		EmbeddedPreview result;
		try {
			result = embedded(file);
		} catch (IOException ex) {
			throw new NoEmbeddedPreviewException(unreadable(file.getName(), ex.getMessage()), ex);
		}
		if (result == null) {
			throw new NoEmbeddedPreviewException(noPreview(file.getName()));
		}
		return result;
	}

	/** The refusal of a raw that carries no JPEG preview. */
	public static String noPreview(String name) {
		return "'" + name + "' is a raw file without an embedded JPEG preview, which is all this server shows of a"
			+ " raw; download the original to develop it.";
	}

	/** The refusal of a raw whose structure cannot be read. */
	public static String unreadable(String name, String reason) {
		return "The embedded preview of the raw file '" + name + "' cannot be read: " + reason;
	}

	/**
	 * The JPEG preview of the given raw file as a stream ImageIO reads, see {@link #require}; for a
	 * preview that is a {@link EmbeddedPreview#getTiffIndex() TIFF image}, the whole file.
	 */
	public static ImageInputStream open(File file, EmbeddedPreview preview) throws IOException {
		if (preview.getTiffIndex() >= 0) {
			return ImageIO.createImageInputStream(file);
		}
		return new RangeImageInputStream(file, preview.getOffset(), preview.getLength());
	}

	/**
	 * The metadata of the given raw file: what metadata-extractor reads of a TIFF-based raw (read in
	 * place, never buffered from the start of the file), the <code>CMT1</code>, <code>CMT2</code>
	 * and <code>CMT4</code> TIFFs of a CR3 (which metadata-extractor 2.18 recognises but does not
	 * read) and the EXIF of a RAF's JPEG.
	 */
	public static Metadata metadata(File file) throws IOException, ImageProcessingException {
		String suffix = Util.suffix(file.getName());
		if ("cr3".equals(suffix)) {
			return Cr3.metadata(file);
		}
		if ("raf".equals(suffix)) {
			try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
				long[] range = rafJpeg(in);
				if (range == null) {
					return new Metadata();
				}
				try (InputStream jpeg = new BufferedInputStream(new RangeInputStream(file, range[0], range[1]))) {
					return JpegMetadataReader.readMetadata(jpeg);
				} catch (JpegProcessingException ex) {
					throw new ImageProcessingException(ex);
				}
			}
		}
		try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
			return TiffMetadataReader.readMetadata(new RandomAccessFileReader(in));
		} catch (TiffProcessingException ex) {
			throw new ImageProcessingException(ex);
		}
	}

	private static EmbeddedPreview locate(RandomAccessFile in, String suffix) throws IOException {
		List<long[]> candidates = new ArrayList<>();
		List<EmbeddedPreview> pictures = new ArrayList<>();
		if ("cr3".equals(suffix)) {
			Cr3.candidates(in, candidates);
		} else if ("raf".equals(suffix)) {
			long[] range = rafJpeg(in);
			if (range != null) {
				candidates.add(range);
			}
		} else {
			tiffCandidates(in, candidates, pictures);
		}
		EmbeddedPreview best = null;
		for (long[] candidate : candidates) {
			best = better(best, verified(in, candidate[0], candidate[1]));
		}
		for (EmbeddedPreview picture : pictures) {
			best = better(best, picture);
		}
		if (best == null || Math.max(best.getWidth(), best.getHeight()) < SCAN_BELOW) {
			best = better(best, scan(in));
		}
		return best;
	}

	private static EmbeddedPreview better(EmbeddedPreview best, EmbeddedPreview candidate) {
		if (candidate == null) {
			return best;
		}
		if (best == null || candidate.pixels() > best.pixels()) {
			return candidate;
		}
		return best;
	}

	private static EmbeddedPreview verified(RandomAccessFile in, long offset, long length) throws IOException {
		if (offset <= 0 || length <= 4 || offset + length > in.length()) {
			return null;
		}
		int[] size = JpegHeader.frameSize(in, offset, offset + length);
		if (size == null) {
			return null;
		}
		return new EmbeddedPreview(offset, length, size[0], size[1]);
	}

	/**
	 * The largest JPEG stream found anywhere in the file, see the class comment: every
	 * <code>FFD8FF</code> where a decodable frame header follows, measured to its <code>EOI</code>;
	 * the search goes on behind each stream found, so a thumbnail inside a preview's EXIF is never a
	 * second candidate.
	 */
	private static EmbeddedPreview scan(RandomAccessFile in) throws IOException {
		long length = in.length();
		JpegHeader.Window window = new JpegHeader.Window(in, length);
		EmbeddedPreview best = null;
		long pos = 0;
		while (pos + 3 < length) {
			if (window.at(pos) != 0xFF || window.at(pos + 1) != 0xD8 || window.at(pos + 2) != 0xFF) {
				pos++;
				continue;
			}
			int[] size = JpegHeader.frameSize(in, pos, length);
			if (size == null) {
				pos++;
				continue;
			}
			long end = JpegHeader.end(in, pos, length);
			if (end < 0) {
				pos++;
				continue;
			}
			best = better(best, new EmbeddedPreview(pos, end - pos, size[0], size[1]));
			pos = end;
		}
		return best;
	}

	// --- RAF. ---

	private static final String RAF_MAGIC = "FUJIFILMCCD-RAW ";

	/** The offset and length of a RAF's JPEG, <code>null</code> where the file is no RAF. */
	private static long[] rafJpeg(RandomAccessFile in) throws IOException {
		if (in.length() < 92) {
			return null;
		}
		byte[] magic = new byte[16];
		in.seek(0);
		in.readFully(magic);
		if (!RAF_MAGIC.equals(new String(magic, StandardCharsets.US_ASCII))) {
			return null;
		}
		in.seek(84);
		long offset = in.readInt() & 0xFFFFFFFFL;
		long length = in.readInt() & 0xFFFFFFFFL;
		return new long[] { offset, length };
	}

	// --- TIFF. ---

	private static final int MAX_IFDS = 64;

	/**
	 * The JPEG streams the IFDs of a TIFF-based raw point at, see the class comment, and the
	 * uncompressed RGB pictures of its main chain.
	 */
	private static void tiffCandidates(RandomAccessFile in, List<long[]> result, List<EmbeddedPreview> pictures)
			throws IOException {
		Tiff tiff = Tiff.open(in);
		if (tiff == null) {
			return;
		}
		Set<Long> visited = new HashSet<>();
		long ifd = tiff.u32(4);
		for (int index = 0; ifd > 0 && visited.size() < MAX_IFDS; index++) {
			ifd = ifd(tiff, ifd, index, visited, result, pictures);
		}
	}

	/**
	 * Reads one IFD and the SubIFDs below it; the offset of the next IFD of its chain.
	 *
	 * @param index
	 *        The index of the IFD in the main chain, which is the index the TIFF reader of the JDK
	 *        knows it by; <code>-1</code> for a SubIFD.
	 */
	private static long ifd(Tiff tiff, long offset, int index, Set<Long> visited, List<long[]> result,
			List<EmbeddedPreview> pictures) throws IOException {
		if (!visited.add(offset) || visited.size() > MAX_IFDS || offset + 2 > tiff.length()) {
			return 0;
		}
		int count = tiff.u16(offset);
		if (offset + 2 + 12L * count + 4 > tiff.length()) {
			return 0;
		}
		long jpegOffset = -1;
		long jpegLength = -1;
		long[] strips = null;
		long[] stripLengths = null;
		long[] subIfds = null;
		long width = 0;
		long height = 0;
		long compression = 1;
		long photometric = -1;
		long samples = 1;
		long planar = 1;
		long[] bits = null;
		for (int n = 0; n < count; n++) {
			long entry = offset + 2 + 12L * n;
			int tag = tiff.u16(entry);
			int type = tiff.u16(entry + 2);
			long values = tiff.u32(entry + 4);
			switch (tag) {
				case 0x0201:
					jpegOffset = tiff.first(entry, type, values);
					break;
				case 0x0202:
					jpegLength = tiff.first(entry, type, values);
					break;
				case 0x0111:
					strips = tiff.array(entry, type, values);
					break;
				case 0x0117:
					stripLengths = tiff.array(entry, type, values);
					break;
				case 0x014A:
					subIfds = tiff.array(entry, type, values);
					break;
				case 0x0100:
					width = tiff.first(entry, type, values);
					break;
				case 0x0101:
					height = tiff.first(entry, type, values);
					break;
				case 0x0102:
					bits = tiff.array(entry, type, values);
					break;
				case 0x0103:
					compression = tiff.first(entry, type, values);
					break;
				case 0x0106:
					photometric = tiff.first(entry, type, values);
					break;
				case 0x0115:
					samples = tiff.first(entry, type, values);
					break;
				case 0x011C:
					planar = tiff.first(entry, type, values);
					break;
				case 0x002E:
					// Panasonic's JpgFromRaw: an undefined blob that is a whole JPEG.
					if (values > 4) {
						result.add(new long[] { tiff.u32(entry + 8), values });
					}
					break;
				default:
					break;
			}
		}
		if (jpegOffset > 0 && jpegLength > 0) {
			result.add(new long[] { jpegOffset, jpegLength });
		}
		if (strips != null && stripLengths != null && strips.length > 0 && strips.length == stripLengths.length) {
			long start = strips[0];
			long total = 0;
			boolean contiguous = true;
			for (int n = 0; n < strips.length; n++) {
				if (strips[n] != start + total) {
					contiguous = false;
					break;
				}
				total += stripLengths[n];
			}
			if (contiguous) {
				result.add(new long[] { start, total });
			}
		}
		if (index >= 0 && compression == 1 && photometric == 2 && samples == 3 && planar == 1 && width > 0
			&& height > 0 && width <= 65535 && height <= 65535 && eightBits(bits)) {
			// An uncompressed RGB thumbnail, as Android's DngCreator writes the only preview of a DNG.
			pictures.add(EmbeddedPreview.tiffPicture(index, (int) width, (int) height));
		}
		long next = tiff.u32(offset + 2 + 12L * count);
		if (subIfds != null) {
			for (long sub : subIfds) {
				if (sub > 0) {
					ifd(tiff, sub, -1, visited, result, pictures);
				}
			}
		}
		return next;
	}

	private static boolean eightBits(long[] bits) {
		if (bits == null || bits.length == 0) {
			return false;
		}
		for (long value : bits) {
			if (value != 8) {
				return false;
			}
		}
		return true;
	}

	/** The byte order and the integers of a TIFF structure. */
	static final class Tiff {

		private final RandomAccessFile _in;

		private final boolean _little;

		private final long _base;

		private final long _length;

		Tiff(RandomAccessFile in, boolean little, long base, long length) {
			_in = in;
			_little = little;
			_base = base;
			_length = length;
		}

		/**
		 * The TIFF the file begins with, <code>null</code> where it begins with none: the byte order
		 * and the magic of a TIFF (42), an Olympus ORF (<code>RO</code>, <code>RS</code>) or a
		 * Panasonic RW2 (85).
		 */
		static Tiff open(RandomAccessFile in) throws IOException {
			if (in.length() < 8) {
				return null;
			}
			in.seek(0);
			int b0 = in.read();
			int b1 = in.read();
			boolean little;
			if (b0 == 'I' && b1 == 'I') {
				little = true;
			} else if (b0 == 'M' && b1 == 'M') {
				little = false;
			} else {
				return null;
			}
			Tiff tiff = new Tiff(in, little, 0, in.length());
			int magic = tiff.u16(2);
			if (magic != 42 && magic != 0x4F52 && magic != 0x5352 && magic != 0x55) {
				return null;
			}
			return tiff;
		}

		long length() {
			return _length;
		}

		int u16(long pos) throws IOException {
			_in.seek(_base + pos);
			int a = _in.read();
			int b = _in.read();
			if ((a | b) < 0) {
				throw new IOException("Truncated TIFF structure at " + pos + ".");
			}
			return _little ? (a | b << 8) : (a << 8 | b);
		}

		long u32(long pos) throws IOException {
			_in.seek(_base + pos);
			byte[] bytes = new byte[4];
			_in.readFully(bytes);
			long a = bytes[0] & 0xFF;
			long b = bytes[1] & 0xFF;
			long c = bytes[2] & 0xFF;
			long d = bytes[3] & 0xFF;
			return _little ? (a | b << 8 | c << 16 | d << 24) : (a << 24 | b << 16 | c << 8 | d);
		}

		/** The first value of a SHORT or LONG entry. */
		long first(long entry, int type, long count) throws IOException {
			long[] values = array(entry, type, Math.min(count, 1));
			return values.length == 0 ? -1 : values[0];
		}

		/** The values of a SHORT, LONG or IFD entry, at most 4096 of them. */
		long[] array(long entry, int type, long count) throws IOException {
			int size;
			if (type == 3) {
				size = 2;
			} else if (type == 4 || type == 13) {
				size = 4;
			} else {
				return new long[0];
			}
			int n = (int) Math.min(count, 4096);
			long at = count * size <= 4 ? entry + 8 : u32(entry + 8);
			if (at + (long) n * size > _length) {
				return new long[0];
			}
			long[] result = new long[n];
			for (int i = 0; i < n; i++) {
				result[i] = size == 2 ? u16(at + 2L * i) : u32(at + 4L * i);
			}
			return result;
		}
	}

	// --- CR3. ---

	/** The ISO-BMFF structure of a Canon CR3. */
	static final class Cr3 {

		/** Canon's <code>uuid</code> inside <code>moov</code>, holding the metadata and THMB. */
		private static final String CANON_UUID = "85c0b687820f11e08111f4ce462b6a48";

		/** The top-level <code>uuid</code> holding the PRVW box. */
		private static final String PREVIEW_UUID = "eaf42b5e1c984b88b9fbb7dc406e4d16";

		private Cr3() {
			// Static utility.
		}

		/** The candidates of a CR3: THMB, PRVW and the first sample of every track. */
		static void candidates(RandomAccessFile in, List<long[]> result) throws IOException {
			for (Box box : boxes(in, 0, in.length())) {
				if ("moov".equals(box._type)) {
					for (Box child : boxes(in, box._content, box._end)) {
						if ("uuid".equals(child._type) && CANON_UUID.equals(child._uuid)) {
							for (Box item : boxes(in, child._content, child._end)) {
								if ("THMB".equals(item._type)) {
									addJpegWithin(in, item._content, item._end, result);
								}
							}
						} else if ("trak".equals(child._type)) {
							track(in, child, result);
						}
					}
				} else if ("uuid".equals(box._type) && PREVIEW_UUID.equals(box._uuid)) {
					addJpegWithin(in, box._content, box._end, result);
				}
			}
		}

		/** The first sample of the given track: <code>stsz</code> and <code>co64</code>/<code>stco</code>. */
		private static void track(RandomAccessFile in, Box trak, List<long[]> result) throws IOException {
			Box stbl = find(in, trak, "mdia", "minf", "stbl");
			if (stbl == null) {
				return;
			}
			long size = -1;
			long offset = -1;
			for (Box box : boxes(in, stbl._content, stbl._end)) {
				if ("stsz".equals(box._type) && box._end - box._content >= 12) {
					in.seek(box._content + 4);
					long constant = in.readInt() & 0xFFFFFFFFL;
					long count = in.readInt() & 0xFFFFFFFFL;
					if (constant != 0) {
						size = constant;
					} else if (count > 0 && box._end - box._content >= 16) {
						size = in.readInt() & 0xFFFFFFFFL;
					}
				} else if ("co64".equals(box._type) && box._end - box._content >= 16) {
					in.seek(box._content + 4);
					if ((in.readInt() & 0xFFFFFFFFL) > 0) {
						offset = in.readLong();
					}
				} else if ("stco".equals(box._type) && box._end - box._content >= 12) {
					in.seek(box._content + 4);
					if ((in.readInt() & 0xFFFFFFFFL) > 0) {
						offset = in.readInt() & 0xFFFFFFFFL;
					}
				}
			}
			if (size > 0 && offset > 0) {
				result.add(new long[] { offset, size });
			}
		}

		private static Box find(RandomAccessFile in, Box box, String... path) throws IOException {
			Box current = box;
			for (String type : path) {
				Box next = null;
				for (Box child : boxes(in, current._content, current._end)) {
					if (type.equals(child._type)) {
						next = child;
						break;
					}
				}
				if (next == null) {
					return null;
				}
				current = next;
			}
			return current;
		}

		/**
		 * The JPEG inside the given box content: it begins within its first 64 bytes, behind a
		 * small header whose layout is Canon's and differs between THMB and PRVW, and ends at the
		 * end of the box at the latest.
		 */
		private static void addJpegWithin(RandomAccessFile in, long start, long end, List<long[]> result)
				throws IOException {
			long limit = Math.min(end - 3, start + 64);
			for (long pos = start; pos < limit; pos++) {
				in.seek(pos);
				if (in.read() == 0xFF && in.read() == 0xD8 && in.read() == 0xFF) {
					long jpegEnd = JpegHeader.end(in, pos, end);
					result.add(new long[] { pos, (jpegEnd > 0 ? jpegEnd : end) - pos });
					return;
				}
			}
		}

		/** The metadata of a CR3, out of its CMT1 (IFD0), CMT2 (EXIF) and CMT4 (GPS) TIFFs. */
		static Metadata metadata(File file) throws IOException {
			Metadata metadata = new Metadata();
			try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
				for (Box box : boxes(in, 0, in.length())) {
					if (!"moov".equals(box._type)) {
						continue;
					}
					for (Box child : boxes(in, box._content, box._end)) {
						if (!"uuid".equals(child._type) || !CANON_UUID.equals(child._uuid)) {
							continue;
						}
						for (Box item : boxes(in, child._content, child._end)) {
							switch (item._type) {
								case "CMT1":
									tiff(in, item, metadata, null);
									break;
								case "CMT2":
									tiff(in, item, metadata, ExifSubIFDDirectory.class);
									break;
								case "CMT4":
									tiff(in, item, metadata, GpsDirectory.class);
									break;
								default:
									break;
							}
						}
					}
				}
			}
			return metadata;
		}

		private static final int MAX_TIFF = 4 * 1024 * 1024;

		/**
		 * Reads one CMT TIFF into the given metadata: its IFD0 as the given directory, or as EXIF
		 * IFD0 for <code>null</code>.
		 */
		private static void tiff(RandomAccessFile in, Box box, Metadata metadata,
				Class<? extends Directory> directory) throws IOException {
			long size = box._end - box._content;
			if (size <= 8 || size > MAX_TIFF) {
				return;
			}
			byte[] bytes = new byte[(int) size];
			in.seek(box._content);
			in.readFully(bytes);
			ExifTiffHandler handler = directory == null ? new ExifTiffHandler(metadata, null)
				: new ExifTiffHandler(metadata, null) {
					@Override
					public void setTiffMarker(int marker) {
						pushDirectory(directory);
					}
				};
			try {
				new TiffReader().processTiff(new ByteArrayReader(bytes), handler, 0);
			} catch (TiffProcessingException | IOException ex) {
				LOG.log(Level.FINE, "Cannot read the " + box._type + " of a CR3: " + ex.getMessage(), ex);
			}
		}

		/** The boxes between the given offsets. */
		private static List<Box> boxes(RandomAccessFile in, long start, long end) throws IOException {
			List<Box> result = new ArrayList<>();
			long pos = start;
			while (pos + 8 <= end && result.size() < 1024) {
				in.seek(pos);
				long size = in.readInt() & 0xFFFFFFFFL;
				byte[] type = new byte[4];
				in.readFully(type);
				long header = 8;
				if (size == 1) {
					if (pos + 16 > end) {
						break;
					}
					size = in.readLong();
					header = 16;
				} else if (size == 0) {
					size = end - pos;
				}
				if (size < header || pos + size > end) {
					break;
				}
				Box box = new Box(new String(type, StandardCharsets.ISO_8859_1), pos + header, pos + size);
				if ("uuid".equals(box._type) && box._end - box._content >= 16) {
					byte[] uuid = new byte[16];
					in.readFully(uuid);
					StringBuilder hex = new StringBuilder();
					for (byte b : uuid) {
						hex.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
					}
					box._uuid = hex.toString();
					box._content += 16;
				}
				result.add(box);
				pos += size;
			}
			return result;
		}

		/** One box: its type, where its content begins and where it ends. */
		private static final class Box {

			final String _type;

			long _content;

			final long _end;

			String _uuid;

			Box(String type, long content, long end) {
				_type = type;
				_content = content;
				_end = end;
			}
		}
	}

	/** A piece of a file as an {@link InputStream}. */
	static final class RangeInputStream extends FilterInputStream {

		private long _left;

		RangeInputStream(File file, long offset, long length) throws IOException {
			super(new java.io.FileInputStream(file));
			long skipped = 0;
			while (skipped < offset) {
				long n = in.skip(offset - skipped);
				if (n <= 0) {
					break;
				}
				skipped += n;
			}
			_left = length;
		}

		@Override
		public int read() throws IOException {
			if (_left <= 0) {
				return -1;
			}
			int result = super.read();
			if (result >= 0) {
				_left--;
			}
			return result;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			if (_left <= 0) {
				return -1;
			}
			int result = super.read(b, off, (int) Math.min(len, _left));
			if (result > 0) {
				_left -= result;
			}
			return result;
		}
	}

	/** What {@link #embedded(File)} found in a file of the given length and modification time. */
	private static final class Found {

		final long _length;

		final long _modified;

		final EmbeddedPreview _jpeg;

		Found(long length, long modified, EmbeddedPreview jpeg) {
			_length = length;
			_modified = modified;
			_jpeg = jpeg;
		}
	}
}
