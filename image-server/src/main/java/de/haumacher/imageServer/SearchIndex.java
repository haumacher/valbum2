/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.PhotoSearch.Album;
import de.haumacher.imageServer.PhotoSearch.Match;
import de.haumacher.imageServer.PhotoSearch.Photo;
import de.haumacher.imageServer.PhotoSearch.Test;
import de.haumacher.imageServer.pipeline.FolderPipeline;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Resource;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * What the search of issue #227 asks of every photograph of one space, kept in memory so that a
 * query never reads a sidecar.
 *
 * <p>
 * Per photograph a {@link Photo}: its name, date, confirmed persons, labels, place ids, camera,
 * kind, rating and privacy, and its description; per album its title and subtitle. Built from
 * what the {@link de.haumacher.imageServer.cache.ResourceCache} answers for each folder &mdash;
 * the sidecar with the places derived on read &mdash;, so it answers exactly what a scan of the
 * albums answers. <b>Nothing is stored on disk</b>: after a restart it is built again by the
 * background work of the space.
 * </p>
 *
 * <h2>Staying current</h2>
 *
 * <ul>
 * <li><b>Every sidecar the server writes</b> goes through <code>ImageServlet#storeSidecar</code>,
 * which tells the index of its space ({@link #sidecarWritten(File)}), the one place, as
 * <code>HashCache#flush()</code> tells the hash index: the album's next search reads it again. A
 * PUT, a label change, a tagged face, a crop, a move, a delete, an addition to a collection all
 * write there.</li>
 * <li><b>Everything else</b> &mdash; a file copied in, a folder removed by hand &mdash; changes the
 * modification stamp of a folder or of its <code>index.json</code>. A search compares both stamps of
 * every folder it looks at and reads a changed folder again, its new subfolders with it; a folder
 * that is gone is dropped with everything below it.</li>
 * <li>As a step of the {@link FolderPipeline} (after the places of issue #234) every folder the
 * background work of issue #236 visits is read again; its first run builds the whole index, after
 * which it is {@link #isComplete() complete}. Until then a search asks the albums themselves:
 * never wrong, only slower.</li>
 * </ul>
 */
public final class SearchIndex implements FolderPipeline.Step {

	private static final Logger LOG = Logger.getLogger(SearchIndex.class.getName());

	/** The name of the step in the pipeline's record. */
	public static final String NAME = "search";

	/** The indexes of the spaces this process serves, see {@link #sidecarWritten(File)}. */
	private static final List<SearchIndex> INSTANCES = new CopyOnWriteArrayList<>();

	/**
	 * Tells the index of the space the given folder lies in that its sidecar was just written, see
	 * the class comment. A folder outside every served space is ignored.
	 */
	static void sidecarWritten(File folder) {
		for (SearchIndex index : INSTANCES) {
			String path = index.relative(folder);
			if (path != null) {
				index._stale.add(path);
			}
		}
	}

	/** What is known about one folder. */
	static final class Folder {
		final long _folderStamp;

		final long _sidecarStamp;

		/** The album's texts, <code>null</code> for a folder holding no photographs of its own. */
		final Album _album;

		final Photo[] _photos;

		/** The subfolders to index; none below a collection or a saved search. */
		final String[] _children;

		/** The places epoch a photograph waiting for its places was read at, <code>-1</code> for none. */
		final long _waitingEpoch;

		Folder(long folderStamp, long sidecarStamp, Album album, Photo[] photos, String[] children,
				long waitingEpoch) {
			_folderStamp = folderStamp;
			_sidecarStamp = sidecarStamp;
			_album = album;
			_photos = photos;
			_children = children;
			_waitingEpoch = waitingEpoch;
		}
	}

	private static final Photo[] NO_PHOTOS = new Photo[0];

	private static final String[] NO_CHILDREN = new String[0];

	private final Path _basePath;

	private final Path _root;

	private final Function<PathInfo, Resource> _albums;

	private final LongSupplier _placesEpoch;

	/** By folder path relative to the space root; the root itself is the empty string. */
	private volatile Map<String, Folder> _folders = new ConcurrentHashMap<>();

	/** The folders whose sidecar the server wrote since they were read. */
	private final Set<String> _stale = ConcurrentHashMap.newKeySet();

	/** The one instance of every person id, label and camera. */
	private final Map<String, String> _strings = new ConcurrentHashMap<>();

	private final PhotoSearch.Interner _interner = value -> _strings.computeIfAbsent(value, x -> x);

	private volatile boolean _complete;

	/**
	 * Creates the index of the space at the given root.
	 *
	 * @param albums
	 *        A folder as the cache answers it.
	 * @param placesEpoch
	 *        The epoch of the gazetteer, see <code>Places#epoch()</code>, <code>-1</code> without one.
	 */
	public SearchIndex(Path basePath, Function<PathInfo, Resource> albums, LongSupplier placesEpoch) {
		_basePath = basePath;
		_root = basePath.toAbsolutePath().normalize();
		_albums = albums;
		_placesEpoch = placesEpoch;
		INSTANCES.add(this);
	}

	/** Stops hearing of sidecar writes. */
	public void close() {
		INSTANCES.remove(this);
	}

	/** Whether every folder of the space was read: a search may then ask the index alone. */
	public boolean isComplete() {
		return _complete;
	}

	/** How many folders and photographs the index holds, <code>{folders, photos}</code>. */
	public int[] size() {
		int photos = 0;
		Map<String, Folder> folders = _folders;
		for (Folder folder : folders.values()) {
			photos += folder._photos.length;
		}
		return new int[] { folders.size(), photos };
	}

	// --- The pipeline step. ---

	@Override
	public String name() {
		return NAME;
	}

	/** Its own check is cheap: two stamps. */
	@Override
	public boolean trustsRecord() {
		return false;
	}

	@Override
	public FolderPipeline.Outcome run(File folder) {
		if (!_complete) {
			build();
		} else {
			String path = relative(folder);
			if (path != null) {
				refresh(path);
			}
		}
		return FolderPipeline.Outcome.DONE;
	}

	// --- Building and refreshing. ---

	/** Reads every folder of the space, see the class comment; then the index is complete. */
	public void build() {
		long start = System.nanoTime();
		Map<String, Folder> folders = new ConcurrentHashMap<>();
		read("", folders);
		synchronized (this) {
			// What was written while the walk ran is read again by the next search.
			_folders = folders;
			_complete = true;
		}
		int[] size = size();
		LOG.info("Indexed " + size[1] + " photographs in " + size[0] + " folders for searching in "
			+ (System.nanoTime() - start) / 1_000_000 + " ms.");
	}

	/** Reads the folder at the given path and every unknown folder below it into the given map. */
	private void read(String path, Map<String, Folder> folders) {
		_stale.remove(path);
		File dir = fileOf(path);
		if (!dir.isDirectory()) {
			removeTree(path, folders);
			return;
		}
		Folder old = folders.get(path);
		Folder folder = load(path, dir);
		folders.put(path, folder);
		Set<String> children = new HashSet<>();
		for (String child : folder._children) {
			String childPath = path.isEmpty() ? child : path + "/" + child;
			children.add(childPath);
			if (!folders.containsKey(childPath)) {
				read(childPath, folders);
			}
		}
		if (old != null) {
			for (String child : old._children) {
				String childPath = path.isEmpty() ? child : path + "/" + child;
				if (!children.contains(childPath)) {
					removeTree(childPath, folders);
				}
			}
		}
	}

	private static void removeTree(String path, Map<String, Folder> folders) {
		folders.remove(path);
		String prefix = path + "/";
		folders.keySet().removeIf(key -> key.startsWith(prefix));
	}

	/** Reads the folder at the given path again, see the class comment. */
	synchronized void refresh(String path) {
		read(path, _folders);
	}

	/** What the folder at the given path holds now. */
	private Folder load(String path, File dir) {
		long folderStamp = dir.lastModified();
		long sidecarStamp = new File(dir, "index.json").lastModified();
		File[] entries = dir.listFiles();
		List<String> children = new ArrayList<>();
		boolean holdsFiles = false;
		if (entries != null) {
			for (File entry : entries) {
				if (!PhotoSearch.isLibraryEntry(entry.getName())) {
					continue;
				}
				if (entry.isDirectory()) {
					children.add(entry.getName());
				} else {
					holdsFiles = true;
				}
			}
		}
		PathInfo folder = pathOf(path);
		AlbumInfo album;
		try {
			album = PhotoSearch.albumAt(folder, holdsFiles, _albums);
		} catch (RuntimeException ex) {
			LOG.warning("Cannot index '" + dir + "': " + ex.getMessage());
			album = null;
		}
		if (album == null) {
			return new Folder(folderStamp, sidecarStamp, null, NO_PHOTOS, children.toArray(NO_CHILDREN), -1);
		}
		if (PhotoSearch.holdsNoFiles(album)) {
			return new Folder(folderStamp, sidecarStamp, null, NO_PHOTOS, NO_CHILDREN, -1);
		}
		List<ImagePart> images = PhotoSearch.images(album);
		Photo[] photos = new Photo[images.size()];
		boolean waiting = false;
		for (int n = 0; n < photos.length; n++) {
			ImagePart image = images.get(n);
			photos[n] = Photo.of(image, _interner);
			waiting |= image.getPlaces() != null && !image.getPlaces().getPending().isEmpty();
		}
		return new Folder(folderStamp, sidecarStamp, PhotoSearch.albumOf(album, folder), photos,
			children.toArray(NO_CHILDREN), waiting ? _placesEpoch.getAsLong() : -1);
	}

	/** Whether what is known about the folder at the given path is what it holds now. */
	private boolean current(String path, Folder folder) {
		if (_stale.contains(path)) {
			return false;
		}
		File dir = fileOf(path);
		if (dir.lastModified() != folder._folderStamp
			|| new File(dir, "index.json").lastModified() != folder._sidecarStamp) {
			return false;
		}
		// A photograph waiting for its places has them once the gazetteer moved on, see issue #234.
		return folder._waitingEpoch < 0 || folder._waitingEpoch == _placesEpoch.getAsLong();
	}

	// --- Searching. ---

	/**
	 * Every photograph below the given folder the given test accepts, the trash left out, unsorted,
	 * see {@link PhotoSearch#find}. Folders that changed are read again first.
	 */
	List<Match> find(PathInfo scope, Test test) {
		String prefix = relative(scope.toFile());
		if (prefix == null) {
			return new ArrayList<>();
		}
		for (String path : inScope(prefix)) {
			Folder folder = _folders.get(path);
			if (folder != null && !current(path, folder)) {
				refresh(path);
			}
		}
		List<Match> result = new ArrayList<>();
		Map<String, Folder> folders = _folders;
		for (String path : inScope(prefix)) {
			Folder folder = folders.get(path);
			if (folder == null || folder._album == null) {
				continue;
			}
			String below = path.equals(prefix) ? "" : (prefix.isEmpty() ? path : path.substring(prefix.length() + 1)) + "/";
			PathInfo location = null;
			for (Photo photo : folder._photos) {
				if (!photo.isTrash() && test.test(photo, folder._album)) {
					if (location == null) {
						location = pathOf(path);
					}
					result.add(new Match(location, below + photo.getName(), photo));
				}
			}
		}
		return result;
	}

	/** The paths of the folders at or below the given one, as known now. */
	private List<String> inScope(String prefix) {
		List<String> result = new ArrayList<>();
		String below = prefix + "/";
		for (String path : _folders.keySet()) {
			if (prefix.isEmpty() || path.equals(prefix) || path.startsWith(below)) {
				result.add(path);
			}
		}
		return result;
	}

	// --- Coordinates. ---

	/** The given folder relative to the space root, <code>null</code> outside it. */
	String relative(File folder) {
		Path path = folder.toPath().toAbsolutePath().normalize();
		if (!path.startsWith(_root)) {
			return null;
		}
		return _root.relativize(path).toString().replace(File.separatorChar, '/');
	}

	private File fileOf(String path) {
		return path.isEmpty() ? _root.toFile() : _root.resolve(path).toFile();
	}

	private PathInfo pathOf(String path) {
		return path.isEmpty() ? new PathInfo(_basePath) : new PathInfo(_basePath, Paths.get(path));
	}

	/** For a test: the folders known, by path. */
	Map<String, Folder> folders() {
		return new HashMap<>(_folders);
	}
}
