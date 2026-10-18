/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.content;

import de.haumacher.imageServer.CacheRefresh;
import de.haumacher.imageServer.PreviewCache;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.CRC32;

/**
 * The vectors of the content search of one album, in that album's own cache, see issue #257.
 *
 * <p>
 * A cache and nothing else, like the faces of {@link de.haumacher.imageServer.faces.FaceCache}:
 * everything in it is computed again from the previews and the model, so
 * <code>?action=refresh-cache</code> may throw it away ({@link CacheRefresh#isGenerated(String)}).
 * It lives in the album's {@value PreviewCache#CACHE_DIRECTORY_NAME} as
 * {@value #FILE_NAME}, never beside the originals. It is read here and by the vector index of the
 * space (#264, slice 4), never by a request.
 * </p>
 *
 * <h2>Keys</h2>
 *
 * <ul>
 * <li>An <b>image vector</b> (<code>v_img</code>) is keyed by the SHA-256 of the file's contents
 * (the hash of <code>.hashes.json</code>) and the moment of a video's poster (<code>0</code> for a
 * photograph and for a video's first frame, see issue #285): a renamed photo, a copy of it in the
 * album and one moved here by the server (see {@link #carry(File, String, File)}) find their
 * vector again; a new poster moment is another vector.</li>
 * <li>A <b>text vector</b> (<code>v_txt</code>) is keyed by the SHA-256 of its text
 * ({@link ContentText.Text#document()}): a changed description, label, place or album title is another
 * text and is embedded anew, without the picture; photos with the same text share one.</li>
 * <li>Each <b>photo</b> names its two keys, by file name, so that the index reads the vectors of an
 * album without computing any text.</li>
 * </ul>
 *
 * <p>
 * Each kind of vector is stamped as a whole ({@link Embedder#stamp()}, and
 * {@link ContentText#stamp(String)} for the texts, which names the template too): vectors of another
 * stamp are not read at all, which is how a new model, precision or preprocessing re-embeds a
 * library, and a new text template re-embeds only the texts.
 * </p>
 *
 * <h2>The file</h2>
 *
 * <p>
 * Binary, little-endian, for size: a photo costs about 2.2 KB (two vectors of
 * {@link Embedder#DIMENSION} float32 with their 32-byte keys, and its name), against some 2.9 KB as
 * JSON with base64 — 100 000 photos are about 220 MB.
 * </p>
 *
 * <pre>
 * "VACV"  u8 version  u16 dimension
 * str image stamp   u32 n   n x (32 bytes hash, i64 poster millis, f32 quality, dimension x f32)
 * str text stamp    u32 m   m x (32 bytes text hash, dimension x f32)
 * u32 p   p x (str name, 32 bytes hash, i64 poster millis, 32 bytes text hash)
 * u32 CRC-32 of everything before
 * </pre>
 *
 * <p>
 * <code>str</code> is a u16 length and UTF-8. The <code>quality</code> of a picture (version 2,
 * issue #257) is what decides the best shot of a burst: see {@link #quality(ImageKey)}. A file of
 * version 1, which has none, is read with every quality unknown, and its vectors are kept: the
 * content step measures what is missing from the previews alone, without the embedder. A file that
 * is not this (another version, a foreign
 * or truncated file, a wrong checksum) is ignored and written anew, never fatal. Every change is a
 * read, a change and a write under one lock per album ({@link #update(File, Consumer)}), through a
 * temporary sibling.
 * </p>
 */
public final class ContentCache {

	private static final Logger LOG = Logger.getLogger(ContentCache.class.getName());

	/** The name of the file inside the album's {@value PreviewCache#CACHE_DIRECTORY_NAME}. */
	public static final String FILE_NAME = "content.bin";

	/** The version this build writes. */
	public static final int VERSION = 2;

	/** The version before the {@link #quality(ImageKey) quality} of a picture, still read. */
	static final int VERSION_1 = 1;

	private static final byte[] MAGIC = { 'V', 'A', 'C', 'V' };

	private static final int HASH_BYTES = 32;

	/** One lock per album for the read-change-write of its file. */
	private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>();

