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
 * H2 format) is deleted and created empty, and the background builds it again from the sidecars. A
 * schema change is a rebuild, never a migration, and the index is no part of a backup.
 * </p>
 *
 * <p>
 * <b>Connections.</b> One process, no server, no TCP. Requests read through a small pool of
 * connections and never wait for a writer: H2's MVStore answers each statement from the last
 * committed state. Writes go through {@link #write(Write)}, one at a time on a connection of their
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
 * <b>Memory.</b> H2's page cache is the heap this index takes, bounded by {@link #setBudget(long)}
 * (<code>VALBUM_INDEX_MEMORY</code>, all spaces together), {@link #defaultBudget()} by default.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class SpaceIndex implements AutoCloseable {

	private static final Logger LOG = Logger.getLogger(SpaceIndex.class.getName());

	/** The folder below {@value UserStore#DIRECTORY_NAME} the index lives in. */
	public static final String DIRECTORY_NAME = "index";

	/** The name of the H2 database in {@link #DIRECTORY_NAME}: its files start with it. */
	public static final String DATABASE_NAME = "index";

	/**
	 * The schema this build writes. A database stamped with anything else is deleted and built
	 * again from the sidecars; bump it with every change of the tables below.
	 */
	public static final int SCHEMA = 1;

	/** The key of the schema stamp in the meta table. */
	public static final String SCHEMA_KEY = "schema";

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

	private final Path _directory;

	private final String _label;

	private JdbcConnectionPool _pool;

	private Connection _writer;

	private boolean _persistent;

	private boolean _fresh;

	private volatile boolean _closed;

	private long _budget;

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
		synchronized (this) {
			_budget = budget;
		}
		write(connection -> {
			// SET takes no parameter; the number is ours, never a user's.
			try (Statement statement = connection.createStatement()) {
				statement.execute("SET CACHE_SIZE " + cacheKilobytes(budget));
			}
		});
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
			return read.run(connection);
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
	 * Gives the database back: its files are closed and unlocked once no other index of the same
	 * space in this process holds them (a test's second servlet over the same folder).
	 */
	@Override
	public synchronized void close() {
		if (_closed) {
			return;
		}
		_closed = true;
		disconnect();
	}

	// --- Opening. ---

	private synchronized void start() {
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
		return ";CACHE_SIZE=" + cacheKilobytes(_budget) + ";TRACE_LEVEL_FILE=0;DB_CLOSE_ON_EXIT=FALSE";
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
		return String.valueOf(SCHEMA).equals(stamp);
	}

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
	 * The tables of this build, stamped with {@link #SCHEMA}; a change here is a change of
	 * {@link #SCHEMA}.
	 *
	 * <ul>
	 * <li><code>meta</code>: the stamp, and whatever a part of the index keeps about itself.</li>
	 * <li><code>folders</code>: every folder of the space whose <code>.hashes.json</code> was read,
	 * with the modification stamp of that file; <code>id</code> is the order folders became known
	 * in, which is the order lookups answer in.</li>
	 * <li><code>hashes</code>: per folder, each content hash (32 bytes of SHA-256) with the name of
	 * one file holding it; found by hash for a lookup, by folder when a folder is read again.</li>
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
		}
		try (PreparedStatement statement = _writer.prepareStatement("INSERT INTO meta (name, val) VALUES (?, ?)")) {
			statement.setString(1, SCHEMA_KEY);
			statement.setString(2, String.valueOf(SCHEMA));
			statement.executeUpdate();
		}
		_writer.commit();
	}
}
