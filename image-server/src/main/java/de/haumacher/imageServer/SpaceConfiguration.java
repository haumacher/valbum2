/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.ImageServlet.Context;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.AuthService.Caller;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.SpaceStore;
import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.faces.FaceDetection;
import de.haumacher.imageServer.shared.model.AnonymousAccess;
import de.haumacher.imageServer.shared.model.ContentSwitch;
import de.haumacher.imageServer.shared.model.SpaceSettings;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * The settings of a space, made by its administrator in the app, see issue #297 and
 * {@link SpaceSettings}.
 *
 * <p>
 * <code>GET &lt;data&gt;/?type=space-settings</code> answers what the space's
 * <code>space.json</code> says, <code>POST &lt;data&gt;/?action=space-settings</code> with a
 * {@link SpaceSettings} stores it and answers it as it is then. A write is a <code>POST</code> with
 * an action, as every write of this API that is no upload into a folder is: the settings are no
 * resource at a path of the library, and the body is a message of the protocol like those of
 * <code>set-permission</code> or <code>invite</code>.
 * </p>
 *
 * <p>
 * The administrator's alone: a share link is refused (<code>403</code>), a visitor is asked to sign
 * in (<code>401</code>) and a member who is no administrator is refused (<code>403</code>), each
 * with the reason. Under <code>--auth off</code> there is nobody to refuse, as for every other
 * administration of the server.
 * </p>
 *
 * <p>
 * Every value is checked before anything is written, and a refusal names each value that is
 * wrong; <code>space.json</code> is then exactly what it was. What is written takes effect at once,
 * see {@link ImageServlet#reconfigure(SpaceStore.Config)}.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
final class SpaceConfiguration {

	private static final Logger LOG = Logger.getLogger(SpaceConfiguration.class.getName());

	/** The type the settings are read with, and the action they are stored with. */
	static final String TYPE = "space-settings";

	/** What a member who is no administrator is refused with. */
	static final String ADMIN_ONLY = "The settings of this space are made by its administrator.";

	/** What a share link is refused with. */
	static final String SHARE_LINK_REFUSED = "A share link shows an album; it changes no settings of the space.";

	/** What an unreadable body is refused with. */
	static final String UNREADABLE = "The settings could not be read: send them as SpaceSettings in JSON.";

	/** What an empty name is refused with. */
	static final String NAME_EMPTY = "The space needs a name.";

	/** What a map template that is no web address is refused with. */
	static final String MAP_NOT_WEB = "The map must be a web address starting with 'http://' or 'https://'.";

	/** What a map template without the place of the position is refused with. */
	static final String MAP_WITHOUT_POSITION =
		"The map address must contain {lat} and {lon}, where the position of the photo goes.";

	/** Why the inbox stays where it is while it holds something. */
	static final String INBOX_HOLDS_PHOTOS = "The inbox holds photos: another inbox would turn them into an "
		+ "ordinary album that every member sees. Sort them into albums first.";

	/** Serialises the saves of this space, so that two never interleave their read and write. */
	private static final Object LOCK = new Object();

	private final ImageServlet _servlet;

	private final AuthService _auth;

	private final Path _root;

	/** The first path segment the space is addressed by, empty in a server of one library. */
	private final String _segment;

	/** The name shown where the file names none: the segment, else the folder's own name. */
	private final String _folderName;

	SpaceConfiguration(ImageServlet servlet, AuthService auth, Path root, String segment) {
		_servlet = servlet;
		_auth = auth;
		_root = root;
		_segment = segment;
		Path name = root.toAbsolutePath().normalize().getFileName();
		_folderName = !segment.isEmpty() ? segment : name == null ? "" : name.toString();
	}

	/** Answers <code>?type=space-settings</code>, see {@link SpaceConfiguration}. */
	void serve(Context context, Caller caller) throws IOException {
		if (!administrator(context, caller, false)) {
			return;
		}
		ImageServlet.serveJsonObject(context.response(), answer(_servlet.config()));
	}

	/** Stores <code>?action=space-settings</code>, see {@link SpaceConfiguration}. */
	void save(Context context, Caller caller) throws IOException {
		if (!administrator(context, caller, true)) {
			return;
		}
		SpaceSettings request;
		try {
			byte[] contents = ImageServlet.readBody(context.request());
			request = SpaceSettings.readSpaceSettings(new JsonReader(
				new ReaderAdapter(new InputStreamReader(new ByteArrayInputStream(contents), StandardCharsets.UTF_8))));
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting unparsable space settings: " + ex.getMessage());
			ImageServlet.errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, UNREADABLE);
			return;
		}
		SpaceStore.Config stored;
		synchronized (LOCK) {
			SpaceStore.Config current = _servlet.config();
			List<String> problems = problems(request, current);
			if (!problems.isEmpty()) {
				String message = String.join(" ", problems);
				LOG.warning("Refusing the settings of the space at '" + _root + "': " + message);
				ImageServlet.errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, message);
				return;
			}
			SpaceStore.storeSettings(_root, _folderName, request.getName(),
				request.getAnonymous() == AnonymousAccess.PUBLIC ? SpaceStore.ANONYMOUS_PUBLIC
					: SpaceStore.ANONYMOUS_NONE,
				request.isFaces() ? SpaceStore.FACES_ON : SpaceStore.FACES_OFF, request.getMapUrl(),
				request.getTimeZone(), request.getInbox(), content(request.getContent()));
			// Read back as the start reads it, so that what everybody sees from now on is what the
			// next start sees.
			stored = SpaceStore.load(_root, _segment);
			LOG.info("'" + caller.getUserName() + "' saved the settings of the space at '" + _root + "': " + stored);
			_servlet.reconfigure(stored);
		}
		ImageServlet.serveJsonObject(context.response(), answer(stored));
	}

	/**
	 * What the given switch of the content search is stored as, <code>null</code> to keep what the
	 * space says: an app that does not know the switch sends {@link ContentSwitch#KEEP}.
	 */
	private static String content(ContentSwitch value) {
		switch (value) {
			case ON:
				return SpaceStore.CONTENT_ON;
			case OFF:
				return SpaceStore.CONTENT_OFF;
			case KEEP:
			default:
				return null;
		}
	}

	/**
	 * Whether the caller administers this space; the refusal is answered here if not, see
	 * {@link SpaceConfiguration}.
	 */
	private boolean administrator(Context context, Caller caller, boolean write) throws IOException {
		if (_auth.getMode() == AuthMode.OFF) {
			return true;
		}
		if (caller.isShareLink()) {
			LOG.warning("Refusing the space settings to the share link '" + caller.getShareLabel() + "'.");
			ImageServlet.errorInfo(context, HttpServletResponse.SC_FORBIDDEN, SHARE_LINK_REFUSED);
			return false;
		}
		if (!caller.isPaired()) {
			_servlet.unauthorized(context, caller, write);
			return false;
		}
		if (!Roles.isAdmin(caller.getRole())) {
			LOG.warning("Refusing the space settings to '" + caller.getUserName() + "'.");
			ImageServlet.errorInfo(context, HttpServletResponse.SC_FORBIDDEN, ADMIN_ONLY);
			return false;
		}
		return true;
	}

	/** The settings as the protocol carries them, with what the server knows around them. */
	SpaceSettings answer(SpaceStore.Config config) {
		String fixed = _auth.anonymousFixed();
		String inboxFixed = inboxFixed();
		String unavailable = FaceDetection.unavailability();
		return SpaceSettings.create()
			.setName(config.getName().trim().isEmpty() ? _folderName : config.getName())
			.setAnonymous(config.isAnonymousAllowed() ? AnonymousAccess.PUBLIC : AnonymousAccess.NONE)
			.setAnonymousFixed(fixed == null ? "" : fixed)
			.setFaces(config.isFacesEnabled())
			.setFacesUnavailable(unavailable == null ? "" : unavailable)
			.setMapUrl(config.getMapUrl())
			.setDefaultMapUrl(SpaceStore.DEFAULT_MAP_URL)
			.setTimeZone(config.getTimeZone())
			.setServerTimeZone(ZoneId.systemDefault().getId())
			.setInbox(config.getInbox())
			.setInboxFixed(inboxFixed == null ? "" : inboxFixed)
			.setContent(config.isContentEnabled() ? ContentSwitch.ON : ContentSwitch.OFF)
			.setContentUnavailable(Objects.requireNonNullElse(_servlet.content().unavailability(), ""));
	}

	/** What is wrong with the given settings, one sentence per value; empty where nothing is. */
	List<String> problems(SpaceSettings request, SpaceStore.Config current) {
		List<String> result = new ArrayList<>();
		if (request.getName() == null || request.getName().trim().isEmpty()) {
			result.add(NAME_EMPTY);
		}
		boolean anonymous = request.getAnonymous() == AnonymousAccess.PUBLIC;
		String fixed = _auth.anonymousFixed();
		if (fixed != null && anonymous != current.isAnonymousAllowed()) {
			result.add(fixed);
		}
		String map = checkMapUrl(request.getMapUrl());
		if (map != null) {
			result.add(map);
		}
		String zone = request.getTimeZone() == null ? "" : request.getTimeZone().trim();
		if (!zone.isEmpty() && SpaceStore.zoneOf(zone) == null) {
			result.add(SpaceStore.unknownTimeZone(zone));
		}
		String inbox = request.getInbox() == null ? "" : request.getInbox().trim();
		if (!inbox.equals(current.getInbox())) {
			String problem = checkNewInbox(inbox);
			if (problem != null) {
				result.add(problem);
			}
		}
		return result;
	}

	/**
	 * Why the given map template cannot be a space's, <code>null</code> where it can; empty is the
	 * default map.
	 */
	static String checkMapUrl(String mapUrl) {
		String map = mapUrl == null ? "" : mapUrl.trim();
		if (map.isEmpty()) {
			return null;
		}
		String lower = map.toLowerCase(Locale.ROOT);
		if (!(lower.startsWith("http://") || lower.startsWith("https://")) || map.matches(".*\\s.*")
			|| lower.equals("http://") || lower.equals("https://")) {
			return MAP_NOT_WEB;
		}
		if (!map.contains("{lat}") || !map.contains("{lon}")) {
			return MAP_WITHOUT_POSITION;
		}
		return null;
	}

	/**
	 * Why the inbox cannot move from where it is now, <code>null</code> where it can: it can while
	 * it holds nothing, so that a move is a change of a name and never a move of photographs.
	 */
	String inboxFixed() {
		File folder = _servlet.inboxFolder();
		if (!folder.exists()) {
			return null;
		}
		File[] entries = folder.listFiles();
		if (entries == null) {
			return INBOX_HOLDS_PHOTOS;
		}
		for (File entry : entries) {
			String name = entry.getName();
			if (LibraryFiles.isIgnored(name) || (entry.isFile() && !ResourceCache.isImage(entry))) {
				// The album's own files (index.json, the generated ones), no photograph.
				continue;
			}
			return INBOX_HOLDS_PHOTOS;
		}
		return null;
	}

	/** Why the inbox cannot be moved to the given folder, <code>null</code> where it can. */
	private String checkNewInbox(String inbox) {
		String fixed = inboxFixed();
		if (fixed != null) {
			return fixed;
		}
		String problem = SpaceStore.checkInbox(inbox);
		if (problem != null) {
			return problem;
		}
		File target = _root.resolve(inbox.replace('/', File.separatorChar)).toFile();
		if (target.exists()) {
			return "There is a folder '" + inbox + "' in the space already: the inbox needs a folder of its own, "
				+ "which its first upload creates.";
		}
		for (File above = target.getParentFile(); above != null && !above.toPath().equals(_root);
				above = above.getParentFile()) {
			if (above.isFile()) {
				return "'" + inbox + "' lies below a file, not a folder.";
			}
			if (above.isDirectory() && (PhotoCollections.isCollection(above) || PhotoSearch.isSearch(above))) {
				return "'" + inbox + "' lies in a collection or a saved search, which holds no folder.";
			}
		}
		return null;
	}
}