	/** The key of an image vector: the contents of the file and the poster moment. */
	public record ImageKey(String hash, long poster) {
		// A pair.
	}

	/** What a photo of the album is described by, see {@link ContentCache}. */
	public record Photo(ImageKey image, String text) {
		// A pair.
	}

	private final int _dimension;

	private String _imageStamp;

	private String _textStamp;

	private final Map<ImageKey, float[]> _images = new LinkedHashMap<>();

	/** The quality of each picture that has one, see {@link #quality(ImageKey)}. */
	private final Map<ImageKey, Float> _qualities = new LinkedHashMap<>();

	private final Map<String, float[]> _texts = new LinkedHashMap<>();

	private final Map<String, Photo> _photos = new LinkedHashMap<>();

	/** An empty cache of the given stamps. */
	public ContentCache(String imageStamp, String textStamp) {
		this(imageStamp, textStamp, Embedder.DIMENSION);
	}

	ContentCache(String imageStamp, String textStamp, int dimension) {
		_imageStamp = imageStamp;
		_textStamp = textStamp;
		_dimension = dimension;
	}

	/** The file of the given album, whether it is there or not. */
	public static File file(File folder) {
		return new File(CacheRefresh.cacheDir(folder), FILE_NAME);
	}

	/**
	 * Reads the cache of the given album as it is; an empty one of no stamp where there is none or
	 * it cannot be read.
	 */
	public static ContentCache read(File folder) {
		File file = file(folder);
		if (!file.isFile()) {
			return new ContentCache("", "");
		}
		try {
			return parse(Files.readAllBytes(file.toPath()));
		} catch (IOException | RuntimeException ex) {
			// A cache can always be made again; a broken one stops nothing.
			LOG.log(Level.INFO, "Ignoring the unreadable content cache '" + file.getAbsolutePath() + "': "
				+ ex.getMessage());
			return new ContentCache("", "");
		}
	}

	/**
	 * Reads the cache of the given album with the given stamps: the vectors of another stamp are
	 * left out, and the photos that named them.
	 */
	public static ContentCache read(File folder, String imageStamp, String textStamp) {
		ContentCache result = read(folder);
		result.restamp(imageStamp, textStamp);
		return result;
	}

	/**
	 * Changes the cache of the given album under its lock: read with the given stamps, changed by
	 * the given function, written where it changed.
	 */
	public static void update(File folder, String imageStamp, String textStamp, Consumer<ContentCache> change)
			throws IOException {
		synchronized (lockOf(folder)) {
			ContentCache before = read(folder);
			ContentCache cache = read(folder);
			cache.restamp(imageStamp, textStamp);
			change.accept(cache);
			if (!cache.equals(before)) {
				cache.write(folder);
			}
		}
	}

	/**
	 * The vector of the given photograph, just moved into another album by the server, goes with it,
	 * see {@link de.haumacher.imageServer.MoveService}: a move is no reason to describe a photo
	 * again. Nothing where the source album has none of that contents, or the target has vectors of
	 * another model; a failure is logged, the target then simply embeds it again.
	 */
	public static void carry(File source, String hash, File target) {
		if (hash == null || !file(source).isFile()) {
			return;
		}
		try {
			ContentCache from = read(source);
			Map<ImageKey, float[]> vectors = new LinkedHashMap<>();
			for (Map.Entry<ImageKey, float[]> entry : from._images.entrySet()) {
				if (entry.getKey().hash().equals(hash)) {
					vectors.put(entry.getKey(), entry.getValue());
				}
			}
			if (vectors.isEmpty()) {
				return;
			}
			synchronized (lockOf(target)) {
				ContentCache to = read(target);
				if (!to._imageStamp.isEmpty() && !to._imageStamp.equals(from._imageStamp)) {
					return;
				}
				ContentCache before = read(target);
				if (to._imageStamp.isEmpty()) {
					to._imageStamp = from._imageStamp;
				}
				for (Map.Entry<ImageKey, float[]> entry : vectors.entrySet()) {
					if (to._images.putIfAbsent(entry.getKey(), entry.getValue()) == null) {
						Float quality = from._qualities.get(entry.getKey());
						if (quality != null) {
							to._qualities.put(entry.getKey(), quality);
						}
					}
				}
				if (!to.equals(before)) {
					to.write(target);
				}
			}
		} catch (IOException | RuntimeException ex) {
			LOG.log(Level.WARNING, "Cannot carry the content vector of '" + hash + "' from '" + source + "' to '"
				+ target + "': " + ex.getMessage(), ex);
		}
	}

