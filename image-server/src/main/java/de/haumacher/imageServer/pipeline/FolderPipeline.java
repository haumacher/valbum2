/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.pipeline;

import de.haumacher.imageServer.LibraryFiles;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.cache.ResourceCache;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The background work of one space, folder by folder, see issue #235.
 *
 * <p>
 * A folder is brought up to date by an ordered list of {@link Step}s, each idempotent and cheap
 * when there is nothing to do. Hashing (see {@link de.haumacher.imageServer.upload.HashIndex}) is
 * the first; issue #236 adds previews, faces and places behind it. A step is only ever run here:
 * on one thread of the space at {@link Thread#MIN_PRIORITY}, one folder at a time, never on the
 * request path.
 * </p>
 *
 * <h2>When a folder is worked on</h2>
 *
 * <ul>
 * <li>By a walk over the whole space, see {@link #process(File)}, which the owner of the pipeline
 * runs once at start-up.</li>
 * <li>When the {@link ResourceCache} notices it: a folder that is loaded, a folder whose watch
 * reports a new or changed photograph, a new folder appearing in a watched one (with everything
 * below it), see {@link #notice(File, boolean)}. No restart, no admin action: a library copied in
 * while the server runs is taken care of by itself.</li>
 * </ul>
 *
 * <p>
 * <b>Debounced.</b> A folder that is still being copied into must not be worked on for every file
 * that lands in it. A noticed folder waits for a quiet period ({@link #QUIET_MILLIS}); then the
 * {@link #fingerprint(List) fingerprint} of what it holds (names, sizes, modification stamps) is
 * taken, and the folder waits another quiet period; it is worked on only when the fingerprint did
 * not change in between, and waits again otherwise. Watching the files themselves would not do:
 * inside a new folder nobody watches anything yet, and <code>cp -p</code> sets old modification
 * stamps.
 * </p>
 *
 * <h2>What is remembered</h2>
 *
 * <p>
 * <code>&lt;space&gt;/.valbum/{@value #FILE_NAME}</code> records per folder which steps are done
 * for which fingerprint, so that a restart resumes:
 * </p>
 *
 * <pre>
 * {"version":1,"folders":{"2020/Trip":{"fingerprint":"&lt;hex&gt;","done":["hash"]}}}
 * </pre>
 *
 * <p>
 * A step that {@link Step#trustsRecord() trusts} that record is skipped for a folder whose
 * fingerprint is unchanged; hashing does not, its own check against the sidecar is as cheap and is
 * the truth. A step that failed, or could not store its result because the folder is not writable
 * ({@link ReadOnlyFolders}), is not run again for that folder in this process until its fingerprint
 * changes: retried later, never in a loop.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class FolderPipeline {

	private static final Logger LOG = Logger.getLogger(FolderPipeline.class.getName());

	/** The name of the progress file below the space's own folder. */
	public static final String FILE_NAME = "pipeline.json";

	/** The version of {@value #FILE_NAME} this build writes. */
	public static final int VERSION = 1;

	/** How long a noticed folder must stay unchanged before it is worked on. */
	public static final long QUIET_MILLIS = 3000;

	/** How long the pass may go on before the progress file is written again. */
	static final long WRITE_INTERVAL_MILLIS = 2000;

	private static final String VERSION__PROP = "version";

	private static final String FOLDERS__PROP = "folders";

	private static final String FINGERPRINT__PROP = "fingerprint";

	private static final String DONE__PROP = "done";

	/** What a {@link Step} did with a folder. */
	public enum Outcome {
		/** The folder is up to date for the step. */
		DONE,

		/**
		 * The step did its work but could not store it: the folder is not writable. Later steps
		 * still run; this one is not run again in this process until the folder changes.
		 */
		UNWRITABLE,

		/**
		 * The step could not do its work. Later steps, which build on it, do not run; this one is
		 * not run again in this process until the folder changes.
		 */
		FAILED,

		/**
		 * The step waits for something outside the folder that is on its way (the place names of a
		 * country being downloaded, see issue #234). It is not recorded as done and later steps
		 * still run; the step itself {@link FolderPipeline#notice(File, boolean) notices} the folder
		 * again when what it waits for arrives, so nothing polls.
		 */
		WAITING
	}

	/**
	 * One kind of work done to every folder, see {@link FolderPipeline}.
	 *
	 * <p>
	 * The extension point of issue #236: previews, faces and places are steps behind hashing.
	 * </p>
	 */
	public interface Step {

		/** The name the step is recorded under in {@value FolderPipeline#FILE_NAME}; never changes. */
		String name();

		/**
		 * Brings the given folder up to date; does nothing (cheaply) where it is.
		 *
		 * <p>
		 * Called on the pipeline's thread only. An exception counts as {@link Outcome#FAILED}.
		 * </p>
		 */
		Outcome run(File folder) throws IOException;

		/**
		 * Whether the step may be skipped for a folder whose fingerprint is what it was when the
		 * step last finished there. <code>false</code> for a step whose own check is cheap.
		 */
		default boolean trustsRecord() {
			return true;
		}
	}

	/** What is recorded about one folder. */
	static final class Record {

		final String _fingerprint;

		final Set<String> _done;

		Record(String fingerprint, Set<String> done) {
			_fingerprint = fingerprint;
			_done = done;
		}
	}

	/** A folder waiting for its quiet period. */
	private static final class Pending {

		final File _folder;

		boolean _tree;

		long _due;

		/** The fingerprint taken when the quiet period last ended, <code>null</code> before. */
		String _fingerprint;

		Pending(File folder, boolean tree, long due) {
			_folder = folder;
			_tree = tree;
			_due = due;
		}
	}

	private final Path _root;

	private final Path _file;

	private final String _name;

	private final List<Step> _steps = new CopyOnWriteArrayList<>();

	/** Guarded by this. */
	private final Map<String, Record> _records = new LinkedHashMap<>();

	/**
	 * Folders and steps not to run again while the fingerprint is the given one, by step + "\n" +
	 * path, as the fingerprint, a space and the {@link Outcome} that put it here.
	 */
	private final Map<String, String> _skipped = new ConcurrentHashMap<>();

	/** Guarded by this. */
	private final Map<String, Pending> _pending = new LinkedHashMap<>();

	private boolean _tickScheduled;

	private boolean _dirty;

	private long _written;

	private long _quietMillis = QUIET_MILLIS;

	private ScheduledExecutorService _executor;

	private final ResourceCache.FolderObserver _observer = this::notice;

	/**
	 * Creates the pipeline of the given space and reads its progress file; nothing runs until
	 * {@link #start()}.
	 *
	 * @param name
	 *        The name of the thread, for the thread dump.
	 */
	public FolderPipeline(Path root, String name) {
		_root = root.toAbsolutePath().normalize();
		_file = _root.resolve(UserStore.DIRECTORY_NAME).resolve(FILE_NAME);
		_name = name;
		load();
	}

	/** Adds a step behind the ones there are. */
	public void addStep(Step step) {
		_steps.add(step);
	}

	/** Takes a step out again. */
	public void removeStep(Step step) {
		_steps.remove(step);
	}

	/** The steps, in the order they run. */
	public List<Step> steps() {
		return new ArrayList<>(_steps);
	}

	/** Sets the quiet period, for the tests. */
	public void setQuietMillis(long millis) {
		_quietMillis = millis;
	}

	/** The file the progress is recorded in. */
	public File getFile() {
		return _file.toFile();
	}

	// --- The thread. ---

	/**
	 * Starts the pipeline's thread and listens to what the {@link ResourceCache}s notice.
	 *
	 * @return Whether it was started by this call.
	 */
	public synchronized boolean start() {
		if (_executor != null) {
			return false;
		}
		_executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, _name);
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY);
			return thread;
		});
		ResourceCache.addObserver(_observer);
		return true;
	}

	/** Whether {@link #start()} was called and {@link #shutdown()} was not. */
	public synchronized boolean isStarted() {
		return _executor != null;
	}

	/** Runs the given work on the pipeline's thread, behind what is queued. */
	public synchronized void execute(Runnable work) {
		if (_executor == null) {
			throw new IllegalStateException("Not started.");
		}
		_executor.execute(work);
	}

	/** Stops the thread; what is done is written. */
	public void shutdown() {
		ResourceCache.removeObserver(_observer);
		ScheduledExecutorService executor;
		synchronized (this) {
			executor = _executor;
			_executor = null;
			_pending.clear();
		}
		if (executor != null) {
			executor.shutdownNow();
			try {
				executor.awaitTermination(2, TimeUnit.SECONDS);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
		flush();
	}

	/**
	 * Waits until nothing is waiting for its quiet period and nothing is being worked on; for the
	 * tests.
	 */
	public boolean awaitIdle(long timeoutMillis) throws InterruptedException {
		long end = System.currentTimeMillis() + timeoutMillis;
		while (System.currentTimeMillis() < end) {
			ScheduledExecutorService executor;
			boolean pending;
			synchronized (this) {
				executor = _executor;
				pending = !_pending.isEmpty();
			}
			if (executor == null) {
				return true;
			}
			if (!pending) {
				try {
					executor.submit(() -> {
						// Being run at all is the answer.
					}).get(Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
				} catch (ExecutionException | RejectedExecutionException ex) {
					return true;
				} catch (TimeoutException ex) {
					return false;
				}
				synchronized (this) {
					if (_pending.isEmpty()) {
						return true;
					}
				}
			}
			Thread.sleep(20);
		}
		return false;
	}

	// --- Noticing. ---

	/**
	 * Takes note of a folder that was loaded or changed; it is worked on after its quiet period.
	 *
	 * <p>
	 * Cheap, and safe on a request thread: nothing is read here. A folder outside the space, the
	 * server's own and what is no part of the library are ignored, and so is everything before
	 * {@link #start()}.
	 * </p>
	 *
	 * @param tree
	 *        Whether everything below the folder is to be worked on, too.
	 */
	public void notice(File folder, boolean tree) {
		String path = relative(folder);
		if (path == null || !LibraryFiles.isLibraryPath(path)) {
			return;
		}
		synchronized (this) {
			if (_executor == null) {
				return;
			}
			long due = System.currentTimeMillis() + _quietMillis;
			Pending pending = _pending.get(path);
			if (pending == null) {
				_pending.put(path, new Pending(folder, tree, due));
			} else {
				pending._due = due;
				pending._tree |= tree;
			}
			schedule(_quietMillis);
		}
	}

	/** Schedules the next look at the pending folders; called while synchronized. */
	private void schedule(long delay) {
		if (_tickScheduled || _executor == null) {
			return;
		}
		try {
			_executor.schedule(this::tick, Math.max(0, delay), TimeUnit.MILLISECONDS);
			_tickScheduled = true;
		} catch (RejectedExecutionException ex) {
			// Stopping.
		}
	}

	/** Works on the pending folders whose quiet period is over and whose contents stood still. */
	private void tick() {
		List<Pending> due = new ArrayList<>();
		synchronized (this) {
			_tickScheduled = false;
			long now = System.currentTimeMillis();
			for (Pending pending : _pending.values()) {
				if (pending._due <= now) {
					due.add(pending);
				}
			}
		}
		for (Pending pending : due) {
			if (Thread.currentThread().isInterrupted()) {
				return;
			}
			List<File> folders = pending._tree ? tree(pending._folder) : Arrays.asList(pending._folder);
			String fingerprint = fingerprint(folders);
			boolean ready;
			synchronized (this) {
				String path = relative(pending._folder);
				if (_pending.get(path) != pending) {
					continue;
				}
				ready = fingerprint.equals(pending._fingerprint) && pending._due <= System.currentTimeMillis();
				if (ready) {
					_pending.remove(path);
				} else {
					// Still moving, or seen for the first time: one more quiet period.
					pending._fingerprint = fingerprint;
					pending._due = Math.max(pending._due, System.currentTimeMillis() + _quietMillis);
				}
			}
			if (ready) {
				for (File folder : folders) {
					if (Thread.currentThread().isInterrupted()) {
						return;
					}
					process(folder);
				}
			}
		}
		synchronized (this) {
			persist(false);
			if (!_pending.isEmpty()) {
				long next = Long.MAX_VALUE;
				for (Pending pending : _pending.values()) {
					next = Math.min(next, pending._due);
				}
				schedule(next - System.currentTimeMillis());
			}
		}
	}

	// --- The work. ---

	/**
	 * Runs every step on the given folder, in the calling thread.
	 *
	 * <p>
	 * What the walk at start-up and a noticed folder both end in. A folder without images is
	 * skipped by no step here: whether there is anything to do is each step's own question.
	 * </p>
	 */
	public void process(File folder) {
		String path = relative(folder);
		if (path == null || !folder.isDirectory()) {
			return;
		}
		String fingerprint = fingerprint(Arrays.asList(folder));
		Set<String> done = new LinkedHashSet<>();
		synchronized (this) {
			Record record = _records.get(path);
			if (record != null && record._fingerprint.equals(fingerprint)) {
				done.addAll(record._done);
			}
		}
		for (Step step : _steps) {
			String name = step.name();
			if (step.trustsRecord() && done.contains(name)) {
				continue;
			}
			String skipKey = name + "\n" + path;
			String skipped = _skipped.get(skipKey);
			if (skipped != null && skipped.startsWith(fingerprint)) {
				// Failed or not storable for exactly these contents in this process already.
				done.remove(name);
				if (skipped.endsWith(Outcome.FAILED.name())) {
					break;
				}
				continue;
			}
			Outcome outcome;
			try {
				outcome = step.run(folder);
			} catch (IOException | RuntimeException ex) {
				LOG.log(Level.WARNING, "Step '" + name + "' failed on '" + folder.getAbsolutePath() + "': "
					+ ex.getMessage(), ex);
				outcome = Outcome.FAILED;
			}
			if (outcome == Outcome.DONE) {
				_skipped.remove(skipKey);
				done.add(name);
				continue;
			}
			if (outcome == Outcome.WAITING) {
				_skipped.remove(skipKey);
				done.remove(name);
				continue;
			}
			_skipped.put(skipKey, fingerprint + " " + outcome.name());
			done.remove(name);
			if (outcome == Outcome.UNWRITABLE) {
				ReadOnlyFolders.report(folder, "the results of '" + name + "'");
				continue;
			}
			break;
		}
		synchronized (this) {
			Record before = _records.get(path);
			if (before == null || !before._fingerprint.equals(fingerprint) || !before._done.equals(done)) {
				_records.put(path, new Record(fingerprint, done));
				_dirty = true;
			}
			persist(false);
		}
	}

	/** Whether the given step is recorded as done for the given folder as it is now. */
	public synchronized boolean isDone(File folder, String step) {
		Record record = _records.get(relative(folder));
		return record != null && record._done.contains(step)
			&& record._fingerprint.equals(fingerprint(Arrays.asList(folder)));
	}

	/** Forgets the records of folders that are gone or no part of the walked library. */
	public synchronized void retain(Set<String> paths) {
		if (_records.keySet().removeIf(path -> !paths.contains(path))) {
			_dirty = true;
		}
	}

	/**
	 * The fingerprint of the image and video files of the given folders: their names, sizes and
	 * modification stamps.
	 */
	public static String fingerprint(List<File> folders) {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
		for (File folder : folders) {
			File[] files = folder.listFiles(f -> f.isFile() && ResourceCache.isImage(f));
			digest.update(folder.getAbsolutePath().getBytes(StandardCharsets.UTF_8));
			if (files == null) {
				continue;
			}
			Arrays.sort(files, Comparator.comparing(File::getName));
			for (File file : files) {
				digest.update((file.getName() + "\0" + file.length() + "\0" + file.lastModified() + "\n")
					.getBytes(StandardCharsets.UTF_8));
			}
		}
		StringBuilder result = new StringBuilder();
		for (byte b : digest.digest()) {
			result.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
		}
		return result.toString();
	}

	/** The given folder and every folder below it that is part of the library. */
	public static List<File> tree(File root) {
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
				Arrays.sort(children, Comparator.comparing(File::getName));
				pending.addAll(Arrays.asList(children));
			}
		}
		return result;
	}

	/** The path of the given folder relative to the space root, <code>null</code> outside it. */
	private String relative(File folder) {
		Path path = folder.toPath().toAbsolutePath().normalize();
		if (!path.startsWith(_root)) {
			return null;
		}
		return _root.relativize(path).toString().replace(File.separatorChar, '/');
	}

	// --- The file. ---

	/** The records as they stand, for the tests. */
	synchronized Map<String, Record> records() {
		return new HashMap<>(_records);
	}

	private synchronized void load() {
		if (!Files.isRegularFile(_file)) {
			return;
		}
		try (Reader reader = new InputStreamReader(Files.newInputStream(_file), StandardCharsets.UTF_8)) {
			read(new JsonReader(new ReaderAdapter(reader)));
		} catch (IOException | RuntimeException ex) {
			// Progress can always be made again; a broken file must never stop the server.
			LOG.log(Level.WARNING, "Starting over with the unreadable '" + _file + "': " + ex.getMessage());
			_records.clear();
		}
	}

	private void read(JsonReader in) throws IOException {
		int version = 0;
		in.beginObject();
		while (in.hasNext()) {
			switch (in.nextName()) {
				case VERSION__PROP:
					version = in.nextInt();
					break;
				case FOLDERS__PROP:
					in.beginObject();
					while (in.hasNext()) {
						String path = in.nextName();
						Record record = readRecord(in);
						if (record != null) {
							_records.put(path, record);
						}
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
			LOG.warning("'" + _file + "' was written by a newer version (" + version + " > " + VERSION
				+ "); what is recorded is trusted only where the folder is unchanged anyway.");
		}
	}

	private static Record readRecord(JsonReader in) throws IOException {
		String fingerprint = null;
		Set<String> done = new LinkedHashSet<>();
		in.beginObject();
		while (in.hasNext()) {
			switch (in.nextName()) {
				case FINGERPRINT__PROP:
					fingerprint = in.nextString();
					break;
				case DONE__PROP:
					in.beginArray();
					while (in.hasNext()) {
						done.add(in.nextString());
					}
					in.endArray();
					break;
				default:
					in.skipValue();
					break;
			}
		}
		in.endObject();
		return fingerprint == null ? null : new Record(fingerprint, done);
	}

	/** Writes the progress file now, whatever the clock says. */
	public synchronized void flush() {
		persist(true);
	}

	/** Writes the progress file, unless it was written a moment ago; called while synchronized. */
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
			LOG.log(Level.WARNING, "Cannot write '" + _file + "': " + ex.getMessage(), ex);
		}
	}

	private void store() throws IOException {
		Path folder = _file.getParent();
		Files.createDirectories(folder);
		Path tmpFile = Files.createTempFile(folder, "pipeline", ".json");
		try (Writer writer = new OutputStreamWriter(Files.newOutputStream(tmpFile), StandardCharsets.UTF_8)) {
			try (JsonWriter out = new JsonWriter(new WriterAdapter(writer))) {
				out.beginObject();
				out.name(VERSION__PROP);
				out.value(VERSION);
				out.name(FOLDERS__PROP);
				out.beginObject();
				for (Map.Entry<String, Record> entry : _records.entrySet()) {
					out.name(entry.getKey());
					out.beginObject();
					out.name(FINGERPRINT__PROP);
					out.value(entry.getValue()._fingerprint);
					out.name(DONE__PROP);
					out.beginArray();
					for (String step : entry.getValue()._done) {
						out.value(step);
					}
					out.endArray();
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
