/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer;


import de.haumacher.imageServer.MoveService.MoveRefused;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.AuthService.Caller;
import de.haumacher.imageServer.auth.AuthService.Location;
import de.haumacher.imageServer.auth.AuthService.PairRefused;
import de.haumacher.imageServer.auth.AuthService.PathRefused;
import de.haumacher.imageServer.auth.Clearances;
import de.haumacher.imageServer.auth.DeviceCodeStore;
import de.haumacher.imageServer.auth.InvitationStore;
import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.auth.Ratings;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.ShareStore;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.ContentHash;
import de.haumacher.imageServer.shared.model.CreateResult;
import de.haumacher.imageServer.shared.model.DeviceCodeCreated;
import de.haumacher.imageServer.shared.model.DeviceCodeRequest;
import de.haumacher.imageServer.shared.model.DeviceEntry;
import de.haumacher.imageServer.shared.model.DeviceList;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.ImageKind;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Invitation;
import de.haumacher.imageServer.shared.model.InvitationCreated;
import de.haumacher.imageServer.shared.model.InvitationList;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.MemberName;
import de.haumacher.imageServer.shared.model.MoveName;
import de.haumacher.imageServer.shared.model.MoveOutcome;
import de.haumacher.imageServer.shared.model.MoveRequest;
import de.haumacher.imageServer.shared.model.MoveResult;
import de.haumacher.imageServer.shared.model.PairRequest;
import de.haumacher.imageServer.shared.model.PairResponse;
import de.haumacher.imageServer.shared.model.PresentFile;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.ShareLink;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import de.haumacher.imageServer.shared.model.ShareLinkList;
import de.haumacher.imageServer.shared.model.UploadCheck;
import de.haumacher.imageServer.shared.model.UploadCheckResult;
import de.haumacher.imageServer.shared.model.UploadResult;
import de.haumacher.imageServer.shared.model.UploadedFile;
import de.haumacher.imageServer.shared.model.UserEntry;
import de.haumacher.imageServer.shared.model.UserList;
import de.haumacher.imageServer.shared.model.UserPermission;
import de.haumacher.imageServer.shared.ui.Settings;
import de.haumacher.imageServer.upload.HashCache;
import de.haumacher.imageServer.upload.UploadFactory;
import de.haumacher.imageServer.upload.UploadItem;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import de.haumacher.util.servlet.ByteRange;
import de.haumacher.util.servlet.Util;
import jakarta.activation.MimeType;
import jakarta.activation.MimeTypeParseException;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.apache.commons.fileupload2.jakarta.JakartaServletFileUpload;

