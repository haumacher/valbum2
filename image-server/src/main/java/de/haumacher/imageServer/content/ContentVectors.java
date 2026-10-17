/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.content;

import de.haumacher.imageServer.LibraryFiles;
import de.haumacher.imageServer.SearchIndex;
import de.haumacher.imageServer.index.SpaceIndex;
import de.haumacher.imageServer.index.VectorIndex;
import de.haumacher.imageServer.pipeline.FolderPipeline;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Feeds the {@link VectorIndex} of a space from the {@link ContentCache} of every album, see issues
 * #257 and #264 (slice 4): the cache stays the truth, the index is what a query asks.
 *
 * <h2>When</h2>
 *
 * <ul>
 * <li>Whenever the content step wrote an album's cache ({@link ContentIndex#setOnWritten}).</li>
 * <li>Whenever the server rearranged a tree (a move, a delete, a rename): {@link #treeChanged(File)},
 * after the search read it, whose ids the vectors are filed under.</li>
 * <li>On every visit of the background, as a step of its own behind the search and the content
 * step ({@link #NAME}): it trusts no record and compares a stamp per album, which is how a library
 * whose vectors were cached before this index existed (an upgrade), or a fill killed half-way, is
 * filled: the walk at every start visits every album.</li>
 * </ul>
 *
 * <h2>The stamp of an album</h2>
 *
 * <p>
 * The modification time and size of its {@link ContentCache#FILE_NAME}, the stamp of the vectors
 * the index holds, and the names and ids its photographs have in the search; nothing else decides
 * what the album's documents are. An album whose marker carries that stamp is not read; another is
 * read, and only the photographs whose vectors or ids changed are written.
 * </p>
 *
 * <h2>The model</h2>
 *
 * <p>
 * The index holds the vectors of one stamp: the embedder's as the content step learned it
 * ({@link ContentIndex#stamp()}), else the one it holds, else the first it is fed. Vectors of another
 * stamp are not taken; a new stamp drops every vector, and every album is handed to the background
 * to be fed again (what its cache holds of the new model goes in at once, the rest as it is made).
 * </p>
 */
public final class ContentVectors implements FolderPipeline.Step {

	private static final Logger LOG = Logger.getLogger(ContentVectors.class.getName());

	/** The name of the step in the record of the pipeline. */
	public static final String NAME = "vectors";

	private final Path _root;

	private final SpaceIndex _store;

	private final ContentIndex _content;

	private volatile FolderPipeline _pipeline;

	/** How many albums were read into the index, for the tests. */
	private final AtomicInteger _reads = new AtomicInteger();

	/**
	 * Creates the feed of the vector index of the space at the given root.
	 *
	 * @param content
	 *        The content search of the space, whose embedder names the model; <code>null</code> for
	 *        none (a tool), which takes the stamp the index or the caches hold.
	 */
	public ContentVectors(Path root, SpaceIndex store, ContentIndex content) {
		_root = root.toAbsolutePath().normalize();
		_store = store;
		_content = content;
	}

	/** The pipeline that feeds every album again after a new model, see {@link ContentVectors}. */
	public void setPipeline(FolderPipeline pipeline) {
		_pipeline = pipeline;
	}

	/** How many albums were read into the index since this feed was created, for the tests. */
	public int reads() {
		return _reads.get();
	}

	// --- The step. ---

	@Override
	public String name() {
		return NAME;
	}

	/** Its own check is a stamp per album. */
	@Override
	public boolean trustsRecord() {
		return false;
	}

	@Override
	public FolderPipeline.Outcome run(File folder) {
		refresh(folder);
		return FolderPipeline.Outcome.DONE;
	}

	@Override
	public void gone(String path) {
		VectorIndex vectors = _store.vectors();
		if (vectors != null) {
			vectors.removeTree(path);
		}
	}

	@Override
	public void retain(Set<String> paths) {
		VectorIndex vectors = _store.vectors();
		if (vectors == null) {
			return;
		}
		try {
			for (String known : vectors.albums()) {
				if (!fileOf(known).isDirectory() || (!paths.contains(known) && !LibraryFiles.isLibraryPath(known))) {
					vectors.removeTree(known);
				}
			}
		} catch (IOException ex) {
			LOG.log(Level.WARNING, "Cannot read the vector index of '" + _root + "': " + ex.getMessage(), ex);
		}
	}

	// --- Keeping current. ---

	/**
	 * Feeds every album of the space that changed since it was fed, and forgets what is gone: the
	 * background's walk, done at once, for a tool and the tests.
	 */
	public void fill() {
		Set<String> seen = new HashSet<>();
		for (File folder : FolderPipeline.tree(_root.toFile())) {
			String path = relative(folder);
			if (path != null) {
				seen.add(path);
				refresh(folder);
			}
		}
		forgetBelow("", seen);
	}

	/**
	 * Feeds the given folder and every folder below it again where it changed, and forgets what is
	 * gone of it: a move, a delete, a purge or a rename rearranged the tree. To be called after the
	 * search read the tree again ({@link SearchIndex#treeChanged(File)}): its ids are the keys here.
	 */
	public void treeChanged(File folder) {
		String path = relative(folder);
		if (path == null) {
			return;
		}
		Set<String> seen = new HashSet<>();
		if (folder.isDirectory()) {
			for (File below : FolderPipeline.tree(folder)) {
				String known = relative(below);
				if (known != null) {
					seen.add(known);
					refresh(below);
				}
			}
		}
		forgetBelow(path, seen);
	}

	private void forgetBelow(String path, Set<String> seen) {
		VectorIndex vectors = _store.vectors();
		if (vectors == null) {
			return;
		}
		try {
			for (String known : vectors.albums(path)) {
				if (!seen.contains(known)) {
					vectors.removeTree(known);
				}
			}
		} catch (IOException ex) {
			LOG.log(Level.WARNING, "Cannot read the vector index of '" + _root + "': " + ex.getMessage(), ex);
		}
	}

	/**
	 * Feeds the given album where its stamp changed since it was fed, see {@link ContentVectors};
	 * forgets it where it is gone.
	 *
	 * @return Whether it was read.
	 */
	public boolean refresh(File folder) {
		boolean result = feed(folder);
		boolean walk;
		synchronized (this) {
			walk = _walk;
			_walk = false;
		}
		if (walk) {
			// Outside the lock of the feed: the content step feeds while the pipeline holds its own.
			walk();
		}
		return result;
	}

	/** Whether every album is to be handed to the background, see {@link #refresh(File)}. */
	private boolean _walk;

	private synchronized boolean feed(File folder) {
		String path = relative(folder);
		VectorIndex vectors = _store.vectors();
		if (path == null || vectors == null) {
			return false;
		}
		if (!folder.isDirectory()) {
			vectors.removeTree(path);
			return false;
		}
		try {
			return feed(vectors, folder, path);
		} catch (IOException | RuntimeException ex) {
			// The marker is not set: the next visit tries again.
			LOG.log(Level.WARNING, "Cannot feed the vectors of '" + folder + "': " + ex.getMessage(), ex);
			return false;
		}
	}

	private boolean feed(VectorIndex vectors, File folder, String path) throws IOException {
		Map<String, Long> ids = SearchIndex.photoIds(_store, path);
		if (ids == null) {
			// The search cannot be read now; nothing is decided.
			return false;
		}
		String imageStamp = imageStamp(vectors, folder);
		String stamp = imageStamp.isEmpty() ? "" : stampOf(imageStamp);
		if (!stamp.isEmpty() && !stamp.equals(vectors.stamp())) {
			boolean held = !vectors.stamp().isEmpty();
			vectors.reset(stamp);
			if (held) {
				LOG.info("Content search of '" + _root + "': the vectors are of the model " + imageStamp
					+ " now; every album is fed again.");
				_walk = true;
			}
		}
		File file = ContentCache.file(folder);
		long marker = vectors.marker(path);
		if (marker == 0 && !file.isFile()) {
			// Nothing described, nothing held: an album the content search has not reached.
			return false;
		}
		long albumStamp = albumStamp(vectors.stamp(), file, ids);
		if (marker == albumStamp) {
			return false;
		}
		_reads.incrementAndGet();
		List<VectorIndex.Entry> wanted = new ArrayList<>();
		if (!stamp.isEmpty() && stamp.equals(vectors.stamp())) {
			ContentCache cache = ContentCache.read(folder, imageStamp, ContentText.stamp(imageStamp));
			for (Map.Entry<String, ContentCache.Photo> entry : cache.photos().entrySet()) {
				Long id = ids.get(entry.getKey());
				if (id == null) {
					// Not in the search yet, which numbers it: fed once it is (its stamp changes then).
					continue;
				}
				ContentCache.Photo photo = entry.getValue();
				float[] image = cache.image(photo.image());
				float[] text = cache.text(photo.text());
				if (image == null || text == null) {
					continue;
				}
				long hash = SearchIndex.hash(id + "\u0000" + photo.image().hash() + "\u0000" + photo.image().poster()
					+ "\u0000" + photo.text());
				wanted.add(new VectorIndex.Entry(id.longValue(), hash, image, text));
			}
		}
		Map<Long, Long> existing = vectors.photos(path);
		List<Long> removed = new ArrayList<>();
		Set<Long> keep = new HashSet<>();
		List<VectorIndex.Entry> added = new ArrayList<>();
		for (VectorIndex.Entry entry : wanted) {
			Long id = Long.valueOf(entry.photo());
			keep.add(id);
			Long before = existing.get(id);
			if (before == null || before.longValue() != entry.hash()) {
				added.add(entry);
			}
		}
		for (Long id : existing.keySet()) {
			if (!keep.contains(id)) {
				removed.add(id);
			}
		}
		vectors.write(path, albumStamp, removed, added);
		return true;
	}

	/** The stamp of the image vectors the given album is fed with, see {@link ContentVectors}. */
	private String imageStamp(VectorIndex vectors, File folder) {
		String embedder = _content == null ? null : _content.stamp();
		if (embedder != null) {
			return embedder;
		}
		String held = vectors.stamp();
		if (!held.isEmpty()) {
			int end = held.indexOf('\n');
			return end < 0 ? held : held.substring(0, end);
		}
		return ContentCache.read(folder).getImageStamp();
	}

	/** The stamp of the vector index that holds the vectors of the given image stamp. */
	static String stampOf(String imageStamp) {
		return imageStamp + "\n" + ContentText.stamp(imageStamp);
	}

	/** The stamp of an album, see {@link ContentVectors}; never <code>0</code>. */
	private static long albumStamp(String stamp, File file, Map<String, Long> ids) {
		StringBuilder text = new StringBuilder();
		text.append(VectorIndex.FORMAT).append('\u0000').append(stamp).append('\u0000').append(file.lastModified())
			.append('\u0000').append(file.isFile() ? file.length() : -1);
		for (Map.Entry<String, Long> entry : new TreeMap<>(ids).entrySet()) {
			text.append('\u0000').append(entry.getKey()).append('\u0001').append(entry.getValue());
		}
		return SearchIndex.hash(text.toString());
	}

	/** Hands every album of the space to the background, newest first. */
	private void walk() {
		FolderPipeline pipeline = _pipeline;
		if (pipeline == null || !pipeline.isStarted()) {
			return;
		}
		try {
			pipeline.execute(() -> pipeline.catchUp(FolderPipeline.newestFirst(FolderPipeline.tree(_root.toFile()))));
		} catch (IllegalStateException ex) {
			// Stopped meanwhile: the next start walks the space anyway.
		}
	}

	// --- Coordinates. ---

	private String relative(File folder) {
		Path path = folder.toPath().toAbsolutePath().normalize();
		if (!path.startsWith(_root)) {
			return null;
		}
		return _root.relativize(path).toString().replace(File.separatorChar, '/');
	}

	private File fileOf(String path) {
		return path.isEmpty() ? _root.toFile() : _root.resolve(path).toFile();
	}
}
