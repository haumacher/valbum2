/*
 * Copyright (c) 2020 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.util.servlet;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link HttpServlet} serving static web content from a list of {@link ContentSource}s.
 *
 * <p>
 * By default, content is taken from <code>/META-INF/resources</code> of the class path. A
 * directory of the file system can be given precedence, see
 * {@link #ResourceServlet(Path, String)}.
 * </p>
 *
 * <p>
 * A request that does not resolve to an existing file but looks like a client-side route falls
 * back to <code>index.html</code>, see {@link WebRootResolver}.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class ResourceServlet extends HttpServlet {

	/**
	 * The class path prefix static resources are read from.
	 */
	public static final String RESOURCE_PREFIX = "/META-INF/resources";

	private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

	private final List<ContentSource> _sources = new ArrayList<>();

	private final String _dataPath;

	/**
	 * Creates a {@link ResourceServlet} serving the class path only.
	 *
	 * @param dataPath
	 *        The context-relative path of the JSON API, mentioned in the message sent when no web
	 *        application is deployed at all.
	 */
	public ResourceServlet(String dataPath) {
		this(null, dataPath);
	}

	/**
	 * Creates a {@link ResourceServlet}.
	 *
	 * @param webRoot
	 *        A directory of the file system taking precedence over the class path,
	 *        <code>null</code> for serving the class path only.
	 * @param dataPath
	 *        See {@link #ResourceServlet(String)}.
	 */
	public ResourceServlet(Path webRoot, String dataPath) {
		if (webRoot != null) {
			_sources.add(new ContentSource.Directory(webRoot));
		}
		_sources.add(new ContentSource.Classpath(RESOURCE_PREFIX, ResourceServlet.class.getClassLoader()));
		_dataPath = dataPath;
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		String pathInfo = request.getPathInfo();

		String resource = WebRootResolver.resolve(pathInfo, this::exists);
		if (resource == null) {
			sendNotFound(request, response, pathInfo);
			return;
		}

		for (ContentSource source : _sources) {
			InputStream in = source.open(resource);
			if (in == null) {
				continue;
			}
			try {
				response.setContentType(contentType(resource));
				if (isIndex(resource)) {
					// The Flutter web build hard-codes <base href="/">; the application is mounted at the
					// context path, so the base must follow it or every asset request misses the app.
					byte[] html = rebaseIndex(in.readAllBytes(), request.getContextPath());
					response.setContentLength(html.length);
					response.getOutputStream().write(html);
				} else {
					long size = source.size(resource);
					if (size >= 0) {
						response.setContentLengthLong(size);
					}
					Util.sendBytes(response, in);
				}
			} finally {
				in.close();
			}
			return;
		}

		// Vanished between the existence check and the delivery.
		sendNotFound(request, response, pathInfo);
	}

	static boolean isIndex(String resource) {
		return resource.equals(WebRootResolver.INDEX) || resource.endsWith("/" + WebRootResolver.INDEX);
	}

	/**
	 * Rewrites a root-absolute {@code <base href="/">} in an index page to the given context path.
	 *
	 * <p>Only a base of exactly {@code /} is touched: an application built with an explicit
	 * {@code --base-href} states its own deployment location and is left alone.</p>
	 */
	static byte[] rebaseIndex(byte[] html, String contextPath) {
		if (contextPath == null || contextPath.isEmpty() || contextPath.equals("/")) {
			return html;
		}
		String page = new String(html, StandardCharsets.UTF_8);
		String rebased = page.replaceFirst("<base href=\"/\">", "<base href=\"" + contextPath + "/\">");
		return rebased.getBytes(StandardCharsets.UTF_8);
	}

	private boolean exists(String resource) {
		for (ContentSource source : _sources) {
			if (source.exists(resource)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The content type to announce for the given web-root-relative path.
	 */
	protected String contentType(String resource) {
		String suffix = Util.suffix(resource);
		if (suffix != null) {
			switch (suffix) {
				case "wasm":
					return "application/wasm";
				case "json":
					return "application/json;charset=utf-8";
				case "js":
					return "text/javascript;charset=utf-8";
				default:
					break;
			}
		}

		String mimeType = getServletContext().getMimeType(resource);
		if (mimeType == null) {
			return DEFAULT_CONTENT_TYPE;
		}
		if (mimeType.startsWith("text/") && !mimeType.contains("charset")) {
			return mimeType + ";charset=utf-8";
		}
		return mimeType;
	}

	private void sendNotFound(HttpServletRequest request, HttpServletResponse response, String pathInfo) throws IOException {
		response.setStatus(HttpServletResponse.SC_NOT_FOUND);

		if (!WebRootResolver.isRoute(pathInfo)) {
			// A request for a file that simply does not exist.
			return;
		}

		// There is no web application at all: say so, instead of answering an empty 404.
		response.setContentType("text/plain;charset=utf-8");
		response.getOutputStream().write("""
			The VAlbum web application is not deployed on this server.

			Bundle the Flutter web build into the server JAR, or start the server with
			'--webroot <directory>' pointing to the output of 'flutter build web'.

			The JSON API of this server is available at %s/?type=json
			""".formatted(request.getContextPath() + _dataPath).getBytes(StandardCharsets.UTF_8));
	}

}