/**
 * {@link HttpServlet} serving image, video, preview and directory listing data.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@MultipartConfig
public class ImageServlet extends HttpServlet {

	private static final Logger LOG = Logger.getLogger(ImageServlet.class.getName());

	/** The {@link UploadedFile#getStatus() status} of contents that were written to the album. */
	public static final String STORED = "stored";

	/**
	 * The {@link UploadedFile#getStatus() status} of contents the target folder already held.
	 *
	 * <p>
	 * Nothing was written: this is what makes a retried upload idempotent, see issue #29.
	 * </p>
	 */
	public static final String PRESENT = "present";

	/** The message a PUT is refused with that would replace an existing file with other contents. */
	public static final String REPLACE_REFUSED =
		"A different file with this name already exists; it is not replaced.";

	/** The message an unreadable upload check is refused with. */
	public static final String CHECK_UNREADABLE = "The upload check cannot be read.";

	/** The message an unreadable request to remove a link is refused with. */
	public static final String UNLINK_UNREADABLE = "The request to remove a shared album cannot be read.";

	/** The message an unreadable share link is refused with. */
	public static final String SHARE_UNREADABLE = "The share link cannot be read.";

	/** The message an unreadable invitation is refused with, see issue #52. */
	public static final String INVITATION_UNREADABLE = "The invitation cannot be read.";

	/** The message an unreadable promotion request is refused with. */
	public static final String PROMOTION_UNREADABLE = "The request naming the user to promote cannot be read.";

	/**
	 * The message a request for an image below the caller's rating limit is refused with, see
	 * issue #51.
	 *
	 * <p>
	 * Only a share link has such a limit, and it is the author's choice of what the link shows —
	 * so the refusal says that, rather than pretending the file is not there.
	 * </p>
	 */
	public static final String RATING_REFUSED = "This image is not part of what this link shows.";

	/** The message a grant or revoke naming a share-link token is refused with, see issue #51. */
	public static final String SHARE_GRANT_REFUSED =
		"The grant of a share link belongs to the link: create one with ?action=share and withdraw it with "
			+ "?action=unshare.";

	/** The message an unreadable grant is refused with. */
	public static final String GRANT_UNREADABLE = "The grant cannot be read.";

	/** The message an unreadable group is refused with. */
	public static final String GROUP_UNREADABLE = "The group cannot be read.";

	/** The message an unreadable group rename is refused with, see issue #55. */
	public static final String RENAME_UNREADABLE = "The request naming the group to rename cannot be read.";

	/** The message an unreadable device code request is refused with, see issue #89. */
	public static final String DEVICE_CODE_UNREADABLE = "The request naming whom the code is for cannot be read.";

	/** The message an unreadable unpair request is refused with, see issue #55. */
	public static final String DEVICE_UNREADABLE = "The request naming the device to sign out cannot be read.";

	/**
	 * The message a request for an image above the caller's clearance is refused with, see issue
	 * #46.
	 *
	 * <p>
	 * The refusal names no privacy level: that the image exists is already visible from the path,
	 * but how far it is restricted is not the caller's business. It is a refusal, never a
	 * <code>404</code>: nothing here lies about what is there.
	 * </p>
	 */
	public static final String IMAGE_REFUSED =
		"This image is not available to you. Signing in may give access to it.";

	/** The message a request with an unknown <code>viewAs</code> value is refused with. */
	public static final String VIEW_AS_REFUSED =
		"Unknown 'viewAs' value; use 'public' or 'members'.";

	/**
	 * The message a thumbnail request is answered with when the preview cannot be built.
	 *
	 * <p>
	 * A failure of the preview generator is a failure of this server, not a missing file: the
	 * former <code>404</code> told the app the image was gone and hid every such failure in the
	 * server's log (issue #68). What is missing is answered with a <code>404</code> before the
	 * preview is ever asked for, see {@link #doGet(HttpServletRequest, HttpServletResponse)}.
	 * </p>
	 */
	public static final String PREVIEW_FAILED = "The preview of this image cannot be created.";

	/**
	 * The message a request for a video rendition that is not made yet is answered with.
	 *
	 * <p>
	 * A transcode takes minutes, so no request ever waits for one: the answer is
	 * <code>202 Accepted</code> with a <code>Retry-After</code>, and the work is queued, see
	 * {@link VideoRenditions}.
	 * </p>
	 */
	public static final String RENDITION_PENDING = "This video is being prepared; please try again shortly.";

	/** The message a request for a video rendition that cannot be made is answered with. */
	public static final String RENDITION_FAILED = "This video cannot be prepared for playback.";

	/** How long a caller waits before asking for a pending rendition again, in seconds. */
	public static final String RETRY_AFTER_SECONDS = "10";

	/**
	 * What a rendition request is answered with on a server that cannot transcode at all.
	 *
	 * <p>
	 * The reason follows: on a machine without the system libraries the bundled FFmpeg links, the
	 * program does not load, and saying so once is better than queueing work that can never be
	 * done (issue #81).
	 * </p>
	 */
	public static final String RENDITIONS_UNAVAILABLE = "Video renditions are not available on this server: ";

	/** What a request for the retired sharing grants of issue #49 is answered with. */
	public static final String RETIRED_GRANTS =
		"Sharing with a user is done by inviting them into the space: see the users of the space "
			+ "and invite whom you want in, with the role and clearance they should have.";

	/** What a request for the retired user groups of issue #49 is answered with. */
	public static final String RETIRED_GROUPS =
		"Groups are gone: a permission belongs to a user now. Invite the people you meant "
			+ "individually, each with the role and clearance they should have.";

	/** What a request for the retired shared-album links of issue #50 is answered with. */
	public static final String RETIRED_LINKS =
		"An album is no longer shared into somebody's own tree: invite them into this space, or "
			+ "hand them a share link.";

	/** What a request for the retired guest promotion of issue #52 is answered with. */
	public static final String RETIRED_PROMOTE =
		"There are no guests any more: a guest is a user with the 'view' role and a low clearance. "
			+ "Change what they may do with 'set-permission'.";

	/** The message a management request from somebody who is not an administrator is refused with. */
	public static final String ADMIN_ONLY = "Only an administrator of this space may do that.";

	/** The message an unreadable permission or user name is refused with. */
	public static final String PERMISSION_UNREADABLE = "The request cannot be read.";

	/** The message an invitation naming a clearance this build does not know is refused with. */
	public static final String CLEARANCE_REFUSED = "Unknown clearance; use one of " + Clearances.names() + ".";

	static {
		LOG.info("Loading: " + ExifReaderPatch.class);
	}

	private Path _basePath;
	private ResourceCache _cache;

	/** The space this servlet serves, see {@link #ImageServlet(File, AuthService, String)}. */
	private final String _space;

	/** The transcoded sidecars of the videos this server shows, see issue #74. */
	private final VideoRenditions _videos = new VideoRenditions();

	/** The video renditions of this server, for the tests that wait for a transcode. */
	VideoRenditions videos() {
		return _videos;
	}

	private PrivacyFilter _privacy;


	private JakartaServletFileUpload<UploadItem, UploadFactory> _fileUpload;

	private final AuthService _auth;

	/** Who may do what here; the users, devices and codes of the space this servlet serves. */
	AuthService auth() {
		return _auth;
	}

	/**
	 * Creates a {@link ImageServlet} serving every request without authentication.
	 *
	 * @param basePath The root path of the photo album to serve.
	 */
	public ImageServlet(File basePath) throws IOException {
		this(basePath, AuthService.disabled());
	}

	/**
	 * Creates a {@link ImageServlet}.
	 *
	 * @param basePath The root path of the photo album to serve.
	 * @param auth Decides who may read and who may write, see {@link AuthService}.
	 */
	public ImageServlet(File basePath, AuthService auth) throws IOException {
		this(basePath, auth, "");
	}

	/**
	 * Creates an {@link ImageServlet} serving one space of a multi-space server (issue #82).
	 *
	 * @param space
	 *        The first path segment this space is addressed by, the empty string on a
	 *        single-space server. It is what <code>?type=auth</code> names, so that the app knows
	 *        which space it is talking to.
	 */
	public ImageServlet(File basePath, AuthService auth, String space) throws IOException {
		_space = space == null ? "" : space;
		_basePath = basePath.toPath();
		_cache = new ResourceCache();
		_privacy = new PrivacyFilter(_cache);
		_auth = auth;
	}

	@Override
	public void init() throws ServletException {
		super.init();

		File repository = new File(_basePath.toFile(), ".upload");
		repository.mkdirs();

		_fileUpload = new JakartaServletFileUpload<UploadItem, UploadFactory>(new UploadFactory(repository));
	}

	/**
	 * Gives the directory watcher of the {@link ResourceCache} back to the operating system.
	 *
	 * <p>
	 * A server holds one servlet and one cache, but a test builds many; without this, every one of
	 * them would keep an <code>inotify</code> instance until the process ends.
	 * </p>
	 */
	@Override
	public void destroy() {
		_videos.shutdown();
		try {
			_cache.close();
		} catch (IOException ex) {
			LOG.log(Level.WARNING, "Cannot close the resource cache: " + ex.getMessage(), ex);
		}
		super.destroy();
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		String pathInfo = request.getPathInfo();
		Context context = new Context(request, response);

		String type = context.getParameter("type");

		Caller caller = _auth.caller(request);
		if (gone(context, caller)) {
			return;
		}
		if ("auth".equals(type)) {
			if (caller.getInvitationGone() != null) {
				// The one endpoint an invitation token reaches, and the one place it is told what
				// became of the invitation instead of simply being nobody, see issue #52.
				LOG.warning("Refusing the invitation at '" + pathInfo + "': " + caller.getInvitationGone());
				errorInfo(context, HttpServletResponse.SC_GONE, caller.getInvitationGone());
				return;
			}
			// Always answerable: this is how an unpaired app learns that it must pair.
			serveJsonObject(response, _auth.authInfo(caller, _basePath, _space));
			return;
		}
		if ("invitations".equals(type)) {
			serveInvitations(context, caller);
			return;
		}
		if ("users".equals(type)) {
			serveUsers(context, caller);
			return;
		}
		if ("groups".equals(type)) {
			retired(context, RETIRED_GROUPS);
			return;
		}
		if ("grants".equals(type)) {
			retired(context, RETIRED_GRANTS);
			return;
		}
		if ("links".equals(type)) {
			retired(context, RETIRED_LINKS);
			return;
		}
		if ("devices".equals(type)) {
			serveDevices(context, caller);
			return;
		}

		int viewAs;
		try {
			viewAs = Privacy.viewAs(context.getParameter(Privacy.VIEW_AS_PARAMETER));
		} catch (IllegalArgumentException ex) {
			LOG.warning("Rejecting the unknown 'viewAs' value '" + ex.getMessage() + "'.");
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, VIEW_AS_REFUSED);
			return;
		}

		Location location = resolve(context, caller);
		if (location == null) {
			return;
		}
		PathInfo resourcePath = location.getPath();

		if ("shares".equals(type)) {
			serveShares(context, caller, location);
			return;
		}

		// A caller the mode shuts out and no grant lets in is told so before the disk is touched:
		// what lies at the path is not their business, not even whether anything does.
		if (!_auth.readAllowed(caller) && _auth.rights(caller, resourcePath).isEmpty()) {
			unauthorized(context, caller, false);
			return;
		}

		File file = resourcePath.toFile();
		if (!file.exists()) {
			error404(context);
			return;
		}

		if (file.isDirectory()) {
			// The "view as" of the request survives the redirect, or the preview would jump back.
			String query = "/?type=" + type + viewAsQuery(context);
			if (pathInfo == null) {
				sendRedirect(response, request.getContextPath() + request.getServletPath() + query);
				return;
			}
			if (!pathInfo.endsWith("/")) {
				sendRedirect(response, request.getContextPath() + request.getServletPath() + pathInfo + query);
				return;
			}

			if (!_auth.mayView(caller, resourcePath)) {
				refuse(context, caller, resourcePath, Rights.VIEW, false);
				return;
			}
				// "View as" only ever lowers: it is safe for anybody to send, see Privacy#viewAs(String).
			int clearance = Math.min(_auth.clearance(caller, resourcePath), viewAs);
			serveFolder(context, resourcePath, clearance, viewAs, caller);
		} else if (ResourceCache.isImage(file)) {
			// The description and the thumbnail are looking; the original file is taking a copy.
			// A rendition is looking, exactly as the thumbnail is; only the original is taking a copy.
			String right = jsonRequested(context) || "tn".equals(type) || VideoRenditions.Kind.PLAYBACK.parameter().equals(type)
				|| VideoRenditions.Kind.TEASER.parameter().equals(type) ? Rights.VIEW : Rights.DOWNLOAD;
			if (!_auth.rights(caller, resourcePath).contains(right)) {
				refuse(context, caller, resourcePath, right, false);
				return;
			}
			int clearance = Math.min(_auth.clearance(caller, resourcePath), viewAs);
			serveImage(context, resourcePath, caller, clearance, _auth.minRating(caller));
		} else {
			error404(context);
		}
	}

	/** The <code>viewAs</code> parameter of the current request, ready to be appended to a URL. */
	private static String viewAsQuery(Context context) {
		String value = context.getParameter(Privacy.VIEW_AS_PARAMETER);
		return value == null ? "" : "&" + Privacy.VIEW_AS_PARAMETER + "=" + value;
	}

	private void sendRedirect(HttpServletResponse response, String location) throws IOException {
		LOG.log(Level.INFO, "Redirecting to: " + location);

		response.setHeader("Access-Control-Allow-Origin", "*");
		response.sendRedirect(location);
	}

	@Override
	protected void doPut(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		String pathInfo = request.getPathInfo();
		Context context = new Context(request, response);

		Caller caller = _auth.caller(request);
		if (gone(context, caller)) {
			return;
		}
		Location location = resolve(context, caller);
		if (location == null) {
			return;
		}
		PathInfo resourcePath = location.getPath();

		// Whether this caller may change anything here at all; which right in particular is asked
		// for below, where it is known what the request wants to do.
		if (!_auth.writeAllowed(caller) && !_auth.mayContribute(caller, resourcePath)) {
			unauthorized(context, caller, true);
			return;
		}

		String contentType = context.request().getContentType();
		if (contentType == null) {
			LOG.warning("Missing content type in PUT to '" + pathInfo + "'.");
			error(context, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
			return;
		}
		MimeType mimeType;
		try {
			mimeType = new MimeType(contentType);
		} catch (MimeTypeParseException ex) {
			LOG.warning("Invalid content type: " + contentType);
			error(context, HttpServletResponse.SC_BAD_REQUEST);
			return;
		}
		String baseType = mimeType.getBaseType();

		File file = resourcePath.toFile();
		if (!file.isDirectory()) {
			File parent = file.getParentFile();
			if (parent == null || !parent.isDirectory()) {
				error404(context);
				return;
			}
			PathInfo folder = resourcePath.parent();

			if (baseType.equals("application/json")) {
				if (file.exists()) {
					// A file is not a folder; its sidecar belongs to the folder around it.
					error(context, HttpServletResponse.SC_METHOD_NOT_ALLOWED);
					return;
				}
				// Creating an album changes the folder it lands in.
				if (!_auth.mayEdit(caller, folder)) {
					refuse(context, caller, folder, Rights.EDIT, true);
					return;
				}
				createAlbum(context, location, resourcePath);
				return;
			}

			if (!_auth.mayContribute(caller, folder)) {
				refuse(context, caller, folder, Rights.CONTRIBUTE, true);
				return;
			}
			storeSingleImage(context, caller, folder, file);
			return;
		}

		if (baseType.equals("multipart/form-data")) {
			if (!_auth.mayContribute(caller, resourcePath)) {
				refuse(context, caller, resourcePath, Rights.CONTRIBUTE, true);
				return;
			}
			storeUploads(context, caller, resourcePath);
			return;
		}

		if (!baseType.equals("application/json")) {
			LOG.warning("Unsupported content type: " + contentType);
			error(context, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
			return;
		}

		if (!_auth.mayEdit(caller, resourcePath)) {
			refuse(context, caller, resourcePath, Rights.EDIT, true);
			return;
		}
		storeFolder(context, resourcePath);
	}

	/**
	 * Resolves the path of the current request against a user's space, the one place it happens.
	 *
	 * <p>
	 * The data root is a folder like any other: an empty path (<code>PUT /data</code> and
	 * <code>PUT /data/</code>) addresses the space root itself and must be able to store its
	 * <code>index.json</code>.
	 * </p>
	 *
	 * <p>
	 * A path whose first segment starts with <code>~</code> addresses another user's space, every
	 * other path the caller's own, see {@link AuthService#resolve(Caller, Path, String)}. Reaching
	 * a path is never a permission to do anything with it: the rights are asked for separately, on
	 * every endpoint.
	 * </p>
	 *
	 * @return <code>null</code> if the path cannot be resolved; the response is completed with a
	 *         speaking refusal in that case.
	 */
	private Location resolve(Context context, Caller caller) throws IOException {
		String pathInfo = context.request().getPathInfo();
		String relativePath = pathInfo == null || pathInfo.isEmpty() ? "" : pathInfo.substring(1);
		try {
			return _auth.resolve(caller, _basePath, relativePath);
		} catch (PathRefused ex) {
			LOG.warning("Refusing the path '" + pathInfo + "': " + ex.getMessage());
			errorInfo(context, ex.getStatus(), ex.getMessage());
			return null;
		}
	}

	/**
	 * Refuses the request for want of a right, naming the right that is missing.
	 *
	 * <p>
	 * An anonymous caller is answered with <code>401</code> and the challenge that says how to get
	 * further; a signed-in caller with <code>403</code> and the right they do not hold, because
	 * signing in again would not help them. Nothing declines silently, see
	 * {@link AuthService#refusal(Caller, String, boolean)}.
	 * </p>
	 */
	private void refuse(Context context, Caller caller, PathInfo path, String right, boolean write)
			throws IOException {
		String message = _auth.refusal(caller, right, write);
		LOG.warning("Refusing '" + right + "' on '" + context.request().getPathInfo() + "': " + message);
		if (identified(caller)) {
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, message);
		} else {
			context.response().setHeader("WWW-Authenticate", "Bearer");
			errorInfo(context, HttpServletResponse.SC_UNAUTHORIZED, message);
		}
	}

	/**
	 * Creates a new album folder with the request body as its <code>index.json</code>.
	 *
	 * <p>
	 * The folder the client asks for is not necessarily the folder the album ends up in: when the
	 * folder above carries a placement rule, the album is filed into its year (or month) folder,
	 * see issue #48. The answer is a {@link CreateResult} naming the path the album really has, so
	 * that the client can go there instead of looking where it is not — spelled in the coordinates
	 * of the request that asked, the canonical form of another user's library included, see
	 * {@link Location#spell(String)}.
	 * </p>
	 */
	private void createAlbum(Context context, Location location, PathInfo resourcePath) throws IOException {
		byte[] contents = readBody(context.request());
		FolderResource resource = checkFolderResource(context, contents);
		if (resource == null) {
			return;
		}

		File asked = resourcePath.toFile();
		File parent = asked.getParentFile();
		String name = asked.getName();

		// The album is filed by what is written on it: the date the request carries, else the date
		// in the folder name it asks for.
		PlacementRule rule = PlacementRule.of(parent);
		File placement;
		try {
			placement = PlacementRule.isPlacementFolder(name) ? null
				: rule.create(parent, AlbumDate.ofFolder(resource, name));
		} catch (IOException ex) {
			LOG.log(Level.WARNING, "Cannot file the new album '" + name + "': " + ex.getMessage(), ex);
			errorInfo(context, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ex.getMessage());
			return;
		}

		PathInfo createdPath = resourcePath;
		String message = "";
		if (placement != null) {
			PathInfo folderPath = resourcePath.parent();
			for (Path segment : parent.toPath().relativize(placement.toPath())) {
				folderPath = folderPath.child(segment.toString());
			}
			createdPath = folderPath.child(name);
			message = PlacementRule.filedIn(relative(parent, placement));
		}

		File folder = createdPath.toFile();
		if (folder.exists()) {
			LOG.warning("Refusing to create the album '" + folder.getAbsolutePath() + "': the name is taken.");
			errorInfo(context, HttpServletResponse.SC_CONFLICT, MoveService.nameTaken(name));
			return;
		}
		if (!folder.mkdirs()) {
			LOG.warning("Cannot create path: " + folder.getAbsolutePath());
			error(context, HttpServletResponse.SC_BAD_REQUEST);
			return;
		}

		try (FileOutputStream out = new FileOutputStream(new File(folder, "index.json"))) {
			out.write(stored(contents, resource));
		}

		// The listing above shows the new album, and it may show a new year folder, too.
		_cache.invalidateTree(resourcePath.parent());

		LOG.info("Created album: " + folder.getAbsolutePath());
		serveJsonObject(context.response(),
			CreateResult.create().setPath(location.spell(createdPath.relativePath())).setMessage(message));
	}

	/** The path of the given folder below the given one, with <code>/</code> as separator. */
	private static String relative(File folder, File descendant) {
		return folder.toPath().relativize(descendant.toPath()).toString().replace(File.separatorChar, '/');
	}

	/**
	 * Stores a single uploaded image at the requested path.
	 *
	 * <p>
	 * Originals are sacred, so this never replaces anything. The received contents are hashed: if
	 * the folder already holds them, nothing is written and the answer names the file that has
	 * them; if the requested name is taken by <em>different</em> contents, the request is refused
	 * with a <code>409</code>, see {@link #REPLACE_REFUSED}.
	 * </p>
	 */
	private void storeSingleImage(Context context, Caller caller, PathInfo folderPath, File target)
			throws IOException {
		String name = target.getName();
		if (!PreviewCache.SUPPORTED_EXTENSIONS.contains(extension(name))) {
			LOG.warning("Unsupported upload extension: " + name);
			error(context, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
			return;
		}

		List<UploadItem> uploads = _fileUpload.parseRequest(context.request());
		if (uploads.size() != 1) {
			LOG.warning("Tried to upload multiple files to a single image location " + name + ": "
				+ uploads.stream().map(u -> u.getName()).collect(Collectors.joining(", ")));
			discard(uploads);
			error(context, HttpServletResponse.SC_BAD_REQUEST);
			return;
		}

		UploadItem upload = uploads.get(0);
		File folder = target.getParentFile();
		HashCache hashes = new HashCache(folder);
		UploadResult result;
		try {
			String hash = HashCache.sha256(upload.getUpload());
			String existing = hashes.nameOf(hash);
			if (existing != null) {
				upload.delete();
				LOG.info("Upload of '" + name + "' is already present as '" + existing + "'.");
				result = UploadResult.create().addFile(uploaded(name, existing, hash, PRESENT));
			} else if (target.exists()) {
				upload.delete();
				LOG.warning("Refusing to replace '" + target.getAbsolutePath() + "' with other contents.");
				errorInfo(context, HttpServletResponse.SC_CONFLICT, REPLACE_REFUSED);
				return;
			} else {
				store(upload, target);
				hashes.put(target, hash, attribution(caller));
				LOG.info("Storing image: " + target);
				result = UploadResult.create().addFile(uploaded(name, name, hash, STORED));
			}
		} finally {
			hashes.flush();
		}

		// The next read must see the new photo, and with it who contributed it.
		_cache.invalidate(folderPath);

		serveJsonObject(context.response(), result);
	}

	/**
	 * Who to record as the contributor of what the given caller uploads, see issue #53.
	 *
	 * <p>
	 * The caller's {@link Caller#subject() subject} — the very string a grant is made out to — and
	 * the label to show for it. Both are copied into the hash sidecar at the upload and never
	 * looked up again: a share link that is renamed or withdrawn afterwards still says who
	 * contributed, and a user who is renamed keeps what they brought.
	 * </p>
	 */
	private static HashCache.Attribution attribution(Caller caller) {
		if (caller == null) {
			return HashCache.Attribution.NONE;
		}
		return new HashCache.Attribution(caller.subject(), caller.contributorLabel());
	}


	/**
	 * Stores the files of a multipart upload in the given folder.
	 *
	 * <p>
	 * Every received file is hashed and compared with the contents the folder already holds: a
	 * duplicate is dropped and reported as {@link #PRESENT}, so that an upload retried after a
	 * lost connection never creates a second copy. Contents that are new are stored under a
	 * de-duplicated name; an existing file is never overwritten.
	 * </p>
	 *
	 * <p>
	 * This is where the attribution of issue #53 is recorded: every file that is actually stored
	 * gets the uploader's {@link Caller#subject() subject} and label in the hash sidecar beside
	 * it. A file reported as {@link #PRESENT} writes nothing, so it keeps the <em>first</em>
	 * contributor — whoever brought the photo here is who brought it here, however often it is
	 * sent again.
	 * </p>
	 */
	private void storeUploads(Context context, Caller caller, PathInfo folderPath) throws IOException {
		File folder = folderPath.toFile();
		List<UploadItem> uploads = _fileUpload.parseRequest(context.request());
		for (UploadItem upload : uploads) {
			String name = baseName(upload.getName());
			if (!PreviewCache.SUPPORTED_EXTENSIONS.contains(extension(name))) {
				LOG.warning("Unsupported upload extension: " + name);
				// Nothing is stored: an upload is accepted as a whole or not at all.
				discard(uploads);
				error(context, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
				return;
			}
		}

		UploadResult result = UploadResult.create();
		HashCache hashes = new HashCache(folder);
		try {
			for (UploadItem upload : uploads) {
				String name = baseName(upload.getName());
				String hash = HashCache.sha256(upload.getUpload());

				String existing = hashes.nameOf(hash);
				if (existing != null) {
					upload.delete();
					LOG.info("Upload of '" + name + "' is already present as '" + existing + "'.");
					result.addFile(uploaded(name, existing, hash, PRESENT));
					continue;
				}

				File targetFile = freeName(folder, name);
				store(upload, targetFile);
				hashes.put(targetFile, hash, attribution(caller));
				LOG.info("Storing image: " + targetFile);
				result.addFile(uploaded(name, targetFile.getName(), hash, STORED));
			}
		} finally {
			hashes.flush();
		}

		// The next read must see the new photos, and with them who contributed them.
		_cache.invalidate(folderPath);

		serveJsonObject(context.response(), result);
	}

	/**
	 * Moves entries of the addressed folder into another folder, see issue #47.
	 *
	 * <p>
	 * A move is a write: it is refused exactly as a PUT is when the caller may not write, and the
	 * source and the target must both lie in the caller's space. Beyond that, the target folder
	 * must grant the contribute right, and the source folder the edit right — <em>or</em> the
	 * caller must be the contributor of every entry the request names, see issue #53 and
	 * {@link MoveService#contributedBy(PathInfo, List, String)}: whoever added a photo to somebody
	 * else's album may take it back out again, and nobody's else. Taking back is never a delete:
	 * the photo is renamed into a folder of the contributor's, exactly as any other move.
	 * </p>
	 *
	 * <p>
	 * A refusal that concerns the request as a whole is an {@link ErrorInfo}; a refusal that
	 * concerns one named entry is a {@link MoveOutcome#getMessage() message} in an otherwise
	 * successful answer, because the other entries did move.
	 * </p>
	 */
	private void moveEntries(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		Location sourceLocation = resolve(context, caller);
		if (sourceLocation == null) {
			return;
		}
		PathInfo source = sourceLocation.getPath();
		if (!_auth.writeAllowed(caller) && !_auth.mayContribute(caller, source)) {
			unauthorized(context, caller, true);
			return;
		}

		MoveRequest moveRequest;
		try {
			byte[] contents = readBody(context.request());
			moveRequest = MoveRequest.readMoveRequest(new JsonReader(
				new ReaderAdapter(new InputStreamReader(new ByteArrayInputStream(contents), StandardCharsets.UTF_8))));
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting unparsable move request: " + ex.getMessage());
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, MoveService.MOVE_UNREADABLE);
			return;
		}

		// The target is given in the same coordinates as any other path, the canonical form of
		// another user's space included: there is one way to name a folder.
		PathInfo target;
		try {
			target = _auth.resolve(caller, _basePath, moveRequest.getTarget() == null ? "" : moveRequest.getTarget())
				.getPath();
		} catch (PathRefused ex) {
			LOG.warning("Refusing the move target '" + moveRequest.getTarget() + "': " + ex.getMessage());
			errorInfo(context, HttpServletResponse.SC_NOT_FOUND,
				AuthService.PATH_ESCAPED.equals(ex.getMessage()) ? MoveService.TARGET_ESCAPED : ex.getMessage());
			return;
		}

		List<String> names = moveRequest.getNames().stream().map(MoveName::getName).collect(Collectors.toList());
		MoveService moveService = new MoveService(_basePath, _cache, _auth);

		// The source rule of issue #53: the edit right, or one's own contribution and nothing
		// else. A share link never gets that far — it has no album to take anything back into.
		if (!_auth.mayEdit(caller, source)) {
			if (caller.isShareLink()) {
				// Not the generic "this link does not allow changes here": the link may well
				// allow adding photos. What it has not got is anywhere to move one to.
				LOG.warning("Refusing the move through a share link at '" + context.request().getPathInfo()
					+ "': " + AuthService.SHARE_MOVE_REFUSED);
				errorInfo(context, HttpServletResponse.SC_FORBIDDEN, AuthService.SHARE_MOVE_REFUSED);
				return;
			}
			if (!moveService.contributedBy(source, names, caller.subject())) {
				refuseMove(context, caller, MoveService.CONTRIBUTION_REFUSED, true);
				return;
			}
		}
		if (!_auth.mayContribute(caller, target)) {
			refuseMove(context, caller, MoveService.CONTRIBUTE_REFUSED, true);
			return;
		}

		MoveResult result;
		try {
			result = moveService.move(source, target, names);
		} catch (MoveRefused ex) {
			LOG.warning("Refusing to move from '" + context.request().getPathInfo() + "': " + ex.getMessage());
			errorInfo(context, ex.getStatus(), ex.getMessage());
			return;
		}

		serveJsonObject(context.response(), result);
	}

	/**
	 * Refuses a move, with the message that names the folder the caller may not touch.
	 *
	 * <p>
	 * A caller that is nobody yet is told how to become somebody instead, exactly as every other
	 * refusal does, see {@link AuthService#refusal(Caller, boolean)}.
	 * </p>
	 */
	private void refuseMove(Context context, Caller caller, String message, boolean write) throws IOException {
		if (caller.isPaired()) {
			LOG.warning("Refusing the move at '" + context.request().getPathInfo() + "': " + message);
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, message);
		} else {
			unauthorized(context, caller, write);
		}
	}

	/**
	 * Files what is already in the addressed folder by that folder's placement rule, see issue #48.
	 *
	 * <p>
	 * The "apply once" of a rule, and never anything else: nothing here happens implicitly, on a
	 * read or at start-up. It is a write on the folder itself — every child is renamed — so it is
	 * refused exactly as a move out of that folder is, and it answers with the outcomes a move
	 * answers with, see {@link MoveService#place(PathInfo)}.
	 * </p>
	 */
	private void placeEntries(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		Location location = resolve(context, caller);
		if (location == null) {
			return;
		}
		PathInfo folder = location.getPath();
		if (!_auth.writeAllowed(caller) && !_auth.mayContribute(caller, folder)) {
			unauthorized(context, caller, true);
			return;
		}
		if (!_auth.mayEdit(caller, folder)) {
			refuseMove(context, caller, MoveService.EDIT_REFUSED, true);
			return;
		}

		MoveResult result;
		try {
			result = new MoveService(_basePath, _cache, _auth).place(folder);
		} catch (MoveRefused ex) {
			LOG.warning("Refusing to apply the placement rule of '" + context.request().getPathInfo() + "': "
				+ ex.getMessage());
			errorInfo(context, ex.getStatus(), ex.getMessage());
			return;
		}

		serveJsonObject(context.response(), result);
	}

	/**
	 * Answers which of the asked contents the addressed folder already holds.
	 *
	 * <p>
	 * A client uses it to skip transferring what is already there, so it asks for what an upload
	 * asks for: the contribute right, see {@link AuthService#mayContribute(Caller, PathInfo)}. The
	 * upload itself is idempotent in any case, see {@link #storeUploads(Context, PathInfo)}.
	 * </p>
	 */
	private void checkUploads(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		Location location = resolve(context, caller);
		if (location == null) {
			return;
		}
		PathInfo resourcePath = location.getPath();

		// Contributing is what the answer is for. In the caller's own space, and in the
		// single-user library mode WRITES keeps open, it stays the plain read it has been since
		// issue #29 — that library has no grants and never had this endpoint closed.
		// Contributing is what the answer is for, but it says no more than a listing does — which
		// of these photos are already here — so looking is enough to ask it.
		if (!_auth.mayContribute(caller, resourcePath) && !_auth.mayView(caller, resourcePath)) {
			refuse(context, caller, resourcePath, Rights.CONTRIBUTE, false);
			return;
		}

		File folder = resourcePath.toFile();
		if (!folder.isDirectory()) {
			error404(context);
			return;
		}

		UploadCheck check;
		try {
			byte[] contents = readBody(context.request());
			check = UploadCheck.readUploadCheck(new JsonReader(
				new ReaderAdapter(new InputStreamReader(new ByteArrayInputStream(contents), StandardCharsets.UTF_8))));
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting unparsable upload check: " + ex.getMessage());
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, CHECK_UNREADABLE);
			return;
		}

		HashCache hashes = new HashCache(folder);
		Map<String, String> nameByHash;
		try {
			nameByHash = hashes.nameByHash();
		} finally {
			hashes.flush();
		}

		UploadCheckResult result = UploadCheckResult.create();
		for (ContentHash asked : check.getHashes()) {
			String name = nameByHash.get(asked.getHash());
			if (name != null) {
				result.addPresent(PresentFile.create().setHash(asked.getHash()).setName(name));
			}
		}

		serveJsonObject(context.response(), result);
	}

	/** Deletes the temporary files of an upload that is not stored. */
	private static void discard(List<UploadItem> uploads) {
		for (UploadItem upload : uploads) {
			upload.delete();
		}
	}

	/**
	 * A file with the given name in the given folder that does not exist yet.
	 *
	 * <p>
	 * A name clash is resolved by appending a number: nothing is ever overwritten. This is the one
	 * naming rule of this server: a move that collides at its target renames exactly like an
	 * upload does, see {@link MoveService}.
	 * </p>
	 */
	static File freeName(File folder, String fileName) {
		File targetFile = new File(folder, fileName);
		if (!targetFile.exists()) {
			return targetFile;
		}

		String extension = extension(fileName);
		String baseName = fileName.substring(0, fileName.length() - extension.length() - 1);
		int num = 2;
		do {
			targetFile = new File(folder, baseName + "-" + num + "." + extension);
			num++;
		} while (targetFile.exists());
		return targetFile;
	}

	/**
	 * Moves the received contents to their place in the album.
	 *
	 * <p>
	 * The target must not exist: the move fails rather than replacing an original.
	 * </p>
	 */
	private static void store(UploadItem upload, File target) throws IOException {
		Files.move(upload.getUpload().toPath(), target.toPath());
	}

	private static UploadedFile uploaded(String name, String storedAs, String hash, String status) {
		return UploadedFile.create().setName(name).setStoredAs(storedAs).setHash(hash).setStatus(status);
	}

	/**
	 * Answers the users of this server at <code>&lt;data&gt;/?type=users</code>, see issue #49.
	 *
	 * <p>
	 * Their names and roles, and since issue #55 their space, the day they arrived and how many
	 * devices they use: a member needs the names to share something, and the rest is what a
	 * management screen shows about people who all see each other anyway — there is nothing secret
	 * in a count. A guest is not shown the family's names, and an anonymous caller is not shown
	 * anything, see {@link AuthService#maySeeUsers(Caller)}.
	 * </p>
	 */
	private void serveUsers(Context context, Caller caller) throws IOException {
		if (!caller.isPaired()) {
			unauthorized(context, caller, false);
			return;
		}
		if (!_auth.maySeeUsers(caller)) {
			LOG.warning("Refusing the user list to '" + caller.getUserName() + "'.");
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, AuthService.USERS_REFUSED);
			return;
		}

		UserList result = UserList.create();
		UserStore users = _auth.getUsers();
		if (users != null) {
			for (UserStore.User user : users.getUsers()) {
				if (user.getName().isEmpty()) {
					// The owner of a library that was never named; there is nothing to share with.
					continue;
				}
				result.addUser(onTheWire(user));
			}
		}
		serveJsonObject(context.response(), result);
	}


	/**
	 * Answers the caller's own devices at <code>&lt;data&gt;/?type=devices</code>, see issue #55.
	 *
	 * <p>
	 * Every device the caller is signed in on, with the one that asked marked as the current one,
	 * and never a token or the hash of one: a device is named by its id, which is a name and not a
	 * secret. Only one's own — the administrator manages the users of this server, not other
	 * people's phones, and there is deliberately no endpoint that shows them.
	 * </p>
	 *
	 * <p>
	 * A share link and an invitation are refused: they are tokens, not sign-ins, and have no
	 * devices. The share link is told so (<code>403</code>); an invitation is anonymous on every
	 * endpoint but <code>?type=auth</code> and is answered <code>401</code> like anybody else who
	 * has not signed in, which is also the right answer — pairing is exactly what it should do.
	 * </p>
	 */
	private void serveDevices(Context context, Caller caller) throws IOException {
		if (caller.isShareLink()) {
			LOG.warning("Refusing the device list to the share link '" + caller.getShareLabel() + "'.");
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, AuthService.DEVICES_REFUSED);
			return;
		}
		if (!caller.isPaired()) {
			unauthorized(context, caller, false);
			return;
		}
		serveJsonObject(context.response(), devices(caller));
	}

	/**
	 * Signs a device of the caller out at <code>&lt;data&gt;/?action=unpair</code>, see issue #55.
	 *
	 * <p>
	 * The body names the device by its {@link DeviceEntry#getId() id} and nothing else. Only the
	 * caller's own devices can be named: an id of somebody else's is refused exactly like an id
	 * nobody has, so that the endpoint says nothing about who holds what. Signing out the asking
	 * device is allowed and is the point of it — the answer still arrives, and the next request
	 * carrying that token is refused.
	 * </p>
	 *
	 * <p>
	 * The answer is what is left: the caller's remaining devices, as <code>?type=devices</code>
	 * answers them.
	 * </p>
	 */
	private void unpairDevice(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		if (caller.isShareLink()) {
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, AuthService.DEVICES_REFUSED);
			return;
		}
		if (!caller.isPaired()) {
			unauthorized(context, caller, true);
			return;
		}

		DeviceEntry request;
		try {
			byte[] contents = readBody(context.request());
			request = DeviceEntry.readDeviceEntry(new JsonReader(
				new ReaderAdapter(new InputStreamReader(new ByteArrayInputStream(contents), StandardCharsets.UTF_8))));
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting an unparsable unpair request: " + ex.getMessage());
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, DEVICE_UNREADABLE);
			return;
		}

		UserStore.Device device;
		try {
			device = _auth.unpair(caller, request.getId());
		} catch (AuthService.Refused ex) {
			LOG.warning("Refusing to sign out the device '" + request.getId() + "': " + ex.getMessage());
			errorInfo(context, ex.getStatus(), ex.getMessage());
			return;
		}
		LOG.info("Signed the device '" + device.getName() + "' of '" + caller.getUserName() + "' out.");
		serveJsonObject(context.response(), devices(caller));
	}

	/**
	 * Issues a code for a further device of the caller at
	 * <code>&lt;data&gt;/?action=device-code</code>, see issue #65.
	 *
	 * <p>
	 * Adding a device of one's own is not inviting somebody, and it is built so that it can never
	 * be mistaken for it: the answer is a code to type, not a link to send — no URL, no path
	 * segment, and nothing that is a bearer anywhere. It lives ten minutes, it works once, and
	 * whoever types it is signed in <em>as the caller</em>, which is why a device typing it appears
	 * in <code>?type=devices</code> at once and can be signed out there.
	 * </p>
	 *
	 * <p>
	 * The request needs no body: everything the code says — whom it signs in and which device
	 * asked — the server already knows from the token. A body may name somebody else
	 * ({@link DeviceCodeRequest}), which only an administrator may do: the <em>recovery code</em>
	 * of issue #89, for a person who lost every device they had. A share link is refused
	 * (<code>403</code>): it is nobody, and there is nobody for it to add a device to. An
	 * anonymous caller and an invitation bearer are answered <code>401</code>, exactly as
	 * <code>?type=devices</code> answers them.
	 * </p>
	 */
	private void createDeviceCode(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		if (_auth.getMode() == AuthMode.OFF) {
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, AuthService.PAIRING_DISABLED);
			return;
		}
		if (caller.isShareLink()) {
			LOG.warning("Refusing a device code to the share link '" + caller.getShareLabel() + "'.");
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, AuthService.DEVICE_CODE_REFUSED);
			return;
		}
		if (!caller.isPaired()) {
			unauthorized(context, caller, true);
			return;
		}

		String userName;
		try {
			userName = deviceCodeTarget(context.request());
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting an unreadable device code request: " + ex.getMessage());
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, DEVICE_CODE_UNREADABLE);
			return;
		}

		DeviceCodeStore.Issued issued;
		try {
			issued = _auth.deviceCode(caller, userName);
		} catch (AuthService.Refused ex) {
			LOG.warning("Refusing a device code to '" + caller.getUserName() + "': " + ex.getMessage());
			errorInfo(context, ex.getStatus(), ex.getMessage());
			return;
		}
		LOG.info("Issued " + issued.getRecord() + ".");
		serveJsonObject(context.response(), DeviceCodeCreated.create()
			.setCode(DeviceCodeStore.format(issued.getCode()))
			.setExpires(issued.getRecord().getExpires()));
	}

	/**
	 * Whom the given device-code request is for, the empty string for the caller themselves.
	 *
	 * <p>
	 * An empty body is the ordinary case and stays what it always was; a body that is a
	 * {@link DeviceCodeRequest} names the user the code signs in, see issue #89.
	 * </p>
	 */
	private static String deviceCodeTarget(HttpServletRequest request) throws IOException {
		byte[] contents = readBody(request);
		if (contents.length == 0) {
			return "";
		}
		DeviceCodeRequest parsed = DeviceCodeRequest.readDeviceCodeRequest(new JsonReader(
			new ReaderAdapter(new InputStreamReader(new ByteArrayInputStream(contents), StandardCharsets.UTF_8))));
		return parsed.getUserName() == null ? "" : parsed.getUserName();
	}

	/** The caller's own devices as the protocol carries them, the asking one marked. */
	private DeviceList devices(Caller caller) {
		DeviceList result = DeviceList.create();
		for (UserStore.Device device : _auth.devices(caller)) {
			result.addDevice(DeviceEntry.create()
				.setId(device.getId())
				.setName(device.getName())
				.setCreated(device.getCreated())
				.setCurrent(device.getId().equals(caller.getDeviceId())));
		}
		return result;
	}



	/**
	 * Answers the share links covering the addressed folder at
	 * <code>&lt;folder&gt;/?type=shares</code>, see issue #51.
	 *
	 * <p>
	 * The links on the folder and on every folder above it within the space, the nearest one first,
	 * withdrawn ones included and marked as such. Only the owner of the space and the administrator
	 * may ask, exactly as for the grants — and no answer ever carries a token: the token is shown
	 * once, when the link is made, and never again.
	 * </p>
	 */
	private void serveShares(Context context, Caller caller, Location location) throws IOException {
		if (!caller.isPaired()) {
			unauthorized(context, caller, false);
			return;
		}
		if (!_auth.mayShareLinks(caller)) {
			LOG.warning("Refusing the share links of '" + location.getOwner() + "' to '" + caller.getUserName() + "'.");
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, AuthService.SHARING_REFUSED);
			return;
		}

		ShareLinkList result = ShareLinkList.create();
		ShareStore shares = _auth.getShares();
		if (shares != null) {
			for (ShareStore.Link link : shares.covering(location.getOwner(), location.getOwnerPath())) {
				// A link belongs to whoever handed it out; an administrator of the space sees them
				// all, because keeping the space in order is what an administrator is for (#84).
				if (mine(caller, link)) {
					result.addLink(onTheWire(link));
				}
			}
		}
		serveJsonObject(context.response(), result);
	}

	/**
	 * Creates a share link on the addressed folder at <code>&lt;folder&gt;/?action=share</code>,
	 * see issue #51.
	 *
	 * <p>
	 * Two records in one step: the {@link ShareStore.Link} that says what the link shows and how
	 * long it lives, and the {@link GrantStore.Grant} to its <code>token:&lt;id&gt;</code> subject
	 * that says what it may do. The grant is the one mechanism every other sharing uses, so a link
	 * needs no second answer to "may this caller do that here".
	 * </p>
	 *
	 * <p>
	 * The token travels back exactly once, in the {@link ShareLinkCreated}: this server keeps its
	 * hash and can never show it again. A lost link is withdrawn and made anew.
	 * </p>
	 */
	private void createShare(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		Location location = resolve(context, caller);
		if (location == null) {
			return;
		}
		if (!caller.isPaired()) {
			unauthorized(context, caller, true);
			return;
		}
		if (!_auth.mayShareLinks(caller)) {
			LOG.warning("Refusing to share the library of '" + location.getOwner() + "'.");
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, AuthService.SHARING_REFUSED);
			return;
		}
		if (location.getOwner().isEmpty()) {
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, AuthService.PATH_ESCAPED);
			return;
		}

		ShareLink request = readShareLink(context);
		if (request == null) {
			return;
		}

		Set<String> rights = new LinkedHashSet<>();
		for (de.haumacher.imageServer.shared.model.RightName right : request.getRights()) {
			String name = right.getName();
			if (Rights.EDIT.equals(name)) {
				LOG.warning("Refusing a share link that would allow editing.");
				errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, AuthService.SHARE_EDIT_REFUSED);
				return;
			}
			if (!Rights.isKnown(name)) {
				LOG.warning("Refusing a share link with the unknown right '" + name + "'.");
				errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, AuthService.unknownRight(name));
				return;
			}
			rights.add(name);
		}
		if (rights.isEmpty()) {
			// A link that allows nothing would be a link to nothing; looking is the least it does.
			rights.add(Rights.VIEW);
		}

		int maxPrivacy = request.getMaxPrivacy();
		if (maxPrivacy < Privacy.PUBLIC || maxPrivacy > Privacy.PRIVATE) {
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, AuthService.SHARE_PRIVACY_REFUSED);
			return;
		}
		// Nobody hands out more than they hold, and it is said rather than quietly trimmed: a link
		// answered with a lower limit than the one asked for would be a link its maker believes
		// shows more than it does, see issue #84.
		int clearance = _auth.clearance(caller, location.getPath());
		if (maxPrivacy > clearance) {
			LOG.warning("Refusing a share link showing more than '" + caller.getUserName() + "' may see.");
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, AuthService.shareAboveClearance(clearance));
			return;
		}
		int minRating = request.getMinRating();
		if (!Ratings.isKnown(minRating)) {
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, AuthService.SHARE_RATING_REFUSED);
			return;
		}
		String expires = request.getExpires() == null ? "" : request.getExpires().trim();
		if (!expires.isEmpty()) {
			try {
				expires = java.time.Instant.parse(expires).toString();
			} catch (java.time.DateTimeException ex) {
				LOG.warning("Refusing the share link expiry '" + expires + "': " + ex.getMessage());
				errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, AuthService.SHARE_EXPIRY_REFUSED);
				return;
			}
		}
		String label = request.getLabel() == null ? "" : request.getLabel().trim();

		// The link is the permission (issue #83): what it may do is recorded on the link itself,
		// and there is no grant beside it any more.
		ShareStore.Issued issued = _auth.getShares().create(location.getOwner(), location.getOwnerPath(), label,
			expires, maxPrivacy, minRating, rights, caller.getUserName());
		ShareStore.Link link = issued.getLink();

		LOG.info("Created the share link " + link + " with " + rights + ".");
		serveJsonObject(context.response(), ShareLinkCreated.create()
			.setLink(onTheWire(link))
			.setToken(issued.getToken())
			.setUrl(shareUrl(context, issued.getToken())));
	}

	/**
	 * Withdraws a share link of the addressed folder at
	 * <code>&lt;folder&gt;/?action=unshare</code>, see issue #51.
	 *
	 * <p>
	 * The record is marked withdrawn and kept — a management screen shows what became of a link
	 * somebody handed out, see issue #55 — and the grant is removed, which is what actually closes
	 * the door: the next request with that token is answered <code>410 Gone</code>.
	 * </p>
	 */
	private void removeShare(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		Location location = resolve(context, caller);
		if (location == null) {
			return;
		}
		if (!caller.isPaired()) {
			unauthorized(context, caller, true);
			return;
		}
		if (!_auth.mayShareLinks(caller)) {
			LOG.warning("Refusing to withdraw a share link of '" + location.getOwner() + "'.");
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, AuthService.SHARING_REFUSED);
			return;
		}

		ShareLink request = readShareLink(context);
		if (request == null) {
			return;
		}
		String id = request.getId() == null ? "" : request.getId().trim();
		ShareStore shares = _auth.getShares();
		ShareStore.Link link = id.isEmpty() ? null : shares.get(id);
		if (link == null || !link.covers(location.getOwner(), location.getOwnerPath()) || !mine(caller, link)) {
			// A link of somebody else's, or of no folder above this one, is a link this request
			// never saw: it is told that there is none, not whose it is.
			LOG.warning("Refusing to withdraw the unknown share link '" + id + "'.");
			errorInfo(context, HttpServletResponse.SC_NOT_FOUND, AuthService.SHARE_UNKNOWN);
			return;
		}

		shares.revoke(link.getId());
		LOG.info("Withdrew the share link " + link + ".");
		serveJsonObject(context.response(), ShareLinkList.create().addLink(onTheWire(link)));
	}

	/**
	 * Whether the given link is the caller's business, see issue #84.
	 *
	 * <p>
	 * Their own, or anybody's if they administer the space. A link stored before the creator was
	 * recorded belongs to nobody and is an administrator's to manage.
	 * </p>
	 */
	private static boolean mine(Caller caller, ShareStore.Link link) {
		return Roles.isAdmin(caller.getRole()) || caller.getUserName().equals(link.getCreatedBy());
	}

	/** The body of a share request, <code>null</code> if it cannot be read (the response is complete). */
	private static ShareLink readShareLink(Context context) throws IOException {
		try {
			byte[] contents = readBody(context.request());
			return ShareLink.readShareLink(new JsonReader(
				new ReaderAdapter(new InputStreamReader(new ByteArrayInputStream(contents), StandardCharsets.UTF_8))));
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting unparsable share link: " + ex.getMessage());
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, SHARE_UNREADABLE);
			return null;
		}
	}

	/**
	 * The path the given token opens this server's web application at.
	 *
	 * <p>
	 * Relative to the server: the origin is the client's business (it knows which name it reached
	 * this server under), the context path is the servlet's. The static handler serves the
	 * application below this path without ever looking at the token, see
	 * {@link ShareStore#URL_SEGMENT}.
	 * </p>
	 */
	private static String shareUrl(Context context, String token) {
		return appUrl(context, ShareStore.URL_SEGMENT, token);
	}

	/**
	 * The path a token opens this server's web application at, below the given segment.
	 *
	 * <p>
	 * <code>/s/</code> for a share link (issue #51) and <code>/i/</code> for an invitation (issue
	 * #52): two tokens of different kinds, one static handler, and the same rule for both.
	 * </p>
	 *
	 * <p>
	 * A session of a space lives <em>under</em> that space (issue #82):
	 * <code>&lt;context&gt;/&lt;space&gt;/s/&lt;token&gt;/</code>, because the space is the first
	 * path segment and the app opened at the link has to find a data root — at the context root of
	 * a multi-space server there is none. The base is taken from the request itself rather than
	 * from a field, so a single-space server keeps spelling <code>&lt;context&gt;/s/&lt;token&gt;/</code>
	 * exactly as before.
	 * </p>
	 */
	private static String appUrl(Context context, String segment, String token) {
		String contextPath = context.getContextPath() == null ? "" : context.getContextPath();
		return contextPath + appBase(context) + "/" + segment + "/" + token + "/";
	}

	/**
	 * Where the web application is mounted for the request at hand, without a trailing slash.
	 *
	 * <p>
	 * The servlet path of a request is <code>/data</code> on a single-space server and
	 * <code>/&lt;space&gt;/data</code> on a multi-space one, see
	 * {@link SpaceServlet.InSpace}: what is in front of the data prefix is the application's base.
	 * </p>
	 */
	private static String appBase(Context context) {
		String servletPath = context.request().getServletPath();
		if (servletPath == null || !servletPath.endsWith(Settings.DATA_PREFIX)) {
			return "";
		}
		return servletPath.substring(0, servletPath.length() - Settings.DATA_PREFIX.length());
	}

	/**
	 * The given share link as the protocol carries it, with the rights it was created with.
	 *
	 * <p>
	 * Never the token and never its hash: what a listing shows is what the author needs to tell one
	 * link from another, see issue #51.
	 * </p>
	 */
	private ShareLink onTheWire(ShareStore.Link link) {
		Set<String> rights = link.getRights();
		return ShareLink.create()
			.setId(link.getId())
			.setCreatedBy(link.getCreatedBy())
			.setLabel(link.getLabel())
			.setExpires(link.getExpires())
			.setMaxPrivacy(link.getMaxPrivacy())
			.setMinRating(link.getMinRating())
			.setRights(Rights.onTheWire(rights))
			.setPath(AuthService.canonical(link))
			.setCreated(link.getCreated())
			.setRevoked(link.getRevoked());
	}

	/**
	 * Issues an invitation at <code>&lt;data&gt;/?action=invite</code>, see issue #52.
	 *
	 * <p>
	 * An invitation is a single-use token that <em>creates a user</em>, so it names no path and
	 * touches no album: it is answered at the data root and the request body says only what the
	 * accepting user becomes. The token travels back exactly once, in the
	 * {@link InvitationCreated}, together with the URL the app is served under for it.
	 * </p>
	 *
	 * <p>
	 * Who may: a member and the admin, unless the server was started with
	 * <code>--invite admin</code>, and then only the admin. Never a guest, and never an anonymous
	 * caller, who is refused like every other write. Whom to: a member or a guest, never an admin —
	 * the library has one owner and nobody is invited into that seat.
	 * </p>
	 */
	private void createInvitation(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		if (!caller.isPaired()) {
			unauthorized(context, caller, true);
			return;
		}
		if (!_auth.mayInvite(caller)) {
			LOG.warning("Refusing an invitation to '" + caller.getUserName() + "': only an "
				+ "administrator invites into a space.");
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, AuthService.INVITE_ADMIN_ONLY);
			return;
		}

		Invitation request = readInvitation(context);
		if (request == null) {
			return;
		}
		String requested = request.getRole() == null || request.getRole().trim().isEmpty() ? Roles.VIEW
			: request.getRole().trim();
		String role = Roles.of(requested);
		if (Roles.ADMIN.equals(role)) {
			LOG.warning("Refusing an invitation into the administrator's seat.");
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, AuthService.INVITATION_ADMIN_REFUSED);
			return;
		}
		if (role == null) {
			LOG.warning("Refusing an invitation with the unknown role '" + requested + "'.");
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, AuthService.invitationRoleRefused(requested));
			return;
		}
		String expires = request.getExpires() == null ? "" : request.getExpires().trim();
		if (!expires.isEmpty()) {
			try {
				expires = java.time.Instant.parse(expires).toString();
			} catch (java.time.DateTimeException ex) {
				LOG.warning("Refusing the invitation expiry '" + expires + "': " + ex.getMessage());
				errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, AuthService.INVITATION_EXPIRY_REFUSED);
				return;
			}
		}
		String note = request.getNote() == null ? "" : request.getNote().trim();

		// What the invitee will hold, see issue #82: what the invitation says, capped at what the
		// inviter holds themselves — nobody hands out more than they have.
		String clearance = request.getClearance() == null ? "" : request.getClearance().trim();
		if (!clearance.isEmpty() && !Clearances.isKnown(clearance)) {
			LOG.warning("Refusing an invitation with the unknown clearance '" + clearance + "'.");
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, CLEARANCE_REFUSED);
			return;
		}
		if (clearance.isEmpty()) {
			clearance = Clearances.ofRole(role);
		}
		if (Clearances.level(clearance) > Clearances.level(caller.getClearance())) {
			clearance = caller.getClearance();
		}
		boolean mayShare = request.isMayShare() && caller.mayShare();

		InvitationStore.Issued issued =
			_auth.getInvitations().create(role, clearance, mayShare, caller.getUserName(), note, expires);
		LOG.info("Issued " + issued.getInvitation() + ".");
		serveJsonObject(context.response(), InvitationCreated.create()
			.setInvitation(onTheWire(issued.getInvitation()))
			.setToken(issued.getToken())
			.setUrl(appUrl(context, InvitationStore.URL_SEGMENT, issued.getToken())));
	}

	/**
	 * Answers the invitations at <code>&lt;data&gt;/?type=invitations</code>, see issue #52.
	 *
	 * <p>
	 * The admin sees every invitation of the server, a member the ones they issued themselves:
	 * whom somebody else invited is nobody else's business, and the admin is the one who keeps the
	 * server in order. A guest and an anonymous caller see none at all, and no answer ever carries
	 * a token.
	 * </p>
	 */
	private void serveInvitations(Context context, Caller caller) throws IOException {
		if (!caller.isPaired()) {
			unauthorized(context, caller, false);
			return;
		}
		if (!Roles.isAdmin(caller.getRole())) {
			LOG.warning("Refusing the invitations to '" + caller.getUserName() + "'.");
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, AuthService.INVITATIONS_REFUSED);
			return;
		}

		InvitationList result = InvitationList.create();
		InvitationStore invitations = _auth.getInvitations();
		if (invitations != null) {
			boolean admin = Roles.ADMIN.equals(caller.getRole());
			for (InvitationStore.Link invitation : invitations.getInvitations()) {
				if (admin || invitation.getInvitedBy().equals(caller.getUserName())) {
					result.addInvitation(onTheWire(invitation));
				}
			}
		}
		serveJsonObject(context.response(), result);
	}

	/**
	 * Withdraws an invitation at <code>&lt;data&gt;/?action=uninvite</code>, see issue #52.
	 *
	 * <p>
	 * The record is marked withdrawn and kept — a management screen shows what became of an
	 * invitation somebody handed out, see issue #55 — and the token is refused from then on. A user
	 * the invitation already created is untouched: withdrawing an invitation is not removing
	 * somebody. Only the issuer and the admin may withdraw; anybody else is told that there is no
	 * such invitation, not whose it is.
	 * </p>
	 */
	private void removeInvitation(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		if (!caller.isPaired()) {
			unauthorized(context, caller, true);
			return;
		}
		if (!Roles.isAdmin(caller.getRole())) {
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, AuthService.INVITATIONS_REFUSED);
			return;
		}

		Invitation request = readInvitation(context);
		if (request == null) {
			return;
		}
		String id = request.getId() == null ? "" : request.getId().trim();
		InvitationStore invitations = _auth.getInvitations();
		InvitationStore.Link invitation = id.isEmpty() || invitations == null ? null : invitations.get(id);
		boolean mine = invitation != null && (Roles.ADMIN.equals(caller.getRole())
			|| invitation.getInvitedBy().equals(caller.getUserName()));
		if (!mine) {
			LOG.warning("Refusing to withdraw the unknown invitation '" + id + "'.");
			errorInfo(context, HttpServletResponse.SC_NOT_FOUND, AuthService.INVITATION_UNKNOWN);
			return;
		}

		invitations.revoke(invitation.getId());
		LOG.info("Withdrew " + invitation + ".");
		serveJsonObject(context.response(), InvitationList.create().addInvitation(onTheWire(invitation)));
	}


	/** The body of an invitation request, <code>null</code> if it cannot be read (the response is complete). */
	private static Invitation readInvitation(Context context) throws IOException {
		try {
			byte[] contents = readBody(context.request());
			return Invitation.readInvitation(new JsonReader(
				new ReaderAdapter(new InputStreamReader(new ByteArrayInputStream(contents), StandardCharsets.UTF_8))));
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting an unparsable invitation: " + ex.getMessage());
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, INVITATION_UNREADABLE);
			return null;
		}
	}

	/**
	 * The given invitation as the protocol carries it.
	 *
	 * <p>
	 * Never the token and never its hash: what a listing shows is what the inviter needs to tell
	 * one invitation from another and to see what became of it, see issue #52.
	 * </p>
	 */
	private static Invitation onTheWire(InvitationStore.Link invitation) {
		return Invitation.create()
			.setId(invitation.getId())
			.setRole(invitation.getRole())
			.setClearance(invitation.getClearance())
			.setMayShare(invitation.isShare())
			.setNote(invitation.getNote())
			.setExpires(invitation.getExpires())
			.setInvitedBy(invitation.getInvitedBy())
			.setCreated(invitation.getCreated())
			.setUsed(invitation.getUsed())
			.setUsedBy(invitation.getUsedBy())
			.setRevoked(invitation.getRevoked());
	}





	/**
	 * Sets what a user of this space may do, at <code>&lt;data&gt;/?action=set-permission</code>,
	 * see issue #83.
	 *
	 * <p>
	 * The administrator's own decision. A user's permission is the same in every album, so this is
	 * the whole of sharing inside a space: who is in it, and what they may do.
	 * </p>
	 */
	private void setPermission(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		if (!administrator(context, caller)) {
			return;
		}
		UserPermission request;
		try {
			byte[] contents = readBody(context.request());
			request = UserPermission.readUserPermission(new JsonReader(
				new ReaderAdapter(new InputStreamReader(new ByteArrayInputStream(contents), StandardCharsets.UTF_8))));
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting an unparsable permission: " + ex.getMessage());
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, PERMISSION_UNREADABLE);
			return;
		}
		try {
			UserStore.User user = _auth.setPermission(request.getName(), request.getRole(),
				request.getClearance(), request.isMayShare());
			LOG.info("The permission of '" + user.getName() + "' is now " + user.getRole() + ".");
			serveJsonObject(context.response(), userList());
		} catch (AuthService.Refused ex) {
			LOG.warning("Refusing to set a permission: " + ex.getMessage());
			errorInfo(context, ex.getStatus(), ex.getMessage());
		}
	}

	/**
	 * Removes a user of this space with their devices, at
	 * <code>&lt;data&gt;/?action=remove-user</code>, see issue #83.
	 *
	 * <p>
	 * Their tokens stop working at once. Nothing of theirs leaves the album tree: what they
	 * uploaded belongs to the space, and their name stays in its attribution.
	 * </p>
	 */
	private void removeUser(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		if (!administrator(context, caller)) {
			return;
		}
		MemberName request;
		try {
			byte[] contents = readBody(context.request());
			request = MemberName.readMemberName(new JsonReader(
				new ReaderAdapter(new InputStreamReader(new ByteArrayInputStream(contents), StandardCharsets.UTF_8))));
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting an unparsable user name: " + ex.getMessage());
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, PERMISSION_UNREADABLE);
			return;
		}
		try {
			int revoked = _auth.removeUser(request.getName());
			serveJsonObject(context.response(), userList().setRevokedLinks(revoked));
		} catch (AuthService.Refused ex) {
			LOG.warning("Refusing to remove a user: " + ex.getMessage());
			errorInfo(context, ex.getStatus(), ex.getMessage());
		}
	}

	/** Whether the caller administers this space; the refusal is answered here if not. */
	private boolean administrator(Context context, Caller caller) throws IOException {
		if (!caller.isPaired()) {
			unauthorized(context, caller, true);
			return false;
		}
		if (!Roles.isAdmin(caller.getRole())) {
			LOG.warning("Refusing the management request of '" + caller.getUserName() + "'.");
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, ADMIN_ONLY);
			return false;
		}
		return true;
	}

	/** The users of this space, as the protocol carries them. */
	private UserList userList() {
		UserList result = UserList.create();
		for (UserStore.User user : _auth.getUsers().getUsers()) {
			result.addUser(onTheWire(user));
		}
		return result;
	}

	/**
	 * The given user as the protocol carries them, see issue #55.
	 *
	 * <p>
	 * Never a device of theirs and never a token: how many devices somebody uses is a number, and
	 * what those devices are called is answered to their owner alone, see {@link DeviceList}.
	 * </p>
	 */
	private static UserEntry onTheWire(UserStore.User user) {
		return UserEntry.create()
			.setName(user.getName())
			.setRole(user.getRole())
			.setSpace(user.getSpace())
			.setCreated(user.getCreated())
			.setClearance(user.getClearance())
			.setMayShare(user.isShare())
			.setDevices(user.getDevices().size());
	}


	/**
	 * Handles the pairing request at <code>&lt;data&gt;/?action=pair</code>.
	 *
	 * <p>
	 * Pairing is the only write-like request that is open to an unpaired caller: it is guarded by
	 * the pairing secret the server was started with, not by a token. Every other POST is refused
	 * like a PUT.
	 * </p>
	 */
	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		Context context = new Context(request, response);

		if (gone(context, _auth.caller(request))) {
			return;
		}

		String action = context.getParameter("action");
		if ("check".equals(action)) {
			checkUploads(context);
			return;
		}
		if ("move".equals(action)) {
			moveEntries(context);
			return;
		}
		if ("place".equals(action)) {
			placeEntries(context);
			return;
		}
		if ("unlink".equals(action)) {
			retired(context, RETIRED_LINKS);
			return;
		}
		if ("grant".equals(action) || "revoke".equals(action)) {
			retired(context, RETIRED_GRANTS);
			return;
		}
		if ("group".equals(action) || "ungroup".equals(action)) {
			retired(context, RETIRED_GROUPS);
			return;
		}
		if ("regroup".equals(action)) {
			retired(context, RETIRED_GROUPS);
			return;
		}
		if ("unpair".equals(action)) {
			unpairDevice(context);
			return;
		}
		if ("device-code".equals(action)) {
			createDeviceCode(context);
			return;
		}
		if ("share".equals(action)) {
			createShare(context);
			return;
		}
		if ("unshare".equals(action)) {
			removeShare(context);
			return;
		}
		if ("invite".equals(action)) {
			createInvitation(context);
			return;
		}
		if ("uninvite".equals(action)) {
			removeInvitation(context);
			return;
		}
		if ("promote".equals(action)) {
			retired(context, RETIRED_PROMOTE);
			return;
		}
		if ("set-permission".equals(action)) {
			setPermission(context);
			return;
		}
		if ("remove-user".equals(action)) {
			removeUser(context);
			return;
		}

		if (!"pair".equals(action)) {
			Caller caller = _auth.caller(request);
			if (!_auth.writeAllowed(caller)) {
				unauthorized(context, caller, true);
				return;
			}
			error(context, HttpServletResponse.SC_METHOD_NOT_ALLOWED);
			return;
		}

		PairRequest pairRequest;
		try {
			byte[] contents = readBody(request);
			pairRequest = PairRequest.readPairRequest(new JsonReader(
				new ReaderAdapter(new InputStreamReader(new ByteArrayInputStream(contents), StandardCharsets.UTF_8))));
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting unparsable pairing request: " + ex.getMessage());
			errorInfo(context, HttpServletResponse.SC_BAD_REQUEST, "The pairing request cannot be read.");
			return;
		}

		PairResponse pairResponse;
		try {
			pairResponse = _auth.pair(pairRequest);
		} catch (PairRefused ex) {
			LOG.warning("Refusing to pair device '" + pairRequest.getDeviceName() + "': " + ex.getMessage());
			errorInfo(context, ex.getStatus(), ex.getMessage());
			return;
		}

		LOG.info("Paired device: " + pairResponse.getDeviceName());
		serveJsonObject(response, pairResponse);
	}

	/**
	 * Answers a cross-origin preflight, so that a browser may send the
	 * <code>Authorization</code> header of an authenticated request.
	 */
	@Override
	protected void doOptions(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		allowCrossOrigin(response);
		response.setHeader("Access-Control-Allow-Methods", "GET, PUT, POST, OPTIONS");
		response.setStatus(HttpServletResponse.SC_OK);
	}

	/**
	 * Refuses the request with <code>401</code>, naming the reason in the response body.
	 *
	 * <p>
	 * Nothing declines silently: the message is what the app shows the user, see
	 * {@link AuthService#refusal(Caller, boolean)}.
	 * </p>
	 */
	private void unauthorized(Context context, Caller caller, boolean write) throws IOException {
		String message = _auth.refusal(caller, write);
		LOG.warning("Refusing " + (write ? "write" : "read") + " access to '" + context.request().getPathInfo()
			+ "': " + message);

		if (identified(caller)) {
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, message);
			return;
		}
		context.response().setHeader("WWW-Authenticate", "Bearer");
		errorInfo(context, HttpServletResponse.SC_UNAUTHORIZED, message);
	}

	/**
	 * Whether the given caller already said who it is, so that a challenge would not help it.
	 *
	 * <p>
	 * A paired device, and a share link of issue #51: both presented a token this server issued, so
	 * a refusal names what they may not do (<code>403</code>) instead of asking them to sign in.
	 * A link holder has nothing to sign in as, and telling them to pair a device would be nonsense.
	 * </p>
	 */
	private static boolean identified(Caller caller) {
		return caller.isPaired() || caller.isShareLink();
	}

	/**
	 * Answers a caller whose share link expired or was withdrawn with <code>410 Gone</code>.
	 *
	 * <p>
	 * On every endpoint, <code>?type=auth</code> included: the app asks who it is, is told that the
	 * link is gone and why, and shows a plain page instead of an error dump, see issue #51.
	 * </p>
	 *
	 * @return Whether the request was answered here.
	 */
	private static boolean gone(Context context, Caller caller) throws IOException {
		if (!caller.isShareGone()) {
			return false;
		}
		LOG.warning("Refusing '" + context.request().getPathInfo() + "': " + caller.getGone());
		errorInfo(context, HttpServletResponse.SC_GONE, caller.getGone());
		return true;
	}

	/**
	 * Answers a request for something this build has retired, see issue #83.
	 *
	 * <p>
	 * <code>410 Gone</code> and not <code>404</code>: the endpoint was here and is not coming
	 * back, and the message names what does the job now. Kept for one release, so that an app that
	 * was not updated says something useful instead of failing silently.
	 * </p>
	 */
	private static void retired(Context context, String message) throws IOException {
		LOG.warning("Refusing the retired endpoint '" + context.request().getPathInfo() + "': " + message);
		errorInfo(context, HttpServletResponse.SC_GONE, message);
	}

	/** Answers with the given status and an {@link ErrorInfo} body carrying the given message. */
	private static void errorInfo(Context context, int status, String message) throws IOException {
		HttpServletResponse response = context.response();
		allowCrossOrigin(response);
		response.setStatus(status);
		serveJson(response, ErrorInfo.create().setMessage(message));
	}

	private static String baseName(String name) {
		int index = name.lastIndexOf('/');
		if (index < 0) {
			return name;
		}
		return name.substring(index + 1);
	}

	private static String extension(String name) {
		int index = name.lastIndexOf('.');
		if (index < 0) {
			return "";
		}
		return name.substring(index + 1).toLowerCase();
	}

	/**
	 * Stores the request body as <code>index.json</code> of the given folder.
	 *
	 * <p>
	 * The client's bytes are stored verbatim: the body is only parsed to make sure that it is a
	 * {@link FolderResource}, and it is re-serialised only when it carries something the server
	 * derived and must not keep, see {@link #stored(byte[], FolderResource)}. A pre-existing
	 * <code>index.json</code> is kept as a timestamped backup.
	 * </p>
	 */
	private void storeFolder(Context context, PathInfo resourcePath) throws IOException {
		byte[] contents = readBody(context.request());
		FolderResource resource = checkFolderResource(context, contents);
		if (resource == null) {
			return;
		}

		storeSidecar(resourcePath.toFile(), stored(contents, resource));

		// The next read must see what was just written, in the folder and in the listing above.
		_cache.invalidate(resourcePath);
	}

	/**
	 * Writes the given bytes as the <code>index.json</code> of the given folder.
	 *
	 * <p>
	 * The one way a sidecar reaches the disk: a client's PUT and the album a move rewrites take
	 * the same path, so there is only ever one sidecar format. A pre-existing sidecar is kept as a
	 * timestamped backup and the new one appears by a rename, so a reader never sees half a file.
	 * </p>
	 */
	static void storeSidecar(File directory, byte[] contents) throws IOException {
		File indexFile = new File(directory, "index.json");

		File tmpFile = File.createTempFile("index", ".json", directory);
		try (OutputStream stream = new FileOutputStream(tmpFile)) {
			stream.write(contents);
		}

		if (indexFile.exists()) {
			indexFile.renameTo(new File(directory, "index.json." + indexFile.lastModified()));
		}

		tmpFile.renameTo(indexFile);

		LOG.info("Stored folder resource: " + indexFile.getAbsolutePath());
	}

	/**
	 * Reads the complete request body into memory.
	 *
	 * <p>
	 * A folder sidecar is small; keeping it in memory is what allows validating it before anything
	 * is written to disk.
	 * </p>
	 */
	private static byte[] readBody(HttpServletRequest request) throws IOException {
		try (InputStream in = request.getInputStream()) {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			Util.transfer(in, buffer);
			return buffer.toByteArray();
		}
	}

	/**
	 * Checks that the given bytes parse as a {@link FolderResource}.
	 *
	 * <p>
	 * If they do not, the response is completed with an error status and the reason is logged.
	 * </p>
	 *
	 * @return The parsed resource, <code>null</code> if the contents may not be stored.
	 */
	private static FolderResource checkFolderResource(Context context, byte[] contents) {
		Resource resource;
		try {
			resource = Resource.readResource(
				new JsonReader(new ReaderAdapter(new InputStreamReader(new ByteArrayInputStream(contents), StandardCharsets.UTF_8))));
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting unparsable folder resource for '" + context.request().getPathInfo() + "': " + ex.getMessage());
			error(context, HttpServletResponse.SC_BAD_REQUEST);
			return null;
		}
		if (!(resource instanceof FolderResource)) {
			LOG.warning("Rejecting non-folder resource for '" + context.request().getPathInfo() + "': " + resource);
			error(context, HttpServletResponse.SC_BAD_REQUEST);
			return null;
		}
		return (FolderResource) resource;
	}

	/**
	 * The bytes to store for a received sidecar.
	 *
	 * <p>
	 * A client's body is stored exactly as it was sent — that is what keeps one sidecar format —
	 * unless it carries a date the server derived rather than the author stated. Such a field would
	 * freeze today's guess into the file and outlive whatever it was derived from, so it is cleared
	 * and only then is the body re-serialised, see {@link AlbumDate#clearDerived(FolderResource)}.
	 * </p>
	 */
	private static byte[] stored(byte[] contents, FolderResource resource) throws IOException {
		if (!AlbumDate.clearDerived(resource)) {
			return contents;
		}
		LOG.info("Dropping the derived date of a stored sidecar: it is answered, never kept.");
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (JsonWriter json = new JsonWriter(new WriterAdapter(new OutputStreamWriter(buffer,
			StandardCharsets.UTF_8)))) {
			resource.writeTo(json);
		}
		return buffer.toByteArray();
	}

	/**
	 * Delivers the description of a folder, filtered to what the request may see.
	 *
	 * <p>
	 * The cache keeps the folder as it is on disk; only the answer is filtered, see
	 * {@link PrivacyFilter}.
	 * </p>
	 */
	private void serveFolder(Context context, PathInfo pathInfo, int clearance, int viewAs, Caller caller)
			throws IOException {
		Resource resource = _cache.lookup(pathInfo);
		if (jsonRequested(context)) {
			Resource answer = _privacy.filter(resource, pathInfo, clearance, _auth.minRating(caller));
			if (answer instanceof ListingInfo) {
				// The shared albums of this folder, shown as what they point at, see issue #50.
				// After the privacy filter: a link is filtered by the clearance on its own target,
				// which is not the one this listing was filtered with.
			}
			serveJson(context.response(), withRights(answer, _auth.rights(caller, pathInfo)));
		} else {
			error404(context);
		}
	}



	/**
	 * The given folder answer carrying the caller's rights on it, see {@link FolderResource#getRights()}.
	 *
	 * <p>
	 * Always on a copy: what the {@link ResourceCache} holds is one object shared by every request
	 * and by the sidecar a move rewrites, and one caller's rights are not another's. The copy
	 * shares the parts of the cached album, exactly as the copy of the {@link PrivacyFilter} does
	 * — it is only ever serialised into the response.
	 * </p>
	 */
	private static Resource withRights(Resource resource, Set<String> rights) {
		if (!(resource instanceof FolderResource)) {
			return resource;
		}
		FolderResource copy;
		if (resource instanceof AlbumInfo) {
			AlbumInfo album = (AlbumInfo) resource;
			AlbumInfo result = AlbumInfo.create()
				.setTitle(album.getTitle())
				.setSubTitle(album.getSubTitle())
				.setDate(album.getDate())
				.setEffectiveDate(album.getEffectiveDate())
				.setParts(album.getParts());
			if (album.getIndexPicture() != null) {
				result.setIndexPicture(album.getIndexPicture());
			}
			copy = result;
		} else if (resource instanceof ListingInfo) {
			ListingInfo listing = (ListingInfo) resource;
			copy = ListingInfo.create()
				.setTitle(listing.getTitle())
				.setPlacement(listing.getPlacement())
				.setFolders(listing.getFolders());
		} else {
			return resource;
		}
		copy.setRights(Rights.onTheWire(rights));
		return copy;
	}

	/**
	 * Delivers an image: its description, its thumbnail or the original.
	 *
	 * <p>
	 * A path is not a permission: an image above the request's clearance or below its rating limit
	 * is refused here, whichever of the three is asked for, see
	 * {@link #imageRefused(Context, Caller, String)}.
	 * </p>
	 */
	private void serveImage(Context context, PathInfo pathInfo, Caller caller, int clearance, int minRating)
			throws IOException {
		String refusal = hidden(pathInfo, clearance, minRating);
		if (refusal != null) {
			imageRefused(context, caller, refusal);
			return;
		}

		if (jsonRequested(context)) {
			Resource resource = _cache.lookup(pathInfo);
			serveJson(context.response(), resource);
			return;
		}

		String type = context.getParameter("type");
		if ("tn".equals(type)) {
			File data;
			try {
				data = PreviewCache.createPreview(pathInfo.toFile());
			} catch (PreviewException ex) {
				LOG.log(Level.WARNING, ex.getMessage(), ex.getCause());
				errorInfo(context, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, PREVIEW_FAILED);
				return;
			}
			serveData(context, data, "image/jpeg");
		} else if (VideoRenditions.Kind.PLAYBACK.parameter().equals(type)) {
			serveRendition(context, pathInfo, VideoRenditions.Kind.PLAYBACK);
		} else if (VideoRenditions.Kind.TEASER.parameter().equals(type)) {
			serveRendition(context, pathInfo, VideoRenditions.Kind.TEASER);
		} else {
			Resource resource = _cache.lookup(pathInfo);
			if (resource != null) {
				String mimeType = mimeType(context, resource);

				serveData(context, pathInfo.toFile(), mimeType);
			}
		}
	}

	/**
	 * Delivers a transcoded rendition of a video, see issue #74.
	 *
	 * <p>
	 * The rendition is never made while the request waits: what is not there is queued and
	 * answered with <code>202</code>, so that the app can come back for it. What could not be made
	 * is a failure of this server, answered like any other, and coming back would not help.
	 * </p>
	 */
	private void serveRendition(Context context, PathInfo pathInfo, VideoRenditions.Kind kind) throws IOException {
		File file = pathInfo.toFile();
		if (!VideoRenditions.isVideo(file)) {
			// Only a video has renditions; a photo has its thumbnail.
			error404(context);
			return;
		}
		VideoRenditions.Rendition rendition = _videos.lookup(file, kind);
		switch (rendition.getState()) {
			case READY:
				serveData(context, rendition.getFile(), "video/mp4");
				return;
			case PENDING:
				context.response().setHeader("Retry-After", RETRY_AFTER_SECONDS);
				errorInfo(context, HttpServletResponse.SC_ACCEPTED, RENDITION_PENDING);
				return;
			case UNAVAILABLE:
				LOG.warning("Refusing the " + kind.parameter() + " rendition of '"
					+ context.request().getPathInfo() + "': " + rendition.getReason());
				errorInfo(context, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
					RENDITIONS_UNAVAILABLE + rendition.getReason());
				return;
			default:
				LOG.warning("Refusing the " + kind.parameter() + " rendition of '"
					+ context.request().getPathInfo() + "': " + rendition.getReason());
				errorInfo(context, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, RENDITION_FAILED);
				return;
		}
	}

	/**
	 * Why the image at the given path may not be shown to such a request.
	 *
	 * <p>
	 * An image file the album model does not describe (its analysis failed, say) carries no
	 * privacy level and is {@link Privacy#PUBLIC}, exactly as one that was never edited.
	 * </p>
	 *
	 * @return The message the request is refused with, <code>null</code> if the image may be shown.
	 */
	private String hidden(PathInfo pathInfo, int clearance, int minRating) {
		Resource resource = _cache.lookup(pathInfo);
		if (!(resource instanceof ImagePart)) {
			return null;
		}
		ImagePart image = (ImagePart) resource;
		if (!Privacy.visible(image.getPrivacy(), clearance)) {
			return IMAGE_REFUSED;
		}
		if (!Ratings.visible(image.getRating(), minRating)) {
			return RATING_REFUSED;
		}
		return null;
	}

	/**
	 * Refuses a request for an image the caller may not see, see {@link #IMAGE_REFUSED}.
	 *
	 * <p>
	 * An anonymous caller is answered with <code>401</code> and the challenge that says how to get
	 * further; a signed-in caller with <code>403</code>, because signing in again would not help
	 * (that is also what the "view as" preview of the owner's own album produces).
	 * </p>
	 */
	private static void imageRefused(Context context, Caller caller, String message) throws IOException {
		LOG.warning("Refusing the image '" + context.request().getPathInfo() + "': " + message);
		if (identified(caller)) {
			errorInfo(context, HttpServletResponse.SC_FORBIDDEN, message);
		} else {
			context.response().setHeader("WWW-Authenticate", "Bearer");
			errorInfo(context, HttpServletResponse.SC_UNAUTHORIZED, message);
		}
	}

	private String mimeType(Context context, Resource resource) {
		if (resource instanceof ImagePart) {
			ImagePart image = (ImagePart) resource;
			ImageKind kind = image.getKind();
			switch (kind) {
			case VIDEO:
				return "video/mp4";
			case QUICKTIME:
				return "video/quicktime";
			case IMAGE:
				return context.request().getServletContext().getMimeType(image.getName());
			}
		}
		return "application/binary";
	}

	private static void serveJson(HttpServletResponse response, Resource album) throws IOException {
		LOG.log(Level.FINE, "Delivering JSON.");

		response.setContentType("application/json");
		response.setCharacterEncoding("utf-8");

		// Allow access from mobile app.
		allowCrossOrigin(response);
		try (JsonWriter json = new JsonWriter(new WriterAdapter(new OutputStreamWriter(response.getOutputStream(), "utf-8")))) {
			album.writeTo(json);
		}
	}

	/**
	 * Delivers a plain data object (one that is not a {@link Resource}) as JSON.
	 *
	 * <p>
	 * Unlike a {@link Resource}, such an object carries no type tag: the caller knows what it
	 * asked for.
	 * </p>
	 */
	private static void serveJsonObject(HttpServletResponse response, de.haumacher.msgbuf.data.DataObject object)
			throws IOException {
		response.setContentType("application/json");
		response.setCharacterEncoding("utf-8");

		allowCrossOrigin(response);
		try (JsonWriter json = new JsonWriter(new WriterAdapter(new OutputStreamWriter(response.getOutputStream(), "utf-8")))) {
			object.writeTo(json);
		}
	}

	/**
	 * Announces that the app may talk to this server from another origin, and that nothing of
	 * what it is told may be kept.
	 *
	 * <p>
	 * The <code>Authorization</code> header of an authenticated request makes a browser send a
	 * preflight, so the header must be allowed explicitly, see
	 * {@link #doOptions(HttpServletRequest, HttpServletResponse)}.
	 * </p>
	 *
	 * <p>
	 * Every JSON answer depends on the bearer that asked, and some — <code>410 Gone</code>
	 * among them — a browser caches by default: a withdrawn share link answered once at
	 * <code>?type=auth</code> would then be replayed for every later token at the same origin,
	 * and an invitation opened next would be told it was withdrawn. So no JSON answer may be
	 * stored, and a cache that stores anyway must key it on the bearer.
	 * </p>
	 */
	private static void allowCrossOrigin(HttpServletResponse response) {
		response.setHeader("Access-Control-Allow-Origin", "*");
		response.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type");
		response.setHeader("Cache-Control", "no-store");
		response.setHeader("Vary", "Authorization");
	}

	/**
	 * Delivers the contents of the given file, honouring a <code>Range</code> request header.
	 *
	 * <p>
	 * Range support is generic: it applies to originals and thumbnails alike. A single byte range
	 * is answered with <code>206 Partial Content</code>, an unsatisfiable one with
	 * <code>416</code>. A multi-range request is answered with the complete file and status
	 * <code>200</code> (an allowed response that spares building a
	 * <code>multipart/byteranges</code> body); see {@link ByteRange}.
	 * </p>
	 */
	private void serveData(Context context, File file, String mimeType) throws IOException {
		LOG.log(Level.FINE, "Delivering image data: " + mimeType);
		HttpServletResponse response = context.response();

		response.setContentType(mimeType);
		// Allow access from mobile app (is required even for images, since they are rendered using WebGL from Flutter).
		response.setHeader("Access-Control-Allow-Origin", "*");
		response.setHeader("Access-Control-Expose-Headers", "Accept-Ranges, Content-Range, Content-Length");

		long length = file.length();

		// Announce range support on every response, so that a client knows it may seek.
		response.setHeader("Accept-Ranges", "bytes");

		ByteRange range = ByteRange.parse(context.request().getHeader("Range"), length);
		if (range.isUnsatisfiable()) {
			LOG.log(Level.WARNING, "Unsatisfiable range '" + context.request().getHeader("Range") + "' for file of size " + length + ": " + file.getAbsolutePath());
			response.setHeader("Content-Range", "bytes */" + length);
			response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
			return;
		}

		if (range.isPartial()) {
			long start = range.getStart();
			long end = range.getEnd();
			response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
			response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + length);
			response.setContentLengthLong(range.getLength());
			Util.sendSlice(response, file, start, range.getLength());
			return;
		}

		response.setContentLengthLong(length);
		try (FileInputStream in = new FileInputStream(file)) {
			Util.sendBytes(response, in);
		}
	}

	private static boolean jsonRequested(Context context) {
		return "json".equals(context.getParameter("type"));
	}

	private void error404(Context context) {
		error(context, HttpServletResponse.SC_NOT_FOUND);
	}

	private static void error(Context context, int errorCode) {
		LOG.log(Level.WARNING, "Faild to access '" + context.request().getPathInfo() + "': " + errorCode);

		HttpServletResponse response = context.response();
		allowCrossOrigin(response);
		response.setStatus(errorCode);
	}

	static class Context {

		private final HttpServletRequest _request;
		private final HttpServletResponse _response;

		/**
		 * Creates a {@link Context}.
		 */
		public Context(HttpServletRequest request, HttpServletResponse response) {
			_request = request;
			_response = response;
		}

		public String getContextPath() {
			return _request.getContextPath();
		}

		/**
		 * See {@link HttpServletRequest#getParameter(String)}.
		 */
		public String getParameter(String name) {
			return request().getParameter(name);
		}

		/**
		 * The current {@link HttpServletRequest}.
		 */
		public HttpServletRequest request() {
			return _request;
		}

		/**
		 * The current {@link HttpServletResponse}.
		 */
		public HttpServletResponse response() {
			return _response;
		}

	}
}
