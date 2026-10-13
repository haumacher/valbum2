/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.cache;

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
import java.text.DateFormat;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
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

	/**
	 * How many bytes of heap one part of a cached album takes, the unit the cache is weighed in
	 * (see {@link #weight(Resource)} and issue #245).
	 *
	 * <p>
	 * Measured by <code>TestResourceCacheMemory#testHeapPerPart</code>: 200 albums of 50 photographs
	 * read from their sidecars (a name, a size, a date, a camera, a position, a comment, a label,
	 * three places, every fourth with a face tag), the used heap after <code>System.gc()</code>
	 * before and after the albums are in the cache. It said 1270 to 1290 bytes per part (the album,
	 * its map of parts and the cache's own entry included; a part with a name, a size and a date
	 * only took 313 bytes), on a JDK 21 with compressed
	 * references. This constant is that with a fifth more, so that the budget errs on the safe side.
	 * </p>
	 */
	public static final long BYTES_PER_PART = 1536;


	/**
	 * The caches alive in this process, which hear of every folder whose record the background
	 * wrote, see {@link #recorded(File)}.
	 */
	private static final Set<ResourceCache> LIVE = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/**
	 * Hears of a file that is left out of its album because it cannot be read, see
	 * {@link ResourceCache#setRefusals(Refusals)} and issue #252.
	 */
	public interface Refusals {

		/** Hears nothing. */
		Refusals NONE = (folder, reason) -> {
			// The log says it.
		};

		/**
		 * The given folder holds a file that was left out of it.
		 *
		 * @param reason
		 *        The file's name and why it cannot be read, <code>"name: reason"</code>.
		 */
		void refused(File folder, String reason);
	}

	/**
	 * A folder whose own <code>index.json</code> cannot be read, see issue #254.
	 *
	 * <p>
	 * Thrown by {@link ResourceCache#lookup(PathInfo)} for the folder and for every file in it, and
	 * by {@link ResourceCache#checkSidecar(File)} before anything would be written over the file.
	 * The servlet answers it with a refusal carrying {@link #getMessage()}: the album is never
	 * answered from its files alone, because a write of such an album would overwrite the titles,
	 * the order and the privacy the user may have in the file, and a photograph whose privacy cannot
	 * be read is no photograph to show.
	 * </p>
	 */
	public static final class UnreadableSidecar extends RuntimeException {

		private static final long serialVersionUID = 1L;

		private final File _folder;

		UnreadableSidecar(File folder, Throwable cause) {
			super(message(reason(cause)), cause);
			_folder = folder;
		}

		/** The folder whose <code>index.json</code> cannot be read. */
		public File getFolder() {
			return _folder;
		}

		/** The sentence a request touching a folder with an unreadable sidecar is refused with. */
		public static String message(String reason) {
			return "index.json of this album cannot be read: " + reason + " \u2014 repair or remove it";
		}

		private static String reason(Throwable cause) {
			String message = cause.getMessage();
			return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message.strip();
		}
	}

	/**
	 * Refuses with {@link UnreadableSidecar} where the given folder has an <code>index.json</code>
	 * that cannot be read; nothing happens where it has none or a readable one.
	 *
	 * <p>
	 * Asked before anything is written into the folder, its sidecar above all, see issue #254: a
	 * file that could not be read may hold what the user wrote, and is never overwritten.
	 * </p>
	 */
	public static void checkSidecar(File dir) throws UnreadableSidecar {
		readSidecar(dir);
	}

	/**
	 * The <code>index.json</code> of the given folder, <code>null</code> if it has none; the strict
	 * form of {@link #sidecar(File)}, see issue #254.
	 *
	 * @throws UnreadableSidecar
	 *         Where the folder has an <code>index.json</code> that cannot be read.
	 */
	public static FolderResource readSidecar(File dir) throws UnreadableSidecar {
		return Loader.readDirIndex(dir);
	}

	/**
	 * Forgets, in every cache of this process, what is held for the given folder and for the folder
	 * containing it: its analysis record was written (see {@link AnalysisRecord}), or a watch of
	 * the background reported a change in it. The next request reads both again, from their
	 * sidecars.
	 */
	public static void recorded(File folder) {
		File changed = folder.toPath().toAbsolutePath().normalize().toFile();
		File parent = changed.getParentFile();
		for (ResourceCache cache : LIVE) {
			cache.forget(changed, parent);
		}
	}

	private void forget(File folder, File parent) {
		LoadingCache<PathInfo, Resource> cache = _cache;
		for (PathInfo key : _loader.keysOf(folder)) {
			cache.invalidate(key);
		}
		if (parent != null) {
			for (PathInfo key : _loader.keysOf(parent)) {
				cache.invalidate(key);
			}
		}
	}

	private Loader _loader;

	private volatile LoadingCache<PathInfo, Resource> _cache;

	/** How many bytes of heap the albums held may take, see {@link #setBudget(long)}. */
	private long _budget;

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
		this(analysis, zone, defaultBudget());
	}

	/**
	 * Creates a {@link ResourceCache} holding albums up to the given number of bytes; for the
	 * tests, see issue #245.
	 */
	ResourceCache(ImageData.Analysis analysis, ZoneId zone, long budget) throws IOException {
		_loader = new Loader(analysis, zone);
		_budget = budget;
		_cache = build(budget);
		_loader.attach(_cache);
		LIVE.add(this);
	}

	private LoadingCache<PathInfo, Resource> build(long budget) {
		return CacheBuilder.newBuilder().maximumWeight(Math.max(1, budget / BYTES_PER_PART))
			.weigher((PathInfo path, Resource resource) -> weight(resource))
			.removalListener((RemovalNotification<PathInfo, Resource> removal) -> _loader.removed(removal.getKey()))
			.build(_loader);
	}

	/**
	 * The default of {@link #setBudget(long)}: an eighth of the heap, as for the gazetteer (see
	 * {@link de.haumacher.imageServer.places.Places#defaultBudget()}).
	 */
	public static long defaultBudget() {
		return Runtime.getRuntime().maxMemory() / 8;
	}

	/**
	 * Lets the albums held take the given number of bytes of heap, see issue #245 and
	 * <code>VALBUM_CACHE_MEMORY</code>.
	 *
	 * <p>
	 * Called at start-up, before the cache is used: what is held is carried over into a cache of
	 * the new size (Guava's cannot be resized), the albums that do not fit leave it as any evicted
	 * album does.
	 * </p>
	 */
	public synchronized void setBudget(long budget) {
		if (budget == _budget) {
			return;
		}
		LoadingCache<PathInfo, Resource> before = _cache;
		LoadingCache<PathInfo, Resource> after = build(budget);
		after.putAll(before.asMap());
		_budget = budget;
		_loader.attach(after);
		_cache = after;
	}

	/** How many bytes of heap the albums held may take, see {@link #setBudget(long)}. */
	public synchronized long getBudget() {
		return _budget;
	}

	/**
	 * What the given folder weighs in the cache, in parts of {@link #BYTES_PER_PART} bytes each:
	 * an album its photographs (the members of a group one by one), a listing its folders, and
	 * anything at least one.
	 */
	static int weight(Resource resource) {
		int result = 0;
		if (resource instanceof AlbumInfo) {
			for (AlbumPart part : ((AlbumInfo) resource).getParts()) {
				result += part instanceof ImageGroup ? Math.max(1, ((ImageGroup) part).getImages().size()) : 1;
			}
		} else if (resource instanceof ListingInfo) {
			result = ((ListingInfo) resource).getFolders().size();
		}
		return Math.max(1, result);
	}

	/** How many folders were read from disk so far, for the tests. */
	long loadCount() {
		return _loader.loadCount();
	}

	/** How many folders are held right now, for the tests. */
	long size() {
		return _cache.size();
	}

	/** What the folders held weigh together, in parts, for the tests; see {@link #weight(Resource)}. */
	long weight() {
		long result = 0;
		for (Resource resource : _cache.asMap().values()) {
			result += weight(resource);
		}
		return result;
	}

	/**
	 * Whether the background work of the space records the folders, see issue #253.
	 *
	 * <p>
	 * With a background (a running server, see
	 * {@link de.haumacher.imageServer.ImageServlet#startIndexing()}), a request is a pure read of
	 * the sidecars: it never lists a folder's files and never opens a photograph; what the
	 * background has not recorded yet is not answered. Without one &mdash; a servlet a test builds
	 * over a fixture, an offline tool &mdash; nobody else would ever record a folder, so a request
	 * records the folder it reads first, doing exactly the background's work for it, see
	 * {@link AnalysisRecord#update(File, AnalysisRecord.Analyst, boolean)}.
	 * </p>
	 */
	public void setBackground(boolean background) {
		_loader.setBackground(background);
	}

	/** Whether {@link #setBackground(boolean)} said that a background records the folders. */
	public boolean hasBackground() {
		return _loader.hasBackground();
	}

	/** The analysis a file of this space is analysed with: its part, as the background records it. */
	public AnalysisRecord.Analyst analyst() {
		return _loader::analyze;
	}

	/**
	 * Gives the photographs of every album read from now on their places, see issue #234 and
	 * {@link de.haumacher.imageServer.places.PhotoPlaces}; <code>null</code> (the default) for none.
	 */
	public void setPlaces(de.haumacher.imageServer.places.Places places) {
		_loader.setPlaces(places);
		_cache.invalidateAll();
	}

	/**
	 * Who hears of a file that is left out of its album because it cannot be read, see issue #252;
	 * <code>null</code> (the default) for nobody but the log.
	 */
	public void setRefusals(Refusals refusals) {
		_loader.setRefusals(refusals);
	}

	/** The gazetteer the places come from, <code>null</code> for none. */
	public de.haumacher.imageServer.places.Places places() {
		return _loader.places();
	}

	/**
	 * The zone a photograph of this space is dated in where it says neither its offset nor a GPS
	 * time, see issue #183.
	 */
	public ZoneId zone() {
		return _loader.zone();
	}

	/**
	 * Drops this cache: it no longer hears of recorded folders, see {@link #recorded(File)} and
	 * {@link de.haumacher.imageServer.ImageServlet#destroy()}. The watches of the library are the
	 * background's, see {@link de.haumacher.imageServer.pipeline.LibraryWatch}.
	 */
	public void close() throws IOException {
		LIVE.remove(this);
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
	 * background's watch reports the same change, but only some time after the write, and the
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
	 * The picture the given folder's tile shows in the listing above it, exactly as that listing
	 * answers it: its {@link ThumbnailInfo#getImage() image} relative to the folder, a crop where
	 * the photograph is cropped; <code>null</code> where the tile shows none. For the background
	 * work of issue #236, which makes the tile's preview before anybody opens the listing.
	 */
	public static ThumbnailInfo tilePicture(File folder) {
		return Loader.loadFolderInfo(folder).getIndexPicture();
	}

	/**
	 * The tile of the given folder in the listing above it, exactly as that listing answers it: its
	 * {@link FolderInfo#getIndexPicture() picture} (see {@link #tilePicture(File)}) and whether it is
	 * {@link FolderInfo#isStarred() starred}, which makes it a 2&times;2 tile asking for the large
	 * preview on a dense screen (issues #239, #241).
	 */
	public static FolderInfo tile(File folder) {
		return Loader.loadFolderInfo(folder);
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
	 * The order an undated listing shows its entries in: the newest first, the undated behind them
	 * by name, see {@link #listingOrder(AlbumDate)}.
	 *
	 * <p>
	 * A folder without a date sorts by name exactly as every folder did before issue #48. The date
	 * is the cheap one — a sidecar date or a date in the folder name — so this order costs a
	 * listing nothing but the sidecars it reads anyway, see {@link FolderInfo#getEffectiveDate()}.
	 * </p>
	 */
	public static final Comparator<FolderInfo> NEWEST_FIRST =
		Comparator.comparing((FolderInfo folder) -> folder.getEffectiveDate() == 0L)
			.thenComparing(Comparator.comparingLong(FolderInfo::getEffectiveDate).reversed())
			.thenComparing(FolderInfo::getName, String.CASE_INSENSITIVE_ORDER);

	/**
	 * The order a dated listing shows its entries in: the oldest first, the undated behind them by
	 * name, see {@link #listingOrder(AlbumDate)}.
	 */
	public static final Comparator<FolderInfo> OLDEST_FIRST =
		Comparator.comparing((FolderInfo folder) -> folder.getEffectiveDate() == 0L)
			.thenComparingLong(FolderInfo::getEffectiveDate)
			.thenComparing(FolderInfo::getName, String.CASE_INSENSITIVE_ORDER);

	/**
	 * The order a listing shows its entries in, chosen by the listing's own date, see issue #270.
	 *
	 * <p>
	 * A listing whose own folder has a date (a year folder <code>2025</code>, a month folder: the
	 * date it sorts by in the listing above it, {@link AlbumDate#ofFolder(FolderResource, String)})
	 * is read through its period like an album: {@link #OLDEST_FIRST oldest first}. An undated
	 * listing (the root of a space, <code>Trips</code>) keeps {@link #NEWEST_FIRST newest first}, so
	 * the start page opens on the latest year. Undated entries stand behind the dated ones by name in
	 * both. The app mirrors this rule for an older server and its offline cache
	 * (<code>sortedFolders</code> in <code>album_date.dart</code>).
	 * </p>
	 *
	 * @param listingDate
	 *        The date of the listing's own folder, {@link AlbumDate#NONE} for none.
	 */
	public static Comparator<FolderInfo> listingOrder(AlbumDate listingDate) {
		return listingDate.isSet() ? OLDEST_FIRST : NEWEST_FIRST;
	}

	/**
	 * The date of the listing at the given path, the one {@link #listingOrder(AlbumDate)} chooses by.
	 *
	 * <p>
	 * The root of a space is undated whatever its folder on disk is called: it is no period of time
	 * but the whole library, and the app, which sees it as the empty path, cannot know that name.
	 * </p>
	 *
	 * @param path
	 *        The listing's folder.
	 * @param sidecar
	 *        Its own <code>index.json</code>, <code>null</code> if it has none.
	 */
	public static AlbumDate listingDate(PathInfo path, FolderResource sidecar) {
		return path.isRoot() ? AlbumDate.NONE : AlbumDate.ofFolder(sidecar, path.getName());
	}

	public Resource lookup(PathInfo pathInfo) {
		LoadingCache<PathInfo, Resource> cache = _cache;
		PathInfo folder = pathInfo.toFile().isDirectory() || pathInfo.isRoot() ? pathInfo : pathInfo.parent();
		if (!_loader.hasBackground()) {
			// Nobody else records the folder, see #setBackground(boolean).
			_loader.recordInline(folder.toFile(), true);
		}
		if (cache.getIfPresent(folder) != null && !_loader.current(folder)) {
			// A sidecar changed behind the cache's back (a hand-edited index.json, a record written
			// in another process): read again.
			invalidate(cache, folder);
		} else if (_loader.placesOutdated(folder)) {
			// A photograph of this album waits for its places, and the gazetteer may have more to
			// say now (a country arrived, a download failed): read again, see issue #234. Only the
			// album, not the listing above it: a listing shows no places.
			cache.invalidate(folder);
		}
		if (!pathInfo.toFile().exists() && Inboxes.isInbox(pathInfo.toFile())) {
			// The inbox of the space before its first upload, see issue #226: an empty inbox,
			// answered without writing and without caching anything -- the first upload creates
			// the folder.
			return Loader.createGenericAlbumInfo(pathInfo);
		}
		if (pathInfo.toFile().isDirectory()) {
			return load(cache, pathInfo);
		} else {
			AlbumInfo container = albumHolding(pathInfo);
			if (container == null) {
				return null;
			}
			ImagePart image = container.getImageByName().get(pathInfo.getName());
			if (image == null) {
				// The raw companion of a photograph is that photograph, and is answered with its
				// rights, its privacy and its rating, see issue #191.
				image = RawPairs.partOf(container, pathInfo.getName());
			}
			// A file the background has not recorded yet is nothing yet, see issue #253.
			return image;
		}
	}

	/**
	 * The album the given file lies in, <code>null</code> where its folder holds no media.
	 *
	 * <p>
	 * A folder read while it held no photograph is cached as a listing; the first media file that
	 * the background records in it afterwards turns it into an album, which the cache may not have
	 * heard of yet when that file is asked for. The folder is read again once then (a pure read of
	 * its sidecars); a folder that still holds no media answers <code>null</code> rather than a
	 * cast that fails.
	 * </p>
	 */
	private AlbumInfo albumHolding(PathInfo pathInfo) {
		PathInfo folder = pathInfo.parent();
		LoadingCache<PathInfo, Resource> cache = _cache;
		Resource container = load(cache, folder);
		if (container instanceof AlbumInfo) {
			return (AlbumInfo) container;
		}
		cache.invalidate(folder);
		container = load(cache, folder);
		return container instanceof AlbumInfo ? (AlbumInfo) container : null;
	}

	/**
	 * What the cache holds for the given folder, read where it holds nothing; a folder whose
	 * sidecar cannot be read is refused with {@link UnreadableSidecar}, never held, so that the
	 * repaired file is read on the next request, see issue #254.
	 */
	private static Resource load(LoadingCache<PathInfo, Resource> cache, PathInfo folder) {
		try {
			return cache.getUnchecked(folder);
		} catch (com.google.common.util.concurrent.UncheckedExecutionException ex) {
			if (ex.getCause() instanceof UnreadableSidecar) {
				throw (UnreadableSidecar) ex.getCause();
			}
			throw ex;
		}
	}

	static final class Loader extends CacheLoader<PathInfo, Resource> {
		private static final FileFilter DIRECTORIES = f -> f.isDirectory() && !LibraryFiles.isIgnored(f);

		private static final Logger LOG = Logger.getLogger(ResourceCache.class.getName());

		/** How many folders were read from disk, for the tests. */
		private final AtomicLong _loads = new AtomicLong();

		/**
		 * The stamp of the sidecars of each held folder when it was loaded: what tells that one
		 * changed behind the cache's back, see {@link #current(PathInfo)}.
		 */
		private final Map<PathInfo, String> _stamps = new java.util.concurrent.ConcurrentHashMap<>();

		/** The cache this loader fills. */
		private volatile LoadingCache<PathInfo, Resource> _cache;

		/**
		 * The keys loaded, by the absolute path of their folder: what {@link ResourceCache#recorded(File)}
		 * finds the folder by without looking at every key.
		 */
		private final Map<String, Set<PathInfo>> _keys = new java.util.concurrent.ConcurrentHashMap<>();

		/** What is taken over out of a photograph that is analysed here, see issue #129. */
		private final ImageData.Analysis _analysis;

		/** The zone of the space, <code>null</code> for the server's; see issue #183. */
		private final ZoneId _zone;

		/** Where the places of a photograph come from, see issue #234; <code>null</code> for none. */
		private volatile de.haumacher.imageServer.places.Places _places;

		/**
		 * The albums held whose photographs wait for their places, with the
		 * {@link de.haumacher.imageServer.places.Places#epoch()} they were read at.
		 */
		private final Map<PathInfo, Long> _placesPending = new java.util.concurrent.ConcurrentHashMap<>();

		/** Who hears of a file found unreadable by a request's own recording, see #recordInline. */
		private volatile Refusals _refusals = Refusals.NONE;

		/** See {@link ResourceCache#setBackground(boolean)}. */
		private volatile boolean _background;

		void setPlaces(de.haumacher.imageServer.places.Places places) {
			_places = places;
			_placesPending.clear();
		}

		void setRefusals(Refusals refusals) {
			_refusals = refusals == null ? Refusals.NONE : refusals;
		}

		void setBackground(boolean background) {
			_background = background;
		}

		boolean hasBackground() {
			return _background;
		}

		/** The part the given file is analysed into, as {@link AnalysisRecord} records it. */
		ImagePart analyze(File file) throws ImageProcessingException, IOException, MetadataException {
			return ImageData.analyze(null, file, _analysis, zone());
		}

		/**
		 * Records the given folder in the calling thread, where no background does, see
		 * {@link ResourceCache#setBackground(boolean)}: the background's own work, see
		 * {@link AnalysisRecord#update(File, AnalysisRecord.Analyst, boolean)}. A file found
		 * unreadable is said to the {@link Refusals} once per state of the file.
		 *
		 * @param children
		 *        Whether the folders in it are recorded as well where it is a folder of folders: their
		 *        tiles are described from their records.
		 */
		void recordInline(File dir, boolean children) {
			if (!dir.isDirectory()) {
				return;
			}
			try {
				AnalysisRecord.Result result = AnalysisRecord.update(dir, this::analyze, true);
				for (String failure : result.getFresh()) {
					try {
						_refusals.refused(dir, failure);
					} catch (RuntimeException ex) {
						LOG.log(Level.WARNING, "Cannot report '" + failure + "': " + ex.getMessage(), ex);
					}
				}
			} catch (IOException | RuntimeException ex) {
				LOG.log(Level.WARNING, "Cannot record the folder '" + dir + "': " + ex.getMessage(), ex);
				return;
			}
			if (!children) {
				return;
			}
			try {
				FolderResource sidecar = readDirIndex(dir);
				if (sidecar instanceof AlbumInfo) {
					return;
				}
				if (sidecar == null) {
					AnalysisRecord record = AnalysisRecord.read(dir);
					if (record != null && !record.isEmpty()) {
						return;
					}
				}
			} catch (RuntimeException ex) {
				// A broken sidecar: the request says so.
				return;
			}
			File[] folders = dir.listFiles(DIRECTORIES);
			if (folders == null) {
				return;
			}
			for (File folder : folders) {
				FolderResource sidecar;
				try {
					sidecar = readDirIndex(folder);
				} catch (RuntimeException ex) {
					sidecar = null;
				}
				// The tile of an album that chose no cover is described from its record, see
				// issue #260.
				if (!(sidecar instanceof AlbumInfo) || (((AlbumInfo) sidecar).getKind() == AlbumKind.ALBUM
					&& ((AlbumInfo) sidecar).getIndexPicture() == null)) {
					recordInline(folder, false);
				}
			}
		}

		de.haumacher.imageServer.places.Places places() {
			return _places;
		}

		/**
		 * Whether the given album was read while some of its photographs waited for their places,
		 * and the gazetteer changed since.
		 */
		boolean placesOutdated(PathInfo path) {
			Long epoch = _placesPending.get(path);
			de.haumacher.imageServer.places.Places places = _places;
			return epoch != null && places != null && places.epoch() != epoch.longValue();
		}

		/**
		 * Creates a {@link ResourceCache.Loader}.
		 */
		public Loader(ImageData.Analysis analysis, ZoneId zone) {
			_analysis = analysis == null ? ImageData.Analysis.NONE : analysis;
			_zone = zone;
		}

		void attach(LoadingCache<PathInfo, Resource> cache) {
			_cache = cache;
		}

		/** The cache dropped the given folder. */
		void removed(PathInfo path) {
			_placesPending.remove(path);
			_stamps.remove(path);
			Set<PathInfo> keys = _keys.get(keyOf(path.toFile()));
			if (keys != null) {
				keys.remove(path);
			}
		}

		private static String keyOf(File folder) {
			return folder.toPath().toAbsolutePath().normalize().toString();
		}

		/** The keys loaded for the given folder. */
		List<PathInfo> keysOf(File folder) {
			Set<PathInfo> keys = _keys.get(keyOf(folder));
			return keys == null ? java.util.Collections.emptyList() : new ArrayList<>(keys);
		}

		/**
		 * Whether what is held for the given folder can still be trusted without reading it again:
		 * none of the files it was read from changed since. The background tells the cache of every
		 * record it writes (see {@link ResourceCache#recorded(File)}) and the servlet of every
		 * sidecar it writes; this catches what neither said, a hand-edited <code>index.json</code>
		 * above all.
		 */
		boolean current(PathInfo path) {
			String stamp = _stamps.get(path);
			return stamp != null && stamp.equals(stamp(path.toFile()));
		}

		/**
		 * The stamp of what a folder is read from: the folder itself (an entry added or removed),
		 * its <code>index.json</code>, its analysis record and its hash sidecar (who contributed
		 * what, see issue #53).
		 */
		private static String stamp(File dir) {
			return Stamps.of(dir) + "|" + Stamps.of(new File(dir, "index.json")) + "|" + AnalysisRecord.stamp(dir)
				+ "|" + Stamps.of(new File(dir, de.haumacher.imageServer.upload.HashCache.FILE_NAME));
		}

		/** See {@link ResourceCache#zone()}. */
		ZoneId zone() {
			return _zone == null ? ZoneId.systemDefault() : _zone;
		}

		long loadCount() {
			return _loads.get();
		}

		@Override
		public Resource load(PathInfo pathInfo) {
			_loads.incrementAndGet();
			if (pathInfo.isDirectory()) {
				Resource result = loadDir(pathInfo);
				return result;
			} else {
				throw new UnsupportedOperationException("Not a directory: " + pathInfo);
			}
		}

		/**
		 * Reads a folder from its sidecars, see issue #253: its <code>index.json</code> and its
		 * {@link AnalysisRecord}. Nothing is listed but the folders of a folder of folders, no
		 * photograph is opened, nothing is written and nobody is told: a pure read.
		 */
		private Resource loadDir(PathInfo path) {
			File dir = path.toFile();

			// Taken before anything is read: a change while reading reads the folder again.
			_stamps.put(path, stamp(dir));
			_keys.computeIfAbsent(keyOf(dir), x -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(path);

			FolderResource resource;
			try {
				resource = readDirIndex(dir);
			} catch (UnreadableSidecar ex) {
				// Never held: the next request reads the file again, repaired or not.
				_stamps.remove(path);
				throw ex;
			}
			if (resource instanceof ListingInfo) {
				return loadListing(path, (ListingInfo) resource);
			}

			AlbumInfo album = (AlbumInfo) resource;
			if (album != null && (album.getKind() == AlbumKind.COLLECTION || album.getKind() == AlbumKind.SEARCH)) {
				// A collection holds references and no files, see issue #221: its parts are
				// what its sidecar says, never reconciled with the folder, and a file somebody
				// put there by hand is no part of it. A saved search (issue #227) holds no
				// parts at all: they are found whenever it is asked.
				UpdateTransient.updateTransient(album);
				album.setEffectiveDate(AlbumDate.ofAlbum(album, path.getName()).millis());
				return album;
			}

			AnalysisRecord record = AnalysisRecord.read(dir);
			if (album == null) {
				if (record != null && record.isEmpty() && !Inboxes.isInbox(dir)) {
					// The background found no photograph here: a folder of folders.
					return loadListing(path, createGenericListingInfo(path));
				}
				// Holding photographs, the inbox (an album however empty, see issue #226), or not
				// recorded yet: an album, being prepared in the last case.
				album = createGenericAlbumInfo(path);
			}

			loadAlbum(album, dir, record);

			// Derived on every read and never stored, see AlbumDate#clearDerived(FolderResource).
			album.setEffectiveDate(AlbumDate.ofAlbum(album, path.getName()).millis());

			// Who uploaded which photo, from the hash sidecar beside them, see issue #53.
			Contributors.derive(album, dir);

			// Where each photo was taken, from the gazetteer, see issue #234. The epoch is taken
			// before the lookups, so that a file arriving during them reads the album again.
			de.haumacher.imageServer.places.Places places = _places;
			if (places != null) {
				long epoch = places.epoch();
				if (de.haumacher.imageServer.places.PhotoPlaces.derive(album, places)) {
					_placesPending.put(path, Long.valueOf(epoch));
				} else {
					_placesPending.remove(path);
				}
			}
			return album;
		}

		/**
		 * The <code>index.json</code> of the given folder, <code>null</code> where it has none or
		 * one that cannot be read (logged); see {@link #readDirIndex(File)} for the strict read.
		 */
		private static FolderResource loadDirIndex(File dir) {
			try {
				return readDirIndex(dir);
			} catch (UnreadableSidecar ex) {
				LOG.log(Level.WARNING, "Cannot read the sidecar of '" + dir.getAbsolutePath() + "': "
					+ ex.getCause().getMessage(), ex.getCause());
				return null;
			}
		}

		/**
		 * The <code>index.json</code> of the given folder, <code>null</code> where it has none.
		 *
		 * @throws UnreadableSidecar
		 *         Where the file is there but cannot be read: whatever the reader fails with, a
		 *         malformed file and a field of the wrong type alike, see issue #254.
		 */
		static FolderResource readDirIndex(File dir) throws UnreadableSidecar {
			File indexFile = new File(dir, "index.json");
			if (!indexFile.exists()) {
				return null;
			}
			FolderResource resource;
			try {
				resource = loadJSON(indexFile, FolderResource::readFolderResource);
			} catch (IOException | RuntimeException ex) {
				throw new UnreadableSidecar(dir, ex);
			}
			if (resource == null) {
				throw new UnreadableSidecar(dir, new IOException("it describes no album and no folder"));
			}
			if (resource instanceof AlbumInfo) {
				// Where the folder lies says whether it is the inbox, never the sidecar: an
				// album an older build flagged as one reads as an album, see issue #226.
				// A collection is the one kind a sidecar states, see issue #221.
				AlbumInfo album = (AlbumInfo) resource;
				AlbumKind kind = Inboxes.kindOf(dir);
				// So is a saved search, see issue #227.
				album.setKind(kind == AlbumKind.ALBUM
					&& (album.getKind() == AlbumKind.COLLECTION || album.getKind() == AlbumKind.SEARCH)
						? album.getKind() : kind);
			}
			dropZeroLocations(resource);
			dropIgnoredParts(resource);
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
			folders.sort(listingOrder(listingDate(pathInfo, listing)));

			listing.setFolders(folders);
			return listing;
		}

		/**
		 * The tile of the given folder in the listing above it.
		 *
		 * <p>
		 * One broken folder never fails the listing it stands in (issue #252): where the tile
		 * cannot be built for whatever reason, the folder is shown all the same, by its name, see
		 * {@link #nameOnly(File)}.
		 * </p>
		 */
		static FolderInfo loadFolderInfo(File folder) {
			try {
				return describeFolder(folder);
			} catch (RuntimeException ex) {
				LOG.log(Level.WARNING, "Cannot describe the folder '" + folder + "', shown by its name: "
					+ ex.getMessage(), ex);
				return nameOnly(folder);
			}
		}

		/**
		 * The tile of a folder that could not be described: its name as its title, as for a folder
		 * without a sidecar, and nothing that would have to be read out of a file.
		 */
		private static FolderInfo nameOnly(File folder) {
			String folderName = folder.getName();
			FolderInfo folderInfo = FolderInfo.create();
			folderInfo.setName(folderName);
			try {
				folderInfo.setEffectiveDate(AlbumDate.ofFolder(null, folderName).millis());
				AnalysisRecord record = AnalysisRecord.read(folder);
				folderInfo.setKind(record != null && record.isEmpty() ? FolderKind.FOLDER : FolderKind.ALBUM);
				titleFromName(folderInfo, folderName, 0L);
			} catch (RuntimeException ex) {
				LOG.log(Level.WARNING, "Cannot read even the name of the folder '" + folder + "': "
					+ ex.getMessage(), ex);
				folderInfo.setTitle(folderName);
			}
			return folderInfo;
		}

		private static FolderInfo describeFolder(File folder) {
			String folderName = folder.getName();

			FolderInfo folderInfo = FolderInfo.create();
			folderInfo.setName(folderName);

			// Strict: a folder whose sidecar cannot be read is a tile by its name alone, never one
			// built from its files (whose privacy that sidecar would say), see issue #254.
			FolderResource folderResource = readDirIndex(folder);

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
						: albumInfo.getKind() == AlbumKind.COLLECTION ? FolderKind.COLLECTION
							: albumInfo.getKind() == AlbumKind.SEARCH ? FolderKind.SEARCH : FolderKind.ALBUM);
					if (inbox) {
						// The pile of work an inbox tile says how much of is left, see issue #137.
						// Counted out of the sidecar that was read a line ago, so a listing pays
						// nothing for it and no image is opened.
						folderInfo.setImageCount(imageCount(albumInfo));
					}
					folderInfo.setTitle(albumInfo.getTitle());
					folderInfo.setSubTitle(albumInfo.getSubTitle());
					// Whether the tile stands out, read from the sidecar like the title, see
					// issue #239.
					folderInfo.setStarred(albumInfo.isStarred());
					ThumbnailInfo chosen = albumInfo.getIndexPicture();
					if (chosen != null) {
						// A cropped photograph is shown cut, and the tile asks for its region, see
						// ThumbnailInfo#getCrop() and issue #212.
						folderInfo.setIndexPicture(de.haumacher.imageServer.Crops.withRegion(albumInfo, chosen));
					} else if (albumInfo.getKind() == AlbumKind.ALBUM) {
						// An album that chose no cover is shown by the same rule as one without a
						// sidecar, never by none: writing a sidecar (a star, a rating, a title) must
						// not take the picture off its tile, see issue #260. A collection and a
						// saved search are given theirs where they are answered.
						folderInfo.setIndexPicture(defaultCover(folder, albumInfo));
					}
					return folderInfo;
				}
				else if (folderResource instanceof ListingInfo) {
					ListingInfo listingInfo = (ListingInfo) folderResource;
					folderInfo.setKind(FolderKind.FOLDER);
					folderInfo.setTitle(listingInfo.getTitle());
					folderInfo.setStarred(listingInfo.isStarred());
					// A folder holds no photograph of its own; it is shown by the picture of the
					// child it chose, resolved through the sidecars alone, see issue #110.
					ThumbnailInfo cover = FolderCover.of(folder, listingInfo);
					if (cover != null) {
						folderInfo.setIndexPicture(cover);
					}
					return folderInfo;
				}
			}

			// No sidecar: what the background recorded of the folder, and never a photograph opened,
			// see issue #253. A folder not recorded yet is a tile by its name; one holding no
			// photograph is a folder of folders, as Loader#loadDir(PathInfo) answers it.
			AnalysisRecord record = AnalysisRecord.read(folder);
			if (record == null) {
				return nameOnly(folder);
			}
			folderInfo.setKind(record.isEmpty() ? FolderKind.FOLDER : FolderKind.ALBUM);
			// The album the folder is answered as (see #loadDir(PathInfo)), shown by the rule of
			// an album that chose no cover, see issue #260.
			AlbumInfo album = loadAlbum(AlbumInfo.create().setKind(Inboxes.kindOf(folder)), folder, record);
			ImagePart picture = de.haumacher.imageServer.PrivacyFilter.defaultImage(album);
			if (picture != null) {
				folderInfo.setIndexPicture(de.haumacher.imageServer.PrivacyFilter.defaultCover(album));
			}
			titleFromName(folderInfo, folderName, picture == null ? 0L : picture.getDate());
			return folderInfo;
		}

		/**
		 * The picture the tile of the given album shows where its sidecar chose none, see
		 * {@link de.haumacher.imageServer.PrivacyFilter#defaultCover(AlbumInfo)} and issue #260.
		 *
		 * <p>
		 * Read from the sidecar and the analysis record exactly as the album itself is (see
		 * {@link #loadAlbum(AlbumInfo, File, AnalysisRecord)}), so the tile shows what opening the
		 * album would show first: no image is opened, and nothing is written. A folder the
		 * background has not recorded yet has no picture yet.
		 * </p>
		 *
		 * @param sidecar
		 *        The album's sidecar as just read; completed by its record here.
		 */
		private static ThumbnailInfo defaultCover(File folder, AlbumInfo sidecar) {
			AnalysisRecord record = AnalysisRecord.read(folder);
			if (record == null) {
				return null;
			}
			return de.haumacher.imageServer.PrivacyFilter.defaultCover(loadAlbum(sidecar, folder, record));
		}

		/**
		 * The title and subtitle of the tile of a folder without a sidecar: the name, a date in it
		 * as the subtitle, else the given date of its picture, if any (<code>0</code> for none).
		 */
		private static void titleFromName(FolderInfo folderInfo, String folderName, long imageDate) {
			Matcher matcher = DATE_PATTERN.matcher(folderName);
			if (matcher.find()) {
				int year = Integer.parseInt(matcher.group(1));
				int month = Integer.parseInt(matcher.group(2));
				int day = Integer.parseInt(matcher.group(3));

				folderInfo.setSubTitle(dateString(year, month, day));
				folderName = removeMatch(folderName, matcher);
			} else {
				if (imageDate > 0L) {
					Date date = new Date(imageDate);
					folderInfo.setSubTitle(formatDate(date));
				}
			}
			folderInfo.setTitle(fromTechnicalName(folderName));
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

		/**
		 * Completes the given album from the given record of its folder, see issue #253.
		 *
		 * <p>
		 * The sidecar's parts are the truth for the files it lists; a file the sidecar does not list
		 * is added with the part the background analysed, or as a broken part where it cannot be
		 * read; a part whose file the record no longer holds is left out. A folder the background has
		 * not recorded yet is answered with what its sidecar says, and as being prepared; so is one
		 * holding a file only seen, not analysed yet.
		 * </p>
		 */
		private static AlbumInfo loadAlbum(AlbumInfo album, File dir, AnalysisRecord record) {
			// Update early to be able to match new images against existing image.
			UpdateTransient.updateTransient(album);

			if (record == null) {
				album.setPreparing(true);
				return album;
			}

			File[] files = new File[record.entries().size()];
			int index = 0;
			for (AnalysisRecord.Entry entry : record.entries()) {
				files[index++] = new File(dir, entry.getName());
			}

			// A raw and the JPEG of its name are one photograph, see issue #191: what the sidecar
			// says about them is made true to the files before anything new is added. A JPEG that
			// takes over the part of its raw brings what the background analysed.
			RawPairs.Plan pairs = RawPairs.reconcile(album, files, file -> {
				AnalysisRecord.Entry entry = record.get(file.getName());
				return entry != null && entry.isAnalysed() ? entry.getPart() : null;
			});

			// A part whose file is gone, as far as the background knows: left out.
			boolean dropped = false;
			for (ImagePart image : images(album)) {
				if (record.get(image.getName()) == null) {
					MoveService.detach(album, image);
					dropped = true;
				}
			}
			if (dropped) {
				UpdateTransient.updateTransient(album);
			}

			List<ImagePart> newImages = new ArrayList<>();
			boolean pending = false;
			for (AnalysisRecord.Entry entry : record.entries()) {
				String name = entry.getName();
				ImagePart stored = album.getImageByName().get(name);
				if (stored != null) {
					if (BrokenParts.hasNoPicture(stored)) {
						// Stored while its file was broken, see BrokenParts#clear(AlbumInfo).
						if (entry.isBroken()) {
							stored.setBroken(entry.getFailure());
							stored.setFileSize(entry.getSize());
						} else if (entry.isAnalysed()) {
							BrokenParts.adopt(stored, entry.getPart());
						} else {
							pending = true;
						}
					}
					continue;
				}
				if (pairs.skips(name)) {
					// The companion of a photograph.
					continue;
				}
				if (entry.isAnalysed()) {
					ImagePart image = entry.getPart();
					image.setRaw(pairs.rawFor(name));
					newImages.add(image);
				} else if (entry.isBroken()) {
					newImages.add(entry.brokenPart());
				} else {
					// Seen, not analysed yet.
					pending = true;
				}
			}

			// What came through a share link is described so that the link shows it, see #214.
			Contributors.applyLinkLimits(newImages, dir);

			AlbumUtil.insertSorted(album, newImages);
			album.setPreparing(pending);
			return album;
		}

		/** Every photograph of the given album, the members of a group one by one. */
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
