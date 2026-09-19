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

		/** Creates a {@link Code}. */
		public Code(String id, String codeHash, String user, String issuedBy, String created, String expires,
				String used, String usedBy, String revoked) {
			_id = id;
			_codeHash = codeHash;
			_user = user;
			_issuedBy = issuedBy;
			_created = created;
			_expires = expires;
			_used = used;
			_usedBy = usedBy;
			_revoked = revoked;
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

		/** When the code stops working, an ISO-8601 instant; never empty. */
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
		 * An {@link #getExpires() expiry} this server cannot parse is treated as expired: a code
		 * whose lifetime nobody can read is not a code that lives forever.
		 * </p>
		 */
		public boolean isExpired(Instant now) {
			if (_expires.isEmpty()) {
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
			return "device-code:" + _id + " (for " + _user + ", by device " + _issuedBy + ")"
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
		String normalised = normalise(code);
		if (normalised.isEmpty()) {
			return null;
		}
		byte[] hash = UserStore.hash(normalised).getBytes(StandardCharsets.US_ASCII);
		for (Code candidate : _codes) {
			// Constant-time comparison: the hash of a guessed code must not be probed by timing.
			if (MessageDigest.isEqual(hash, candidate.getCodeHash().getBytes(StandardCharsets.US_ASCII))) {
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
		String code = fixed == null ? randomCode() : checkCode(fixed);
		Code record = new Code(freeId(), UserStore.hash(code), user, issuedBy == null ? "" : issuedBy,
			now.toString(), now.plus(Duration.ofMinutes(LIFETIME_MINUTES)).toString(), "", "", "");
		_codes.add(record);
		store();
		return new Issued(record, code);
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
	 * The characters of a code as they are shown: <code>XXXX-XXXX</code>.
	 *
	 * <p>
	 * The dash is decoration and nothing else — {@link #normalise(String)} throws it away again, so
	 * a person may type it or not.
	 * </p>
	 */
	public static String format(String code) {
		if (code == null || code.length() <= GROUP_LENGTH) {
			return code == null ? "" : code;
		}
		return code.substring(0, GROUP_LENGTH) + "-" + code.substring(GROUP_LENGTH);
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

	/** A fresh code of {@value #CODE_LENGTH} characters from {@link #ALPHABET}. */
	private String randomCode() {
		StringBuilder result = new StringBuilder(CODE_LENGTH);
		for (int n = 0; n < CODE_LENGTH; n++) {
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
		for (Iterator<Code> it = _codes.iterator(); it.hasNext();) {
			Instant dead = it.next().deadSince(now);
			if (dead != null && dead.isBefore(limit)) {
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
				default:
					in.skipValue();
					break;
			}
		}
		in.endObject();
		return new Code(id, codeHash, user, issuedBy, created, expires, used, usedBy, revoked);
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
		out.endObject();
	}
}
