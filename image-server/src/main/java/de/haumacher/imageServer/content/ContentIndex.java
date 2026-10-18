/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.content;

import de.haumacher.imageServer.CacheRefresh;
import de.haumacher.imageServer.PathInfo;
import de.haumacher.imageServer.PhotoSearch;
import de.haumacher.imageServer.Posters;
import de.haumacher.imageServer.PreviewCache;
import de.haumacher.imageServer.RawPairs;
import de.haumacher.imageServer.cache.AnalysisRecord;
import de.haumacher.imageServer.cache.BrokenParts;
import de.haumacher.imageServer.pipeline.Background;
import de.haumacher.imageServer.pipeline.FolderPipeline;
import de.haumacher.imageServer.pipeline.FolderPipeline.Outcome;
import de.haumacher.imageServer.pipeline.ReadOnlyFolders;
import de.haumacher.imageServer.raw.RawFile;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.upload.HashCache;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The content search of one space in the background, see issue #257: every photo and video of the
 * space described by two vectors, kept in each album's {@link ContentCache}.
 *
 * <p>
 * Switched on by the space ({@link de.haumacher.imageServer.auth.SpaceStore.Config#isContentEnabled()}),
 * and nothing is computed, sent or written while it is off. The vectors are made by the
 * {@link Embedder} of the server, which may run on another machine.
 * </p>
 *
 * <h2>The step</h2>
 *
 * <p>
 * {@link #step()} is a {@link FolderPipeline.UnitStep}, one unit per photo that needs a vector: its
 * units wait behind the photo work of every album (previews, cover, faces, places) like the videos,
 * newest album first, so that an embedder taking a minute per photo never holds up a preview. A unit
 * embeds the <b>preview</b> of the photo (the poster of a video, at the moment its author chose, see
 * issue #285), never the original, as <code>v_img</code>, and its text ({@link ContentText}) as
 * <code>v_txt</code>; what the cache has already is not embedded again. A photo whose preview is not
 * there (not made yet, or it cannot be made) has no unit until it is.
 * </p>
 *
 * <p>
 * The step trusts no record of the pipeline: its own check is the album's cache file, which is the
 * truth; a changed description finds its text missing there and embeds that text alone.
 * </p>
 *
 * <h2>An embedder that cannot answer</h2>
 *
 * <p>
 * Down, refusing the key, still downloading its model: the unit is not done
 * ({@link Outcome#WAITING}, nothing recorded, nothing reported per photo), its album is remembered,
 * and the embedder is asked again after a back-off ({@link #FIRST_RETRY_MILLIS}, doubling up to
 * {@link #MAX_RETRY_MILLIS}); until then every unit waits without asking. Once it answers, the
 * albums that waited are noticed again. Why it cannot answer is {@link #unavailability()}, for the
 * settings of the space and the log (said once per reason).
 * </p>
 *
 * <p>
 * A new stamp of the embedder (another model, precision or preprocessing) makes every vector stale:
 * the whole space is walked again, and every album's cache is replaced as its turn comes.
 * </p>
 */
public final class ContentIndex {

	private static final Logger LOG = Logger.getLogger(ContentIndex.class.getName());

	/** The name of {@link #step()} in the record of the pipeline. */
	public static final String STEP = "content";

	/** How long the step waits before it asks an embedder that could not answer again, at first. */
	public static final long FIRST_RETRY_MILLIS = 60_000;

	/** How long it waits at most, see {@link #FIRST_RETRY_MILLIS}. */
	public static final long MAX_RETRY_MILLIS = 30 * 60_000;

	/**
	 * How long the stamp an embedder named is trusted before the step asks it again: a service
	 * on another machine may be given another model while this server runs.
	 */
	public static final long RECHECK_MILLIS = 10 * 60_000;

	/** What {@link #unavailability()} says where the server has no embedder at all. */
	public static final String NO_EMBEDDER = "this server has no embedder for the content search";

	/** What separates the name of a photo from the key of its work in a unit. */
	private static final char SEPARATOR = '\u0000';

	/** How long a unit waits for an image vector between two looks at whether it is put aside. */
	private static final long POLL_MILLIS = 100;

	private final Path _basePath;

	private final Path _root;

	private final Function<PathInfo, Resource> _albums;

	private final String _label;

	private volatile boolean _enabled;

	private volatile Embedder _embedder;

	private volatile FolderPipeline _pipeline;

	/** Who hears of every album whose cache this index wrote, see {@link #setOnWritten(java.util.function.Consumer)}. */
	private volatile java.util.function.Consumer<File> _onWritten = folder -> {
		// Nobody.
	};

	private long _firstRetry = FIRST_RETRY_MILLIS;

	private long _maxRetry = MAX_RETRY_MILLIS;

	private long _recheck = RECHECK_MILLIS;

	// --- What is known of the embedder, guarded by this. ---

	/** Whether the embedder was asked at all. */
	private boolean _asked;

	/** Why it could not answer when it was last asked, <code>null</code> when it could. */
	private String _problem;

	/** The stamp it named when it last answered, <code>null</code> before. */
	private String _stamp;

	/** The back-off now, <code>0</code> while it answers. */
	private long _backoff;

	/** Before when it is not asked again. */
	private long _retryAt;

	/** When it last answered. */
	private long _answeredAt;

	/** The albums whose units waited for the embedder. */
	private final Set<File> _waiting = new LinkedHashSet<>();

	/**
	 * The albums whose units were listed before the stamp of the embedder was known, by the stamps
	 * of their file: listed again once it is.
	 */
	private final Set<File> _guessed = new LinkedHashSet<>();

	private ScheduledExecutorService _timer;

	private ScheduledFuture<?> _retry;

	private ExecutorService _worker;

	/** The image vector being computed, which a unit put aside takes up again. */
	private Inflight _inflight;

	private volatile boolean _aside;

	private boolean _closed;

	private record Inflight(String key, Future<float[]> future) {
		// A pair.
	}

	/**
	 * Creates the content search of the space at the given root.
	 *
	 * @param albums
	 *        A folder as the cache of the space answers it: an album with its places derived.
	 * @param enabled
	 *        What the space says, see
	 *        {@link de.haumacher.imageServer.auth.SpaceStore.Config#isContentEnabled()}.
	 */
	public ContentIndex(Path basePath, Function<PathInfo, Resource> albums, boolean enabled) {
		_basePath = basePath;
		_root = basePath.toAbsolutePath().normalize();
		_albums = albums;
		_enabled = enabled;
		Path name = _root.getFileName();
		_label = name == null ? _root.toString() : name.toString();
	}

	/** Whether the space describes its photos. */
	public boolean isEnabled() {
		return _enabled;
	}

	/**
	 * Follows the administrator's switch, see issue #257: off, nothing is computed or sent from now
	 * on, and nothing stored is touched; on, the next units embed. The steps of the pipeline are the
	 * business of the caller, see {@link de.haumacher.imageServer.CatchUp#switchContent}.
	 */
	public void setEnabled(boolean enabled) {
		_enabled = enabled;
		if (!enabled) {
			synchronized (this) {
				_waiting.clear();
				_guessed.clear();
				if (_retry != null) {
					_retry.cancel(false);
					_retry = null;
				}
			}
		}
	}

	/** The embedder of the server, <code>null</code> for none (every unit then waits). */
	public void setEmbedder(Embedder embedder) {
		synchronized (this) {
			_embedder = embedder;
			_asked = false;
			_problem = null;
			_backoff = 0;
			_retryAt = 0;
		}
	}

	/** The embedder, <code>null</code> for none. */
	public Embedder getEmbedder() {
		return _embedder;
	}

	/** The pipeline the step is part of, which it notices albums in again. */
	public void setPipeline(FolderPipeline pipeline) {
		_pipeline = pipeline;
	}

	/**
	 * Lets the given listener hear of every album whose {@link ContentCache} the step wrote, after it
	 * was written, on the thread that wrote it: the vector index of the space (issue #264, slice 4)
	 * takes the new vectors at once.
	 */
	public void setOnWritten(java.util.function.Consumer<File> listener) {
		_onWritten = listener == null ? folder -> {
			// Nobody.
		} : listener;
	}

	/**
	 * The stamp the embedder named when it last answered, <code>null</code> while it was never
	 * asked or never answered; nothing is asked here.
	 */
	public synchronized String stamp() {
		return _stamp;
	}

	/** Sets the back-off of an embedder that cannot answer, for the tests. */
	public void setRetryMillis(long first, long max) {
		_firstRetry = first;
		_maxRetry = max;
	}

	/** Sets how long the stamp of an embedder is trusted, see {@link #RECHECK_MILLIS}; for the tests. */
	public void setRecheckMillis(long millis) {
		_recheck = millis;
	}

	/** Stops the threads of the back-off and of the image vectors. */
	public void close() {
		ScheduledExecutorService timer;
		ExecutorService worker;
		synchronized (this) {
			_closed = true;
			timer = _timer;
			worker = _worker;
			_timer = null;
			_worker = null;
		}
		if (timer != null) {
			timer.shutdownNow();
		}
		if (worker != null) {
			worker.shutdownNow();
		}
	}

	// --- What the settings show. ---

	/**
	 * Why the embedder cannot describe photos now, <code>null</code> where it can, see
	 * {@link de.haumacher.imageServer.shared.model.SpaceSettings#getContentUnavailable()}.
	 *
	 * <p>
	 * Switched on, what the step found when it last asked (asked now if it never did); off, what the
	 * embedder says without being set in motion ({@link Embedder#peekUnavailability()}): a space that
	 * did not ask for the content search starts no download.
	 * </p>
	 */
	public String unavailability() {
		Embedder embedder = _embedder;
		if (embedder == null) {
			return NO_EMBEDDER;
		}
		if (!_enabled) {
			return embedder.peekUnavailability();
		}
		boolean asked;
		synchronized (this) {
			asked = _asked;
		}
		if (!asked) {
			check();
		}
		synchronized (this) {
			return _problem;
		}
	}

	/** The albums whose units wait for the embedder, for the tests. */
	public synchronized List<File> waiting() {
		return new ArrayList<>(_waiting);
	}

	// --- The availability. ---

	/**
	 * Whether the embedder answers: <code>false</code> without asking while the back-off of a
	 * failed question runs, <code>true</code> without asking while its last answer is recent (a
	 * vector it then cannot make says why, see {@link EmbedderUnavailableException}).
	 */
	private boolean available() {
		synchronized (this) {
			long now = System.currentTimeMillis();
			if (_asked && _problem != null && now < _retryAt) {
				return false;
			}
			if (_asked && _problem == null && _stamp != null && now - _answeredAt < _recheck) {
				return true;
			}
		}
		return check();
	}

	/** Asks the embedder and records what it says; whether it answers. */
	private boolean check() {
		Embedder embedder = _embedder;
		String problem;
		String stamp = null;
		if (embedder == null) {
			problem = NO_EMBEDDER;
		} else {
			try {
				problem = embedder.unavailability();
				if (problem == null) {
					stamp = embedder.stamp();
					if (stamp == null) {
						problem = "the embedder names no model";
					}
				}
			} catch (RuntimeException ex) {
				problem = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
			}
		}
		if (problem == null) {
			answered(stamp);
			return true;
		}
		refused(problem);
		return false;
	}

	/** The embedder answered with the given stamp. */
	private void answered(String stamp) {
		List<File> notice;
		boolean walk;
		boolean recovered;
		synchronized (this) {
			recovered = _asked && _problem != null;
			walk = _stamp != null && !_stamp.equals(stamp);
			_asked = true;
			_problem = null;
			_backoff = 0;
			_retryAt = 0;
			_answeredAt = System.currentTimeMillis();
			if (_retry != null) {
				_retry.cancel(false);
				_retry = null;
			}
			boolean first = _stamp == null;
			_stamp = stamp;
			notice = new ArrayList<>(_waiting);
			_waiting.clear();
			if (first) {
				notice.addAll(_guessed);
			}
			_guessed.clear();
		}
		if (recovered) {
			LOG.info("Content search of '" + _label + "': the embedder answers again (" + stamp + ").");
		}
		FolderPipeline pipeline = _pipeline;
		if (pipeline == null || !_enabled) {
			return;
		}
		if (walk) {
			LOG.info("Content search of '" + _label + "': the embedder's model is now " + stamp
				+ "; every photo is described again.");
			walk(pipeline);
			return;
		}
		for (File folder : notice) {
			pipeline.notice(folder, false);
		}
	}

	/** Hands every album of the space to the catch-up, newest first, on the pipeline's thread. */
	private void walk(FolderPipeline pipeline) {
		if (!pipeline.isStarted()) {
			return;
		}
		try {
			pipeline.execute(() -> pipeline.catchUp(FolderPipeline.newestFirst(FolderPipeline.tree(_root.toFile()))));
		} catch (IllegalStateException ex) {
			// Stopped meanwhile: the next start walks the space anyway.
		}
	}

	/** The embedder could not answer, for the given reason: back off, and ask again later. */
	private void refused(String problem) {
		long delay;
		boolean changed;
		synchronized (this) {
			changed = !Objects.equals(problem, _problem) || !_asked;
			_asked = true;
			_problem = problem;
			_backoff = _backoff <= 0 ? Math.max(1, _firstRetry) : Math.min(_backoff * 2, Math.max(1, _maxRetry));
			delay = _backoff;
			_retryAt = System.currentTimeMillis() + delay;
			scheduleRetry(delay);
		}
		LOG.log(changed ? Level.WARNING : Level.FINE, "Content search of '" + _label + "' waits: " + problem
			+ " (asked again in " + (delay >= 60_000 ? delay / 60_000 + " min" : delay / 1000 + " s") + ").");
	}

	/** Asks the embedder again after the given time, unless switched off; called while synchronized. */
	private void scheduleRetry(long delay) {
		if (_closed || !_enabled) {
			return;
		}
		if (_retry != null) {
			_retry.cancel(false);
		}
		if (_timer == null) {
			_timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
				Thread thread = new Thread(runnable, "content-retry " + _label);
				thread.setDaemon(true);
				thread.setPriority(Thread.MIN_PRIORITY);
				return thread;
			});
		}
		try {
			_retry = _timer.schedule(() -> {
				synchronized (this) {
					_retry = null;
				}
				if (_enabled) {
					check();
				}
			}, delay, TimeUnit.MILLISECONDS);
		} catch (RejectedExecutionException ex) {
			// Closed meanwhile.
		}
	}

	/** Remembers an album whose unit waited for the embedder. */
	private synchronized void waitFor(File folder) {
		if (_enabled) {
			_waiting.add(folder);
		}
	}

	// --- The step. ---

	/** The background work of this space's content search, see {@link ContentIndex}. */
	public FolderPipeline.UnitStep step() {
		return _step;
	}

	private final FolderPipeline.UnitStep _step = new FolderPipeline.UnitStep() {
		@Override
		public String name() {
			return STEP;
		}

		@Override
		public boolean trustsRecord() {
			// The album's cache file is the truth, and reading it is the check.
			return false;
		}

		@Override
		public List<String> units(File folder) {
			return ContentIndex.this.units(folder);
		}

		@Override
		public String itemOf(String unit) {
			int separator = unit.indexOf(SEPARATOR);
			return separator < 0 ? unit : unit.substring(0, separator);
		}

		@Override
		public void putAside() {
			_aside = true;
		}

		@Override
		public Outcome runUnit(File folder, String unit) throws IOException {
			return ContentIndex.this.runUnit(folder, itemOf(unit));
		}
	};

	/** One photo of an album as the step sees it. */
	private record Item(ImagePart part, File file, ContentCache.ImageKey image, File preview, ContentText.Text text,
			String textHash) {

		/** The unit that brings it up to date, see {@link ContentIndex#units(File)}. */
		String unit(String imageStamp) {
			return part.getName() + SEPARATOR
				+ ContentCache.textHash(imageStamp + "\n" + image.hash() + "\n" + image.poster() + "\n" + textHash)
					.substring(0, 12);
		}
	}

	/**
	 * What an album holds for the step.
	 *
	 * @param items
	 *        The photos to describe, by name.
	 * @param names
	 *        The names of every file of the album, described or not.
	 * @param hashes
	 *        The contents of every file of the album that is hashed.
	 */
	private record Album(Map<String, Item> items, Set<String> names, Set<String> hashes) {
		// A triple.
	}

	/**
	 * What the given album holds for the step; <code>null</code> where it cannot be read now (then
	 * nothing of its cache is dropped either) or is no album of its own.
	 *
	 * @param only
	 *        The one photo whose item is wanted, <code>null</code> for all.
	 */
	private Album items(File folder, String only) {
		AlbumInfo album = album(folder);
		if (album == null) {
			return null;
		}
		Map<String, Item> result = new LinkedHashMap<>();
		Set<String> names = new HashSet<>();
		Set<String> present = new HashSet<>();
		Map<String, String> hashes = new HashCache(folder).storedHashByName();
		File[] files = folder.listFiles(File::isFile);
		if (files != null) {
			for (File file : files) {
				names.add(file.getName());
				String hash = hashes.get(file.getName());
				if (hash != null) {
					present.add(hash);
				}
			}
		}
		Set<String> broken = AnalysisRecord.broken(folder);
		List<ImagePart> images = PhotoSearch.images(album);
		Set<String> pairedBases = new HashSet<>();
		for (ImagePart image : images) {
			if (RawPairs.isCompanionType(image.getName())) {
				pairedBases.add(RawPairs.base(image.getName()));
			}
		}
		for (ImagePart image : images) {
			String name = image.getName();
			if ((only != null && !only.equals(name)) || image.isMissing() || BrokenParts.isBroken(image) || broken.contains(name) || result.containsKey(name)) {
				continue;
			}
			File file = new File(folder, name);
			if (!file.isFile()) {
				continue;
			}
			// The raw beside a JPEG of its name is that photo, see issue #191.
			if (RawFile.isRaw(file) && pairedBases.contains(RawPairs.base(name))) {
				continue;
			}
			String hash = hashes.get(name);
			if (hash == null) {
				// Not hashed yet: the hash step comes first, and notices the album when it is done.
				continue;
			}
			long poster = PreviewCache.isVideoName(name) ? Math.max(0, Posters.millis(image)) : 0;
			File preview = poster > 0 ? PreviewCache.posterFile(file, poster) : PreviewCache.previewFile(file);
			if (!PreviewCache.present(preview)) {
				// The previews step makes it, and the album is worked on again then.
				continue;
			}
			ContentText.Text text = ContentText.of(album, folder.getName(), image);
			result.put(name, new Item(image, file, new ContentCache.ImageKey(hash, poster), preview, text,
				ContentCache.textHash(text.document())));
		}
		return new Album(result, names, present);
	}

	private AlbumInfo album(File folder) {
		Path path = folder.toPath().toAbsolutePath().normalize();
		if (!path.startsWith(_root)) {
			return null;
		}
		Path relative = _root.relativize(path);
		PathInfo info = relative.toString().isEmpty() ? new PathInfo(_basePath) : new PathInfo(_basePath, relative);
		Resource resource;
		try {
			resource = _albums.apply(info);
		} catch (RuntimeException ex) {
			LOG.log(Level.FINE, "Cannot read '" + folder + "' for the content search: " + ex.getMessage(), ex);
			return null;
		}
		if (!(resource instanceof AlbumInfo) || PhotoSearch.holdsNoFiles(resource)) {
			// A collection or a saved search shows photos of other albums, which are described there.
			return null;
		}
		return (AlbumInfo) resource;
	}

	/** The stamps to compare the given album's cache with: the embedder's, else the file's own. */
	private String[] stamps(ContentCache file) {
		String stamp;
		synchronized (this) {
			stamp = _stamp;
		}
		if (stamp == null) {
			return new String[] { file.getImageStamp(), file.getTextStamp() };
		}
		return new String[] { stamp, ContentText.stamp(stamp) };
	}

	/**
	 * Whether the given cache describes the given photo completely: both its vectors, and the
	 * {@link Quality} of a photograph's picture (a video joins no burst, so it needs none).
	 */
	private static boolean complete(ContentCache cache, Item item) {
		ContentCache.Photo photo = cache.photos().get(item.part().getName());
		return photo != null && photo.image().equals(item.image()) && photo.text().equals(item.textHash())
			&& cache.image(item.image()) != null && cache.text(item.textHash()) != null && measured(cache, item);
	}

	/** Whether the given photo needs no {@link Quality} measured, or has it, see {@link ContentCache#quality}. */
	private static boolean measured(ContentCache cache, Item item) {
		return !needsQuality(item) || !Float.isNaN(cache.quality(item.image()));
	}

	/** Whether the given photo is one a {@link Quality} is measured of: a photograph, not a video. */
	private static boolean needsQuality(Item item) {
		return !PreviewCache.isVideoName(item.part().getName());
	}

	/** The {@link Quality} to store for the given photo, {@link Float#NaN} for none to store. */
	private static float qualityOf(ContentCache cache, Item item) {
		if (measured(cache, item)) {
			return Float.NaN;
		}
		return Quality.measure(item.preview());
	}

	/**
	 * The units of the given album: one per photo its cache does not describe completely. What the
	 * cache holds of photos that are gone is dropped on the way.
	 */
	List<String> units(File folder) {
		if (!_enabled) {
			return List.of();
		}
		boolean ask;
		synchronized (this) {
			// Never asked in this process, or the stamp is old: what the cache is compared with must
			// be the embedder's (a failed question is asked again by the back-off, not here).
			ask = !_asked || (_problem == null && System.currentTimeMillis() - _answeredAt >= _recheck);
		}
		if (ask) {
			check();
		}
		Album album = items(folder, null);
		File file = ContentCache.file(folder);
		if (album == null || (album.items().isEmpty() && !file.isFile())) {
			return List.of();
		}
		Map<String, Item> items = album.items();
		ContentCache onDisk = ContentCache.read(folder);
		String[] stamps = stamps(onDisk);
		boolean guessed;
		synchronized (this) {
			guessed = _stamp == null && !onDisk.getImageStamp().isEmpty();
			if (guessed) {
				_guessed.add(folder);
			}
		}
		ContentCache cache = ContentCache.read(folder, stamps[0], stamps[1]);
		if (file.isFile() && ReadOnlyFolders.writable(CacheRefresh.cacheDir(folder))) {
			// What the album no longer holds goes: a file gone, contents replaced, a stale stamp.
			ContentCache kept = ContentCache.read(folder, stamps[0], stamps[1]);
			kept.retain(album.names(), album.hashes());
			if (!kept.equals(onDisk)) {
				try {
					ContentCache.update(folder, stamps[0], stamps[1],
						each -> each.retain(album.names(), album.hashes()));
					_onWritten.accept(folder);
				} catch (IOException ex) {
					LOG.log(Level.FINE, "Cannot tidy the content cache of '" + folder + "': " + ex.getMessage(), ex);
				}
			}
		}
		List<String> result = new ArrayList<>();
		for (Item item : items.values()) {
			if (!complete(cache, item)) {
				result.add(item.unit(stamps[0]));
			}
		}
		return result;
	}

	/** Brings the photo of the given name up to date, see {@link ContentIndex}. */
	Outcome runUnit(File folder, String name) throws IOException {
		if (!_enabled) {
			// Switched off: not done, so that the photo is described when the switch is on again.
			return Outcome.WAITING;
		}
		Album album = items(folder, name);
		Item item = album == null ? null : album.items().get(name);
		if (item == null) {
			// Gone, or no more to describe.
			return Outcome.DONE;
		}
		if (!ReadOnlyFolders.writable(CacheRefresh.cacheDir(folder).isDirectory() ? CacheRefresh.cacheDir(folder)
			: folder)) {
			return Outcome.UNWRITABLE;
		}
		ContentCache onDisk = ContentCache.read(folder);
		String[] stamps = stamps(onDisk);
		ContentCache cache = ContentCache.read(folder, stamps[0], stamps[1]);
		if (complete(cache, item)) {
			return Outcome.DONE;
		}
		if (cache.image(item.image()) != null && cache.text(item.textHash()) != null) {
			// A photo renamed, or a copy: both vectors are there, it is only named. Or a cache of the
			// build before the quality of a picture: it is measured from the preview, without asking
			// the embedder.
			float quality = qualityOf(cache, item);
			ContentCache.update(folder, stamps[0], stamps[1],
				each -> mapIfThere(each, item, quality));
			_onWritten.accept(folder);
			return Outcome.DONE;
		}
		_aside = false;
		if (!available()) {
			waitFor(folder);
			return Outcome.WAITING;
		}
		String stamp;
		synchronized (this) {
			stamp = _stamp;
		}
		String textStamp = ContentText.stamp(stamp);
		cache = ContentCache.read(folder, stamp, textStamp);
		Embedder embedder = _embedder;
		try {
			float[] image = cache.image(item.image());
			if (image == null) {
				image = embedImage(embedder, item);
				if (image == null) {
					// Put aside for something more urgent; the vector is taken up where it was.
					return Outcome.AGAIN;
				}
			}
			float[] text = cache.text(item.textHash());
			if (text == null) {
				text = embedder.embedDocument(item.text().title(), item.text().body());
			}
			if (!stamp.equals(embedder.stamp())) {
				// The model changed while this ran; the new one describes it again, see answered().
				check();
				return Outcome.WAITING;
			}
			float[] imageVector = image;
			float[] textVector = text;
			float quality = qualityOf(cache, item);
			ContentCache.update(folder, stamp, textStamp, each -> {
				each.putImage(item.image(), imageVector);
				if (!Float.isNaN(quality)) {
					each.putQuality(item.image(), quality);
				}
				each.putText(item.textHash(), textVector);
				each.putPhoto(name, new ContentCache.Photo(item.image(), item.textHash()));
			});
			_onWritten.accept(folder);
			return Outcome.DONE;
		} catch (EmbedderUnavailableException ex) {
			refused(ex.getMessage());
			waitFor(folder);
			return Outcome.WAITING;
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return Outcome.WAITING;
		}
	}

	private static void mapIfThere(ContentCache cache, Item item, float quality) {
		if (cache.image(item.image()) != null && cache.text(item.textHash()) != null) {
			if (!Float.isNaN(quality)) {
				cache.putQuality(item.image(), quality);
			}
			cache.putPhoto(item.part().getName(), new ContentCache.Photo(item.image(), item.textHash()));
		}
	}

	/**
	 * The image vector of the given photo, from its preview; <code>null</code> where the unit was put
	 * aside meanwhile ({@link FolderPipeline.UnitStep#putAside()}): the vector is computed on, and the
	 * unit takes it up when it runs again.
	 */
	private float[] embedImage(Embedder embedder, Item item) throws IOException, InterruptedException {
		String key = item.file().getAbsolutePath() + "\n" + item.image();
		Future<float[]> future;
		synchronized (this) {
			if (_inflight != null && _inflight.key().equals(key)) {
				future = _inflight.future();
			} else {
				future = null;
			}
		}
		if (future == null) {
			// Requests first, as for every unit of the background.
			Background.awaitQuiet();
			// Never the original: the preview the background made, see issue #257.
			byte[] preview = Files.readAllBytes(item.preview().toPath());
			synchronized (this) {
				if (_closed) {
					throw new InterruptedException();
				}
				if (_worker == null) {
					_worker = Executors.newSingleThreadExecutor(runnable -> {
						Thread thread = new Thread(runnable, "content-embed " + _label);
						thread.setDaemon(true);
						thread.setPriority(Thread.MIN_PRIORITY);
						return thread;
					});
				}
				future = _worker.submit(() -> embedder.embedImage(preview));
				_inflight = new Inflight(key, future);
			}
		}
		while (true) {
			try {
				float[] result = future.get(POLL_MILLIS, TimeUnit.MILLISECONDS);
				clearInflight(future);
				return result;
			} catch (TimeoutException ex) {
				if (_aside) {
					return null;
				}
			} catch (ExecutionException ex) {
				clearInflight(future);
				Throwable cause = ex.getCause();
				if (cause instanceof IOException) {
					throw (IOException) cause;
				}
				if (cause instanceof RuntimeException) {
					throw (RuntimeException) cause;
				}
				throw new IOException(cause);
			}
		}
	}

	private synchronized void clearInflight(Future<float[]> future) {
		if (_inflight != null && _inflight.future() == future) {
			_inflight = null;
		}
	}
}
