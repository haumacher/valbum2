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
 * The invitations of this server, persisted beside the album tree (issue #52).
 *
 * <p>
 * An invitation is a single-use token that <em>creates a user</em>. It is not a login and it opens
 * nothing: whoever holds it may accept it once, under a name of their choosing, and what they
 * become is the {@link Link#getRole() role} the inviter picked. That is the whole difference to the
 * share links of {@link ShareStore}, which open one subtree to whoever holds them and create
 * nobody.
 * </p>
 *
 * <p>
 * The store lives in {@link UserStore#DIRECTORY_NAME} at the root of the served folder, never
 * inside a user's album folder, and it holds a <em>hash</em> of every issued token, never the token
 * itself: a stolen store cannot be replayed against the server. Nothing is ever deleted — an
 * accepted invitation is marked {@link Link#getUsed() used} and a withdrawn one
 * {@link Link#getRevoked() revoked}, so that the management screens of issue #55 can say what
 * became of an invitation somebody handed out. The file format is persisted data and therefore
 * versioned:
 * </p>
 *
 * <pre>
 * {"version":1,"invitations":[{"id":"a1b2c3d4","tokenHash":"&lt;64 hex chars&gt;","role":"member",
 *   "invitedBy":"alice","note":"Uncle Bob","expires":"2026-09-20T00:00:00Z",
 *   "created":"2026-09-13T10:11:12Z","used":"","usedBy":"","revoked":""}]}
 * </pre>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class InvitationStore {

	private static final Logger LOG = Logger.getLogger(InvitationStore.class.getName());

	/** The name of the store within {@link UserStore#DIRECTORY_NAME}. */
	public static final String FILE_NAME = "invitations.json";

	/**
	 * The path segment the web application is served under for an invitation.
	 *
	 * <p>
	 * An invitation's URL is <code>&lt;context&gt;/i/&lt;token&gt;/</code>: the static handler
	 * serves the same application there with its base href rewritten, exactly as it does for the
	 * <code>/s/</code> of a share link, and the app sends the token as its bearer so that
	 * <code>?type=auth</code> can tell it who invited it and as what.
	 * </p>
	 */
	public static final String URL_SEGMENT = "i";

	/** The version this build writes, see {@link InvitationStore}. */
	public static final int VERSION = 1;

	/** How long an invitation lives when the request names no expiry: seven days. */
	public static final int DEFAULT_DAYS = 7;

	/** The number of random bytes a token is built from, as for a device token. */
	private static final int TOKEN_BYTES = 32;

	/** The number of random bytes an id is built from; it is a name, not a secret. */
	private static final int ID_BYTES = 6;

	private static final String VERSION__PROP = "version";

	private static final String INVITATIONS__PROP = "invitations";

	private static final String ID__PROP = "id";

	private static final String TOKEN_HASH__PROP = "tokenHash";

	private static final String ROLE__PROP = "role";

	private static final String CLEARANCE__PROP = "clearance";

	private static final String SHARE__PROP = "mayShare";

	private static final String INVITED_BY__PROP = "invitedBy";

	private static final String NOTE__PROP = "note";

	private static final String EXPIRES__PROP = "expires";

	private static final String CREATED__PROP = "created";

	private static final String USED__PROP = "used";

	private static final String USED_BY__PROP = "usedBy";

	private static final String REVOKED__PROP = "revoked";

	/** A single invitation, see {@link InvitationStore}. */
	public static final class Link {

		private final String _id;

		private final String _tokenHash;

		private final String _role;

		private final String _clearance;

		private final boolean _share;

		private final String _invitedBy;

		private final String _note;

		private final String _expires;

		private final String _created;

		private String _used;

		private String _usedBy;

		private String _revoked;

		/** Creates a {@link Link}. */
		public Link(String id, String tokenHash, String role, String invitedBy, String note, String expires,
				String created, String used, String usedBy, String revoked) {
			this(id, tokenHash, role, Clearances.ofRole(role), Clearances.mayShareByRole(role), invitedBy,
				note, expires, created, used, usedBy, revoked);
		}

		/** Creates a {@link Link} carrying the permission the accepting user is created with (issue #82). */
		public Link(String id, String tokenHash, String role, String clearance, boolean share, String invitedBy,
				String note, String expires, String created, String used, String usedBy, String revoked) {
			_clearance = clearance;
			_share = share;
			_id = id;
			_tokenHash = tokenHash;
			_role = role;
			_invitedBy = invitedBy;
			_note = note;
			_expires = expires;
			_created = created;
			_used = used;
			_usedBy = usedBy;
			_revoked = revoked;
		}

		/** The short id of this invitation; what a request names it by. */
		public String getId() {
			return _id;
		}

		/** The SHA-256 hash of the invitation's token, in lower-case hex. */
		public String getTokenHash() {
			return _tokenHash;
		}

		/** The {@link Roles role} the accepting user is created with: member or guest. */
		public String getClearance() {
			return _clearance;
		}

		/** Whether the accepting user may create share links, see issue #82. */
		public boolean isShare() {
			return _share;
		}

		/** The role the accepting user is created with. */
		public String getRole() {
			return _role;
		}

		/** The name of the user who issued this invitation. */
		public String getInvitedBy() {
			return _invitedBy;
		}

		/** The note the inviter wrote for themselves, empty if they wrote none. */
		public String getNote() {
			return _note;
		}

		/** When the invitation expires, an ISO-8601 instant; never empty. */
		public String getExpires() {
			return _expires;
		}

		/** When the invitation was issued, an ISO-8601 instant. */
		public String getCreated() {
			return _created;
		}

		/** When the invitation was accepted, an ISO-8601 instant; empty while it is unused. */
		public String getUsed() {
			return _used;
		}

		/** The name of the user this invitation created, empty while it is unused. */
		public String getUsedBy() {
			return _usedBy;
		}

		/** When the invitation was withdrawn, an ISO-8601 instant; empty while it stands. */
		public String getRevoked() {
			return _revoked;
		}

		void setUsed(String used, String usedBy) {
			_used = used;
			_usedBy = usedBy;
		}

		void setRevoked(String revoked) {
			_revoked = revoked;
		}

		/** Whether this invitation was already accepted. */
		public boolean isUsed() {
			return !_used.isEmpty();
		}

		/** Whether this invitation was withdrawn. */
		public boolean isRevoked() {
			return !_revoked.isEmpty();
		}

		/**
		 * Whether this invitation's lifetime has run out at the given moment.
		 *
		 * <p>
		 * An {@link #getExpires() expiry} this server cannot parse is treated as expired: an
		 * invitation whose lifetime nobody can read is not an invitation that lives forever.
		 * </p>
		 */
		public boolean isExpired(Instant now) {
			if (_expires.isEmpty()) {
				// A record from a damaged file: an invitation without a lifetime is no invitation.
				LOG.warning("The invitation '" + _id + "' has no expiry; it is treated as expired.");
				return true;
			}
			try {
				return !Instant.parse(_expires).isAfter(now);
			} catch (DateTimeException ex) {
				LOG.warning("The invitation '" + _id + "' has an unreadable expiry '" + _expires
					+ "'; it is treated as expired.");
				return true;
			}
		}

		/** Whether this invitation can no longer be accepted, for whatever reason. */
		public boolean isDead(Instant now) {
			return isUsed() || isRevoked() || isExpired(now);
		}

		/** Whether this invitation can still be accepted right now. */
		public boolean isLive() {
			return !isDead(Instant.now());
		}

		@Override
		public String toString() {
			return "invitation:" + _id + " (" + _role + ", by " + _invitedBy + ")"
				+ (isUsed() ? " used by " + _usedBy : "") + (isRevoked() ? " (revoked)" : "");
		}
	}

	/** A newly issued {@link Link} together with its token, answered exactly once. */
	public static final class Issued {

		private final Link _invitation;

		private final String _token;

		Issued(Link invitation, String token) {
			_invitation = invitation;
			_token = token;
		}

		/** The invitation that was recorded. */
		public Link getInvitation() {
			return _invitation;
		}

		/** The token accepting it, which this server never stores and never answers again. */
		public String getToken() {
			return _token;
		}
	}

	private final Path _file;

	private final SecureRandom _random = new SecureRandom();

	private List<Link> _invitations = new ArrayList<>();

	/** Whether the file on disk could not be read; it is set aside instead of overwritten. */
	private boolean _damaged;

	/**
	 * Creates an {@link InvitationStore} for the album tree rooted at the given path.
	 *
	 * <p>
	 * The store is read immediately, so that a restarted server keeps honouring the invitations it
	 * issued. A library that never invited anybody has no file and an empty store.
	 * </p>
	 */
	public InvitationStore(Path basePath) {
		_file = basePath.resolve(UserStore.DIRECTORY_NAME).resolve(FILE_NAME);
		load();
	}

	/** The file this store is persisted in. */
	public Path getFile() {
		return _file;
	}

	/** Every invitation of this server, in the order they were issued. */
	public synchronized List<Link> getInvitations() {
		return Collections.unmodifiableList(new ArrayList<>(_invitations));
	}

	/**
	 * The invitation of the given id.
	 *
	 * @return <code>null</code> if there is none.
	 */
	public synchronized Link get(String id) {
		for (Link invitation : _invitations) {
			if (invitation.getId().equals(id)) {
				return invitation;
			}
		}
		return null;
	}

	/**
	 * The invitation the given token names, whether it can still be accepted or not.
	 *
	 * <p>
	 * A dead invitation is answered too, and deliberately: the caller is told that the invitation
	 * expired, was used or was withdrawn, which is a different thing from a token nobody ever
	 * issued.
	 * </p>
	 *
	 * @return <code>null</code> if the token is not one this server issued.
	 */
	public synchronized Link lookup(String token) {
		if (token == null || token.isEmpty()) {
			return null;
		}
		byte[] hash = UserStore.hash(token).getBytes(StandardCharsets.US_ASCII);
		for (Link invitation : _invitations) {
			// Constant-time comparison: the hash of a guessed token must not be probed by timing.
			if (MessageDigest.isEqual(hash, invitation.getTokenHash().getBytes(StandardCharsets.US_ASCII))) {
				return invitation;
			}
		}
		return null;
	}

	/**
	 * Issues a new invitation and persists its record.
	 *
	 * <p>
	 * The token is returned to the caller exactly once and never stored.
	 * </p>
	 *
	 * @param expires
	 *        When the invitation runs out, an ISO-8601 instant; empty for
	 *        {@link #DEFAULT_DAYS} days from now.
	 */
	public synchronized Issued create(String role, String invitedBy, String note, String expires)
			throws IOException {
		return create(role, Clearances.ofRole(role), Clearances.mayShareByRole(role), invitedBy, note, expires);
	}

	/**
	 * Issues an invitation carrying the permission the accepting user is created with (issue #82).
	 */
	public synchronized Issued create(String role, String clearance, boolean share, String invitedBy, String note,
			String expires) throws IOException {
		byte[] bytes = new byte[TOKEN_BYTES];
		_random.nextBytes(bytes);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

		String lifetime = expires == null || expires.isEmpty()
			? Instant.now().plus(java.time.Duration.ofDays(DEFAULT_DAYS)).toString() : expires;
		Link invitation = new Link(freeId(), UserStore.hash(token), role,
			Clearances.isKnown(clearance) ? clearance : Clearances.ofRole(role), share, invitedBy,
			note == null ? "" : note, lifetime, Instant.now().toString(), "", "", "");
		_invitations.add(invitation);
		store();
		return new Issued(invitation, token);
	}

	/**
	 * Marks the invitation of the given id as accepted by the user of the given name.
	 *
	 * <p>
	 * The record stays and its token is never accepted again: a single-use token is used up by
	 * this call, which is why it is made before the answer is written.
	 * </p>
	 *
	 * @return The invitation, <code>null</code> if there is none of that id.
	 */
	public synchronized Link markUsed(String id, String userName) throws IOException {
		Link invitation = get(id);
		if (invitation == null) {
			return null;
		}
		if (!invitation.isUsed()) {
			invitation.setUsed(Instant.now().toString(), userName);
			store();
		}
		return invitation;
	}

	/**
	 * Marks the invitation of the given id as withdrawn.
	 *
	 * <p>
	 * The record stays: a withdrawn invitation is shown as withdrawn, and its id is never handed
	 * out again. A user the invitation already created is untouched — withdrawing an invitation is
	 * not removing somebody.
	 * </p>
	 *
	 * @return The invitation, <code>null</code> if there is none of that id.
	 */
	public synchronized Link revoke(String id) throws IOException {
		Link invitation = get(id);
		if (invitation == null) {
			return null;
		}
		if (!invitation.isRevoked()) {
			invitation.setRevoked(Instant.now().toString());
			store();
		}
		return invitation;
	}

	/** An id no invitation of this store has. */
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
			_invitations = readInvitations(new JsonReader(new ReaderAdapter(reader)));
		} catch (IOException | RuntimeException ex) {
			// A broken store must not lock the server up; it accepts no invitation instead.
			LOG.log(Level.WARNING, "Cannot read the invitation store '" + _file + "': " + ex.getMessage());
			_invitations = new ArrayList<>();
			_damaged = true;
		}
	}

	private static List<Link> readInvitations(JsonReader in) throws IOException {
		List<Link> result = new ArrayList<>();
		int version = 0;
		in.beginObject();
		while (in.hasNext()) {
			String key = in.nextName();
			switch (key) {
				case VERSION__PROP:
					version = in.nextInt();
					break;
				case INVITATIONS__PROP:
					in.beginArray();
					while (in.hasNext()) {
						result.add(readInvitation(in));
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
			LOG.warning("The invitation store was written by a newer version (" + version + " > " + VERSION
				+ "); unknown entries are kept as read.");
		}
		return result;
	}

	private static Link readInvitation(JsonReader in) throws IOException {
		String id = "";
		String tokenHash = "";
		String role = Roles.EDIT;
		String clearance = "";
		Boolean share = null;
		String invitedBy = "";
		String note = "";
		String expires = "";
		String created = "";
		String used = "";
		String usedBy = "";
		String revoked = "";
		in.beginObject();
		while (in.hasNext()) {
			String key = in.nextName();
			switch (key) {
				case ID__PROP:
					id = in.nextString();
					break;
				case TOKEN_HASH__PROP:
					tokenHash = in.nextString();
					break;
				case ROLE__PROP:
					role = in.nextString();
					break;
				case CLEARANCE__PROP:
					clearance = in.nextString();
					break;
				case SHARE__PROP:
					share = Boolean.valueOf(in.nextBoolean());
					break;
				case INVITED_BY__PROP:
					invitedBy = in.nextString();
					break;
				case NOTE__PROP:
					note = in.nextString();
					break;
				case EXPIRES__PROP:
					expires = in.nextString();
					break;
				case CREATED__PROP:
					created = in.nextString();
					break;
				case USED__PROP:
					used = in.nextString();
					break;
				case USED_BY__PROP:
					usedBy = in.nextString();
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
		// An invitation written before issue #82 carries neither: its role says what it offers.
		return new Link(id, tokenHash, role,
			Clearances.isKnown(clearance) ? clearance : Clearances.ofRole(role),
			share == null ? Clearances.mayShareByRole(role) : share.booleanValue(),
			invitedBy, note, expires, created, used, usedBy, revoked);
	}

	/** Writes this store to disk, atomically: a crash never leaves a half-written store. */
	public synchronized void store() throws IOException {
		Path directory = _file.getParent();
		Files.createDirectories(directory);

		if (_damaged && Files.exists(_file)) {
			// The unreadable file may still hold invitations somebody handed out; keep it for repair.
			Path broken = directory.resolve(FILE_NAME + ".broken-" + Instant.now().toString().replace(':', '-'));
			Files.move(_file, broken, StandardCopyOption.REPLACE_EXISTING);
			LOG.warning("Kept the unreadable invitation store as '" + broken + "'.");
		}
		_damaged = false;

		Path tmpFile = Files.createTempFile(directory, "invitations", ".json");
		try (Writer writer = new OutputStreamWriter(Files.newOutputStream(tmpFile), StandardCharsets.UTF_8)) {
			try (JsonWriter out = new JsonWriter(new WriterAdapter(writer))) {
				out.beginObject();
				out.name(VERSION__PROP);
				out.value(VERSION);
				out.name(INVITATIONS__PROP);
				out.beginArray();
				for (Link invitation : _invitations) {
					writeInvitation(out, invitation);
				}
				out.endArray();
				out.endObject();
			}
		}
		Files.move(tmpFile, _file, StandardCopyOption.REPLACE_EXISTING);
	}

	private static void writeInvitation(JsonWriter out, Link invitation) throws IOException {
		out.beginObject();
		out.name(ID__PROP);
		out.value(invitation.getId());
		out.name(TOKEN_HASH__PROP);
		out.value(invitation.getTokenHash());
		out.name(ROLE__PROP);
		out.value(invitation.getRole());
		out.name(CLEARANCE__PROP);
		out.value(invitation.getClearance());
		out.name(SHARE__PROP);
		out.value(invitation.isShare());
		out.name(INVITED_BY__PROP);
		out.value(invitation.getInvitedBy());
		out.name(NOTE__PROP);
		out.value(invitation.getNote());
		out.name(EXPIRES__PROP);
		out.value(invitation.getExpires());
		out.name(CREATED__PROP);
		out.value(invitation.getCreated());
		out.name(USED__PROP);
		out.value(invitation.getUsed());
		out.name(USED_BY__PROP);
		out.value(invitation.getUsedBy());
		out.name(REVOKED__PROP);
		out.value(invitation.getRevoked());
		out.endObject();
	}
}
