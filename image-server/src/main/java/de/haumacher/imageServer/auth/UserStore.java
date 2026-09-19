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
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The users of this server with their devices, persisted beside the album tree (issue #45).
 *
 * <p>
 * The store lives in {@link #DIRECTORY_NAME} at the root of the served folder, never inside a
 * user's album folder. It holds a <em>hash</em> of every issued token, never the token itself: a
 * stolen store cannot be replayed against the server. It is the one place that knows users, roles,
 * spaces and devices.
 * </p>
 *
 * <p>
 * The file format is persisted data and therefore versioned:
 * </p>
 *
 * <pre>
 * {"version":1,"users":[{"name":"haui","role":"admin","space":"haui","created":"2026-09-06T10:11:12Z",
 *   "devices":[{"id":"&lt;8 chars&gt;","name":"Phone","tokenHash":"&lt;64 hex chars&gt;",
 *   "created":"2026-09-06T10:11:12Z"}]}]}
 * </pre>
 *
 * <p>
 * The device {@link Device#getId() id} arrived with issue #55, and it did so without a version
 * step: a device of a store written before it simply has none, and gets a free one on the first
 * {@link #load()}, which writes the store back once (the same move the {@link #LEGACY_FILE_NAME}
 * takeover makes). The file stays readable by the build before, which skips the unknown
 * <code>id</code> like every other entry it does not know — so a library can be moved back to it
 * and every token still works.
 * </p>
 *
 * <p>
 * It replaces the device-only store of issue #28 ({@link #LEGACY_FILE_NAME}). A store from before
 * issue #45 is migrated on the first read: every legacy device becomes a device of a freshly
 * created, still unnamed {@link Roles#ADMIN}, and the old file is kept as
 * {@link #LEGACY_FILE_NAME}{@link #MIGRATED_SUFFIX}. Every token issued before keeps working.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class UserStore {

	private static final Logger LOG = Logger.getLogger(UserStore.class.getName());

	/** The directory below the served folder holding this server's own state. */
	public static final String DIRECTORY_NAME = ".valbum";

	/** The name of the store within {@link #DIRECTORY_NAME}. */
	public static final String FILE_NAME = "users.json";

	/** The name of the device-only store of issue #28, read once and then set aside. */
	public static final String LEGACY_FILE_NAME = "devices.json";

	/** The suffix the {@link #LEGACY_FILE_NAME} is kept under after it was read. */
	public static final String MIGRATED_SUFFIX = ".migrated";

	/** The staging directory of uploads; it belongs to the server, not to a user's space. */
	public static final String UPLOAD_DIRECTORY_NAME = ".upload";

	/**
	 * The directory within {@link #DIRECTORY_NAME} holding the roots of the guests, see issue #52.
	 *
	 * <p>
	 * A guest has no library of their own, so their root is not a folder of the album tree: it is
	 * <code>{@value #DIRECTORY_NAME}/{@value #GUESTS_DIRECTORY_NAME}/&lt;name&gt;</code>, a little
	 * space root that holds nothing but the link entries of issue #50 and the share registry below
	 * its own <code>{@value #DIRECTORY_NAME}</code>. It never holds a photo — album creation,
	 * upload and a move into it are refused by role — and it lies below a dotted folder, so no
	 * listing of anybody's ever shows it.
	 * </p>
	 *
	 * <p>
	 * It is shaped exactly like a member's space root, which is what makes the promotion of issue
	 * #52 a single rename: the folder <em>becomes</em> the new member's space.
	 * </p>
	 */
	public static final String GUESTS_DIRECTORY_NAME = "guests";

	/**
	 * The root of the given guest, relative to the base folder, <code>/</code> as separator.
	 *
	 * @see #GUESTS_DIRECTORY_NAME
	 */
	public static String guestSpace(String name) {
		return DIRECTORY_NAME + "/" + GUESTS_DIRECTORY_NAME + "/" + name;
	}

	/** The version this build writes, see {@link UserStore}. */
	public static final int VERSION = 1;

	/** Why a name is not usable as the name of a user, see {@link #checkUserName(String)}. */
	public static final String NAME_REFUSED =
		"A user name is also the name of the user's folder: it must not be empty, must not contain "
			+ "'/' or '\\', must not be '.' or '..', must not start with a dot or a '~' and must not "
			+ "contain control characters.";

	/** The number of random bytes a token is built from. */
	private static final int TOKEN_BYTES = 32;

	private static final String VERSION__PROP = "version";

	private static final String USERS__PROP = "users";

	private static final String DEVICES__PROP = "devices";

	private static final String NAME__PROP = "name";

	private static final String ROLE__PROP = "role";

	private static final String SPACE__PROP = "space";

	private static final String TOKEN_HASH__PROP = "tokenHash";

	private static final String CREATED__PROP = "created";

	private static final String CLEARANCE__PROP = "clearance";

	private static final String SHARE__PROP = "mayShare";

	private static final String ID__PROP = "id";

	/** The number of random bytes a device id is built from; it is a name, not a secret. */
	private static final int ID_BYTES = 6;

	/** A device a user signed in on. */
	public static final class Device {

		private String _id;

		private final String _name;

		private final String _tokenHash;

		private final String _created;

		/**
		 * Creates a {@link Device} without an id yet, see {@link #getId()}.
		 *
		 * <p>
		 * The store gives it a free id before it writes it, so that a device built here and added
		 * to a user is named like every other one.
		 * </p>
		 */
		public Device(String name, String tokenHash, String created) {
			this("", name, tokenHash, created);
		}

		/** Creates a {@link Device} with the given id. */
		public Device(String id, String name, String tokenHash, String created) {
			_id = id;
			_name = name;
			_tokenHash = tokenHash;
			_created = created;
		}

		/**
		 * The short opaque id naming this device, see issue #55.
		 *
		 * <p>
		 * Device names repeat — two phones called "Phone" are two devices — so a device that can be
		 * listed and signed out needs a name of its own that nothing else carries. It is assigned
		 * when the device is paired, it is a name and not a secret, and it is the empty string only
		 * for a device that was read from a store written before issue #55 and not yet written back,
		 * see {@link UserStore#ensureIds()}.
		 * </p>
		 */
		public String getId() {
			return _id;
		}

		/** See {@link #getId()}. */
		void setId(String id) {
			_id = id;
		}

		/** The name the device announced itself with. */
		public String getName() {
			return _name;
		}

		/** The SHA-256 hash of the device's token, in lower-case hex. */
		public String getTokenHash() {
			return _tokenHash;
		}

		/** When the device was signed in, an ISO-8601 instant. */
		public String getCreated() {
			return _created;
		}
	}

	/** A person using this server, the principal of every authenticated request. */
	public static final class User {

		private String _name;

		private String _role;

		private String _space;

		private final String _created;

		private String _clearance;

		private boolean _share;

		private final List<Device> _devices = new ArrayList<>();

		/** Creates a {@link User} with what their role implies, see {@link Clearances#ofRole(String)}. */
		public User(String name, String role, String space, String created) {
			this(name, role, space, created, Clearances.ofRole(role), Clearances.mayShareByRole(role));
		}

		/** Creates a {@link User}. */
		public User(String name, String role, String space, String created, String clearance, boolean share) {
			_name = name;
			_role = role;
			_space = space;
			_created = created;
			_clearance = clearance;
			_share = share;
		}

		/**
		 * Which privacy levels this user may see, one of {@link Clearances} (issue #82).
		 *
		 * <p>
		 * Stored here and enforced by issue #83. A user written before the field existed reads
		 * with what their role implies, so nothing about them changes.
		 * </p>
		 */
		public String getClearance() {
			return _clearance;
		}

		/** See {@link #getClearance()}. */
		public void setClearance(String clearance) {
			_clearance = clearance;
		}

		/** Whether this user may create share links (issue #82). */
		public boolean isShare() {
			return _share;
		}

		/** See {@link #isShare()}. */
		public void setShare(boolean share) {
			_share = share;
		}

		/**
		 * The user's name, which is also the name of their {@link #getSpace() space} folder.
		 *
		 * <p>
		 * The library owner created by a migration has no name yet; it is chosen once, see
		 * {@link UserStore#nameOwner(String)}.
		 * </p>
		 */
		public String getName() {
			return _name;
		}

		/** See {@link #getName()}. */
		public void setName(String name) {
			_name = name;
		}

		/** One of {@link Roles}. */
		public String getRole() {
			return _role;
		}

		/**
		 * See {@link #getRole()}.
		 *
		 * <p>
		 * A role changes exactly once and in one direction: a guest is promoted to a member by the
		 * admin, see issue #52. There is no demotion — taking a space away from somebody who has
		 * filled it with photos is not a thing a server does behind a button.
		 * </p>
		 */
		public void setRole(String role) {
			_role = role;
		}

		/**
		 * The folder below the server's base folder this user's requests are resolved against.
		 *
		 * <p>
		 * The empty string is the base folder itself: that is a library that was never migrated,
		 * and it looks exactly as it did before issue #45.
		 * </p>
		 */
		public String getSpace() {
			return _space;
		}

		/** See {@link #getSpace()}. */
		public void setSpace(String space) {
			_space = space;
		}

		/** When the user was created, an ISO-8601 instant. */
		public String getCreated() {
			return _created;
		}

		/** The devices this user signed in on, in the order they were added. */
		public List<Device> getDevices() {
			return Collections.unmodifiableList(new ArrayList<>(_devices));
		}

		/** Adds a device to this user. */
		public void addDevice(Device device) {
			_devices.add(device);
		}

		/**
		 * The device of the given id among this user's own, see issue #55.
		 *
		 * @return <code>null</code> if this user has no device of that id; whether somebody else
		 *         has one is deliberately not something this answers.
		 */
		public Device getDevice(String id) {
			if (id == null || id.isEmpty()) {
				return null;
			}
			for (Device device : _devices) {
				if (id.equals(device.getId())) {
					return device;
				}
			}
			return null;
		}

		/**
		 * Removes the given device from this user.
		 *
		 * <p>
		 * The caller stores; the token the device holds is refused from the next request on, this
		 * being the only place it was ever known.
		 * </p>
		 */
		public boolean removeDevice(Device device) {
			return _devices.remove(device);
		}
	}

	/** A device together with the user it belongs to, see {@link UserStore#lookup(String)}. */
	public static final class Login {

		private final User _user;

		private final Device _device;

		/** Creates a {@link Login}. */
		public Login(User user, Device device) {
			_user = user;
			_device = device;
		}

		/** The signed-in user. */
		public User getUser() {
			return _user;
		}

		/** The device the token was issued to. */
		public Device getDevice() {
			return _device;
		}
	}

	private final Path _file;

	private final Path _legacyFile;

	private final SecureRandom _random = new SecureRandom();

	private List<User> _users = new ArrayList<>();

	/**
	 * Whether the file on disk could not be read, see {@link #load()}: it is then set aside instead
	 * of overwritten by the next {@link #store()}.
	 */
	private boolean _damaged;

	/**
	 * Creates a {@link UserStore} for the album tree rooted at the given path.
	 *
	 * <p>
	 * The store is read immediately, so that a restarted server keeps accepting the tokens it
	 * issued before. A device store from before issue #45 is migrated at that point and written
	 * back; nothing else is written until a device signs in.
	 * </p>
	 */
	public UserStore(Path basePath) {
		_file = basePath.resolve(DIRECTORY_NAME).resolve(FILE_NAME);
		_legacyFile = basePath.resolve(DIRECTORY_NAME).resolve(LEGACY_FILE_NAME);
		load();
	}

	/** The file this store is persisted in. */
	public Path getFile() {
		return _file;
	}

	/** The users known to this server, in the order they were created. */
	public synchronized List<User> getUsers() {
		return Collections.unmodifiableList(new ArrayList<>(_users));
	}

	/**
	 * The library owner, the single {@link Roles#ADMIN} user.
	 *
	 * @return <code>null</code> while nobody has signed in yet.
	 */
	public synchronized User getOwner() {
		for (User user : _users) {
			if (Roles.ADMIN.equals(user.getRole())) {
				return user;
			}
		}
		return null;
	}

	/**
	 * The user with the given name.
	 *
	 * @return <code>null</code> if no such user exists.
	 */
	public synchronized User getUser(String name) {
		for (User user : _users) {
			if (user.getName().equals(name)) {
				return user;
			}
		}
		return null;
	}

	/**
	 * The user and device the given token was issued to.
	 *
	 * @return <code>null</code> if the token is not one this server issued.
	 */
	public synchronized Login lookup(String token) {
		if (token == null || token.isEmpty()) {
			return null;
		}
		byte[] hash = hash(token).getBytes(StandardCharsets.US_ASCII);
		for (User user : _users) {
			for (Device device : user._devices) {
				// Constant-time comparison: the hash of a guessed token must not be probed by timing.
				if (MessageDigest.isEqual(hash, device.getTokenHash().getBytes(StandardCharsets.US_ASCII))) {
					return new Login(user, device);
				}
			}
		}
		return null;
	}

	/**
	 * Creates the library owner, without a name and rooted at the base folder.
	 *
	 * <p>
	 * The caller stores; this is the state a library that was never migrated is in.
	 * </p>
	 */
	public synchronized User createOwner() {
		User owner = new User("", Roles.ADMIN, "", Instant.now().toString());
		_users.add(owner);
		return owner;
	}

	/**
	 * Adds a user built by the caller.
	 *
	 * <p>
	 * Nothing in this package calls this with a role other than {@link Roles#ADMIN}: a
	 * {@link Roles#MEMBER} arrives with the invitation flow of issue #52. It exists so that the
	 * space mechanism can be built and tested for a member from the start.
	 * </p>
	 *
	 * <p>
	 * The caller stores.
	 * </p>
	 */
	public synchronized User addUser(User user) {
		_users.add(user);
		return user;
	}

	/**
	 * Removes the given user with every device they signed in on, see issue #83.
	 *
	 * <p>
	 * The caller stores. Their tokens stop working with the next load, which is what removing a
	 * user has to mean; nothing of theirs is taken out of the album tree.
	 * </p>
	 */
	public synchronized boolean removeUser(User user) {
		return _users.remove(user);
	}

	/**
	 * Names the library owner, creating it if it does not exist yet.
	 *
	 * <p>
	 * The name is chosen exactly once: afterwards the same name is accepted again and a different
	 * one is refused. The caller stores.
	 * </p>
	 *
	 * @param name
	 *        The name to give the owner; the empty string means "the owner, whatever its name".
	 * @return The owner.
	 * @throws IllegalArgumentException
	 *         If the name is not a valid folder name, or names somebody else.
	 */
	public synchronized User nameOwner(String name) {
		User owner = getOwner();
		if (owner == null) {
			owner = createOwner();
		}
		String requested = name == null ? "" : name.trim();
		if (requested.isEmpty()) {
			return owner;
		}
		if (owner.getName().isEmpty()) {
			owner.setName(checkUserName(requested));
			return owner;
		}
		if (!owner.getName().equals(requested)) {
			throw new IllegalArgumentException(ownerMismatch(owner.getName()));
		}
		return owner;
	}

	/** The message a sign-in under a name other than the owner's is refused with. */
	public static String ownerMismatch(String ownerName) {
		return "The pairing secret signs in the library owner '" + ownerName
			+ "'. Sign in under that name, or ask the owner for an invitation.";
	}

	/**
	 * Issues a new token for a device of the given user and persists its hash.
	 *
	 * @return The token, which is returned to the caller exactly once and never stored.
	 */
	public synchronized String addDevice(User user, String deviceName) throws IOException {
		byte[] bytes = new byte[TOKEN_BYTES];
		_random.nextBytes(bytes);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

		String name = deviceName == null || deviceName.trim().isEmpty() ? "Unnamed device" : deviceName.trim();
		user.addDevice(new Device(freeId(), name, hash(token), Instant.now().toString()));
		store();
		return token;
	}

	/**
	 * Signs the given device of the given user out, see issue #55.
	 *
	 * <p>
	 * The record is removed, not marked: a device is a token, and the way to stop honouring a token
	 * is to forget its hash. The store is written before this returns, so the very next request
	 * carrying that token is refused.
	 * </p>
	 *
	 * @return Whether the user had that device; the store is written only if they had.
	 */
	public synchronized boolean removeDevice(User user, Device device) throws IOException {
		if (!user.removeDevice(device)) {
			return false;
		}
		store();
		return true;
	}

	/**
	 * Gives every device that has no {@link Device#getId() id} yet a free one.
	 *
	 * @return Whether anything was assigned, and the store therefore has to be written.
	 */
	private boolean ensureIds() {
		boolean changed = false;
		for (User user : _users) {
			for (Device device : user._devices) {
				if (device.getId().isEmpty()) {
					device.setId(freeId());
					changed = true;
				}
			}
		}
		return changed;
	}

	/** An id no device of this store carries. */
	private String freeId() {
		while (true) {
			byte[] bytes = new byte[ID_BYTES];
			_random.nextBytes(bytes);
			String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
			if (!taken(id)) {
				return id;
			}
		}
	}

	/**
	 * The user holding the device of the given id, <code>null</code> if nobody does.
	 *
	 * <p>
	 * Ids are free across the whole store, so one id names at most one device. What a device code
	 * asks when it wants to know whether the device that issued it is still signed in — which may
	 * be an administrator's device rather than the code's own user's, see issue #89.
	 * </p>
	 */
	public synchronized User deviceOwner(String id) {
		if (id == null || id.isEmpty()) {
			return null;
		}
		for (User user : _users) {
			if (user.getDevice(id) != null) {
				return user;
			}
		}
		return null;
	}

	private boolean taken(String id) {
		for (User user : _users) {
			if (user.getDevice(id) != null) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Checks that the given string can be the name of a user, and therefore of a folder.
	 *
	 * <p>
	 * A user's name <em>is</em> the name of their space folder, so it must be a single valid path
	 * segment. This is the one place the rule lives; the library migration command uses it too.
	 * </p>
	 *
	 * @return The name with surrounding whitespace removed.
	 * @throws IllegalArgumentException
	 *         With {@link #NAME_REFUSED} if the name cannot be a folder name.
	 */
	public static String checkUserName(String name) {
		String trimmed = name == null ? "" : name.trim();
		// A leading '~' is the canonical form of another user's space (~owner/...), see issue #49;
		// a user of that name would make the two indistinguishable on the wire.
		if (trimmed.isEmpty() || trimmed.equals(".") || trimmed.equals("..") || trimmed.startsWith(".")
			|| trimmed.startsWith("~") || trimmed.indexOf('/') >= 0 || trimmed.indexOf('\\') >= 0) {
			throw new IllegalArgumentException(NAME_REFUSED);
		}
		for (int n = 0, cnt = trimmed.length(); n < cnt; n++) {
			if (Character.isISOControl(trimmed.charAt(n))) {
				throw new IllegalArgumentException(NAME_REFUSED);
			}
		}
		return trimmed;
	}

	/** The SHA-256 hash of the given token, in lower-case hex. */
	public static String hash(String token) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
			StringBuilder result = new StringBuilder(digest.length * 2);
			for (byte b : digest) {
				result.append(Character.forDigit((b >> 4) & 0xF, 16));
				result.append(Character.forDigit(b & 0xF, 16));
			}
			return result.toString();
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required by the platform.", ex);
		}
	}

	private void load() {
		if (_file.toFile().exists()) {
			try (Reader reader = new InputStreamReader(Files.newInputStream(_file), StandardCharsets.UTF_8)) {
				_users = readUsers(new JsonReader(new ReaderAdapter(reader)));
			} catch (IOException | RuntimeException ex) {
				// A broken store must not lock the server up; it refuses every token instead.
				LOG.log(Level.WARNING, "Cannot read the user store '" + _file + "': " + ex.getMessage());
				_users = new ArrayList<>();
				_damaged = true;
			}
			if (ensureIds()) {
				// A store written before issue #55; its devices are named once and kept that way.
				try {
					store();
					LOG.info("Gave the devices in '" + _file + "' an id of their own.");
				} catch (IOException ex) {
					// Not fatal: the ids are in memory and every token keeps working. The next
					// write of the store persists them.
					LOG.log(Level.WARNING, "Cannot write the user store '" + _file + "': " + ex.getMessage());
				}
			}
			return;
		}
		if (_legacyFile.toFile().exists()) {
			migrateDevices();
		}
	}

	/**
	 * Turns the device store of issue #28 into a user store with a single, still unnamed owner.
	 *
	 * <p>
	 * Every token issued before issue #45 keeps working: the devices are carried over unchanged,
	 * hashes and all. The old file is kept beside the new one instead of being deleted, so that a
	 * failed migration can be inspected.
	 * </p>
	 */
	private void migrateDevices() {
		List<Device> devices;
		try (Reader reader = new InputStreamReader(Files.newInputStream(_legacyFile), StandardCharsets.UTF_8)) {
			devices = readLegacyDevices(new JsonReader(new ReaderAdapter(reader)));
		} catch (IOException | RuntimeException ex) {
			LOG.log(Level.WARNING, "Cannot read the device store '" + _legacyFile + "': " + ex.getMessage()
				+ "; it is left in place and no user is created from it.");
			return;
		}

		User owner = createOwner();
		for (Device device : devices) {
			owner.addDevice(device);
		}
		try {
			store();
			Files.move(_legacyFile, _legacyFile.resolveSibling(LEGACY_FILE_NAME + MIGRATED_SUFFIX),
				StandardCopyOption.REPLACE_EXISTING);
			LOG.info("Migrated " + devices.size() + " paired device(s) to the library owner in '" + _file + "'.");
		} catch (IOException ex) {
			LOG.log(Level.WARNING, "Cannot write the user store '" + _file + "': " + ex.getMessage());
		}
	}

	private static List<Device> readLegacyDevices(JsonReader in) throws IOException {
		List<Device> result = new ArrayList<>();
		in.beginObject();
		while (in.hasNext()) {
			String key = in.nextName();
			if (DEVICES__PROP.equals(key)) {
				in.beginArray();
				while (in.hasNext()) {
					result.add(readDevice(in));
				}
				in.endArray();
			} else {
				in.skipValue();
			}
		}
		in.endObject();
		return result;
	}

	private static List<User> readUsers(JsonReader in) throws IOException {
		List<User> result = new ArrayList<>();
		int version = 0;
		in.beginObject();
		while (in.hasNext()) {
			String key = in.nextName();
			switch (key) {
				case VERSION__PROP:
					version = in.nextInt();
					break;
				case USERS__PROP:
					in.beginArray();
					while (in.hasNext()) {
						result.add(readUser(in));
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
			LOG.warning("The user store was written by a newer version (" + version + " > " + VERSION
				+ "); unknown entries are kept as read.");
		}
		return result;
	}

	private static User readUser(JsonReader in) throws IOException {
		String name = "";
		String role = "";
		String space = "";
		String created = "";
		String clearance = "";
		Boolean share = null;
		List<Device> devices = new ArrayList<>();
		in.beginObject();
		while (in.hasNext()) {
			String key = in.nextName();
			switch (key) {
				case NAME__PROP:
					name = in.nextString();
					break;
				case ROLE__PROP:
					role = in.nextString();
					break;
				case SPACE__PROP:
					space = in.nextString();
					break;
				case CREATED__PROP:
					created = in.nextString();
					break;
				case CLEARANCE__PROP:
					clearance = in.nextString();
					break;
				case SHARE__PROP:
					share = Boolean.valueOf(in.nextBoolean());
					break;
				case DEVICES__PROP:
					in.beginArray();
					while (in.hasNext()) {
						devices.add(readDevice(in));
					}
					in.endArray();
					break;
				default:
					in.skipValue();
					break;
			}
		}
		in.endObject();

		// A store written before issue #82 carries neither: the role says what such a user held.
		User result = new User(name, role, space, created,
			Clearances.isKnown(clearance) ? clearance : Clearances.ofRole(role),
			share == null ? Clearances.mayShareByRole(role) : share.booleanValue());
		for (Device device : devices) {
			result.addDevice(device);
		}
		return result;
	}

	private static Device readDevice(JsonReader in) throws IOException {
		String id = "";
		String name = "";
		String tokenHash = "";
		String created = "";
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
				case TOKEN_HASH__PROP:
					tokenHash = in.nextString();
					break;
				case CREATED__PROP:
					created = in.nextString();
					break;
				default:
					in.skipValue();
					break;
			}
		}
		in.endObject();
		return new Device(id, name, tokenHash, created);
	}

	/** Writes this store to disk, atomically: a crash never leaves a half-written store. */
	public synchronized void store() throws IOException {
		// A device built by a caller carries no id yet; nothing is ever written without one.
		ensureIds();

		Path directory = _file.getParent();
		Files.createDirectories(directory);

		if (_damaged && Files.exists(_file)) {
			// The unreadable file may still hold the users known so far; keep it for repair.
			Path broken = directory.resolve(FILE_NAME + ".broken-" + Instant.now().toString().replace(':', '-'));
			Files.move(_file, broken, StandardCopyOption.REPLACE_EXISTING);
			LOG.warning("Kept the unreadable user store as '" + broken + "'.");
		}
		_damaged = false;

		Path tmpFile = Files.createTempFile(directory, "users", ".json");
		try (Writer writer = new OutputStreamWriter(Files.newOutputStream(tmpFile), StandardCharsets.UTF_8)) {
			try (JsonWriter out = new JsonWriter(new WriterAdapter(writer))) {
				out.beginObject();
				out.name(VERSION__PROP);
				out.value(VERSION);
				out.name(USERS__PROP);
				out.beginArray();
				for (User user : _users) {
					writeUser(out, user);
				}
				out.endArray();
				out.endObject();
			}
		}
		Files.move(tmpFile, _file, StandardCopyOption.REPLACE_EXISTING);
	}

	private static void writeUser(JsonWriter out, User user) throws IOException {
		out.beginObject();
		out.name(NAME__PROP);
		out.value(user.getName());
		out.name(ROLE__PROP);
		out.value(user.getRole());
		out.name(SPACE__PROP);
		out.value(user.getSpace());
		out.name(CREATED__PROP);
		out.value(user.getCreated());
		out.name(CLEARANCE__PROP);
		out.value(user.getClearance());
		out.name(SHARE__PROP);
		out.value(user.isShare());
		out.name(DEVICES__PROP);
		out.beginArray();
		for (Device device : user.getDevices()) {
			out.beginObject();
			out.name(ID__PROP);
			out.value(device.getId());
			out.name(NAME__PROP);
			out.value(device.getName());
			out.name(TOKEN_HASH__PROP);
			out.value(device.getTokenHash());
			out.name(CREATED__PROP);
			out.value(device.getCreated());
			out.endObject();
		}
		out.endArray();
		out.endObject();
	}

	/** Whether the given file name belongs to this server rather than to a user's library. */
	public static boolean isServerEntry(String name) {
		return DIRECTORY_NAME.equals(name) || UPLOAD_DIRECTORY_NAME.equals(name);
	}
}
