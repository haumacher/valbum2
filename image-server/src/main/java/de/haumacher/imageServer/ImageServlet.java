/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;

import com.google.gson.stream.JsonWriter;

import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.ui.ResourceRenderer;
import de.haumacher.util.xml.RenderContext;
import de.haumacher.util.xml.ValueFragment;
import de.haumacher.util.xml.XmlWriter;

/**
 * {@link HttpServlet} serving image, video, preview and directory listing data.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class ImageServlet extends HttpServlet { 

	private static final Logger LOG = Logger.getLogger(ImageServlet.class.getName());

	static {
		LOG.info("Loading: " + ExifReaderPatch.class);
	}

	private Path _basePath;

	/** 
	 * Creates a {@link ImageServlet}.
	 *
	 * @param basePath
	 */
	public ImageServlet(File basePath) {
		_basePath = basePath.toPath();
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		String pathInfo = request.getPathInfo();
		Context context = new Context(request, response);
		
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
				if (path.startsWith("..")) {
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
				response.sendRedirect(request.getContextPath() + "/");
				return;
			}
			if (!pathInfo.endsWith("/")) {
				response.sendRedirect(request.getContextPath() + pathInfo + "/");
				return;
			}
			
			serveFolder(context, resourcePath);
		} else if (ResourceCache.isImage(file)) {
			serveImage(context, resourcePath);
		} else {
			error404(context);
		}
	}

	private void serveFolder(Context context, PathInfo pathInfo) throws IOException {
		Resource resource = ResourceCache.lookup(pathInfo);
		if (jsonRequested(context)) {
			serveJson(context.response(), resource);
		} else {
			render(context, resource);
		}
	}
	
	private void serveImage(Context context, PathInfo pathInfo) throws IOException {
		if (jsonRequested(context)) {
			Resource resource = ResourceCache.lookup(pathInfo);
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
			serveData(context, data);
		} else if ("page".equals(type)) {
			Resource resource = ResourceCache.lookup(pathInfo);
			render(context, resource);
		} else {
			serveData(context, pathInfo.toFile());
		}
	}

	private void render(Context context, Resource resource) throws IOException, UnsupportedEncodingException {
		HttpServletResponse response = context.response();
		
		response.setContentType("text/html");
		response.setCharacterEncoding("utf-8");
		try (Writer w = new OutputStreamWriter(response.getOutputStream(), "utf-8")) {
			try (XmlWriter out = new XmlWriter(w)) {
				new Page("VAlbum", ValueFragment.create(ResourceRenderer.INSTANCE, resource)).write(context, out);
			}
		}
	}

	private void serveJson(HttpServletResponse response, Resource album) throws IOException {
		response.setContentType("application/json");
		try (JsonWriter json = new JsonWriter(new OutputStreamWriter(response.getOutputStream(), "utf-8"))) {
			album.writePolymorphic(json);
		}
	}

	private void serveData(Context context, File file) throws IOException {
		HttpServletRequest request = context.request();
		HttpServletResponse response = context.response();

		String mimeType = request.getServletContext().getMimeType(file.getName());
		response.setContentType(mimeType);
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
		context.response().setStatus(HttpStatus.NOT_FOUND_404);
	}

	static class Context implements RenderContext {
	
		private final HttpServletRequest _request;
		private final HttpServletResponse _response;
	
		/** 
		 * Creates a {@link Context}.
		 */
		public Context(HttpServletRequest request, HttpServletResponse response) {
			_request = request;
			_response = response;
		}
		
		@Override
		public String getContextPath() {
			return _request.getContextPath();
		}

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
