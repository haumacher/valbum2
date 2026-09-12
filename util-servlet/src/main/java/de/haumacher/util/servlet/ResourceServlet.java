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

	private final String _virtualPrefix;

	/**
	 * Creates a {@link ResourceServlet} serving the class path only.
	 *
	 * @param dataPath
	 *        The context-relative path of the JSON API, mentioned in the message sent when no web
	 *        application is deployed at all.
	 */
	public ResourceServlet(String dataPath) {
		this(null, dataPath, null);
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
		this(webRoot, dataPath, null);
	}

	/**
	 * Creates a {@link ResourceServlet} that also serves the application below a virtual base.
	 *
	 * @param webRoot
	 *        See {@link #ResourceServlet(Path, String)}.
	 * @param dataPath
	 *        See {@link #ResourceServlet(String)}.
	 * @param virtualPrefix
	 *        The first segment of a virtual base (<code>s</code> for the share links of issue
	 *        #51), <code>null</code> for serving the context root only. A request below
	 *        <code>/&lt;prefix&gt;/&lt;segment&gt;/</code> is served the same files with the
	 *        <code>&lt;base href&gt;</code> of the index page rewritten to that base, see
	 *        {@link WebRootResolver#virtualBase(String, String)}. Nothing here looks at the
	 *        segment: whether it opens anything is the JSON API's business, and an application
	 *        served under a dead one asks and says so.
	 */
	public ResourceServlet(Path webRoot, String dataPath, String virtualPrefix) {
		_virtualPrefix = virtualPrefix;
		if (webRoot != null) {
			_sources.add(new ContentSource.Directory(webRoot));
		}
		_sources.add(new ContentSource.Classpath(RESOURCE_PREFIX, ResourceServlet.class.getClassLoader()));
		_dataPath = dataPath;
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		String pathInfo = request.getPathInfo();

		String virtualBase = WebRootResolver.virtualBase(pathInfo, _virtualPrefix);
		String relative = virtualBase == null ? pathInfo : pathInfo.substring(virtualBase.length());
		if (virtualBase != null && relative.isEmpty()) {
			// "/s/<token>" without a trailing slash is the application's entry point, too.
			relative = "/";
		}

		String resource = WebRootResolver.resolve(relative, this::exists);
		if (resource == null) {
			sendNotFound(request, response, relative);
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
					String base = (request.getContextPath() == null ? "" : request.getContextPath())
						+ (virtualBase == null ? "" : virtualBase);
					byte[] html = rebaseIndex(in.readAllBytes(), base);
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
		sendNotFound(request, response, relative);
	}

	static boolean isIndex(String resource) {
		return resource.equals(WebRootResolver.INDEX) || resource.endsWith("/" + WebRootResolver.INDEX);
	}

	/**
	 * Rewrites a root-absolute {@code <base href="/">} in an index page to the given base.
	 *
	 * <p>Only a base of exactly {@code /} is touched: an application built with an explicit
	 * {@code --base-href} states its own deployment location and is left alone.</p>
	 *
	 * @param base
	 *        Where the application is mounted, without a trailing slash: the context path, and
	 *        below a virtual base the context path followed by it, see
	 *        {@link #ResourceServlet(Path, String, String)}.
	 */
	static byte[] rebaseIndex(byte[] html, String base) {
		if (base == null || base.isEmpty() || base.equals("/")) {
			return html;
		}
		String page = new String(html, StandardCharsets.UTF_8);
		String rebased = page.replaceFirst("<base href=\"/\">", "<base href=\"" + base + "/\">");
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
