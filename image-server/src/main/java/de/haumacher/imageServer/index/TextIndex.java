/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.index;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.LockObtainFailedException;

/**
 * The full-text half of the index of a space: a Lucene index in
 * <code>.valbum/index/{@value #DIRECTORY_NAME}/</code>, beside the H2 database of the
 * {@link SpaceIndex}, see issue #264.
 *
 * <p>
 * <b>One per folder and process.</b> Lucene lets one writer hold a folder; a test's second servlet
 * over the same space (and H2, which shares a database among the connections of a process) asks
 * for the same folder again and gets the same instance, counted, and closed with the last of them.
 * </p>
 *
 * <p>
 * <b>Stamped like H2.</b> Every commit carries the schema, the generation of the H2 database it
 * belongs to (a random id written when the database was created), and the sequence number of the
 * last write of H2 whose text it holds. An index of another schema or generation is emptied, one
 * Lucene cannot read is deleted, and the {@link SpaceIndex} learns from {@link #applied()} which
 * writes of H2 the text lost, see {@link SpaceIndex#textSince()}.
 * </p>
 *
 * <p>
 * <b>Committed now and then.</b> A change is searchable at once (a reader near real time); it is
 * committed to disk at most every {@link #COMMIT_MILLIS} and when the index is closed, so that the
 * background's thousands of small writes do not each cost a sync. A process killed in between
 * loses the text of its last writes, which the sequence numbers find again.
 * </p>
 */
final class TextIndex {

	private static final Logger LOG = Logger.getLogger(TextIndex.class.getName());

	/** The folder below the {@link SpaceIndex#getDirectory() index folder} Lucene keeps its files in. */
	static final String DIRECTORY_NAME = "lucene";

	/** How long after a commit the next one waits at least. */
	static final long COMMIT_MILLIS = 2000;

	private static final String SCHEMA_KEY = "schema";

	private static final String GENERATION_KEY = "generation";

	private static final String SEQUENCE_KEY = "seq";

	/** The Lucene RAM buffer when nobody set one, in bytes. */
	static final long DEFAULT_BUFFER = 4L * 1024 * 1024;

	/** The open indexes of this process, by folder. */
	private static final Map<Path, TextIndex> OPEN = new HashMap<>();

	/** Changes the index, see {@link SpaceIndex#writeText(SpaceIndex.TextWrite)}. */
	interface Change {

		/** Applies the change to the given writer. */
		void apply(IndexWriter writer) throws IOException;
	}

	private final Path _folder;

	private final String _label;

	private Directory _directory;

	private IndexWriter _writer;

	private SearcherManager _searchers;

	private int _references;

	private boolean _persistent;

	/** The sequence number of the last write applied, see {@link TextIndex}. */
	private long _applied;

	private String _generation = "";

	private boolean _dirty;

	private long _lastCommit;

	/** Counts the writes of the index this belongs to, see {@link SpaceIndex#version()}. */
	private final java.util.concurrent.atomic.AtomicLong _version = new java.util.concurrent.atomic.AtomicLong();

	private TextIndex(Path folder, String label) {
		_folder = folder;
		_label = label;
	}

	/**
	 * The text index in the given folder, opened, created or rebuilt where needed; never fails, see
	 * {@link SpaceIndex}.
	 *
	 * @param folder
	 *        Where the index lives, <code>null</code> for one in memory.
	 */
	static TextIndex acquire(Path folder, String label) {
		if (folder == null) {
			TextIndex result = new TextIndex(null, label);
			result.openInMemory();
			result._references = 1;
			return result;
		}
		Path key = folder.toAbsolutePath().normalize();
		synchronized (OPEN) {
			TextIndex result = OPEN.get(key);
			if (result == null) {
				result = new TextIndex(key, label);
				result.open();
				if (result._persistent) {
					OPEN.put(key, result);
				}
			}
			result._references++;
			return result;
		}
	}

	/** Counts one more write of the index this belongs to, see {@link SpaceIndex#version()}. */
	void written() {
		_version.incrementAndGet();
	}

	/** How many writes the index this belongs to saw, see {@link SpaceIndex#version()}. */
	long version() {
		return _version.get();
	}

	/** Whether this index is kept on disk. */
	synchronized boolean isPersistent() {
		return _persistent;
	}

	/**
	 * Ties this index to the H2 database of the given generation; an index of another generation
	 * or schema is emptied.
	 *
	 * @return The sequence number of the last write of that database this index holds the text of.
	 */
	synchronized long attach(String generation, int schema) {
		if (_writer == null) {
			return 0;
		}
		Map<String, String> data = commitData();
		if (generation.equals(_generation) || (generation.equals(data.get(GENERATION_KEY))
			&& String.valueOf(schema).equals(data.get(SCHEMA_KEY)))) {
			_generation = generation;
			return _applied;
		}
		try {
			if (!data.isEmpty()) {
				LOG.info("The text index of '" + _label + "' belongs to another index; it is built again.");
			}
			_writer.deleteAll();
			_generation = generation;
			_applied = 0;
			commit(schema);
			_searchers.maybeRefreshBlocking();
		} catch (IOException | RuntimeException ex) {
			LOG.log(Level.WARNING, "Cannot empty the text index of '" + _label + "': " + ex.getMessage(), ex);
		}
		return 0;
	}

	/** Applies a change under the given sequence number; searchable when this returns. */
	synchronized void apply(long sequence, Change change, int schema) throws IOException {
		if (_writer == null) {
			throw new IOException("The text index of '" + _label + "' is not open.");
		}
		change.apply(_writer);
		_applied = Math.max(_applied, sequence);
		_dirty = true;
		_searchers.maybeRefreshBlocking();
		if (System.currentTimeMillis() - _lastCommit >= COMMIT_MILLIS) {
			commit(schema);
		}
	}

