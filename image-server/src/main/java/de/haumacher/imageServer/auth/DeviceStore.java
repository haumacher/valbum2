/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.auth;

import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import java.io.File;
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
 * The devices paired with this server, persisted beside the album tree.
 *
 * <p>
 * The store lives in {@link #DIRECTORY_NAME} at the root of the served folder, never inside a
 * user's album folder, and it is the only file this server writes outside a folder's
 * <code>index.json</code>. It holds a <em>hash</em> of every issued token, never the token
 * itself: a stolen store cannot be replayed against the server.
 * </p>
 *
 * <p>
 * The file format is persisted data and therefore versioned:
 * </p>
 *
 * <pre>
 * {"version":1,"devices":[{"name":"Phone","tokenHash":"&lt;64 hex chars&gt;","created":"2026-09-06T10:11:12Z"}]}
 * </pre>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class DeviceStore {

	private static final Logger LOG = Logger.getLogger(DeviceStore.class.getName());

	/** The directory below the served folder holding this server's own state. */
	public static final String DIRECTORY_NAME = ".valbum";

	/** The name of the store within {@link #DIRECTORY_NAME}. */
	public static final String FILE_NAME = "devices.json";

	/** The version this build writes, see {@link DeviceStore}. */
	public static final int VERSION = 1;

	/** The number of random bytes a token is built from. */
	private static final int TOKEN_BYTES = 32;

	private static final String VERSION__PROP = "version";

	private static final String DEVICES__PROP = "devices";

	private static final String NAME__PROP = "name";

	private static final String TOKEN_HASH__PROP = "tokenHash";

	private static final String CREATED__PROP = "created";

	/** A device that was paired with this server. */
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

		/** When the device was paired, an ISO-8601 instant. */
		public String getCreated() {
			return _created;
		}
	}

	private final Path _file;

	private final SecureRandom _random = new SecureRandom();

	private List<Device> _devices = new ArrayList<>();

	/**
	 * Whether the file on disk could not be read, see {@link #load()}: it is then set aside
	 * instead of overwritten by the next {@link #store()}.
	 */
	private boolean _damaged;

	/**
	 * Creates a {@link DeviceStore} for the album tree rooted at the given path.
	 *
	 * <p>
	 * The store is read immediately, so that a restarted server keeps accepting the tokens it
	 * issued before. Nothing is written until a device is {@link #pair(String) paired}.
	 * </p>
	 */
	public DeviceStore(Path basePath) {
		_file = basePath.resolve(DIRECTORY_NAME).resolve(FILE_NAME);
		load();
	}

	/** The file this store is persisted in. */
	public Path getFile() {
		return _file;
	}

	/** The devices known to this server, in the order they were paired. */
	public synchronized List<Device> getDevices() {
		return Collections.unmodifiableList(new ArrayList<>(_devices));
	}

	/**
	 * The name of the device the given token was issued to.
	 *
	 * @return <code>null</code> if the token is not one this server issued.
	 */
	public synchronized String deviceName(String token) {
		if (token == null || token.isEmpty()) {
			return null;
		}
		String hash = hash(token);
		for (Device device : _devices) {
			// Constant-time comparison: the hash of a guessed token must not be probed by timing.
			if (MessageDigest.isEqual(hash.getBytes(StandardCharsets.US_ASCII),
					device.getTokenHash().getBytes(StandardCharsets.US_ASCII))) {
				return device.getName();
			}
		}
		return null;
	}

	/**
	 * Issues a new token for a device with the given name and persists its hash.
	 *
	 * @return The token, which is returned to the caller exactly once and never stored.
	 */
	public synchronized String pair(String deviceName) throws IOException {
		byte[] bytes = new byte[TOKEN_BYTES];
		_random.nextBytes(bytes);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

		String name = deviceName == null || deviceName.trim().isEmpty() ? "Unnamed device" : deviceName.trim();
		_devices.add(new Device(name, hash(token), Instant.now().toString()));
		store();
		return token;
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
		File file = _file.toFile();
		if (!file.exists()) {
			return;
		}
		try (Reader reader = new InputStreamReader(Files.newInputStream(_file), StandardCharsets.UTF_8)) {
			_devices = readDevices(new JsonReader(new ReaderAdapter(reader)));
		} catch (IOException | RuntimeException ex) {
			// A broken store must not lock the server up; it refuses every token instead.
			LOG.log(Level.WARNING, "Cannot read the device store '" + _file + "': " + ex.getMessage());
			_devices = new ArrayList<>();
			_damaged = true;
		}
	}

	private static List<Device> readDevices(JsonReader in) throws IOException {
		List<Device> result = new ArrayList<>();
		int version = 0;
		in.beginObject();
		while (in.hasNext()) {
			String key = in.nextName();
			switch (key) {
				case VERSION__PROP:
					version = in.nextInt();
					break;
				case DEVICES__PROP:
					in.beginArray();
					while (in.hasNext()) {
						result.add(readDevice(in));
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
			LOG.warning("The device store was written by a newer version (" + version + " > " + VERSION
				+ "); unknown entries are kept as read.");
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

	private void store() throws IOException {
		Path directory = _file.getParent();
		Files.createDirectories(directory);

		if (_damaged && Files.exists(_file)) {
			// The unreadable file may still hold the devices paired so far; keep it for repair.
			Path broken = directory.resolve(FILE_NAME + ".broken-" + Instant.now().toString().replace(':', '-'));
			Files.move(_file, broken, StandardCopyOption.REPLACE_EXISTING);
			LOG.warning("Kept the unreadable device store as '" + broken + "'.");
		}
		_damaged = false;

		Path tmpFile = Files.createTempFile(directory, "devices", ".json");
		try (Writer writer = new OutputStreamWriter(Files.newOutputStream(tmpFile), StandardCharsets.UTF_8)) {
			try (JsonWriter out = new JsonWriter(new WriterAdapter(writer))) {
				out.beginObject();
				out.name(VERSION__PROP);
				out.value(VERSION);
				out.name(DEVICES__PROP);
				out.beginArray();
				for (Device device : _devices) {
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
		}
		Files.move(tmpFile, _file, StandardCopyOption.REPLACE_EXISTING);
	}
}
