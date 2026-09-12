/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.links;

import de.haumacher.imageServer.upload.HashCache;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The link entries of a single folder, see issue #50.
 *
 * <p>
 * A link is an entry of this folder that resolves somewhere else: the shared album of another user,
 * appearing in the recipient's own tree under a name and in a place the recipient chose. The record
 * names the entry ({@link Link#getName()}) and its target in the owner's coordinates
 * ({@link Link#getOwner()} and {@link Link#getPath()}, exactly what
 * {@link de.haumacher.imageServer.auth.AuthService#spaceOf(de.haumacher.imageServer.PathInfo)}
 * answers for the target). Nothing of the target is copied here: a link carries no title, no cover
 * and no date, because all three belong to the owner and are read from the owner's folder on every
 * listing.
 * </p>
 *
 * <p>
 * The store is a sidecar {@value #FILE_NAME} beside the folder's <code>index.json</code>, the
 * {@link HashCache} precedent, and it lives only in the recipient's own space: the owner's folders
 * never learn that somebody linked them. It is written in this class's own JSON, not in the
 * protocol's, and the format is persisted data and therefore versioned:
 * </p>
 *
 * <pre>
 * {"version":1,"links":[{"name":"Zoo","owner":"alice","path":"2024/2024-05-01 Zoo",
 *   "created":"2026-09-12T10:11:12Z"}]}
 * </pre>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class LinkStore {

	private static final Logger LOG = Logger.getLogger(LinkStore.class.getName());

	/** The name of the sidecar in the folder whose links it holds. */
	public static final String FILE_NAME = ".links.json";

	/** The version this build writes, see {@link LinkStore}. */
	public static final int VERSION = 1;

	private static final String VERSION__PROP = "version";

	private static final String LINKS__PROP = "links";

	private static final String NAME__PROP = "name";

	private static final String OWNER__PROP = "owner";

	private static final String PATH__PROP = "path";

	private static final String CREATED__PROP = "created";

	/** A single link entry, see {@link LinkStore}. */
	public static final class Link {

		private final String _name;

		private final String _owner;

		private final String _path;

		private final String _created;

		/** Creates a {@link Link}. */
		public Link(String name, String owner, String path, String created) {
			_name = name;
			_owner = owner;
			_path = path;
			_created = created;
		}

		/** The name this entry has in the folder holding it. */
		public String getName() {
			return _name;
		}

		/** The name of the user in whose space the target lies. */
		public String getOwner() {
			return _owner;
		}

		/**
		 * The target as a path relative to the owner's space, <code>/</code> as separator.
		 *
		 * <p>
		 * The empty string is the owner's space itself: a grant on a whole library is linked as
		 * that library.
		 * </p>
		 */
		public String getPath() {
			return _path;
		}

		/** When the link was made, an ISO-8601 instant. */
		public String getCreated() {
			return _created;
		}

		/** This link's target in the canonical form <code>~&lt;owner&gt;/&lt;path&gt;</code>. */
		public String canonical() {
			return LinkStore.canonical(_owner, _path);
		}

		/** The same link under another name, see {@link LinkStore#move(Link, LinkStore, String)}. */
		public Link renamed(String name) {
			return new Link(name, _owner, _path, _created);
		}

		@Override
		public String toString() {
			return _name + " -> " + canonical();
		}
	}

	/** The canonical form <code>~&lt;owner&gt;/&lt;path&gt;</code> of the given target. */
	public static String canonical(String owner, String path) {
		return de.haumacher.imageServer.auth.AuthService.HOME_PREFIX + owner + (path.isEmpty() ? "" : "/" + path);
	}

	private final File _file;

	private List<Link> _links = new ArrayList<>();

	/** Whether the file on disk could not be read; it is set aside instead of overwritten. */
	private boolean _damaged;

	/**
	 * Loads the links of the given folder.
	 *
	 * <p>
	 * A folder that holds no links has no file, and reading one is nothing but a failed
	 * {@link File#exists()}: this is on the path of every listing, and a tree without a single
	 * share must not pay for the feature.
	 * </p>
	 */
	public LinkStore(File folder) {
		_file = new File(folder, FILE_NAME);
		load();
	}

	/** Whether the given folder holds any link record at all, without reading the file. */
	public static boolean exists(File folder) {
		return new File(folder, FILE_NAME).exists();
	}

	/** The file this store is persisted in. */
	public File getFile() {
		return _file;
	}

	/** The links of this folder, in the order they were made. */
	public synchronized List<Link> getLinks() {
		return Collections.unmodifiableList(new ArrayList<>(_links));
	}

	/** Whether this folder holds no link at all. */
	public synchronized boolean isEmpty() {
		return _links.isEmpty();
	}

	/** The link of the given name, <code>null</code> if this folder has none. */
	public synchronized Link get(String name) {
		for (Link link : _links) {
			if (link.getName().equals(name)) {
				return link;
			}
		}
		return null;
	}

	/** Whether this folder already links the given target. */
	public synchronized boolean links(String owner, String path) {
		for (Link link : _links) {
			if (link.getOwner().equals(owner) && link.getPath().equals(path)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Records a link, replacing one of the same name. The store is written before this returns.
	 */
	public synchronized Link put(String name, String owner, String path) throws IOException {
		Link result = new Link(name, owner, path, Instant.now().toString());
		remove(name, false);
		_links.add(result);
		store();
		return result;
	}

	/**
	 * Removes the link of the given name.
	 *
	 * @return The removed link, <code>null</code> if there was none; the store is written only if
	 *         there was.
	 */
	public synchronized Link remove(String name) throws IOException {
		return remove(name, true);
	}

	private Link remove(String name, boolean store) throws IOException {
		Link existing = get(name);
		if (existing == null) {
			return null;
		}
		_links.remove(existing);
		if (store) {
			store();
		}
		return existing;
	}

	/**
	 * Moves a link out of this folder into another one, see issue #47.
	 *
	 * <p>
	 * The record is what travels; nothing on disk is renamed, because a link is not a file. Both
	 * stores are written here, so that a move never leaves the same link in two folders.
	 * </p>
	 *
	 * @param link
	 *        The link of this folder to move, see {@link #get(String)}.
	 * @param target
	 *        The store of the folder it moves into.
	 * @param newName
	 *        The name it takes there, see
	 *        {@link LinkService#freeName(java.io.File, LinkStore, String)}.
	 */
	public synchronized void move(Link link, LinkStore target, String newName) throws IOException {
		remove(link.getName());
		target.put(newName, link.getOwner(), link.getPath());
	}

	private void load() {
		if (!_file.exists()) {
			return;
		}
		try (Reader reader = new InputStreamReader(Files.newInputStream(_file.toPath()), StandardCharsets.UTF_8)) {
			_links = readLinks(new JsonReader(new ReaderAdapter(reader)));
		} catch (IOException | RuntimeException ex) {
			// A broken sidecar must not hide the folder it lies in; it links nothing instead.
			LOG.log(Level.WARNING, "Cannot read the link store '" + _file + "': " + ex.getMessage());
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
			LOG.warning("The link store was written by a newer version (" + version + " > " + VERSION
				+ "); unknown entries are kept as read.");
		}
		return result;
	}

	private static Link readLink(JsonReader in) throws IOException {
		String name = "";
		String owner = "";
		String path = "";
		String created = "";
		in.beginObject();
		while (in.hasNext()) {
			String key = in.nextName();
			switch (key) {
				case NAME__PROP:
					name = in.nextString();
					break;
				case OWNER__PROP:
					owner = in.nextString();
					break;
				case PATH__PROP:
					path = in.nextString();
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
		return new Link(name, owner, path, created);
	}

	/** Writes this store to disk, atomically: a crash never leaves a half-written store. */
	public synchronized void store() throws IOException {
		Path file = _file.toPath();
		Path directory = file.getParent();
		Files.createDirectories(directory);

		if (_damaged && Files.exists(file)) {
			// The unreadable file may still hold links somebody made; keep it for repair.
			Path broken = directory.resolve(FILE_NAME + ".broken-" + Instant.now().toString().replace(':', '-'));
			Files.move(file, broken, StandardCopyOption.REPLACE_EXISTING);
			LOG.warning("Kept the unreadable link store as '" + broken + "'.");
		}
		_damaged = false;

		if (_links.isEmpty() && !Files.exists(file)) {
			// A folder that never held a link keeps no empty file.
			return;
		}

		Path tmpFile = Files.createTempFile(directory, "links", ".json");
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
		Files.move(tmpFile, file, StandardCopyOption.REPLACE_EXISTING);
	}

	private static void writeLink(JsonWriter out, Link link) throws IOException {
		out.beginObject();
		out.name(NAME__PROP);
		out.value(link.getName());
		out.name(OWNER__PROP);
		out.value(link.getOwner());
		out.name(PATH__PROP);
		out.value(link.getPath());
		out.name(CREATED__PROP);
		out.value(link.getCreated());
		out.endObject();
	}
}
