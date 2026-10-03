/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.auth;

import de.haumacher.imageServer.LibraryFiles;
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
 *   "maxPrivacy":0,"minRating":0,"created":"2026-09-12T10:11:12Z"}]}
 * </pre>
 *
 * <p>
 * <b>A deleted link is gone</b> (issue #217): {@link #delete(String)} takes its record out of the
 * file, and its token is from then on one this server never issued. A record an earlier build kept
 * as withdrawn (<code>"revoked"</code> not empty) is read as deleted &mdash; dropped on load, so it
 * is never listed and never opens anything, and gone from the file with the next write.
 * </p>
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

	/**
	 * The version this build writes, see {@link ShareStore}.
	 *
	 * <p>
	 * Version 2 (issue #198) adds the <code>type</code> of a link and, for a personal one, its
	 * <code>recipients</code> and <code>shutOut</code> lists; a link without a type is anonymous, so
	 * a version 1 file reads unchanged:
	 * </p>
	 *
	 * <pre>
	 * {"id":"…", …, "type":"personal",
	 *  "recipients":[{"contact":"&lt;contact id&gt;","tokenHash":"&lt;64 hex&gt;","voided":["&lt;64 hex&gt;"],
	 *    "issued":"…","opened":""}],
	 *  "shutOut":[{"contact":"&lt;contact id&gt;","at":"…"}]}
	 * </pre>
	 *
	 * <p>
	 * Issue #203 adds, without a version bump because both are additive, <code>"addressed":true</code>
	 * on a link only its recipients open (read as true wherever a link has recipients, so that a
	 * link whose recipients were all deleted stays closed), and <code>"visitors"</code>, who came in
	 * through a personal link and when:
	 * </p>
	 *
	 * <pre>
	 *  "visitors":[{"contact":"&lt;contact id&gt;","first":"…","last":"…"}]
	 * </pre>
	 */
	public static final int VERSION = 2;

	/** The stored type of an anonymous link; a link without a type is one. */
	public static final String ANONYMOUS = "anonymous";

	/** The stored type of a personal link, see issue #198. */
	public static final String PERSONAL = "personal";

	/** The number of random bytes a token is built from, as for a device token. */
	private static final int TOKEN_BYTES = 32;

	/** The number of random bytes an id is built from; it is a name, not a secret. */
	private static final int ID_BYTES = 6;

	private static final String VERSION__PROP = "version";

	private static final String LINKS__PROP = "links";

	private static final String ID__PROP = "id";

	private static final String RIGHTS__PROP = "rights";

	private static final String CREATED_BY__PROP = "createdBy";

	private static final String TOKEN_HASH__PROP = "tokenHash";

	private static final String OWNER__PROP = "owner";

	private static final String PATH__PROP = "path";

	private static final String LABEL__PROP = "label";

	private static final String EXPIRES__PROP = "expires";

	private static final String MAX_PRIVACY__PROP = "maxPrivacy";

	private static final String MIN_RATING__PROP = "minRating";

	private static final String CREATED__PROP = "created";

	/**
	 * The withdrawal an earlier build stored; a record carrying one is dropped on load, see
	 * {@link ShareStore}.
	 */
	private static final String REVOKED__PROP = "revoked";

	private static final String TYPE__PROP = "type";

	private static final String RECIPIENTS__PROP = "recipients";

	private static final String SHUT_OUT__PROP = "shutOut";

	private static final String PHOTO_LABEL__PROP = "photoLabel";

	private static final String ADDRESSED__PROP = "addressed";

	private static final String VISITORS__PROP = "visitors";

	/**
	 * When a contact came in through a personal link, see issue #203.
	 *
	 * <p>
	 * Written down at most every {@link ContactStore#USE_GRANULARITY}, like the use of a credential.
	 * </p>
	 */
	public static final class Visit {

		private final String _first;

		private String _last;

		Visit(String first, String last) {
			_first = first;
			_last = last;
		}

		/** When the contact first came in through the link. */
		public String getFirst() {
			return _first;
		}

		/** When the contact last came in through the link, to {@link ContactStore#USE_GRANULARITY}. */
		public String getLast() {
			return _last;
		}
	}

	/**
	 * A recipient of an addressed personal link and their own link, see issue #198.
	 *
	 * <p>
	 * The recipient's token is stored as its hash, like the link's own. Sending again replaces it
	 * and keeps the old hash among the {@link #getVoided() voided} ones, so that the old link is told
	 * apart from one that never existed.
	 * </p>
	 */
	public static final class Recipient {

		private final String _contact;

		private String _tokenHash;

		private final List<String> _voided = new ArrayList<>();

		private String _issued;

		private String _opened;

		Recipient(String contact, String tokenHash, String issued, String opened) {
			_contact = contact;
			_tokenHash = tokenHash;
			_issued = issued;
			_opened = opened;
		}

		/** The id of the contact, see {@link ContactStore}. */
		public String getContact() {
			return _contact;
		}

		String getTokenHash() {
			return _tokenHash;
		}

		/** The hashes of the recipient's earlier tokens. */
		List<String> getVoided() {
			return _voided;
		}

		/** When the current token was issued. */
		public String getIssued() {
			return _issued;
		}

		/** When the current token was first opened, empty while it was not. */
		public String getOpened() {
			return _opened;
		}

		/** Whether the current token was opened: it identifies nobody any more. */
		public boolean isOpened() {
			return !_opened.isEmpty();
		}
	}

	/** What a token presented to this store is, see {@link ShareStore#match(String)}. */
	public static final class Match {

		private final Link _link;

		private final Recipient _recipient;

		private final boolean _voided;

		Match(Link link, Recipient recipient, boolean voided) {
			_link = link;
			_recipient = recipient;
			_voided = voided;
		}

		/** The link the token belongs to. */
		public Link getLink() {
			return _link;
		}

		/** The recipient whose own token it is, <code>null</code> for the link's own token. */
		public Recipient getRecipient() {
			return _recipient;
		}

		/** Whether it is a recipient's token that was replaced by sending again. */
		public boolean isVoided() {
			return _voided;
		}
	}

	/** A single share link, see {@link ShareStore}. */
	public static final class Link {

		private final String _id;

		private final String _tokenHash;

		private final String _owner;

		private String _path;

		private final String _label;

		private final String _expires;

		private final int _maxPrivacy;

		private final int _minRating;

		private final String _created;

		private final java.util.Set<String> _rights;

		private final String _createdBy;

		private String _type = ANONYMOUS;

		private String _photoLabel = "";

		private final List<Recipient> _recipients = new ArrayList<>();

		private final java.util.Map<String, String> _shutOut = new java.util.LinkedHashMap<>();

		private boolean _addressed;

		private final java.util.Map<String, Visit> _visitors = new java.util.LinkedHashMap<>();

		/** Creates a {@link Link} that knows who handed it out, see issue #84. */
		public Link(String id, String tokenHash, String owner, String path, String label, String expires,
				int maxPrivacy, int minRating, java.util.Collection<String> rights, String createdBy,
				String created) {
			_createdBy = createdBy == null ? "" : createdBy;
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
		}

		/**
		 * The name of the user who created this link, see issue #84.
		 *
		 * <p>
		 * A link belongs to whoever handed it out: they and an administrator of the space see it
		 * and may delete it, and removing them takes it back. The empty string for a link stored
		 * before this field existed — nobody's, so only an administrator manages it.
		 * </p>
		 */
		public String getCreatedBy() {
			return _createdBy;
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
		/**
		 * Where this link points after its folder was renamed, see issue #130.
		 *
		 * <p>
		 * The one thing about a link that ever changes, and it changes
		 * nothing the link <em>allows</em>: the folder it was handed out on is the same folder,
		 * spelled the way it is spelled now. What a link may do and show stays frozen at what it
		 * was created with, see {@link ShareStore}.
		 * </p>
		 */
		void setPath(String path) {
			_path = path;
		}

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
			return !isExpired(Instant.now());
		}

		/**
		 * The one label of the album whose photographs this link shows, empty for the whole album,
		 * see issue #213.
		 *
		 * <p>
		 * Frozen like {@link #getMinRating()}, with one exception: a rename of the label in the
		 * album the link points at carries the link along ({@link ShareStore#relabel}), because
		 * the link was handed out on those photographs and not on a spelling.
		 * </p>
		 */
		public String getPhotoLabel() {
			return _photoLabel;
		}

		/** {@link ShareStore#ANONYMOUS} or {@link ShareStore#PERSONAL}, see issue #198. */
		public String getType() {
			return _type;
		}

		/** Whether the person behind this link is a contact of the space, see issue #198. */
		public boolean isPersonal() {
			return PERSONAL.equals(_type);
		}

		/** Whether this is a personal link that only its recipients open. */
		public boolean isAddressed() {
			// A link whose recipients were all deleted from the contacts stays addressed: it never
			// opens to whoever proves an address by losing them (issue #203).
			return isPersonal() && (_addressed || !_recipients.isEmpty());
		}

		/** When the given contact came in through this link, <code>null</code> if they never did (issue #203). */
		public Visit visitOf(String contact) {
			return _visitors.get(contact);
		}

		/** Every contact who came in through this link, by contact id, in the order they first did. */
		public java.util.Map<String, Visit> getVisitors() {
			return Collections.unmodifiableMap(_visitors);
		}

		/** The recipients of an addressed link, empty for every other. */
		public List<Recipient> getRecipients() {
			return Collections.unmodifiableList(_recipients);
		}

		/** The recipient that is the given contact, <code>null</code> if they are none. */
		public Recipient recipient(String contact) {
			for (Recipient recipient : _recipients) {
				if (recipient.getContact().equals(contact)) {
					return recipient;
				}
			}
			return null;
		}

		/** When the given contact was shut out of this link, empty while they are not. */
		public String shutOutAt(String contact) {
			String at = _shutOut.get(contact);
			return at == null ? "" : at;
		}

		/** Whether the given contact is shut out of this link. */
		public boolean isShutOut(String contact) {
			return _shutOut.containsKey(contact);
		}

		/**
		 * Whether this link lets the given contact in, see issue #198.
		 *
		 * <p>
		 * A personal link only; an addressed one only its recipients; and never a contact shut out of
		 * it. Whether the link is live is a question of its own.
		 * </p>
		 */
		public boolean admits(String contact) {
			if (!isPersonal() || isShutOut(contact)) {
				return false;
			}
			return !isAddressed() || recipient(contact) != null;
		}

		/** How an attribution names a contribution made through this link, see issue #53. */
		public String getSubject() {
			return "token:" + _id;
		}

		/** Whether the given path is the given folder or lies below it. */
		static boolean isBelow(String path, String folder) {
			return folder.isEmpty() || path.equals(folder) || path.startsWith(folder + "/");
		}

		/**
		 * Whether this link covers the given path in the given space, which is where it is listed
		 * and deleted.
		 *
		 * <p>
		 * A link an earlier build made on what is no part of the library now (a NAS's
		 * <code>@eaDir</code>, issue #173) opens nothing, and no address reaches its folder: it is
		 * managed at the deepest folder above it that is still part of the library, so that its
		 * owner can see it and delete it.
		 * </p>
		 */
		public boolean covers(String owner, String path) {
			return _owner.equals(owner) && isBelow(path, LibraryFiles.libraryPrefix(_path));
		}

		@Override
		public String toString() {
			return getSubject() + "@" + _owner + "/" + _path;
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
	 * An expired link is listed too: it still is a link, and only deleting it removes it. A deleted
	 * one is not, there being no record of it any more (issue #217).
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
	 * An expired link is answered too, and deliberately: the caller is told that the link expired,
	 * which is a different thing from a token nobody ever issued. A deleted link is such a token
	 * (issue #217).
	 * </p>
	 *
	 * @return <code>null</code> if the token is not one this server issued.
	 */
	public synchronized Link lookup(String token) {
		Match match = match(token);
		return match == null || match.isVoided() ? null : match.getLink();
	}

	/**
	 * What the given token is: the own token of a link, or the token of one of its recipients
	 * (issue #198), current or voided.
	 *
	 * @return <code>null</code> if the token is not one this server issued.
	 */
	public synchronized Match match(String token) {
		if (token == null || token.isEmpty()) {
			return null;
		}
		byte[] hash = UserStore.hash(token).getBytes(StandardCharsets.US_ASCII);
		for (Link link : _links) {
			// Constant-time comparison: the hash of a guessed token must not be probed by timing.
			if (same(hash, link.getTokenHash())) {
				return new Match(link, null, false);
			}
			for (Recipient recipient : link._recipients) {
				if (same(hash, recipient.getTokenHash())) {
					return new Match(link, recipient, false);
				}
				for (String voided : recipient.getVoided()) {
					if (same(hash, voided)) {
						return new Match(link, recipient, true);
					}
				}
			}
		}
		return null;
	}

	private static boolean same(byte[] hash, String stored) {
		return !stored.isEmpty() && MessageDigest.isEqual(hash, stored.getBytes(StandardCharsets.US_ASCII));
	}

	/** A newly created personal link, with its own token and one token per recipient. */
	public static final class IssuedPersonal {

		private final Issued _issued;

		private final java.util.Map<String, String> _tokens;

		IssuedPersonal(Issued issued, java.util.Map<String, String> tokens) {
			_issued = issued;
			_tokens = tokens;
		}

		/** The link and its own token. */
		public Issued getIssued() {
			return _issued;
		}

		/** The token of every recipient, by contact id, in the order of the recipients. */
		public java.util.Map<String, String> getTokens() {
			return _tokens;
		}
	}

	/**
	 * Issues a personal link, see issue #198.
	 *
	 * @param recipients
	 *        The ids of the contacts it is sent to, without repetition; empty for an open link.
	 */
	public synchronized IssuedPersonal createPersonal(String owner, String path, String label, String expires,
			int maxPrivacy, int minRating, java.util.Collection<String> rights, String createdBy,
			List<String> recipients) throws IOException {
		String token = newToken();
		String now = Instant.now().toString();
		Link link = new Link(freeId(), UserStore.hash(token), owner, path, label, expires, maxPrivacy, minRating,
			rights, createdBy, now);
		link._type = PERSONAL;
		link._addressed = !recipients.isEmpty();
		java.util.Map<String, String> tokens = new java.util.LinkedHashMap<>();
		for (String contact : recipients) {
			if (tokens.containsKey(contact)) {
				continue;
			}
			String own = newToken();
			link._recipients.add(new Recipient(contact, UserStore.hash(own), now, ""));
			tokens.put(contact, own);
		}
		_links.add(link);
		store();
		return new IssuedPersonal(new Issued(link, token), tokens);
	}

	/**
	 * Gives a link that was just issued the label whose photographs it shows, see issue #213.
	 *
	 * <p>
	 * Part of issuing it: called right after {@link #create} or {@link #createPersonal} and before
	 * the link was ever answered, never to change what a link handed out shows.
	 * </p>
	 */
	public synchronized void setPhotoLabel(Link link, String photoLabel) throws IOException {
		String label = photoLabel == null ? "" : photoLabel;
		if (label.equals(link._photoLabel)) {
			return;
		}
		link._photoLabel = label;
		store();
	}

	/**
	 * Carries every link on the given album that shows the given label along to the label's new
	 * name, see issue #213.
	 *
	 * <p>
	 * The decision of #213: a link was handed out on the photographs of a label, and a rename
	 * changes how the author spells that set, not which photographs it holds. A link that froze the
	 * old spelling would silently show nothing after the rename.
	 * </p>
	 *
	 * @param owner
	 *        The space the album lies in, see {@link Link#getOwner()}.
	 * @param path
	 *        The album's path in that space.
	 * @return How many links were rewritten.
	 */
	public synchronized int relabel(String owner, String path, String from, String to) throws IOException {
		if (owner == null || path == null || from == null || from.isEmpty() || to == null || to.isEmpty()
			|| from.equals(to)) {
			return 0;
		}
		int changed = 0;
		for (Link link : _links) {
			if (owner.equals(link.getOwner()) && path.equals(link.getPath()) && from.equals(link._photoLabel)) {
				link._photoLabel = to;
				changed++;
			}
		}
		if (changed > 0) {
			store();
			LOG.info("Carried " + changed + " share link(s) on '" + path + "' from the label '" + from + "' to '" + to
				+ "'.");
		}
		return changed;
	}

	/**
	 * Marks the given recipient's current token as opened: it identifies nobody any more.
	 *
	 * @return Whether this call opened it; <code>false</code> if it was opened before, which is
	 *         how only one of two concurrent first opens wins.
	 */
	public synchronized boolean open(Link link, Recipient recipient) throws IOException {
		if (recipient.isOpened() || !link._recipients.contains(recipient)) {
			return false;
		}
		recipient._opened = Instant.now().toString();
		store();
		return true;
	}

	/** Takes back {@link #open(Link, Recipient)}, for an identification that could not be finished. */
	synchronized void reopen(Recipient recipient) throws IOException {
		recipient._opened = "";
		store();
	}

	/**
	 * Voids the given recipient's token and issues a fresh one, see issue #198.
	 *
	 * @return The fresh token, <code>null</code> if the contact is no recipient of the link.
	 */
	public synchronized String resend(String linkId, String contact) throws IOException {
		Link link = get(linkId);
		Recipient recipient = link == null ? null : link.recipient(contact);
		if (recipient == null) {
			return null;
		}
		String token = newToken();
		recipient._voided.add(recipient._tokenHash);
		recipient._tokenHash = UserStore.hash(token);
		recipient._issued = Instant.now().toString();
		recipient._opened = "";
		store();
		return token;
	}

	/**
	 * Shuts the given contact out of the given personal link, or lets them in again (issue #198).
	 *
	 * @return The link, <code>null</code> if there is none of that id.
	 */
	public synchronized Link shutOut(String linkId, String contact, boolean shutOut) throws IOException {
		Link link = get(linkId);
		if (link == null) {
			return null;
		}
		boolean changed = shutOut ? link._shutOut.putIfAbsent(contact, Instant.now().toString()) == null
			: link._shutOut.remove(contact) != null;
		if (changed) {
			store();
		}
		return link;
	}

	/**
	 * Notes that the given contact came in through the given personal link, see issue #203.
	 *
	 * <p>
	 * Written at most every {@link ContactStore#USE_GRANULARITY} per link and contact, so that a
	 * session of a hundred requests is one write.
	 * </p>
	 */
	public synchronized void visited(Link link, String contact) throws IOException {
		if (!_links.contains(link) || contact == null || contact.isEmpty()) {
			return;
		}
		Instant now = Instant.now();
		Visit visit = link._visitors.get(contact);
		if (visit != null) {
			Instant last;
			try {
				last = Instant.parse(visit._last);
			} catch (java.time.DateTimeException ex) {
				last = Instant.EPOCH;
			}
			if (java.time.Duration.between(last, now).compareTo(ContactStore.USE_GRANULARITY) < 0) {
				return;
			}
			visit._last = now.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
		} else {
			String at = now.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
			link._visitors.put(contact, new Visit(at, at));
		}
		store();
	}

	/**
	 * Forgets the given contact on every link, see issue #203: they were deleted from the space.
	 *
	 * <p>
	 * Their own link of every personal link goes with its voided predecessors &mdash; from then on
	 * tokens nobody issued &mdash; and so do their visits and their shutting out. A link that loses
	 * its last recipient stays addressed, see {@link Link#isAddressed()}.
	 * </p>
	 *
	 * @return How many links mentioned the contact.
	 */
	public synchronized int forgetContact(String contact) throws IOException {
		int changed = 0;
		for (Link link : _links) {
			boolean mentioned = false;
			for (java.util.Iterator<Recipient> it = link._recipients.iterator(); it.hasNext();) {
				if (it.next().getContact().equals(contact)) {
					link._addressed = true;
					it.remove();
					mentioned = true;
				}
			}
			mentioned |= link._shutOut.remove(contact) != null;
			mentioned |= link._visitors.remove(contact) != null;
			if (mentioned) {
				changed++;
			}
		}
		if (changed > 0) {
			store();
		}
		return changed;
	}

	private String newToken() {
		byte[] bytes = new byte[TOKEN_BYTES];
		_random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
		return create(owner, path, label, expires, maxPrivacy, minRating, rights, "");
	}

	/** Issues a link that knows who handed it out, see issue #84. */
	public synchronized Issued create(String owner, String path, String label, String expires, int maxPrivacy,
			int minRating, java.util.Collection<String> rights, String createdBy) throws IOException {
		String token = newToken();

		Link link = new Link(freeId(), UserStore.hash(token), owner, path, label, expires, maxPrivacy, minRating,
			rights, createdBy, Instant.now().toString());
		_links.add(link);
		store();
		return new Issued(link, token);
	}

	/**
	 * Deletes the link of the given id, as if it had never been made (issue #217).
	 *
	 * <p>
	 * The record goes, and with it the tokens of its recipients: the link is the permission (issue
	 * #83), so deleting it is what closes the door, and its token is from then on one this server
	 * never issued. What was uploaded through it keeps its attribution, which was copied at the
	 * upload (issue #53); the contacts it reached stay contacts of the space. Ending the contact
	 * sessions opened through it is the {@link ContactStore}'s part, see
	 * {@link ContactStore#endSessionsOfLink(String)}.
	 * </p>
	 *
	 * @return The deleted link, <code>null</code> if there is none of that id.
	 */
	public synchronized Link delete(String id) throws IOException {
		Link link = get(id);
		if (link == null) {
			return null;
		}
		_links.remove(link);
		store();
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
						Link link = readLink(in);
						if (link != null) {
							result.add(link);
						}
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

	/**
	 * Reads one record; <code>null</code> for one an earlier build kept as withdrawn, which is a
	 * deleted link since issue #217.
	 */
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
		String createdBy = "";
		String revoked = "";
		java.util.List<String> rights = null;
		String type = ANONYMOUS;
		String photoLabel = "";
		List<Recipient> recipients = new ArrayList<>();
		java.util.Map<String, String> shutOut = new java.util.LinkedHashMap<>();
		boolean addressed = false;
		java.util.Map<String, Visit> visitors = new java.util.LinkedHashMap<>();
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
				case CREATED_BY__PROP:
					createdBy = in.nextString();
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
				case TYPE__PROP:
					type = in.nextString();
					break;
				case PHOTO_LABEL__PROP:
					photoLabel = in.nextString();
					break;
				case RECIPIENTS__PROP:
					in.beginArray();
					while (in.hasNext()) {
						recipients.add(readRecipient(in));
					}
					in.endArray();
					break;
				case SHUT_OUT__PROP:
					in.beginArray();
					while (in.hasNext()) {
						readShutOut(in, shutOut);
					}
					in.endArray();
					break;
				case ADDRESSED__PROP:
					addressed = in.nextBoolean();
					break;
				case VISITORS__PROP:
					in.beginArray();
					while (in.hasNext()) {
						readVisit(in, visitors);
					}
					in.endArray();
					break;
				default:
					in.skipValue();
					break;
			}
		}
		in.endObject();
		if (!revoked.isEmpty()) {
			// Withdrawn by an earlier build: deleted now, and gone from the file with the next write.
			LOG.info("Dropping the share link '" + id + "' withdrawn on " + revoked + ": a withdrawn link is deleted.");
			return null;
		}
		// A link written before issue #83 says nothing about its rights: it allowed looking.
		Link link = new Link(id, tokenHash, owner, path, label, expires, maxPrivacy, minRating,
			rights == null ? Rights.READ_ONLY : rights, createdBy, created);
		// A link written before issue #213 says nothing about a label: it shows the whole album.
		link._photoLabel = photoLabel;
		if (PERSONAL.equals(type)) {
			link._type = PERSONAL;
			link._recipients.addAll(recipients);
			link._shutOut.putAll(shutOut);
			link._addressed = addressed || !recipients.isEmpty();
			link._visitors.putAll(visitors);
		} else if (!ANONYMOUS.equals(type)) {
			// A type this build does not know is no anonymous link: it must not open to everybody.
			LOG.warning("The share link '" + id + "' has the unknown type '" + type + "'; it opens nothing.");
			link._type = type;
		}
		return link;
	}

	private static Recipient readRecipient(JsonReader in) throws IOException {
		String contact = "";
		String tokenHash = "";
		String issued = "";
		String opened = "";
		List<String> voided = new ArrayList<>();
		in.beginObject();
		while (in.hasNext()) {
			switch (in.nextName()) {
				case "contact":
					contact = in.nextString();
					break;
				case TOKEN_HASH__PROP:
					tokenHash = in.nextString();
					break;
				case "issued":
					issued = in.nextString();
					break;
				case "opened":
					opened = in.nextString();
					break;
				case "voided":
					in.beginArray();
					while (in.hasNext()) {
						voided.add(in.nextString());
					}
					in.endArray();
					break;
				default:
					in.skipValue();
					break;
			}
		}
		in.endObject();
		Recipient result = new Recipient(contact, tokenHash, issued, opened);
		result._voided.addAll(voided);
		return result;
	}

	private static void readVisit(JsonReader in, java.util.Map<String, Visit> visitors) throws IOException {
		String contact = "";
		String first = "";
		String last = "";
		in.beginObject();
		while (in.hasNext()) {
			switch (in.nextName()) {
				case "contact":
					contact = in.nextString();
					break;
				case "first":
					first = in.nextString();
					break;
				case "last":
					last = in.nextString();
					break;
				default:
					in.skipValue();
					break;
			}
		}
		in.endObject();
		if (!contact.isEmpty()) {
			visitors.put(contact, new Visit(first, last.isEmpty() ? first : last));
		}
	}

	private static void readShutOut(JsonReader in, java.util.Map<String, String> shutOut) throws IOException {
		String contact = "";
		String at = "";
		in.beginObject();
		while (in.hasNext()) {
			switch (in.nextName()) {
				case "contact":
					contact = in.nextString();
					break;
				case "at":
					at = in.nextString();
					break;
				default:
					in.skipValue();
					break;
			}
		}
		in.endObject();
		if (!contact.isEmpty()) {
			shutOut.put(contact, at);
		}
	}

	/**
	 * Rewrites the path of every link on the given folder and below it, see issue #130.
	 *
	 * <p>
	 * A folder is renamed when its properties are written, and a link handed out yesterday must
	 * keep opening the album it was handed out on. The paths stored here are the only reference to
	 * a folder that lives outside the folder itself — an index picture is relative to its own
	 * album, and a sidecar and a <code>.hashes.json</code> ride along inside the folder — so this
	 * is the whole of "keeping the references".
	 * </p>
	 *
	 * @param owner
	 *        The space the folder lies in, see {@link Link#getOwner()}.
	 * @param oldPath
	 *        The folder's path in that space before the rename.
	 * @param newPath
	 *        Its path after the rename.
	 * @return How many links were rewritten.
	 */
	public synchronized int rename(String owner, String oldPath, String newPath) throws IOException {
		if (owner == null || oldPath == null || newPath == null || oldPath.isEmpty()
			|| oldPath.equals(newPath)) {
			return 0;
		}
		int renamed = 0;
		for (Link link : _links) {
			if (!owner.equals(link.getOwner())) {
				continue;
			}
			String path = link.getPath();
			if (path.equals(oldPath)) {
				link.setPath(newPath);
				renamed++;
			} else if (path.startsWith(oldPath + "/")) {
				link.setPath(newPath + path.substring(oldPath.length()));
				renamed++;
			}
		}
		if (renamed > 0) {
			store();
			LOG.info("Rewrote " + renamed + " share link(s) from '" + oldPath + "' to '" + newPath + "'.");
		}
		return renamed;
	}

	/**
	 * Deletes every link the given user handed out, see issues #84 and #217.
	 *
	 * <p>
	 * Removing somebody takes back what they gave away: a link is a door they opened, and it must
	 * not outlive their account. Links of nobody (stored before the creator was recorded) are left
	 * alone — they were not this user's to lose.
	 * </p>
	 *
	 * @return The deleted links.
	 */
	public synchronized List<Link> deleteCreatedBy(String user) throws IOException {
		List<Link> deleted = new ArrayList<>();
		if (user == null || user.isEmpty()) {
			return deleted;
		}
		for (Link link : _links) {
			if (user.equals(link.getCreatedBy())) {
				deleted.add(link);
			}
		}
		if (!deleted.isEmpty()) {
			_links.removeAll(deleted);
			store();
		}
		return deleted;
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
		out.name(CREATED_BY__PROP);
		out.value(link.getCreatedBy());
		out.name(CREATED__PROP);
		out.value(link.getCreated());
		if (!link.getPhotoLabel().isEmpty()) {
			// A link showing the whole album is written as every link was before issue #213.
			out.name(PHOTO_LABEL__PROP);
			out.value(link.getPhotoLabel());
		}
		if (!ANONYMOUS.equals(link.getType())) {
			// An anonymous link is written as every link was before issue #198.
			out.name(TYPE__PROP);
			out.value(link.getType());
			out.name(RECIPIENTS__PROP);
			out.beginArray();
			for (Recipient recipient : link._recipients) {
				out.beginObject();
				out.name("contact");
				out.value(recipient.getContact());
				out.name(TOKEN_HASH__PROP);
				out.value(recipient.getTokenHash());
				out.name("voided");
				out.beginArray();
				for (String voided : recipient.getVoided()) {
					out.value(voided);
				}
				out.endArray();
				out.name("issued");
				out.value(recipient.getIssued());
				out.name("opened");
				out.value(recipient.getOpened());
				out.endObject();
			}
			out.endArray();
			out.name(SHUT_OUT__PROP);
			out.beginArray();
			for (java.util.Map.Entry<String, String> entry : link._shutOut.entrySet()) {
				out.beginObject();
				out.name("contact");
				out.value(entry.getKey());
				out.name("at");
				out.value(entry.getValue());
				out.endObject();
			}
			out.endArray();
			if (link.isAddressed()) {
				out.name(ADDRESSED__PROP);
				out.value(true);
			}
			if (!link._visitors.isEmpty()) {
				out.name(VISITORS__PROP);
				out.beginArray();
				for (java.util.Map.Entry<String, Visit> entry : link._visitors.entrySet()) {
					out.beginObject();
					out.name("contact");
					out.value(entry.getKey());
					out.name("first");
					out.value(entry.getValue().getFirst());
					out.name("last");
					out.value(entry.getValue().getLast());
					out.endObject();
				}
				out.endArray();
			}
		}
		out.endObject();
	}
}
