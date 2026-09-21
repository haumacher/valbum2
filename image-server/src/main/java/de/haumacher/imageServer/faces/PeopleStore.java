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
 * {"version":1,"people":[{"id":"&lt;22 chars&gt;","name":"Anna","cover":{"path":"2024/Trip/IMG_1.jpg","face":0},
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
 * {@link Person#getUser() user} is opaque here: issue #128 writes it, this build stores it and
 * answers it and draws no conclusion from it. {@link Person#getCover() cover} is the same &mdash;
 * issue #126 sets it, and nothing here resolves the path it names.
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

	private static final String COVER__PROP = "cover";

	private static final String PATH__PROP = "path";

	private static final String FACE__PROP = "face";

	private static final String ALIASES__PROP = "aliases";

	private static final String USER__PROP = "user";

	private static final String CREATED__PROP = "created";

	private static final String CREATED_BY__PROP = "createdBy";

	/** Why a person cannot be created or renamed without a name. */
	public static final String NAME_REQUIRED = "A person needs a name.";

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

	/** One person, as this register keeps them. */
	public static final class Entry {

		private final String _id;

		private String _name;

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

		/** What to call this person. */
		public String getName() {
			return _name;
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
			Person result = Person.create().setId(_id).setName(_name).setUser(_user);
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
		String trimmed = name == null ? "" : name.trim();
		if (trimmed.isEmpty()) {
			throw new PersonRefused(400, NAME_REQUIRED);
		}
		if (nameTaken(trimmed, null)) {
			throw new PersonRefused(409, personExists(trimmed));
		}
		Entry entry = new Entry(newId(), trimmed, Instant.now().toString(), createdBy);
		_people.put(entry.getId(), entry);
		store();
		return entry;
	}

	/** Renames the person the given id names and writes the register. */
	public synchronized Entry rename(String id, String name) throws PersonRefused, IOException {
		Entry entry = resolve(id);
		if (entry == null) {
			throw new PersonRefused(404, unknownPerson(id == null ? "" : id));
		}
		String trimmed = name == null ? "" : name.trim();
		if (trimmed.isEmpty()) {
			throw new PersonRefused(400, NAME_REQUIRED);
		}
		if (nameTaken(trimmed, entry.getId())) {
			throw new PersonRefused(409, personExists(trimmed));
		}
		entry._name = trimmed;
		store();
		return entry;
	}

	/**
	 * Makes the second person an alias of the first and writes the register.
	 *
	 * <p>
	 * The survivor keeps their id, their name and their cover; the other one's aliases are carried
	 * along, so a chain of merges stays one lookup deep, and their cover is taken over only where
	 * the survivor has none. Not one album is touched: what an album says goes on being an id, and
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
		_people.remove(merged.getId());
		survivor._aliases.add(merged.getId());
		_aliases.put(merged.getId(), survivor.getId());
		for (String alias : merged.getAliases()) {
			survivor._aliases.add(alias);
			_aliases.put(alias, survivor.getId());
		}
		if (survivor.getCoverPath().isEmpty() && !merged.getCoverPath().isEmpty()) {
			survivor.setCover(merged.getCoverPath(), merged.getCoverFace());
		}
		store();
		return survivor;
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
