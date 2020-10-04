/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer;

import static de.haumacher.util.xml.HTML.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
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

import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.ImageInfo;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.ui.ResourceRenderer;
import de.haumacher.util.xml.XmlWriter;

/**
 * {@link HttpServlet} serving image, video, preview and directory listing data.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class ImageServlet extends HttpServlet implements Resource.Visitor<Void, ImageServlet.Context, IOException> { 

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
		
		Path resourcePath;
		if (pathInfo == null) {
			resourcePath = _basePath;
		} else {
			Path path = Paths.get(pathInfo.substring(1)).normalize();
			if (path.startsWith("..")) {
				error404(context);
				return;
			}
			resourcePath = _basePath.resolve(path);
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
			
			serveFolder(context, file);
		} else if (ResourceCache.isImage(file)) {
			serveImage(context, file);
		} else {
			error404(context);
		}
	}

	private void serveFolder(Context context, File file) throws IOException {
		Resource resource = ResourceCache.lookup(file);
		if (jsonRequested(context)) {
			serveJson(context.response(), resource);
		} else {
			resource.visit(this, context);
		}
	}
	
	private void serveImage(Context context, File file) throws IOException {
		if (jsonRequested(context)) {
			Resource resource = ResourceCache.lookup(file);
			serveJson(context.response(), resource);
			return;
		}

		File data;
		if ("tn".equals(context.getParameter("type"))) {
			try {
				data = PreviewCache.createPreview(file);
			} catch (PreviewException ex) {
				LOG.log(Level.WARNING, ex.getMessage(), ex.getCause());
				error404(context);
				return;
			}
		} else {
			data = file;
		}
		serveData(context, data);
	}

	@Override
	public Void visit(ErrorInfo error, Context context) throws IOException {
		error404(context);
		return null;
	}
	
	@Override
	public Void visit(ImageInfo image, Context context) throws IOException {
		throw new UnsupportedOperationException("Image data is always served directly.");
	}
	
	@Override
	public Void visit(AlbumInfo album, Context context) throws IOException {
		if (jsonRequested(context)) {
			serveJson(context.response(), album);
		} else {
			serveFolderHtml(context, album);
		}
		return null;
	}

	private static boolean jsonRequested(Context context) {
		return "json".equals(context.getParameter("type"));
	}

	@Override
	public Void visit(ListingInfo listing, Context context) throws IOException {
		HttpServletResponse response = context.response();
		
		response.setContentType("text/html");
		response.setCharacterEncoding("utf-8");
		try (Writer w = new OutputStreamWriter(response.getOutputStream(), "utf-8")) {
			try (XmlWriter out = new XmlWriter(w)) {
				out.begin(HTML);
				{
					out.begin(HEAD);
					{
						out.begin(META);
						out.attr(NAME_ATTR, "viewport");
						out.attr(CONTENT_ATTR, "width=device-width, initial-scale=1.0");
						out.endEmpty();

						out.begin(TITLE); out.append("VAlbum"); out.end();
					}
					out.end();
					
					out.begin(BODY);
					{
						ResourceRenderer.INSTANCE.visit(listing, out);
					}
					out.end();
				}
				out.end();
			}
		}
		
		return null;
	}

	private void serveFolderHtml(Context context, AlbumInfo album) throws IOException {
		HttpServletResponse response = context.response();
		HttpServletRequest request = context.request();

		response.setContentType("text/html");
		response.setCharacterEncoding("utf-8");
		try (Writer w = new OutputStreamWriter(response.getOutputStream(), "utf-8")) {
			try (XmlWriter out = new XmlWriter(w)) {
				out.begin(HTML);
				{
					out.begin(HEAD);
					{
						out.begin(META);
						out.attr(NAME_ATTR, "viewport");
						out.attr(CONTENT_ATTR, "width=device-width, initial-scale=1.0");
						out.endEmpty();
						
						// <link rel="stylesheet" type="text/css" href="https://cdn.sstatic.net/Sites/stackoverflow/primary.css?v=905b10e527f0">
						out.begin(LINK);
						out.attr(REL_ATTR, "stylesheet");
						out.attr(TYPE_ATTR, "text/css");
						out.attr(HREF_ATTR, request.getContextPath() + "/static/style/valbum.css");
						out.endEmpty();
						
						out.begin(TITLE);
						out.append("VAlbum");
						out.end();
					}
					out.end();
					out.begin(BODY);
					{
						album.visit(ResourceRenderer.INSTANCE, out);
					}
					out.end();
				}
				out.end();
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

	private void error404(Context context) {
		context.response().setStatus(HttpStatus.NOT_FOUND_404);
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

		public String getParameter(String name) {
			return request().getParameter(name);
		}

		/**
		 * TODO
		 */
		public HttpServletRequest request() {
			return _request;
		}
		
		/**
		 * TODO
		 */
		public HttpServletResponse response() {
			return _response;
		}
		
	}
}
