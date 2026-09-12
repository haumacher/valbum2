/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.auth;

import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The sharing grants of this server, persisted beside the album tree (issue #49).
 *
 * <p>
 * One grant says who ({@link Grant#getSubject() subject}) may do what ({@link Grant#getRights()
 * rights}) on which subtree ({@link Grant#getOwner() owner} and {@link Grant#getPath() path}).
 * Grants are inherited downwards, and a grant is identified by owner, path and subject: granting
 * again replaces its rights, revoking removes it. Nothing else in this server grants anything.
 * </p>
 *
 * <p>
 * The store lives in {@link UserStore#DIRECTORY_NAME} at the root of the served folder, never
 * inside a user's album folder, and it is written in this class's own JSON, not in the protocol's:
 * what is persisted is independent of what travels on the wire. The file format is persisted data
 * and therefore versioned:
 * </p>
 *
 * <pre>
 * {"version":1,"grants":[{"owner":"alice","path":"2024","subject":"user:bob",
 *   "rights":["view","download"],"created":"2026-09-12T10:11:12Z"}]}
 * </pre>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class GrantStore {

	private static final Logger LOG = Logger.getLogger(GrantStore.class.getName());

	/** The name of the store within {@link UserStore#DIRECTORY_NAME}. */
	public static final String FILE_NAME = "grants.json";

	/** The version this build writes, see {@link GrantStore}. */
	public static final int VERSION = 1;

	private static final String VERSION__PROP = "version";

	private static final String GRANTS__PROP = "grants";

	private static final String OWNER__PROP = "owner";

	private static final String PATH__PROP = "path";

	private static final String SUBJECT__PROP = "subject";

	private static final String RIGHTS__PROP = "rights";

	private static final String CREATED__PROP = "created";

	/** A single grant, see {@link GrantStore}. */
	public static final class Grant {

		private final String _owner;

		private final String _path;

		private final String _subject;

		private Set<String> _rights;

		private final String _created;

		/** Creates a {@link Grant}. */
		public Grant(String owner, String path, String subject, Collection<String> rights, String created) {
			_owner = owner;
			_path = path;
			_subject = subject;
			_rights = new LinkedHashSet<>(rights);
			_created = created;
		}

		/** The name of the user in whose space the granted subtree lies. */
		public String getOwner() {
			return _owner;
		}

		/**
		 * The granted folder as a path relative to the owner's space, <code>/</code> as separator.
		 *
		 * <p>
		 * The empty string is the space itself, and a grant on it covers everything in it.
		 * </p>
		 */
		public String getPath() {
			return _path;
		}

		/** Who is granted, see {@link Subjects}. */
		public String getSubject() {
			return _subject;
		}

		/** What is granted, see {@link Rights}; as stored, without the implications applied. */
		public Set<String> getRights() {
			return Collections.unmodifiableSet(_rights);
		}

		/** See {@link #getRights()}. */
		void setRights(Collection<String> rights) {
			_rights = new LinkedHashSet<>(rights);
		}

		/** When the grant was made, an ISO-8601 instant. */
		public String getCreated() {
			return _created;
		}

		/** Whether this grant covers the given path in the given space. */
		public boolean covers(String owner, String path) {
			if (!_owner.equals(owner)) {
				return false;
			}
			return isBelow(path, _path);
		}

		@Override
		public String toString() {
			return _subject + "@" + _owner + "/" + _path + _rights;
		}
	}

	/**
	 * Whether the given path is the given folder or lies below it, both relative to the same space.
	 *
	 * <p>
	 * A string comparison on purpose: both sides are the server's own normalised relative paths
	 * with <code>/</code> as separator, and the empty folder is the space itself.
	 * </p>
	 */
	public static boolean isBelow(String path, String folder) {
		if (folder.isEmpty()) {
			return true;
		}
		if (path.equals(folder)) {
			return true;
		}
		return path.startsWith(folder + "/");
	}

	private final Path _file;

	private List<Grant> _grants = new ArrayList<>();

	/** Whether the file on disk could not be read; it is set aside instead of overwritten. */
	private boolean _damaged;

	/**
	 * Creates a {@link GrantStore} for the album tree rooted at the given path.
	 *
	 * <p>
	 * The store is read immediately, so that a restarted server honours the grants it recorded
	 * before. A library that never shared anything has no file and an empty store.
	 * </p>
	 */
	public GrantStore(Path basePath) {
		_file = basePath.resolve(UserStore.DIRECTORY_NAME).resolve(FILE_NAME);
		load();
	}

	/** The file this store is persisted in. */
	public Path getFile() {
		return _file;
	}

	/** Every grant of this server, in the order they were made. */
	public synchronized List<Grant> getGrants() {
		return Collections.unmodifiableList(new ArrayList<>(_grants));
	}

	/** The grants on the space of the given user, in the order they were made. */
	public synchronized List<Grant> ofOwner(String owner) {
		List<Grant> result = new ArrayList<>();
		for (Grant grant : _grants) {
			if (grant.getOwner().equals(owner)) {
				result.add(grant);
			}
		}
		return result;
	}

	/**
	 * The grants covering the given path of the given space, the nearest one first.
	 *
	 * <p>
	 * That is the grant on the path itself and the grants on every folder above it, up to the
	 * space root: rights are inherited downwards.
	 * </p>
	 */
	public synchronized List<Grant> covering(String owner, String path) {
		List<Grant> result = new ArrayList<>();
		for (Grant grant : _grants) {
			if (grant.covers(owner, path)) {
				result.add(grant);
			}
		}
		result.sort((g1, g2) -> Integer.compare(g2.getPath().length(), g1.getPath().length()));
		return result;
	}

	/**
	 * Records a grant, replacing the rights of one that was made before.
	 *
	 * <p>
	 * A grant is identified by owner, path and subject; there is never a second one for the same
	 * three. The store is written before this returns.
	 * </p>
	 */
	public synchronized Grant grant(String owner, String path, String subject, Collection<String> rights)
			throws IOException {
		Grant existing = find(owner, path, subject);
		Grant result;
		if (existing != null) {
			existing.setRights(rights);
			result = existing;
		} else {
			result = new Grant(owner, path, subject, rights, Instant.now().toString());
			_grants.add(result);
		}
		store();
		return result;
	}

	/**
	 * Removes the grant identified by the given owner, path and subject.
	 *
	 * @return Whether there was one; the store is written only if there was.
	 */
	public synchronized boolean revoke(String owner, String path, String subject) throws IOException {
		Grant existing = find(owner, path, subject);
		if (existing == null) {
			return false;
		}
		_grants.remove(existing);
		store();
		return true;
	}

	private Grant find(String owner, String path, String subject) {
		for (Grant grant : _grants) {
			if (grant.getOwner().equals(owner) && grant.getPath().equals(path)
				&& grant.getSubject().equals(subject)) {
				return grant;
			}
		}
		return null;
	}

	private void load() {
		if (!_file.toFile().exists()) {
			return;
		}
		try (Reader reader = new InputStreamReader(Files.newInputStream(_file), StandardCharsets.UTF_8)) {
			_grants = readGrants(new JsonReader(new ReaderAdapter(reader)));
		} catch (IOException | RuntimeException ex) {
			// A broken store must not lock the server up; it grants nothing instead.
			LOG.log(Level.WARNING, "Cannot read the grant store '" + _file + "': " + ex.getMessage());
			_grants = new ArrayList<>();
			_damaged = true;
		}
	}

	private static List<Grant> readGrants(JsonReader in) throws IOException {
		List<Grant> result = new ArrayList<>();
		int version = 0;
		in.beginObject();
		while (in.hasNext()) {
			String key = in.nextName();
			switch (key) {
				case VERSION__PROP:
					version = in.nextInt();
					break;
				case GRANTS__PROP:
					in.beginArray();
					while (in.hasNext()) {
						result.add(readGrant(in));
					}
					in.endArray();
					break;
				default:
					in.skipValue();
					break;
			}
		}
		in.endObject();

		if (version > VERSION) {
			LOG.warning("The grant store was written by a newer version (" + version + " > " + VERSION
				+ "); unknown entries are kept as read.");
		}
		return result;
	}

	private static Grant readGrant(JsonReader in) throws IOException {
		String owner = "";
		String path = "";
		String subject = "";
		String created = "";
		List<String> rights = new ArrayList<>();
		in.beginObject();
		while (in.hasNext()) {
			String key = in.nextName();
			switch (key) {
				case OWNER__PROP:
					owner = in.nextString();
					break;
				case PATH__PROP:
					path = in.nextString();
					break;
				case SUBJECT__PROP:
					subject = in.nextString();
					break;
				case CREATED__PROP:
					created = in.nextString();
					break;
				case RIGHTS__PROP:
					in.beginArray();
					while (in.hasNext()) {
						rights.add(in.nextString());
					}
					in.endArray();
					break;
				default:
					in.skipValue();
					break;
			}
		}
		in.endObject();
		return new Grant(owner, path, subject, rights, created);
	}

	/** Writes this store to disk, atomically: a crash never leaves a half-written store. */
	public synchronized void store() throws IOException {
		Path directory = _file.getParent();
		Files.createDirectories(directory);

		if (_damaged && Files.exists(_file)) {
			// The unreadable file may still hold grants somebody made; keep it for repair.
			Path broken = directory.resolve(FILE_NAME + ".broken-" + Instant.now().toString().replace(':', '-'));
			Files.move(_file, broken, StandardCopyOption.REPLACE_EXISTING);
			LOG.warning("Kept the unreadable grant store as '" + broken + "'.");
		}
		_damaged = false;

		Path tmpFile = Files.createTempFile(directory, "grants", ".json");
		try (Writer writer = new OutputStreamWriter(Files.newOutputStream(tmpFile), StandardCharsets.UTF_8)) {
			try (JsonWriter out = new JsonWriter(new WriterAdapter(writer))) {
				out.beginObject();
				out.name(VERSION__PROP);
				out.value(VERSION);
				out.name(GRANTS__PROP);
				out.beginArray();
				for (Grant grant : _grants) {
					writeGrant(out, grant);
				}
				out.endArray();
				out.endObject();
			}
		}
		Files.move(tmpFile, _file, StandardCopyOption.REPLACE_EXISTING);
	}

	private static void writeGrant(JsonWriter out, Grant grant) throws IOException {
		out.beginObject();
		out.name(OWNER__PROP);
		out.value(grant.getOwner());
		out.name(PATH__PROP);
		out.value(grant.getPath());
		out.name(SUBJECT__PROP);
		out.value(grant.getSubject());
		out.name(RIGHTS__PROP);
		out.beginArray();
		for (String right : grant.getRights()) {
			out.value(right);
		}
		out.endArray();
		out.name(CREATED__PROP);
		out.value(grant.getCreated());
		out.endObject();
	}
}