	private static Object lockOf(File folder) {
		return LOCKS.computeIfAbsent(folder.toPath().toAbsolutePath().normalize().toString(), key -> new Object());
	}

	/** Leaves out what is not of the given stamps. */
	void restamp(String imageStamp, String textStamp) {
		if (!Objects.equals(_imageStamp, imageStamp)) {
			_images.clear();
			_qualities.clear();
			_photos.clear();
			_imageStamp = imageStamp;
		}
		if (!Objects.equals(_textStamp, textStamp)) {
			_texts.clear();
			_photos.clear();
			_textStamp = textStamp;
		}
	}

	/** The stamp of the image vectors, empty for none. */
	public String getImageStamp() {
		return _imageStamp;
	}

	/** The stamp of the text vectors, empty for none. */
	public String getTextStamp() {
		return _textStamp;
	}

	/** The image vector of the given key, <code>null</code> where there is none. */
	public float[] image(ImageKey key) {
		return _images.get(key);
	}

	/**
	 * How sharp and well exposed the picture of the given key is, for the best shot of a burst (see
	 * {@link de.haumacher.imageServer.shared.model.PhotoStack}): {@link Quality#measure}, the larger
	 * the better; {@link Float#NaN} where it was not measured yet, and a negative number where it
	 * cannot be measured (a preview that does not decode).
	 */
	public float quality(ImageKey key) {
		Float result = _qualities.get(key);
		return result == null ? Float.NaN : result.floatValue();
	}

	/** Stores the quality of the picture of the given key, whose vector must be stored. */
	public void putQuality(ImageKey key, float quality) {
		if (!_images.containsKey(key)) {
			throw new IllegalArgumentException("A quality needs its picture's vector: " + key);
		}
		if (Float.isNaN(quality)) {
			_qualities.remove(key);
		} else {
			_qualities.put(key, Float.valueOf(quality));
		}
	}

	/** The text vector of the text of the given hash, <code>null</code> where there is none. */
	public float[] text(String textHash) {
		return _texts.get(textHash);
	}

	/** The photos by name, see {@link Photo}. */
	public Map<String, Photo> photos() {
		return java.util.Collections.unmodifiableMap(_photos);
	}

	/** The keys of the image vectors. */
	public Set<ImageKey> imageKeys() {
		return java.util.Collections.unmodifiableSet(_images.keySet());
	}

	/** How many text vectors there are. */
	public int textCount() {
		return _texts.size();
	}

	/** Stores an image vector. */
	public void putImage(ImageKey key, float[] vector) {
		_images.put(key, check(vector));
	}

	/** Stores a text vector, of a text of the given hash. */
	public void putText(String textHash, float[] vector) {
		_texts.put(textHash, check(vector));
	}

	/** Says which vectors describe the photo of the given name; both must be stored. */
	public void putPhoto(String name, Photo photo) {
		if (!_images.containsKey(photo.image()) || !_texts.containsKey(photo.text())) {
			throw new IllegalArgumentException("A photo needs both its vectors: " + name);
		}
		_photos.put(name, photo);
	}

	/**
	 * Keeps only what the given photos of the album can use: the photos of these names, the image
	 * vectors of these contents (any poster moment of them), and the texts some photo names.
	 */
	public void retain(Collection<String> names, Collection<String> hashes) {
		Set<String> keepNames = new HashSet<>(names);
		Set<String> keepHashes = new HashSet<>(hashes);
		_photos.keySet().retainAll(keepNames);
		_images.keySet().removeIf(key -> !keepHashes.contains(key.hash()));
		_qualities.keySet().retainAll(_images.keySet());
		_photos.values().removeIf(photo -> !_images.containsKey(photo.image()));
		Set<String> texts = new HashSet<>();
		for (Photo photo : _photos.values()) {
			texts.add(photo.text());
		}
		_texts.keySet().retainAll(texts);
	}

