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

/**
 * What a folder says about itself as a space, read from <code>.valbum/space.json</code> (issue #82).
 *
 * <p>
 * The presence of this file is what makes a folder below the base folder a space, which is why the
 * file is written by hand — a space is created by whoever administers the machine, never by the
 * server, so that no request can bring a space into existence.
 * </p>
 *
 * <p>
 * The file format is persisted data and therefore versioned:
 * </p>
 *
 * <pre>
 * {"version":1,"name":"Alice","anonymous":"public","mapUrl":"https://www.google.com/maps?q={lat},{lon}"}
 * </pre>
 *
 * <p>
 * Everything is optional: a file holding nothing but <code>{}</code> is a space with the folder's
 * own name, no anonymous access and the default map (issue #112). An unknown
 * <code>anonymous</code> value is read as {@link #ANONYMOUS_NONE}, the closed one — a space is
 * never opened by a typo.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class SpaceStore {

	/** The name of the file within {@link UserStore#DIRECTORY_NAME} marking a folder as a space. */
	public static final String FILE_NAME = "space.json";

	/** Nobody sees anything here without signing in. */
	public static final String ANONYMOUS_NONE = "none";

	/** An anonymous caller may look at the public images of this space. */
	public static final String ANONYMOUS_PUBLIC = "public";

	/**
	 * Where a position is shown when the space names no map of its own, see issue #112.
	 *
	 * <p>
	 * A URL template carrying <code>{lat}</code> and <code>{lon}</code>. There is no provider to
	 * choose from, because a provider <em>is</em> a template: OpenStreetMap is
	 * <code>https://www.openstreetmap.org/?mlat={lat}&amp;mlon={lon}#map=15/{lat}/{lon}</code>,
	 * Apple Maps is <code>https://maps.apple.com/?ll={lat},{lon}</code>, and a map of one's own is
	 * whatever it is. The app applies the same default where an older server answers none.
	 * </p>
	 */
	public static final String DEFAULT_MAP_URL = "https://www.google.com/maps?q={lat},{lon}";

	private static final int VERSION = 1;

	private static final String VERSION__PROP = "version";

	private static final String NAME__PROP = "name";

	private static final String ANONYMOUS__PROP = "anonymous";

	private static final String MAP_URL__PROP = "mapUrl";

	/** What a space says about itself. */
	public static final class Config {

		private final String _name;

		private final String _anonymous;

		private final String _mapUrl;

		/** Creates a {@link Config} with the default map, see {@link SpaceStore#DEFAULT_MAP_URL}. */
		public Config(String name, String anonymous) {
			this(name, anonymous, "");
		}

		/**
		 * Creates a {@link Config}.
		 *
		 * @param mapUrl
		 *        The map template of this space; the empty string for {@link #DEFAULT_MAP_URL}.
		 */
		public Config(String name, String anonymous, String mapUrl) {
			_name = name;
			_anonymous = anonymous;
			_mapUrl = mapUrl == null || mapUrl.trim().isEmpty() ? DEFAULT_MAP_URL : mapUrl.trim();
		}

		/** The name to show for this space; the folder name when the file gives none. */
		public String getName() {
			return _name;
		}

		/** {@link SpaceStore#ANONYMOUS_NONE} or {@link SpaceStore#ANONYMOUS_PUBLIC}. */
		public String getAnonymous() {
			return _anonymous;
		}

		/**
		 * The URL template a position of this space is shown on a map with, see issue #112.
		 *
		 * <p>
		 * Never empty: a space that names none is answered {@link SpaceStore#DEFAULT_MAP_URL}, so
		 * that every space has a map and the app never has to decide what "nothing" means.
		 * </p>
		 */
		public String getMapUrl() {
			return _mapUrl;
		}

		/** Whether an anonymous caller may look at the public images of this space. */
		public boolean isAnonymousAllowed() {
			return ANONYMOUS_PUBLIC.equals(_anonymous);
		}

		@Override
		public String toString() {
			return "Space[" + _name + ", anonymous=" + _anonymous + ", map=" + _mapUrl + "]";
		}
	}

	/** The file marking the given folder as a space, whether it exists or not. */
	public static Path file(Path spaceRoot) {
		return spaceRoot.resolve(UserStore.DIRECTORY_NAME).resolve(FILE_NAME);
	}

	/** Whether the given folder carries a {@value #FILE_NAME} and is therefore a space. */
	public static boolean isSpace(Path folder) {
		return Files.isRegularFile(file(folder));
	}

	/**
	 * Reads the configuration of the space at the given folder.
	 *
	 * @param folderName
	 *        The name the space is addressed by, used when the file names none.
	 * @throws IOException
	 *         If the file is there but cannot be read; a space whose configuration is broken is
	 *         not silently served with defaults.
	 */
	public static Config load(Path spaceRoot, String folderName) throws IOException {
		Path file = file(spaceRoot);
		if (!Files.isRegularFile(file)) {
			return new Config(folderName, ANONYMOUS_NONE);
		}
		String name = "";
		String anonymous = "";
		String mapUrl = "";
		try (Reader reader = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8);
				JsonReader in = new JsonReader(new ReaderAdapter(reader))) {
			in.beginObject();
			while (in.hasNext()) {
				String key = in.nextName();
				switch (key) {
					case NAME__PROP:
						name = in.nextString();
						break;
					case ANONYMOUS__PROP:
						anonymous = in.nextString();
						break;
					case MAP_URL__PROP:
						mapUrl = in.nextString();
						break;
					default:
						in.skipValue();
						break;
				}
			}
			in.endObject();
		} catch (RuntimeException ex) {
			throw new IOException("Cannot read '" + file + "': " + ex.getMessage(), ex);
		}
		return new Config(name.trim().isEmpty() ? folderName : name.trim(),
			ANONYMOUS_PUBLIC.equals(anonymous) ? ANONYMOUS_PUBLIC : ANONYMOUS_NONE, mapUrl);
	}

	/**
	 * Writes the configuration of the space at the given folder, creating the folder's
	 * {@value UserStore#DIRECTORY_NAME} if it has none.
	 *
	 * <p>
	 * Used by the migration of issue #82, which turns the folders of a per-user library into
	 * spaces; the running server never writes this file.
	 * </p>
	 */
	public static void store(Path spaceRoot, Config config) throws IOException {
		Path file = file(spaceRoot);
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
		try (Writer writer = new OutputStreamWriter(Files.newOutputStream(tmp), StandardCharsets.UTF_8);
				JsonWriter out = new JsonWriter(new WriterAdapter(writer))) {
			out.beginObject();
			out.name(VERSION__PROP);
			out.value(VERSION);
			out.name(NAME__PROP);
			out.value(config.getName());
			out.name(ANONYMOUS__PROP);
			out.value(config.getAnonymous());
			out.name(MAP_URL__PROP);
			out.value(config.getMapUrl());
			out.endObject();
		}
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
	}
}
