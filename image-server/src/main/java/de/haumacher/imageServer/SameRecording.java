/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.jpeg.JpegDirectory;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Whether two files hold the same recording, whatever their metadata say, see issue #167.
 *
 * <p>
 * The question {@link ReplaceOriginals} asks before it puts an original in the place of the copy
 * a phone uploaded: the redaction of issue #166 zero-fills the GPS tags and leaves the compressed
 * picture untouched, so the two files differ in their headers and in nothing else. That is exactly
 * what is compared, and nothing is ever decoded:
 * </p>
 *
 * <ul>
 * <li>a JPEG by its <em>scan data</em>, the bytes from the first start-of-scan marker
 * (<code>FFDA</code>) up to and including the end-of-image marker (<code>FFD9</code>) that ends
 * it, which must be byte for byte the same; the pixel dimensions must agree, and so must the
 * <code>DateTimeOriginal</code> where both files carry one (read through metadata-extractor, as
 * {@link de.haumacher.imageServer.cache.ImageData} reads it). What stands behind the end-of-image
 * marker (the video of a motion photo, a maker's trailer) is not part of the picture and is not
 * compared;</li>
 * <li>an mp4 or QuickTime file by the payload of its <code>mdat</code> boxes, in their order,
 * which must be byte for byte the same &mdash; where a container states its position, it states
 * it in the <code>moov</code> box, which is not compared;</li>
 * <li>anything else (a PNG, a file of two different kinds) cannot be compared and is never taken
 * for the same.</li>
 * </ul>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class SameRecording {

	private static final int BUFFER_SIZE = 64 * 1024;

	private SameRecording() {
		// Static utility.
	}

	/**
	 * Why the given files are not the same recording.
	 *
	 * @return <code>null</code> if they are, else the reason in a few words, for a report.
	 */
	public static String difference(File a, File b) throws IOException {
		Kind kind = kindOf(a);
		if (kind != kindOf(b)) {
			return "cannot compare (not files of the same kind)";
		}
		switch (kind) {
			case JPEG:
				return jpegDifference(a, b);
			case ISO_MEDIA:
				return mediaDifference(a, b);
			default:
				return "cannot compare (neither a JPEG nor an mp4 or QuickTime video)";
		}
	}

	/** What a file is, by its first bytes. */
	enum Kind {
		JPEG, ISO_MEDIA, OTHER;
	}

	/** The top-level boxes an ISO media file (mp4, QuickTime) may begin with. */
	private static final List<String> FIRST_BOXES = Arrays.asList("ftyp", "moov", "mdat", "wide", "free", "skip");

	static Kind kindOf(File file) throws IOException {
		byte[] head = new byte[8];
		try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
			if (in.read(head) < head.length) {
				return Kind.OTHER;
			}
		}
		if ((head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8) {
			return Kind.JPEG;
		}
		if (FIRST_BOXES.contains(new String(head, 4, 4, StandardCharsets.ISO_8859_1))) {
			return Kind.ISO_MEDIA;
		}
		return Kind.OTHER;
	}

	// --- JPEG. ---

	private static String jpegDifference(File a, File b) throws IOException {
		byte[] first = Files.readAllBytes(a.toPath());
		byte[] second = Files.readAllBytes(b.toPath());
		int[] scanA = scan(first);
		int[] scanB = scan(second);
		if (scanA == null || scanB == null) {
			return "unreadable (no complete JPEG scan)";
		}
		if (!Arrays.equals(first, scanA[0], scanA[1], second, scanB[0], scanB[1])) {
			return "the pixels differ (the JPEG scan data is not the same)";
		}

		Metadata metaA;
		Metadata metaB;
		try {
			metaA = ImageMetadataReader.readMetadata(a);
			metaB = ImageMetadataReader.readMetadata(b);
		} catch (Exception ex) {
			return "unreadable (" + ex.getMessage() + ")";
		}
		String sizeA = dimensions(metaA);
		String sizeB = dimensions(metaB);
		if (sizeA == null || !sizeA.equals(sizeB)) {
			return "the dimensions differ (" + sizeA + " vs. " + sizeB + ")";
		}
		String takenA = dateTimeOriginal(metaA);
		String takenB = dateTimeOriginal(metaB);
		if (takenA != null && takenB != null && !takenA.equals(takenB)) {
			return "the recording times differ (" + takenA + " vs. " + takenB + ")";
		}
		return null;
	}

	/**
	 * Where the scan data of the given JPEG lies.
	 *
	 * <p>
	 * The marker segments are walked by their lengths up to the first start of scan; from there the
	 * entropy-coded data is read byte by byte, skipping stuffed bytes (<code>FF00</code>), restart
	 * markers (<code>FFD0</code>..<code>FFD7</code>) and fill bytes, and walking over the segments
	 * a progressive JPEG puts between its scans by their lengths again, so a <code>FFD9</code> in a
	 * table is never taken for the end of the image.
	 * </p>
	 *
	 * @return The start (the <code>FF</code> of the first <code>FFDA</code>) and the end
	 *         (exclusive, behind the <code>FFD9</code>), or <code>null</code> if the file is not a
	 *         complete JPEG.
	 */
	static int[] scan(byte[] jpeg) {
		if (jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
			return null;
		}
		int pos = 2;
		int start = -1;
		while (pos + 1 < jpeg.length) {
			if ((jpeg[pos] & 0xFF) != 0xFF) {
				if (start < 0) {
					// Garbage between two marker segments: not a JPEG this reads.
					return null;
				}
				pos++;
				continue;
			}
			int marker = jpeg[pos + 1] & 0xFF;
			if (marker == 0xFF) {
				// A fill byte.
				pos++;
				continue;
			}
			if (start >= 0 && (marker == 0x00 || (marker >= 0xD0 && marker <= 0xD7))) {
				// A stuffed byte or a restart marker inside the entropy-coded data.
				pos += 2;
				continue;
			}
			if (marker == 0xD9) {
				return start < 0 ? null : new int[] { start, pos + 2 };
			}
			if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8)) {
				// A marker without a length.
				pos += 2;
				continue;
			}
			if (pos + 3 >= jpeg.length) {
				return null;
			}
			int length = ((jpeg[pos + 2] & 0xFF) << 8) | (jpeg[pos + 3] & 0xFF);
			if (length < 2) {
				return null;
			}
			if (marker == 0xDA && start < 0) {
				start = pos;
			}
			pos += 2 + length;
		}
		return null;
	}

	private static String dimensions(Metadata metadata) {
		JpegDirectory jpeg = metadata.getFirstDirectoryOfType(JpegDirectory.class);
		if (jpeg == null) {
			return null;
		}
		try {
			return jpeg.getImageWidth() + "x" + jpeg.getImageHeight();
		} catch (Exception ex) {
			return null;
		}
	}

	private static String dateTimeOriginal(Metadata metadata) {
		ExifSubIFDDirectory exif = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
		if (exif == null) {
			return null;
		}
		String value = exif.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL);
		if (value == null) {
			return null;
		}
		value = value.trim();
		return value.isEmpty() ? null : value;
	}

	// --- ISO media (mp4, QuickTime). ---

	private static String mediaDifference(File a, File b) throws IOException {
		try (RandomAccessFile first = new RandomAccessFile(a, "r");
				RandomAccessFile second = new RandomAccessFile(b, "r")) {
			List<long[]> dataA = mdat(first);
			List<long[]> dataB = mdat(second);
			if (dataA == null || dataB == null || dataA.isEmpty() || dataB.isEmpty()) {
				return "unreadable (no media data box)";
			}
			long lengthA = total(dataA);
			long lengthB = total(dataB);
			if (lengthA != lengthB) {
				return "the recordings differ (the media data is not the same)";
			}
			return sameBytes(first, dataA, second, dataB) ? null
				: "the recordings differ (the media data is not the same)";
		}
	}

	/**
	 * The payloads of the top-level <code>mdat</code> boxes of the given file, as
	 * <code>{offset, length}</code>.
	 *
	 * @return <code>null</code> if the box structure is broken.
	 */
	static List<long[]> mdat(RandomAccessFile in) throws IOException {
		List<long[]> result = new ArrayList<>();
		long fileLength = in.length();
		long pos = 0;
		byte[] header = new byte[8];
		while (pos + 8 <= fileLength) {
			in.seek(pos);
			in.readFully(header);
			long size = ((header[0] & 0xFFL) << 24) | ((header[1] & 0xFFL) << 16) | ((header[2] & 0xFFL) << 8)
				| (header[3] & 0xFFL);
			String type = new String(header, 4, 4, StandardCharsets.ISO_8859_1);
			long headerLength = 8;
			if (size == 1) {
				if (pos + 16 > fileLength) {
					return null;
				}
				size = in.readLong();
				headerLength = 16;
			} else if (size == 0) {
				size = fileLength - pos;
			}
			if (size < headerLength || pos + size > fileLength) {
				return null;
			}
			if ("mdat".equals(type)) {
				result.add(new long[] { pos + headerLength, size - headerLength });
			}
			pos += size;
		}
		return result;
	}

	private static long total(List<long[]> ranges) {
		long result = 0;
		for (long[] range : ranges) {
			result += range[1];
		}
		return result;
	}

	/** Whether the concatenated ranges of both files hold the same bytes; their totals agree. */
	private static boolean sameBytes(RandomAccessFile a, List<long[]> rangesA, RandomAccessFile b,
			List<long[]> rangesB) throws IOException {
		Cursor first = new Cursor(a, rangesA);
		Cursor second = new Cursor(b, rangesB);
		byte[] bufferA = new byte[BUFFER_SIZE];
		byte[] bufferB = new byte[BUFFER_SIZE];
		while (true) {
			int n = first.read(bufferA);
			if (n <= 0) {
				return second.read(bufferB) <= 0;
			}
			second.readFully(bufferB, n);
			if (!Arrays.equals(bufferA, 0, n, bufferB, 0, n)) {
				return false;
			}
		}
	}

	/** Reads a list of ranges of a file as one stream. */
	private static final class Cursor {
		private final RandomAccessFile _file;

		private final List<long[]> _ranges;

		private int _index;

		private long _done;

		Cursor(RandomAccessFile file, List<long[]> ranges) {
			_file = file;
			_ranges = ranges;
		}

		/** Reads up to a buffer full, at most up to the end of the current range. */
		int read(byte[] buffer) throws IOException {
			return read(buffer, 0, buffer.length);
		}

		private int read(byte[] buffer, int offset, int max) throws IOException {
			while (_index < _ranges.size() && _done == _ranges.get(_index)[1]) {
				_index++;
				_done = 0;
			}
			if (_index == _ranges.size()) {
				return 0;
			}
			long[] range = _ranges.get(_index);
			int n = (int) Math.min(max, range[1] - _done);
			_file.seek(range[0] + _done);
			_file.readFully(buffer, offset, n);
			_done += n;
			return n;
		}

		/** Reads exactly the given number of bytes, across ranges. */
		void readFully(byte[] buffer, int length) throws IOException {
			int got = 0;
			while (got < length) {
				int n = read(buffer, got, length - got);
				if (n <= 0) {
					throw new IOException("Unexpected end of the media data.");
				}
				got += n;
			}
		}
	}
}
