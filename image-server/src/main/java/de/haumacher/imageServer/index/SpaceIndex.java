/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.index;

import de.haumacher.imageServer.auth.UserStore;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.lucene.search.IndexSearcher;
import org.h2.api.ErrorCode;
import org.h2.jdbcx.JdbcConnectionPool;

/**
 * The index of one space: an embedded H2 database in
 * <code>&lt;space&gt;/{@value UserStore#DIRECTORY_NAME}/{@value #DIRECTORY_NAME}/</code>, see issue
 * #264.
 *
 * <p>
 * <b>A rebuildable index, never a truth.</b> Everything in it is derived from the sidecars of the
 * albums (<code>.hashes.json</code> for the {@link de.haumacher.imageServer.upload.HashIndex hash
 * index}), which stay authoritative. The database carries a schema stamp ({@link #SCHEMA}); a
 * database without it, with another one, or one H2 cannot open at all (corrupt, written by another
 * H2 format) is deleted and created empty, and the background builds it again from the sidecars. The
 * index is no part of a backup.
 * </p>
 *
 * <p>
 * <b>Added to in place.</b> A rebuild costs what is no truth of anything but still worth keeping
 * &mdash; the read marks of the bell, the search until the background has read the library again
 * &mdash; so a build that only <em>adds</em> to the tables does so in place: a {@link #MIGRATIONS
 * migration step} creates what it needs where it is not there yet, and the meta table remembers
 * how many steps an index has had ({@link #MIGRATION_KEY}). What fills the new tables is the
 * background's ordinary work, which treats a missing row as "not read yet". {@link #SCHEMA} is
 * bumped, and the index rebuilt, only for a change a step cannot make: one that changes what is
 * there.
 * </p>
 *
 * <p>
 * <b>Connections.</b> One process, no server, no TCP. Requests read through a small pool of
 * connections and never wait for a writer: H2's MVStore answers each read from one snapshot of the
 * last committed state, see {@link #read(Read, Object)}. Writes go through {@link #write(Write)}, one at a time on a connection of their
 * own, each a transaction — the background (one writer per space) and the server's own edits, which
 * write through in the same step as their sidecar. Every statement is prepared; nothing a user
 * typed is ever spelled into SQL.
 * </p>
 *
 * <p>
 * <b>Never a reason to fail the space.</b> A database that cannot be opened is deleted and created
 * again; where even that fails (a library the server may not write into, a database another
 * process holds), the index lives in memory for the life of the process and says so once.
 * </p>
 *
 * <p>
 * <b>Two stores, one index.</b> What H2 cannot index &mdash; the words of titles, descriptions,
 * labels and place names &mdash; lives beside it in a Lucene index ({@link TextIndex}, in
 * <code>{@value TextIndex#DIRECTORY_NAME}/</code>), written in the same step through
 * {@link #writeText(TextWrite)} and read through {@link #searchText(TextRead, Object)}. Lucene
 * holds the text of rows H2 numbers, so it is stamped with the generation of the database, a
 * random id written when the database was created: a text index of another generation (H2 was
 * rebuilt) or schema is emptied, one Lucene cannot read is deleted. Lucene commits now and then,
 * not with every write; each text write carries a sequence number stored in H2 and in Lucene's
 * commit, so that the rows whose text a killed process lost are known ({@link #textSince()}) and
 * read again by the background.
 * </p>
 *
 * <p>
 * <b>Three stores.</b> The vectors of the content search (issue #257) are a Lucene index of their
 * own ({@link VectorIndex}, in <code>{@value VectorIndex#DIRECTORY_NAME}/</code>), keyed by the
 * same ids of H2 and stamped with the same generation, and with the model that made them: a new
 * model drops every vector and keeps every word, and an index of the build before gained it without
 * a migration step &mdash; nothing of H2 changed.
 * </p>
 *
 * <p>
 * <b>Memory.</b> H2's page cache and Lucene's buffer of new documents are the heap this index
 * takes, bounded by {@link #setBudget(long)} (<code>VALBUM_INDEX_MEMORY</code>, all spaces
 * together), {@link #defaultBudget()} by default: a quarter of it, at most
 * {@link #MAX_TEXT_BUFFER}, is Lucene's buffer, the rest H2's cache. Lucene reads its files through
 * memory mapping, which takes the operating system's page cache, not the heap. The buffer of
 * the vector index ({@link #vectorBuffer(long)}, an eighth, at most {@link #MAX_VECTOR_BUFFER}) is
 * taken from H2's share too.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class SpaceIndex implements AutoCloseable {

	private static final Logger LOG = Logger.getLogger(SpaceIndex.class.getName());

	/** The folder below {@value UserStore#DIRECTORY_NAME} the index lives in. */
	public static final String DIRECTORY_NAME = "index";

	/** The folder in {@link #DIRECTORY_NAME} the text index keeps its files in, see {@link TextIndex}. */
	public static final String TEXT_DIRECTORY_NAME = TextIndex.DIRECTORY_NAME;

	/** The name of the H2 database in {@link #DIRECTORY_NAME}: its files start with it. */
	public static final String DATABASE_NAME = "index";

	/**
	 * The schema this build writes. A database stamped with anything else is deleted and built
	 * again from the sidecars; bump it with every change of the tables below that a
	 * {@link #MIGRATIONS migration step} cannot make.
	 */
	public static final int SCHEMA = 4;

	/** The key of the generation in the meta table, see {@link TextIndex}. */
	public static final String GENERATION_KEY = "generation";

	/** The most of the budget Lucene's buffer of new documents takes, see {@link SpaceIndex}. */
	public static final long MAX_TEXT_BUFFER = 16L * 1024 * 1024;

	/** The most of the budget the vector index's buffer of new documents takes, see {@link SpaceIndex}. */
	public static final long MAX_VECTOR_BUFFER = 16L * 1024 * 1024;

	/**
	 * The key of the moment the database was created in the meta table, see {@link #created()}.
	 */
	public static final String CREATED_KEY = "created";

	/** The key of the schema stamp in the meta table. */
	public static final String SCHEMA_KEY = "schema";

	/**
	 * The key of the number of {@link #MIGRATIONS migration steps} an index of {@link #SCHEMA} has
	 * had in the meta table; an index without it has had none.
	 */
	public static final String MIGRATION_KEY = "migration";

	/**
	 * What builds after the schema {@link #SCHEMA} added to it, in place, one step after the other,
	 * see {@link SpaceIndex}: each step is a list of statements, run on every index that has not had
	 * it yet and on every index created.
	 *
	 * <p>
	 * A step only adds (a table, a column, an index), and says so with <code>IF NOT EXISTS</code>:
	 * H2 commits every such statement on its own, so a process killed half-way through a step runs
	 * the whole step again on its next start. A step is never changed nor removed once released;
	 * a change of what is there is a bump of {@link #SCHEMA}, which starts this list afresh.
	 * </p>
	 *
	 * <ol>
	 * <li>The prototypes of the face recognition of issue #127, see
	 * <code>de.haumacher.imageServer.faces.Recognition</code>: <code>face_albums</code>, every album
	 * the recognition read, with a stamp of the three files it read (<code>index.json</code>, the
	 * album's face cache and its <code>.hashes.json</code>) and the model the embeddings are of,
	 * <code>0</code> for a stamp nobody checked; <code>face_prototypes</code>, per album the
	 * normalised embedding of every face confirmed in it, with the person id as it is stored in the
	 * album, never resolved (a merge is felt when a suggestion is computed).</li>
	 * </ol>
	 */
	private static final String[][] MIGRATIONS = {
		{
			"CREATE TABLE IF NOT EXISTS face_albums (path VARCHAR PRIMARY KEY, stamp BIGINT NOT NULL, "
				+ "model VARCHAR NOT NULL)",
			"CREATE TABLE IF NOT EXISTS face_prototypes (album VARCHAR NOT NULL REFERENCES face_albums(path) "
				+ "ON DELETE CASCADE, n INT NOT NULL, person VARCHAR NOT NULL, embedding VARBINARY NOT NULL, "
				+ "PRIMARY KEY (album, n))",
		},
	};

	/** How many {@link #MIGRATIONS migration steps} this build knows; what a created index has had. */
	public static final int MIGRATION = MIGRATIONS.length;

	/** How many connections requests read through at most, besides the writer's. */
	private static final int READERS = 16;

	/** The smallest page cache an index is given, whatever the budget says. */
	private static final long MIN_BUDGET = 1024 * 1024;

	/** Runs reads on a connection of the pool. */
	public interface Read<T> {

		/** Reads through the given connection. */
		T run(Connection connection) throws SQLException;
	}

	/** Runs one transaction on the writer's connection. */
	public interface Write {

		/** Writes through the given connection; committed when this returns. */
		void run(Connection connection) throws SQLException;
	}

	/** Runs one transaction on the writer's connection that also changes the text index. */
	public interface TextWrite {

		/**
		 * Writes through the given connection, and says what the text index is to be changed by
		 * once that is committed.
		 *
		 * @param sequence
		 *        The sequence number of this write, to be stored with every row whose text it
		 *        changes, see {@link SpaceIndex#textSince()}.
		 * @return The change of the text index, <code>null</code> for none.
		 */
		TextChange run(Connection connection, long sequence) throws SQLException;
	}

	/** A change of the text index, see {@link TextWrite}. */
	public interface TextChange {

		/** Applies the change to the given writer. */
		void apply(org.apache.lucene.index.IndexWriter writer) throws java.io.IOException;
	}

	/** Reads the text index. */
	public interface TextRead<T> {

		/** Reads through the given searcher. */
		T run(IndexSearcher searcher) throws java.io.IOException;
	}

	private final Path _directory;

	private final String _label;

	private JdbcConnectionPool _pool;

	private Connection _writer;

	private boolean _persistent;

	private boolean _fresh;

	private volatile boolean _closed;

	private long _budget;

	/** The full-text half, see {@link SpaceIndex}; <code>null</code> where nothing could be opened. */
	private TextIndex _text;

	/** The vectors of the content search, see {@link SpaceIndex}; <code>null</code> where nothing could be opened. */
	private VectorIndex _vectors;

	/** What {@link #textSince()} answers. */
	private long _textSince;

	/** What {@link #created()} answers. */
	private long _created;

	private SpaceIndex(Path directory, String label, long budget) {
		_directory = directory;
		_label = label;
		_budget = budget;
	}

	/**
	 * Opens the index of the space at the given root, creating or rebuilding it where needed; never
	 * fails, see {@link SpaceIndex}.
	 */
	public static SpaceIndex open(Path root) {
		Path normalized = root.toAbsolutePath().normalize();
		SpaceIndex result = new SpaceIndex(directoryOf(normalized), String.valueOf(normalized), defaultBudget());
		result.start();
		return result;
	}

	/** The folder the index of the space at the given root lives in. */
	public static Path directoryOf(Path root) {
		return root.resolve(UserStore.DIRECTORY_NAME).resolve(DIRECTORY_NAME);
	}

	/**
	 * The default of {@link #setBudget(long)}: a sixteenth of the largest heap the JVM may use.
	 *
	 * <p>
	 * Half the album cache's eighth: what a request asks the index is an exact lookup, which needs
	 * the pages of one key's path through the tree, not the whole table. A 100 000-photo hash index
	 * is some 10 MB of pages altogether, so a heap of 1 GB (64 MB here) holds all of it, and a
	 * small heap still keeps the inner pages, which is what makes a lookup cheap.
	 * </p>
	 */
	public static long defaultBudget() {
		return Math.max(MIN_BUDGET, Runtime.getRuntime().maxMemory() / 16);
	}

	/** The folder this index lives in. */
	public Path getDirectory() {
		return _directory;
	}

	/**
	 * Whether this index started empty: created, or rebuilt because it was of another schema or
	 * could not be read. The background fills it from the sidecars.
	 */
	public synchronized boolean isFresh() {
		return _fresh;
	}

	/**
	 * Whether this index is kept on disk; <code>false</code> for one held in memory because its
	 * folder could not be used, see {@link SpaceIndex}.
	 */
	public synchronized boolean isPersistent() {
		return _persistent;
	}

	/**
	 * Lets H2's page cache of this index take the given number of bytes of heap, see
	 * {@link SpaceIndex}.
	 */
	public void setBudget(long bytes) {
		long budget = Math.max(MIN_BUDGET, bytes);
		TextIndex text;
		VectorIndex vectors;
		synchronized (this) {
			_budget = budget;
			text = _text;
			vectors = _vectors;
		}
		if (text != null) {
			text.setBuffer(textBuffer(budget));
		}
		if (vectors != null) {
			vectors.setBuffer(vectorBuffer(budget));
		}
		write(connection -> {
			// SET takes no parameter; the number is ours, never a user's.
			try (Statement statement = connection.createStatement()) {
				statement.execute("SET CACHE_SIZE " + cacheKilobytes(cacheBudget(budget)));
			}
		});
	}

	/**
	 * Holds H2's page cache to its budget after a read that walked more pages than it holds, see
	 * <code>faq/search-index.md</code>: H2 evicts a page by keeping it weakly, and a read of it before
	 * the next collection takes it back without evicting another, so a cache read through again and
	 * again grows beyond any size it was given. Where it holds more than twice its size (H2's own
	 * count, in megabytes), it is emptied; at most once a second.
	 */
	public void trimCache() {
		long now = System.currentTimeMillis();
		synchronized (this) {
			if (now - _lastTrim < TRIM_MILLIS || _closed) {
				return;
			}
			_lastTrim = now;
		}
		long[] cache = read(connection -> {
			long[] result = new long[2];
			try (PreparedStatement select = connection.prepareStatement(
				"SELECT SETTING_NAME, SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME IN (?, ?)")) {
				select.setString(1, "info.CACHE_SIZE");
				select.setString(2, "info.CACHE_MAX_SIZE");
				try (ResultSet rows = select.executeQuery()) {
					while (rows.next()) {
						result[rows.getString(1).equals("info.CACHE_SIZE") ? 0 : 1] = Long.parseLong(rows.getString(2));
					}
				}
			}
			return result;
		}, null);
		if (cache == null || cache[0] <= 2 * cache[1] + 1) {
			return;
		}
		long budget;
		synchronized (this) {
			budget = _budget;
		}
		// Setting the size empties the cache, see FileStore#setCacheSize.
		write(connection -> {
			try (Statement statement = connection.createStatement()) {
				statement.execute("SET CACHE_SIZE " + cacheKilobytes(cacheBudget(budget)));
			}
		});
	}

	/** How often {@link #trimCache()} looks at most. */
	private static final long TRIM_MILLIS = 1000;

	private long _lastTrim;

	/** Lucene's share of the given budget, see {@link SpaceIndex}. */
	public static long textBuffer(long budget) {
		return Math.min(MAX_TEXT_BUFFER, budget / 4);
	}

	/** The vector index's share of the given budget, see {@link SpaceIndex}. */
	public static long vectorBuffer(long budget) {
		return Math.min(MAX_VECTOR_BUFFER, budget / 8);
	}

	/** H2's share of the given budget, see {@link SpaceIndex}. */
	public static long cacheBudget(long budget) {
		return Math.max(MIN_BUDGET, budget - textBuffer(budget) - vectorBuffer(budget));
	}

	/** How many bytes of heap H2's page cache of this index may take, see {@link #setBudget(long)}. */
	public synchronized long getBudget() {
		return _budget;
	}

	private static long cacheKilobytes(long bytes) {
		return Math.max(1, bytes / 1024);
	}

	/**
	 * Runs a read on a connection of the pool.
	 *
	 * @return What the read answered; the given fallback where the index cannot be read (it was
	 *         closed, the database failed), which is logged: an index that cannot answer is an
	 *         index that knows nothing, never a failed request.
	 */
	public <T> T read(Read<T> read, T fallback) {
		JdbcConnectionPool pool;
		synchronized (this) {
			pool = _pool;
		}
		if (pool == null || _closed) {
			return fallback;
		}
		try (Connection connection = pool.getConnection()) {
			// One transaction of one snapshot: the statements of a read see the same state, and a
			// row a writer deletes meanwhile is never half gone, see faq/search-index.md.
			connection.setAutoCommit(false);
			connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
			try {
				return read.run(connection);
			} finally {
				connection.rollback();
			}
		} catch (SQLException | RuntimeException ex) {
			if (!_closed) {
				LOG.log(Level.WARNING, "Cannot read the index of '" + _label + "': " + ex.getMessage(), ex);
			}
			return fallback;
		}
	}

	/**
	 * Runs one transaction on the writer's connection, one writer at a time.
	 *
	 * @return Whether it was committed; a failure is rolled back and logged, see
	 *         {@link #read(Read, Object)}.
	 */
	public synchronized boolean write(Write write) {
		if (_writer == null || _closed) {
			return false;
		}
		try {
			write.run(_writer);
			_writer.commit();
			return true;
		} catch (SQLException | RuntimeException ex) {
			LOG.log(Level.WARNING, "Cannot write the index of '" + _label + "': " + ex.getMessage(), ex);
			try {
				_writer.rollback();
			} catch (SQLException rollback) {
				LOG.log(Level.FINE, "Rollback failed: " + rollback.getMessage(), rollback);
			}
			return false;
		}
	}

	/**
	 * Runs one transaction on the writer's connection that also changes the text index, one writer
	 * at a time: H2 first, and once that is committed, Lucene.
	 *
	 * @return Whether both were changed. Where H2 fails, nothing is; where Lucene fails after H2
	 *         was committed, the rows of this write carry a sequence number the text index never
	 *         reached, which {@link #textSince()} tells after the next start, and the caller learns
	 *         it here to read them again.
	 */
	public boolean writeText(TextWrite write) {
		TextIndex text;
		synchronized (this) {
			text = _text;
		}
		if (text == null) {
			return false;
		}
		// The text index is shared by the indexes of one space in this process: its sequence is
		// the order of the writes of all of them.
		synchronized (text) {
			synchronized (this) {
				if (_writer == null || _closed) {
					return false;
				}
				TextChange change;
				try {
					long sequence = nextSequence();
					change = write.run(_writer, sequence);
					_writer.commit();
					try {
						if (change != null) {
							text.apply(sequence, change::apply, SCHEMA);
						}
					} finally {
						// After the commit: a reader that saw this version sees what was written.
						text.written();
					}
					return true;
				} catch (SQLException | RuntimeException ex) {
					LOG.log(Level.WARNING, "Cannot write the index of '" + _label + "': " + ex.getMessage(), ex);
					try {
						_writer.rollback();
					} catch (SQLException rollback) {
						LOG.log(Level.FINE, "Rollback failed: " + rollback.getMessage(), rollback);
					}
					return false;
				} catch (java.io.IOException ex) {
					LOG.log(Level.WARNING, "Cannot write the text index of '" + _label + "': " + ex.getMessage(), ex);
					return false;
				}
			}
		}
	}

	private long nextSequence() throws SQLException {
		try (Statement statement = _writer.createStatement();
				ResultSet result = statement.executeQuery("SELECT NEXT VALUE FOR text_seq")) {
			result.next();
			return result.getLong(1);
		}
	}

	/**
	 * Runs a read of the text index.
	 *
	 * @return What the read answered; the given fallback where the index cannot be read, see
	 *         {@link #read(Read, Object)}.
	 */
	public <T> T searchText(TextRead<T> read, T fallback) {
		TextIndex text;
		synchronized (this) {
			text = _text;
		}
		if (text == null || _closed) {
			return fallback;
		}
		try {
			return text.search(read);
		} catch (java.io.IOException | RuntimeException ex) {
			if (!_closed) {
				LOG.log(Level.WARNING, "Cannot read the text index of '" + _label + "': " + ex.getMessage(), ex);
			}
			return fallback;
		}
	}

	/**
	 * A number that changes with every {@link #writeText(TextWrite) text write} of the indexes of
	 * this space in this process, after it was committed: what a reader computed from the index
	 * stays true while this number does not change.
	 */
	public long version() {
		TextIndex text;
		synchronized (this) {
			text = _text;
		}
		return text == null ? 0 : text.version();
	}

	/**
	 * The sequence number of the last {@link #writeText(TextWrite) text write} whose text the text
	 * index held when this index was opened: every row stamped with a higher one lost its text
	 * (a process killed before Lucene committed it, a text index rebuilt) and is to be read again.
	 * <code>0</code> for a text index that holds nothing.
	 */
	public synchronized long textSince() {
		return _textSince;
	}

	/**
	 * When this database was created, milliseconds since the epoch: the start of what it knows
	 * first-hand. The read marks of the bell (issue #268) are kept here and go with a rebuild; an
	 * event older than this moment counts as read, so a rebuilt index does not ring for everything
	 * the library ever received. <code>0</code> for an index that could not be opened at all.
	 */
	public synchronized long created() {
		return _created;
	}

	/**
	 * The vectors of the content search of this space, see {@link VectorIndex}; <code>null</code>
	 * where they could not be opened at all, or this index is closed.
	 */
	public synchronized VectorIndex vectors() {
		return _closed ? null : _vectors;
	}

	/** Whether the text index is kept on disk, see {@link #isPersistent()}. */
	public synchronized boolean isTextPersistent() {
		return _text != null && _text.isPersistent();
	}

	/** Commits what the text index was given, for a test that restarts without closing. */
	public void flushText() {
		TextIndex text;
		synchronized (this) {
			text = _text;
		}
		if (text != null) {
			text.flush(SCHEMA);
		}
	}

	/**
	 * Gives the database back: its files are closed and unlocked once no other index of the same
	 * space in this process holds them (a test's second servlet over the same folder). The text
	 * index commits what it was given.
	 */
	@Override
	public void close() {
		TextIndex text;
		VectorIndex vectors;
		synchronized (this) {
			if (_closed) {
				return;
			}
			_closed = true;
			text = _text;
			_text = null;
			vectors = _vectors;
			_vectors = null;
		}
		// Outside this index's lock: a text write takes the text index's lock first.
		if (text != null) {
			text.release(SCHEMA);
		}
		if (vectors != null) {
			vectors.release();
		}
		synchronized (this) {
			disconnect();
		}
	}

	// --- Opening. ---

	private synchronized void start() {
		startDatabase();
		readCreated();
		openText();
	}

	/** Reads {@link #created()} from the meta table. */
	private void readCreated() {
		if (_writer == null) {
			return;
		}
		try {
			try (PreparedStatement statement = _writer.prepareStatement("SELECT val FROM meta WHERE name = ?")) {
				statement.setString(1, CREATED_KEY);
				try (ResultSet result = statement.executeQuery()) {
					_created = result.next() ? Long.parseLong(result.getString(1)) : 0;
				}
			} finally {
				_writer.commit();
			}
		} catch (SQLException | NumberFormatException ex) {
			LOG.log(Level.WARNING, "The index of '" + _label + "' has no creation time: " + ex.getMessage(), ex);
		}
	}

	/** Opens the text index beside the database, tied to its generation, see {@link SpaceIndex}. */
	private void openText() {
		if (_writer == null) {
			return;
		}
		String generation;
		try {
			generation = generation();
		} catch (SQLException ex) {
			LOG.log(Level.WARNING, "The index of '" + _label + "' has no generation: " + ex.getMessage(), ex);
			return;
		}
		_text = TextIndex.acquire(_persistent ? _directory.resolve(TextIndex.DIRECTORY_NAME) : null, _label);
		_text.setBuffer(textBuffer(_budget));
		_textSince = _text.attach(generation, SCHEMA);
		_vectors = VectorIndex.acquire(_persistent ? _directory.resolve(VectorIndex.DIRECTORY_NAME) : null, _label);
		_vectors.setBuffer(vectorBuffer(_budget));
		_vectors.attach(generation);
	}

	private String generation() throws SQLException {
		try (PreparedStatement statement = _writer.prepareStatement("SELECT val FROM meta WHERE name = ?")) {
			statement.setString(1, GENERATION_KEY);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					throw new SQLException("No generation.");
				}
				return result.getString(1);
			}
		} finally {
			_writer.commit();
		}
	}

	private void startDatabase() {
		String url = fileUrl();
		if (url != null) {
			try {
				connect(url);
				if (stamped()) {
					_persistent = true;
					return;
				}
				LOG.info("The index of '" + _label + "' is of another schema; it is built again.");
			} catch (SQLException | RuntimeException ex) {
				if (inUse(ex)) {
					LOG.warning("The index of '" + _label + "' is used by another process (" + ex.getMessage()
						+ "); this server keeps its own in memory.");
					disconnect();
					openInMemory();
					return;
				}
				LOG.log(Level.WARNING, "The index of '" + _label + "' cannot be opened (" + ex.getMessage()
					+ "); it is deleted and built again.", ex);
			}
			disconnect();
			try {
				deleteFiles();
				connect(url);
				if (!stamped()) {
					// Another index of this space in this process still holds the old database open:
					// its contents go instead of its files.
					dropAll();
					createSchema();
				}
				_persistent = true;
				_fresh = true;
				return;
			} catch (SQLException | IOException | RuntimeException ex) {
				LOG.log(Level.WARNING, "The index of '" + _label + "' cannot be created (" + ex.getMessage()
					+ "); this server keeps it in memory.", ex);
				disconnect();
			}
		}
		openInMemory();
	}

	/** The address of the database on disk; <code>null</code> where its folder cannot be used. */
	private String fileUrl() {
		try {
			Files.createDirectories(_directory);
		} catch (IOException | RuntimeException ex) {
			LOG.warning("The folder of the index '" + _directory + "' cannot be created (" + ex.getMessage()
				+ "); this server keeps the index in memory.");
			return null;
		}
		String path = _directory.resolve(DATABASE_NAME).toAbsolutePath().toString();
		if (path.indexOf(';') >= 0) {
			// H2 reads its settings after a semicolon: such a path cannot be spelled as an address.
			LOG.warning("The index '" + _directory + "' cannot be addressed by H2 (a ';' in its path); this "
				+ "server keeps it in memory.");
			return null;
		}
		// "async:" reads and writes through an AsynchronousFileChannel, which an interrupt does not
		// close: the background thread is interrupted when the server goes down, and a plain
		// FileChannel would be closed under every other thread of the space.
		return "jdbc:h2:async:" + path + settings();
	}

	private String settings() {
		// No file trace (the log says what fails), no shutdown hook of H2's own (the servlet closes
		// the index, and the hook would close it under the requests of a server still shutting down).
		// Lazy results: a reader streams its rows instead of holding them all (a search reads the ids of
		// every photograph of a folder), see #connect(String) for the writer.
		// No CACHE_SIZE here: every connection of the pool opened later would set it back to what it
		// was when the index opened, see #connect(String).
		return ";TRACE_LEVEL_FILE=0;DB_CLOSE_ON_EXIT=FALSE;LAZY_QUERY_EXECUTION=TRUE";
	}

	private void openInMemory() {
		try {
			connect("jdbc:h2:mem:valbum-index-" + UUID.randomUUID() + settings());
			createSchema();
			_persistent = false;
			_fresh = true;
		} catch (SQLException | RuntimeException ex) {
			// Not even memory: the index answers nothing and the server runs without it.
			LOG.log(Level.SEVERE, "The index of '" + _label + "' cannot be opened at all: " + ex.getMessage(), ex);
			disconnect();
		}
	}

	private void connect(String url) throws SQLException {
		JdbcConnectionPool pool = JdbcConnectionPool.create(url, "", "");
		// The readers', and the writer's, which is held for the life of the index.
		pool.setMaxConnections(READERS + 1);
		_pool = pool;
		_writer = pool.getConnection();
		try (Statement statement = _writer.createStatement()) {
			// The writer changes what it reads: its results are whole before it writes.
			statement.execute("SET LAZY_QUERY_EXECUTION FALSE");
			// The cache of the database, set once; SET takes no parameter, the number is ours.
			statement.execute("SET CACHE_SIZE " + cacheKilobytes(cacheBudget(_budget)));
		}
		_writer.setAutoCommit(false);
	}

	private void disconnect() {
		if (_writer != null) {
			try {
				_writer.rollback();
				_writer.close();
			} catch (SQLException ex) {
				LOG.log(Level.FINE, "Closing the writer failed: " + ex.getMessage(), ex);
			}
			_writer = null;
		}
		if (_pool != null) {
			_pool.dispose();
			_pool = null;
		}
	}

	private static boolean inUse(Throwable ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof SQLException && ((SQLException) cause).getErrorCode() == ErrorCode.DATABASE_ALREADY_OPEN_1) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether the open database carries this build's schema; an empty one is given it here.
	 *
	 * @return <code>false</code> for a database of something else, which has to go.
	 */
	private boolean stamped() throws SQLException {
		if (tableCount() == 0) {
			createSchema();
			_fresh = true;
			return true;
		}
		String stamp;
		try (PreparedStatement statement = _writer.prepareStatement("SELECT val FROM meta WHERE name = ?")) {
			statement.setString(1, SCHEMA_KEY);
			try (ResultSet result = statement.executeQuery()) {
				stamp = result.next() ? result.getString(1) : null;
			}
		} catch (SQLException ex) {
			// No meta table: a database of something else.
			_writer.rollback();
			return false;
		}
		_writer.commit();
		if (!String.valueOf(SCHEMA).equals(stamp)) {
			return false;
		}
		int migration = migration();
		if (migration > MIGRATION) {
			// Added to by a later build, which this one cannot keep current: what it added would be
			// stale when that build runs again.
			LOG.info("The index of '" + _label + "' was written by a later build (migration " + migration + ").");
			return false;
		}
		migrate(migration);
		return true;
	}

	/** How many {@link #MIGRATIONS migration steps} the open database has had. */
	private int migration() throws SQLException {
		try (PreparedStatement statement = _writer.prepareStatement("SELECT val FROM meta WHERE name = ?")) {
			statement.setString(1, MIGRATION_KEY);
			try (ResultSet result = statement.executeQuery()) {
				return result.next() ? Integer.parseInt(result.getString(1)) : 0;
			}
		} catch (NumberFormatException ex) {
			throw new SQLException("Unreadable migration: " + ex.getMessage(), ex);
		} finally {
			_writer.commit();
		}
	}

	/**
	 * Runs every {@link #MIGRATIONS migration step} the open database has not had, from the given
	 * one on, each recorded once it ran, see {@link SpaceIndex}.
	 */
	private void migrate(int from) throws SQLException {
		for (int step = from; step < MIGRATION; step++) {
			try (Statement statement = _writer.createStatement()) {
				for (String sql : MIGRATIONS[step]) {
					statement.execute(sql);
				}
			}
			try (PreparedStatement statement =
				_writer.prepareStatement("MERGE INTO meta (name, val) KEY (name) VALUES (?, ?)")) {
				statement.setString(1, MIGRATION_KEY);
				statement.setString(2, String.valueOf(step + 1));
				statement.executeUpdate();
			}
			_writer.commit();
			if (!_creating) {
				LOG.info("The index of '" + _label + "' was added to in place (migration " + (step + 1) + ").");
			}
		}
	}

	/** Whether {@link #createSchema()} runs, which says nothing of the steps it takes. */
	private boolean _creating;

	private int tableCount() throws SQLException {
		try (PreparedStatement statement = _writer.prepareStatement(
			"SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ?")) {
			statement.setString(1, "PUBLIC");
			try (ResultSet result = statement.executeQuery()) {
				result.next();
				return result.getInt(1);
			} finally {
				_writer.commit();
			}
		}
	}

	private void dropAll() throws SQLException {
		try (Statement statement = _writer.createStatement()) {
			statement.execute("DROP ALL OBJECTS");
		}
		_writer.commit();
	}

	/** Deletes the files of the database, which must be closed; the folder stays. */
	private void deleteFiles() throws IOException {
		if (!Files.isDirectory(_directory)) {
			return;
		}
		try (DirectoryStream<Path> files = Files.newDirectoryStream(_directory, DATABASE_NAME + ".*")) {
			for (Path file : files) {
				Files.deleteIfExists(file);
			}
		}
	}

	/**
	 * The tables of this build, stamped with {@link #SCHEMA}, and every {@link #MIGRATIONS migration
	 * step} after it; a change here is a change of {@link #SCHEMA}.
	 *
	 * <ul>
	 * <li><code>meta</code>: the stamp, and whatever a part of the index keeps about itself.</li>
	 * <li><code>folders</code>: every folder of the space whose <code>.hashes.json</code> was read,
	 * with the modification stamp of that file; <code>id</code> is the order folders became known
	 * in, which is the order lookups answer in.</li>
	 * <li><code>hashes</code>: per folder, each content hash (32 bytes of SHA-256) with the name of
	 * one file holding it; found by hash for a lookup, by folder when a folder is read again.</li>
	 * <li>The search of issue #227, see <code>SearchIndex</code>: <code>albums</code>, every folder
	 * the search read, with the stamps it was read at, and the sequence number of its last text
	 * write (<code>text_seq</code>, see {@link #textSince()}); <code>photos</code>, a row per
	 * photograph with what a criterion asks of it (the date, the rating, the privacy, the camera,
	 * whether it is a video); <code>photo_hashes</code>, a hash of all of it and of its text, which
	 * tells whether it changed; <code>photo_persons</code>, <code>photo_labels</code> and <code>photo_places</code>,
	 * the person ids of its confirmed faces, its labels and the GeoNames ids of its places, each
	 * found by its value; <code>places</code>, per GeoNames id the tag a photograph was last read
	 * with (its name, kind, feature code and country), so that the options of the search view are
	 * answered without an album (schema 4). Its text is in Lucene, a document per photograph keyed
	 * by the row's id.</li>
	 * <li>The bell of issue #268, see <code>Arrivals</code>: <code>arrivals</code>, per folder of
	 * <code>folders</code> the files that arrived for the others at a known moment &mdash; who made
	 * them arrive (an upload, or a move out of the inbox), what to show for them, when, and whether
	 * by a move &mdash; read from <code>.hashes.json</code> with the hashes; <code>arrival_marks</code>,
	 * what each member marked as read: the folder, the actor and the span of time of an entry. The
	 * marks are no truth of anything and go with a rebuild, see {@link #created()}.</li>
	 * </ul>
	 */
	private void createSchema() throws SQLException {
		try (Statement statement = _writer.createStatement()) {
			statement.execute("CREATE TABLE meta (name VARCHAR(64) PRIMARY KEY, val VARCHAR NOT NULL)");
			statement.execute("CREATE TABLE folders (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, "
				+ "path VARCHAR NOT NULL UNIQUE, stamp BIGINT NOT NULL)");
			statement.execute("CREATE TABLE hashes (folder BIGINT NOT NULL REFERENCES folders(id) ON DELETE CASCADE, "
				+ "hash BINARY(32) NOT NULL, name VARCHAR NOT NULL, PRIMARY KEY (folder, hash))");
			statement.execute("CREATE INDEX hashes_by_hash ON hashes(hash)");

			statement.execute("CREATE SEQUENCE text_seq");
			statement.execute("CREATE TABLE albums (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, "
				+ "path VARCHAR NOT NULL UNIQUE, folder_stamp BIGINT NOT NULL, sidecar_stamp BIGINT NOT NULL, "
				+ "record_stamp BIGINT NOT NULL, waiting_epoch BIGINT NOT NULL, closed BOOLEAN NOT NULL, "
				+ "text_seq BIGINT NOT NULL)");
			statement.execute("CREATE INDEX albums_by_seq ON albums(text_seq)");
			statement.execute("CREATE TABLE photos (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, "
				+ "album BIGINT NOT NULL REFERENCES albums(id) ON DELETE CASCADE, name VARCHAR NOT NULL, "
				+ "taken BIGINT NOT NULL, rating TINYINT NOT NULL, privacy TINYINT NOT NULL, camera VARCHAR NOT NULL, "
				+ "video BOOLEAN NOT NULL, UNIQUE (album, name))");
			// Read when a folder is written only: kept out of the rows every search walks.
			statement.execute("CREATE TABLE photo_hashes (photo BIGINT PRIMARY KEY REFERENCES photos(id) ON DELETE CASCADE, "
				+ "row_hash BIGINT NOT NULL, text_hash BIGINT NOT NULL)");
			statement.execute("CREATE INDEX photos_by_taken ON photos(taken)");
			statement.execute("CREATE INDEX photos_by_rating ON photos(rating)");
			statement.execute("CREATE INDEX photos_by_camera ON photos(camera)");
			statement.execute("CREATE TABLE photo_persons (photo BIGINT NOT NULL REFERENCES photos(id) ON DELETE CASCADE, "
				+ "person VARCHAR NOT NULL, PRIMARY KEY (photo, person))");
			statement.execute("CREATE INDEX photo_persons_by_person ON photo_persons(person)");
			statement.execute("CREATE TABLE photo_labels (photo BIGINT NOT NULL REFERENCES photos(id) ON DELETE CASCADE, "
				+ "label VARCHAR NOT NULL, PRIMARY KEY (photo, label))");
			statement.execute("CREATE INDEX photo_labels_by_label ON photo_labels(label)");
			statement.execute("CREATE TABLE photo_places (photo BIGINT NOT NULL REFERENCES photos(id) ON DELETE CASCADE, "
				+ "place INT NOT NULL, PRIMARY KEY (photo, place))");
			statement.execute("CREATE INDEX photo_places_by_place ON photo_places(place)");
			statement.execute("CREATE TABLE places (id INT PRIMARY KEY, name VARCHAR NOT NULL, kind VARCHAR NOT NULL, "
				+ "feature VARCHAR NOT NULL, country VARCHAR NOT NULL)");

			statement.execute("CREATE TABLE arrivals (folder BIGINT NOT NULL REFERENCES folders(id) ON DELETE CASCADE, "
				+ "name VARCHAR NOT NULL, actor VARCHAR NOT NULL, label VARCHAR NOT NULL, arrived BIGINT NOT NULL, "
				+ "moved BOOLEAN NOT NULL, PRIMARY KEY (folder, name))");
			statement.execute("CREATE INDEX arrivals_by_time ON arrivals(arrived)");
			statement.execute("CREATE TABLE arrival_marks (member VARCHAR NOT NULL, folder VARCHAR NOT NULL, "
				+ "actor VARCHAR NOT NULL, first_at BIGINT NOT NULL, last_at BIGINT NOT NULL, marked BIGINT NOT NULL)");
			statement.execute("CREATE INDEX arrival_marks_by_member ON arrival_marks(member)");
		}
		try (PreparedStatement statement = _writer.prepareStatement("INSERT INTO meta (name, val) VALUES (?, ?)")) {
			statement.setString(1, SCHEMA_KEY);
			statement.setString(2, String.valueOf(SCHEMA));
			statement.executeUpdate();
			statement.setString(1, GENERATION_KEY);
			statement.setString(2, UUID.randomUUID().toString());
			statement.executeUpdate();
			statement.setString(1, CREATED_KEY);
			statement.setString(2, String.valueOf(System.currentTimeMillis()));
			statement.executeUpdate();
		}
		_writer.commit();
		_creating = true;
		try {
			migrate(0);
		} finally {
			_creating = false;
		}
	}
}
