/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.faces;

import de.haumacher.imageServer.CacheRefresh;
import de.haumacher.imageServer.PreviewCache;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * What was found in the photographs of one album, in that album's own cache, see issue #124.
 *
 * <p>
 * A cache and nothing else: everything in it is recomputable from the pixels plus the model, so
 * <code>?action=refresh-cache</code> (issue #98) may throw it away and the next pass makes it
 * again, see {@link CacheRefresh#isGenerated(String)}. It lives in the album's
 * {@value PreviewCache#CACHE_DIRECTORY_NAME} beside the previews, never next to the originals.
 * </p>
 *
 * <h2>The file</h2>
 *
 * <pre>
 * {"version":1,"model":"yunet-2022mar+sface-2021dec/1","files":{
 *   "&lt;sha256&gt;":{"faces":[{"box":{"x":0.31,"y":0.12,"w":0.2,"h":0.27},"score":0.99,
 *                            "embedding":"&lt;base64 of 128 little-endian float32&gt;",
 *                            "refined":true}],
 *                 "cluster":["c1"]}}}
 * </pre>
 *
 * <p>
 * Keyed by the SHA-256 of the file's contents, the very hash the {@value
 * de.haumacher.imageServer.upload.HashCache#FILE_NAME} sidecar beside the photographs already
 * knows, so a rename or a move inside the space finds its faces again and a file whose contents
 * changed simply has no entry any more. The whole file is stamped with the
 * {@link FaceDetection#MODEL model}: a stamp that does not match is not read at all, which is how
 * a new model re-detects a library without anybody having to delete anything.
 * </p>
 *
 * <p>
 * <code>cluster</code> is parallel to <code>faces</code>, one identifier per face, filled when the
 * album is clustered (see {@link Clustering}) and empty before that.
 * </p>
 *
 * <p>
 * <b>The embeddings never leave the server.</b> They are here because re-computing them would mean
 * decoding every photograph again; what goes on the wire is a box and a cluster, see
 * {@link de.haumacher.imageServer.shared.model.FaceInfo}.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class FaceCache {

	private static final Logger LOG = Logger.getLogger(FaceCache.class.getName());

	/** The name of the file inside the album's {@value PreviewCache#CACHE_DIRECTORY_NAME}. */
	public static final String FILE_NAME = "faces.json";

	/** The version this build writes. */
	public static final int VERSION = 1;

	private static final String VERSION__PROP = "version";

	private static final String MODEL__PROP = "model";

	private static final String FILES__PROP = "files";

	private static final String FACES__PROP = "faces";

	private static final String CLUSTER__PROP = "cluster";

	private static final String BOX__PROP = "box";

	private static final String SCORE__PROP = "score";

	private static final String EMBEDDING__PROP = "embedding";

	private static final String REFINED__PROP = "refined";

	private static final String X__PROP = "x";

	private static final String Y__PROP = "y";

	private static final String W__PROP = "w";

	private static final String H__PROP = "h";

	/** One face of one photograph, as it is kept here. */
	public static final class Face {

		private final double _x;

		private final double _y;

		private final double _w;

		private final double _h;

		private final double _score;

		private final float[] _embedding;

		private final boolean _refined;

		private String _cluster = "";

		/** Creates a {@link Face} from a box in the raw raster of the file, see {@link Faces}. */
		public Face(double x, double y, double w, double h, double score, float[] embedding) {
			this(x, y, w, h, score, embedding, false);
		}

		/**
		 * Creates a {@link Face} that says whether it was described at the best resolution this
		 * build knows, see issue #140.
		 */
		public Face(double x, double y, double w, double h, double score, float[] embedding,
				boolean refined) {
			_x = x;
			_y = y;
			_w = w;
			_h = h;
			_score = score;
			_embedding = embedding == null ? new float[0] : embedding;
			_refined = refined;
		}

		/** The left edge, as a fraction of the raw file width. */
		public double getX() {
			return _x;
		}

		/** The top edge, as a fraction of the raw file height. */
		public double getY() {
			return _y;
		}

		/** The width, as a fraction of the raw file width. */
		public double getW() {
			return _w;
		}

		/** The height, as a fraction of the raw file height. */
		public double getH() {
			return _h;
		}

		/** How sure the detector was. */
		public double getScore() {
			return _score;
		}

		/** The numbers the recogniser describes this face with; never answered on the wire. */
		public float[] getEmbedding() {
			return _embedding;
		}

		/**
		 * Whether this face was described at the best resolution this build knows, see issue #140.
		 *
		 * <p>
		 * True for a face that was looked up in the original because it was smaller than
		 * {@link FaceDetection#REFINE_PIXELS} on the preview, and for one that was large enough on
		 * the preview to need no such look. False — which is what an entry written before issue #140
		 * reads as, the field being absent there — means that a pass may still have something to
		 * gain here, see {@link FaceIndex}.
		 * </p>
		 */
		public boolean isRefined() {
			return _refined;
		}

		/** Which group of the album's faces this one was put into; empty before clustering. */
		public String getCluster() {
			return _cluster;
		}

		/** Puts this face into a group, see {@link Clustering}. */
		public void setCluster(String cluster) {
			_cluster = cluster == null ? "" : cluster;
		}
	}

	private final File _folder;

	private final File _file;

	/** The faces by content hash, in the order they were read or added. */
	private Map<String, List<Face>> _byHash = new LinkedHashMap<>();

	private boolean _dirty;

	/** Loads the faces of the given album folder; nothing is detected and nothing is written. */
	public FaceCache(File folder) {
		_folder = folder;
		_file = file(folder);
		load();
	}

	/** Where the faces of the given album folder are kept, whether the file exists or not. */
	public static File file(File folder) {
		return new File(CacheRefresh.cacheDir(folder), FILE_NAME);
	}

	/** The file this cache is persisted in. */
	public File getFile() {
		return _file;
	}

	/** Whether anything at all is recorded here. */
	public boolean isEmpty() {
		return _byHash.isEmpty();
	}

	/** Whether the contents with the given hash were looked at. */
	public boolean knows(String hash) {
		return _byHash.containsKey(hash);
	}

	/** The faces of the contents with the given hash, empty when there are none or it is unknown. */
	public List<Face> facesOf(String hash) {
		List<Face> result = _byHash.get(hash);
		return result == null ? Collections.emptyList() : Collections.unmodifiableList(result);
	}

	/** The hashes this cache knows, in the order they were recorded. */
	public java.util.Set<String> hashes() {
		return Collections.unmodifiableSet(_byHash.keySet());
	}

	/** Records what was found in the contents with the given hash; an empty list is a result too. */
	public void put(String hash, List<Face> faces) {
		_byHash.put(hash, new ArrayList<>(faces));
		_dirty = true;
	}

	/** Forgets every hash that is not among the given ones, after the album changed. */
	public void retain(java.util.Set<String> hashes) {
		if (_byHash.keySet().retainAll(hashes)) {
			_dirty = true;
		}
	}

	/** Says that a cluster was assigned; the file has to be written again. */
	public void clustered() {
		_dirty = true;
	}

	/** Every face of this album, in the order of the hashes, for the clustering. */
	public List<Face> allFaces() {
		List<Face> result = new ArrayList<>();
		for (List<Face> faces : _byHash.values()) {
			result.addAll(faces);
		}
		return result;
	}

	/** Writes the cache back, if anything changed. */
	public void flush() throws IOException {
		if (!_dirty) {
			return;
		}
		store();
		_dirty = false;
	}

	private void load() {
		if (!_file.isFile()) {
			return;
		}
		try (Reader reader = new InputStreamReader(Files.newInputStream(_file.toPath()), StandardCharsets.UTF_8)) {
			read(new JsonReader(new ReaderAdapter(reader)));
		} catch (IOException | RuntimeException ex) {
			// A cache can always be rebuilt; a broken one must never stop an album from loading.
			LOG.log(Level.WARNING, "Rebuilding the unreadable face cache '" + _file.getAbsolutePath()
				+ "': " + ex.getMessage());
			_byHash = new LinkedHashMap<>();
		}
	}

	private void read(JsonReader in) throws IOException {
		String model = "";
		Map<String, List<Face>> files = new LinkedHashMap<>();
		in.beginObject();
		while (in.hasNext()) {
			String key = in.nextName();
			switch (key) {
				case MODEL__PROP:
					model = in.nextString();
					break;
				case FILES__PROP:
					in.beginObject();
					while (in.hasNext()) {
						String hash = in.nextName();
						files.put(hash, readEntry(in));
					}
					in.endObject();
					break;
				default:
					in.skipValue();
					break;
			}
		}
		in.endObject();
		if (!FaceDetection.MODEL.equals(model)) {
			// Another model looked at these photographs; nothing it said counts here.
			return;
		}
		_byHash = files;
	}

	private static List<Face> readEntry(JsonReader in) throws IOException {
		List<Face> faces = new ArrayList<>();
		List<String> clusters = new ArrayList<>();
		in.beginObject();
		while (in.hasNext()) {
			String key = in.nextName();
			switch (key) {
				case FACES__PROP:
					in.beginArray();
					while (in.hasNext()) {
						faces.add(readFace(in));
					}
					in.endArray();
					break;
				case CLUSTER__PROP:
					in.beginArray();
					while (in.hasNext()) {
						clusters.add(in.nextString());
					}
					in.endArray();
					break;
				default:
					in.skipValue();
					break;
			}
		}
		in.endObject();
		for (int n = 0, size = Math.min(faces.size(), clusters.size()); n < size; n++) {
			faces.get(n).setCluster(clusters.get(n));
		}
		return faces;
	}

	private static Face readFace(JsonReader in) throws IOException {
		double x = 0;
		double y = 0;
		double w = 0;
		double h = 0;
		double score = 0;
		float[] embedding = new float[0];
		boolean refined = false;
		in.beginObject();
		while (in.hasNext()) {
			String key = in.nextName();
			switch (key) {
				case BOX__PROP:
					in.beginObject();
					while (in.hasNext()) {
						String field = in.nextName();
						double value = in.nextDouble();
						switch (field) {
							case X__PROP: x = value; break;
							case Y__PROP: y = value; break;
							case W__PROP: w = value; break;
							case H__PROP: h = value; break;
							default: break;
						}
					}
					in.endObject();
					break;
				case SCORE__PROP:
					score = in.nextDouble();
					break;
				case EMBEDDING__PROP:
					embedding = decode(in.nextString());
					break;
				case REFINED__PROP:
					refined = in.nextBoolean();
					break;
				default:
					in.skipValue();
					break;
			}
		}
		in.endObject();
		return new Face(x, y, w, h, score, embedding, refined);
	}

	private void store() throws IOException {
		Path cacheDir = CacheRefresh.cacheDir(_folder).toPath();
		Files.createDirectories(cacheDir);
		Path tmp = cacheDir.resolve(FILE_NAME + PreviewCache.TMP_SUFFIX);
		try (Writer writer = new OutputStreamWriter(Files.newOutputStream(tmp), StandardCharsets.UTF_8);
				JsonWriter out = new JsonWriter(new WriterAdapter(writer))) {
			out.beginObject();
			out.name(VERSION__PROP);
			out.value(VERSION);
			out.name(MODEL__PROP);
			out.value(FaceDetection.MODEL);
			out.name(FILES__PROP);
			out.beginObject();
			for (Map.Entry<String, List<Face>> entry : _byHash.entrySet()) {
				out.name(entry.getKey());
				out.beginObject();
				out.name(FACES__PROP);
				out.beginArray();
				for (Face face : entry.getValue()) {
					out.beginObject();
					out.name(BOX__PROP);
					out.beginObject();
					out.name(X__PROP);
					out.value(face.getX());
					out.name(Y__PROP);
					out.value(face.getY());
					out.name(W__PROP);
					out.value(face.getW());
					out.name(H__PROP);
					out.value(face.getH());
					out.endObject();
					out.name(SCORE__PROP);
					out.value(face.getScore());
					out.name(EMBEDDING__PROP);
					out.value(encode(face.getEmbedding()));
					if (face.isRefined()) {
						// Absent is false, so a file this build writes is read by an older one.
						out.name(REFINED__PROP);
						out.value(true);
					}
					out.endObject();
				}
				out.endArray();
				out.name(CLUSTER__PROP);
				out.beginArray();
				for (Face face : entry.getValue()) {
					out.value(face.getCluster());
				}
				out.endArray();
				out.endObject();
			}
			out.endObject();
			out.endObject();
		}
		try {
			Files.move(tmp, _file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException ex) {
			Files.move(tmp, _file.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** The given numbers as little-endian float32, Base64 encoded. */
	static String encode(float[] values) {
		ByteBuffer buffer = ByteBuffer.allocate(values.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
		for (float value : values) {
			buffer.putFloat(value);
		}
		return Base64.getEncoder().encodeToString(buffer.array());
	}

	/** The inverse of {@link #encode(float[])}; an unreadable value is no embedding at all. */
	static float[] decode(String encoded) {
		if (encoded == null || encoded.isEmpty()) {
			return new float[0];
		}
		byte[] bytes;
		try {
			bytes = Base64.getDecoder().decode(encoded);
		} catch (IllegalArgumentException ex) {
			return new float[0];
		}
		ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		float[] result = new float[bytes.length / Float.BYTES];
		for (int n = 0; n < result.length; n++) {
			result[n] = buffer.getFloat();
		}
		return result;
	}
}
