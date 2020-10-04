/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer;

import static de.haumacher.util.xml.HTML.*;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;

import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.MetadataException;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumProperties;
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
public class ImageServlet extends HttpServlet {

	private static Set<String> ACCEPTED = new HashSet<>(Arrays.asList("jpg", "mp4"));
	
	private static final FileFilter IMAGES = f -> {
		return f.isFile() && ACCEPTED.contains(Util.suffix(f.getName()));
	};

	private static final Logger LOG = Logger.getLogger(ImageServlet.class.getName());

	private static final FileFilter DIRECTORIES = f -> f.isDirectory();

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
		
		Path resourcePath;
		if (pathInfo == null) {
			resourcePath = _basePath;
		} else {
			Path path = Paths.get(pathInfo.substring(1)).normalize();
			if (path.startsWith("..")) {
				error404(response);
				return;
			}
			resourcePath = _basePath.resolve(path);
		}
		
		File file = resourcePath.toFile();
		if (!file.exists()) {
			error404(response);
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
			
			serveFolder(request, response, file);
		} else if (IMAGES.accept(file)) {
			serveImage(request, response, file);
		} else {
			error404(response);
		}
	}

	private void serveFolder(HttpServletRequest request, HttpServletResponse response, File dir) throws IOException {
		File[] files = dir.listFiles(IMAGES);
		if (files == null) {
			error404(response);
			return;
		}
	
		if (files.length == 0) {
			serveListing(request, response, dir);
			return;
		}
		
		AlbumInfo album = new AlbumInfo();
		
		for (File file : files) {
			try {
				ImageData image = ImageData.analyze(file);
				if (image == null) {
					continue;
				}
				album.addImage(image);
			} catch (ImageProcessingException | IOException
					| MetadataException ex) {
				LOG.log(Level.WARNING,
						"Cannot access '" + file + "': " + ex.getMessage(), ex);
				continue;
			}
		}
		
		Collections.sort(album.getImages(), (a, b) -> a.getDate().compareTo(b.getDate()));
	
		File indexResource = new File(dir, "index.json");
		AlbumProperties header = album.getHeader();
		if (indexResource.exists()) {
			try (InputStream in = new FileInputStream(indexResource)) {
				JsonReader json = new JsonReader(
						new InputStreamReader(in, "utf-8"));
				header.readFrom(json);
			}
		} else {
			String dirName = dir.getName();
			
			Pattern prefixPattern = Pattern.compile("[-_\\.\\s0-9]*");
			Matcher matcher = prefixPattern.matcher(dirName);
			if (matcher.lookingAt()) {
				header.setTitle(dirName.substring(matcher.end()));
				header.setSubTitle(dirName.substring(0, matcher.end()));
			} else {
				header.setTitle(dirName);
			}
		}
		
		if (jsonRequested(request)) {
			serveJson(response, album);
		} else {
			serveFolderHtml(request, response, album);
		}
	}

	private static boolean jsonRequested(HttpServletRequest request) {
		return "json".equals(request.getParameter("type"));
	}

	private void serveListing(HttpServletRequest request, HttpServletResponse response, File dir) throws IOException {
		File[] dirs = dir.listFiles(DIRECTORIES);
		if (dirs == null) {
			error404(response);
			return;
		}
		
		Arrays.sort(dirs, (f1, f2) -> f1.getName().compareToIgnoreCase(f2.getName()));
		
		ListingInfo listing = new ListingInfo(dir.getName());
		load(listing, dirs);
		
		if (jsonRequested(request)) {
			serveJson(response, listing);
		} else {
			serveListingHtml(response, listing);
		}
	}
	
	private void serveListingHtml(HttpServletResponse response, ListingInfo listing) throws IOException {
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
	}

	private void load(ListingInfo listing, File[] dirs) {
		for (File dir : dirs) {
			listing.addFolder(dir.getName());
		}
	}

	private void serveFolderHtml(HttpServletRequest request, HttpServletResponse response, AlbumInfo album) throws IOException {
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
						out.begin(H1);
						AlbumProperties header = album.getHeader();
						out.append(header.getTitle());
						out.end();
						out.begin(H2);
						out.append(header.getSubTitle());
						out.end();

						out.begin(DIV);
						out.attr(CLASS_ATTR, "image-rows");
						{
							ImageRow row = new ImageRow(1280, 400);
							for (ImageInfo image : album.getImages()) {
								if (row.isComplete()) {
									writeRow(out, row);
									row.clear();
								}
								row.add(image);
							}
							writeRow(out, row);
						}
						out.end();
					}
					out.end();
				}
				out.end();
			}
		}
	}

	private void writeRow(XmlWriter out, ImageRow row) throws IOException {
		if (row.getSize() == 0) {
			return;
		}
		double rowHeight = row.getHeight();
		int spacing = row.getSpacing();

		out.begin(DIV);
		out.attr("style", "display: table; margin-top: " + spacing + "px;");
		{
			out.begin(DIV);
			out.attr(STYLE_ATTR, "display: table-row;");
			for (int n = 0, cnt = row.getSize(); n < cnt; n++) {
				ImageInfo image = row.getImage(n);

				out.begin(DIV);
				out.openAttr(STYLE_ATTR);
				out.append("display: table-cell;");
				out.closeAttr();
				{
					out.begin(A);
					out.openAttr(STYLE_ATTR);
					out.append("display: block;");
					if (n > 0) {
						out.append("margin-left: ");
						out.append(spacing);
						out.append("px;");
					}
					out.closeAttr();
					out.attr(HREF_ATTR, image.getName());
					{
						out.begin(IMG);
						
						out.openAttr(SRC_ATTR);
						out.append(image.getName());
						out.append("?type=tn");
						out.closeAttr();
						
						out.attr(WIDTH_ATTR, row.getScaledWidth(n));
						out.attr(HEIGHT_ATTR, rowHeight);
						out.end();
					}
					out.end();
				}
				out.end();
			}
			out.end();
		}
		out.end();
	}

	private void serveImage(HttpServletRequest request, HttpServletResponse response, File file) throws IOException {
		String type = request.getParameter("type");
		if (!"tn".equals(type)) {
			serveData(request, response, file);
			return;
		}
		
		File previewCache;
		try {
			previewCache = PreviewCache.createPreview(file);
		} catch (PreviewException ex) {
			LOG.log(Level.WARNING, ex.getMessage(), ex.getCause());
			error404(response);
			return;
		}
		
		serveData(request, response, previewCache);
	}

	private void serveJson(HttpServletResponse response, Resource album) throws IOException {
		response.setContentType("application/json");
		try (JsonWriter json = new JsonWriter(new OutputStreamWriter(response.getOutputStream(), "utf-8"))) {
			album.writePolymorphic(json);
		}
	}

	private void serveData(HttpServletRequest request, HttpServletResponse response, File file) throws IOException {
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

	private void error404(HttpServletResponse response) {
		response.setStatus(HttpStatus.NOT_FOUND_404);
	}
}
