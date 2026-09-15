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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The share links of this server, persisted beside the album tree (issue #51).
 *
 * <p>
 * A share link is a scoped token: whoever holds it opens one subtree of one user's library, within
 * the limits the author set. What the link <em>may do</em> there is not stored here — that is a
 * {@link GrantStore.Grant} to the subject <code>token:&lt;id&gt;</code>, so that a link is granted
 * and revoked by the one mechanism every other sharing uses. This store holds what is peculiar to a
 * link: the hash of its token, the limits it shows the album under, its label and its lifetime.
 * </p>
 *
 * <p>
 * The store lives in {@link UserStore#DIRECTORY_NAME} at the root of the served folder, never
 * inside a user's album folder, and it holds a <em>hash</em> of every issued token, never the token
 * itself: a stolen store cannot be replayed against the server. The file format is persisted data
 * and therefore versioned:
 * </p>
 *
 * <pre>
 * {"version":1,"links":[{"id":"a1b2c3d4","tokenHash":"&lt;64 hex chars&gt;","owner":"alice",
 *   "path":"2024/2024-05-01 Zoo","label":"Grandma","expires":"2026-12-24T00:00:00Z",
 *   "maxPrivacy":0,"minRating":0,"created":"2026-09-12T10:11:12Z","revoked":""}]}
 * </pre>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class ShareStore {

	private static final Logger LOG = Logger.getLogger(ShareStore.class.getName());

	/** The name of the store within {@link UserStore#DIRECTORY_NAME}. */
	public static final String FILE_NAME = "shares.json";

	/**
	 * The path segment the web application is served under for a share link.
	 *
	 * <p>
	 * A link's URL is <code>&lt;context&gt;/s/&lt;token&gt;/</code>: the static handler serves the
	 * same application there with its base href rewritten, and the app sends the token as its
	 * bearer. The segment is named here because the server builds the URL and the static handler is
	 * configured with it, see <code>ResourceServlet</code>.
	 * </p>
	 */
	public static final String URL_SEGMENT = "s";

	/** The version this build writes, see {@link ShareStore}. */
	public static final int VERSION = 1;

	/** The number of random bytes a token is built from, as for a device token. */
	private static final int TOKEN_BYTES = 32;

	/** The number of random bytes an id is built from; it is a name, not a secret. */
	private static final int ID_BYTES = 6;

	private static final String VERSION__PROP = "version";

	private static final String LINKS__PROP = "links";

	private static final String ID__PROP = "id";

	private static final String RIGHTS__PROP = "rights";

	private static final String TOKEN_HASH__PROP = "tokenHash";

	private static final String OWNER__PROP = "owner";

	private static final String PATH__PROP = "path";

	private static final String LABEL__PROP = "label";

	private static final String EXPIRES__PROP = "expires";

	private static final String MAX_PRIVACY__PROP = "maxPrivacy";

	private static final String MIN_RATING__PROP = "minRating";

	private static final String CREATED__PROP = "created";

	private static final String REVOKED__PROP = "revoked";

	/** A single share link, see {@link ShareStore}. */
	public static final class Link {

		private final String _id;

		private final String _tokenHash;

		private final String _owner;

		private final String _path;

		private final String _label;

		private final String _expires;

		private final int _maxPrivacy;

		private final int _minRating;

		private final String _created;

		private final java.util.Set<String> _rights;

		private String _revoked;

		/** Creates a {@link Link} that allows looking and downloading. */
		public Link(String id, String tokenHash, String owner, String path, String label, String expires,
				int maxPrivacy, int minRating, String created, String revoked) {
			this(id, tokenHash, owner, path, label, expires, maxPrivacy, minRating, Rights.READ_ONLY, created,
				revoked);
		}

		/** Creates a {@link Link} carrying what it allows, see issue #83. */
		public Link(String id, String tokenHash, String owner, String path, String label, String expires,
				int maxPrivacy, int minRating, java.util.Collection<String> rights, String created,
				String revoked) {
			_rights = Rights.closure(rights);
			_id = id;
			_tokenHash = tokenHash;
			_owner = owner;
			_path = path;
			_label = label;
			_expires = expires;
			_maxPrivacy = maxPrivacy;
			_minRating = minRating;
			_created = created;
			_revoked = revoked;
		}

		/**
		 * What this link allows, see issue #83.
		 *
		 * <p>
		 * The link <em>is</em> the permission: it was created with these rights, and they are what
		 * its holder may do — there is no grant record beside it any more. A link stored before
		 * this build carries none and is read as looking and downloading, which is what every link
		 * allowed.
		 * </p>
		 */
		public java.util.Set<String> getRights() {
			return _rights;
		}

		/** The short id of this link; what the {@link Subjects#token(String) subject} names. */
		public String getId() {
			return _id;
		}

		/** The SHA-256 hash of the link's token, in lower-case hex. */
		public String getTokenHash() {
			return _tokenHash;
		}

		/** The name of the user in whose space the shared subtree lies. */
		public String getOwner() {
			return _owner;
		}

		/** The shared folder as a path relative to the owner's space, <code>/</code> as separator. */
		public String getPath() {
			return _path;
		}

		/** The label the link was created with, empty if it has none. */
		public String getLabel() {
			return _label;
		}

		/** When the link expires, an ISO-8601 instant; empty if it never does. */
		public String getExpires() {
			return _expires;
		}

		/** The highest {@link Privacy} level this link shows. */
		public int getMaxPrivacy() {
			return _maxPrivacy;
		}

		/** The lowest {@link Ratings rating} this link shows. */
		public int getMinRating() {
			return _minRating;
		}

		/** When the link was created, an ISO-8601 instant. */
		public String getCreated() {
			return _created;
		}

		/** When the link was withdrawn, an ISO-8601 instant; empty while it is live. */
		public String getRevoked() {
			return _revoked;
		}

		/** See {@link #getRevoked()}. */
		void setRevoked(String revoked) {
			_revoked = revoked;
		}

		/** Whether this link was withdrawn. */
		public boolean isRevoked() {
			return !_revoked.isEmpty();
		}

		/**
		 * Whether this link's lifetime has run out at the given moment.
		 *
		 * <p>
		 * An {@link #getExpires() expiry} this server cannot parse is treated as expired: a link
		 * whose lifetime nobody can read is not a link that lives forever.
		 * </p>
		 */
		public boolean isExpired(Instant now) {
			if (_expires.isEmpty()) {
				return false;
			}
			try {
				return !Instant.parse(_expires).isAfter(now);
			} catch (DateTimeException ex) {
				LOG.warning("The share link '" + _id + "' has an unreadable expiry '" + _expires
					+ "'; it is treated as expired.");
				return true;
			}
		}

		/** Whether this link opens anything at all right now. */
		public boolean isLive() {
			return !isRevoked() && !isExpired(Instant.now());
		}

		/** How an attribution names a contribution made through this link, see issue #53. */
		public String getSubject() {
			return "token:" + _id;
		}

		/** Whether the given path is the given folder or lies below it. */
		static boolean isBelow(String path, String folder) {
			return folder.isEmpty() || path.equals(folder) || path.startsWith(folder + "/");
		}

		/** Whether this link covers the given path in the given space. */
		public boolean covers(String owner, String path) {
			return _owner.equals(owner) && isBelow(path, _path);
		}

		@Override
		public String toString() {
			return getSubject() + "@" + _owner + "/" + _path + (isRevoked() ? " (revoked)" : "");
		}
	}

	/** A newly created {@link Link} together with its token, answered exactly once. */
	public static final class Issued {

		private final Link _link;

		private final String _token;

		Issued(Link link, String token) {
			_link = link;
			_token = token;
		}

		/** The link that was recorded. */
		public Link getLink() {
			return _link;
		}

		/** The token opening it, which this server never stores and never answers again. */
		public String getToken() {
			return _token;
		}
	}

	private final Path _file;

	private final SecureRandom _random = new SecureRandom();

	private List<Link> _links = new ArrayList<>();

	/** Whether the file on disk could not be read; it is set aside instead of overwritten. */
	private boolean _damaged;

	/**
	 * Creates a {@link ShareStore} for the album tree rooted at the given path.
	 *
	 * <p>
	 * The store is read immediately, so that a restarted server keeps honouring the links it
	 * issued. A library that never shared a link has no file and an empty store.
	 * </p>
	 */
	public ShareStore(Path basePath) {
		_file = basePath.resolve(UserStore.DIRECTORY_NAME).resolve(FILE_NAME);
		load();
	}

	/** The file this store is persisted in. */
	public Path getFile() {
		return _file;
	}

	/** Every link of this server, in the order they were created. */
	public synchronized List<Link> getLinks() {
		return Collections.unmodifiableList(new ArrayList<>(_links));
	}

	/**
	 * The link of the given id.
	 *
	 * @return <code>null</code> if there is none.
	 */
	public synchronized Link get(String id) {
		for (Link link : _links) {
			if (link.getId().equals(id)) {
				return link;
			}
		}
		return null;
	}

	/**
	 * The links on the given path of the given space and on every folder above it, the nearest
	 * first.
	 *
	 * <p>
	 * Revoked links are listed too, marked as such: a management screen shows what happened to a
	 * link somebody handed out, see issue #55.
	 * </p>
	 */
	public synchronized List<Link> covering(String owner, String path) {
		List<Link> result = new ArrayList<>();
		for (Link link : _links) {
			if (link.covers(owner, path)) {
				result.add(link);
			}
		}
		result.sort((l1, l2) -> Integer.compare(l2.getPath().length(), l1.getPath().length()));
		return result;
	}

	/**
	 * The link the given token opens, whether it is live or not.
	 *
	 * <p>
	 * A dead link is answered too, and deliberately: the caller is told that the link expired or was
	 * withdrawn, which is a different thing from a token nobody ever issued.
	 * </p>
	 *
	 * @return <code>null</code> if the token is not one this server issued.
	 */
	public synchronized Link lookup(String token) {
		if (token == null || token.isEmpty()) {
			return null;
		}
		byte[] hash = UserStore.hash(token).getBytes(StandardCharsets.US_ASCII);
		for (Link link : _links) {
			// Constant-time comparison: the hash of a guessed token must not be probed by timing.
			if (MessageDigest.isEqual(hash, link.getTokenHash().getBytes(StandardCharsets.US_ASCII))) {
				return link;
			}
		}
		return null;
	}

	/**
	 * Issues a new link on the given subtree and persists its record.
	 *
	 * <p>
	 * The token is returned to the caller exactly once and never stored; the caller records the
	 * {@link GrantStore.Grant} that says what the link may do.
	 * </p>
	 */
	public synchronized Issued create(String owner, String path, String label, String expires, int maxPrivacy,
			int minRating) throws IOException {
		return create(owner, path, label, expires, maxPrivacy, minRating, Rights.READ_ONLY);
	}

	/** Issues a link allowing exactly the given rights, see issue #83. */
	public synchronized Issued create(String owner, String path, String label, String expires, int maxPrivacy,
			int minRating, java.util.Collection<String> rights) throws IOException {
		byte[] bytes = new byte[TOKEN_BYTES];
		_random.nextBytes(bytes);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

		Link link = new Link(freeId(), UserStore.hash(token), owner, path, label, expires, maxPrivacy, minRating,
			rights, Instant.now().toString(), "");
		_links.add(link);
		store();
		return new Issued(link, token);
	}

	/**
	 * Marks the link of the given id as withdrawn.
	 *
	 * <p>
	 * The record stays: a withdrawn link is shown as withdrawn, and its id is never handed out
	 * again. The caller removes the grant, which is what actually closes the door.
	 * </p>
	 *
	 * @return The link, <code>null</code> if there is none of that id.
	 */
	public synchronized Link revoke(String id) throws IOException {
		Link link = get(id);
		if (link == null) {
			return null;
		}
		if (!link.isRevoked()) {
			link.setRevoked(Instant.now().toString());
			store();
		}
		return link;
	}

	/** An id no link of this store has. */
	private String freeId() {
		while (true) {
			byte[] bytes = new byte[ID_BYTES];
			_random.nextBytes(bytes);
			String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
			if (get(id) == null) {
				return id;
			}
		}
	}

	private void load() {
		if (!_file.toFile().exists()) {
			return;
		}
		try (Reader reader = new InputStreamReader(Files.newInputStream(_file), StandardCharsets.UTF_8)) {
			_links = readLinks(new JsonReader(new ReaderAdapter(reader)));
		} catch (IOException | RuntimeException ex) {
			// A broken store must not lock the server up; it opens no link instead.
			LOG.log(Level.WARNING, "Cannot read the share store '" + _file + "': " + ex.getMessage());
			_links = new ArrayList<>();
			_damaged = true;
		}
	}

	private static List<Link> readLinks(JsonReader in) throws IOException {
		List<Link> result = new ArrayList<>();
		int version = 0;
		in.beginObject();
		while (in.hasNext()) {
			String key = in.nextName();
			switch (key) {
				case VERSION__PROP:
					version = in.nextInt();
					break;
				case LINKS__PROP:
					in.beginArray();
					while (in.hasNext()) {
						result.add(readLink(in));
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
			LOG.warning("The share store was written by a newer version (" + version + " > " + VERSION
				+ "); unknown entries are kept as read.");
		}
		return result;
	}

	private static Link readLink(JsonReader in) throws IOException {
		String id = "";
		String tokenHash = "";
		String owner = "";
		String path = "";
		String label = "";
		String expires = "";
		int maxPrivacy = Privacy.PUBLIC;
		int minRating = Ratings.MIN;
		String created = "";
		String revoked = "";
		java.util.List<String> rights = null;
		in.beginObject();
		while (in.hasNext()) {
			String key = in.nextName();
			switch (key) {
				case RIGHTS__PROP:
					rights = new java.util.ArrayList<>();
					in.beginArray();
					while (in.hasNext()) {
						rights.add(in.nextString());
					}
					in.endArray();
					break;
				case ID__PROP:
					id = in.nextString();
					break;
				case TOKEN_HASH__PROP:
					tokenHash = in.nextString();
					break;
				case OWNER__PROP:
					owner = in.nextString();
					break;
				case PATH__PROP:
					path = in.nextString();
					break;
				case LABEL__PROP:
					label = in.nextString();
					break;
				case EXPIRES__PROP:
					expires = in.nextString();
					break;
				case MAX_PRIVACY__PROP:
					maxPrivacy = in.nextInt();
					break;
				case MIN_RATING__PROP:
					minRating = in.nextInt();
					break;
				case CREATED__PROP:
					created = in.nextString();
					break;
				case REVOKED__PROP:
					revoked = in.nextString();
					break;
				default:
					in.skipValue();
					break;
			}
		}
		in.endObject();
		// A link written before issue #83 says nothing about its rights: it allowed looking.
		return new Link(id, tokenHash, owner, path, label, expires, maxPrivacy, minRating,
			rights == null ? Rights.READ_ONLY : rights, created, revoked);
	}

	/** Writes this store to disk, atomically: a crash never leaves a half-written store. */
	public synchronized void store() throws IOException {
		Path directory = _file.getParent();
		Files.createDirectories(directory);

		if (_damaged && Files.exists(_file)) {
			// The unreadable file may still hold links somebody handed out; keep it for repair.
			Path broken = directory.resolve(FILE_NAME + ".broken-" + Instant.now().toString().replace(':', '-'));
			Files.move(_file, broken, StandardCopyOption.REPLACE_EXISTING);
			LOG.warning("Kept the unreadable share store as '" + broken + "'.");
		}
		_damaged = false;

		Path tmpFile = Files.createTempFile(directory, "shares", ".json");
		try (Writer writer = new OutputStreamWriter(Files.newOutputStream(tmpFile), StandardCharsets.UTF_8)) {
			try (JsonWriter out = new JsonWriter(new WriterAdapter(writer))) {
				out.beginObject();
				out.name(VERSION__PROP);
				out.value(VERSION);
				out.name(LINKS__PROP);
				out.beginArray();
				for (Link link : _links) {
					writeLink(out, link);
				}
				out.endArray();
				out.endObject();
			}
		}
		Files.move(tmpFile, _file, StandardCopyOption.REPLACE_EXISTING);
	}

	private static void writeLink(JsonWriter out, Link link) throws IOException {
		out.beginObject();
		out.name(ID__PROP);
		out.value(link.getId());
		out.name(TOKEN_HASH__PROP);
		out.value(link.getTokenHash());
		out.name(RIGHTS__PROP);
		out.beginArray();
		for (String right : link.getRights()) {
			out.value(right);
		}
		out.endArray();
		out.name(OWNER__PROP);
		out.value(link.getOwner());
		out.name(PATH__PROP);
		out.value(link.getPath());
		out.name(LABEL__PROP);
		out.value(link.getLabel());
		out.name(EXPIRES__PROP);
		out.value(link.getExpires());
		out.name(MAX_PRIVACY__PROP);
		out.value(link.getMaxPrivacy());
		out.name(MIN_RATING__PROP);
		out.value(link.getMinRating());
		out.name(CREATED__PROP);
		out.value(link.getCreated());
		out.name(REVOKED__PROP);
		out.value(link.getRevoked());
		out.endObject();
	}
}
