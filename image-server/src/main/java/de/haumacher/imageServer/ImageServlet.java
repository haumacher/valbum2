/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer;

import static de.haumacher.util.html.HTML.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.apache.commons.fileupload2.jakarta.JakartaServletFileUpload;

import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.shared.model.ImageKind;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.upload.UploadFactory;
import de.haumacher.imageServer.upload.UploadItem;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import de.haumacher.util.servlet.Util;
import de.haumacher.util.xml.XmlWriter;
import jakarta.activation.MimeType;
import jakarta.activation.MimeTypeParseException;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

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
		if (type == null) {
			if (pathInfo == null || pathInfo.equals("/")) {
				LOG.log(Level.FINE, "Delivering main page.");
				serveIndex(context);
				return;
			}
		}
		
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

		if (pathInfo == null) {
			error(context, HttpServletResponse.SC_METHOD_NOT_ALLOWED);
			return;
		}
		
		PathInfo resourcePath;
		{
			String relativePath = pathInfo.substring(1);
			if (relativePath.isEmpty()) {
				error(context, HttpServletResponse.SC_METHOD_NOT_ALLOWED);
				return;
			}
			
			Path path = Paths.get(relativePath).normalize();
			if (path.startsWith("..") || path.startsWith("/")) {
				error404(context);
				return;
			}
			
			resourcePath = new PathInfo(_basePath, path);
		}
		
		File file = resourcePath.toFile();
		if (!file.exists()) {
			File parent = file.getParentFile();
			if (parent.exists() && parent.isDirectory()) {
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
				return;
			}
			
			error404(context);
			return;
		}

		if (!file.isDirectory()) {
			error(context, HttpServletResponse.SC_METHOD_NOT_ALLOWED);
			return;
		}
		
		String contentType = context.request().getContentType();
		MimeType mimeType;
		try {
			mimeType = new MimeType(contentType);
		} catch (MimeTypeParseException ex) {
			LOG.warning("Invalid content type: " + contentType);
			error(context, HttpServletResponse.SC_BAD_REQUEST);
			return;
		}
		String baseType = mimeType.getBaseType();
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

	private void storeFolder(Context context, PathInfo resourcePath) throws IOException {
		File directory = resourcePath.toFile();
		File indexFile = new File(directory, "index.json");
		
		File tmpFile = File.createTempFile("index", ".json", directory);

		try (OutputStream stream = new FileOutputStream(tmpFile); Writer out = new OutputStreamWriter(stream, "utf-8")) {
			BufferedReader reader = context.request().getReader();
			char[] buffer = new char[4096];
			while (true) {
				int direct = reader.read(buffer);
				if (direct < 0) {
					break;
				}
				out.write(buffer, 0, direct);
			}
		}

		if (indexFile.exists()) {
			indexFile.renameTo(new File(directory, "index.json." + indexFile.lastModified()));
		}
		
		tmpFile.renameTo(indexFile);
	}

	private void serveIndex(Context context) throws IOException {
		HttpServletResponse response = context.response();
		
		response.setContentType("text/html");
		response.setCharacterEncoding("utf-8");
		try (Writer w = new OutputStreamWriter(response.getOutputStream(), "utf-8")) {
			try (XmlWriter out = new XmlWriter(w)) {
				out.special("<!doctype html>");
				out.begin(HTML);
				{
					out.begin(HEAD);
					{
						out.begin(META);
						out.attr(HTTP_EQUIV_ATTR, "content-type");
						out.attr(CONTENT_ATTR, "text/html; charset=UTF-8");
						out.endEmpty();
						
						out.begin(LINK);
						out.attr(TYPE_ATTR, "text/css");
						out.attr(REL_ATTR, "stylesheet");
						out.attr(HREF_ATTR, context.getContextPath() + "/style/valbum.css");
						out.endEmpty();
						
						out.begin(LINK);
						out.attr(TYPE_ATTR, "text/css");
						out.attr(REL_ATTR, "stylesheet");
						out.attr(HREF_ATTR, context.getContextPath() + "/webjars/font-awesome/" + Page.FA_VERSION + "/css/all.css");
						out.endEmpty();
						
						out.begin(TITLE);
						out.append("Virtual Photo Album");
						out.end();
						
						out.begin(SCRIPT);
						out.attr(TYPE_ATTR, "text/javascript");
						out.attr(SRC_ATTR, context.getContextPath() + "/client/client.nocache.js");
						out.end();
					}
					out.end();
					
					out.begin(BODY);
					{
						out.begin(NOSCRIPT);
						{
							out.append("Without JavaScript, you must enter here.");
							
							out.begin(A);
							out.attr(HREF_ATTR, "./?type=html");
							{
								out.append("here");
							}
							out.end();
							
							out.append(".");
						}
						out.end();
						
						out.begin(DIV);
						out.attr(ID_ATTR, "main");
						{
							out.append("Loading app...");
						}
						out.end();
					}
					out.end();
				}
				out.end();
			}
		}
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
				return "video/mpeg";
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

	private void serveData(Context context, File file, String mimeType) throws IOException {
		LOG.log(Level.FINE, "Delivering image data: " + mimeType);
		HttpServletResponse response = context.response();

		response.setContentType(mimeType);
		// Allow access from mobile app (is required even for images, since they are rendered using WebGL from Flutter).
		response.setHeader("Access-Control-Allow-Origin", "*");
		long length = file.length();
		if (length <= Integer.MAX_VALUE) {
			response.setContentLength((int) length);
		}
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

	private void error(Context context, int errorCode) {
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
