/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.upload;

import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.shared.model.IndexProgress;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
 * <b>No database.</b> A hash is 32 bytes and a path perhaps 60, so even a library of 200 000
 * photos is some 20 MB in memory and a file of that order on disk — one {@link Map}, one exact
 * lookup, nothing to query. What is expensive here is never the index but hashing photos, which
 * is why the index holds what the sidecars already know and hashes only what nobody hashed yet.
 * </p>
 *
 * <h2>The file</h2>
 *
 * <p>
 * <code>&lt;space&gt;/{@value UserStore#DIRECTORY_NAME}/{@value #FILE_NAME}</code>:
 * </p>
 *
 * <pre>
 * {"version":1,"folders":{
 *   "":         {"stamp":1757000000000,"hashes":{"&lt;64 hex&gt;":"IMG_1.jpg"}},
 *   "2020/Trip":{"stamp":1757000001000,"hashes":{"&lt;64 hex&gt;":"IMG_2.jpg"}}}}
 * </pre>
 *
 * <p>
 * Grouped by folder rather than written as one flat hash&nbsp;&rarr;&nbsp;path map, for two
 * reasons: the folder path is written once instead of once per photo (which is most of the file),
 * and every folder carries the modification stamp of the {@value HashCache#FILE_NAME} it was read
 * from, which is what makes a start-up cheap — a folder whose sidecar has not changed is not even
 * opened. The stamp is the only invalidation rule; a stamp that does not match is simply re-read.
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
 * The pass is resumable: what is done is in the file, so a server that is stopped halfway carries
 * on where it left off. The file is not rewritten after literally every folder — 20 MB times a few
 * thousand folders would be gigabytes of writes for no gain — but at most every
 * {@value #WRITE_INTERVAL_MILLIS} ms, and always at the end of the pass, after a change a request
 * caused, and at {@link #shutdown()}.
 * </p>
 *
 * <h2>Staying current</h2>
 *
 * <p>
 * Between passes the index is kept current by the writers of a sidecar themselves: every
 * {@link HashCache#flush()} that really wrote something tells the index about its folder (see
 * {@link #sidecarWritten(File)}), which covers the upload, the move and the lazy fill of a check
 * without any of them knowing this class. What no sidecar write announces — a file deleted or a
 * folder renamed behind the index's back — is caught on the way out: {@link #pathOf(String)}
 * checks that the file it found is really there and forgets it if it is not, so the index never
 * points at a photo that is gone.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class HashIndex {

	private static final Logger LOG = Logger.getLogger(HashIndex.class.getName());

	/** The name of the index file below {@value UserStore#DIRECTORY_NAME}. */
	public static final String FILE_NAME = "hash-index.json";

	/** The version this build writes. */
	public static final int VERSION = 1;

	/** How long the background pass may go on before the file is written again. */
	static final long WRITE_INTERVAL_MILLIS = 2000;

	private static final String VERSION__PROP = "version";

	private static final String FOLDERS__PROP = "folders";

	private static final String STAMP__PROP = "stamp";

	private static final String HASHES__PROP = "hashes";

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

	/** What is known about one folder. */
	private static final class Folder {

		/** The modification stamp of the {@value HashCache#FILE_NAME} this was read from. */
		final long _stamp;

		/** The hashes of this folder's files, by file name. */
		final Map<String, String> _nameByHash;

		Folder(long stamp, Map<String, String> nameByHash) {
			_stamp = stamp;
			_nameByHash = nameByHash;
		}
	}

	private final Path _root;

	private final Path _file;

	/** What is known, by folder path relative to the space root; the root itself is the empty string. */
	private final Map<String, Folder> _folders = new LinkedHashMap<>();

	/** Hash to the path of a file holding those contents, derived from {@link #_folders}. */
	private Map<String, String> _byHash = new HashMap<>();

	private boolean _lookupStale;

	private boolean _dirty;

	private long _written;

	private int _done;

	private int _total;

	private boolean _complete;

	private ExecutorService _indexer;

	private CountDownLatch _pass;

	/**
	 * Loads the index of the given space; nothing is scanned and nothing is hashed until
	 * {@link #start()} or {@link #indexNow()}.
	 */
	public HashIndex(Path root) {
		_root = root.toAbsolutePath().normalize();
		_file = _root.resolve(UserStore.DIRECTORY_NAME).resolve(FILE_NAME);
		load();
		INSTANCES.add(this);
	}

	/** The root of the space this index answers for. */
	public Path getRoot() {
		return _root;
	}

	/** The file this index is persisted in. */
	public File getFile() {
		return _file.toFile();
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
	 * forgotten and the answer looks further, so a stale entry never becomes a lie.
	 * </p>
	 *
	 * @return <code>null</code> if this space holds no such contents, as far as it is indexed.
	 */
	public synchronized String pathOf(String hash) {
		if (hash == null || hash.isEmpty()) {
			return null;
		}
		if (_lookupStale) {
			rebuildLookup();
		}
		for (;;) {
			String path = _byHash.get(hash);
			if (path == null) {
				return null;
			}
			if (Files.isRegularFile(_root.resolve(path))) {
				return path;
			}
			// The file went behind the index's back (a delete, a rename). Forget it here and in
			// the folder it was recorded in, and look whether another folder holds the contents.
			forget(hash, path);
			_byHash.remove(hash);
			String other = search(hash, null);
			if (other == null) {
				return null;
			}
			_byHash.put(hash, other);
		}
	}

	/**
	 * Where the contents with the given hash are <em>outside</em> the given folder, see the sweep
	 * of issue #118.
	 *
	 * @param folder
	 *        The folder path relative to the space root that does not count as an answer.
	 * @return <code>null</code> if nothing else in the space holds those contents.
	 */
	public synchronized String elsewhere(String hash, String folder) {
		if (hash == null || hash.isEmpty()) {
			return null;
		}
		return search(hash, folder == null ? "" : folder);
	}

	/** Whether the given folder path is the given one or lies below it. */
	private static boolean below(String folder, String ancestor) {
		return ancestor.isEmpty() || folder.equals(ancestor) || folder.startsWith(ancestor + "/");
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
			persist(false);
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
			// was renamed away leaves no entry behind.
			_folders.keySet().removeIf(known -> below(known, path));
			_lookupStale = true;
			_dirty = true;
			for (File each : tree(folder)) {
				read(each, relative(each));
			}
			persist(true);
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
		if (_indexer != null) {
			return;
		}
		_indexer = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "hash-index " + _root.getFileName());
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY);
			return thread;
		});
		_pass = new CountDownLatch(1);
		CountDownLatch pass = _pass;
		_indexer.execute(() -> {
			try {
				indexNow();
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
	 * Indexes the whole space in the calling thread, hashing what no sidecar knows.
	 *
	 * <p>
	 * What {@link #start()} runs in the background, and what a test drives synchronously.
	 * </p>
	 */
	public void indexNow() {
		List<File> folders;
		try {
			folders = tree(_root.toFile());
		} catch (RuntimeException ex) {
			LOG.log(Level.WARNING, "Cannot walk the space '" + _root + "': " + ex.getMessage(), ex);
			return;
		}

		List<File> withImages = new ArrayList<>();
		for (File folder : folders) {
			if (images(folder).length > 0) {
				withImages.add(folder);
			}
		}
		synchronized (this) {
			_total = withImages.size();
			_done = 0;
			_complete = false;
		}

		for (File folder : withImages) {
			if (Thread.currentThread().isInterrupted()) {
				// The server is going down: what is done is written, and the next start carries
				// on where this left off.
				synchronized (this) {
					persist(true);
				}
				LOG.info("Indexing of '" + _root + "' stopped after " + _done + " folder(s).");
				return;
			}
			try {
				index(folder);
			} catch (IOException | RuntimeException ex) {
				// One unreadable folder is not a reason to leave the rest of the library unknown.
				LOG.log(Level.WARNING,
					"Cannot index '" + folder.getAbsolutePath() + "': " + ex.getMessage(), ex);
			}
			synchronized (this) {
				_done++;
				persist(false);
			}
		}
		synchronized (this) {
			// A folder that is gone from the tree is gone from the index; what its files were is
			// answered by nothing any more.
			_folders.keySet().removeIf(path -> {
				boolean gone = !Files.isDirectory(_root.resolve(path));
				if (gone) {
					_lookupStale = true;
					_dirty = true;
				}
				return gone;
			});
			_total = Math.max(_total, _done);
			_done = _total;
			_complete = true;
			persist(true);
		}
		LOG.info("Indexed " + _total + " folder(s) of '" + _root + "'.");
	}

	/** Brings one folder into the index, hashing what its sidecar does not know yet. */
	private void index(File folder) throws IOException {
		String path = relative(folder);
		if (path == null) {
			return;
		}
		File sidecar = new File(folder, HashCache.FILE_NAME);
		synchronized (this) {
			Folder known = _folders.get(path);
			if (known != null && sidecar.isFile() && known._stamp == sidecar.lastModified()
				&& knowsEvery(new HashSet<>(known._nameByHash.values()), images(folder))) {
				// Unchanged since it was read: not even opened.
				return;
			}
		}

		HashCache cache = new HashCache(folder);
		Map<String, String> hashByName = cache.storedHashByName();
		if (!knowsEvery(hashByName.keySet(), images(folder))) {
			// The first build of the index is where a library that was filled by other means is
			// hashed, once; the sidecar is written, so a later pass finds it done.
			cache.refresh();
			cache.flush();
			hashByName = cache.storedHashByName();
		}
		synchronized (this) {
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
			File[] children = folder.listFiles(f -> f.isDirectory() && !f.getName().startsWith("."));
			if (children != null) {
				for (File child : children) {
					pending.add(child);
				}
			}
		}
		return result;
	}

	/** Stops the background pass; what is done is written. */
	public void shutdown() {
		ExecutorService indexer;
		synchronized (this) {
			indexer = _indexer;
			_indexer = null;
		}
		if (indexer != null) {
			indexer.shutdownNow();
			try {
				indexer.awaitTermination(2, TimeUnit.SECONDS);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
		synchronized (this) {
			persist(true);
		}
		INSTANCES.remove(this);
	}

	// --- The map. ---

	/** Reads the sidecar of one folder into the index, or forgets the folder if it has none. */
	private void read(File folder, String path) {
		if (path == null) {
			return;
		}
		File sidecar = new File(folder, HashCache.FILE_NAME);
		if (!sidecar.isFile()) {
			if (_folders.remove(path) != null) {
				_lookupStale = true;
				_dirty = true;
			}
			return;
		}
		HashCache cache = new HashCache(folder);
		record(path, sidecar.lastModified(), cache.storedHashByName());
	}

	/** Puts what a folder holds into the index, replacing what was known about it. */
	private void record(String path, long stamp, Map<String, String> hashByName) {
		Map<String, String> nameByHash = new HashMap<>();
		for (Map.Entry<String, String> entry : hashByName.entrySet()) {
			nameByHash.putIfAbsent(entry.getValue(), entry.getKey());
		}
		Folder previous = _folders.put(path, new Folder(stamp, nameByHash));
		_dirty = true;
		if (previous != null) {
			// What the folder held before may have been the answer for a hash it no longer holds.
			_lookupStale = true;
		} else if (!_lookupStale) {
			for (Map.Entry<String, String> entry : nameByHash.entrySet()) {
				_byHash.putIfAbsent(entry.getKey(), join(path, entry.getValue()));
			}
		}
	}

	/** Forgets one hash of the folder the given path lies in. */
	private void forget(String hash, String path) {
		int slash = path.lastIndexOf('/');
		String folder = slash < 0 ? "" : path.substring(0, slash);
		Folder known = _folders.get(folder);
		if (known != null && known._nameByHash.remove(hash) != null) {
			_dirty = true;
		}
	}

	/** Looks through every folder for the given hash, skipping the one named. */
	private String search(String hash, String skip) {
		for (Map.Entry<String, Folder> entry : _folders.entrySet()) {
			if (skip != null && entry.getKey().equals(skip)) {
				continue;
			}
			String name = entry.getValue()._nameByHash.get(hash);
			if (name == null) {
				continue;
			}
			String path = join(entry.getKey(), name);
			if (Files.isRegularFile(_root.resolve(path))) {
				return path;
			}
		}
		return null;
	}

	private void rebuildLookup() {
		Map<String, String> lookup = new HashMap<>();
		for (Map.Entry<String, Folder> entry : _folders.entrySet()) {
			for (Map.Entry<String, String> hash : entry.getValue()._nameByHash.entrySet()) {
				lookup.putIfAbsent(hash.getKey(), join(entry.getKey(), hash.getValue()));
			}
		}
		_byHash = lookup;
		_lookupStale = false;
	}

	private static String join(String folder, String name) {
		return folder.isEmpty() ? name : folder + "/" + name;
	}

	/** How many contents this index knows, for the tests and the log. */
	public synchronized int size() {
		if (_lookupStale) {
			rebuildLookup();
		}
		return _byHash.size();
	}

	// --- The file. ---

	private void load() {
		if (!Files.isRegularFile(_file)) {
			return;
		}
		try (Reader reader = new InputStreamReader(Files.newInputStream(_file), StandardCharsets.UTF_8)) {
			readIndex(new JsonReader(new ReaderAdapter(reader)));
		} catch (IOException | RuntimeException ex) {
			// A derived file can always be rebuilt; a broken one must never stop the server.
			LOG.log(Level.WARNING,
				"Rebuilding the unreadable hash index '" + _file + "': " + ex.getMessage());
			_folders.clear();
		}
		_lookupStale = true;
	}

	private void readIndex(JsonReader in) throws IOException {
		int version = 0;
		in.beginObject();
		while (in.hasNext()) {
			String key = in.nextName();
			switch (key) {
				case VERSION__PROP:
					version = in.nextInt();
					break;
				case FOLDERS__PROP:
					in.beginObject();
					while (in.hasNext()) {
						String path = in.nextName();
						readFolder(in, path);
					}
					in.endObject();
					break;
				default:
					in.skipValue();
					break;
			}
		}
		in.endObject();
		if (version > VERSION) {
			LOG.warning("The hash index was written by a newer version (" + version + " > " + VERSION
				+ "); every folder whose sidecar changed is read again anyway.");
		}
	}

	private void readFolder(JsonReader in, String path) throws IOException {
		long stamp = 0;
		Map<String, String> nameByHash = new HashMap<>();
		in.beginObject();
		while (in.hasNext()) {
			String key = in.nextName();
			switch (key) {
				case STAMP__PROP:
					stamp = in.nextLong();
					break;
				case HASHES__PROP:
					in.beginObject();
					while (in.hasNext()) {
						String hash = in.nextName();
						nameByHash.put(hash, in.nextString());
					}
					in.endObject();
					break;
				default:
					in.skipValue();
					break;
			}
		}
		in.endObject();
		_folders.put(path, new Folder(stamp, nameByHash));
	}

	/**
	 * Writes the index, unless it was written a moment ago and nothing insists.
	 *
	 * @param now
	 *        Whether to write whatever the clock says, see {@link #WRITE_INTERVAL_MILLIS}.
	 */
	private void persist(boolean now) {
		if (!_dirty) {
			return;
		}
		long when = System.currentTimeMillis();
		if (!now && when - _written < WRITE_INTERVAL_MILLIS) {
			return;
		}
		try {
			store();
			_dirty = false;
			_written = when;
		} catch (IOException ex) {
			LOG.log(Level.WARNING, "Cannot write the hash index '" + _file + "': " + ex.getMessage(), ex);
		}
	}

	/** Writes the index now, whatever the clock says. */
	public synchronized void flush() {
		persist(true);
	}

	private void store() throws IOException {
		Path folder = _file.getParent();
		Files.createDirectories(folder);
		Path tmpFile = Files.createTempFile(folder, "hash-index", ".json");
		try (Writer writer = new OutputStreamWriter(Files.newOutputStream(tmpFile), StandardCharsets.UTF_8)) {
			try (JsonWriter out = new JsonWriter(new WriterAdapter(writer))) {
				out.beginObject();
				out.name(VERSION__PROP);
				out.value(VERSION);
				out.name(FOLDERS__PROP);
				out.beginObject();
				for (Map.Entry<String, Folder> entry : _folders.entrySet()) {
					out.name(entry.getKey());
					out.beginObject();
					out.name(STAMP__PROP);
					out.value(entry.getValue()._stamp);
					out.name(HASHES__PROP);
					out.beginObject();
					for (Map.Entry<String, String> hash : entry.getValue()._nameByHash.entrySet()) {
						out.name(hash.getKey());
						out.value(hash.getValue());
					}
					out.endObject();
					out.endObject();
				}
				out.endObject();
				out.endObject();
			}
		}
		try {
			Files.move(tmpFile, _file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException ex) {
			Files.move(tmpFile, _file, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
