/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.cache.ImageData;
import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.GeoLocation;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ReanalyzeResult;
import de.haumacher.imageServer.shared.model.Resource;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reading the photo details of a folder once more and filling in what its sidecars lack, see
 * issue #161.
 *
 * <h2>What it does</h2>
 *
 * <p>
 * A part a sidecar already lists is never analysed again (issue #78), so a library described
 * before the camera (#78) or the position (#112) existed carries neither, and nothing the loader
 * does will ever change that. This does, on request: every photograph and video below the folder
 * whose part lacks a {@link ImagePart#getCamera() camera} or a {@link ImagePart#getLocation()
 * location} (an absent one, or the <code>0/0</code> of a camera without a fix) has its file's
 * headers read again by {@link ImageData#analyze(AlbumInfo, java.io.File)} &mdash; metadata only,
 * never a pixel decoded, a video's container read exactly as at analysis &mdash; and what the file
 * says is written into exactly those two fields. A stored date, orientation, rating, privacy,
 * comment, tag or anything else is never touched: the author may have corrected the date, and a
 * file cannot know better than its author. A part that lacks nothing is not even read.
 * </p>
 *
 * <p>
 * An album that gained something is written like <code>?action=place</code> writes one &mdash;
 * {@link ImageServlet#storeSidecar(File, byte[])}, derived fields cleared &mdash; and one that
 * gained nothing is not written at all, so its bytes and its modification time stay.
 * </p>
 *
 * <h2>Where it runs</h2>
 *
 * <p>
 * An album is read while its request waits: a few hundred header reads take a second or two. A
 * folder of folders may be a whole library &mdash; 50&nbsp;000 photographs are minutes of reading
 * &mdash; which no browser and no proxy waits for. So a tree is walked on one low-priority thread
 * per space, album after album, the request waiting {@link ImageServlet#REANALYZE_WAIT_MILLIS} for
 * it: a small folder is answered complete, a large one <code>202</code> with the counts so far and
 * {@link ReanalyzeResult#isRunning()} set. {@link #progress(File)} answers the counts of the
 * latest run on a folder (<code>GET ?type=reanalyze</code>), and asking again for a folder whose
 * run is still going joins it instead of starting a second one.
 * </p>
 *
 * <p>
 * Nothing is held across albums &mdash; no lock, no buffer &mdash; so another caller is never kept
 * waiting by a run, and a run that is interrupted (a restart, {@link #shutdown()}) has written
 * every album it finished. Running it again is the resumption: an album that was filled lacks
 * nothing any more, so only what the files themselves cannot answer is read a second time.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class Reanalysis {

	private static final Logger LOG = Logger.getLogger(Reanalysis.class.getName());

	private final ResourceCache _cache;

	private final String _name;

	/** The latest run per folder, by its absolute path. */
	private final Map<String, Progress> _runs = new ConcurrentHashMap<>();

	private Executor _executor;

	private ExecutorService _own;

	/**
	 * Creates a {@link Reanalysis}.
	 *
	 * @param cache
	 *        The albums of the space, read and invalidated through it.
	 * @param name
	 *        The name of the space, for the thread.
	 */
	public Reanalysis(ResourceCache cache, String name) {
		_cache = cache;
		_name = name;
	}

	/** The counts of one run, updated while it goes. */
	public static final class Progress {

		final AtomicInteger _examined = new AtomicInteger();

		final AtomicInteger _filled = new AtomicInteger();

		final AtomicInteger _albums = new AtomicInteger();

		final CompletableFuture<Void> _done = new CompletableFuture<>();

		/** Whether the run has ended, successfully or not. */
		public boolean isDone() {
			return _done.isDone();
		}

		/** Completes when the run has ended. */
		public CompletableFuture<Void> done() {
			return _done;
		}

		/** The counts as they stand. */
		public ReanalyzeResult result() {
			return ReanalyzeResult.create()
				.setExamined(_examined.get())
				.setFilled(_filled.get())
				.setAlbums(_albums.get())
				.setRunning(!isDone());
		}
	}

	/** Replaces the thread a tree is walked on, for the tests. */
	synchronized void useExecutor(Executor executor) {
		_executor = executor;
	}

	/**
	 * Re-reads the album or the tree at the given path while the caller waits.
	 *
	 * @return The counts, complete.
	 */
	public ReanalyzeResult now(PathInfo folder) {
		Progress progress = new Progress();
		try {
			walk(folder, progress);
		} finally {
			progress._done.complete(null);
		}
		_runs.put(key(folder), progress);
		return progress.result();
	}

	/**
	 * Re-reads the tree at the given path on the background thread, or joins the run that is
	 * already reading it.
	 */
	public Progress start(PathInfo folder) {
		String key = key(folder);
		synchronized (this) {
			Progress running = _runs.get(key);
			if (running != null && !running.isDone()) {
				return running;
			}
			Progress progress = new Progress();
			_runs.put(key, progress);
			executor().execute(() -> {
				try {
					walk(folder, progress);
				} catch (RuntimeException ex) {
					LOG.log(Level.WARNING, "The re-reading of '" + folder.toFile() + "' failed: " + ex.getMessage(), ex);
				} finally {
					progress._done.complete(null);
					LOG.info("Re-read '" + folder.toFile() + "': " + progress.result());
				}
			});
			return progress;
		}
	}

	/** The latest run on the given folder, <code>null</code> when there was none. */
	public Progress progress(File folder) {
		return _runs.get(folder.getAbsolutePath());
	}

	/** Stops the background thread; a run in progress stops between two albums. */
	public synchronized void shutdown() {
		if (_own != null) {
			_own.shutdownNow();
			_own = null;
		}
	}

	private synchronized Executor executor() {
		if (_executor == null) {
			_own = Executors.newSingleThreadExecutor(runnable -> {
				Thread thread = new Thread(runnable, "reanalyze " + _name);
				thread.setDaemon(true);
				thread.setPriority(Thread.MIN_PRIORITY);
				return thread;
			});
			_executor = _own;
		}
		return _executor;
	}

	private static String key(PathInfo folder) {
		return folder.toFile().getAbsolutePath();
	}

	/** Re-reads the given folder and everything below it, album by album. */
	private void walk(PathInfo folder, Progress progress) {
		if (Thread.currentThread().isInterrupted()) {
			return;
		}
		File dir = folder.toFile();
		if (!dir.isDirectory()) {
			return;
		}
		Resource resource = _cache.lookup(folder);
		if (resource instanceof AlbumInfo) {
			try {
				album(folder, progress);
			} catch (IOException ex) {
				LOG.log(Level.WARNING, "Cannot write the details re-read in '" + dir + "': " + ex.getMessage(), ex);
			}
		}
		// Hidden folders are the server's own: the cache, the trash, the duplicates.
		File[] children = dir.listFiles(f -> f.isDirectory() && !f.getName().startsWith("."));
		if (children == null) {
			return;
		}
		Arrays.sort(children);
		for (File child : children) {
			walk(folder.child(child.getName()), progress);
		}
	}

	/** What a file said that its part lacked. */
	private static final class Found {
		final String _camera;

		final GeoLocation _location;

		Found(String camera, GeoLocation location) {
			_camera = camera;
			_location = location;
		}
	}

	/**
	 * Re-reads one album, see the class comment.
	 *
	 * <p>
	 * What is filled and written is the album's <em>sidecar as it is stored</em>, read afresh from
	 * disk ({@link ResourceCache#sidecar(File)}) &mdash; never the album a caller is answered, and
	 * not even the cached one. An answer is a per-caller copy for the wire: the inbox of issue #131
	 * flattened and sorted by date, the privacy filter and "view as" having dropped what the caller
	 * may not see, the faces derived onto it; written back, any of those would freeze a derived
	 * order, dissolve a group or lose a part. The cached album is closer, but the loader has merged
	 * into it every file the sidecar does not list yet, analysed afresh; writing it would make this
	 * request list them as a side effect. So the stored form is the one read, filled and written,
	 * its arrangement, its groups and every statement in it untouched, exactly as
	 * <code>?action=place</code> leaves everything but what it changes. A file the sidecar does
	 * not list needs nothing from here: it is analysed afresh on every read, with this build's
	 * rules.
	 * </p>
	 */
	private void album(PathInfo folder, Progress progress) throws IOException {
		File dir = folder.toFile();
		AlbumInfo album = (AlbumInfo) _cache.lookup(folder);
		// Every photograph of the album was looked at: the listed ones here, the rest when the
		// loader analysed them.
		progress._examined.addAndGet(images(album).size());

		FolderResource stored = ResourceCache.sidecar(dir);
		if (!(stored instanceof AlbumInfo)) {
			// No sidecar: every part was analysed afresh by the loader, and nothing is missing that
			// a file could tell.
			return;
		}

		// The files are read without anything held: this is the slow part.
		Map<String, Found> found = new HashMap<>();
		for (ImagePart image : images((AlbumInfo) stored)) {
			if (!lacksSomething(image)) {
				continue;
			}
			File file = new File(dir, image.getName());
			if (!file.isFile()) {
				continue;
			}
			ImageData analysed;
			try {
				analysed = ImageData.analyze(AlbumInfo.create(), file);
			} catch (Exception ex) {
				LOG.warning("Cannot re-read '" + file + "': " + ex.getMessage());
				continue;
			}
			found.put(image.getName(), new Found(analysed.getCamera(), analysed.getLocation()));
		}
		if (found.isEmpty()) {
			return;
		}

		// And written into the sidecar as it is now, which somebody may have changed meanwhile:
		// only what is still missing is filled.
		FolderResource current = ResourceCache.sidecar(dir);
		if (!(current instanceof AlbumInfo)) {
			return;
		}
		AlbumInfo target = (AlbumInfo) current;
		int filled = 0;
		for (ImagePart image : images(target)) {
			Found details = found.get(image.getName());
			if (details != null && fill(image, details)) {
				filled++;
			}
		}
		if (filled == 0) {
			return;
		}
		ImageServlet.storeSidecar(dir, ImageServlet.sidecarOf(target));
		_cache.invalidate(folder);
		progress._filled.addAndGet(filled);
		progress._albums.incrementAndGet();
		LOG.info("Filled the details of " + filled + " photograph(s) in '" + dir + "'.");
	}

	/** Whether the given part lacks something a file could tell. */
	private static boolean lacksSomething(ImagePart image) {
		return lacksCamera(image) || lacksLocation(image);
	}

	private static boolean lacksCamera(ImagePart image) {
		return image.getCamera() == null || image.getCamera().isEmpty();
	}

	private static boolean lacksLocation(ImagePart image) {
		return image.getLocation() == null || ImageData.isZero(image.getLocation());
	}

	/** Fills what the given part lacks from what its file said; whether anything changed. */
	private static boolean fill(ImagePart image, Found details) {
		boolean changed = false;
		if (lacksCamera(image) && details._camera != null && !details._camera.isEmpty()) {
			image.setCamera(details._camera);
			changed = true;
		}
		if (lacksLocation(image) && details._location != null && !ImageData.isZero(details._location)) {
			image.setLocation(details._location);
			changed = true;
		}
		return changed;
	}

	/** Every photograph and video of the album, the members of a group one by one. */
	private static List<ImagePart> images(AlbumInfo album) {
		List<ImagePart> result = new ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				result.add((ImagePart) part);
			} else if (part instanceof ImageGroup) {
				result.addAll(((ImageGroup) part).getImages());
			}
		}
		return result;
	}
}
