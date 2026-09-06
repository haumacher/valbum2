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
 *   "devices":[{"name":"Phone","tokenHash":"&lt;64 hex chars&gt;","created":"2026-09-06T10:11:12Z"}]}]}
 * </pre>
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

	/** The version this build writes, see {@link UserStore}. */
	public static final int VERSION = 1;

	/** Why a name is not usable as the name of a user, see {@link #checkUserName(String)}. */
	public static final String NAME_REFUSED =
		"A user name is also the name of the user's folder: it must not be empty, must not contain "
			+ "'/' or '\\', must not be '.' or '..', must not start with a dot and must not contain "
			+ "control characters.";

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

	/** A device a user signed in on. */
	public static final class Device {

		private final String _name;

		private final String _tokenHash;

		private final String _created;

		/** Creates a {@link Device}. */
		public Device(String name, String tokenHash, String created) {
			_name = name;
			_tokenHash = tokenHash;
			_created = created;
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

		private final String _role;

		private String _space;

		private final String _created;

		private final List<Device> _devices = new ArrayList<>();

		/** Creates a {@link User}. */
		public User(String name, String role, String space, String created) {
			_name = name;
			_role = role;
			_space = space;
			_created = created;
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
		user.addDevice(new Device(name, hash(token), Instant.now().toString()));
		store();
		return token;
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
		if (trimmed.isEmpty() || trimmed.equals(".") || trimmed.equals("..") || trimmed.startsWith(".")
			|| trimmed.indexOf('/') >= 0 || trimmed.indexOf('\\') >= 0) {
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

		User result = new User(name, role, space, created);
		for (Device device : devices) {
			result.addDevice(device);
		}
		return result;
	}

	private static Device readDevice(JsonReader in) throws IOException {
		String name = "";
		String tokenHash = "";
		String created = "";
		in.beginObject();
		while (in.hasNext()) {
			String key = in.nextName();
			switch (key) {
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
		return new Device(name, tokenHash, created);
	}

	/** Writes this store to disk, atomically: a crash never leaves a half-written store. */
	public synchronized void store() throws IOException {
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
		out.name(DEVICES__PROP);
		out.beginArray();
		for (Device device : user.getDevices()) {
			out.beginObject();
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
