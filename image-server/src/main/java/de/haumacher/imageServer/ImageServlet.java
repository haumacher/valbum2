/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer;


import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.AuthService.Caller;
import de.haumacher.imageServer.auth.AuthService.PairRefused;
import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.shared.model.ContentHash;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.ImageKind;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.PairRequest;
import de.haumacher.imageServer.shared.model.PairResponse;
import de.haumacher.imageServer.shared.model.PresentFile;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.UploadCheck;
import de.haumacher.imageServer.shared.model.UploadCheckResult;
import de.haumacher.imageServer.shared.model.UploadResult;
import de.haumacher.imageServer.shared.model.UploadedFile;
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
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
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

	static {
		LOG.info("Loading: " + ExifReaderPatch.class);
	}

	private Path _basePath;
	private ResourceCache _cache;

	private JakartaServletFileUpload<UploadItem, UploadFactory> _fileUpload;

	private final AuthService _auth;

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
		_basePath = basePath.toPath();
		_cache = new ResourceCache();
		_auth = auth;
	}

	@Override
	public void init() throws ServletException {
		super.init();

		File repository = new File(_basePath.toFile(), ".upload");
		repository.mkdirs();

		_fileUpload = new JakartaServletFileUpload<UploadItem, UploadFactory>(new UploadFactory(repository));
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		String pathInfo = request.getPathInfo();
		Context context = new Context(request, response);

		String type = context.getParameter("type");

		Caller caller = _auth.caller(request);
		if ("auth".equals(type)) {
			// Always answerable: this is how an unpaired app learns that it must pair.
			serveJsonObject(response, _auth.authInfo(caller));
			return;
		}
		if (!_auth.readAllowed(caller)) {
			unauthorized(context, caller, false);
			return;
		}

		Path root = _auth.spaceRoot(caller, _basePath);
		PathInfo resourcePath;
		if (pathInfo == null) {
			resourcePath = new PathInfo(root);
		} else {
			String relativePath = pathInfo.substring(1);
			Path path;
			if (relativePath.isEmpty()) {
				path = null;
			} else {
				path = Paths.get(relativePath).normalize();
				if (path.startsWith("..") || path.startsWith("/")) {
					error404(context);
					return;
				}
			}
			resourcePath = new PathInfo(root, path);
		}

		File file = resourcePath.toFile();
		if (!file.exists()) {
			error404(context);
			return;
		}

		if (file.isDirectory()) {
			if (pathInfo == null) {
				String location = request.getContextPath() + request.getServletPath() + "/?type=" + type;
				sendRedirect(response, location);
				return;
			}
			if (!pathInfo.endsWith("/")) {
				String location = request.getContextPath() + request.getServletPath() + pathInfo + "/?type=" + type;
				sendRedirect(response, location);
				return;
			}

			serveFolder(context, resourcePath);
		} else if (ResourceCache.isImage(file)) {
			serveImage(context, resourcePath);
		} else {
			error404(context);
		}
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
		if (!_auth.writeAllowed(caller)) {
			unauthorized(context, caller, true);
			return;
		}

		PathInfo resourcePath = resolve(context, caller);
		if (resourcePath == null) {
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

			if (baseType.equals("application/json")) {
				if (file.exists()) {
					// A file is not a folder; its sidecar belongs to the folder around it.
					error(context, HttpServletResponse.SC_METHOD_NOT_ALLOWED);
					return;
				}
				createAlbum(context, file);
				return;
			}

			storeSingleImage(context, file);
			return;
		}

		if (baseType.equals("multipart/form-data")) {
			storeUploads(context, file);
			return;
		}

		if (!baseType.equals("application/json")) {
			LOG.warning("Unsupported content type: " + contentType);
			error(context, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
			return;
		}

		storeFolder(context, resourcePath);
	}

	/**
	 * Resolves the path of the current request against the served album tree.
	 *
	 * <p>
	 * The data root is a folder like any other: an empty path (<code>PUT /data</code> and
	 * <code>PUT /data/</code>) addresses the base folder itself and must be able to store its
	 * <code>index.json</code>.
	 * </p>
	 *
	 * <p>
	 * Every path is resolved against the caller's space, see
	 * {@link AuthService#spaceRoot(Caller, Path)}: a path that would leave it is a
	 * <code>404</code>, exactly like a path leaving the base folder.
	 * </p>
	 *
	 * @return <code>null</code> if the path leaves the caller's space; the response is completed
	 *         with a <code>404</code> in that case.
	 */
	private PathInfo resolve(Context context, Caller caller) {
		Path root = _auth.spaceRoot(caller, _basePath);
		String pathInfo = context.request().getPathInfo();
		String relativePath = pathInfo == null ? "" : pathInfo.substring(1);
		if (relativePath.isEmpty()) {
			return new PathInfo(root);
		}

		Path path = Paths.get(relativePath).normalize();
		if (path.startsWith("..") || path.startsWith("/")) {
			error404(context);
			return null;
		}
		return new PathInfo(root, path);
	}

	/** Creates a new album folder with the request body as its <code>index.json</code>. */
	private void createAlbum(Context context, File folder) throws IOException {
		byte[] contents = readBody(context.request());
		if (!checkFolderResource(context, contents)) {
			return;
		}

		boolean ok = folder.mkdirs();
		if (!ok) {
			LOG.warning("Cannot create path: " + folder.getAbsolutePath());
			error(context, HttpServletResponse.SC_BAD_REQUEST);
			return;
		}

		try (FileOutputStream out = new FileOutputStream(new File(folder, "index.json"))) {
			out.write(contents);
		}

		LOG.info("Created album: " + folder.getName());
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
	private void storeSingleImage(Context context, File target) throws IOException {
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
				hashes.put(target, hash);
				LOG.info("Storing image: " + target);
				result = UploadResult.create().addFile(uploaded(name, name, hash, STORED));
			}
		} finally {
			hashes.flush();
		}

		serveJsonObject(context.response(), result);
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
	 */
	private void storeUploads(Context context, File folder) throws IOException {
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
				hashes.put(targetFile, hash);
				LOG.info("Storing image: " + targetFile);
				result.addFile(uploaded(name, targetFile.getName(), hash, STORED));
			}
		} finally {
			hashes.flush();
		}

		serveJsonObject(context.response(), result);
	}

	/**
	 * Answers which of the asked contents the addressed folder already holds.
	 *
	 * <p>
	 * Asking is a read: it only reveals what the folder contains, so it obeys
	 * {@link AuthService#readAllowed(Caller)}. A client uses it to skip transferring what is
	 * already there; the upload itself is idempotent in any case, see
	 * {@link #storeUploads(Context, File)}.
	 * </p>
	 */
	private void checkUploads(Context context) throws IOException {
		Caller caller = _auth.caller(context.request());
		if (!_auth.readAllowed(caller)) {
			unauthorized(context, caller, false);
			return;
		}

		PathInfo resourcePath = resolve(context, caller);
		if (resourcePath == null) {
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
	 * A name clash is resolved by appending a number: nothing is ever overwritten.
	 * </p>
	 */
	private static File freeName(File folder, String fileName) {
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

		String action = context.getParameter("action");
		if ("check".equals(action)) {
			checkUploads(context);
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

		context.response().setHeader("WWW-Authenticate", "Bearer");
		errorInfo(context, HttpServletResponse.SC_UNAUTHORIZED, message);
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
	 * {@link FolderResource}, it is never re-serialised. A pre-existing <code>index.json</code> is
	 * kept as a timestamped backup.
	 * </p>
	 */
	private void storeFolder(Context context, PathInfo resourcePath) throws IOException {
		byte[] contents = readBody(context.request());
		if (!checkFolderResource(context, contents)) {
			return;
		}

		File directory = resourcePath.toFile();
		File indexFile = new File(directory, "index.json");

		File tmpFile = File.createTempFile("index", ".json", directory);
		try (OutputStream stream = new FileOutputStream(tmpFile)) {
			stream.write(contents);
		}

		if (indexFile.exists()) {
			indexFile.renameTo(new File(directory, "index.json." + indexFile.lastModified()));
		}

		tmpFile.renameTo(indexFile);

		// The next read must see what was just written, in the folder and in the listing above.
		_cache.invalidate(resourcePath);

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
	 * @return Whether the contents may be stored.
	 */
	private static boolean checkFolderResource(Context context, byte[] contents) {
		Resource resource;
		try {
			resource = Resource.readResource(
				new JsonReader(new ReaderAdapter(new InputStreamReader(new ByteArrayInputStream(contents), StandardCharsets.UTF_8))));
		} catch (IOException | RuntimeException ex) {
			LOG.warning("Rejecting unparsable folder resource for '" + context.request().getPathInfo() + "': " + ex.getMessage());
			error(context, HttpServletResponse.SC_BAD_REQUEST);
			return false;
		}
		if (!(resource instanceof FolderResource)) {
			LOG.warning("Rejecting non-folder resource for '" + context.request().getPathInfo() + "': " + resource);
			error(context, HttpServletResponse.SC_BAD_REQUEST);
			return false;
		}
		return true;
	}

	private void serveFolder(Context context, PathInfo pathInfo) throws IOException {
		Resource resource = _cache.lookup(pathInfo);
		if (jsonRequested(context)) {
			serveJson(context.response(), resource);
		} else {
			error404(context);
		}
	}

	private void serveImage(Context context, PathInfo pathInfo) throws IOException {
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
				error404(context);
				return;
			}
			serveData(context, data, "image/jpeg");
		} else {
			Resource resource = _cache.lookup(pathInfo);
			if (resource != null) {
				String mimeType = mimeType(context, resource);

				serveData(context, pathInfo.toFile(), mimeType);
			}
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
	 * Announces that the app may talk to this server from another origin.
	 *
	 * <p>
	 * The <code>Authorization</code> header of an authenticated request makes a browser send a
	 * preflight, so the header must be allowed explicitly, see
	 * {@link #doOptions(HttpServletRequest, HttpServletResponse)}.
	 * </p>
	 */
	private static void allowCrossOrigin(HttpServletResponse response) {
		response.setHeader("Access-Control-Allow-Origin", "*");
		response.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type");
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
