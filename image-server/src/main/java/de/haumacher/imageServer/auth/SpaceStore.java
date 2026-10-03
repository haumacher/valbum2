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
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.logging.Logger;

/**
 * What a folder says about itself as a space, read from <code>.valbum/space.json</code> (issue #82).
 *
 * <p>
 * The presence of this file is what makes a folder below the base folder a space, which is why the
 * file is written by whoever administers the machine — by hand, or with the command
 * <code>--create-space</code> of issue #175, see {@link #create(Path, String, String, String)} — and
 * never by a request, so that no request can bring a space into existence. The one thing a starting
 * server writes into it is the inbox it settled on, once, see {@link #storeInbox(Path, String)}.
 * </p>
 *
 * <p>
 * The file format is persisted data and therefore versioned:
 * </p>
 *
 * <pre>
 * {"version":1,"name":"Alice","anonymous":"public","mapUrl":"https://www.google.com/maps?q={lat},{lon}",
 *  "faces":"on","timeZone":"Europe/Berlin","inbox":"Inbox"}
 * </pre>
 *
 * <p>
 * Everything is optional: a file holding nothing but <code>{}</code> is a space with the folder's
 * own name, no anonymous access, the default map (issue #112), no face index (issue #124) and the
 * server's own time zone for the photographs that do not say theirs (issue #183), and its inbox
 * at {@link #DEFAULT_INBOX} (issue #226). An
 * unknown <code>anonymous</code> value is read as {@link #ANONYMOUS_NONE}, the closed one, and an
 * unknown <code>faces</code> value as {@link #FACES_OFF} — a space is never opened and never made
 * to process biometrics by a typo.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class SpaceStore {

	/** The name of the file within {@link UserStore#DIRECTORY_NAME} marking a folder as a space. */
	public static final String FILE_NAME = "space.json";

	/** Nobody sees anything here without signing in. */
	public static final String ANONYMOUS_NONE = "none";

	/** An anonymous caller may look at the public images of this space. */
	public static final String ANONYMOUS_PUBLIC = "public";

	/** Nothing in this space is ever looked at for faces, see issue #124. */
	public static final String FACES_OFF = "off";

	/**
	 * The server detects the faces in the photographs of this space, see issue #124.
	 *
	 * <p>
	 * Opt-in, and off wherever the file does not say this word: processing the biometrics of one's
	 * family is the administrator's decision, never a default somebody is surprised by.
	 * </p>
	 */
	public static final String FACES_ON = "on";

	/**
	 * Where a position is shown when the space names no map of its own, see issue #112.
	 *
	 * <p>
	 * A URL template carrying <code>{lat}</code> and <code>{lon}</code>. There is no provider to
	 * choose from, because a provider <em>is</em> a template: OpenStreetMap is
	 * <code>https://www.openstreetmap.org/?mlat={lat}&amp;mlon={lon}#map=15/{lat}/{lon}</code>,
	 * Apple Maps is <code>https://maps.apple.com/?ll={lat},{lon}</code>, and a map of one's own is
	 * whatever it is. The app applies the same default where an older server answers none.
	 * </p>
	 */
	public static final String DEFAULT_MAP_URL = "https://www.google.com/maps?q={lat},{lon}";

	/**
	 * The folder of the space's inbox where the file names none, see issue #226: a folder of this
	 * name at the space root, created by the first upload into it.
	 */
	public static final String DEFAULT_INBOX = "Inbox";

	private static final int VERSION = 1;

	private static final String VERSION__PROP = "version";

	private static final String NAME__PROP = "name";

	private static final String ANONYMOUS__PROP = "anonymous";

	private static final String MAP_URL__PROP = "mapUrl";

	private static final String FACES__PROP = "faces";

	private static final String TIME_ZONE__PROP = "timeZone";

	private static final String INBOX__PROP = "inbox";

	private static final Logger LOG = Logger.getLogger(SpaceStore.class.getName());

	/**
	 * The zone the given <code>timeZone</code> value names, see issue #183.
	 *
	 * @param id
	 *        An IANA zone id such as <code>Europe/Berlin</code> (anything {@link ZoneId#of(String)}
	 *        reads, so a fixed offset such as <code>+01:00</code> too).
	 * @return <code>null</code> for an empty or unknown value.
	 */
	public static ZoneId zoneOf(String id) {
		if (id == null || id.trim().isEmpty()) {
			return null;
		}
		try {
			return ZoneId.of(id.trim());
		} catch (DateTimeException ex) {
			return null;
		}
	}

	/** The refusal of a <code>timeZone</code> value {@link #zoneOf(String)} does not know. */
	public static String unknownTimeZone(String id) {
		return "'" + id + "' is no time zone: give an IANA zone id such as 'Europe/Berlin' or 'UTC'.";
	}

	/** What a space says about itself. */
	public static final class Config {

		private final String _name;

		private final String _anonymous;

		private final String _mapUrl;

		private final String _faces;

		private final String _timeZone;

		private final ZoneId _zone;

		private final String _inbox;

		/** Creates a {@link Config} with the default map, see {@link SpaceStore#DEFAULT_MAP_URL}. */
		public Config(String name, String anonymous) {
			this(name, anonymous, "");
		}

		/**
		 * Creates a {@link Config} without a face index, see {@link SpaceStore#FACES_OFF}.
		 *
		 * @param mapUrl
		 *        The map template of this space; the empty string for {@link #DEFAULT_MAP_URL}.
		 */
		public Config(String name, String anonymous, String mapUrl) {
			this(name, anonymous, mapUrl, FACES_OFF);
		}

		/**
		 * Creates a {@link Config}.
		 *
		 * @param faces
		 *        {@link SpaceStore#FACES_ON} or {@link SpaceStore#FACES_OFF}; anything else is off.
		 */
		public Config(String name, String anonymous, String mapUrl, String faces) {
			this(name, anonymous, mapUrl, faces, "");
		}

		/**
		 * Creates a {@link Config}.
		 *
		 * @param timeZone
		 *        The zone a photograph that does not say its own is dated in, see issue #183; the
		 *        empty string (or an unknown id) for the server's own zone.
		 */
		public Config(String name, String anonymous, String mapUrl, String faces, String timeZone) {
			this(name, anonymous, mapUrl, faces, timeZone, "");
		}

		/**
		 * Creates a {@link Config}.
		 *
		 * @param inbox
		 *        The path of the space's inbox relative to its root as the file names it, see issue
		 *        #226; the empty string where it names none (the inbox is then
		 *        {@link SpaceStore#DEFAULT_INBOX}). A path {@link SpaceStore#checkInbox(String)}
		 *        refuses is logged and read as none.
		 */
		public Config(String name, String anonymous, String mapUrl, String faces, String timeZone, String inbox) {
			_name = name;
			_anonymous = anonymous;
			_mapUrl = mapUrl == null || mapUrl.trim().isEmpty() ? DEFAULT_MAP_URL : mapUrl.trim();
			_faces = FACES_ON.equals(faces) ? FACES_ON : FACES_OFF;
			_timeZone = timeZone == null ? "" : timeZone.trim();
			ZoneId zone = zoneOf(_timeZone);
			if (zone == null && !_timeZone.isEmpty()) {
				LOG.warning("The space '" + name + "' names the time zone '" + _timeZone
					+ "', which this server does not know; its photographs are dated in the server's zone "
					+ ZoneId.systemDefault() + ".");
			}
			_zone = zone;
			String stored = inbox == null ? "" : inbox.trim();
			String problem = stored.isEmpty() ? null : checkInbox(stored);
			if (problem != null) {
				LOG.warning("The space '" + name + "' names the inbox '" + stored + "': " + problem
					+ " Its inbox is '" + DEFAULT_INBOX + "'.");
				stored = "";
			}
			_inbox = stored;
		}

		/**
		 * The path of the space's inbox relative to its root, see issue #226: what the file names,
		 * else {@link SpaceStore#DEFAULT_INBOX}. Never empty.
		 */
		public String getInbox() {
			return _inbox.isEmpty() ? DEFAULT_INBOX : _inbox;
		}

		/** This configuration with the given inbox, see {@link #getInbox()}. */
		public Config withInbox(String inbox) {
			return new Config(_name, _anonymous, _mapUrl, _faces, _timeZone, inbox);
		}

		/**
		 * Whether the file names the inbox itself, see issue #226; a space that does not has not
		 * been settled by {@link de.haumacher.imageServer.InboxMigration} yet.
		 */
		public boolean hasStoredInbox() {
			return !_inbox.isEmpty();
		}

		/**
		 * The <code>timeZone</code> the file says, as written; empty where it says none (issue #183).
		 */
		public String getTimeZone() {
			return _timeZone;
		}

		/**
		 * The zone the wall clock of a photograph is read in where the file carries neither an
		 * offset nor a GPS time, and a date in a file name is read in (issue #183).
		 *
		 * <p>
		 * The space's {@link #getTimeZone() timeZone}, else &mdash; missing or unknown &mdash; the
		 * server's own zone, asked at every call so that a test may set it.
		 * </p>
		 */
		public ZoneId getZone() {
			return _zone == null ? ZoneId.systemDefault() : _zone;
		}

		/** {@link SpaceStore#FACES_OFF} or {@link SpaceStore#FACES_ON}, see issue #124. */
		public String getFaces() {
			return _faces;
		}

		/**
		 * Whether the server looks for faces in the photographs of this space, see issue #124.
		 *
		 * <p>
		 * <code>false</code> unless the file says <code>"faces":"on"</code>: an unknown value, a
		 * missing one and a file that is not there at all all mean no.
		 * </p>
		 */
		public boolean isFacesEnabled() {
			return FACES_ON.equals(_faces);
		}

		/** The name to show for this space; the folder name when the file gives none. */
		public String getName() {
			return _name;
		}

		/** {@link SpaceStore#ANONYMOUS_NONE} or {@link SpaceStore#ANONYMOUS_PUBLIC}. */
		public String getAnonymous() {
			return _anonymous;
		}

		/**
		 * The URL template a position of this space is shown on a map with, see issue #112.
		 *
		 * <p>
		 * Never empty: a space that names none is answered {@link SpaceStore#DEFAULT_MAP_URL}, so
		 * that every space has a map and the app never has to decide what "nothing" means.
		 * </p>
		 */
		public String getMapUrl() {
			return _mapUrl;
		}

		/** Whether an anonymous caller may look at the public images of this space. */
		public boolean isAnonymousAllowed() {
			return ANONYMOUS_PUBLIC.equals(_anonymous);
		}

		@Override
		public String toString() {
			return "Space[" + _name + ", anonymous=" + _anonymous + ", map=" + _mapUrl + ", faces="
				+ _faces + ", timeZone=" + _timeZone + ", inbox=" + getInbox() + "]";
		}
	}

	/** The file marking the given folder as a space, whether it exists or not. */
	public static Path file(Path spaceRoot) {
		return spaceRoot.resolve(UserStore.DIRECTORY_NAME).resolve(FILE_NAME);
	}

	/** Whether the given folder carries a {@value #FILE_NAME} and is therefore a space. */
	public static boolean isSpace(Path folder) {
		return Files.isRegularFile(file(folder));
	}

	/**
	 * Reads the configuration of the space at the given folder.
	 *
	 * @param folderName
	 *        The name the space is addressed by, used when the file names none.
	 * @throws IOException
	 *         If the file is there but cannot be read; a space whose configuration is broken is
	 *         not silently served with defaults.
	 */
	public static Config load(Path spaceRoot, String folderName) throws IOException {
		Path file = file(spaceRoot);
		if (!Files.isRegularFile(file)) {
			return new Config(folderName, ANONYMOUS_NONE);
		}
		Stored stored = readStored(file);
		return new Config(stored.name.trim().isEmpty() ? folderName : stored.name.trim(),
			ANONYMOUS_PUBLIC.equals(stored.anonymous) ? ANONYMOUS_PUBLIC : ANONYMOUS_NONE, stored.mapUrl,
			stored.faces.trim(), stored.timeZone, stored.inbox);
	}

	/** The values of a {@value #FILE_NAME} exactly as they are written, empty where one is absent. */
	private static final class Stored {
		String name = "";

		String anonymous = "";

		String mapUrl = "";

		String faces = "";

		String timeZone = "";

		String inbox = "";
	}

	/**
	 * Reads the given file as it is written.
	 *
	 * @throws IOException
	 *         If it cannot be read; a broken file is never answered with defaults.
	 */
	private static Stored readStored(Path file) throws IOException {
		Stored result = new Stored();
		try (Reader reader = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8);
				JsonReader in = new JsonReader(new ReaderAdapter(reader))) {
			in.beginObject();
			while (in.hasNext()) {
				String key = in.nextName();
				switch (key) {
					case NAME__PROP:
						result.name = in.nextString();
						break;
					case ANONYMOUS__PROP:
						result.anonymous = in.nextString();
						break;
					case MAP_URL__PROP:
						result.mapUrl = in.nextString();
						break;
					case FACES__PROP:
						result.faces = in.nextString();
						break;
					case TIME_ZONE__PROP:
						result.timeZone = in.nextString();
						break;
					case INBOX__PROP:
						result.inbox = in.nextString();
						break;
					default:
						in.skipValue();
						break;
				}
			}
			in.endObject();
		} catch (RuntimeException ex) {
			throw new IOException("Cannot read '" + file + "': " + ex.getMessage(), ex);
		}
		return result;
	}

	/**
	 * Why the given value cannot name the inbox of a space, <code>null</code> where it can, see
	 * issue #226.
	 *
	 * <p>
	 * A path relative to the space root, its segments separated by <code>/</code>: every segment a
	 * legal folder name that the library does not ignore (no leading dot, no <code>..</code>, no
	 * litter of issue #173), so that the inbox is an ordinary folder of the library and never a
	 * place below <code>.valbum</code> or outside the space.
	 * </p>
	 */
	public static String checkInbox(String inbox) {
		if (inbox == null || inbox.trim().isEmpty()) {
			return "An inbox needs a folder name.";
		}
		for (String segment : inbox.trim().split("/", -1)) {
			if (segment.isEmpty() || !segment.equals(segment.trim())
				|| !de.haumacher.imageServer.FolderNames.isLegal(segment)
				|| de.haumacher.imageServer.LibraryFiles.isIgnored(segment)) {
				return "'" + inbox + "' is no folder of the space: name a folder below the space root, "
					+ "its segments separated by '/', none of them empty or starting with a dot.";
			}
		}
		return null;
	}

	/**
	 * Writes the inbox of the space at the given folder into its {@value #FILE_NAME}, see issue
	 * #226, keeping everything else the file says exactly as it is written; a file that is not
	 * there yet is written with the defaults {@link #load(Path, String)} reads anyway.
	 *
	 * <p>
	 * The one write of the running server into this file: the decision of
	 * {@link de.haumacher.imageServer.InboxMigration}, made once and written down so that it is
	 * not made again, differently, at the next start.
	 * </p>
	 *
	 * @throws IOException
	 *         If the file is there but cannot be read; it is not touched then.
	 * @throws IllegalArgumentException
	 *         If {@link #checkInbox(String)} refuses the path; nothing is written then.
	 */
	public static void storeInbox(Path spaceRoot, String inbox) throws IOException {
		String problem = checkInbox(inbox);
		if (problem != null) {
			throw new IllegalArgumentException(problem);
		}
		Path file = file(spaceRoot);
		Stored stored = Files.isRegularFile(file) ? readStored(file) : new Stored();
		stored.inbox = inbox.trim();
		write(spaceRoot, stored, true);
	}

	/**
	 * Writes the configuration of the space at the given folder, creating the folder's
	 * {@value UserStore#DIRECTORY_NAME} if it has none.
	 *
	 * <p>
	 * Used by the migration of issue #82, which turns the folders of a per-user library into
	 * spaces; the running server never writes this file.
	 * </p>
	 */
	public static void store(Path spaceRoot, Config config) throws IOException {
		write(spaceRoot, config.getName(), config.getAnonymous(), config.getMapUrl(), config.getFaces(),
			config.getTimeZone().isEmpty() ? null : config.getTimeZone(),
			config.hasStoredInbox() ? config.getInbox() : null, true);
	}

	/**
	 * Makes the given folder a space, see issue #175: writes a new {@value #FILE_NAME}, creating the
	 * folder and its {@value UserStore#DIRECTORY_NAME} where they are missing.
	 *
	 * <p>
	 * What is written is exactly what {@link #load(Path, String)} reads back: the name only where
	 * one is given (else the folder's own name is the space's, and follows a renamed folder), the
	 * anonymous access and the face index as the two words this class knows, and no map template,
	 * so that the space shows positions on {@link #DEFAULT_MAP_URL} until somebody names another.
	 * </p>
	 *
	 * @param name
	 *        The name to show for the space; empty for the folder's own name.
	 * @param anonymous
	 *        {@link #ANONYMOUS_NONE} or {@link #ANONYMOUS_PUBLIC}.
	 * @param faces
	 *        {@link #FACES_OFF} or {@link #FACES_ON}.
	 * @throws java.nio.file.FileAlreadyExistsException
	 *         If the folder is a space already; its file is not touched then.
	 * @throws IllegalArgumentException
	 *         If <code>anonymous</code> or <code>faces</code> is not one of the words this class
	 *         reads.
	 */
	public static void create(Path spaceRoot, String name, String anonymous, String faces) throws IOException {
		create(spaceRoot, name, anonymous, faces, null);
	}

	/**
	 * Makes the given folder a space, see {@link #create(Path, String, String, String)}, naming the
	 * zone its photographs are dated in where they do not say theirs (issue #183).
	 *
	 * @param timeZone
	 *        An id {@link #zoneOf(String)} knows; <code>null</code> or empty to leave it out, so that
	 *        the space follows the server's zone.
	 * @throws IllegalArgumentException
	 *         If <code>timeZone</code> names no zone; nothing is written then.
	 */
	public static void create(Path spaceRoot, String name, String anonymous, String faces, String timeZone)
			throws IOException {
		String zone = timeZone == null ? "" : timeZone.trim();
		if (!zone.isEmpty() && zoneOf(zone) == null) {
			throw new IllegalArgumentException(unknownTimeZone(timeZone));
		}
		if (!ANONYMOUS_NONE.equals(anonymous) && !ANONYMOUS_PUBLIC.equals(anonymous)) {
			throw new IllegalArgumentException("Anonymous access is '" + ANONYMOUS_NONE + "' or '"
				+ ANONYMOUS_PUBLIC + "', not '" + anonymous + "'.");
		}
		if (!FACES_OFF.equals(faces) && !FACES_ON.equals(faces)) {
			throw new IllegalArgumentException("The face index is '" + FACES_ON + "' or '" + FACES_OFF
				+ "', not '" + faces + "'.");
		}
		String trimmed = name == null ? "" : name.trim();
		write(spaceRoot, trimmed.isEmpty() ? null : trimmed, anonymous, null, faces, zone.isEmpty() ? null : zone,
			null, false);
	}

	/**
	 * Rewrites the {@value #FILE_NAME} of a library moved into a space, see
	 * <code>--move-into-space</code>, writing it where there is none.
	 *
	 * <p>
	 * What the file said is kept as it was written - a name only where it gave one, a map template
	 * only where it named one, the face index, the time zone of issue #183 and the inbox of issue
	 * #226 only where it named them - and only the given values replace it.
	 * </p>
	 *
	 * @param name
	 *        The name to show for the space; <code>null</code> or empty to keep the file's.
	 * @param anonymous
	 *        {@link #ANONYMOUS_NONE} or {@link #ANONYMOUS_PUBLIC}.
	 * @throws IOException
	 *         If the file is there but cannot be read; it is not touched then.
	 */
	public static void rewrite(Path spaceRoot, String name, String anonymous) throws IOException {
		Path file = file(spaceRoot);
		Stored stored = Files.isRegularFile(file) ? readStored(file) : new Stored();
		String given = name == null ? "" : name.trim();
		stored.name = given.isEmpty() ? stored.name.trim() : given;
		stored.anonymous = ANONYMOUS_PUBLIC.equals(anonymous) ? ANONYMOUS_PUBLIC : ANONYMOUS_NONE;
		write(spaceRoot, stored, true);
	}

	/** Writes what was read, see {@link #readStored(Path)}, leaving out what it does not say. */
	private static void write(Path spaceRoot, Stored stored, boolean replace) throws IOException {
		write(spaceRoot, stored.name.trim().isEmpty() ? null : stored.name.trim(),
			ANONYMOUS_PUBLIC.equals(stored.anonymous) ? ANONYMOUS_PUBLIC : ANONYMOUS_NONE,
			stored.mapUrl.trim().isEmpty() ? null : stored.mapUrl.trim(),
			FACES_ON.equals(stored.faces.trim()) ? FACES_ON : FACES_OFF,
			stored.timeZone.trim().isEmpty() ? null : stored.timeZone.trim(),
			stored.inbox.trim().isEmpty() ? null : stored.inbox.trim(), replace);
	}

	/**
	 * Writes the file through a temporary sibling, so that a space is never half-written.
	 *
	 * @param name
	 *        <code>null</code> to leave the name out.
	 * @param mapUrl
	 *        <code>null</code> to leave the map template out.
	 * @param timeZone
	 *        <code>null</code> to leave the time zone out.
	 * @param inbox
	 *        <code>null</code> to leave the inbox out, see issue #226.
	 * @param replace
	 *        Whether an existing file is replaced; otherwise it is left as it is and the call fails.
	 */
	private static void write(Path spaceRoot, String name, String anonymous, String mapUrl, String faces,
			String timeZone, String inbox, boolean replace) throws IOException {
		Path file = file(spaceRoot);
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
		try {
			try (Writer writer = new OutputStreamWriter(Files.newOutputStream(tmp), StandardCharsets.UTF_8);
					JsonWriter out = new JsonWriter(new WriterAdapter(writer))) {
				out.beginObject();
				out.name(VERSION__PROP);
				out.value(VERSION);
				if (name != null) {
					out.name(NAME__PROP);
					out.value(name);
				}
				out.name(ANONYMOUS__PROP);
				out.value(anonymous);
				if (mapUrl != null) {
					out.name(MAP_URL__PROP);
					out.value(mapUrl);
				}
				out.name(FACES__PROP);
				out.value(faces);
				if (timeZone != null) {
					out.name(TIME_ZONE__PROP);
					out.value(timeZone);
				}
				if (inbox != null) {
					out.name(INBOX__PROP);
					out.value(inbox);
				}
				out.endObject();
			}
			if (replace) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			} else {
				// No REPLACE_EXISTING: a space that appeared in the meantime is never overwritten.
				Files.move(tmp, file);
			}
		} finally {
			Files.deleteIfExists(tmp);
		}
	}
}
