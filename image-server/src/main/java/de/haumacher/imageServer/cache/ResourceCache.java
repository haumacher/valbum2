/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.cache;

import static java.nio.file.StandardWatchEventKinds.*;

import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.MetadataException;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalNotification;
import de.haumacher.imageServer.AlbumDate;
import de.haumacher.imageServer.Contributors;
import de.haumacher.imageServer.FolderCover;
import de.haumacher.imageServer.Inboxes;
import de.haumacher.imageServer.LibraryFiles;
import de.haumacher.imageServer.MoveService;
import de.haumacher.imageServer.PathInfo;
import de.haumacher.imageServer.PreviewCache;
import de.haumacher.imageServer.RawPairs;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumKind;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.FolderKind;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.Orientation;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.ThumbnailInfo;
import de.haumacher.imageServer.shared.util.AlbumUtil;
import de.haumacher.imageServer.shared.util.UpdateTransient;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import de.haumacher.util.servlet.Util;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.text.DateFormat;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cache of {@link Resource}s representing directories and files in a photo album.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class ResourceCache {

	/** What a folder shows, the one list an upload is held to as well, see issue #186. */
	private static final Set<String> ACCEPTED = PreviewCache.SUPPORTED_EXTENSIONS;

	static final FileFilter IMAGES = f -> {
		// A hidden file or the litter of another system is no photograph, whatever its extension says
		// (Synology's @eaDir thumbnails lie in an ignored folder, macOS' ._ files carry .jpg), see
		// issue #173.
		return f.isFile() && ACCEPTED.contains(Util.suffix(f.getName())) && !LibraryFiles.isIgnored(f);
	};

	private static final String SEP = "[-_\\.]";
	static final Pattern DATE_PATTERN = Pattern.compile(
			"(" + "\\d{4}" + ")" + SEP + "(" + "\\d{2}" + ")" + SEP + "(" + "\\d{2}" + ")");

	/** How many folders a cache holds, and so how many folders it watches at most, see issue #235. */
	public static final int MAX_FOLDERS = 1000;

	/**
	 * Who is told about folders this cache notices, see {@link FolderObserver}.
	 */
	private static final List<FolderObserver> OBSERVERS = new CopyOnWriteArrayList<>();

	/** Whether the failure to watch a folder has been reported in this process. */
	private static final AtomicBoolean UNWATCHED_REPORTED = new AtomicBoolean();

	/**
	 * Is told about the folders a {@link ResourceCache} notices, see issue #235.
	 *
	 * <p>
	 * The cache learns about the library anyway &mdash; it loads folders and its directory watcher
	 * reports what changed in them &mdash; and the background work of a space (hashing, see
	 * {@link de.haumacher.imageServer.upload.HashIndex}) wants to hear of exactly that, so that a
	 * folder copied in by hand is taken care of without a restart. Called on a request thread or on
	 * the watcher's thread, so an observer only takes note and never works.
	 * </p>
	 */
	public interface FolderObserver {

		/**
		 * The given folder was loaded or changed.
		 *
		 * @param tree
		 *        Whether everything below it is new as well: a folder that appeared in a watched one
		 *        (the folders inside it are watched by nobody yet), or a watcher that lost events.
		 */
		void folderNoticed(File folder, boolean tree);
	}

	/** Tells the given observer about every folder any cache notices from now on. */
	public static void addObserver(FolderObserver observer) {
		OBSERVERS.add(observer);
	}

	/** Stops telling the given observer. */
	public static void removeObserver(FolderObserver observer) {
		OBSERVERS.remove(observer);
	}

	static void notice(File folder, boolean tree) {
		for (FolderObserver observer : OBSERVERS) {
			try {
				observer.folderNoticed(folder, tree);
			} catch (RuntimeException ex) {
				Loader.LOG.log(Level.WARNING, "An observer failed on '" + folder + "': " + ex.getMessage(), ex);
			}
		}
	}

	private Loader _loader;

	private LoadingCache<PathInfo, Resource> _cache;

	/**
	 * Creates a {@link ResourceCache} that takes nothing over out of a photograph.
	 */
	public ResourceCache() throws IOException {
		this(ImageData.Analysis.NONE);
	}

	/**
	 * Creates a {@link ResourceCache}.
	 *
	 * @param analysis
	 *        What is read out of a photograph beyond its own properties, the first time it is
	 *        looked at: the named faces an older tool wrote into it, see
	 *        {@link de.haumacher.imageServer.faces.FaceImport} and issue #129.
	 */
	public ResourceCache(ImageData.Analysis analysis) throws IOException {
		this(analysis, null);
	}

	/**
	 * Creates a {@link ResourceCache} for the albums of one space.
	 *
	 * @param analysis
	 *        See {@link #ResourceCache(ImageData.Analysis)}.
	 * @param zone
	 *        The zone of the space, see
	 *        {@link de.haumacher.imageServer.auth.SpaceStore.Config#getZone()}: a photograph that says
	 *        neither its offset nor a GPS time is dated in it (issue #183); <code>null</code> for the
	 *        server's zone, asked at every analysis.
	 */
	public ResourceCache(ImageData.Analysis analysis, ZoneId zone) throws IOException {
		this(analysis, zone, MAX_FOLDERS);
	}

	/**
	 * Creates a {@link ResourceCache} holding at most the given number of folders; for the tests.
	 *
	 * <p>
	 * A folder is watched for exactly as long as it is held: its watch is released when it leaves
	 * the cache, so the watches of a large library are bounded by this number and never grow
	 * towards the system's limit, see issue #235.
	 * </p>
	 */
	ResourceCache(ImageData.Analysis analysis, ZoneId zone, int maxFolders) throws IOException {
		_loader = new Loader(analysis, zone, maxFolders);
		_cache = CacheBuilder.newBuilder().maximumSize(maxFolders)
			.removalListener((RemovalNotification<PathInfo, Resource> removal) -> _loader.removed(removal.getKey(), removal.wasEvicted()))
			.build(_loader);
		_loader.attach(_cache);
	}

	/** How many folders are watched right now, for the tests. */
	int watchCount() {
		return _loader.watchCount();
	}

	/** How many folders are held right now, for the tests. */
	long size() {
		return _cache.size();
	}

	/** Processes what the watcher reported, as a request would before it is answered; for the tests. */
	void processEvents() {
		_loader.processEvents(_cache);
	}

	/**
	 * The zone a photograph of this space is dated in where it says neither its offset nor a GPS
	 * time, see issue #183.
	 */
	public ZoneId zone() {
		return _loader.zone();
	}

	/**
	 * Releases the directory watcher this cache holds.
	 *
	 * <p>
	 * A watcher is an operating-system resource (an <code>inotify</code> instance on Linux) and
	 * the system grants only a limited number of them, so a cache that is dropped must give its
	 * own back, see {@link de.haumacher.imageServer.ImageServlet#destroy()}.
	 * </p>
	 */
	public void close() throws IOException {
		_loader.close();
	}

	/**
	 * Whether the given {@link File} is a supported image or video file.
	 */
	public static boolean isImage(File file) {
		return IMAGES.accept(file);
	}

	/**
	 * Retrieves the {@link Resource} description for the given {@link PathInfo}.
	 *
	 * @param pathInfo The file-system resource to analyze.
	 * @return The {@link Resource} describing the system resource.
	 */
	/**
	 * Forgets what is cached for the given folder and for the folder containing it.
	 *
	 * <p>
	 * A listing describes each of its folders from that folder's own sidecar (title, subtitle,
	 * index picture, see {@link Loader#loadFolderInfo(File)}), so a change inside a folder is a
	 * change of the listing above it, too. Called when the servlet has written a sidecar: the
	 * directory watcher reports the same change, but only some time after the write, and the
	 * client's next request may come first.
	 * </p>
	 */
	public void invalidate(PathInfo pathInfo) {
		invalidate(_cache, pathInfo);
	}

	static void invalidate(LoadingCache<PathInfo, Resource> cache, PathInfo pathInfo) {
		cache.invalidate(pathInfo);
		if (!pathInfo.isRoot()) {
			cache.invalidate(pathInfo.parent());
		}
	}

	/**
	 * The <code>index.json</code> of the given folder, <code>null</code> if it has none or it
	 * cannot be read.
	 *
	 * <p>
	 * This is the cheap look into a folder: it reads the sidecar and nothing else, no image file
	 * is opened and nothing is cached. The privacy filter of issue #46 uses it to learn whether a
	 * folder's index picture is one the caller may see, without loading every album of a listing.
	 * </p>
	 */
	public static FolderResource sidecar(File dir) {
		return Loader.loadDirIndex(dir);
	}

	/**
	 * Forgets what is cached for the given folder, for everything below it and for the folder
	 * above it.
	 *
	 * <p>
	 * What a folder move leaves behind: the moved folder is cached under its old path, and so is
	 * every album inside it. Only the keys the cache actually holds are visited, so this costs
	 * nothing on disk however deep the moved tree is, see
	 * {@link de.haumacher.imageServer.MoveService}.
	 * </p>
	 */
	public void invalidateTree(PathInfo pathInfo) {
		String prefix = pathInfo.toFile().getAbsolutePath() + File.separator;
		for (PathInfo key : new ArrayList<>(_cache.asMap().keySet())) {
			if (key.toFile().getAbsolutePath().startsWith(prefix)) {
				_cache.invalidate(key);
			}
		}
		invalidate(pathInfo);
	}

	/**
	 * The {@link AlbumInfo} a folder without a sidecar is described by.
	 *
	 * <p>
	 * A move that carries the first images into a folder that had none must write that folder's
	 * sidecar, and it must be the same one the loader would have made up, see
	 * {@link de.haumacher.imageServer.MoveService}.
	 * </p>
	 */
	public static AlbumInfo genericAlbum(PathInfo path) {
		return Loader.createGenericAlbumInfo(path);
	}

	/**
	 * The tile a listing shows the given folder with: its title, subtitle, cover and date.
	 *
	 * <p>
	 * The very method a listing builds its own entries with, offered to the link entries of issue
	 * #50: a link is shown as what it points at, so its tile must be built exactly as the target's
	 * own would be. Nothing is written and nothing is cached; the target's sidecar is read, and an
	 * image is opened only for a folder that has no sidecar, exactly as in a listing.
	 * </p>
	 */
	public static FolderInfo folderInfo(File folder) {
		return Loader.loadFolderInfo(folder);
	}

	/**
	 * The order a listing shows its entries in: the newest first, the undated behind them by name.
	 *
	 * <p>
	 * Public, because a listing carrying link entries (issue #50) is ordered by the same rule: a
	 * shared album takes its place among one's own by its date, not behind them.
	 * </p>
	 *
	 * <p>
	 * A folder without a date sorts by name exactly as every folder did before issue #48. The date
	 * is the cheap one — a sidecar date or a date in the folder name — so this order costs a
	 * listing nothing but the sidecars it reads anyway, see {@link FolderInfo#getEffectiveDate()}.
	 * </p>
	 */
	public static final Comparator<FolderInfo> BY_DATE =
		Comparator.comparingLong((FolderInfo folder) -> folder.getEffectiveDate()).reversed()
			.thenComparing(FolderInfo::getName, String.CASE_INSENSITIVE_ORDER);

	public Resource lookup(PathInfo pathInfo) {
		_loader.processEvents(_cache);
		PathInfo folder = pathInfo.toFile().isDirectory() || pathInfo.isRoot() ? pathInfo : pathInfo.parent();
		if (_cache.getIfPresent(folder) != null && !_loader.current(folder)) {
			// A folder nobody watches (the watch could not be registered, or was lost) and whose
			// modification stamp moved: read again, see issue #235.
			invalidate(_cache, folder);
		}
		if (!pathInfo.toFile().exists() && Inboxes.isInbox(pathInfo.toFile())) {
			// The inbox of the space before its first upload, see issue #226: an empty inbox,
			// answered without writing and without caching anything -- the first upload creates
			// the folder.
			return Loader.createGenericAlbumInfo(pathInfo);
		}
		if (pathInfo.toFile().isDirectory()) {
			return _cache.getUnchecked(pathInfo);
		} else {
			AlbumInfo container = (AlbumInfo) _cache.getUnchecked(pathInfo.parent());
			ImagePart image = container.getImageByName().get(pathInfo.getName());
			if (image == null && isImage(pathInfo.toFile())) {
				// A photograph that arrived after its album was cached, before the directory
				// watcher reported it: the album is read again once rather than the file answered
				// as nothing.
				_cache.invalidate(pathInfo.parent());
				container = (AlbumInfo) _cache.getUnchecked(pathInfo.parent());
				image = container.getImageByName().get(pathInfo.getName());
			}
			if (image == null) {
				// The raw companion of a photograph is that photograph, and is answered with its
				// rights, its privacy and its rating, see issue #191.
				image = RawPairs.partOf(container, pathInfo.getName());
			}
			return image;
		}
	}

	static final class Loader extends CacheLoader<PathInfo, Resource> {
		private static final FileFilter DIRECTORIES = f -> f.isDirectory() && !LibraryFiles.isIgnored(f);

		private static final Logger LOG = Logger.getLogger(ResourceCache.class.getName());

		private final WatchService _watcher;

		/**
		 * The folder each watch is on; with {@link #_byPath}, {@link #_stamps} and
		 * {@link #_orphans} guarded by this loader, see issue #235.
		 */
		private final Map<WatchKey, PathInfo> _byKey = new HashMap<>();

		/** The watch on each watched folder. */
		private final Map<PathInfo, WatchKey> _byPath = new HashMap<>();

		/**
		 * The modification stamp of each held folder when it was loaded: what tells a folder that
		 * could not be watched has changed, see {@link #current(PathInfo)}.
		 */
		private final Map<PathInfo, Long> _stamps = new HashMap<>();

		/**
		 * Folders whose watch is kept although they left the cache, oldest first.
		 *
		 * <p>
		 * A folder dropped because it changed (its watch reported something, or the servlet wrote
		 * its sidecar) is read again with the next request; until then its watch stays, so that a
		 * copy going on in it, or a new folder appearing in it, is still noticed. Counted against the
		 * limit of watches and released oldest first, see {@link #_maxWatches}. A folder the cache
		 * evicts for room loses its watch at once.
		 * </p>
		 */
		private final Set<PathInfo> _orphans = new LinkedHashSet<>();

		/** How many folders are watched at most: the size of the cache. */
		private final int _maxWatches;

		/** The cache this loader fills, for the watcher's thread. */
		private volatile LoadingCache<PathInfo, Resource> _cache;

		/** Serialises the handling of watch events between the watcher's thread and requests. */
		private final Object _events = new Object();

		/** The thread waiting for watch events, started with the first watch. */
		private Thread _pump;

		/** What is taken over out of a photograph that is analysed here, see issue #129. */
		private final ImageData.Analysis _analysis;

		/** The zone of the space, <code>null</code> for the server's; see issue #183. */
		private final ZoneId _zone;

		/**
		 * Creates a {@link ResourceCache.Loader}.
		 */
		public Loader(ImageData.Analysis analysis, ZoneId zone, int maxWatches) throws IOException {
			_analysis = analysis == null ? ImageData.Analysis.NONE : analysis;
			_zone = zone;
			_maxWatches = maxWatches;
			_watcher = FileSystems.getDefault().newWatchService();
		}

		void attach(LoadingCache<PathInfo, Resource> cache) {
			_cache = cache;
		}

		synchronized int watchCount() {
			return _byPath.size();
		}

		/**
		 * Watches the given folder, before it is read, so that no change after the read is missed.
		 */
		private void watch(PathInfo path, File dir) {
			synchronized (this) {
				_stamps.put(path, dir.lastModified());
				_orphans.remove(path);
				try {
					WatchKey key = dir.toPath().register(_watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
					PathInfo before = _byKey.put(key, path);
					if (before != null && !before.equals(path)) {
						// The same directory under another name (a link): one watch, the latest name.
						_byPath.remove(before);
					}
					_byPath.put(path, key);
					trim();
					startPump();
				} catch (ClosedWatchServiceException ex) {
					// The cache is closed; nothing more is watched.
				} catch (NoSuchFileException ex) {
					// Gone meanwhile; the read says so.
				} catch (IOException ex) {
					unwatch(path);
					_stamps.put(path, dir.lastModified());
					reportUnwatched(dir, ex);
				}
			}
		}

		private static void reportUnwatched(File dir, IOException ex) {
			if (UNWATCHED_REPORTED.compareAndSet(false, true)) {
				LOG.log(Level.WARNING, "Cannot watch the folder '" + dir + "' for changes: " + ex.getMessage()
					+ ". The system's limit of directory watches is probably reached"
					+ " (Linux: raise fs.inotify.max_user_watches). A folder that cannot be watched is checked"
					+ " by its modification time whenever it is opened instead. Further folders that cannot be"
					+ " watched are logged at FINE only.");
			} else {
				LOG.log(Level.FINE, "Cannot watch the folder '" + dir + "' for changes: " + ex.getMessage());
			}
		}

		/** Releases watches of folders that left the cache while more are held than allowed. */
		private void trim() {
			java.util.Iterator<PathInfo> oldest = _orphans.iterator();
			while (_byPath.size() > _maxWatches && oldest.hasNext()) {
				PathInfo orphan = oldest.next();
				oldest.remove();
				cancel(orphan);
			}
		}

		/** Forgets the watch of the given folder and cancels it. */
		private void unwatch(PathInfo path) {
			_orphans.remove(path);
			_stamps.remove(path);
			cancel(path);
		}

		private void cancel(PathInfo path) {
			WatchKey key = _byPath.remove(path);
			if (key != null) {
				_byKey.remove(key);
				key.cancel();
			}
		}

		/**
		 * The cache dropped the given folder: its watch goes with it when it was evicted for room,
		 * and stays a while when it was dropped for a change, see {@link #_orphans} and issue #235.
		 */
		synchronized void removed(PathInfo path, boolean evicted) {
			WatchKey key = _byPath.get(path);
			if (!evicted && key != null && key.isValid()) {
				_orphans.add(path);
				trim();
			} else {
				unwatch(path);
			}
		}

		/**
		 * Whether what is held for the given folder can still be trusted without reading it again:
		 * its watch is alive, or its modification stamp is the one it was loaded with.
		 */
		synchronized boolean current(PathInfo path) {
			WatchKey key = _byPath.get(path);
			if (key != null && key.isValid() && !_orphans.contains(path)) {
				return true;
			}
			Long stamp = _stamps.get(path);
			return stamp != null && stamp.longValue() == path.toFile().lastModified();
		}

		/**
		 * Starts the thread that waits for watch events, so that a change is noticed when it happens
		 * and not only with the next request, see issue #235.
		 */
		private void startPump() {
			if (_pump != null) {
				return;
			}
			_pump = new Thread(this::pump, "resource-watch");
			_pump.setDaemon(true);
			_pump.start();
		}

		private void pump() {
			while (true) {
				WatchKey key;
				try {
					key = _watcher.take();
				} catch (ClosedWatchServiceException | InterruptedException ex) {
					return;
				}
				LoadingCache<PathInfo, Resource> cache = _cache;
				try {
					synchronized (_events) {
						handle(cache, key);
					}
				} catch (ClosedWatchServiceException ex) {
					return;
				} catch (RuntimeException ex) {
					LOG.log(Level.WARNING, "Cannot process a change of the library: " + ex.getMessage(), ex);
				}
			}
		}

		/** See {@link ResourceCache#zone()}. */
		ZoneId zone() {
			return _zone == null ? ZoneId.systemDefault() : _zone;
		}

		@Override
		public Resource load(PathInfo pathInfo) {
			if (pathInfo.isDirectory()) {
				Resource result = loadDir(pathInfo);
				return result;
			} else {
				throw new UnsupportedOperationException("Not a directory: " + pathInfo);
			}
		}

		/** Releases the directory watcher, see {@link ResourceCache#close()}. */
		void close() throws IOException {
			_watcher.close();
		}

		public void processEvents(LoadingCache<PathInfo, Resource> cache) {
			synchronized (_events) {
				while (true) {
					WatchKey key;
					try {
						key = _watcher.poll();
					} catch (ClosedWatchServiceException ex) {
						return;
					}
					if (key == null) {
						break;
					}
					handle(cache, key);
				}
			}
		}

		/**
		 * Handles what one watch reported: the folder is read again with the next request, its watch
		 * stays, and whoever does background work for the library hears of new photographs and new
		 * folders, see {@link FolderObserver}.
		 */
		private void handle(LoadingCache<PathInfo, Resource> cache, WatchKey key) {
			PathInfo path;
			synchronized (this) {
				path = _byKey.get(key);
			}
			List<WatchEvent<?>> events = key.pollEvents();
			if (path == null) {
				key.cancel();
				return;
			}
			File dir = path.toFile();
			boolean changed = false;
			for (WatchEvent<?> event : events) {
				if (event.kind() == OVERFLOW) {
					notice(dir, true);
					continue;
				}
				File child = new File(dir, ((Path) event.context()).toString());
				if (event.kind() == ENTRY_DELETE || LibraryFiles.isIgnored(child)) {
					continue;
				}
				if (event.kind() == ENTRY_CREATE && child.isDirectory()) {
					// Nobody watches inside a new folder yet: all of it is new.
					notice(child, true);
				} else if (!changed && ACCEPTED.contains(Util.suffix(child.getName()))) {
					changed = true;
					notice(dir, false);
				}
			}

			// The listing above describes this folder from its contents, see
			// ResourceCache#invalidate(PathInfo). The watch stays, see #_orphans.
			invalidate(cache, path);
			synchronized (this) {
				if (!key.reset() && path.equals(_byKey.get(key))) {
					// The folder is gone.
					unwatch(path);
				}
			}
		}

		private Resource loadDir(PathInfo path) {
			File dir = path.toFile();

			if (dir.isDirectory()) {
				watch(path, dir);
			}

			FolderResource resource = loadDirIndex(dir);

			File[] images = dir.listFiles(IMAGES);
			if (images == null) {
				return ErrorInfo.create().setMessage("Cannot list folder.");
			}

			if (resource instanceof AlbumInfo || images.length > 0
				// The inbox is an album however empty it is, see issue #226.
				|| (resource == null && Inboxes.isInbox(dir))) {
				AlbumInfo album = resource == null ? createGenericAlbumInfo(path) : (AlbumInfo) resource;

				if (album.getKind() == AlbumKind.COLLECTION) {
					// A collection holds references and no files, see issue #221: its parts are
					// what its sidecar says, never reconciled with the folder, and a file somebody
					// put there by hand is no part of it.
					UpdateTransient.updateTransient(album);
					album.setEffectiveDate(AlbumDate.ofAlbum(album, path.getName()).millis());
					return album;
				}

				loadAlbum(album, dir, images, _analysis, zone());

				// Derived on every read and never stored, see AlbumDate#clearDerived(FolderResource).
				album.setEffectiveDate(AlbumDate.ofAlbum(album, path.getName()).millis());

				// Who uploaded which photo, from the hash sidecar beside them, see issue #53.
				Contributors.derive(album, dir);

				if (images.length > 0) {
					// A folder that was copied in by hand is hashed when it is first looked at, see
					// issue #235.
					notice(dir, false);
				}
				return album;
			} else {
				ListingInfo listing = resource == null ? createGenericListingInfo(path) : (ListingInfo) resource;

				return loadListing(path, listing);
			}
		}

		private static FolderResource loadDirIndex(File dir) {
			File indexFile = new File(dir, "index.json");
			FolderResource resource;
			if (indexFile.exists()) {
				try {
					resource = loadJSON(indexFile, FolderResource::readFolderResource);
					if (resource instanceof AlbumInfo) {
						// Where the folder lies says whether it is the inbox, never the sidecar: an
						// album an older build flagged as one reads as an album, see issue #226.
						// A collection is the one kind a sidecar states, see issue #221.
						AlbumInfo album = (AlbumInfo) resource;
						AlbumKind kind = Inboxes.kindOf(dir);
						album.setKind(kind == AlbumKind.ALBUM && album.getKind() == AlbumKind.COLLECTION
							? AlbumKind.COLLECTION : kind);
					}
					dropZeroLocations(resource);
					dropIgnoredParts(resource);
				} catch (IOException ex) {
					LOG.log(Level.WARNING, "Faild to directory index: " + indexFile.getAbsolutePath(), ex);
					resource = null;
				}
			} else {
				resource = null;
			}
			return resource;
		}

		/**
		 * Forgets every <code>0/0</code> position a sidecar written before issue #161 stores.
		 *
		 * <p>
		 * Such a pair of zeroes is what a camera without a GPS fix wrote, never where the photograph
		 * was taken (see {@link ImageData#isZero(de.haumacher.imageServer.shared.model.GeoLocation)}),
		 * so it is read as no position at all: nothing is answered, and the next ordinary write of
		 * the album simply omits it. The sidecar itself is not rewritten here &mdash; reading never
		 * writes.
		 * </p>
		 */
		static void dropZeroLocations(FolderResource resource) {
			if (!(resource instanceof AlbumInfo)) {
				return;
			}
			for (AlbumPart part : ((AlbumInfo) resource).getParts()) {
				if (part instanceof ImagePart) {
					dropZeroLocation((ImagePart) part);
				} else if (part instanceof ImageGroup) {
					for (ImagePart member : ((ImageGroup) part).getImages()) {
						dropZeroLocation(member);
					}
				}
			}
		}

		/**
		 * Forgets every part a sidecar written before issue #173 lists for a file that is no part of
		 * the library now (a Mac's <code>._IMG_1.jpg</code>, which an earlier build showed as a
		 * photograph), see {@link LibraryFiles#isIgnored(String)}.
		 *
		 * <p>
		 * The file is never looked at again, so its part would be a tile without a picture: it is
		 * taken out of its group the way a move takes a photograph out (a group of one becomes a
		 * plain part), an index picture naming it is cleared, and the next ordinary write of the
		 * album simply omits it. The sidecar itself is not rewritten here &mdash; reading never
		 * writes.
		 * </p>
		 */
		static void dropIgnoredParts(FolderResource resource) {
			if (!(resource instanceof AlbumInfo)) {
				return;
			}
			AlbumInfo album = (AlbumInfo) resource;
			List<ImagePart> ignored = new ArrayList<>();
			for (AlbumPart part : album.getParts()) {
				if (part instanceof ImagePart) {
					addIgnored(ignored, (ImagePart) part);
				} else if (part instanceof ImageGroup) {
					for (ImagePart member : ((ImageGroup) part).getImages()) {
						addIgnored(ignored, member);
					}
				}
			}
			if (ignored.isEmpty()) {
				return;
			}
			UpdateTransient.updateTransient(album);
			for (ImagePart image : ignored) {
				MoveService.detach(album, image);
			}
			ThumbnailInfo cover = album.getIndexPicture();
			if (cover != null && LibraryFiles.isIgnored(cover.getImage())) {
				album.setIndexPicture(null);
			}
		}

		private static void addIgnored(List<ImagePart> result, ImagePart image) {
			if (LibraryFiles.isIgnored(image.getName())) {
				result.add(image);
			}
		}

		private static void dropZeroLocation(ImagePart image) {
			if (ImageData.isZero(image.getLocation())) {
				image.setLocation(null);
			}
			// A crop that is no region of the picture, or one of the whole picture, is read as none
			// (issue #212): a hand-edited or a client-written sidecar never makes a tile of nothing.
			if (image.getCrop() != null && (!de.haumacher.imageServer.Crops.isValid(image.getCrop())
				|| de.haumacher.imageServer.Crops.isWhole(image.getCrop()) || image.getKind() != de.haumacher.imageServer.shared.model.ImageKind.IMAGE)) {
				image.setCrop(null);
			}
		}

		private static Resource loadListing(PathInfo pathInfo, ListingInfo listing) {
			File dir = pathInfo.toFile();

			File[] dirs = dir.listFiles(DIRECTORIES);
			if (dirs == null) {
				return ErrorInfo.create().setMessage("Cannot list files.");
			}

			List<FolderInfo> folders = new ArrayList<>(dirs.length);
			for (File folder : dirs) {
				if (Inboxes.isInbox(folder)) {
					// The inbox is never a tile of a listing, for nobody: it is reached through
					// ?type=auth, see issue #226.
					continue;
				}
				folders.add(loadFolderInfo(folder));
			}
			folders.sort(ResourceCache.BY_DATE);

			listing.setFolders(folders);
			return listing;
		}

		private static FolderInfo loadFolderInfo(File folder) {
			String folderName = folder.getName();

			FolderInfo folderInfo = FolderInfo.create();
			folderInfo.setName(folderName);

			FolderResource folderResource = loadDirIndex(folder);

			// The sidecar and the name, and nothing else: a listing never opens the images of the
			// albums it shows, see FolderInfo#getEffectiveDate().
			folderInfo.setEffectiveDate(AlbumDate.ofFolder(folderResource, folderName).millis());

			if (folderResource != null) {
				if (folderResource instanceof AlbumInfo) {
					AlbumInfo albumInfo = (AlbumInfo) folderResource;
					// The folder's own sidecar says what it is, see FolderInfo#getKind(). An inbox
					// is a kind of album and says so, so that the tile can stand first and show no
					// date, see issue #131.
					boolean inbox = Inboxes.isInbox(albumInfo);
					folderInfo.setKind(inbox ? FolderKind.INBOX
						: albumInfo.getKind() == AlbumKind.COLLECTION ? FolderKind.COLLECTION : FolderKind.ALBUM);
					if (inbox) {
						// The pile of work an inbox tile says how much of is left, see issue #137.
						// Counted out of the sidecar that was read a line ago, so a listing pays
						// nothing for it and no image is opened.
						folderInfo.setImageCount(imageCount(albumInfo));
					}
					folderInfo.setTitle(albumInfo.getTitle());
					folderInfo.setSubTitle(albumInfo.getSubTitle());
					// A cropped photograph is shown cut, and the tile asks for its region, see
					// ThumbnailInfo#getCrop() and issue #212.
					folderInfo.setIndexPicture(de.haumacher.imageServer.Crops.withRegion(albumInfo,
						albumInfo.getIndexPicture()));
					return folderInfo;
				}
				else if (folderResource instanceof ListingInfo) {
					ListingInfo listingInfo = (ListingInfo) folderResource;
					folderInfo.setKind(FolderKind.FOLDER);
					folderInfo.setTitle(listingInfo.getTitle());
					// A folder holds no photograph of its own; it is shown by the picture of the
					// child it chose, resolved through the sidecars alone, see issue #110.
					ThumbnailInfo cover = FolderCover.of(folder, listingInfo);
					if (cover != null) {
						folderInfo.setIndexPicture(cover);
					}
					return folderInfo;
				}
			}

			if (folderInfo.getIndexPicture() == null) {
				File[] images = folder.listFiles(IMAGES);
				// The very rule Loader#loadDir(PathInfo) answers this folder by when it is opened:
				// without a sidecar, a folder holding images is an album and an empty one — or one
				// holding only further folders — is a folder of folders. See issue #133.
				folderInfo.setKind(images != null && images.length > 0 ? FolderKind.ALBUM : FolderKind.FOLDER);
				File indexPicture;
				ImageData imageData;
				if (images != null && images.length > 0) {
					indexPicture = images[0];

					double scale;
					try {
						imageData = ImageData.analyze(null, indexPicture);

						double width = imageData.getWidth();
						double height = imageData.getHeight();

						scale = width / height;
						double ty;
						if (scale < 1.0) {
							scale = 1.0 / scale;
							ty = (height - width) / height * 150;
						} else {
							ty = 0.0;
						}

						ThumbnailInfo thumbnail = ThumbnailInfo.create().setImage(indexPicture.getName()).setScale(scale);
						thumbnail.setTy(ty);
						// A folder without a sidecar carries no stored rotation: the frame this
						// crop was measured in is the one the server's own rendition has, which is
						// the file upright (ImageData applies the EXIF orientation to the
						// dimensions above). See ThumbnailInfo#getOrientation() and issue #115.
						thumbnail.setOrientation(Orientation.IDENTITY);
						folderInfo.setIndexPicture(thumbnail);
					} catch (ImageProcessingException
							| MetadataException | IOException ex) {
						LOG.log(Level.WARNING, "Cannot analyze index picture: " + indexPicture, ex);
						imageData = null;
					}
				} else {
					indexPicture = null;
					imageData = null;
				}

				Matcher matcher = DATE_PATTERN.matcher(folderName);
				if (matcher.find()) {
					int year = Integer.parseInt(matcher.group(1));
					int month = Integer.parseInt(matcher.group(2));
					int day = Integer.parseInt(matcher.group(3));

					folderInfo.setSubTitle(dateString(year, month, day));
					folderName = removeMatch(folderName, matcher);
				} else {
					if (imageData != null) {
						long imageDate = imageData.getDate();
						if (imageDate > 0L) {
							Date date = new Date(imageDate);
							folderInfo.setSubTitle(formatDate(date));
						}
					}
				}
				folderInfo.setTitle(fromTechnicalName(folderName));
			}
			return folderInfo;
		}

		/**
		 * How many photographs the given sidecar lists, the members of a group counted one by one.
		 *
		 * <p>
		 * An inbox stores no group &mdash; it is answered flat and by date, see issue #131 &mdash;
		 * but an album that was turned into one may hold some, and then the tile has to say how
		 * many pictures are waiting there and not how many bundles.
		 * </p>
		 */
		private static int imageCount(AlbumInfo album) {
			int result = 0;
			for (AlbumPart part : album.getParts()) {
				if (part instanceof ImagePart) {
					result++;
				} else if (part instanceof ImageGroup) {
					result += ((ImageGroup) part).getImages().size();
				}
			}
			return result;
		}

		private static String dateString(int year, int month, int day) {
			GregorianCalendar calendar = new GregorianCalendar();
			calendar.set(Calendar.YEAR, year);
			calendar.set(Calendar.MONTH, month - 1 + Calendar.JANUARY);
			calendar.set(Calendar.DAY_OF_MONTH, day);
			calendar.set(Calendar.HOUR, 0);
			calendar.set(Calendar.MINUTE, 0);
			calendar.set(Calendar.SECOND, 0);
			calendar.set(Calendar.MILLISECOND, 0);
			Date date = calendar.getTime();
			return formatDate(date);
		}

		private static String formatDate(Date date) {
			return DateFormat.getDateInstance(DateFormat.LONG).format(date);
		}

		private static String removeMatch(String name, Matcher match) {
			String before = name.substring(0, match.start()).trim();
			String after = name.substring(match.end()).trim();
			if (before.isEmpty()) {
				name = after;
			} else if (after.isEmpty()) {
				name = before;
			} else {
				name = before + " " + after;
			}
			return name;
		}

		private static String fromTechnicalName(String name) {
			return uppercaseStart(name.replaceAll("_+|(?<=\\p{javaLowerCase})(?=\\p{javaUpperCase})", " "));
		}

		private static String uppercaseStart(String expanded) {
			if (expanded.isEmpty()) {
				return expanded;
			}
			return Character.toUpperCase(expanded.charAt(0)) + expanded.substring(1);
		}

		private static AlbumInfo loadAlbum(AlbumInfo album, File dir, File[] files, ImageData.Analysis analysis,
				ZoneId zone) {
			// Update early to be able to match new images against existing image.
			UpdateTransient.updateTransient(album);

			// A raw and the JPEG of its name are one photograph, see issue #191: what the sidecar
			// says about them is made true to the files before anything new is analysed.
			RawPairs.Plan pairs = RawPairs.reconcile(album, files, file -> {
				try {
					return ImageData.analyze(album, file, analysis, zone);
				} catch (IOException | ImageProcessingException | MetadataException | RuntimeException ex) {
					LOG.log(Level.WARNING, "Cannot access '" + file + "': " + ex.getMessage(), ex);
					return null;
				}
			});

			List<ImageData> newImages = new ArrayList<>();
			for (File file : files) {
				String name = file.getName();

				ImagePart existing = album.getImageByName().get(name);
				if (existing != null || pairs.skips(name)) {
					// Already known, or the companion of a photograph.
					continue;
				}

				ImageData image;
				try {
					// Only a file the sidecar does not list gets here, which is what makes the
					// face import of issue #129 run exactly once per photograph.
					image = ImageData.analyze(album, file, analysis, zone);
				} catch (IOException | ImageProcessingException | MetadataException ex) {
					LOG.log(Level.WARNING, "Cannot access '" + file + "': " + ex.getMessage(), ex);
					continue;
				}
				image.setRaw(pairs.rawFor(name));

				newImages.add(image);
			}

			// What came through a share link is described so that the link shows it, see #214.
			Contributors.applyLinkLimits(newImages, dir);

			AlbumUtil.insertSorted(album, newImages);

			return album;
		}

		static AlbumInfo createGenericAlbumInfo(PathInfo pathInfo) {
			AlbumInfo album = AlbumInfo.create().setKind(Inboxes.kindOf(pathInfo.toFile()));
			String dirName = pathInfo.getName();

			Pattern prefixPattern = Pattern.compile("[-_\\.\\s0-9]*");
			Matcher matcher = prefixPattern.matcher(dirName);
			if (matcher.lookingAt()) {
				album.setTitle(fromTechnicalName(dirName.substring(matcher.end())));
				album.setSubTitle(dirName.substring(0, matcher.end()));
			} else {
				album.setTitle(dirName);
			}
			return album;
		}

		private static ListingInfo createGenericListingInfo(PathInfo path) {
			String listingName = path.getName();
			return ListingInfo.create().setTitle(fromTechnicalName(listingName));
		}

		interface LoaderFunction<T> {
			T load(JsonReader json) throws IOException;
		}

		private static <T> T loadJSON(File file, LoaderFunction<T> loader) throws IOException {
			try (InputStream in = new FileInputStream(file)) {
				JsonReader json = new JsonReader(new ReaderAdapter(new InputStreamReader(in, "utf-8")));
				return loader.load(json);
			}
		}
	}

}