	private float[] check(float[] vector) {
		if (vector == null || vector.length != _dimension) {
			throw new IllegalArgumentException("A vector of " + _dimension + " numbers is expected, not "
				+ (vector == null ? "none" : vector.length) + ".");
		}
		return vector;
	}

	/** The SHA-256 of the given text, in lower-case hex: the key of its vector. */
	public static String textHash(String text) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	// --- The file. ---

	/** Writes this cache as the given album's, through a temporary sibling. */
	void write(File folder) throws IOException {
		Path cacheDir = CacheRefresh.cacheDir(folder).toPath();
		Files.createDirectories(cacheDir);
		Path tmp = cacheDir.resolve(FILE_NAME + PreviewCache.TMP_SUFFIX);
		try {
			try (OutputStream out = Files.newOutputStream(tmp)) {
				out.write(toBytes());
			}
			Files.move(tmp, cacheDir.resolve(FILE_NAME), StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.ATOMIC_MOVE);
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	/** This cache as {@value #FILE_NAME} holds it. */
	byte[] toBytes() throws IOException {
		int entry = _dimension * Float.BYTES;
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(
			64 + _images.size() * (HASH_BYTES + 8 + 4 + entry) + _texts.size() * (HASH_BYTES + entry) + _photos.size() * 96);
		DataOutputStream data = new DataOutputStream(bytes);
		ByteBuffer vector = ByteBuffer.allocate(entry).order(ByteOrder.LITTLE_ENDIAN);
		data.write(MAGIC);
		data.writeByte(VERSION);
		writeShort(data, _dimension);
		writeString(data, _imageStamp);
		writeInt(data, _images.size());
		for (Map.Entry<ImageKey, float[]> each : _images.entrySet()) {
			data.write(hashBytes(each.getKey().hash()));
			writeLong(data, each.getKey().poster());
			writeInt(data, Float.floatToIntBits(quality(each.getKey())));
			data.write(floats(vector, each.getValue()));
		}
		writeString(data, _textStamp);
		writeInt(data, _texts.size());
		for (Map.Entry<String, float[]> each : _texts.entrySet()) {
			data.write(hashBytes(each.getKey()));
			data.write(floats(vector, each.getValue()));
		}
		writeInt(data, _photos.size());
		for (Map.Entry<String, Photo> each : _photos.entrySet()) {
			writeString(data, each.getKey());
			data.write(hashBytes(each.getValue().image().hash()));
			writeLong(data, each.getValue().image().poster());
			data.write(hashBytes(each.getValue().text()));
		}
		data.flush();
		CRC32 crc = new CRC32();
		crc.update(bytes.toByteArray());
		writeInt(data, (int) crc.getValue());
		data.flush();
		return bytes.toByteArray();
	}

	/**
	 * Reads what {@link #toBytes()} wrote.
	 *
	 * @throws IOException
	 *         For anything else.
	 */
	static ContentCache parse(byte[] contents) throws IOException {
		if (contents.length < MAGIC.length + 1 + 2 + 4 || !Arrays.equals(contents, 0, MAGIC.length, MAGIC, 0,
			MAGIC.length)) {
			throw new IOException("not a content cache");
		}
		CRC32 crc = new CRC32();
		crc.update(contents, 0, contents.length - 4);
		ByteBuffer in = ByteBuffer.wrap(contents).order(ByteOrder.LITTLE_ENDIAN);
		if (in.getInt(contents.length - 4) != (int) crc.getValue()) {
			throw new IOException("the checksum is wrong (a file cut short?)");
		}
		in.limit(contents.length - 4);
		in.position(MAGIC.length);
		try {
			int version = in.get() & 0xFF;
			if (version != VERSION && version != VERSION_1) {
				throw new IOException("version " + version + ", not " + VERSION);
			}
			boolean qualities = version >= VERSION;
			int dimension = in.getShort() & 0xFFFF;
			if (dimension <= 0) {
				throw new IOException("no dimension");
			}
			String imageStamp = readString(in);
			ContentCache result = new ContentCache(imageStamp, "", dimension);
			int images = count(in, HASH_BYTES + 8 + (qualities ? 4 : 0) + dimension * Float.BYTES);
			for (int n = 0; n < images; n++) {
				String hash = readHash(in);
				long poster = in.getLong();
				float quality = qualities ? in.getFloat() : Float.NaN;
				ImageKey key = new ImageKey(hash, poster);
				result._images.put(key, readFloats(in, dimension));
				if (!Float.isNaN(quality)) {
					result._qualities.put(key, Float.valueOf(quality));
				}
			}
			result._textStamp = readString(in);
			int texts = count(in, HASH_BYTES + dimension * Float.BYTES);
			for (int n = 0; n < texts; n++) {
				String hash = readHash(in);
				result._texts.put(hash, readFloats(in, dimension));
			}
			int photos = count(in, 2 + HASH_BYTES + 8 + HASH_BYTES);
			for (int n = 0; n < photos; n++) {
				String name = readString(in);
				ImageKey image = new ImageKey(readHash(in), in.getLong());
				String text = readHash(in);
				if (result._images.containsKey(image) && result._texts.containsKey(text)) {
					result._photos.put(name, new Photo(image, text));
				}
			}
			if (in.hasRemaining()) {
				throw new IOException("more than it says");
			}
			return result;
		} catch (BufferUnderflowException | IllegalArgumentException ex) {
			throw new IOException("cut short", ex);
		}
	}

	private static int count(ByteBuffer in, int each) throws IOException {
		int count = in.getInt();
		if (count < 0 || (long) count * each > in.remaining()) {
			throw new IOException("a count of " + count + " beyond the file");
		}
		return count;
	}

	private static byte[] floats(ByteBuffer buffer, float[] vector) {
		buffer.clear();
		for (float value : vector) {
			buffer.putFloat(value);
		}
		return buffer.array();
	}

	private static float[] readFloats(ByteBuffer in, int dimension) {
		float[] result = new float[dimension];
		for (int n = 0; n < dimension; n++) {
			result[n] = in.getFloat();
		}
		return result;
	}

	private static byte[] hashBytes(String hex) {
		byte[] result = HexFormat.of().parseHex(hex);
		if (result.length != HASH_BYTES) {
			throw new IllegalArgumentException("Not a SHA-256: " + hex);
		}
		return result;
	}

	private static String readHash(ByteBuffer in) {
		byte[] hash = new byte[HASH_BYTES];
		in.get(hash);
		return HexFormat.of().formatHex(hash);
	}

	private static void writeString(DataOutputStream out, String text) throws IOException {
		byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		if (bytes.length > 0xFFFF) {
			throw new IOException("A name too long for the content cache: " + text.substring(0, 40) + "...");
		}
		writeShort(out, bytes.length);
		out.write(bytes);
	}

	private static String readString(ByteBuffer in) {
		int length = in.getShort() & 0xFFFF;
		byte[] bytes = new byte[length];
		in.get(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}

	private static void writeShort(DataOutputStream out, int value) throws IOException {
		out.write(value & 0xFF);
		out.write((value >>> 8) & 0xFF);
	}

	private static void writeInt(DataOutputStream out, int value) throws IOException {
		out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array());
	}

	private static void writeLong(DataOutputStream out, long value) throws IOException {
		out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array());
	}

	@Override
	public boolean equals(Object other) {
		if (!(other instanceof ContentCache)) {
			return false;
		}
		ContentCache that = (ContentCache) other;
		return _dimension == that._dimension && _imageStamp.equals(that._imageStamp)
			&& _textStamp.equals(that._textStamp) && _photos.equals(that._photos) && _qualities.equals(that._qualities)
			&& sameVectors(_images, that._images)
			&& sameVectors(_texts, that._texts);
	}

	private static <K> boolean sameVectors(Map<K, float[]> left, Map<K, float[]> right) {
		if (!left.keySet().equals(right.keySet())) {
			return false;
		}
		for (Map.Entry<K, float[]> entry : left.entrySet()) {
			if (!Arrays.equals(entry.getValue(), right.get(entry.getKey()))) {
				return false;
			}
		}
		return true;
	}

	@Override
	public int hashCode() {
		return Objects.hash(_imageStamp, _textStamp, _photos.keySet());
	}
}
