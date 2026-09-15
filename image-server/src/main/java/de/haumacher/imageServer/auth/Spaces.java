/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.auth;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The spaces this server hosts, decided once at start-up (issue #82).
 *
 * <p>
 * A space is a mandator: its own users, its own albums, its own share links, and nothing crosses
 * its boundary. Which spaces there are is read from the folder tree and never changes while the
 * server runs — a space is created on disk by hand, so a new one is picked up by a restart, which
 * is also the only moment at which anything could pick it up safely: a space brings its own user
 * store with it.
 * </p>
 *
 * <p>
 * <b>The mode rule.</b> {@link SpaceMode#MULTI} when at least one folder directly below the base
 * folder carries a {@value SpaceStore#FILE_NAME}, {@link SpaceMode#SINGLE} otherwise. That is the
 * whole rule, and it is decidable without asking anybody: a library that was never migrated has no
 * such file anywhere and stays what it is, and a tree where somebody wrote one space marker means
 * to be a multi-space server. <code>--spaces single|multi</code> overrides it for the case where
 * the answer is not the wanted one — forcing {@link SpaceMode#SINGLE} on a tree that has markers
 * serves the base folder as one space and ignores them, forcing {@link SpaceMode#MULTI} on a tree
 * that has none serves a server with no space at all, which answers every address with "no such
 * space" and is only ever useful while a tree is being set up.
 * </p>
 *
 * <p>
 * Each space has its own {@link AuthService} rooted at its own folder, which is what makes the
 * boundary real without a single permission check knowing about spaces: a token of one space's
 * store is simply not in another's, so its holder is anonymous there.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class Spaces {

	private static final Logger LOG = Logger.getLogger(Spaces.class.getName());

	/** One space, with everything that is its own. */
	public static final class Space {

		private final String _segment;

		private final Path _root;

		private final SpaceStore.Config _config;

		private final AuthService _auth;

		Space(String segment, Path root, SpaceStore.Config config, AuthService auth) {
			_segment = segment;
			_root = root;
			_config = config;
			_auth = auth;
		}

		/**
		 * The first path segment this space is addressed by; the empty string in
		 * {@link SpaceMode#SINGLE} mode, where the space has no segment of its own.
		 */
		public String getSegment() {
			return _segment;
		}

		/** The folder this space's paths are resolved against. */
		public Path getRoot() {
			return _root;
		}

		/** What the space says about itself, see {@link SpaceStore}. */
		public SpaceStore.Config getConfig() {
			return _config;
		}

		/** The users, devices and permissions of this space, and nobody else's. */
		public AuthService getAuth() {
			return _auth;
		}

		@Override
		public String toString() {
			return "Space[" + (_segment.isEmpty() ? "<the base folder>" : _segment) + " at " + _root + "]";
		}
	}

	private final SpaceMode _mode;

	private final Map<String, Space> _spaces;

	private Spaces(SpaceMode mode, Map<String, Space> spaces) {
		_mode = mode;
		_spaces = spaces;
	}

	/** Whether this server hosts one space or several. */
	public SpaceMode getMode() {
		return _mode;
	}

	/** The spaces, in the order their folders were found. */
	public Collection<Space> getSpaces() {
		return Collections.unmodifiableCollection(_spaces.values());
	}

	/** The one space of a {@link SpaceMode#SINGLE} server. */
	public Space single() {
		return _spaces.get("");
	}

	/**
	 * The space addressed by the given first path segment.
	 *
	 * @return <code>null</code> if there is no such space, which every endpoint answers as
	 *         "no such space" rather than as "nothing here".
	 */
	public Space bySegment(String segment) {
		return _spaces.get(segment == null ? "" : segment);
	}

	/** Whether the given first path segment addresses a space of this server. */
	public boolean isSpace(String segment) {
		return segment != null && !segment.isEmpty() && _spaces.containsKey(segment);
	}

	/** The one space of a server that has exactly one, for the wiring that already has its service. */
	public static Spaces singleSpace(Path basePath, AuthService auth) {
		Map<String, Space> spaces = new LinkedHashMap<>();
		spaces.put("", new Space("", basePath, new SpaceStore.Config("", SpaceStore.ANONYMOUS_NONE), auth));
		return new Spaces(SpaceMode.SINGLE, spaces);
	}

	/** The segments the spaces are addressed by; empty on a single-space server. */
	public List<String> segments() {
		List<String> result = new ArrayList<>(_spaces.keySet());
		result.remove("");
		return result;
	}

	/**
	 * Reads the spaces of the given base folder and builds one {@link AuthService} for each.
	 *
	 * @param forced
	 *        The mode to use whatever the folder tree says, <code>null</code> to decide by the
	 *        rule described at {@link Spaces}.
	 */
	public static Spaces detect(Path basePath, SpaceMode forced, AuthMode authMode, String pairingSecret,
			InviteMode inviteMode) throws IOException {
		List<String> segments = spaceFolders(basePath);
		SpaceMode mode = forced != null ? forced : (segments.isEmpty() ? SpaceMode.SINGLE : SpaceMode.MULTI);

		Map<String, Space> spaces = new LinkedHashMap<>();
		if (mode == SpaceMode.SINGLE) {
			SpaceStore.Config config = SpaceStore.load(basePath, "");
			spaces.put("", new Space("", basePath,
				config, new AuthService(authMode, pairingSecret, basePath, inviteMode)));
		} else {
			for (String segment : segments) {
				Path root = basePath.resolve(segment);
				SpaceStore.Config config = SpaceStore.load(root, segment);
				// The space says whether it is open to anonymous callers; the server-wide mode only
				// ever closes further (and --auth off stays off, which is what development wants).
				AuthMode spaceMode = authModeOf(authMode, config);
				spaces.put(segment, new Space(segment, root, config,
					new AuthService(spaceMode, pairingSecret, root, inviteMode)));
			}
		}
		return new Spaces(mode, spaces);
	}

	/**
	 * What a space's own authentication mode is.
	 *
	 * <p>
	 * Anonymous access is a property of the space (issue #82), not of the server: a space that
	 * allows it is served like a library in {@link AuthMode#WRITES} (anonymous reads, no anonymous
	 * changes), one that does not like {@link AuthMode#ALL}. The server-wide
	 * <code>--auth off</code> is kept as it is, because it is the switch that turns authentication
	 * off altogether for development, and a server-wide {@link AuthMode#ALL} is never loosened by
	 * a space.
	 * </p>
	 */
	static AuthMode authModeOf(AuthMode serverMode, SpaceStore.Config config) {
		if (serverMode == AuthMode.OFF || serverMode == AuthMode.ALL) {
			return serverMode;
		}
		return config.isAnonymousAllowed() ? AuthMode.WRITES : AuthMode.ALL;
	}

	/** The folders directly below the base folder that carry a {@value SpaceStore#FILE_NAME}. */
	private static List<String> spaceFolders(Path basePath) {
		List<String> result = new ArrayList<>();
		if (basePath == null || !Files.isDirectory(basePath)) {
			return result;
		}
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(basePath)) {
			for (Path entry : entries) {
				String name = entry.getFileName().toString();
				if (UserStore.isServerEntry(name) || !Files.isDirectory(entry)) {
					continue;
				}
				if (SpaceStore.isSpace(entry)) {
					result.add(name);
				}
			}
		} catch (IOException ex) {
			LOG.log(Level.WARNING, "Cannot list the base folder '" + basePath + "'.", ex);
		}
		Collections.sort(result);
		return result;
	}
}
