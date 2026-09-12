/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.links;

import de.haumacher.imageServer.auth.UserStore;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * What a user's space already knows about the albums shared with its owner, see issue #50.
 *
 * <p>
 * One record per target: the share was {@link #LINKED linked} once (a {@link LinkStore.Link} was
 * created for it somewhere in the space, and where it is now is the recipient's business, not this
 * registry's) or it was {@link #DECLINED declined} (the recipient removed the link and does not
 * want it again). Both answers mean "do not materialise this again": the registry is what makes a
 * link appear exactly once, and it is a registry rather than a tree scan because a link may be
 * moved anywhere in the space or removed altogether.
 * </p>
 *
 * <p>
 * It lives in {@value UserStore#DIRECTORY_NAME} at the root of the recipient's <em>own</em> space,
 * never in the owner's: nothing in the owner's library records who linked it. The file name is
 * {@value #FILE_NAME} and not <code>shares.json</code>, which the share-link tokens of issue #51
 * claim at the base folder — in a library that was never migrated the owner's space root
 * <em>is</em> the base folder, and the two stores would collide there.
 * </p>
 *
 * <p>
 * The file format is persisted data and therefore versioned:
 * </p>
 *
 * <pre>
 * {"version":1,"shares":[{"owner":"alice","path":"2024/2024-05-01 Zoo","state":"linked",
 *   "created":"2026-09-12T10:11:12Z"}]}
 * </pre>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class ShareRegistry {

	private static final Logger LOG = Logger.getLogger(ShareRegistry.class.getName());

	/** The name of the store within {@value UserStore#DIRECTORY_NAME}. */
	public static final String FILE_NAME = "share-registry.json";

	/** The version this build writes, see {@link ShareRegistry}. */
	public static final int VERSION = 1;

	/** The state of a share this space has a link for. */
	public static final String LINKED = "linked";

	/**
	 * The state of a share whose link the recipient removed, see
	 * {@link de.haumacher.imageServer.ImageServlet} and its <code>?action=unlink</code>.
	 *
	 * <p>
	 * Declining is a statement and outlives the grant it was made about: a later grant on the same
	 * target does not resurrect the link, because the recipient already said they do not want it in
	 * their tree. Taking that back is {@link #clear(String, String)}, which the management screens
	 * of issue #55 offer as "accept again"; nothing in this build calls it.
	 * </p>
	 */
	public static final String DECLINED = "declined";

	private static final String VERSION__PROP = "version";

	private static final String SHARES__PROP = "shares";

	private static final String OWNER__PROP = "owner";

	private static final String PATH__PROP = "path";

	private static final String STATE__PROP = "state";

	private static final String CREATED__PROP = "created";

	/** What this space knows about one shared target. */
	public static final class Share {

		private final String _owner;

		private final String _path;

		private String _state;

		private final String _created;

		/** Creates a {@link Share}. */
		public Share(String owner, String path, String state, String created) {
			_owner = owner;
			_path = path;
			_state = state;
			_created = created;
		}

		/** The name of the user whose album this is. */
		public String getOwner() {
			return _owner;
		}

		/** The album as a path relative to the owner's space, <code>/</code> as separator. */
		public String getPath() {
			return _path;
		}

		/** {@link ShareRegistry#LINKED} or {@link ShareRegistry#DECLINED}. */
		public String getState() {
			return _state;
		}

		/** See {@link #getState()}. */
		void setState(String state) {
			_state = state;
		}

		/** When this space first learned of the share, an ISO-8601 instant. */
		public String getCreated() {
			return _created;
		}

		@Override
		public String toString() {
			return LinkStore.canonical(_owner, _path) + ":" + _state;
		}
	}

	private final Path _file;

	private List<Share> _shares = new ArrayList<>();

	/** Whether the file on disk could not be read; it is set aside instead of overwritten. */
	private boolean _damaged;

	/**
	 * Creates the {@link ShareRegistry} of the space rooted at the given folder.
	 *
	 * <p>
	 * Nothing is created on disk here: a caller without a space of their own (a guest, an anonymous
	 * caller) must not leave a {@value UserStore#DIRECTORY_NAME} folder behind, see
	 * {@link LinkService#materialise(de.haumacher.imageServer.auth.AuthService.Caller, java.nio.file.Path)}.
	 * </p>
	 */
	public ShareRegistry(Path spaceRoot) {
		_file = spaceRoot.resolve(UserStore.DIRECTORY_NAME).resolve(FILE_NAME);
		load();
	}

	/** The file this registry is persisted in. */
	public Path getFile() {
		return _file;
	}

	/** Everything this space knows, in the order it learned it. */
	public synchronized List<Share> getShares() {
		return Collections.unmodifiableList(new ArrayList<>(_shares));
	}

	/** What this space knows about the given target, <code>null</code> if it knows nothing. */
	public synchronized Share get(String owner, String path) {
		for (Share share : _shares) {
			if (share.getOwner().equals(owner) && share.getPath().equals(path)) {
				return share;
			}
		}
		return null;
	}

	/** Whether this space has already dealt with the given target, linked or declined. */
	public synchronized boolean knows(String owner, String path) {
		return get(owner, path) != null;
	}

	/** Records that a link was created for the given target. */
	public synchronized Share link(String owner, String path) throws IOException {
		return record(owner, path, LINKED);
	}

	/** Records that the recipient removed the link of the given target, see {@link #DECLINED}. */
	public synchronized Share decline(String owner, String path) throws IOException {
		return record(owner, path, DECLINED);
	}

	/**
	 * Forgets what this space knows about the given target, so that it is linked again.
	 *
	 * <p>
	 * The seam for the "accept again" of issue #55: nothing in this build takes a decline back, and
	 * nothing else may, because declining is the recipient's statement.
	 * </p>
	 *
	 * @return Whether there was anything to forget.
	 */
	public synchronized boolean clear(String owner, String path) throws IOException {
		Share existing = get(owner, path);
		if (existing == null) {
			return false;
		}
		_shares.remove(existing);
		store();
		return true;
	}

	private Share record(String owner, String path, String state) throws IOException {
		Share existing = get(owner, path);
		Share result;
		if (existing != null) {
			existing.setState(state);
			result = existing;
		} else {
			result = new Share(owner, path, state, Instant.now().toString());
			_shares.add(result);
		}
		store();
		return result;
	}

	private void load() {
		if (!Files.exists(_file)) {
			return;
		}
		try (Reader reader = new InputStreamReader(Files.newInputStream(_file), StandardCharsets.UTF_8)) {
			_shares = readShares(new JsonReader(new ReaderAdapter(reader)));
		} catch (IOException | RuntimeException ex) {
			// A broken registry must not lock the space up; it knows nothing instead, and the
			// links that are already there keep working.
			LOG.log(Level.WARNING, "Cannot read the share registry '" + _file + "': " + ex.getMessage());
			_shares = new ArrayList<>();
			_damaged = true;
		}
	}

	private static List<Share> readShares(JsonReader in) throws IOException {
		List<Share> result = new ArrayList<>();
		int version = 0;
		in.beginObject();
		while (in.hasNext()) {
			String key = in.nextName();
			switch (key) {
				case VERSION__PROP:
					version = in.nextInt();
					break;
				case SHARES__PROP:
					in.beginArray();
					while (in.hasNext()) {
						result.add(readShare(in));
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
			LOG.warning("The share registry was written by a newer version (" + version + " > " + VERSION
				+ "); unknown entries are kept as read.");
		}
		return result;
	}

	private static Share readShare(JsonReader in) throws IOException {
		String owner = "";
		String path = "";
		String state = LINKED;
		String created = "";
		in.beginObject();
		while (in.hasNext()) {
			String key = in.nextName();
			switch (key) {
				case OWNER__PROP:
					owner = in.nextString();
					break;
				case PATH__PROP:
					path = in.nextString();
					break;
				case STATE__PROP:
					state = in.nextString();
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
		return new Share(owner, path, state, created);
	}

	/** Writes this registry to disk, atomically: a crash never leaves a half-written file. */
	public synchronized void store() throws IOException {
		Path directory = _file.getParent();
		Files.createDirectories(directory);

		if (_damaged && Files.exists(_file)) {
			// The unreadable file may still say what the recipient declined; keep it for repair.
			Path broken = directory.resolve(FILE_NAME + ".broken-" + Instant.now().toString().replace(':', '-'));
			Files.move(_file, broken, StandardCopyOption.REPLACE_EXISTING);
			LOG.warning("Kept the unreadable share registry as '" + broken + "'.");
		}
		_damaged = false;

		Path tmpFile = Files.createTempFile(directory, "shares", ".json");
		try (Writer writer = new OutputStreamWriter(Files.newOutputStream(tmpFile), StandardCharsets.UTF_8)) {
			try (JsonWriter out = new JsonWriter(new WriterAdapter(writer))) {
				out.beginObject();
				out.name(VERSION__PROP);
				out.value(VERSION);
				out.name(SHARES__PROP);
				out.beginArray();
				for (Share share : _shares) {
					writeShare(out, share);
				}
				out.endArray();
				out.endObject();
			}
		}
		Files.move(tmpFile, _file, StandardCopyOption.REPLACE_EXISTING);
	}

	private static void writeShare(JsonWriter out, Share share) throws IOException {
		out.beginObject();
		out.name(OWNER__PROP);
		out.value(share.getOwner());
		out.name(PATH__PROP);
		out.value(share.getPath());
		out.name(STATE__PROP);
		out.value(share.getState());
		out.name(CREATED__PROP);
		out.value(share.getCreated());
		out.endObject();
	}
}