	/** Commits what was applied, if anything. */
	synchronized void flush(int schema) {
		if (_writer == null || !_dirty) {
			return;
		}
		try {
			commit(schema);
		} catch (IOException | RuntimeException ex) {
			LOG.log(Level.WARNING, "Cannot commit the text index of '" + _label + "': " + ex.getMessage(), ex);
		}
	}

	/** The sequence number of the last write applied, see {@link TextIndex}. */
	synchronized long applied() {
		return _applied;
	}

	/** Lets Lucene buffer the given number of bytes of new documents before it writes a segment. */
	synchronized void setBuffer(long bytes) {
		if (_writer != null) {
			_writer.getConfig().setRAMBufferSizeMB(Math.max(1.0, bytes / (1024.0 * 1024.0)));
		}
	}

	/** Runs the given read on the latest searcher, see {@link SpaceIndex#searchText}. */
	<T> T search(SpaceIndex.TextRead<T> read) throws IOException {
		SearcherManager searchers;
		synchronized (this) {
			searchers = _searchers;
		}
		if (searchers == null) {
			throw new IOException("The text index of '" + _label + "' is not open.");
		}
		IndexSearcher searcher = searchers.acquire();
		try {
			return read.run(searcher);
		} finally {
			searchers.release(searcher);
		}
	}

	/** Gives back one reference; the last closes the index, committing what was applied. */
	void release(int schema) {
		if (_folder != null) {
			synchronized (OPEN) {
				if (--_references > 0) {
					return;
				}
				OPEN.remove(_folder);
			}
		}
		synchronized (this) {
			flush(schema);
			disconnect();
		}
	}

	// --- Opening. ---

	private void open() {
		try {
			connect(FSDirectory.open(_folder));
			_persistent = true;
			return;
		} catch (LockObtainFailedException ex) {
			LOG.warning("The text index of '" + _label + "' is used by another process (" + ex.getMessage()
				+ "); this server keeps its own in memory.");
			disconnect();
			openInMemory();
			return;
		} catch (IOException | RuntimeException ex) {
			LOG.log(Level.WARNING, "The text index of '" + _label + "' cannot be opened (" + ex.getMessage()
				+ "); it is deleted and built again.", ex);
			disconnect();
		}
		try {
			deleteFiles();
			connect(FSDirectory.open(_folder));
			_persistent = true;
		} catch (IOException | RuntimeException ex) {
			LOG.log(Level.WARNING, "The text index of '" + _label + "' cannot be created (" + ex.getMessage()
				+ "); this server keeps it in memory.", ex);
			disconnect();
			openInMemory();
		}
	}

	private void openInMemory() {
		try {
			connect(new ByteBuffersDirectory());
			_persistent = false;
		} catch (IOException | RuntimeException ex) {
			LOG.log(Level.SEVERE, "The text index of '" + _label + "' cannot be opened at all: " + ex.getMessage(), ex);
			disconnect();
		}
	}

	private void connect(Directory directory) throws IOException {
		_directory = directory;
		IndexWriterConfig config = new IndexWriterConfig(TextAnalysis.ANALYZER);
		config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
		config.setRAMBufferSizeMB(DEFAULT_BUFFER / (1024.0 * 1024.0));
		// The writes of one space are serialised by its index; no flush on many threads.
		config.setCommitOnClose(false);
		_writer = new IndexWriter(directory, config);
		_searchers = new SearcherManager(_writer, null);
		Map<String, String> data = commitData();
		_applied = parse(data.get(SEQUENCE_KEY));
		_lastCommit = System.currentTimeMillis();
	}

	private void disconnect() {
		if (_searchers != null) {
			try {
				_searchers.close();
			} catch (IOException ex) {
				LOG.log(Level.FINE, "Closing the searchers failed: " + ex.getMessage(), ex);
			}
			_searchers = null;
		}
		if (_writer != null) {
			try {
				_writer.close();
			} catch (IOException | RuntimeException ex) {
				LOG.log(Level.FINE, "Closing the text writer failed: " + ex.getMessage(), ex);
			}
			_writer = null;
		}
		if (_directory != null) {
			try {
				_directory.close();
			} catch (IOException ex) {
				LOG.log(Level.FINE, "Closing the text folder failed: " + ex.getMessage(), ex);
			}
			_directory = null;
		}
	}

	private void deleteFiles() throws IOException {
		if (!Files.isDirectory(_folder)) {
			return;
		}
		try (DirectoryStream<Path> files = Files.newDirectoryStream(_folder)) {
			for (Path file : files) {
				Files.deleteIfExists(file);
			}
		}
	}

	private Map<String, String> commitData() {
		Map<String, String> result = new HashMap<>();
		Iterable<Map.Entry<String, String>> data = _writer.getLiveCommitData();
		if (data != null) {
			for (Map.Entry<String, String> entry : data) {
				result.put(entry.getKey(), entry.getValue());
			}
		}
		return result;
	}

	private void commit(int schema) throws IOException {
		Map<String, String> data = new HashMap<>();
		data.put(SCHEMA_KEY, String.valueOf(schema));
		data.put(GENERATION_KEY, _generation);
		data.put(SEQUENCE_KEY, String.valueOf(_applied));
		_writer.setLiveCommitData(data.entrySet());
		_writer.commit();
		_dirty = false;
		_lastCommit = System.currentTimeMillis();
	}

	private static long parse(String value) {
		if (value == null) {
			return 0;
		}
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException ex) {
			return 0;
		}
	}
}
