/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer;


import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.ImageKind;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Resource;
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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
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

	static {
		LOG.info("Loading: " + ExifReaderPatch.class);
	}

	private Path _basePath;
	private ResourceCache _cache;

	private JakartaServletFileUpload<UploadItem, UploadFactory> _fileUpload;

	/**
	 * Creates a {@link ImageServlet}.
	 *
	 * @param basePath The root path of the photo album to serve.
	 */
	public ImageServlet(File basePath) throws IOException {
		_basePath = basePath.toPath();
		_cache = new ResourceCache();
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

		PathInfo resourcePath;
		if (pathInfo == null) {
			resourcePath = new PathInfo(_basePath);
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
			resourcePath = new PathInfo(_basePath, path);
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

		PathInfo resourcePath;
		{
			// The data root is a folder like any other: "PUT /data" and "PUT /data/" address the
			// base folder itself and must be able to store its "index.json".
			String relativePath = pathInfo == null ? "" : pathInfo.substring(1);
			if (relativePath.isEmpty()) {
				resourcePath = new PathInfo(_basePath);
			} else {
				Path path = Paths.get(relativePath).normalize();
				if (path.startsWith("..") || path.startsWith("/")) {
					error404(context);
					return;
				}

				resourcePath = new PathInfo(_basePath, path);
			}
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
		if (!file.exists()) {
			File parent = file.getParentFile();
			if (parent.exists() && parent.isDirectory()) {
				if (baseType.equals("application/json")) {
					byte[] contents = readBody(request);
					if (!checkFolderResource(context, contents)) {
						return;
					}

					boolean ok = file.mkdirs();
					if (!ok) {
						LOG.warning("Cannot create path: " + file.getAbsolutePath());
						error(context, HttpServletResponse.SC_BAD_REQUEST);
						return;
					}

					try (FileOutputStream out = new FileOutputStream(new File(file, "index.json"))) {
						out.write(contents);
					}

					LOG.info("Created album: " + file.getName());
				} else {
					if (!PreviewCache.SUPPORTED_EXTENSIONS.contains(extension(file.getName()))) {
						LOG.warning("Unsupported upload extension: " + file.getName());
						error(context, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
						return;
					}

					// Process upload.
					List<UploadItem> uploads = _fileUpload.parseRequest(request);

					if (uploads.size() != 1) {
						LOG.warning("Tried to upload multiple files to a single image location " + file.getName() + ": " + uploads.stream().map(u -> u.getName()).collect(Collectors.joining(", ")));
						error(context, HttpServletResponse.SC_BAD_REQUEST);
						return;
					}

					uploads.get(0).getUpload().renameTo(file);
				}
				return;
			}

			error404(context);
			return;
		}

		if (!file.isDirectory()) {
			error(context, HttpServletResponse.SC_METHOD_NOT_ALLOWED);
			return;
		}

		if (baseType.equals("multipart/form-data")) {
			List<UploadItem> uploads = _fileUpload.parseRequest(request);
			for (UploadItem upload : uploads) {
				String name = upload.getName();
				String extension = extension(name);
				if (!PreviewCache.SUPPORTED_EXTENSIONS.contains(extension)) {
					LOG.warning("Unsupported upload extension: " + name);
					error(context, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
					return;
				}

				String fileName = baseName(name);
				File targetFile = new File(file, fileName);
				if (targetFile.exists()) {
					// Name clash.

					String baseName = fileName.substring(0, fileName.length() - extension.length() - 1);
					int num = 2;
					do {
						targetFile = new File(file, baseName + "-" + num + "." + extension);
						num ++;
					} while (targetFile.exists());
				}

				LOG.info("Storing image: " + targetFile);
				upload.getUpload().renameTo(targetFile);
			}
			return;
		}

		if (!baseType.equals("application/json")) {
			LOG.warning("Unsupported content type: " + contentType);
			error(context, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
			return;
		}

		storeFolder(context, resourcePath);
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

	private void serveJson(HttpServletResponse response, Resource album) throws IOException {
		LOG.log(Level.FINE, "Delivering JSON.");

		response.setContentType("application/json");
		response.setCharacterEncoding("utf-8");

		// Allow access from mobile app.
		response.setHeader("Access-Control-Allow-Origin", "*");
		try (JsonWriter json = new JsonWriter(new WriterAdapter(new OutputStreamWriter(response.getOutputStream(), "utf-8")))) {
			album.writeTo(json);
		}
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
		response.setHeader("Access-Control-Allow-Origin", "*");
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
