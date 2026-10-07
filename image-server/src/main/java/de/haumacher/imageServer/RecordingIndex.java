/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import com.drew.imaging.jpeg.JpegMetadataReader;
import com.drew.imaging.mp4.Mp4MetadataReader;
import com.drew.imaging.quicktime.QuickTimeMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.drew.metadata.jpeg.JpegDirectory;
import com.drew.metadata.mov.QuickTimeDirectory;
import com.drew.metadata.mov.media.QuickTimeVideoDirectory;
import com.drew.metadata.mp4.Mp4Directory;
import com.drew.metadata.mp4.media.Mp4VideoDirectory;
import de.haumacher.imageServer.cache.ImageData;
import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Orientation;
import de.haumacher.imageServer.shared.util.Orientations;
import de.haumacher.imageServer.upload.HashCache;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The library by what its recordings are, for one run of {@link ReplaceOriginals}: finding the
 * copy of a photograph or video the phone uploaded under another name.
 *
 * <p>
 * Nothing of this is stored. The index is built in memory when a run first needs it and is thrown
 * away with the run; no sidecar, no hash index and no other state of the server gains a field for
 * it. What it costs is the cheapest read that tells two recordings apart before their content is
 * compared:
 * </p>
 *
 * <ul>
 * <li>A JPEG photograph is keyed by its recording time to the second and its size as shown. The
 * library's are taken from the album's <code>index.json</code> where it lists the photograph
 * (one read per folder); a photograph it does not list is read by its header: the marker segments
 * up to the start of the scan, of which only the EXIF <code>APP1</code> and the frame header are
 * read and every other segment is skipped by its length. The picture is never read.</li>
 * <li>An mp4 or QuickTime video is keyed by its duration and its frame size, read from its
 * header boxes; the media data is skipped by its length. A sidecar stores no duration, so every
 * video of the library is read this way.</li>
 * <li>Nothing else is keyed: {@link SameRecording} cannot prove anything else the same.</li>
 * </ul>
 *
 * <p>
 * A recording time is a moment, and a photograph that names no offset is a wall clock that the
 * server read in the zone of its space &mdash; or, in a sidecar written before issue #183, as if
 * it were UTC, or at the offset its GPS time said. The incoming original is a header read like
 * any other, and its keys are all of these readings in every zone of the run; a key in common is a
 * candidate, never a proof, so a reading too many costs nothing but a comparison. A date somebody
 * corrected by hand in the album is not found.
 * </p>
 *
 * <p>
 * The embedded EXIF thumbnail (IFD1) is read with the header at no extra cost, see
 * {@link Head#getThumbnail()}. It only orders the candidates, see {@link ReplaceOriginals}.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
final class RecordingIndex {

	/** What a library file is found by before its content is compared. */
	record Key(boolean video, long value, int width, int height) {

		/** A photograph taken in the given second, of the given size as shown. */
		static Key photo(long epochSecond, int width, int height) {
			return new Key(false, epochSecond, width, height);
		}

		/** A video of the given duration in milliseconds and the given frame size. */
		static Key video(long durationMillis, int width, int height) {
			return new Key(true, durationMillis, width, height);
		}
	}

	/** What reading the library costs; counted for a test and for nothing else. */
	static final class Reads {
		private final Map<Path, Long> _headerBytes = new LinkedHashMap<>();

		private final List<Path> _compared = new ArrayList<>();

		private int _sidecars;

		private int _fromSidecar;

		/** The bytes read from each file opened for its header, by the file. */
		Map<Path, Long> getHeaderBytes() {
			return _headerBytes;
		}

		/** How many <code>index.json</code> files were read. */
		int getSidecars() {
			return _sidecars;
		}

		/** How many library files were keyed by their album's sidecar without being opened. */
		int getFromSidecar() {
			return _fromSidecar;
		}

		/** The library files read in full to compare them with an original, in their order. */
		List<Path> getCompared() {
			return _compared;
		}

		void compared(Path file) {
			_compared.add(file);
		}

		void read(Path file, long bytes) {
			_headerBytes.merge(file, Long.valueOf(bytes), Long::sum);
		}
	}

	/** What the header of one file says. */
	static final class Head {
		private final Set<Key> _keys;

		private final String _thumbnail;

		private final String _problem;

		Head(Set<Key> keys, String thumbnail, String problem) {
			_keys = keys;
			_thumbnail = thumbnail;
			_problem = problem;
		}

		/** The keys the file is found by; empty where {@link #getProblem()} says why. */
		Set<Key> getKeys() {
			return _keys;
		}

		/**
		 * The SHA-256 of the JPEG thumbnail the EXIF IFD1 embeds, <code>null</code> where the file
		 * carries none.
		 */
		String getThumbnail() {
			return _thumbnail;
		}

		/** Why the file has no key, in a few words; <code>null</code> if it has one. */
		String getProblem() {
			return _problem;
		}

		static Head problem(String problem) {
			return new Head(Set.of(), null, problem);
		}
	}

	private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

	private static final Pattern OFFSET = Pattern.compile("[+-]\\d\\d:\\d\\d");

	private final Map<Key, List<Path>> _byKey = new HashMap<>();

	private final Map<Path, String> _problems = new LinkedHashMap<>();

	private RecordingIndex() {
		// See build().
	}

	/**
	 * Keys the given library files.
	 *
	 * @param files
	 *        Every photograph and video of the library.
	 * @param zoneOf
	 *        The zone of the space a file lies in.
	 * @param reads
	 *        Where the reads are counted.
	 */
	static RecordingIndex build(Collection<Path> files, Function<Path, ZoneId> zoneOf, Reads reads) {
		RecordingIndex result = new RecordingIndex();
		Map<Path, List<Path>> byFolder = new LinkedHashMap<>();
		for (Path file : files) {
			byFolder.computeIfAbsent(file.getParent(), f -> new ArrayList<>()).add(file);
		}
		for (Map.Entry<Path, List<Path>> folder : byFolder.entrySet()) {
			Map<String, ImagePart> stored = null;
			for (Path file : folder.getValue()) {
				String name = file.getFileName().toString();
				Key key = null;
				if (isJpeg(name)) {
					if (stored == null) {
						stored = stored(folder.getKey(), reads);
					}
					key = storedKey(stored.get(name));
					if (key != null) {
						reads._fromSidecar++;
					}
				} else if (!isIsoMedia(name)) {
					continue;
				}
				if (key != null) {
					result.add(key, file);
					continue;
				}
				Head head = head(file, List.of(zoneOf.apply(file)), reads);
				if (head.getProblem() != null) {
					result._problems.put(file, head.getProblem());
				}
				for (Key headKey : head.getKeys()) {
					result.add(headKey, file);
				}
			}
		}
		return result;
	}

	private void add(Key key, Path file) {
		List<Path> list = _byKey.computeIfAbsent(key, k -> new ArrayList<>());
		if (!list.contains(file)) {
			list.add(file);
		}
	}

	/** The library files that share one of the given keys, in the order they were found. */
	List<Path> candidates(Set<Key> keys) {
		Set<Path> result = new LinkedHashSet<>();
		for (Key key : keys) {
			List<Path> found = _byKey.get(key);
			if (found != null) {
				result.addAll(found);
			}
		}
		return new ArrayList<>(result);
	}

	/** The library files whose header could not be read, with the reason. */
	Map<Path, String> getProblems() {
		return _problems;
	}

	/** Whether the run can key the given file by its name; see {@link SameRecording}. */
	static boolean isKeyed(String name) {
		return isJpeg(name) || isIsoMedia(name);
	}

	static boolean isJpeg(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		return lower.endsWith(".jpg") || lower.endsWith(".jpeg");
	}

	static boolean isIsoMedia(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		return lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".m4v")
			|| lower.endsWith(".3gp");
	}

	// --- The sidecar. ---

	/** The photographs the album's sidecar lists, by name; empty where it has none. */
	private static Map<String, ImagePart> stored(Path folder, Reads reads) {
		Map<String, ImagePart> result = new HashMap<>();
		if (!folder.resolve("index.json").toFile().isFile()) {
			return result;
		}
		reads._sidecars++;
		FolderResource resource = ResourceCache.sidecar(folder.toFile());
		if (resource instanceof AlbumInfo) {
			for (AlbumPart part : ((AlbumInfo) resource).getParts()) {
				if (part instanceof ImagePart) {
					result.put(((ImagePart) part).getName(), (ImagePart) part);
				} else if (part instanceof ImageGroup) {
					for (ImagePart image : ((ImageGroup) part).getImages()) {
						result.put(image.getName(), image);
					}
				}
			}
		}
		return result;
	}

	/** The key of a photograph as its sidecar stores it, <code>null</code> where it says too little. */
	private static Key storedKey(ImagePart part) {
		if (part == null || part.getDate() <= 0 || part.getWidth() <= 0 || part.getHeight() <= 0) {
			return null;
		}
		return Key.photo(Math.floorDiv(part.getDate(), 1000L), part.getWidth(), part.getHeight());
	}

	// --- The header. ---

	/**
	 * Reads the header of the given file.
	 *
	 * @param zones
	 *        The zones a wall clock without an offset may have been read in.
	 * @param reads
	 *        Where the bytes read are counted.
	 */
	static Head head(Path file, Collection<ZoneId> zones, Reads reads) {
		String name = file.getFileName().toString();
		try {
			if (isJpeg(name)) {
				return jpegHead(file, zones, reads);
			}
			if (isIsoMedia(name)) {
				return videoHead(file, reads);
			}
			return Head.problem("neither a JPEG nor an mp4 or QuickTime video");
		} catch (IOException | RuntimeException ex) {
			return Head.problem("its header cannot be read (" + ex.getMessage() + ")");
		}
	}

	/** Counts what is read through a {@link RandomAccessFile}. */
	private static final class CountingFile implements AutoCloseable {
		private final RandomAccessFile _in;

		private final Path _path;

		private final Reads _reads;

		CountingFile(Path path, Reads reads) throws IOException {
			_in = new RandomAccessFile(path.toFile(), "r");
			_path = path;
			_reads = reads;
		}

		long length() throws IOException {
			return _in.length();
		}

		void readFully(long pos, byte[] buffer, int offset, int length) throws IOException {
			_in.seek(pos);
			_in.readFully(buffer, offset, length);
			_reads.read(_path, length);
		}

		@Override
		public void close() throws IOException {
			_in.close();
		}
	}

	private static final byte[] EXIF = { 'E', 'x', 'i', 'f', 0, 0 };

	private static Head jpegHead(Path file, Collection<ZoneId> zones, Reads reads) throws IOException {
		ByteArrayOutputStream head = new ByteArrayOutputStream();
		byte[] tiff = null;
		try (CountingFile in = new CountingFile(file, reads)) {
			long length = in.length();
			byte[] two = new byte[2];
			if (length < 4) {
				return Head.problem("not a JPEG");
			}
			in.readFully(0, two, 0, 2);
			if ((two[0] & 0xFF) != 0xFF || (two[1] & 0xFF) != 0xD8) {
				return Head.problem("not a JPEG");
			}
			head.write(0xFF);
			head.write(0xD8);
			long pos = 2;
			byte[] marker = new byte[4];
			while (pos + 4 <= length) {
				in.readFully(pos, marker, 0, 2);
				if ((marker[0] & 0xFF) != 0xFF) {
					return Head.problem("not a JPEG this reads");
				}
				int code = marker[1] & 0xFF;
				if (code == 0xFF) {
					// A fill byte.
					pos++;
					continue;
				}
				if (code == 0xDA || code == 0xD9) {
					// The picture begins: nothing of it is read.
					break;
				}
				if (code == 0x01 || (code >= 0xD0 && code <= 0xD8)) {
					pos += 2;
					continue;
				}
				in.readFully(pos + 2, marker, 2, 2);
				int segment = ((marker[2] & 0xFF) << 8) | (marker[3] & 0xFF);
				if (segment < 2 || pos + 2 + segment > length) {
					return Head.problem("not a JPEG this reads");
				}
				boolean keep = isFrame(code);
				byte[] payload = null;
				if (code == 0xE1 && segment >= 2 + EXIF.length) {
					byte[] signature = new byte[EXIF.length];
					in.readFully(pos + 4, signature, 0, signature.length);
					if (Arrays.equals(signature, EXIF)) {
						payload = new byte[segment - 2];
						System.arraycopy(signature, 0, payload, 0, signature.length);
						in.readFully(pos + 4 + signature.length, payload, signature.length,
							payload.length - signature.length);
						if (tiff == null) {
							tiff = Arrays.copyOfRange(payload, EXIF.length, payload.length);
						}
						keep = true;
					}
				} else if (keep) {
					payload = new byte[segment - 2];
					in.readFully(pos + 4, payload, 0, payload.length);
				}
				if (keep) {
					head.write(marker, 0, 4);
					head.write(payload, 0, payload.length);
				}
				pos += 2 + segment;
			}
		}
		head.write(0xFF);
		head.write(0xD9);

		Metadata metadata;
		try {
			metadata = JpegMetadataReader.readMetadata(new ByteArrayInputStream(head.toByteArray()));
		} catch (Exception ex) {
			return Head.problem("its header cannot be read (" + ex.getMessage() + ")");
		}
		String thumbnail = thumbnail(tiff);
		JpegDirectory jpeg = metadata.getFirstDirectoryOfType(JpegDirectory.class);
		if (jpeg == null || !jpeg.containsTag(JpegDirectory.TAG_IMAGE_WIDTH)) {
			return new Head(Set.of(), thumbnail, "its size cannot be read");
		}
		int width;
		int height;
		try {
			int rawWidth = jpeg.getImageWidth();
			int rawHeight = jpeg.getImageHeight();
			Orientation tx = Orientations.fromCode(ImageData.orientationCode(metadata));
			width = Orientations.width(tx, rawWidth, rawHeight);
			height = Orientations.height(tx, rawWidth, rawHeight);
		} catch (Exception ex) {
			return new Head(Set.of(), thumbnail, "its size cannot be read");
		}
		Set<Long> seconds = seconds(metadata, zones);
		if (seconds.isEmpty()) {
			return new Head(Set.of(), thumbnail, "it carries no recording time");
		}
		Set<Key> keys = new LinkedHashSet<>();
		for (Long second : seconds) {
			keys.add(Key.photo(second.longValue(), width, height));
		}
		return new Head(keys, thumbnail, null);
	}

	/** Whether the given marker starts a frame header (<code>SOFn</code>), which holds the size. */
	private static boolean isFrame(int code) {
		return code >= 0xC0 && code <= 0xCF && code != 0xC4 && code != 0xC8 && code != 0xCC;
	}

	/**
	 * Every second the server may have dated the photograph to, see the class comment and
	 * {@link ImageData}: at its own offset where it names one; else as if UTC (before issue #183),
	 * at the offset its GPS time says, and in each of the given zones.
	 */
	static Set<Long> seconds(Metadata metadata, Collection<ZoneId> zones) {
		Set<Long> result = new LinkedHashSet<>();
		ExifSubIFDDirectory directory = null;
		for (ExifSubIFDDirectory candidate : metadata.getDirectoriesOfType(ExifSubIFDDirectory.class)) {
			if (candidate.containsTag(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL)) {
				directory = candidate;
				break;
			}
		}
		if (directory == null) {
			return result;
		}
		Date wall = directory.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL,
			directory.getString(ExifSubIFDDirectory.TAG_SUBSECOND_TIME_ORIGINAL), UTC);
		if (wall == null) {
			return result;
		}
		long wallAsUtc = wall.getTime();
		ZoneOffset offset = offset(directory.getString(ExifSubIFDDirectory.TAG_TIME_ZONE_ORIGINAL));
		if (offset != null) {
			result.add(second(wallAsUtc - offset.getTotalSeconds() * 1000L));
			return result;
		}
		result.add(second(wallAsUtc));
		Long gps = gpsOffset(metadata, wallAsUtc);
		if (gps != null) {
			result.add(second(wallAsUtc - gps.longValue()));
		}
		LocalDateTime local = LocalDateTime.ofEpochSecond(Math.floorDiv(wallAsUtc, 1000L), 0, ZoneOffset.UTC);
		for (ZoneId zone : zones) {
			result.add(Long.valueOf(local.atZone(zone).toEpochSecond()));
		}
		return result;
	}

	private static Long second(long millis) {
		return Long.valueOf(Math.floorDiv(millis, 1000L));
	}

	private static ZoneOffset offset(String value) {
		if (value == null || !OFFSET.matcher(value.trim()).matches()) {
			return null;
		}
		try {
			return ZoneOffset.of(value.trim());
		} catch (DateTimeException ex) {
			return null;
		}
	}

	/** The offset the GPS time says, as {@link ImageData} reads it. */
	private static Long gpsOffset(Metadata metadata, long wallAsUtc) {
		GpsDirectory gps = metadata.getFirstDirectoryOfType(GpsDirectory.class);
		if (gps == null) {
			return null;
		}
		Date utc;
		try {
			utc = gps.getGpsDate();
		} catch (RuntimeException ex) {
			return null;
		}
		if (utc == null) {
			return null;
		}
		long rounded = Math.round((wallAsUtc - utc.getTime()) / (double) ImageData.OFFSET_UNIT_MILLIS)
			* ImageData.OFFSET_UNIT_MILLIS;
		return Math.abs(rounded) > ImageData.MAX_OFFSET_MILLIS ? null : Long.valueOf(rounded);
	}

	/**
	 * The SHA-256 of the thumbnail the given TIFF block (the EXIF <code>APP1</code> without its
	 * signature) embeds in its IFD1, <code>null</code> where there is none or it is broken.
	 */
	static String thumbnail(byte[] tiff) {
		if (tiff == null || tiff.length < 8) {
			return null;
		}
		boolean little;
		if (tiff[0] == 'I' && tiff[1] == 'I') {
			little = true;
		} else if (tiff[0] == 'M' && tiff[1] == 'M') {
			little = false;
		} else {
			return null;
		}
		long ifd0 = unsigned32(tiff, 4, little);
		if (ifd0 < 8 || ifd0 + 2 > tiff.length) {
			return null;
		}
		int count0 = unsigned16(tiff, (int) ifd0, little);
		long next = ifd0 + 2 + 12L * count0;
		if (next + 4 > tiff.length) {
			return null;
		}
		long ifd1 = unsigned32(tiff, (int) next, little);
		if (ifd1 < 8 || ifd1 + 2 > tiff.length) {
			return null;
		}
		int count1 = unsigned16(tiff, (int) ifd1, little);
		long offset = -1;
		long length = -1;
		for (int n = 0; n < count1; n++) {
			long entry = ifd1 + 2 + 12L * n;
			if (entry + 12 > tiff.length) {
				return null;
			}
			int tag = unsigned16(tiff, (int) entry, little);
			int type = unsigned16(tiff, (int) entry + 2, little);
			long value = type == 3 ? unsigned16(tiff, (int) entry + 8, little) : unsigned32(tiff, (int) entry + 8, little);
			if (tag == 0x0201) {
				offset = value;
			} else if (tag == 0x0202) {
				length = value;
			}
		}
		if (offset < 8 || length <= 0 || offset + length > tiff.length) {
			return null;
		}
		return HashCache.sha256(Arrays.copyOfRange(tiff, (int) offset, (int) (offset + length)));
	}

	private static int unsigned16(byte[] data, int pos, boolean little) {
		int a = data[pos] & 0xFF;
		int b = data[pos + 1] & 0xFF;
		return little ? (b << 8) | a : (a << 8) | b;
	}

	private static long unsigned32(byte[] data, int pos, boolean little) {
		long result = 0;
		for (int n = 0; n < 4; n++) {
			int shift = little ? 8 * n : 8 * (3 - n);
			result |= (data[pos + n] & 0xFFL) << shift;
		}
		return result;
	}

	// --- Videos. ---

	/** The major brand of a QuickTime movie, as {@link ImageData#readMetadata(java.io.File)} tells it. */
	private static final String QUICKTIME_BRAND = "qt  ";

	/** Counts what is read, and lets a skip seek. */
	private static final class CountingStream extends FilterInputStream {
		private final Path _path;

		private final Reads _reads;

		CountingStream(Path path, Reads reads) throws IOException {
			super(new FileInputStream(path.toFile()));
			_path = path;
			_reads = reads;
		}

		@Override
		public int read() throws IOException {
			int result = super.read();
			if (result >= 0) {
				_reads.read(_path, 1);
			}
			return result;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			int result = super.read(b, off, len);
			if (result > 0) {
				_reads.read(_path, result);
			}
			return result;
		}
	}

	private static Head videoHead(Path file, Reads reads) throws IOException {
		byte[] start = new byte[12];
		boolean quickTime;
		try (InputStream in = new CountingStream(file, reads)) {
			int got = in.readNBytes(start, 0, start.length);
			String box = got >= 8 ? new String(start, 4, 4, StandardCharsets.ISO_8859_1) : "";
			String brand = got >= 12 ? new String(start, 8, 4, StandardCharsets.ISO_8859_1) : "";
			quickTime = !"ftyp".equals(box) || QUICKTIME_BRAND.equals(brand);
		}
		Metadata metadata;
		try (InputStream in = new CountingStream(file, reads)) {
			metadata = quickTime ? QuickTimeMetadataReader.readMetadata(in) : Mp4MetadataReader.readMetadata(in);
		}
		Directory container;
		Directory video;
		int widthTag;
		int heightTag;
		if (quickTime) {
			container = metadata.getFirstDirectoryOfType(QuickTimeDirectory.class);
			video = metadata.getFirstDirectoryOfType(QuickTimeVideoDirectory.class);
			widthTag = QuickTimeVideoDirectory.TAG_WIDTH;
			heightTag = QuickTimeVideoDirectory.TAG_HEIGHT;
		} else {
			container = metadata.getFirstDirectoryOfType(Mp4Directory.class);
			video = metadata.getFirstDirectoryOfType(Mp4VideoDirectory.class);
			widthTag = Mp4VideoDirectory.TAG_WIDTH;
			heightTag = Mp4VideoDirectory.TAG_HEIGHT;
		}
		// The duration and the time scale have the same tags in both containers.
		if (container == null || video == null || !container.containsTag(Mp4Directory.TAG_DURATION)
			|| !container.containsTag(Mp4Directory.TAG_TIME_SCALE) || !video.containsTag(widthTag)
			|| !video.containsTag(heightTag)) {
			return Head.problem("its duration and size cannot be read");
		}
		try {
			long duration = container.getLong(Mp4Directory.TAG_DURATION);
			long scale = container.getLong(Mp4Directory.TAG_TIME_SCALE);
			if (scale <= 0) {
				return Head.problem("its duration cannot be read");
			}
			int width = video.getInt(widthTag);
			int height = video.getInt(heightTag);
			return new Head(Set.of(Key.video(duration * 1000L / scale, width, height)), null, null);
		} catch (Exception ex) {
			return Head.problem("its duration and size cannot be read");
		}
	}
}
