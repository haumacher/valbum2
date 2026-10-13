/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.upload;

import de.haumacher.imageServer.LibraryFiles;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.index.SpaceIndex;
import de.haumacher.imageServer.pipeline.FolderPipeline;
import de.haumacher.imageServer.shared.model.IndexProgress;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Where every content of one space is, by its hash — the space-wide answer to "do we have this
 * photo already?", see issue #118.
 *
 * <p>
 * The duplicate check of an upload used to look at the target folder alone: a photo that was
 * synced into the inbox and then moved into an album was unknown to the inbox again, so a
 * reinstalled app uploaded it a second time. This index closes that gap. It is <em>derived</em>
 * and never authoritative: the per-folder {@value HashCache#FILE_NAME} sidecars stay the stored
 * truth and this is a lookup table over them, thrown away and rebuilt whenever it disagrees with
 * a folder.
 * </p>
 *
 * <p>
 * <b>In the space's index, not in memory</b> (issue #264). The lookup lives in the H2 database of
 * the {@link SpaceIndex} of the space, in <code>.valbum/index/</code>: a table of folders with the
 * stamp of the sidecar each was read from, and a table of hashes per folder, found by hash. It used
 * to be one map in memory, loaded whole from <code>.valbum/{@value #LEGACY_FILE_NAME}</code> at
 * start-up (some 35 MB of heap for 100 000 photos); the "no database" of then was about a second
 * truth, which this still is not: the database is a rebuildable index, deleted and built again from
 * the sidecars whenever it is missing, unreadable or of another schema, and no part of a backup. A
 * <code>{@value #LEGACY_FILE_NAME}</code> of an earlier build is litter, deleted after the first
 * complete pass.
 * </p>
 *
 * <h2>The first build</h2>
 *
 * <p>
 * A library that was filled by other means has no sidecars at all, and its photos would stay
 * unknown until somebody looked at their folder — which the camera-roll sync never does, it only
 * ever looks at the inbox. So the first pass <em>hashes</em>: every folder holding an image that
 * the sidecar does not mention is refreshed through {@link HashCache#refresh()} and its sidecar
 * written, once, on a low-priority thread of the space's own, one folder at a time, never on the
 * request path. That is one full read of the library, paid once; a photo whose size and
 * modification stamp are unchanged is never hashed again.
 * </p>
 *
 * <p>
 * The pass is resumable: every folder is committed to the index as it is read, so a server that is
 * stopped halfway carries on where it left off. A folder whose sidecar carries the stamp the index
 * recorded is not even opened, which is what makes a restart cheap.
 * </p>
 *
 * <h2>Staying current</h2>
 *
 * <p>
 * Between passes the index is kept current by the writers of a sidecar themselves: every
 * {@link HashCache#flush()} that really wrote something tells the index about its folder (see
 * {@link #sidecarWritten(File)}), which covers the upload, the move, the delete, the replacement of
 * originals and the lazy fill of a check without any of them knowing this class — the server's own
 * edit is in the index in the same step as in the sidecar, and a request never waits for the
 * background to see it. What no sidecar write announces — a file deleted or a folder renamed behind
 * the index's back — is caught on the way out: {@link #pathOf(String)} answers only a file that is
 * really there, and the background drops the vanished file from the sidecar and the index when it
 * next looks at its folder. A request only ever reads the index.
 * </p>
 *
 * <h2>Photos copied in while the server runs</h2>
 *
 * <p>
 * The background pass is the first {@link FolderPipeline.Step step} of the space's
 * {@link FolderPipeline}, see issue #235: besides the walk at start-up, every folder the
 * {@link ResourceCache} notices &mdash; loaded by a request, reported changed by its watch, or new
 * in a watched folder &mdash; is brought into the index once it stood still for a moment, on the
 * same thread. A library copied into the space while the server runs is hashed without a restart.
 * Hashing a folder happens under the folder's lock in {@link HashCache}, so the face pass and an
 * upload never hash it a second time nor lose what this pass wrote.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class HashIndex {

	private static final Logger LOG = Logger.getLogger(HashIndex.class.getName());

	/**
	 * The name of the file below {@value UserStore#DIRECTORY_NAME} an earlier build kept this index
	 * in; deleted after the first complete pass, see issue #264.
	 */
	public static final String LEGACY_FILE_NAME = "hash-index.json";

	/** The length of a SHA-256 hash in bytes, as the index stores it. */
	private static final int HASH_BYTES = 32;

	/**
	 * The indexes of the spaces this process serves, so that a {@link HashCache} can tell the one
	 * it belongs to that it wrote, without every caller having to carry an index along.
	 */
	private static final List<HashIndex> INSTANCES = new CopyOnWriteArrayList<>();

	/**
	 * Tells the index of the space the given folder lies in that its sidecar was just written.
	 *
	 * <p>
	 * Called by {@link HashCache#flush()}, which is the one place a {@value HashCache#FILE_NAME}
	 * is ever written: the upload, the move and the lazy fill of a check all go through it, so
	 * there is no fourth writer to forget. A folder outside every served space is nobody's
	 * business and is ignored.
	 * </p>
	 */
	static void sidecarWritten(File folder) {
		for (HashIndex index : INSTANCES) {
			if (index.holds(folder)) {
				index.folderChanged(folder);
			}
		}
	}

	/** What the index recorded about one folder. */
	private static final class Folder {

		/** The row of the folder. */
		final long _id;

		/** The modification stamp of the {@value HashCache#FILE_NAME} this was read from. */
		final long _stamp;

		Folder(long id, long stamp) {
			_id = id;
			_stamp = stamp;
		}
	}

	private final Path _root;

	/** Where the lookup is kept, see issue #264. */
	private final SpaceIndex _store;

	/** Whether {@link #shutdown()} closes {@link #_store}: the index opened it itself. */
	private final boolean _ownsStore;

	private int _done;

	private int _total;

	private boolean _complete;

	/** The background work of the space; hashing is its first step, see issue #235. */
	private final FolderPipeline _pipeline;

	/** How often each folder had files hashed by this index, for the tests. */
	private final Map<String, Integer> _hashRuns = new ConcurrentHashMap<>();

	/** How many times a sidecar was read into the index, for the tests. */
	private final AtomicInteger _reads = new AtomicInteger();

	private CountDownLatch _pass;

	/**
	 * The folders recorded since the running pass took its walk of the tree, <code>null</code>
	 * while no pass runs: what an upload or a move put into the index meanwhile is not dropped for
	 * not having been walked, see {@link #indexNow()}.
	 */
	private Set<String> _recordedDuringPass;

	/**
	 * Opens the index of the given space in its own {@link SpaceIndex}; nothing is scanned and
	 * nothing is hashed until {@link #start()} or {@link #indexNow()}.
	 */
	public HashIndex(Path root) {
		this(root, SpaceIndex.open(root), true);
	}

	/**
	 * Creates the hash index of the given space in the given {@link SpaceIndex}, which the caller
	 * closes after {@link #shutdown()}.
	 */
	public HashIndex(Path root, SpaceIndex store) {
		this(root, store, false);
	}

	private HashIndex(Path root, SpaceIndex store, boolean ownsStore) {
		_root = root.toAbsolutePath().normalize();
		_store = store;
		_ownsStore = ownsStore;
		_pipeline = new FolderPipeline(_root, "hash-index " + _root.getFileName());
		_pipeline.addStep(new HashStep());
		INSTANCES.add(this);
	}

	/** The name of the hashing step in the {@link FolderPipeline}. */
	public static final String STEP = "hash";

	/**
	 * The background work of this space, see {@link FolderPipeline}; hashing is its first step,
	 * and further steps are added behind it.
	 */
	public FolderPipeline pipeline() {
		return _pipeline;
	}

	/** The index of the space this lookup is kept in, see issue #264. */
	public SpaceIndex store() {
		return _store;
	}

	/**
	 * How many times files of the given folder (relative to the space root) were hashed by this
	 * index; for the tests.
	 */
	public int hashRuns(String folder) {
		return _hashRuns.getOrDefault(folder, 0);
	}

	/**
	 * How many times a {@value HashCache#FILE_NAME} was read into this index since it was opened;
	 * for the tests: a restart over an index that is up to date reads none.
	 */
	public int sidecarReads() {
		return _reads.get();
	}

	/** Hashing as a {@link FolderPipeline.Step}. */
	private final class HashStep implements FolderPipeline.Step {

		@Override
		public String name() {
			return STEP;
		}

		@Override
		public boolean trustsRecord() {
			// Asking the sidecar is as cheap as asking the record, and it is the truth.
			return false;
		}

		@Override
		public FolderPipeline.Stage stage() {
			// The camera-roll sync waits for a complete index; it must not wait for previews.
			return FolderPipeline.Stage.INDEX;
		}

		@Override
		public FolderPipeline.Outcome run(File folder) throws IOException {
			if (images(folder).length == 0) {
				return FolderPipeline.Outcome.DONE;
			}
			index(folder);
			return HashCache.isInMemory(folder) ? FolderPipeline.Outcome.UNWRITABLE : FolderPipeline.Outcome.DONE;
		}
	}

	/** The root of the space this index answers for. */
	public Path getRoot() {
		return _root;
	}

	/** The file an earlier build kept this index in, see {@link #LEGACY_FILE_NAME}. */
	public File getLegacyFile() {
		return _root.resolve(UserStore.DIRECTORY_NAME).resolve(LEGACY_FILE_NAME).toFile();
	}

	/** Whether the given folder lies in the space this index answers for. */
	boolean holds(File folder) {
		return folder.toPath().toAbsolutePath().normalize().startsWith(_root);
	}

	/**
	 * Where the contents with the given hash are, relative to the space root.
	 *
	 * <p>
	 * A bare file name for a file at the root of the space, a path with <code>/</code> as
	 * separator below it. The file is checked to be there: an index entry whose file vanished is
	 * passed over and the answer looks further, in the order the folders became known, so a stale
	 * entry never becomes a lie. A pure read: the background forgets what vanished.
	 * </p>
	 *
	 * @return <code>null</code> if this space holds no such contents, as far as it is indexed.
	 */
	public String pathOf(String hash) {
		return search(hash, null);
	}

	/**
	 * Where the contents with the given hash are <em>outside</em> the given folder, see the sweep
	 * of issue #118.
	 *
	 * @param folder
	 *        The folder path relative to the space root that does not count as an answer.
	 * @return <code>null</code> if nothing else in the space holds those contents.
	 */
	public String elsewhere(String hash, String folder) {
		return search(hash, folder == null ? "" : folder);
	}

	/**
	 * Every content that lies in more than one folder of the space, with the paths holding it, see
	 * issue #220.
	 *
	 * <p>
	 * A path relative to the space root per folder holding the content (one per folder: two copies
	 * inside one folder are one entry of its sidecar's map), in the order the folders are known.
	 * A path whose file is gone is left out, and a content left in one folder by that is left out
	 * too. Nothing is forgotten here: this is a read.
	 * </p>
	 */
	public Map<String, List<String>> shared() {
		Map<String, List<String>> byHash = _store.read(connection -> {
			Map<String, List<String>> result = new LinkedHashMap<>();
			try (PreparedStatement statement = connection.prepareStatement(
				"SELECT h.hash, f.path, h.name FROM hashes h JOIN folders f ON f.id = h.folder "
					+ "WHERE h.hash IN (SELECT hash FROM hashes GROUP BY hash HAVING COUNT(*) > 1) "
					+ "ORDER BY h.hash, f.id")) {
				try (ResultSet rows = statement.executeQuery()) {
					while (rows.next()) {
						result.computeIfAbsent(hex(rows.getBytes(1)), x -> new ArrayList<>(2))
							.add(join(rows.getString(2), rows.getString(3)));
					}
				}
			}
			return result;
		}, Collections.emptyMap());
		Map<String, List<String>> result = new LinkedHashMap<>();
		for (Map.Entry<String, List<String>> entry : byHash.entrySet()) {
			List<String> present = new ArrayList<>(entry.getValue().size());
			for (String path : entry.getValue()) {
				if (Files.isRegularFile(_root.resolve(path))) {
					present.add(path);
				}
			}
			if (present.size() >= 2) {
				result.put(entry.getKey(), present);
			}
		}
		return result;
	}

	/** The path of the given folder relative to the space root; the empty string for the root. */
	public String relative(File folder) {
		Path path = folder.toPath().toAbsolutePath().normalize();
		if (!path.startsWith(_root)) {
			return null;
		}
		return _root.relativize(path).toString().replace(File.separatorChar, '/');
	}

	/** How far the index has got, see {@link IndexProgress}. */
	public synchronized IndexProgress progress() {
		// While the index does not even know how much there is to do, "total" is one more than
		// "done": an incomplete index must never read as a complete one.
		int total = _complete ? _done : Math.max(_total, _done + 1);
		return IndexProgress.create().setDone(_done).setTotal(total);
	}

	/** Whether the whole space is indexed. */
	public synchronized boolean isComplete() {
		return _complete;
	}

	/**
	 * Re-reads the sidecar of one folder, after somebody wrote it.
	 *
	 * <p>
	 * Nothing is hashed: the sidecar is the truth and this is a read of it.
	 * </p>
	 */
	public void folderChanged(File folder) {
		String path = relative(folder);
		if (path == null) {
			return;
		}
		synchronized (this) {
			read(folder, path);
		}
	}

	/**
	 * Re-reads the sidecars of the given folder and everything below it, after a move or a delete
	 * rearranged a whole tree.
	 */
	public void treeChanged(File folder) {
		String path = relative(folder);
		if (path == null) {
			return;
		}
		synchronized (this) {
			// The whole subtree is read anew, so what was known about it goes first: a folder that
			// was renamed away leaves no entry behind. One transaction: a request never sees the tree
			// gone before it is read again.
			List<Sidecar> sidecars = new ArrayList<>();
			for (File each : tree(folder)) {
				Sidecar sidecar = sidecar(each, relative(each));
				if (sidecar != null) {
					sidecars.add(sidecar);
				}
			}
			_store.write(connection -> {
				deleteTree(connection, path);
				for (Sidecar sidecar : sidecars) {
					sidecar.apply(connection);
				}
			});
		}
	}

	// --- The background pass. ---

	/**
	 * Starts the background indexing of this space, see {@link HashIndex}.
	 *
	 * <p>
	 * One thread per space at {@link Thread#MIN_PRIORITY}, one folder at a time: the index is
	 * worth having, and worth nothing at the price of a server that stops answering while it is
	 * built.
	 * </p>
	 */
	public synchronized void start() {
		if (!_pipeline.start()) {
			return;
		}
		_pass = new CountDownLatch(1);
		CountDownLatch pass = _pass;
		_pipeline.execute(() -> {
			try {
				walk(false);
			} finally {
				pass.countDown();
			}
		});
	}

	/**
	 * Waits for the background pass started by {@link #start()} to finish.
	 *
	 * @return Whether it finished within the given time.
	 */
	public boolean awaitPass(long timeoutMillis) throws InterruptedException {
		CountDownLatch pass;
		synchronized (this) {
			pass = _pass;
		}
		return pass == null || pass.await(timeoutMillis, TimeUnit.MILLISECONDS);
	}

	/**
	 * Indexes the whole space in the calling thread, hashing what no sidecar knows, and runs every
	 * further step of the {@link FolderPipeline} but the videos on every folder, newest album first.
	 *
	 * <p>
	 * What a test drives synchronously; {@link #start()} runs the same walk in the background and
	 * leaves everything behind hashing to the pipeline's queue, see {@link FolderPipeline#catchUp(List)}.
	 * </p>
	 */
	public void indexNow() {
		walk(true);
	}

	/**
	 * The walk over the whole space: hashing first, newest album first, then the other steps.
	 *
	 * @param inline
	 *        Whether the steps behind hashing run here, in the calling thread, rather than queued.
	 */
	private void walk(boolean inline) {
		List<File> folders;
		try {
			folders = tree(_root.toFile());
		} catch (RuntimeException ex) {
			LOG.log(Level.WARNING, "Cannot walk the space '" + _root + "': " + ex.getMessage(), ex);
			return;
		}

		// What every folder holds, before anything else, see issue #253: first a cheap look at every
		// folder, root first, so that the library is known as a whole at once; then the analysis of
		// what is new, newest album first. A request answers nothing but what this recorded.
		for (File folder : folders) {
			if (Thread.currentThread().isInterrupted()) {
				return;
			}
			_pipeline.discover(folder);
		}
		for (File folder : FolderPipeline.newestFirst(folders)) {
			if (Thread.currentThread().isInterrupted()) {
				return;
			}
			_pipeline.process(folder, FolderPipeline.Stage.RECORD);
		}

		List<File> withImages = new ArrayList<>();
		for (File folder : folders) {
			if (images(folder).length > 0) {
				withImages.add(folder);
			}
		}
		// The newest albums are the ones looked at first, see issue #236.
		withImages = FolderPipeline.newestFirst(withImages);
		_pipeline.know(withImages);
		Set<String> walked = new HashSet<>();
		for (File folder : folders) {
			walked.add(relative(folder));
		}
		synchronized (this) {
			_total = withImages.size();
			_done = 0;
			_complete = false;
			_recordedDuringPass = new HashSet<>();
		}

		for (File folder : withImages) {
			if (Thread.currentThread().isInterrupted()) {
				// The server is going down: what is done is committed, and the next start carries
				// on where this left off.
				synchronized (this) {
					// Nothing unvisited is dropped: an interrupted pass knows nothing about the
					// folders it did not reach, and the next one carries on with what is known.
					_recordedDuringPass = null;
				}
				LOG.info("Indexing of '" + _root + "' stopped after " + _done + " folder(s).");
				return;
			}
			// Hashing, and whatever else the index needs; one unreadable folder is not a reason to
			// leave the rest of the library unknown, see FolderPipeline#process(File).
			_pipeline.process(folder, FolderPipeline.Stage.INDEX);
			synchronized (this) {
				_done++;
			}
		}
		synchronized (this) {
			// After a complete pass the index holds exactly the folders the pass walked (and what was
			// recorded while it ran): a folder gone from the tree is gone from the index, and so is
			// one the walk does not go into any more — an entry an earlier build wrote for what is
			// no part of the library now, see LibraryFiles and issue #173.
			Set<String> recorded = _recordedDuringPass;
			_recordedDuringPass = null;
			List<Long> gone = new ArrayList<>();
			for (Map.Entry<String, Folder> known : folders().entrySet()) {
				String path = known.getKey();
				if ((!walked.contains(path) && !recorded.contains(path)) || !Files.isDirectory(_root.resolve(path))) {
					gone.add(Long.valueOf(known.getValue()._id));
				}
			}
			if (!gone.isEmpty()) {
				_store.write(connection -> deleteFolders(connection, gone));
			}
			_total = Math.max(_total, _done);
			_done = _total;
			_complete = true;
		}
		dropLegacyFile();
		_pipeline.retain(walked);
		_pipeline.flush();
		LOG.info("Indexed " + _total + " folder(s) of '" + _root + "'.");

		// Behind the index, everything an album needs before its first visit, see issue #236.
		if (inline) {
			for (File folder : withImages) {
				if (Thread.currentThread().isInterrupted()) {
					return;
				}
				_pipeline.process(folder);
			}
			_pipeline.flush();
		} else {
			_pipeline.catchUp(withImages);
		}
	}

	/**
	 * Deletes the file an earlier build kept the index in, once this index is built: it is litter,
	 * see {@link #LEGACY_FILE_NAME}.
	 */
	private void dropLegacyFile() {
		File legacy = getLegacyFile();
		if (legacy.isFile()) {
			if (legacy.delete()) {
				LOG.info("Deleted '" + legacy + "', which the index in '" + _store.getDirectory() + "' replaces.");
			} else {
				LOG.warning("Cannot delete '" + legacy + "', which the index in '" + _store.getDirectory()
					+ "' replaces; it may be deleted by hand.");
			}
		}
	}

	/** Brings one folder into the index, hashing what its sidecar does not know yet. */
	private void index(File folder) throws IOException {
		String path = relative(folder);
		if (path == null) {
			return;
		}
		File sidecar = new File(folder, HashCache.FILE_NAME);
		File[] images = images(folder);
		Folder known = folder(path);
		if (known != null && sidecar.isFile() && known._stamp == sidecar.lastModified()) {
			Set<String> names = names(known._id);
			if (knowsEvery(names, images) && present(folder, names)) {
				// Unchanged since it was read: not even opened.
				return;
			}
		}

		HashCache cache = new HashCache(folder);
		Map<String, String> hashByName = cache.storedHashByName();
		if (!knowsEvery(hashByName.keySet(), images) || !present(folder, hashByName.keySet())) {
			// The first build of the index is where a library that was filled by other means is
			// hashed, once; the sidecar is written, so a later pass finds it done. A file that went
			// behind the server's back is dropped from the sidecar in the same go, so that the index
			// forgets it, see #pathOf(String).
			cache.refresh();
			if (cache.hashed() > 0) {
				_hashRuns.merge(path, 1, Integer::sum);
			}
			cache.flush();
			hashByName = cache.storedHashByName();
		}
		synchronized (this) {
			_reads.incrementAndGet();
			record(path, sidecar.lastModified(), hashByName);
		}
	}

	/** Whether every image of the folder is one of the given recorded file names. */
	private static boolean knowsEvery(Collection<String> names, File[] images) {
		for (File image : images) {
			if (!names.contains(image.getName())) {
				return false;
			}
		}
		return true;
	}

	/** Whether every one of the given names of the folder is still a file there. */
	private static boolean present(File folder, Collection<String> names) {
		for (String name : names) {
			if (!LibraryFiles.isIgnored(name) && !new File(folder, name).isFile()) {
				return false;
			}
		}
		return true;
	}

	/** The image and video files of the given folder, never <code>null</code>. */
	private static File[] images(File folder) {
		File[] files = folder.listFiles(f -> f.isFile() && ResourceCache.isImage(f));
		return files == null ? new File[0] : files;
	}

	/** The given folder and every folder below it that is not the server's own. */
	private static List<File> tree(File root) {
		List<File> result = new ArrayList<>();
		if (!root.isDirectory()) {
			return result;
		}
		Deque<File> pending = new ArrayDeque<>();
		pending.add(root);
		while (!pending.isEmpty()) {
			File folder = pending.removeFirst();
			result.add(folder);
			File[] children = folder.listFiles(f -> f.isDirectory() && !LibraryFiles.isIgnored(f));
			if (children != null) {
				for (File child : children) {
					pending.add(child);
				}
			}
		}
		return result;
	}

	/**
	 * Stops the background pass; what is done is committed. Closes the {@link SpaceIndex} where
	 * this index opened it itself.
	 */
	public void shutdown() {
		_pipeline.shutdown();
		INSTANCES.remove(this);
		if (_ownsStore) {
			synchronized (this) {
				_store.close();
			}
		}
	}

	/**
	 * Nothing to do any more: every change is committed to the {@link SpaceIndex} as it is made.
	 * Kept for the callers of the file this index was once kept in.
	 */
	public void flush() {
		// Committed already.
	}

	// --- The tables. ---

	/** Reads the sidecar of one folder into the index, or forgets the folder if it has none. */
	private void read(File folder, String path) {
		Sidecar sidecar = sidecar(folder, path);
		if (sidecar != null) {
			_store.write(sidecar::apply);
		}
	}

	/**
	 * What the sidecar of the given folder says, to be applied to the index; <code>null</code> for a
	 * folder outside the space.
	 */
	private Sidecar sidecar(File folder, String path) {
		if (path == null) {
			return null;
		}
		File file = new File(folder, HashCache.FILE_NAME);
		if (!file.isFile() && !HashCache.isInMemory(folder)) {
			return new Sidecar(path, 0, null);
		}
		HashCache cache = new HashCache(folder);
		_reads.incrementAndGet();
		return sidecar(path, file.lastModified(), cache.storedHashByName());
	}

	/** Puts what a folder holds into the index, replacing what was known about it. */
	private void record(String path, long stamp, Map<String, String> hashByName) {
		_store.write(sidecar(path, stamp, hashByName)::apply);
	}

	/** What a folder holds, as the index records it; called under this index's lock. */
	private Sidecar sidecar(String path, long stamp, Map<String, String> hashByName) {
		if (!LibraryFiles.isLibraryPath(path)) {
			// No part of the library: never an answer, whatever wrote a sidecar there.
			return new Sidecar(path, 0, null);
		}
		if (_recordedDuringPass != null) {
			_recordedDuringPass.add(path);
		}
		Map<String, String> nameByHash = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : hashByName.entrySet()) {
			if (LibraryFiles.isIgnored(entry.getKey())) {
				// A sidecar an earlier build wrote may name a file that is no photograph now.
				continue;
			}
			if (bytes(entry.getValue()) == null) {
				// No SHA-256 at all (a sidecar written by hand, or broken): nothing to find it by.
				continue;
			}
			nameByHash.putIfAbsent(entry.getValue(), entry.getKey());
		}
		return new Sidecar(path, stamp, nameByHash);
	}

	/** What one folder's sidecar says, read and ready to be written into the index. */
	private static final class Sidecar {

		final String _path;

		final long _stamp;

		/**
		 * The name of one file per hash (well-formed, see {@link HashIndex#bytes(String)});
		 * <code>null</code> for a folder the index forgets.
		 */
		final Map<String, String> _nameByHash;

		Sidecar(String path, long stamp, Map<String, String> nameByHash) {
			_path = path;
			_stamp = stamp;
			_nameByHash = nameByHash;
		}

		/** Writes this into the index, replacing what was known about the folder. */
		void apply(Connection connection) throws SQLException {
			if (_nameByHash == null) {
				deletePath(connection, _path);
				return;
			}
			long id = folderId(connection, _path, _stamp);
			try (PreparedStatement delete = connection.prepareStatement("DELETE FROM hashes WHERE folder = ?")) {
				delete.setLong(1, id);
				delete.executeUpdate();
			}
			try (PreparedStatement insert =
				connection.prepareStatement("INSERT INTO hashes (folder, hash, name) VALUES (?, ?, ?)")) {
				for (Map.Entry<String, String> entry : _nameByHash.entrySet()) {
					insert.setLong(1, id);
					insert.setBytes(2, bytes(entry.getKey()));
					insert.setString(3, entry.getValue());
					insert.addBatch();
				}
				insert.executeBatch();
			}
		}
	}

	/**
	 * The row of the folder of the given path with the given stamp: the known one, keeping its place
	 * in the order folders became known, else a new one at the end.
	 */
	private static long folderId(Connection connection, String path, long stamp) throws SQLException {
		try (PreparedStatement select = connection.prepareStatement("SELECT id FROM folders WHERE path = ?")) {
			select.setString(1, path);
			try (ResultSet rows = select.executeQuery()) {
				if (rows.next()) {
					long id = rows.getLong(1);
					try (PreparedStatement update =
						connection.prepareStatement("UPDATE folders SET stamp = ? WHERE id = ?")) {
						update.setLong(1, stamp);
						update.setLong(2, id);
						update.executeUpdate();
					}
					return id;
				}
			}
		}
		try (PreparedStatement insert = connection.prepareStatement("INSERT INTO folders (path, stamp) VALUES (?, ?)",
			Statement.RETURN_GENERATED_KEYS)) {
			insert.setString(1, path);
			insert.setLong(2, stamp);
			insert.executeUpdate();
			try (ResultSet keys = insert.getGeneratedKeys()) {
				keys.next();
				return keys.getLong(1);
			}
		}
	}

	private static void deletePath(Connection connection, String path) throws SQLException {
		try (PreparedStatement delete = connection.prepareStatement("DELETE FROM folders WHERE path = ?")) {
			delete.setString(1, path);
			delete.executeUpdate();
		}
	}

	/** Forgets the folder of the given path and every folder below it. */
	private static void deleteTree(Connection connection, String path) throws SQLException {
		if (path.isEmpty()) {
			try (PreparedStatement delete = connection.prepareStatement("DELETE FROM folders")) {
				delete.executeUpdate();
			}
			return;
		}
		try (PreparedStatement delete =
			connection.prepareStatement("DELETE FROM folders WHERE path = ? OR path LIKE ? ESCAPE '\\'")) {
			delete.setString(1, path);
			delete.setString(2, likeEscaped(path) + "/%");
			delete.executeUpdate();
		}
	}

	private static void deleteFolders(Connection connection, List<Long> ids) throws SQLException {
		try (PreparedStatement delete = connection.prepareStatement("DELETE FROM folders WHERE id = ?")) {
			for (Long id : ids) {
				delete.setLong(1, id.longValue());
				delete.addBatch();
			}
			delete.executeBatch();
		}
	}

	/** The given text as the literal start of a <code>LIKE</code> pattern escaped with a backslash. */
	private static String likeEscaped(String text) {
		StringBuilder result = new StringBuilder(text.length() + 8);
		for (int n = 0; n < text.length(); n++) {
			char c = text.charAt(n);
			if (c == '\\' || c == '%' || c == '_') {
				result.append('\\');
			}
			result.append(c);
		}
		return result.toString();
	}

	/** What the index recorded about the folder of the given path, <code>null</code> for nothing. */
	private Folder folder(String path) {
		return _store.read(connection -> {
			try (PreparedStatement select = connection.prepareStatement("SELECT id, stamp FROM folders WHERE path = ?")) {
				select.setString(1, path);
				try (ResultSet rows = select.executeQuery()) {
					return rows.next() ? new Folder(rows.getLong(1), rows.getLong(2)) : null;
				}
			}
		}, null);
	}

	/** The names the index recorded in the folder of the given row. */
	private Set<String> names(long folder) {
		return _store.read(connection -> {
			Set<String> result = new HashSet<>();
			try (PreparedStatement select = connection.prepareStatement("SELECT name FROM hashes WHERE folder = ?")) {
				select.setLong(1, folder);
				try (ResultSet rows = select.executeQuery()) {
					while (rows.next()) {
						result.add(rows.getString(1));
					}
				}
			}
			return result;
		}, Collections.emptySet());
	}

	/** Every folder the index knows, by path, in the order they became known. */
	private Map<String, Folder> folders() {
		return _store.read(connection -> {
			Map<String, Folder> result = new LinkedHashMap<>();
			try (PreparedStatement select = connection.prepareStatement("SELECT path, id, stamp FROM folders ORDER BY id")) {
				try (ResultSet rows = select.executeQuery()) {
					while (rows.next()) {
						result.put(rows.getString(1), new Folder(rows.getLong(2), rows.getLong(3)));
					}
				}
			}
			return result;
		}, Collections.emptyMap());
	}

	/**
	 * The first file holding the given hash that is really there, in the order the folders became
	 * known, skipping the folder named.
	 */
	private String search(String hash, String skip) {
		for (String[] candidate : candidates(hash)) {
			if (skip != null && candidate[0].equals(skip)) {
				continue;
			}
			String path = join(candidate[0], candidate[1]);
			if (Files.isRegularFile(_root.resolve(path))) {
				return path;
			}
		}
		return null;
	}

	/**
	 * Every path the index records for the given hash, whether its file is there or not, in the
	 * order the folders became known; for the tests, which ask what the index holds rather than
	 * what it answers.
	 */
	public List<String> recordedPaths(String hash) {
		List<String> result = new ArrayList<>();
		for (String[] candidate : candidates(hash)) {
			result.add(join(candidate[0], candidate[1]));
		}
		return result;
	}

	/** The folder and the file name of every row of the given hash, in the order the folders became known. */
	private List<String[]> candidates(String hash) {
		byte[] bytes = bytes(hash);
		if (bytes == null) {
			return Collections.emptyList();
		}
		return _store.read(connection -> {
			List<String[]> result = new ArrayList<>(2);
			try (PreparedStatement select = connection.prepareStatement(
				"SELECT f.path, h.name FROM hashes h JOIN folders f ON f.id = h.folder WHERE h.hash = ? ORDER BY f.id")) {
				select.setBytes(1, bytes);
				try (ResultSet rows = select.executeQuery()) {
					while (rows.next()) {
						result.add(new String[] { rows.getString(1), rows.getString(2) });
					}
				}
			}
			return result;
		}, Collections.emptyList());
	}

	private static String join(String folder, String name) {
		return folder.isEmpty() ? name : folder + "/" + name;
	}

	/** How many contents this index knows, for the tests and the log. */
	public int size() {
		Integer result = _store.read(connection -> {
			try (PreparedStatement select = connection.prepareStatement("SELECT COUNT(DISTINCT hash) FROM hashes");
					ResultSet rows = select.executeQuery()) {
				rows.next();
				return Integer.valueOf(rows.getInt(1));
			}
		}, Integer.valueOf(0));
		return result.intValue();
	}

	/** How many folders this index knows, for the tests. */
	public int folderCount() {
		Integer result = _store.read(connection -> {
			try (PreparedStatement select = connection.prepareStatement("SELECT COUNT(*) FROM folders");
					ResultSet rows = select.executeQuery()) {
				rows.next();
				return Integer.valueOf(rows.getInt(1));
			}
		}, Integer.valueOf(0));
		return result.intValue();
	}

	// --- Hashes as bytes. ---

	/**
	 * The 32 bytes of the given SHA-256 in lower-case hex, as {@link HashCache#sha256(File)} spells
	 * it; <code>null</code> for anything else, which no file is found by.
	 */
	static byte[] bytes(String hash) {
		if (hash == null || hash.length() != 2 * HASH_BYTES) {
			return null;
		}
		byte[] result = new byte[HASH_BYTES];
		for (int n = 0; n < HASH_BYTES; n++) {
			int high = digit(hash.charAt(2 * n));
			int low = digit(hash.charAt(2 * n + 1));
			if (high < 0 || low < 0) {
				return null;
			}
			result[n] = (byte) ((high << 4) | low);
		}
		return result;
	}

	private static int digit(char c) {
		if (c >= '0' && c <= '9') {
			return c - '0';
		}
		if (c >= 'a' && c <= 'f') {
			return c - 'a' + 10;
		}
		return -1;
	}

	/** The given hash bytes in lower-case hex. */
	static String hex(byte[] bytes) {
		char[] result = new char[bytes.length * 2];
		for (int n = 0; n < bytes.length; n++) {
			result[2 * n] = Character.forDigit((bytes[n] >> 4) & 0xF, 16);
			result[2 * n + 1] = Character.forDigit(bytes[n] & 0xF, 16);
		}
		return new String(result);
	}
}
