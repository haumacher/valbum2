/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.faces;

import de.haumacher.imageServer.index.SpaceIndex;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.FaceState;
import de.haumacher.imageServer.shared.model.FaceTag;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.upload.HashCache;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Who the people of one space look like, and which unnamed face is most likely one of them, see
 * issue #127.
 *
 * <h2>The prototypes</h2>
 *
 * <p>
 * For every person of the space, the embeddings of the faces somebody <em>confirmed</em> as them,
 * wherever in the space they stand. Every one of them is read from two things that are already on
 * disk — the {@link FaceTag} in the album's <code>index.json</code>, which is the stored truth, and
 * the embedding in that album's {@link FaceCache}, which is cache — and matched against in memory.
 * </p>
 *
 * <p>
 * <b>Kept in the index of the space</b> (issue #264, the tables <code>face_albums</code> and
 * <code>face_prototypes</code> of {@link SpaceIndex}), so that a restart knows at once whom the
 * people look like, without an album being listed or walked: what is read into memory is written
 * through, per album, with a stamp of the three files it was read from. Like every table of that
 * index it is derived and no truth: the background compares the stamp of every album it visits
 * ({@link #refresh(File)}) and reads the album again where it differs, so an index that missed a
 * change (a process killed, a build that did not keep it, a sidecar written behind the server's
 * back) is put right by the next walk. The biometrics stay with the space and in its own folder:
 * the index lives in <code>.valbum/index/</code>, is no part of a backup, and holds the embeddings of
 * confirmed faces only.
 * </p>
 *
 * <p>
 * A tag and a detection are two different statements about one face and are brought together here
 * exactly as {@link FaceTags} brings them together on the way out: by the box they are both written
 * in, overlapping by more than {@link FaceTags#IOU_MATCH}. Nothing is ever recomputed from pixels
 * here — a confirmed face whose detection the cache does not hold (a cache thrown away, a model
 * that no longer finds it, a machine without a detector) simply contributes nothing until the album
 * is looked at again. That is why a suggestion may come and go with the cache while a decision
 * never does.
 * </p>
 *
 * <h2>Where they come from</h2>
 *
 * <p>
 * Per album, and always the whole album at once, so that a contribution is <em>replaced</em> rather
 * than accumulated and a decision taken back leaves nothing behind. What the index of the space
 * holds is read when the recognition is created; after that, these places hand an album in:
 * </p>
 *
 * <ul>
 * <li>the detection of the background, at the end of every album it looks at
 * ({@link FaceIndex#step()}, and {@link FaceIndex#indexNow()} for the tests), and the step behind
 * it that compares the stamps of every album ({@link FaceIndex#prototypeStep()},
 * {@link #refresh(File)}), on the one low-priority thread the space already has;</li>
 * <li>a move, a delete, a purge or a rename of folders, which reads the trees again
 * ({@link #treeChanged(File, boolean)});</li>
 * <li>every listing of an album, from {@link FaceIndex#derive(AlbumInfo, File, PeopleStore)}, which
 * has read the two sidecars for its own sake anyway, so this costs no file access at all and keeps
 * an album that was moved, renamed or written behind the server's back current;</li>
 * <li>the moment a decision lands, from <code>?action=tag-faces</code>, so that the very next
 * listing of <em>another</em> album reflects it without waiting for any walk.</li>
 * </ul>
 *
 * <h2>The rule</h2>
 *
 * <p>
 * For a detection that carries no decision at all: the cosine similarity to the nearest prototype
 * of every person; the best person is suggested when that similarity reaches
 * {@link #threshold()} <em>and</em> leads the second-best person by {@link #margin()}. Two numbers
 * and not one, because the threshold alone answers &quot;this looks like Anna&quot; while the
 * margin answers &quot;and it does not look just as much like Annie&quot;, and a suggestion that
 * cannot tell two sisters apart is worse than none.
 * </p>
 *
 * <p>
 * <b>A face that carries any decision is never suggested anybody</b> — neither one confirmed
 * (it <em>is</em> that person, which is not a guess), nor one rejected, nor one somebody said is no
 * face at all. For the rejected one that is a deliberate choice: the wire carries one person and
 * one state per face (see {@link de.haumacher.imageServer.shared.model.FaceInfo}), a rejection
 * already occupies both, and overwriting them with a guess would erase a decision somebody made in
 * order to put a different guess in its place. So a rejection is not only &quot;never this person
 * again&quot;, it is &quot;never a suggestion again&quot; for that face; whoever knows who it is
 * names them, which is the same one click in the editor either way.
 * </p>
 *
 * <p>
 * A person merged away resolves to the survivor before anything is compared, so both sets of
 * prototypes count for the survivor and a merge is felt at once, without a single album being
 * rewritten — the same resolution {@link FaceTags#person(String, PeopleStore)} does. It happens
 * when a {@link Match} is built and never when a prototype is stored, because a merge does not pass
 * through here at all.
 * </p>
 *
 * <p>
 * Nothing here is ever written to a sidecar. A suggestion is derived at request time, every time,
 * and vanishes when the model changes, when the cache is thrown away or when somebody decides
 * otherwise. A suggestion is a face, and a face is answered only on an album the caller may see,
 * to a member of the space, see {@link FaceIndex}.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class Recognition {

	private static final Logger LOG = Logger.getLogger(Recognition.class.getName());

	/**
	 * The system property the similarity threshold is read from.
	 *
	 * <p>
	 * A property and never a setting of the user interface, see issue #127: it is a property of the
	 * model, not a taste, and somebody who tunes it wants it to hold for the whole server.
	 * </p>
	 */
	public static final String THRESHOLD_PROPERTY = "valbum.faceThreshold";

	/** The system property the margin to the second-best person is read from. */
	public static final String MARGIN_PROPERTY = "valbum.faceMargin";

	/**
	 * How similar a face must be to a person's nearest confirmed face to be suggested as them.
	 *
	 * <p>
	 * The cosine threshold SFace is published with, the very number {@link Clustering#THRESHOLD}
	 * groups an album's faces by: what counts as &quot;the same person&quot; must not depend on
	 * whether the two faces happen to stand in one album.
	 * </p>
	 */
	public static final double DEFAULT_THRESHOLD = Clustering.THRESHOLD;

	/**
	 * How far the best person must lead the second-best one for the best one to be suggested.
	 *
	 * <p>
	 * A tenth of the threshold itself, which is about what two photographs of one person differ by
	 * when the light changes; two <em>different</em> people this close to one face are two people
	 * the numbers cannot tell apart, and then the honest answer is no suggestion at all. A person
	 * with no rival is never held back by this — there is no second-best to lead.
	 * </p>
	 */
	public static final double DEFAULT_MARGIN = 0.05;

	/** One confirmed face, as it is remembered here. */
	public static final class Prototype {

		private final String _person;

		private final float[] _embedding;

		/**
		 * Creates a {@link Prototype}.
		 *
		 * @param person
		 *        The stored {@link FaceTag#getPerson() id}, never resolved: a merge is felt when a
		 *        {@link Match} is built, so that it needs nothing here to be rebuilt.
		 * @param embedding
		 *        The numbers the recogniser described the face with; stored normalised, so that a
		 *        comparison is one dot product.
		 */
		public Prototype(String person, float[] embedding) {
			_person = person == null ? "" : person;
			_embedding = normalised(embedding);
		}

		/** Whom this face was confirmed as. */
		public String getPerson() {
			return _person;
		}

		/** The normalised embedding of this face. */
		public float[] getEmbedding() {
			return _embedding;
		}

		boolean sameAs(Prototype other) {
			return _person.equals(other._person) && Arrays.equals(_embedding, other._embedding);
		}
	}

	/** The prototypes of one album, by its absolute path. */
	private final ConcurrentHashMap<String, List<Prototype>> _byFolder = new ConcurrentHashMap<>();

	/**
	 * Which model the prototypes in hand describe.
	 *
	 * <p>
	 * An embedding of one model says nothing about an embedding of another, so everything is
	 * dropped the moment the stamp changes rather than being compared across models. In practice
	 * the {@link FaceCache} has already refused to read the old numbers, and this is the second
	 * lock on the same door.
	 * </p>
	 */
	private volatile String _model = FaceDetection.MODEL;

	/** The prototypes flattened for matching, <code>null</code> while it has to be rebuilt. */
	private volatile Flat _flat;

	/** The stamp of an album whose stamp nobody checked: never the stamp of any album. */
	static final long UNVERIFIED = 0;

	/** The root of the space, <code>null</code> for a recognition kept in memory alone. */
	private final Path _root;

	/** Where the prototypes are kept across a restart, <code>null</code> for none. */
	private final SpaceIndex _store;

	/**
	 * The stamp of every album the index holds, by its path relative to {@link #_root}, see
	 * {@link #stampOf(File)}; {@link #UNVERIFIED} for one of another model or written by a request.
	 * Guarded by <code>this</code>.
	 */
	private final Map<String, Long> _stamps = new HashMap<>();

	/** How many albums were read from their sidecars, for the tests. */
	private final AtomicInteger _reads = new AtomicInteger();

	/** Creates the recognition of one space in memory alone; nothing is read until an album is handed in. */
	public Recognition() {
		this(null, null);
	}

	/**
	 * Creates the recognition of one space, kept in the given index, and reads what it holds.
	 *
	 * @param root
	 *        The root of the space, which the paths in the index are relative to.
	 * @param store
	 *        The index of the space, <code>null</code> for a recognition in memory alone.
	 */
	public Recognition(Path root, SpaceIndex store) {
		_root = root == null ? null : root.toAbsolutePath().normalize();
		_store = _root == null ? null : store;
		load();
	}

	/** Reads what the index holds into memory, see {@link Recognition}. */
	private void load() {
		if (_store == null) {
			return;
		}
		String model = FaceDetection.MODEL;
		Map<String, Long> stamps = new HashMap<>();
		Map<String, List<Prototype>> byFolder = new HashMap<>();
		boolean read = _store.read(connection -> {
			try (Statement statement = connection.createStatement()) {
				try (ResultSet rows = statement.executeQuery("SELECT path, stamp, model FROM face_albums")) {
					while (rows.next()) {
						// An album of another model is known, and read again by the background.
						stamps.put(rows.getString(1),
							Long.valueOf(model.equals(rows.getString(3)) ? rows.getLong(2) : UNVERIFIED));
					}
				}
			}
			try (PreparedStatement select = connection.prepareStatement("SELECT p.album, p.person, p.embedding "
				+ "FROM face_prototypes p JOIN face_albums a ON a.path = p.album WHERE a.model = ? ORDER BY p.album, p.n")) {
				select.setString(1, model);
				try (ResultSet rows = select.executeQuery()) {
					while (rows.next()) {
						byFolder.computeIfAbsent(rows.getString(1), key -> new ArrayList<>())
							.add(new Prototype(rows.getString(2), floats(rows.getBytes(3))));
					}
				}
			}
			return Boolean.TRUE;
		}, Boolean.FALSE).booleanValue();
		if (!read) {
			return;
		}
		synchronized (this) {
			_stamps.putAll(stamps);
			for (Map.Entry<String, List<Prototype>> entry : byFolder.entrySet()) {
				_byFolder.put(absolute(entry.getKey()), entry.getValue());
			}
			_flat = null;
		}
	}

	/** How many confirmed faces are remembered altogether. */
	public int size() {
		return flat()._persons.length;
	}

	/** Whether nothing at all is known about anybody yet. */
	public boolean isEmpty() {
		return size() == 0;
	}

	// --- What is remembered. ---

	/**
	 * Replaces what the given album contributes, reading its decisions from its own sidecar.
	 *
	 * <p>
	 * What the walk uses, where no album has been read for any other reason. An album without a
	 * sidecar carries no decision and therefore contributes nothing.
	 * </p>
	 */
	public void observe(File folder) {
		// Before reading: a file written meanwhile leaves a stamp behind that is not its own, and
		// the album is read again on the next visit.
		long stamp = stampOf(folder);
		_reads.incrementAndGet();
		AlbumInfo album = sidecarOf(folder);
		put(folder, album == null ? Collections.<Prototype> emptyList() : collect(folder, album), stamp);
	}

	/**
	 * Reads the given album again where any of the files its prototypes were read from changed
	 * since, or where it was never read; what the background does with every album it visits, see
	 * {@link Recognition}.
	 *
	 * @return Whether it was read.
	 */
	public boolean refresh(File folder) {
		String path = relative(folder);
		if (path == null) {
			return false;
		}
		if (!folder.isDirectory()) {
			removeTree(path);
			return false;
		}
		long now = stampOf(folder);
		synchronized (this) {
			Long known = _stamps.get(path);
			if (known != null && known.longValue() == now) {
				return false;
			}
		}
		observe(folder);
		return true;
	}

	/**
	 * Reads the given folder and every folder below it again where it changed, and forgets what is
	 * gone of it: a move, a delete, a purge or a rename rearranged the tree.
	 *
	 * @param read
	 *        Whether to read what changed, or only to forget what is gone: a space that looks for
	 *        no faces keeps what it knew, and reads nothing.
	 */
	public void treeChanged(File folder, boolean read) {
		String path = relative(folder);
		if (path == null) {
			return;
		}
		Set<String> seen = new HashSet<>();
		for (File below : de.haumacher.imageServer.pipeline.FolderPipeline.tree(folder)) {
			String known = relative(below);
			seen.add(known);
			if (read) {
				refresh(below);
			}
		}
		for (String known : knownBelow(path)) {
			if (!seen.contains(known)) {
				removeTree(known);
			}
		}
	}

	/**
	 * Forgets every album that is gone or no part of the walked library, see
	 * {@link de.haumacher.imageServer.pipeline.FolderPipeline.Step#retain(Set)}.
	 */
	public void retain(Set<String> paths) {
		for (String known : knownBelow("")) {
			if (!new File(_root.toFile(), known).isDirectory()
				|| (!paths.contains(known) && !de.haumacher.imageServer.LibraryFiles.isLibraryPath(known))) {
				removeTree(known);
			}
		}
	}

	/** How many albums were read from their sidecars since this recognition was created; for the tests. */
	public int reads() {
		return _reads.get();
	}

	/**
	 * Replaces what the given album contributes by what the given album says today.
	 *
	 * @param album
	 *        The album as it is stored — its {@link ImagePart#getTags() tags} are read and nothing
	 *        else.
	 */
	public void observe(File folder, AlbumInfo album) {
		put(folder, collect(folder, album));
	}

	/**
	 * Replaces what the given album contributes by the given prototypes.
	 *
	 * <p>
	 * The one place anything is remembered. Per album and always wholesale: a decision taken back
	 * disappears because the album is described afresh, never because somebody found the entry it
	 * had left behind.
	 * </p>
	 *
	 * <p>
	 * The caller read the prototypes from somewhere else than the files on disk (the album of the
	 * cache): a change is written through to the index with a stamp nobody checked, so that the
	 * background reads the album itself on its next visit, see {@link #put(File, List, long)}.
	 * </p>
	 */
	public void put(File folder, List<Prototype> prototypes) {
		put(folder, prototypes, UNVERIFIED);
	}

	/**
	 * The one place anything is remembered, in memory and in the index, see
	 * {@link #put(File, List)}.
	 *
	 * @param stamp
	 *        The {@link #stampOf(File) stamp} of the files the prototypes were read from, taken
	 *        before they were read; {@link #UNVERIFIED} where the caller read them from somewhere
	 *        else (the album of the cache), which keeps the stamp in the index where nothing
	 *        changed.
	 */
	private synchronized void put(File folder, List<Prototype> prototypes, long stamp) {
		String model = FaceDetection.MODEL;
		if (!model.equals(_model)) {
			// Another model described these faces; not one of its numbers is comparable to one of
			// this model's.
			_byFolder.clear();
			_stamps.replaceAll((path, known) -> Long.valueOf(UNVERIFIED));
			_model = model;
			_flat = null;
		}
		String key = folder.getAbsolutePath();
		List<Prototype> previous;
		if (prototypes.isEmpty()) {
			previous = _byFolder.remove(key);
		} else {
			previous = _byFolder.put(key, new ArrayList<>(prototypes));
		}
		boolean same = same(previous, prototypes);
		if (!same) {
			_flat = null;
		}

		String path = _store == null ? null : relative(folder);
		if (path == null) {
			return;
		}
		Long known = _stamps.get(path);
		if (known == null && prototypes.isEmpty() && stamp == UNVERIFIED) {
			// Nothing to keep, and nothing anybody checked: an album the index does not know is one
			// not read yet anyway.
			return;
		}
		long stored = stamp != UNVERIFIED ? stamp : (same && known != null ? known.longValue() : UNVERIFIED);
		if (same && known != null && known.longValue() == stored) {
			return;
		}
		_stamps.put(path, Long.valueOf(stored));
		if (!_store.write(connection -> store(connection, path, prototypes, stored, model))) {
			// The index keeps what it had; the background reads the album again.
			_stamps.remove(path);
		}
	}

	/** Writes what the album at the given path contributes, as one transaction. */
	private static void store(Connection connection, String path, List<Prototype> prototypes, long stamp, String model)
			throws SQLException {
		try (PreparedStatement album =
			connection.prepareStatement("MERGE INTO face_albums (path, stamp, model) KEY (path) VALUES (?, ?, ?)")) {
			album.setString(1, path);
			album.setLong(2, stamp);
			album.setString(3, model);
			album.executeUpdate();
		}
		try (PreparedStatement delete = connection.prepareStatement("DELETE FROM face_prototypes WHERE album = ?")) {
			delete.setString(1, path);
			delete.executeUpdate();
		}
		if (prototypes.isEmpty()) {
			return;
		}
		try (PreparedStatement insert = connection
			.prepareStatement("INSERT INTO face_prototypes (album, n, person, embedding) VALUES (?, ?, ?, ?)")) {
			int n = 0;
			for (Prototype prototype : prototypes) {
				insert.setString(1, path);
				insert.setInt(2, n++);
				insert.setString(3, prototype.getPerson());
				insert.setBytes(4, bytes(prototype.getEmbedding()));
				insert.addBatch();
			}
			insert.executeBatch();
		}
	}

	/**
	 * Forgets everything the given album contributed, in the index as well: the cache it was read
	 * from was thrown away, and the background reads the album again.
	 */
	public void forget(File folder) {
		synchronized (this) {
			if (_byFolder.remove(folder.getAbsolutePath()) != null) {
				_flat = null;
			}
		}
		String path = _store == null ? null : relative(folder);
		if (path != null) {
			remove(path, false);
		}
	}

	/** Forgets the album at the given path (relative to the root) and every album below it. */
	public void removeTree(String path) {
		if (_root == null) {
			return;
		}
		synchronized (this) {
			String key = absolute(path);
			String prefix = key.endsWith(File.separator) ? key : key + File.separator;
			if (_byFolder.keySet().removeIf(folder -> folder.equals(key) || folder.startsWith(prefix))) {
				_flat = null;
			}
		}
		if (_store != null) {
			remove(path, true);
		}
	}

	/** Forgets what the index holds of the album at the given path, and of the albums below it. */
	private synchronized void remove(String path, boolean tree) {
		String below = path.isEmpty() ? "" : path + "/";
		_stamps.keySet().removeIf(known -> known.equals(path) || (tree && (path.isEmpty() || known.startsWith(below))));
		_store.write(connection -> {
			if (tree && path.isEmpty()) {
				try (Statement delete = connection.createStatement()) {
					delete.executeUpdate("DELETE FROM face_albums");
				}
				return;
			}
			try (PreparedStatement delete = connection.prepareStatement(tree
				? "DELETE FROM face_albums WHERE path = ? OR path LIKE ? ESCAPE '\\'"
				: "DELETE FROM face_albums WHERE path = ?")) {
				delete.setString(1, path);
				if (tree) {
					delete.setString(2, path.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "/%");
				}
				delete.executeUpdate();
			}
		});
	}

	/** The paths of the albums the index holds at or below the given one. */
	private synchronized List<String> knownBelow(String path) {
		List<String> result = new ArrayList<>();
		String below = path + "/";
		for (String known : _stamps.keySet()) {
			if (path.isEmpty() || known.equals(path) || known.startsWith(below)) {
				result.add(known);
			}
		}
		return result;
	}

	/**
	 * A number that changes when any of the files the prototypes of the given album are read from
	 * changes: its <code>index.json</code>, its {@link FaceCache} and its
	 * <code>.hashes.json</code>, by their modification times and sizes, and the model; never
	 * {@link #UNVERIFIED}.
	 */
	static long stampOf(File folder) {
		long result = FaceDetection.MODEL.hashCode();
		for (File file : new File[] { new File(folder, "index.json"), FaceCache.file(folder),
			new File(folder, HashCache.FILE_NAME) }) {
			result = 31 * result + file.lastModified();
			result = 31 * result + file.length();
		}
		return result == UNVERIFIED ? 1 : result;
	}

	/** The path of the given folder relative to the root, <code>null</code> outside it. */
	private String relative(File folder) {
		if (_root == null) {
			return null;
		}
		Path path = folder.toPath().toAbsolutePath().normalize();
		if (!path.startsWith(_root)) {
			return null;
		}
		return _root.relativize(path).toString().replace(File.separatorChar, '/');
	}

	/** The absolute path of the folder at the given path relative to the root, as a key of memory. */
	private String absolute(String path) {
		return path.isEmpty() ? _root.toFile().getAbsolutePath()
			: _root.resolve(path.replace('/', File.separatorChar)).toFile().getAbsolutePath();
	}

	/** The given normalised embedding as it is stored, four bytes per number. */
	static byte[] bytes(float[] embedding) {
		ByteBuffer buffer = ByteBuffer.allocate(4 * embedding.length).order(ByteOrder.LITTLE_ENDIAN);
		for (float value : embedding) {
			buffer.putFloat(value);
		}
		return buffer.array();
	}

	/** The embedding stored as the given bytes, see {@link #bytes(float[])}. */
	static float[] floats(byte[] bytes) {
		ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		float[] result = new float[bytes.length / 4];
		for (int n = 0; n < result.length; n++) {
			result[n] = buffer.getFloat();
		}
		return result;
	}

	private static boolean same(List<Prototype> previous, List<Prototype> current) {
		if (previous == null) {
			return current.isEmpty();
		}
		if (previous.size() != current.size()) {
			return false;
		}
		for (int n = 0; n < current.size(); n++) {
			if (!previous.get(n).sameAs(current.get(n))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * The confirmed faces of the given album, read from its own cache.
	 *
	 * <p>
	 * The two small sidecars of the album; a caller that has read them already hands them in, see
	 * {@link #collect(AlbumInfo, FaceCache, Map)}.
	 * </p>
	 */
	public List<Prototype> collect(File folder, AlbumInfo album) {
		if (!hasConfirmation(album)) {
			// Neither sidecar is opened for an album nobody has named anybody in, which is most of
			// them.
			return Collections.emptyList();
		}
		return collect(album, new FaceCache(folder), new HashCache(folder).storedHashByName());
	}

	/**
	 * The confirmed faces of the given album, from the two sidecars the caller has already read.
	 *
	 * @param cache
	 *        What the detector found in this album, <code>null</code> where nothing was detected.
	 * @param hashByName
	 *        Which contents each file of this album holds, the key the cache is written under.
	 */
	public static List<Prototype> collect(AlbumInfo album, FaceCache cache, Map<String, String> hashByName) {
		if (cache == null || cache.isEmpty()) {
			return Collections.emptyList();
		}
		List<Prototype> result = new ArrayList<>();
		for (ImagePart image : FaceIndex.imagesOf(album)) {
			if (image.getTags().isEmpty()) {
				continue;
			}
			String hash = hashByName.get(image.getName());
			if (hash == null) {
				continue;
			}
			List<FaceCache.Face> faces = cache.facesOf(hash);
			if (faces.isEmpty()) {
				continue;
			}
			for (FaceTag tag : image.getTags()) {
				if (tag.getState() != FaceState.CONFIRMED || tag.getPerson().isEmpty()) {
					continue;
				}
				FaceCache.Face face = matching(faces, tag);
				if (face == null || face.getEmbedding().length == 0) {
					// A decision whose detection is not in the cache: answered as a face all the
					// same (see FaceTags), but there are no numbers to compare anybody to.
					continue;
				}
				result.add(new Prototype(tag.getPerson(), face.getEmbedding()));
			}
		}
		return result;
	}

	/** Whether anybody at all is confirmed in the given album. */
	private static boolean hasConfirmation(AlbumInfo album) {
		for (ImagePart image : FaceIndex.imagesOf(album)) {
			for (FaceTag tag : image.getTags()) {
				if (tag.getState() == FaceState.CONFIRMED && !tag.getPerson().isEmpty()) {
					return true;
				}
			}
		}
		return false;
	}

	/** The detection the given decision is about, <code>null</code> when the cache holds none. */
	private static FaceCache.Face matching(List<FaceCache.Face> faces, FaceTag tag) {
		FaceCache.Face best = null;
		double bestOverlap = FaceTags.IOU_MATCH;
		for (FaceCache.Face face : faces) {
			double overlap = FaceTags.iou(face.getX(), face.getY(), face.getW(), face.getH(),
				tag.getX(), tag.getY(), tag.getW(), tag.getH());
			if (overlap > bestOverlap) {
				bestOverlap = overlap;
				best = face;
			}
		}
		return best;
	}

	/** The album stored in the given folder, <code>null</code> when none is. */
	private static AlbumInfo sidecarOf(File folder) {
		File file = new File(folder, "index.json");
		if (!file.isFile()) {
			return null;
		}
		try (Reader reader =
			new InputStreamReader(Files.newInputStream(file.toPath()), StandardCharsets.UTF_8)) {
			Resource resource = Resource.readResource(new JsonReader(new ReaderAdapter(reader)));
			return resource instanceof AlbumInfo ? (AlbumInfo) resource : null;
		} catch (IOException | RuntimeException ex) {
			// A sidecar that cannot be read is the resource cache's business to complain about;
			// here it simply means that nobody is known to be in this album.
			LOG.log(Level.FINE, "Cannot read the decisions of '" + folder + "': " + ex.getMessage(), ex);
			return null;
		}
	}

	// --- Matching. ---

	/** The prototypes of every album in one array each, built once and kept until something moves. */
	private static final class Flat {

		final float[][] _embeddings;

		final String[] _persons;

		Flat(float[][] embeddings, String[] persons) {
			_embeddings = embeddings;
			_persons = persons;
		}
	}

	private Flat flat() {
		Flat result = _flat;
		if (result != null) {
			return result;
		}
		synchronized (this) {
			result = _flat;
			if (result != null) {
				return result;
			}
			int count = 0;
			for (List<Prototype> folder : _byFolder.values()) {
				count += folder.size();
			}
			float[][] embeddings = new float[count][];
			String[] persons = new String[count];
			int n = 0;
			for (List<Prototype> folder : _byFolder.values()) {
				for (Prototype prototype : folder) {
					embeddings[n] = prototype.getEmbedding();
					persons[n] = prototype.getPerson();
					n++;
				}
			}
			result = new Flat(embeddings, persons);
			_flat = result;
			return result;
		}
	}

	/**
	 * What every face of one album is compared against, with the merges of the moment applied.
	 *
	 * <p>
	 * Built once per album answer and used for every face in it: the prototypes themselves are
	 * shared with this object, only the resolution of the ids is done here, which is one lookup per
	 * <em>distinct</em> person and not one per prototype.
	 * </p>
	 *
	 * @param people
	 *        The register a stored id is resolved through, <code>null</code> when there is none.
	 */
	public Match matcher(PeopleStore people) {
		Flat flat = flat();
		int count = flat._persons.length;
		if (count == 0) {
			return Match.NONE;
		}
		Map<String, Integer> byId = new LinkedHashMap<>();
		List<String> ids = new ArrayList<>();
		int[] person = new int[count];
		for (int n = 0; n < count; n++) {
			String resolved = FaceTags.person(flat._persons[n], people);
			Integer index = byId.get(resolved);
			if (index == null) {
				index = Integer.valueOf(ids.size());
				byId.put(resolved, index);
				ids.add(resolved);
			}
			person[n] = index.intValue();
		}
		return new Match(flat._embeddings, person, ids.toArray(new String[ids.size()]), threshold(), margin());
	}

	/** What a face is compared against, see {@link Recognition#matcher(PeopleStore)}. */
	public static final class Match {

		/** What a space nobody is confirmed in matches against: nothing, ever. */
		public static final Match NONE =
			new Match(new float[0][], new int[0], new String[0], DEFAULT_THRESHOLD, DEFAULT_MARGIN);

		private final float[][] _embeddings;

		private final int[] _person;

		private final String[] _ids;

		private final double _threshold;

		private final double _margin;

		Match(float[][] embeddings, int[] person, String[] ids, double threshold, double margin) {
			_embeddings = embeddings;
			_person = person;
			_ids = ids;
			_threshold = threshold;
			_margin = margin;
		}

		/** Whether there is anybody at all to be recognised. */
		public boolean isEmpty() {
			return _embeddings.length == 0;
		}

		/**
		 * Who the given face most likely is, the empty string when nobody is likely enough.
		 *
		 * <p>
		 * One dot product per prototype — the prototypes and the query are normalised, so the dot
		 * product <em>is</em> the cosine similarity — and one number kept per person. No allocation
		 * but the query and the per-person maxima, both proportional to what is already in hand.
		 * </p>
		 */
		public String personOf(float[] embedding) {
			if (_embeddings.length == 0 || embedding == null || embedding.length == 0) {
				return "";
			}
			float[] query = normalised(embedding);
			if (query.length == 0) {
				return "";
			}
			double[] best = new double[_ids.length];
			Arrays.fill(best, Double.NEGATIVE_INFINITY);
			for (int n = 0; n < _embeddings.length; n++) {
				float[] prototype = _embeddings[n];
				if (prototype.length != query.length) {
					// Another model's numbers; nothing here compares them.
					continue;
				}
				double dot = 0;
				for (int d = 0; d < prototype.length; d++) {
					dot += ((double) prototype[d]) * query[d];
				}
				int person = _person[n];
				if (dot > best[person]) {
					best[person] = dot;
				}
			}
			int winner = -1;
			double first = Double.NEGATIVE_INFINITY;
			double second = Double.NEGATIVE_INFINITY;
			for (int n = 0; n < best.length; n++) {
				if (best[n] > first) {
					second = first;
					first = best[n];
					winner = n;
				} else if (best[n] > second) {
					second = best[n];
				}
			}
			if (winner < 0 || first < _threshold) {
				return "";
			}
			if (second > Double.NEGATIVE_INFINITY && first - second < _margin) {
				// Two people the numbers cannot tell apart: no suggestion is better than a coin.
				return "";
			}
			return _ids[winner];
		}
	}

	/** How similar a face must be to be suggested, see {@link #DEFAULT_THRESHOLD}. */
	public static double threshold() {
		return number(THRESHOLD_PROPERTY, DEFAULT_THRESHOLD);
	}

	/** How far the best person must lead the second one, see {@link #DEFAULT_MARGIN}. */
	public static double margin() {
		return number(MARGIN_PROPERTY, DEFAULT_MARGIN);
	}

	private static double number(String property, double fallback) {
		String value = System.getProperty(property);
		if (value == null || value.trim().isEmpty()) {
			return fallback;
		}
		try {
			return Double.parseDouble(value.trim());
		} catch (NumberFormatException ex) {
			LOG.warning("Ignoring the unreadable value '" + value + "' of '" + property + "'.");
			return fallback;
		}
	}

	/** The given embedding scaled to length one, so that a comparison is one dot product. */
	static float[] normalised(float[] embedding) {
		if (embedding == null || embedding.length == 0) {
			return new float[0];
		}
		double norm = 0;
		for (float value : embedding) {
			norm += ((double) value) * value;
		}
		if (norm <= 0) {
			return new float[0];
		}
		double scale = 1 / Math.sqrt(norm);
		float[] result = new float[embedding.length];
		for (int n = 0; n < embedding.length; n++) {
			result[n] = (float) (embedding[n] * scale);
		}
		return result;
	}
}
