/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.faces;

import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.shared.model.Person;
import de.haumacher.imageServer.shared.model.PersonAlias;
import de.haumacher.imageServer.shared.model.PersonCover;
import de.haumacher.imageServer.shared.model.PersonList;
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
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The people of one space, see issue #125.
 *
 * <p>
 * A register beside the {@link UserStore}, in the same {@value UserStore#DIRECTORY_NAME} folder and
 * read the same way: once at start-up, written atomically, one per space. It is the only place a
 * person is <em>named</em>. What an album stores about a face is an
 * {@link de.haumacher.imageServer.shared.model.FaceTag#getPerson() id} and nothing else, so
 * renaming a person is one write here and merging two is one write here, whatever either of them is
 * tagged in.
 * </p>
 *
 * <h2>The file</h2>
 *
 * <pre>
 * {"version":1,"people":[{"id":"&lt;22 chars&gt;","name":"Anna","nickname":"Anni","cover":{"path":"2024/Trip/IMG_1.jpg","face":0},
 *                         "aliases":["&lt;id&gt;"],"user":"anna","created":"2026-09-21T10:11:12Z",
 *                         "createdBy":"user:haui"}]}
 * </pre>
 *
 * <p>
 * The {@link Person#getId() id} is sixteen random bytes, Base64url without padding &mdash; opaque,
 * stable and deliberately not derived from the name, which is editable. It is never reused: a
 * person merged into another one leaves their id behind as an
 * {@link #resolve(String) alias} of the survivor, so that every id that was ever handed out goes on
 * resolving and no album has to be rewritten for a merge.
 * </p>
 *
 * <p>
 * {@link Person#getUser() user} is the member of the space this person is (issue #128), written by
 * {@link #link(String, String)} and read by {@link #byUser(String)}; it is a name and not a user
 * object, because this register knows the people of the space and deliberately not its accounts.
 * {@link Person#getCover() cover} is opaque here &mdash; issue #126 sets it, and nothing here
 * resolves the path it names.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class PeopleStore {

	private static final Logger LOG = Logger.getLogger(PeopleStore.class.getName());

	/** The name of the register within {@value UserStore#DIRECTORY_NAME}. */
	public static final String FILE_NAME = "people.json";

	/** The version this build writes. */
	public static final int VERSION = 1;

	/** The number of random bytes an id is built from; it is a name, not a secret. */
	private static final int ID_BYTES = 16;

	private static final String VERSION__PROP = "version";

	private static final String PEOPLE__PROP = "people";

	private static final String ID__PROP = "id";

	private static final String NAME__PROP = "name";

	private static final String NICKNAME__PROP = "nickname";

	private static final String COVER__PROP = "cover";

	private static final String PATH__PROP = "path";

	private static final String FACE__PROP = "face";

	private static final String ALIASES__PROP = "aliases";

	private static final String USER__PROP = "user";

	private static final String CREATED__PROP = "created";

	private static final String CREATED_BY__PROP = "createdBy";

	/** Why a person cannot be created or renamed without a name. */
	public static final String NAME_REQUIRED = "A person needs a name.";

	/**
	 * A typed name, split into what a person is called officially and what they are called on a
	 * photograph, see {@link PeopleStore#parseName(String)}.
	 */
	public static final class Name {

		private final String _canonical;

		private final String _nickname;

		Name(String canonical, String nickname) {
			_canonical = canonical;
			_nickname = nickname;
		}

		/** The full name, unique within the space, see {@link Person#getName()}. */
		public String getCanonical() {
			return _canonical;
		}

		/** What to call them on a photograph, empty where that is the canonical name. */
		public String getNickname() {
			return _nickname;
		}

		@Override
		public String toString() {
			return _nickname.isEmpty() ? _canonical : _canonical + " (" + _nickname + ")";
		}
	}

	/**
	 * Splits a typed name into a canonical name and a nickname, see issue #146.
	 *
	 * <p>
	 * <b>The one place the convention lives.</b> A person has a full name and may be called
	 * something else on a photograph, and both are typed into one field:
	 * <code>Berta M&uuml;ller (Tante Berta)</code> is <em>Berta M&uuml;ller</em>, shown as
	 * <em>Tante Berta</em>. Every way into the register goes through here &mdash;
	 * <code>?action=create-person</code> and <code>?action=rename-person</code> &mdash; so there is
	 * one rule for every client and a request carries one string, as it always did.
	 * </p>
	 *
	 * <p>
	 * The rule: the typed string is trimmed, and where it <b>ends</b> with <code>)</code> and the
	 * <code>(</code> matching that closing bracket has non-blank text before it, what is in the
	 * bracket is the nickname and what stands before it the canonical name, both trimmed. Anything
	 * else is a canonical name with no nickname, exactly as typed &mdash; <code>Berta (Tante
	 * Berta) M&uuml;ller</code> is somebody's name and not a convention. Nesting is counted, so
	 * <code>A (B (C))</code> is <em>A</em> called <em>B (C)</em>, and an empty bracket
	 * (<code>Berta ()</code>) is simply no nickname, which is how one is cleared by editing.
	 * </p>
	 *
	 * <p>
	 * Composed back for editing by the client ({@code fullLabel}), so that a round trip through
	 * the rename dialog keeps both.
	 * </p>
	 *
	 * @throws PersonRefused
	 *         With {@link #NAME_REQUIRED} where nothing but a nickname was typed
	 *         (<code>(Oma)</code>) or nothing at all: a person is identified by their canonical
	 *         name, and a register of nicknames alone would have nothing to be unique about.
	 */
	public static Name parseName(String typed) throws PersonRefused {
		String trimmed = typed == null ? "" : typed.trim();
		if (trimmed.isEmpty()) {
			throw new PersonRefused(400, NAME_REQUIRED);
		}
		if (trimmed.charAt(trimmed.length() - 1) == ')') {
			int open = matchingBracket(trimmed);
			if (open >= 0) {
				String canonical = trimmed.substring(0, open).trim();
				String nickname = trimmed.substring(open + 1, trimmed.length() - 1).trim();
				if (!canonical.isEmpty()) {
					return new Name(canonical, nickname);
				}
				if (nickname.isEmpty()) {
					// "()" and nothing else: no name at all.
					throw new PersonRefused(400, NAME_REQUIRED);
				}
				// "(Oma)": a nickname without anybody to be the nickname of.
				throw new PersonRefused(400, NAME_REQUIRED);
			}
		}
		return new Name(trimmed, "");
	}

	/**
	 * The index of the <code>(</code> matching the <code>)</code> the given string ends with,
	 * <code>-1</code> where the brackets do not balance out.
	 */
	private static int matchingBracket(String text) {
		int depth = 0;
		for (int n = text.length() - 1; n >= 0; n--) {
			char c = text.charAt(n);
			if (c == ')') {
				depth++;
			} else if (c == '(') {
				depth--;
				if (depth == 0) {
					return n;
				}
			}
		}
		return -1;
	}

	/** Why a person cannot be merged into themselves. */
	public static final String MERGE_SELF = "A person cannot be merged into themselves.";

	/** Why a name cannot be given to a second person. */
	public static String personExists(String name) {
		return "There is already somebody called '" + name + "' here.";
	}

	/** Why an id names nobody of this space. */
	public static String unknownPerson(String id) {
		return "There is nobody with the id '" + id + "' here.";
	}

	/** Why two people who are both a member of the space are not merged, see issue #128. */
	public static final String MERGE_LINKED =
		"Both of these people are a member of this space; unlink one of them first.";

	/** Why a member cannot be a second person. */
	public static String userAlreadyLinked(String name) {
		return "'" + name + "' is already linked to somebody else here.";
	}

	/** One person, as this register keeps them. */
	public static final class Entry {

		private final String _id;

		private String _name;

		private String _nickname = "";

		private String _coverPath = "";

		private int _coverFace;

		private final List<String> _aliases = new ArrayList<>();

		private String _user = "";

		private final String _created;

		private final String _createdBy;

		Entry(String id, String name, String created, String createdBy) {
			_id = id;
			_name = name == null ? "" : name;
			_created = created == null ? "" : created;
			_createdBy = createdBy == null ? "" : createdBy;
		}

		/** The identifier of this person, see {@link Person#getId()}. */
		public String getId() {
			return _id;
		}

		/** The canonical name of this person, see {@link Person#getName()}. */
		public String getName() {
			return _name;
		}

		/**
		 * What to call this person on a photograph, empty where the {@link #getName() name} is
		 * what they are called, see issue #146.
		 */
		public String getNickname() {
			return _nickname;
		}

		/** The photograph this person is shown by, empty when nobody chose one. */
		public String getCoverPath() {
			return _coverPath;
		}

		/** Which face of {@link #getCoverPath()}. */
		public int getCoverFace() {
			return _coverFace;
		}

		/** Sets the face this person is shown by; nothing here resolves it (issue #126). */
		public void setCover(String path, int face) {
			_coverPath = path == null ? "" : path;
			_coverFace = face;
		}

		/** The ids merged into this person, in the order they were merged. */
		public List<String> getAliases() {
			return Collections.unmodifiableList(_aliases);
		}

		/** The member of the space this person is, empty when they are nobody in particular. */
		public String getUser() {
			return _user;
		}

		/** Sets the member of the space this person is; see {@link PeopleStore#link(String, String)}. */
		void setUser(String user) {
			_user = user == null ? "" : user;
		}

		/** When this person was created, an ISO-8601 instant. */
		public String getCreated() {
			return _created;
		}

		/** Who created this person, a {@code Caller.subject()}. */
		public String getCreatedBy() {
			return _createdBy;
		}

		/** This person on the wire, see {@link Person}. */
		public Person toWire() {
			Person result =
				Person.create().setId(_id).setName(_name).setNickname(_nickname).setUser(_user);
			if (!_coverPath.isEmpty()) {
				result.setCover(PersonCover.create().setPath(_coverPath).setFace(_coverFace));
			}
			List<PersonAlias> aliases = new ArrayList<>(_aliases.size());
			for (String alias : _aliases) {
				aliases.add(PersonAlias.create().setId(alias));
			}
			result.setAliases(aliases);
			return result;
		}
	}

	private final Path _file;

	private final SecureRandom _random = new SecureRandom();

	/** Whether the file on disk could not be read; nothing is ever written over such a file. */
	private boolean _damaged;

	/** The people by id, in the order they were created. */
	private Map<String, Entry> _people = new LinkedHashMap<>();

	/** Which person an alias stands for, by alias id. */
	private Map<String, String> _aliases = new LinkedHashMap<>();

	/**
	 * Creates the register of the space rooted at the given path and reads it.
	 *
	 * <p>
	 * A register that cannot be read is <em>not</em> silently replaced by an empty one: it is kept
	 * and every write is refused, because the names in it are the only thing that gives an album's
	 * tags a meaning, see {@link #store()}.
	 * </p>
	 */
	public PeopleStore(Path basePath) {
		_file = basePath.resolve(UserStore.DIRECTORY_NAME).resolve(FILE_NAME);
		load();
	}

	/** The file this register is persisted in. */
	public Path getFile() {
		return _file;
	}

	/** The people of this space, in the order they were created; merged-away ones are not here. */
	public synchronized List<Entry> getPeople() {
		return Collections.unmodifiableList(new ArrayList<>(_people.values()));
	}

	/** Everybody of this space, on the wire. */
	public synchronized PersonList toWire() {
		PersonList result = PersonList.create();
		for (Entry entry : _people.values()) {
			result.addPeople(entry.toWire());
		}
		return result;
	}

	/**
	 * The person the given id names, <code>null</code> when nobody does.
	 *
	 * <p>
	 * <b>The one place an alias is resolved</b>, and therefore the one place a merge is felt: an id
	 * that was merged away answers the person it was merged into, so a tag written before the merge
	 * goes on naming somebody without the album it stands in ever being touched.
	 * </p>
	 */
	public synchronized Entry resolve(String id) {
		if (id == null || id.isEmpty()) {
			return null;
		}
		Entry result = _people.get(id);
		if (result != null) {
			return result;
		}
		String survivor = _aliases.get(id);
		return survivor == null ? null : _people.get(survivor);
	}

	/**
	 * The person of the given name, <code>null</code> when nobody carries it.
	 *
	 * <p>
	 * Ignoring case and outer blanks, which is the same rule {@link #nameTaken(String, String)}
	 * refuses a second person of one name by: a register never holds two people whose names differ
	 * only in their case, so this answer is unambiguous. Only survivors are looked at &mdash; a
	 * person merged away is nobody, and an id of theirs is resolved by {@link #resolve(String)}.
	 * </p>
	 *
	 * <p>
	 * A typed name is looked for, so a {@link Entry#getNickname() nickname} answers too (issue
	 * #146): whoever types &quot;Tante Berta&quot; means her. The canonical name is looked at
	 * first, because that one is unique and a nickname is not &mdash; of two grandmothers both
	 * called &quot;Oma&quot; this answers the first, which is why nothing that <em>decides</em>
	 * anything uses this: {@link #nameTaken(String, String)} and the import go by the canonical
	 * name alone.
	 * </p>
	 */
	public synchronized Entry byName(String name) {
		String trimmed = name == null ? "" : name.trim();
		if (trimmed.isEmpty()) {
			return null;
		}
		Entry canonical = byCanonicalName(trimmed);
		if (canonical != null) {
			return canonical;
		}
		for (Entry entry : _people.values()) {
			if (entry.getNickname().equalsIgnoreCase(trimmed)) {
				return entry;
			}
		}
		return null;
	}

	/** The person of the given canonical name, <code>null</code> when nobody carries it. */
	public synchronized Entry byCanonicalName(String name) {
		String trimmed = name == null ? "" : name.trim();
		if (trimmed.isEmpty()) {
			return null;
		}
		for (Entry entry : _people.values()) {
			if (entry.getName().equalsIgnoreCase(trimmed)) {
				return entry;
			}
		}
		return null;
	}

	/**
	 * The person of the given name, created with the given creator where the register has none.
	 *
	 * <p>
	 * The one step {@link de.haumacher.imageServer.faces.FaceImport} takes a name out of a file
	 * with: looking up and creating are one atomic act here, so a name that occurs twice arrives
	 * as one person, see issue #129.
	 * </p>
	 *
	 * <p>
	 * <b>Canonical, both ways</b> (issue #146): a tool wrote this name, nobody typed a convention,
	 * so a bracket in it is part of the name and is neither split off nor matched against a
	 * nickname. Were a nickname matched here, a register holding &quot;Oma&quot; as the nickname
	 * of somebody would swallow an imported &quot;Oma M&uuml;ller&quot;&#x2014;no, worse: an
	 * imported &quot;Oma&quot; would silently become that person, which is a guess and not a
	 * decision anybody made.
	 * </p>
	 */
	public synchronized Entry named(String name, String createdBy) throws PersonRefused, IOException {
		Entry existing = byCanonicalName(name);
		if (existing != null) {
			return existing;
		}
		return addPerson(name == null ? "" : name.trim(), "", createdBy);
	}

	/** Whether anybody but the given person carries the given name, ignoring case. */
	private boolean nameTaken(String name, String exceptId) {
		for (Entry entry : _people.values()) {
			if (!entry.getId().equals(exceptId) && entry.getName().equalsIgnoreCase(name)) {
				return true;
			}
		}
		return false;
	}

	/** Why a register operation was refused; every one of them names its reason. */
	public static class PersonRefused extends Exception {

		private final int _status;

		PersonRefused(int status, String message) {
			super(message);
			_status = status;
		}

		/** The HTTP status this refusal is answered with. */
		public int getStatus() {
			return _status;
		}
	}

	/**
	 * Creates a person with the given name and writes the register.
	 *
	 * @param createdBy
	 *        The {@code Caller.subject()} of whoever asked.
	 */
	public synchronized Entry create(String name, String createdBy) throws PersonRefused, IOException {
		Name parsed = parseName(name);
		return addPerson(parsed.getCanonical(), parsed.getNickname(), createdBy);
	}

	/** Creates a person of the given canonical name and nickname; the one place one is added. */
	private Entry addPerson(String canonical, String nickname, String createdBy)
			throws PersonRefused, IOException {
		if (canonical.isEmpty()) {
			throw new PersonRefused(400, NAME_REQUIRED);
		}
		if (nameTaken(canonical, null)) {
			throw new PersonRefused(409, personExists(canonical));
		}
		Entry entry = new Entry(newId(), canonical, Instant.now().toString(), createdBy);
		entry._nickname = nickname;
		_people.put(entry.getId(), entry);
		store();
		return entry;
	}

	/**
	 * Renames the person the given id names and writes the register.
	 *
	 * <p>
	 * The typed name says both things (issue #146): it is split by {@link #parseName(String)}, so
	 * a name without a bracket <b>clears</b> the nickname the person had &mdash; the field holds
	 * the whole statement, and deleting the bracket is how one takes a nickname back.
	 * </p>
	 */
	public synchronized Entry rename(String id, String name) throws PersonRefused, IOException {
		Entry entry = resolve(id);
		if (entry == null) {
			throw new PersonRefused(404, unknownPerson(id == null ? "" : id));
		}
		Name parsed = parseName(name);
		if (nameTaken(parsed.getCanonical(), entry.getId())) {
			throw new PersonRefused(409, personExists(parsed.getCanonical()));
		}
		entry._name = parsed.getCanonical();
		entry._nickname = parsed.getNickname();
		store();
		return entry;
	}

	/**
	 * Makes the second person an alias of the first and writes the register.
	 *
	 * <p>
	 * The survivor keeps their id, their name, their nickname and their cover; the other one's
	 * aliases are carried along, so a chain of merges stays one lookup deep, and their cover
	 * &mdash; like their nickname (issue #146) &mdash; is taken over only where the survivor has
	 * none. Not one album is touched: what an album says goes on being an id, and
	 * the id it says goes on resolving, see {@link #resolve(String)}.
	 * </p>
	 */
	public synchronized Entry merge(String into, String from) throws PersonRefused, IOException {
		Entry survivor = resolve(into);
		if (survivor == null) {
			throw new PersonRefused(404, unknownPerson(into == null ? "" : into));
		}
		Entry merged = resolve(from);
		if (merged == null) {
			throw new PersonRefused(404, unknownPerson(from == null ? "" : from));
		}
		if (survivor.getId().equals(merged.getId())) {
			throw new PersonRefused(400, MERGE_SELF);
		}
		if (!survivor.getUser().isEmpty() && !merged.getUser().isEmpty()) {
			// Two members are two people, whatever they look like: one of the two links is wrong,
			// and which one is not for this server to guess, see issue #128.
			throw new PersonRefused(409, MERGE_LINKED);
		}
		_people.remove(merged.getId());
		survivor._aliases.add(merged.getId());
		_aliases.put(merged.getId(), survivor.getId());
		for (String alias : merged.getAliases()) {
			survivor._aliases.add(alias);
			_aliases.put(alias, survivor.getId());
		}
		if (survivor.getNickname().isEmpty()) {
			// What the survivor is called stands; where they are called nothing in particular,
			// what the other one was called is better than nothing, see issue #146.
			survivor._nickname = merged.getNickname();
		}
		if (survivor.getCoverPath().isEmpty() && !merged.getCoverPath().isEmpty()) {
			survivor.setCover(merged.getCoverPath(), merged.getCoverFace());
		}
		if (survivor.getUser().isEmpty()) {
			// At most one of the two carried a member (see above), so this either takes the one
			// the other had or changes nothing.
			survivor.setUser(merged.getUser());
		}
		store();
		return survivor;
	}

	/**
	 * Links the person the given id names to the given member of the space, or unlinks them, and
	 * writes the register, see issue #128.
	 *
	 * <p>
	 * The link lives here and nowhere else: <code>?type=users</code> answers it by looking it up
	 * from the other end, so there is one thing to write and nothing to keep in step. An empty
	 * name unlinks, and unlinking somebody who was not linked is no error.
	 * </p>
	 *
	 * <p>
	 * <b>One member is at most one person.</b> Two people who are both "the same member" would
	 * make "photos of me" mean two different things, so a name another person already holds is
	 * refused rather than moved.
	 * </p>
	 *
	 * <p>
	 * Whether the name is a member of this space at all is decided by the caller: this register
	 * knows the people of the space and deliberately not its users, see {@link Person#getUser()}.
	 * </p>
	 */
	public synchronized Entry link(String id, String user) throws PersonRefused, IOException {
		Entry entry = resolve(id);
		if (entry == null) {
			throw new PersonRefused(404, unknownPerson(id == null ? "" : id));
		}
		String name = user == null ? "" : user.trim();
		if (name.equals(entry.getUser())) {
			// Already so; nothing is written for a statement that is already on disk.
			return entry;
		}
		if (!name.isEmpty()) {
			Entry other = byUser(name);
			if (other != null) {
				throw new PersonRefused(409, userAlreadyLinked(name));
			}
		}
		entry.setUser(name);
		store();
		return entry;
	}

	/** The person linked to the given member, <code>null</code> when nobody is (issue #128). */
	public synchronized Entry byUser(String user) {
		if (user == null || user.isEmpty()) {
			return null;
		}
		for (Entry entry : _people.values()) {
			if (entry.getUser().equals(user)) {
				return entry;
			}
		}
		return null;
	}

	/**
	 * Takes the given member off whichever person carries them, see issue #128.
	 *
	 * <p>
	 * What <code>?action=remove-user</code> does: the account goes and the person stays, tags and
	 * all &mdash; the photographs of a former member are still photographs of that person.
	 * </p>
	 *
	 * @return Whether anything was linked at all.
	 */
	public synchronized boolean unlinkUser(String user) throws IOException {
		Entry entry = byUser(user);
		if (entry == null) {
			return false;
		}
		entry.setUser("");
		store();
		return true;
	}

	/** An identifier nobody of this space holds, see {@link Person#getId()}. */
	private String newId() {
		byte[] bytes = new byte[ID_BYTES];
		while (true) {
			_random.nextBytes(bytes);
			String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
			if (!_people.containsKey(id) && !_aliases.containsKey(id)) {
				return id;
			}
		}
	}

	// --- The file. ---

	private void load() {
		if (!Files.isRegularFile(_file)) {
			return;
		}
		try (Reader reader = new InputStreamReader(Files.newInputStream(_file), StandardCharsets.UTF_8)) {
			read(new JsonReader(new ReaderAdapter(reader)));
		} catch (IOException | RuntimeException ex) {
			// The names of the people are the only thing that gives a tag a meaning; an
			// unreadable register is kept and no write is allowed over it.
			LOG.log(Level.WARNING, "Cannot read the people of '" + _file + "': " + ex.getMessage());
			_people = new LinkedHashMap<>();
			_aliases = new LinkedHashMap<>();
			_damaged = true;
		}
	}

	private void read(JsonReader in) throws IOException {
		Map<String, Entry> people = new LinkedHashMap<>();
		Map<String, String> aliases = new LinkedHashMap<>();
		in.beginObject();
		while (in.hasNext()) {
			String key = in.nextName();
			if (PEOPLE__PROP.equals(key)) {
				in.beginArray();
				while (in.hasNext()) {
					Entry entry = readPerson(in);
					if (entry.getId().isEmpty()) {
						continue;
					}
					people.put(entry.getId(), entry);
					for (String alias : entry.getAliases()) {
						aliases.put(alias, entry.getId());
					}
				}
				in.endArray();
			} else {
				in.skipValue();
			}
		}
		in.endObject();
		_people = people;
		_aliases = aliases;
	}

	private static Entry readPerson(JsonReader in) throws IOException {
		String id = "";
		String name = "";
		String nickname = "";
		String created = "";
		String createdBy = "";
		String user = "";
		String coverPath = "";
		int coverFace = 0;
		List<String> aliases = new ArrayList<>();
		in.beginObject();
		while (in.hasNext()) {
			String key = in.nextName();
			switch (key) {
				case ID__PROP:
					id = in.nextString();
					break;
				case NAME__PROP:
					name = in.nextString();
					break;
				case NICKNAME__PROP:
					// Absent in a register written before issue #146, which reads as "no nickname".
					nickname = in.nextString();
					break;
				case USER__PROP:
					user = in.nextString();
					break;
				case CREATED__PROP:
					created = in.nextString();
					break;
				case CREATED_BY__PROP:
					createdBy = in.nextString();
					break;
				case ALIASES__PROP:
					in.beginArray();
					while (in.hasNext()) {
						aliases.add(in.nextString());
					}
					in.endArray();
					break;
				case COVER__PROP:
					in.beginObject();
					while (in.hasNext()) {
						String field = in.nextName();
						if (PATH__PROP.equals(field)) {
							coverPath = in.nextString();
						} else if (FACE__PROP.equals(field)) {
							coverFace = in.nextInt();
						} else {
							in.skipValue();
						}
					}
					in.endObject();
					break;
				default:
					in.skipValue();
					break;
			}
		}
		in.endObject();
		Entry result = new Entry(id, name, created, createdBy);
		result._nickname = nickname;
		result._user = user;
		result.setCover(coverPath, coverFace);
		result._aliases.addAll(aliases);
		return result;
	}

	/** Writes this register to disk, atomically: a crash never leaves half a register. */
	public synchronized void store() throws IOException {
		if (_damaged) {
			throw new IOException("The people of '" + _file + "' could not be read; nothing is written over them.");
		}
		Path directory = _file.getParent();
		Files.createDirectories(directory);
		Path tmp = Files.createTempFile(directory, "people", ".json");
		try (Writer writer = new OutputStreamWriter(Files.newOutputStream(tmp), StandardCharsets.UTF_8);
				JsonWriter out = new JsonWriter(new WriterAdapter(writer))) {
			out.beginObject();
			out.name(VERSION__PROP);
			out.value(VERSION);
			out.name(PEOPLE__PROP);
			out.beginArray();
			for (Entry entry : _people.values()) {
				writePerson(out, entry);
			}
			out.endArray();
			out.endObject();
		}
		Files.move(tmp, _file, StandardCopyOption.REPLACE_EXISTING);
	}

	private static void writePerson(JsonWriter out, Entry entry) throws IOException {
		out.beginObject();
		out.name(ID__PROP);
		out.value(entry.getId());
		out.name(NAME__PROP);
		out.value(entry.getName());
		if (!entry.getNickname().isEmpty()) {
			// Written only where there is one, so a register of people without nicknames stays
			// the file it was before issue #146.
			out.name(NICKNAME__PROP);
			out.value(entry.getNickname());
		}
		if (!entry.getCoverPath().isEmpty()) {
			out.name(COVER__PROP);
			out.beginObject();
			out.name(PATH__PROP);
			out.value(entry.getCoverPath());
			out.name(FACE__PROP);
			out.value(entry.getCoverFace());
			out.endObject();
		}
		out.name(ALIASES__PROP);
		out.beginArray();
		for (String alias : entry.getAliases()) {
			out.value(alias);
		}
		out.endArray();
		out.name(USER__PROP);
		out.value(entry.getUser());
		out.name(CREATED__PROP);
		out.value(entry.getCreated());
		out.name(CREATED_BY__PROP);
		out.value(entry.getCreatedBy());
		out.endObject();
	}
}
