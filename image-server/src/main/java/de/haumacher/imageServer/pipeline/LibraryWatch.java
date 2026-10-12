/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.pipeline;

import static java.nio.file.StandardWatchEventKinds.*;

import de.haumacher.imageServer.LibraryFiles;
import de.haumacher.imageServer.PreviewCache;
import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.util.servlet.Util;
import java.io.File;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The directory watches of one space, owned by its background work, see issue #253.
 *
 * <p>
 * Every folder of the library is watched, not only the ones a request opened (as before issue
 * #253, when the {@link ResourceCache} watched what it loaded): a folder copied in, a photograph
 * added, replaced in place or deleted anywhere in the library is noticed by the
 * {@link FolderPipeline} without a request, analysed and recorded. A request itself is a pure read
 * and watches nothing.
 * </p>
 *
 * <p>
 * A watch is an operating-system resource with a per-user limit (on Linux
 * <code>fs.inotify.max_user_watches</code>, which the README tells users to raise for a large
 * library). Where a watch cannot be registered, nothing fails: the folders left unwatched are found
 * by the pipeline's sweep of modification times (see {@link FolderPipeline#sweep(boolean)}), a
 * minute later instead of at once, and the limit is said once in the log. Where not even the watch
 * service can be had (the limit of <code>inotify</code> instances), the sweep is all there is.
 * </p>
 *
 * <p>
 * The watches are made on a thread of their own, the tree walked from the root, so the pipeline
 * and the requests never wait for them.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class LibraryWatch {

	private static final Logger LOG = Logger.getLogger(LibraryWatch.class.getName());

	/** Whether the limit of watches was reported in this process. */
	private static final AtomicBoolean LIMIT_REPORTED = new AtomicBoolean();

	private final Path _root;

	private final FolderPipeline _pipeline;

	private final String _name;

	/** How many folders are watched at most; for the tests, which simulate the system's limit. */
	private final int _maxWatches;

	/** The folder each watch is on; guarded by this. */
	private final Map<WatchKey, File> _folders = new HashMap<>();

	private WatchService _watcher;

	private Thread _thread;

	/** Whether a watch was refused: no further one is tried, the sweep covers the rest. */
	private volatile boolean _exhausted;

	private volatile boolean _closed;

	/** Creates the watches of the given space for the given pipeline; nothing is watched before {@link #start()}. */
	LibraryWatch(Path root, FolderPipeline pipeline, String name, int maxWatches) {
		_root = root;
		_pipeline = pipeline;
		_name = name;
		_maxWatches = maxWatches;
	}

	/**
	 * Starts watching the whole library on a thread of its own.
	 *
	 * @return Whether there is a watch service at all; without one the sweep is all there is.
	 */
	synchronized boolean start() {
		if (_thread != null || _closed) {
			return _watcher != null;
		}
		try {
			_watcher = FileSystems.getDefault().newWatchService();
		} catch (IOException ex) {
			LOG.log(Level.WARNING, "Cannot watch the library '" + _root + "' for changes: " + ex.getMessage()
				+ ". The system's limit of watch instances is probably reached (Linux: raise"
				+ " fs.inotify.max_user_instances). Changes are found by the sweep of the folders instead, a"
				+ " minute late.");
			_exhausted = true;
			return false;
		}
		_thread = new Thread(this::run, "library-watch " + _name);
		_thread.setDaemon(true);
		_thread.setPriority(Thread.MIN_PRIORITY);
		_thread.start();
		return true;
	}

	/** Stops watching and gives the watches back. */
	void close() {
		WatchService watcher;
		Thread thread;
		synchronized (this) {
			_closed = true;
			watcher = _watcher;
			thread = _thread;
		}
		if (watcher != null) {
			try {
				watcher.close();
			} catch (IOException ex) {
				LOG.log(Level.FINE, "Cannot close the watches of '" + _root + "': " + ex.getMessage(), ex);
			}
		}
		if (thread != null) {
			thread.interrupt();
		}
	}

	/** How many folders are watched right now, for the tests. */
	public synchronized int watchCount() {
		return _folders.size();
	}

	/** Whether a watch was refused and the sweep covers the folders left unwatched, for the tests. */
	public boolean isExhausted() {
		return _exhausted;
	}

	private void run() {
		watchTree(_root.toFile());
		while (!_closed) {
			WatchKey key;
			try {
				key = _watcher.take();
			} catch (ClosedWatchServiceException | InterruptedException ex) {
				return;
			}
			try {
				handle(key);
			} catch (ClosedWatchServiceException ex) {
				return;
			} catch (RuntimeException ex) {
				LOG.log(Level.WARNING, "Cannot process a change of the library: " + ex.getMessage(), ex);
			}
		}
	}

	/** Watches the given folder and every folder below it that is part of the library. */
	private void watchTree(File top) {
		Deque<File> pending = new ArrayDeque<>();
		pending.add(top);
		while (!pending.isEmpty() && !_closed && !_exhausted) {
			File folder = pending.removeFirst();
			if (!isLibraryFolder(folder) || !watch(folder)) {
				continue;
			}
			File[] children = folder.listFiles(f -> f.isDirectory() && !LibraryFiles.isIgnored(f));
			if (children != null) {
				pending.addAll(List.of(children));
			}
		}
	}

	private boolean isLibraryFolder(File folder) {
		Path path = folder.toPath().toAbsolutePath().normalize();
		if (!path.startsWith(_root)) {
			return false;
		}
		String relative = _root.relativize(path).toString().replace(File.separatorChar, '/');
		return relative.isEmpty() || LibraryFiles.isLibraryPath(relative);
	}

	/** Watches one folder; <code>false</code> where it is gone or cannot be watched. */
	private boolean watch(File folder) {
		synchronized (this) {
			if (_closed || _exhausted) {
				return false;
			}
			if (_folders.size() >= _maxWatches) {
				exhausted(folder, "the limit of " + _maxWatches + " watches");
				return false;
			}
			try {
				WatchKey key = folder.toPath().register(_watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
				_folders.put(key, folder);
				return true;
			} catch (ClosedWatchServiceException ex) {
				return false;
			} catch (NoSuchFileException ex) {
				// Gone meanwhile.
				return false;
			} catch (IOException ex) {
				exhausted(folder, ex.getMessage());
				return false;
			}
		}
	}

	private void exhausted(File folder, String reason) {
		_exhausted = true;
		if (LIMIT_REPORTED.compareAndSet(false, true)) {
			LOG.warning("Cannot watch the folder '" + folder + "' for changes: " + reason
				+ ". The system's limit of directory watches is probably reached (Linux: raise"
				+ " fs.inotify.max_user_watches, see the README). The folders left unwatched are checked by"
				+ " the sweep of their modification times instead, about once a minute; a photograph"
				+ " replaced in place there is noticed when its folder changes otherwise.");
		} else {
			LOG.fine("Cannot watch the folder '" + folder + "' for changes: " + reason);
		}
	}

	/**
	 * Handles what one watch reported: a new folder is watched and noticed with everything in it, a
	 * photograph added, changed (its permissions included) or deleted makes the folder's background
	 * work run again, and the cache forgets what it holds of the folder and the listing above it.
	 */
	private void handle(WatchKey key) {
		File dir;
		synchronized (this) {
			dir = _folders.get(key);
		}
		List<WatchEvent<?>> events = key.pollEvents();
		if (dir == null) {
			key.cancel();
			return;
		}
		boolean media = false;
		for (WatchEvent<?> event : events) {
			if (event.kind() == OVERFLOW) {
				// Events were lost: everything below is looked at again.
				_pipeline.notice(dir, true);
				continue;
			}
			File child = new File(dir, ((Path) event.context()).toString());
			if (LibraryFiles.isIgnored(child)) {
				continue;
			}
			if (event.kind() == ENTRY_CREATE && child.isDirectory()) {
				// Nobody watches inside a new folder yet: all of it is new.
				watchTree(child);
				_pipeline.notice(child, true);
			} else if (PreviewCache.SUPPORTED_EXTENSIONS.contains(Util.suffix(child.getName()))) {
				media = true;
			}
		}
		if (media) {
			_pipeline.changed(dir);
		}
		// A hand-edited sidecar, a folder renamed: what the cache holds is read again.
		ResourceCache.recorded(dir);
		synchronized (this) {
			if (!key.reset()) {
				// The folder is gone.
				_folders.remove(key);
			}
		}
	}
}
