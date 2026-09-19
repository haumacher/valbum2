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
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The device codes of this server, persisted beside the album tree (issue #65).
 *
 * <p>
 * A device code adds a further device of <em>one's own</em>. It is not an invitation and it must
 * never be mistaken for one: whoever types it is signed in as the user who asked for it, so it is
 * deliberately nothing that can be forwarded. There is no URL, no path segment and no bearer — the
 * code exists only as the body of one pairing request, it lives
 * {@value #LIFETIME_MINUTES} minutes, and it works once. The device it pairs shows up in the
 * owner's device list immediately, so a stranger who got hold of a code is visible and can be
 * signed out again, see {@link UserStore#removeDevice(UserStore.User, UserStore.Device)}.
 * </p>
 *
 * <p>
 * That last argument is only true if a code dies with the device that made it, so it does: a code
 * is good only while its {@link Code#getIssuedBy() issuer} is still one of its user's devices, and
 * signing a device out therefore takes every code it had handed out and not yet spent with it.
 * Otherwise a stranger who was found in the device list would ask for a code before being thrown
 * out and walk straight back in. Two things enforce it, and either alone would: the issuer is
 * checked against the user's devices at pairing time (see
 * {@link AuthService#pair(de.haumacher.imageServer.shared.model.PairRequest)}), which is
 * fail-closed — an issuer this store cannot find in the device list, an empty one included, signs
 * nobody in — and {@link #revokeIssuedBy(String)} marks the pending codes of a device that is
 * being signed out, so that the file never holds a code that could still be typed.
 * </p>
 *
 * <p>
 * The store lives in {@link UserStore#DIRECTORY_NAME} at the root of the served folder and holds a
 * <em>hash</em> of every issued code, never the code itself. Entries that have been dead — used up
 * or run out — for more than {@value #KEEP_DEAD_HOURS} hours are dropped on the next write, so that
 * the file stays small while "this code expired" can still be told from "this code was already
 * used" for a day. The file format is persisted data and therefore versioned:
 * </p>
 *
 * <pre>
 * {"version":1,"codes":[{"id":"a1b2c3d4","codeHash":"&lt;64 hex chars&gt;","user":"alice",
 *   "issuedBy":"&lt;device id&gt;","created":"2026-09-15T10:11:12Z","expires":"2026-09-15T10:21:12Z",
 *   "used":"","usedBy":"","revoked":""}]}
 * </pre>
 *
 * <p>
 * A record may carry a <code>"kind"</code> (issue #92): absent or empty for an ordinary code, and
 * <code>"backup"</code> for the one code that neither runs out nor dies with the device that made
 * it, see {@link #KIND_BACKUP}. A file written before that field existed reads as a file of
 * ordinary codes, which is exactly what it is.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class DeviceCodeStore {

	private static final Logger LOG = Logger.getLogger(DeviceCodeStore.class.getName());

	/** The name of the store within {@link UserStore#DIRECTORY_NAME}. */
	public static final String FILE_NAME = "device-codes.json";

	/** The version this build writes, see {@link DeviceCodeStore}. */
	public static final int VERSION = 1;

	/** How long a device code lives: minutes, not days. */
	public static final int LIFETIME_MINUTES = 10;

	/** How long a dead code is kept, so that the server can still say how it died. */
	public static final int KEEP_DEAD_HOURS = 24;

	/**
	 * How long the dead code of an invitation is kept, see issue #89.
	 *
	 * <p>
	 * Thirty days rather than {@value #KEEP_DEAD_HOURS} hours, because the two are remembered for
	 * different lengths of time by the people holding them: a device code is read off one screen
	 * and typed on another within ten minutes, and nobody comes back to it a day later; an
	 * invitation is a link in a message, and the person it was sent to may well click it weeks
	 * after it ran out. What they are told then &mdash; "this invitation has expired", "this
	 * invitation was already used", "this invitation was withdrawn", see
	 * {@link de.haumacher.imageServer.InvitationRedirect} &mdash; is worth keeping the record for.
	 * Afterwards the link is simply unknown, which is all anybody can still say about it.
	 * </p>
	 */
	public static final int KEEP_DEAD_INVITATION_DAYS = 30;

	/**
	 * The characters a code is spelled with.
	 *
	 * <p>
	 * No <code>0</code>, <code>O</code>, <code>1</code> or <code>I</code>: the code is read off one
	 * screen and typed on another, and a person must never have to guess which of two look-alikes
	 * was meant.
	 * </p>
	 */
	public static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

	/** How many characters a code has. */
	public static final int CODE_LENGTH = 8;

	/**
	 * How many characters a {@link #KIND_BACKUP backup} code has, see issue #92.
	 *
	 * <p>
	 * Sixteen instead of {@value #CODE_LENGTH}, because the two live for different lengths of
	 * time: a device code is read off one screen and typed on another within ten minutes, so forty
	 * bits of it are more than anybody can guess in that window; a backup code is written down and
	 * kept until the day somebody needs it, and eighty bits is what makes "forever" an acceptable
	 * lifetime. Same alphabet and the same grouping, so it is read and typed exactly like every
	 * other code &mdash; the length is not what tells the two apart, {@link Code#getKind()} is.
	 * </p>
	 */
	public static final int BACKUP_CODE_LENGTH = 16;

	/**
	 * The number of random bytes the code of an invitation is built from, see issue #89.
	 *
	 * <p>
	 * Not characters to type but a token to click: an invitation travels in a URL, so it is
	 * spelled like a device token &mdash; {@value} random bytes, base64url &mdash; and nobody ever
	 * reads it off a screen. The store looks a code up by the hash of what it was given, so the
	 * length and the alphabet are free; it is the hash of the token exactly as it stands, which is
	 * also what makes an invitation issued before this build keep working, see
	 * {@link #lookup(String)}.
	 * </p>
	 */
	public static final int INVITATION_TOKEN_BYTES = 32;

	/** How long an invitation lives when the request names no expiry: seven days. */
	public static final int INVITATION_DEFAULT_DAYS = 7;

	/**
	 * What {@link Code#getKind()} holds for an ordinary code: nothing.
	 *
	 * <p>
	 * The empty string on purpose, so that a record written before issue #92 &mdash; which has no
	 * <code>kind</code> at all &mdash; reads back as exactly what it was.
	 * </p>
	 */
	public static final String KIND_DEVICE = "";

	/**
	 * What {@link Code#getKind()} holds for a backup code, see issue #92.
	 *
	 * <p>
	 * The one code that does not die with the device that made it and does not run out. Both
	 * exemptions hang on this value and on nothing else &mdash; not on the length, not on the
	 * issuer, not on an empty expiry &mdash; so that a damaged record can never be read as an
	 * eternal credential: a code without a lifetime that is not marked here is still treated as
	 * expired, see {@link Code#isExpired(Instant)}.
	 * </p>
	 */
	public static final String KIND_BACKUP = "backup";

	/**
	 * What {@link Code#getKind()} holds for the code of an invitation, see issue #89.
	 *
	 * <p>
	 * <b>An invitation is a pending user carrying a code.</b> Issuing an invitation creates the
	 * user it will name &mdash; nameless, without devices, with the permission the inviter chose
	 * &mdash; and one code of this kind for them; the {@code /i/<token>/} link carries the code,
	 * and redeeming it is the ordinary pairing of {@link AuthService}, which asks the person for a
	 * name and adds their first device. Invitation, seat code, recovery code, device code and
	 * backup code are one mechanism: a single-use secret that adds a device to one target user,
	 * and the kind says who issued it and how long it lives.
	 * </p>
	 *
	 * <p>
	 * Three things follow from the kind and from nothing else: the code is spelled as a
	 * {@value #INVITATION_TOKEN_BYTES}-byte token rather than typed, so it may be a bearer at
	 * <code>?type=auth</code> where no other code ever is; it lives days rather than
	 * {@value #LIFETIME_MINUTES} minutes; and it is exempt from
	 * {@link AuthService#DEVICE_CODE_ISSUER_GONE} like the {@link #KIND_BACKUP backup} code &mdash;
	 * an inviter who signs a device out must not void the invitations they sent. Their <em>user</em>
	 * being removed does void them, see {@link AuthService#removeUser(String)}.
	 * </p>
	 */
	public static final String KIND_INVITATION = "invitation";

	/**
	 * What stands in {@link Code#getIssuedBy()} for a code the server itself issued (issue #89).
	 *
	 * <p>
	 * The seat code of a space: at start-up the server issues one ordinary code for the
	 * administrator of every space whose administrator has no device yet, and prints it. It is the
	 * same code as every other — same alphabet, same {@value #LIFETIME_MINUTES} minutes, same
	 * single use — and the one thing that differs is that it has no issuing device, so the
	 * {@link AuthService#DEVICE_CODE_ISSUER_GONE} rule has nothing to check: it dies by being used,
	 * by running out, or by the next start-up, which withdraws it and prints a new one.
	 * </p>
	 *
	 * <p>
	 * It can never be mistaken for a device id: an id is eight characters of base64url
	 * ({@link UserStore}), and this is six lower-case letters.
	 * </p>
	 */
	public static final String SERVER_ISSUER = "server";

	/** The message a fixed code that is no code of this server is refused with, see issue #89. */
	public static String codeRefused(String code) {
		return "'" + code + "' is no sign-in code: a code is " + CODE_LENGTH + " characters of '"
			+ ALPHABET + "', optionally grouped with a dash.";
	}

	/**
	 * The normalised form of a code somebody fixed on the command line, see issue #89.
	 *
	 * <p>
	 * <code>--admin-code</code> takes the place of the random seat code, for the demo server and
	 * for a server whose administrator cannot read its journal. It has to be a code this server
	 * could have issued itself, or it would be a code nobody can type: the alphabet leaves out the
	 * look-alikes, and the length is what the field expects.
	 * </p>
	 *
	 * @throws IllegalArgumentException
	 *         With {@link #codeRefused(String)} if it is no such code.
	 */
	public static String checkCode(String code) {
		String normalised = normalise(code);
		if (normalised.length() != CODE_LENGTH) {
			throw new IllegalArgumentException(codeRefused(code));
		}
		for (int n = 0; n < normalised.length(); n++) {
			if (ALPHABET.indexOf(normalised.charAt(n)) < 0) {
				throw new IllegalArgumentException(codeRefused(code));
			}
		}
		return normalised;
	}

	/** After how many characters the code is grouped for display, see {@link #format(String)}. */
	private static final int GROUP_LENGTH = 4;

	/** The number of random bytes an id is built from; it is a name, not a secret. */
	private static final int ID_BYTES = 6;

	private static final String VERSION__PROP = "version";

	private static final String CODES__PROP = "codes";

	private static final String ID__PROP = "id";

	private static final String CODE_HASH__PROP = "codeHash";

	private static final String USER__PROP = "user";

	private static final String ISSUED_BY__PROP = "issuedBy";

	private static final String CREATED__PROP = "created";

	private static final String EXPIRES__PROP = "expires";

	private static final String USED__PROP = "used";

	private static final String USED_BY__PROP = "usedBy";

	private static final String REVOKED__PROP = "revoked";

	private static final String KIND__PROP = "kind";

	/** A single device code, see {@link DeviceCodeStore}. */
	public static final class Code {

		private final String _id;

		private final String _codeHash;

		private final String _user;

		private final String _issuedBy;

		private final String _created;

		private final String _expires;

		private String _used;

		private String _usedBy;

		private String _revoked;

		private final String _kind;

		/** Creates an ordinary {@link Code}. */
		public Code(String id, String codeHash, String user, String issuedBy, String created, String expires,
				String used, String usedBy, String revoked) {
			this(id, codeHash, user, issuedBy, created, expires, used, usedBy, revoked, KIND_DEVICE);
		}

		/** Creates a {@link Code} of the given kind, see {@link #getKind()}. */
		public Code(String id, String codeHash, String user, String issuedBy, String created, String expires,
				String used, String usedBy, String revoked, String kind) {
			_id = id;
			_codeHash = codeHash;
			_user = user;
			_issuedBy = issuedBy;
			_created = created;
			_expires = expires;
			_used = used;
			_usedBy = usedBy;
			_revoked = revoked;
			_kind = kind == null ? KIND_DEVICE : kind;
		}

		/**
		 * What kind of code this is: {@link #KIND_DEVICE} or {@link #KIND_BACKUP}.
		 *
		 * <p>
		 * The one thing that tells a backup code from every other, see {@link #KIND_BACKUP}. A
		 * kind this build does not know is read as an ordinary code, which is the fail-closed
		 * reading: an unknown kind gets no exemption.
		 * </p>
		 */
		public String getKind() {
			return _kind;
		}

		/** Whether this is the user's backup code, see {@link #KIND_BACKUP}. */
		public boolean isBackup() {
			return KIND_BACKUP.equals(_kind);
		}

		/** Whether this is the code of an invitation, see {@link #KIND_INVITATION}. */
		public boolean isInvitation() {
			return KIND_INVITATION.equals(_kind);
		}

		/** The short id of this code; what names it in the store, never on the wire. */
		public String getId() {
			return _id;
		}

		/** The SHA-256 hash of the normalised code, in lower-case hex. */
		public String getCodeHash() {
			return _codeHash;
		}

		/** The name of the user a device pairing with this code is signed in as. */
		public String getUser() {
			return _user;
		}

		/**
		 * The id of the device that asked for this code.
		 *
		 * <p>
		 * Not bookkeeping but the code's licence to work: the code is honoured only while this
		 * device is still one of {@link #getUser() its user's} devices, see {@link DeviceCodeStore}.
		 * </p>
		 */
		public String getIssuedBy() {
			return _issuedBy;
		}

		/** When the code was issued, an ISO-8601 instant. */
		public String getCreated() {
			return _created;
		}

		/**
		 * When the code stops working, an ISO-8601 instant.
		 *
		 * <p>
		 * Empty on a {@link #isBackup() backup} code and there only: that code is written down
		 * for a day nobody can foresee, so it dies by being used or by being withdrawn and by
		 * nothing else. Empty on any other record means a damaged file, and such a code is treated
		 * as expired, see {@link #isExpired(Instant)}.
		 * </p>
		 */
		public String getExpires() {
			return _expires;
		}

		/** When the code was typed, an ISO-8601 instant; empty while it is unused. */
		public String getUsed() {
			return _used;
		}

		/** The id of the device this code paired, empty while it is unused. */
		public String getUsedBy() {
			return _usedBy;
		}

		/** When the code was withdrawn, an ISO-8601 instant; empty while it stands. */
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

		/**
		 * Whether this code was withdrawn, see {@link DeviceCodeStore#revokeIssuedBy(String)}.
		 *
		 * <p>
		 * What happens when the device that issued it is signed out: the record stays, so that
		 * whoever types the code is told that its device is gone rather than that the code never
		 * existed.
		 * </p>
		 */
		public boolean isRevoked() {
			return !_revoked.isEmpty();
		}

		/** Whether a device already paired with this code. */
		public boolean isUsed() {
			return !_used.isEmpty();
		}

		/**
		 * Whether this code's ten minutes have run out at the given moment.
		 *
		 * <p>
		 * A {@link #isBackup() backup} code never runs out: an empty expiry <em>is</em> its
		 * lifetime, see {@link #KIND_BACKUP}. On every other record an empty expiry is a damaged
		 * file, and an {@link #getExpires() expiry} this server cannot parse is treated as expired
		 * either way: a code whose lifetime nobody can read is not a code that lives forever.
		 * </p>
		 */
		public boolean isExpired(Instant now) {
			if (_expires.isEmpty()) {
				if (isBackup()) {
					// Written down and kept; it dies by use or by withdrawal, see KIND_BACKUP.
					return false;
				}
				// A record from a damaged file: a code without a lifetime is no code.
				LOG.warning("The device code '" + _id + "' has no expiry; it is treated as expired.");
				return true;
			}
			try {
				return !Instant.parse(_expires).isAfter(now);
			} catch (DateTimeException ex) {
				LOG.warning("The device code '" + _id + "' has an unreadable expiry '" + _expires
					+ "'; it is treated as expired.");
				return true;
			}
		}

		/** Whether this code can no longer pair a device, for whatever reason. */
		public boolean isDead(Instant now) {
			return isUsed() || isRevoked() || isExpired(now);
		}

		/**
		 * Since when this code has been dead, <code>null</code> while it still works.
		 *
		 * <p>
		 * The moment it was typed, or the moment it ran out; an unreadable instant counts as "dead
		 * since forever", which is what drops such a record on the next write.
		 * </p>
		 */
		Instant deadSince(Instant now) {
			if (isUsed()) {
				try {
					return Instant.parse(_used);
				} catch (DateTimeException ex) {
					return Instant.MIN;
				}
			}
			if (isRevoked()) {
				try {
					return Instant.parse(_revoked);
				} catch (DateTimeException ex) {
					return Instant.MIN;
				}
			}
			if (!isExpired(now)) {
				return null;
			}
			try {
				return Instant.parse(_expires);
			} catch (DateTimeException ex) {
				return Instant.MIN;
			}
		}

		@Override
		public String toString() {
			return (isBackup() ? "backup-code:" : isInvitation() ? "invitation:" : "device-code:") + _id
				+ " (for " + (_user.isEmpty() ? "<unnamed>" : _user) + ", by device " + _issuedBy + ")"
				+ (isUsed() ? " used by device " + _usedBy : "") + (isRevoked() ? " (revoked)" : "");
		}
	}

	/** A newly issued {@link Code} together with the characters to type, answered exactly once. */
	public static final class Issued {

		private final Code _record;

		private final String _code;

		Issued(Code record, String code) {
			_record = record;
			_code = code;
		}

		/** The code that was recorded. */
		public Code getRecord() {
			return _record;
		}

		/** The characters to type, which this server never stores and never answers again. */
		public String getCode() {
			return _code;
		}
	}

	private final Path _file;

	private final Clock _clock;

	private final SecureRandom _random = new SecureRandom();

	private List<Code> _codes = new ArrayList<>();

	/** Whether the file on disk could not be read; it is set aside instead of overwritten. */
	private boolean _damaged;

	/**
	 * Creates a {@link DeviceCodeStore} for the album tree rooted at the given path.
	 *
	 * <p>
	 * The store is read immediately, so that a restarted server still honours a code somebody is
	 * carrying to the next room. A library nobody ever added a device to has no file and an empty
	 * store.
	 * </p>
	 */
	public DeviceCodeStore(Path basePath) {
		this(basePath, Clock.systemUTC());
	}

	/**
	 * Creates a {@link DeviceCodeStore} reading the time from the given clock.
	 *
	 * @param clock
	 *        What "now" means to this store; the way a test moves eleven minutes forward without
	 *        waiting eleven minutes.
	 */
	public DeviceCodeStore(Path basePath, Clock clock) {
		_file = basePath.resolve(UserStore.DIRECTORY_NAME).resolve(FILE_NAME);
		_clock = clock;
		load();
	}

	/** The file this store is persisted in. */
	public Path getFile() {
		return _file;
	}

	/** What "now" means to this store. */
	public Clock getClock() {
		return _clock;
	}

	/** Every code this store still holds, in the order they were issued. */
	public synchronized List<Code> getCodes() {
		return Collections.unmodifiableList(new ArrayList<>(_codes));
	}

	/**
	 * The code of the given id.
	 *
	 * @return <code>null</code> if there is none.
	 */
	public synchronized Code get(String id) {
		for (Code code : _codes) {
			if (code.getId().equals(id)) {
				return code;
			}
		}
		return null;
	}

	/**
	 * The code the given characters name, whether it still works or not.
	 *
	 * <p>
	 * A dead code is answered too, and deliberately: the caller is told that it expired or was used
	 * already, which is a different thing from a code nobody ever issued. The characters are
	 * {@link #normalise(String) normalised} first, so that a person may type the dash or leave it
	 * out and may shout in lower case.
	 * </p>
	 *
	 * @return <code>null</code> if the characters are no code of this server.
	 */
	public synchronized Code lookup(String code) {
		if (code == null || code.isEmpty()) {
			return null;
		}
		String normalised = normalise(code);
		// Two spellings, one store (issue #89). A typed code is hashed in its normalised form, so
		// that the dash and the shouting do not matter; the token of an invitation is hashed
		// exactly as it stands, because it is clicked and not typed and normalising it would
		// throw away its case and its dashes. Neither can be mistaken for the other: what is
		// stored is a hash, and only the spelling it was created from produces it.
		byte[] typed = normalised.isEmpty() ? null
			: UserStore.hash(normalised).getBytes(StandardCharsets.US_ASCII);
		byte[] token = UserStore.hash(code).getBytes(StandardCharsets.US_ASCII);
		for (Code candidate : _codes) {
			byte[] stored = candidate.getCodeHash().getBytes(StandardCharsets.US_ASCII);
			// Constant-time comparison: the hash of a guessed code must not be probed by timing.
			if (candidate.isInvitation()) {
				if (MessageDigest.isEqual(token, stored)) {
					return candidate;
				}
			} else if (typed != null && MessageDigest.isEqual(typed, stored)) {
				return candidate;
			}
		}
		return null;
	}

	/**
	 * Issues a new code for the given user and persists its record.
	 *
	 * <p>
	 * The characters are returned to the caller exactly once and never stored. A user may hold
	 * several live codes at a time — asking twice gives two codes, and each of them works once.
	 * </p>
	 *
	 * @param user
	 *        The name of the user a device typing the code is signed in as.
	 * @param issuedBy
	 *        The id of the device that asked.
	 */
	public synchronized Issued create(String user, String issuedBy) throws IOException {
		return create(user, issuedBy, null);
	}

	/**
	 * Issues a code for the given user, spelled as the caller says, see {@link #create(String, String)}.
	 *
	 * @param fixed
	 *        The characters the code has to be, <code>null</code> for a random one. What
	 *        <code>--admin-code</code> fixes (issue #89); it must have passed
	 *        {@link #checkCode(String)}.
	 */
	public synchronized Issued create(String user, String issuedBy, String fixed) throws IOException {
		Instant now = _clock.instant();
		String code = fixed == null ? randomCode(CODE_LENGTH) : checkCode(fixed);
		Code record = new Code(freeId(), UserStore.hash(code), user, issuedBy == null ? "" : issuedBy,
			now.toString(), now.plus(Duration.ofMinutes(LIFETIME_MINUTES)).toString(), "", "", "", KIND_DEVICE);
		_codes.add(record);
		store();
		return new Issued(record, code);
	}

	/**
	 * Issues the given user's backup code, withdrawing the one they had, see issue #92.
	 *
	 * <p>
	 * The way back from signing out of one's last device, and the only code this store makes that
	 * outlives the moment: {@value #BACKUP_CODE_LENGTH} characters, no expiry, and exempt from the
	 * rule that a code dies with the device that issued it &mdash; the device that made it is
	 * precisely the one that will be gone when it is needed. Single use like every other code, and
	 * one per user at a time: making a new one withdraws the old one in the same step, so that a
	 * person never has two pieces of paper of which only one works.
	 * </p>
	 *
	 * <p>
	 * The issuing device is recorded all the same, because it is history worth keeping &mdash; it
	 * simply grants nothing, see {@link Code#isBackup()}.
	 * </p>
	 */
	public synchronized Issued createBackup(String user, String issuedBy) throws IOException {
		Instant now = _clock.instant();
		markBackupRevoked(user, now);
		String code = randomCode(BACKUP_CODE_LENGTH);
		Code record = new Code(freeId(), UserStore.hash(code), user, issuedBy == null ? "" : issuedBy,
			now.toString(), "", "", "", "", KIND_BACKUP);
		_codes.add(record);
		store();
		return new Issued(record, code);
	}

	/**
	 * Issues the code of an invitation, see {@link #KIND_INVITATION} (issue #89).
	 *
	 * <p>
	 * The same single-use record as every other code, told apart by its kind: it is spelled as a
	 * token because it travels in a link, it lives until the instant the inviter chose rather than
	 * ten minutes, and it outlives the device that made it. What it adds a device to is the
	 * pending user the invitation created, who is found by this code's {@link Code#getId() id}
	 * and not by a name they do not have yet, see {@link UserStore#getInvited(String)}.
	 * </p>
	 *
	 * @param issuedBy
	 *        The id of the inviter's device; history, since an invitation does not die with it.
	 * @param expires
	 *        When the invitation runs out, an ISO-8601 instant; empty for
	 *        {@value #INVITATION_DEFAULT_DAYS} days from now.
	 */
	public synchronized Issued createInvitation(String issuedBy, String expires) throws IOException {
		Instant now = _clock.instant();
		byte[] bytes = new byte[INVITATION_TOKEN_BYTES];
		_random.nextBytes(bytes);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		String lifetime = expires == null || expires.isEmpty()
			? now.plus(Duration.ofDays(INVITATION_DEFAULT_DAYS)).toString() : expires;
		Code record = new Code(freeId(), UserStore.hash(token), "", issuedBy == null ? "" : issuedBy,
			now.toString(), lifetime, "", "", "", KIND_INVITATION);
		_codes.add(record);
		store();
		return new Issued(record, token);
	}

	/**
	 * Adopts a code whose hash is already known, see issue #89.
	 *
	 * <p>
	 * What carries an invitation issued by a build that kept its own store into this one: the
	 * token is not here to be hashed again, but its hash is, and a store that looks up by hash
	 * needs nothing else. A link somebody was sent last week therefore keeps working.
	 * </p>
	 */
	public synchronized Code adopt(String codeHash, String issuedBy, String created, String expires, String kind)
			throws IOException {
		Code record = new Code(freeId(), codeHash, "", issuedBy == null ? "" : issuedBy,
			created == null ? _clock.instant().toString() : created, expires == null ? "" : expires,
			"", "", "", kind);
		_codes.add(record);
		store();
		return record;
	}

	/**
	 * Withdraws the code of the given id, see issue #89.
	 *
	 * <p>
	 * What withdrawing an invitation does to its code. The record stays and is marked, so that
	 * whoever opens the link is told that it was withdrawn rather than that it never existed.
	 * </p>
	 *
	 * @return The code, <code>null</code> if there is none of that id.
	 */
	public synchronized Code revoke(String id) throws IOException {
		Code code = get(id);
		if (code == null) {
			return null;
		}
		if (!code.isRevoked() && !code.isUsed()) {
			code.setRevoked(_clock.instant().toString());
			store();
		}
		return code;
	}

	/**
	 * The given user's backup code while it still works.
	 *
	 * @return <code>null</code> if they have none; the code itself is not in it, only its record.
	 */
	public synchronized Code backupCodeOf(String user) {
		if (user == null) {
			return null;
		}
		Instant now = _clock.instant();
		for (Code code : _codes) {
			if (code.isBackup() && user.equals(code.getUser()) && !code.isDead(now)) {
				return code;
			}
		}
		return null;
	}

	/**
	 * Withdraws the given user's backup code, see issue #92.
	 *
	 * @return How many were withdrawn; zero, or one, unless a damaged file held more.
	 */
	public synchronized int revokeBackup(String user) throws IOException {
		int revoked = markBackupRevoked(user, _clock.instant());
		if (revoked > 0) {
			store();
		}
		return revoked;
	}

	/** Marks the given user's live backup codes withdrawn, without writing the store. */
	private int markBackupRevoked(String user, Instant now) {
		if (user == null) {
			return 0;
		}
		String stamp = now.toString();
		int revoked = 0;
		for (Code code : _codes) {
			if (code.isBackup() && user.equals(code.getUser()) && !code.isDead(now)) {
				code.setRevoked(stamp);
				revoked++;
			}
		}
		return revoked;
	}

	/**
	 * Marks the code of the given id as typed by the device of the given id.
	 *
	 * <p>
	 * The record stays and the code is never honoured again: a single-use code is used up by this
	 * call, which is why it is made before the answer is written.
	 * </p>
	 *
	 * @return The code, <code>null</code> if there is none of that id.
	 */
	public synchronized Code markUsed(String id, String deviceId) throws IOException {
		Code code = get(id);
		if (code == null) {
			return null;
		}
		if (!code.isUsed()) {
			code.setUsed(_clock.instant().toString(), deviceId == null ? "" : deviceId);
			store();
		}
		return code;
	}

	/**
	 * Withdraws every code the device of the given id issued and nobody has typed yet.
	 *
	 * <p>
	 * What signing a device out does to what that device handed out, see {@link DeviceCodeStore}:
	 * the whole safety argument of a device code is that the device it created is visible and can
	 * be signed out, and that is only worth anything if the codes it made go with it. The records
	 * stay and are marked, so that whoever types such a code is told that its device is gone rather
	 * than that the code never existed.
	 * </p>
	 *
	 * <p>
	 * A used code is left alone: it is spent, its record is the history of a device that exists,
	 * and withdrawing it afterwards would say something that never happened.
	 * </p>
	 *
	 * @return How many codes were withdrawn; the store is written only if any were.
	 */
	public synchronized int revokeIssuedBy(String deviceId) throws IOException {
		if (deviceId == null || deviceId.isEmpty()) {
			return 0;
		}
		String now = _clock.instant().toString();
		int revoked = 0;
		for (Code code : _codes) {
			if (code.isInvitation()) {
				// An invitation outlives the device that sent it (issue #89): an inviter who signs
				// a laptop out has not taken back what they put in the post. It is withdrawn by
				// ?action=uninvite, by running out, or by its inviter's user being removed.
				continue;
			}
			if (code.isBackup()) {
				// A backup code outlives the device that made it; that is its whole purpose, and
				// the device list argument does not apply to a code its own owner wrote down
				// (issue #92). It is withdrawn by making a new one, or explicitly.
				continue;
			}
			if (deviceId.equals(code.getIssuedBy()) && !code.isUsed() && !code.isRevoked()) {
				code.setRevoked(now);
				revoked++;
			}
		}
		if (revoked > 0) {
			store();
		}
		return revoked;
	}

	/**
	 * The characters of a code as they are shown: groups of {@value #GROUP_LENGTH}, dash-separated.
	 *
	 * <p>
	 * <code>XXXX-XXXX</code> for a device code and <code>XXXX-XXXX-XXXX-XXXX</code> for a backup
	 * code (issue #92) — one rule, so the two are read and copied the same way. The dash is
	 * decoration and nothing else: {@link #normalise(String)} throws it away again, so a person
	 * may type it or not.
	 * </p>
	 */
	public static String format(String code) {
		if (code == null) {
			return "";
		}
		StringBuilder result = new StringBuilder(code.length() + code.length() / GROUP_LENGTH);
		for (int n = 0, cnt = code.length(); n < cnt; n++) {
			if (n > 0 && n % GROUP_LENGTH == 0) {
				result.append('-');
			}
			result.append(code.charAt(n));
		}
		return result.toString();
	}

	/**
	 * The comparable form of what somebody typed: upper case, without dashes and whitespace.
	 *
	 * <p>
	 * The one place the spelling rule lives; the stored hash is the hash of this form, so
	 * <code>abcd-2345</code>, <code>ABCD 2345</code> and <code>ABCD2345</code> are the same code.
	 * </p>
	 */
	public static String normalise(String code) {
		if (code == null) {
			return "";
		}
		StringBuilder result = new StringBuilder(code.length());
		for (int n = 0, cnt = code.length(); n < cnt; n++) {
			char c = code.charAt(n);
			if (c == '-' || Character.isWhitespace(c)) {
				continue;
			}
			result.append(Character.toUpperCase(c));
		}
		return result.toString();
	}

	/** A fresh code of the given number of characters from {@link #ALPHABET}. */
	private String randomCode(int length) {
		StringBuilder result = new StringBuilder(length);
		for (int n = 0; n < length; n++) {
			result.append(ALPHABET.charAt(_random.nextInt(ALPHABET.length())));
		}
		return result.toString();
	}

	/** An id no code of this store has. */
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

	/**
	 * Drops what has been dead longer than {@value #KEEP_DEAD_HOURS} hours.
	 *
	 * <p>
	 * Long enough that "this code was already used" and "this code has expired" can still be told
	 * apart for a day, short enough that the file never grows: a device code is a moment, not a
	 * record anybody keeps.
	 * </p>
	 */
	private void forgetTheLongDead() {
		Instant now = _clock.instant();
		Instant limit = now.minus(Duration.ofHours(KEEP_DEAD_HOURS));
		Instant invitationLimit = now.minus(Duration.ofDays(KEEP_DEAD_INVITATION_DAYS));
		for (Iterator<Code> it = _codes.iterator(); it.hasNext();) {
			Code code = it.next();
			Instant dead = code.deadSince(now);
			if (dead != null && dead.isBefore(code.isInvitation() ? invitationLimit : limit)) {
				it.remove();
			}
		}
	}

	private void load() {
		if (!_file.toFile().exists()) {
			return;
		}
		try (Reader reader = new InputStreamReader(Files.newInputStream(_file), StandardCharsets.UTF_8)) {
			_codes = readCodes(new JsonReader(new ReaderAdapter(reader)));
		} catch (IOException | RuntimeException ex) {
			// A broken store must not lock the server up; it honours no code instead.
			LOG.log(Level.WARNING, "Cannot read the device code store '" + _file + "': " + ex.getMessage());
			_codes = new ArrayList<>();
			_damaged = true;
		}
	}

	private static List<Code> readCodes(JsonReader in) throws IOException {
		List<Code> result = new ArrayList<>();
		int version = 0;
		in.beginObject();
		while (in.hasNext()) {
			String key = in.nextName();
			switch (key) {
				case VERSION__PROP:
					version = in.nextInt();
					break;
				case CODES__PROP:
					in.beginArray();
					while (in.hasNext()) {
						result.add(readCode(in));
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
			LOG.warning("The device code store was written by a newer version (" + version + " > " + VERSION
				+ "); unknown entries are kept as read.");
		}
		return result;
	}

	private static Code readCode(JsonReader in) throws IOException {
		String id = "";
		String codeHash = "";
		String user = "";
		String issuedBy = "";
		String created = "";
		String expires = "";
		String used = "";
		String usedBy = "";
		String revoked = "";
		String kind = KIND_DEVICE;
		in.beginObject();
		while (in.hasNext()) {
			String key = in.nextName();
			switch (key) {
				case ID__PROP:
					id = in.nextString();
					break;
				case CODE_HASH__PROP:
					codeHash = in.nextString();
					break;
				case USER__PROP:
					user = in.nextString();
					break;
				case ISSUED_BY__PROP:
					issuedBy = in.nextString();
					break;
				case CREATED__PROP:
					created = in.nextString();
					break;
				case EXPIRES__PROP:
					expires = in.nextString();
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
				case KIND__PROP:
					kind = in.nextString();
					break;
				default:
					in.skipValue();
					break;
			}
		}
		in.endObject();
		return new Code(id, codeHash, user, issuedBy, created, expires, used, usedBy, revoked, kind);
	}

	/** Writes this store to disk, atomically: a crash never leaves a half-written store. */
	public synchronized void store() throws IOException {
		forgetTheLongDead();

		Path directory = _file.getParent();
		Files.createDirectories(directory);

		if (_damaged && Files.exists(_file)) {
			// The unreadable file may still hold a code somebody is carrying; keep it for repair.
			Path broken = directory.resolve(FILE_NAME + ".broken-" + Instant.now().toString().replace(':', '-'));
			Files.move(_file, broken, StandardCopyOption.REPLACE_EXISTING);
			LOG.warning("Kept the unreadable device code store as '" + broken + "'.");
		}
		_damaged = false;

		Path tmpFile = Files.createTempFile(directory, "device-codes", ".json");
		try (Writer writer = new OutputStreamWriter(Files.newOutputStream(tmpFile), StandardCharsets.UTF_8)) {
			try (JsonWriter out = new JsonWriter(new WriterAdapter(writer))) {
				out.beginObject();
				out.name(VERSION__PROP);
				out.value(VERSION);
				out.name(CODES__PROP);
				out.beginArray();
				for (Code code : _codes) {
					writeCode(out, code);
				}
				out.endArray();
				out.endObject();
			}
		}
		Files.move(tmpFile, _file, StandardCopyOption.REPLACE_EXISTING);
	}

	private static void writeCode(JsonWriter out, Code code) throws IOException {
		out.beginObject();
		out.name(ID__PROP);
		out.value(code.getId());
		out.name(CODE_HASH__PROP);
		out.value(code.getCodeHash());
		out.name(USER__PROP);
		out.value(code.getUser());
		out.name(ISSUED_BY__PROP);
		out.value(code.getIssuedBy());
		out.name(CREATED__PROP);
		out.value(code.getCreated());
		out.name(EXPIRES__PROP);
		out.value(code.getExpires());
		out.name(USED__PROP);
		out.value(code.getUsed());
		out.name(USED_BY__PROP);
		out.value(code.getUsedBy());
		out.name(REVOKED__PROP);
		out.value(code.getRevoked());
		if (!KIND_DEVICE.equals(code.getKind())) {
			// Written only where there is something to say, so that the file of a library that
			// never made a backup code looks exactly as it did before issue #92.
			out.name(KIND__PROP);
			out.value(code.getKind());
		}
		out.endObject();
	}
}
