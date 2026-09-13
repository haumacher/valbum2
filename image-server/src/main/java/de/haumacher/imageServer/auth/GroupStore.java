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
 * The named user groups of this server, persisted beside the album tree (issue #49).
 *
 * <p>
 * A group is a named member list, created by any member and owned by its creator, and it is usable
 * wherever a single user can be named, see {@link Subjects#GROUP_PREFIX}. A group name follows the
 * rules a user name follows ({@link UserStore#checkUserName(String)}), so that
 * <code>group:family</code> and <code>user:family</code> read the same way round.
 * </p>
 *
 * <p>
 * The store lives in {@link UserStore#DIRECTORY_NAME} at the root of the served folder and is
 * written in this class's own JSON, not in the protocol's. The file format is persisted data and
 * therefore versioned:
 * </p>
 *
 * <pre>
 * {"version":1,"groups":[{"name":"family","owner":"alice","members":["bob","carol"],
 *   "created":"2026-09-12T10:11:12Z"}]}
 * </pre>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class GroupStore {

	private static final Logger LOG = Logger.getLogger(GroupStore.class.getName());

	/** The name of the store within {@link UserStore#DIRECTORY_NAME}. */
	public static final String FILE_NAME = "groups.json";

	/** The version this build writes, see {@link GroupStore}. */
	public static final int VERSION = 1;

	private static final String VERSION__PROP = "version";

	private static final String GROUPS__PROP = "groups";

	private static final String NAME__PROP = "name";

	private static final String OWNER__PROP = "owner";

	private static final String MEMBERS__PROP = "members";

	private static final String CREATED__PROP = "created";

	/** A named list of users, see {@link GroupStore}. */
	public static final class Group {

		private String _name;

		private final String _owner;

		private Set<String> _members;

		private final String _created;

		/** Creates a {@link Group}. */
		public Group(String name, String owner, Collection<String> members, String created) {
			_name = name;
			_owner = owner;
			_members = new LinkedHashSet<>(members);
			_created = created;
		}

		/** The name of the group, which is what a <code>group:</code> subject names. */
		public String getName() {
			return _name;
		}

		/**
		 * See {@link #getName()}.
		 *
		 * <p>
		 * A group is renamed through {@link GroupStore#rename(Group, String)} only: the name is
		 * also what every grant made out to the group says, and the two are changed together, see
		 * issue #55.
		 * </p>
		 */
		void setName(String name) {
			_name = name;
		}

		/** The name of the user who created the group and may change it. */
		public String getOwner() {
			return _owner;
		}

		/** The names of the users in the group, in the order they were added. */
		public Set<String> getMembers() {
			return Collections.unmodifiableSet(_members);
		}

		/** See {@link #getMembers()}. */
		void setMembers(Collection<String> members) {
			_members = new LinkedHashSet<>(members);
		}

		/** When the group was created, an ISO-8601 instant. */
		public String getCreated() {
			return _created;
		}

		/** Whether the user of the given name is in this group. */
		public boolean holds(String userName) {
			return _members.contains(userName);
		}

		@Override
		public String toString() {
			return _name + "@" + _owner + _members;
		}
	}

	private final Path _file;

	private List<Group> _groups = new ArrayList<>();

	/** Whether the file on disk could not be read; it is set aside instead of overwritten. */
	private boolean _damaged;

	/** Creates a {@link GroupStore} for the album tree rooted at the given path. */
	public GroupStore(Path basePath) {
		_file = basePath.resolve(UserStore.DIRECTORY_NAME).resolve(FILE_NAME);
		load();
	}

	/** The file this store is persisted in. */
	public Path getFile() {
		return _file;
	}

	/** Every group of this server, in the order they were created. */
	public synchronized List<Group> getGroups() {
		return Collections.unmodifiableList(new ArrayList<>(_groups));
	}

	/**
	 * The group of the given name.
	 *
	 * @return <code>null</code> if no such group exists.
	 */
	public synchronized Group getGroup(String name) {
		for (Group group : _groups) {
			if (group.getName().equals(name)) {
				return group;
			}
		}
		return null;
	}

	/** The names of the groups the user of the given name is a member of. */
	public synchronized Set<String> groupsOf(String userName) {
		Set<String> result = new LinkedHashSet<>();
		for (Group group : _groups) {
			if (group.holds(userName)) {
				result.add(group.getName());
			}
		}
		return result;
	}

	/** The groups the user of the given name owns, and the groups they are in. */
	public synchronized List<Group> visibleTo(String userName) {
		List<Group> result = new ArrayList<>();
		for (Group group : _groups) {
			if (group.getOwner().equals(userName)) {
				result.add(group);
			}
		}
		for (Group group : _groups) {
			if (!group.getOwner().equals(userName) && group.holds(userName)) {
				result.add(group);
			}
		}
		return result;
	}

	/**
	 * Creates the group of the given name, or replaces the members of an existing one.
	 *
	 * <p>
	 * The owner of an existing group is never changed here: whether the caller may touch it at all
	 * is decided before, see {@link AuthService}. The store is written before this returns.
	 * </p>
	 */
	public synchronized Group put(String name, String owner, Collection<String> members) throws IOException {
		Group existing = getGroup(name);
		Group result;
		if (existing != null) {
			existing.setMembers(members);
			result = existing;
		} else {
			result = new Group(name, owner, members, Instant.now().toString());
			_groups.add(result);
		}
		store();
		return result;
	}

	/**
	 * Renames the given group, see issue #55.
	 *
	 * <p>
	 * Owner, members and creation date are kept: it is the same group under another name. The
	 * grants made out to it are <em>not</em> rewritten here — that is the second half of the
	 * operation and it lives in {@link AuthService#renameGroup(AuthService.Caller, String, String)},
	 * which is the only caller. The store is written before this returns.
	 * </p>
	 */
	public synchronized Group rename(Group group, String newName) throws IOException {
		if (group.getName().equals(newName)) {
			return group;
		}
		group.setName(newName);
		store();
		return group;
	}

	/**
	 * Removes the group of the given name.
	 *
	 * @return Whether there was one; the store is written only if there was.
	 */
	public synchronized boolean remove(String name) throws IOException {
		Group existing = getGroup(name);
		if (existing == null) {
			return false;
		}
		_groups.remove(existing);
		store();
		return true;
	}

	private void load() {
		if (!_file.toFile().exists()) {
			return;
		}
		try (Reader reader = new InputStreamReader(Files.newInputStream(_file), StandardCharsets.UTF_8)) {
			_groups = readGroups(new JsonReader(new ReaderAdapter(reader)));
		} catch (IOException | RuntimeException ex) {
			// A broken store must not lock the server up; it knows no group instead.
			LOG.log(Level.WARNING, "Cannot read the group store '" + _file + "': " + ex.getMessage());
			_groups = new ArrayList<>();
			_damaged = true;
		}
	}

	private static List<Group> readGroups(JsonReader in) throws IOException {
		List<Group> result = new ArrayList<>();
		int version = 0;
		in.beginObject();
		while (in.hasNext()) {
			String key = in.nextName();
			switch (key) {
				case VERSION__PROP:
					version = in.nextInt();
					break;
				case GROUPS__PROP:
					in.beginArray();
					while (in.hasNext()) {
						result.add(readGroup(in));
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
			LOG.warning("The group store was written by a newer version (" + version + " > " + VERSION
				+ "); unknown entries are kept as read.");
		}
		return result;
	}

	private static Group readGroup(JsonReader in) throws IOException {
		String name = "";
		String owner = "";
		String created = "";
		List<String> members = new ArrayList<>();
		in.beginObject();
		while (in.hasNext()) {
			String key = in.nextName();
			switch (key) {
				case NAME__PROP:
					name = in.nextString();
					break;
				case OWNER__PROP:
					owner = in.nextString();
					break;
				case CREATED__PROP:
					created = in.nextString();
					break;
				case MEMBERS__PROP:
					in.beginArray();
					while (in.hasNext()) {
						members.add(in.nextString());
					}
					in.endArray();
					break;
				default:
					in.skipValue();
					break;
			}
		}
		in.endObject();
		return new Group(name, owner, members, created);
	}

	/** Writes this store to disk, atomically: a crash never leaves a half-written store. */
	public synchronized void store() throws IOException {
		Path directory = _file.getParent();
		Files.createDirectories(directory);

		if (_damaged && Files.exists(_file)) {
			// The unreadable file may still hold groups somebody built; keep it for repair.
			Path broken = directory.resolve(FILE_NAME + ".broken-" + Instant.now().toString().replace(':', '-'));
			Files.move(_file, broken, StandardCopyOption.REPLACE_EXISTING);
			LOG.warning("Kept the unreadable group store as '" + broken + "'.");
		}
		_damaged = false;

		Path tmpFile = Files.createTempFile(directory, "groups", ".json");
		try (Writer writer = new OutputStreamWriter(Files.newOutputStream(tmpFile), StandardCharsets.UTF_8)) {
			try (JsonWriter out = new JsonWriter(new WriterAdapter(writer))) {
				out.beginObject();
				out.name(VERSION__PROP);
				out.value(VERSION);
				out.name(GROUPS__PROP);
				out.beginArray();
				for (Group group : _groups) {
					writeGroup(out, group);
				}
				out.endArray();
				out.endObject();
			}
		}
		Files.move(tmpFile, _file, StandardCopyOption.REPLACE_EXISTING);
	}

	private static void writeGroup(JsonWriter out, Group group) throws IOException {
		out.beginObject();
		out.name(NAME__PROP);
		out.value(group.getName());
		out.name(OWNER__PROP);
		out.value(group.getOwner());
		out.name(MEMBERS__PROP);
		out.beginArray();
		for (String member : group.getMembers()) {
			out.value(member);
		}
		out.endArray();
		out.name(CREATED__PROP);
		out.value(group.getCreated());
		out.endObject();
	}
}
