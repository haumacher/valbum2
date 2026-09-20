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

	private final String[] _virtualPrefixes;

	private java.util.Collection<String> _baseSegments = java.util.Collections.emptySet();

	/**
	 * Creates a {@link ResourceServlet} serving the class path only.
	 *
	 * @param dataPath
	 *        The context-relative path of the JSON API, mentioned in the message sent when no web
	 *        application is deployed at all.
	 */
	public ResourceServlet(String dataPath) {
		this(null, dataPath, new String[0]);
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
		this(webRoot, dataPath, new String[0]);
	}

	/**
	 * Creates a {@link ResourceServlet} that also serves the application below a virtual base.
	 *
	 * @param webRoot
	 *        See {@link #ResourceServlet(Path, String)}.
	 * @param dataPath
	 *        See {@link #ResourceServlet(String)}.
	 * @param virtualPrefixes
	 *        The first segments a virtual base may have (<code>s</code> for the share links of
	 *        issue #51, <code>i</code> for the invitations of issue #52); none for serving the
	 *        context root only. A request below
	 *        <code>/&lt;prefix&gt;/&lt;segment&gt;/</code> is served the same files with the
	 *        <code>&lt;base href&gt;</code> of the index page rewritten to that base, see
	 *        {@link WebRootResolver#virtualBase(String, String...)}. Nothing here looks at the
	 *        segment: whether it opens anything is the JSON API's business, and an application
	 *        served under a dead one asks and says so.
	 */
	public ResourceServlet(Path webRoot, String dataPath, String... virtualPrefixes) {
		_virtualPrefixes = virtualPrefixes == null ? new String[0] : virtualPrefixes;
		if (webRoot != null) {
			_sources.add(new ContentSource.Directory(webRoot));
		}
		_sources.add(new ContentSource.Classpath(RESOURCE_PREFIX, ResourceServlet.class.getClassLoader()));
		_dataPath = dataPath;
	}

	/**
	 * The first path segments that are a base of the application in their own right, see
	 * {@link WebRootResolver#singleSegmentBase(String, java.util.Collection)}.
	 *
	 * <p>
	 * The spaces of a multi-space server (issue #82). Fixed once at start-up: a space is created on
	 * disk by hand and picked up by a restart.
	 * </p>
	 */
	public void setBaseSegments(java.util.Collection<String> segments) {
		_baseSegments = segments == null ? java.util.Collections.emptySet() : segments;
	}

	/**
	 * Whether the application may be served for the token of a virtual session base, and where to
	 * send the caller instead.
	 *
	 * <p>
	 * The static handler never looks at a token itself and must not learn what one means: what a
	 * token opens is the JSON API's business. This is the one question it asks somebody else, and
	 * only for a <em>page load</em> — the entry point itself and the extension-less deep links
	 * below it. The application's own files are served whatever the answer would be, because a
	 * page that is being served needs them.
	 * </p>
	 */
	@FunctionalInterface
	public interface SessionGuard {

		/**
		 * Where a page load below the given session base belongs instead.
		 *
		 * @param appBase
		 *        What the session lives below, without a trailing slash: the empty string at the
		 *        context root, <code>/&lt;space&gt;</code> below a space, see
		 *        {@link WebRootResolver#spaceSessionBase}.
		 * @param prefix
		 *        The first segment of the session base, one of the virtual prefixes this servlet
		 *        was created with.
		 * @param token
		 *        The second segment, the opaque token.
		 * @return The context-relative path (with a leading slash) to redirect the page load to,
		 *         <code>null</code> to serve the application as usual.
		 */
		String redirect(String appBase, String prefix, String token);
	}

	private SessionGuard _sessionGuard;

	/**
	 * Installs the {@link SessionGuard} asked before a page below a virtual session base is served.
	 *
	 * <p>
	 * <code>null</code> (the default) serves every session base, which is what this servlet did
	 * before anybody asked.
	 * </p>
	 */
	public void setSessionGuard(SessionGuard guard) {
		_sessionGuard = guard;
	}

	/**
	 * Rewrites the page served for a virtual session base before it goes out, see issue #104.
	 *
	 * <p>
	 * A messenger or a social network fetches a link without running the application and reads
	 * what the HTML itself says about the page (its <code>&lt;title&gt;</code> and its Open Graph
	 * tags). What a token opens is the JSON API's business, so the static handler asks somebody
	 * else what to put there and stays free of album knowledge, exactly as it does for the
	 * {@link SessionGuard}.
	 * </p>
	 */
	@FunctionalInterface
	public interface PageDecorator {

		/**
		 * The page to send for a page load below the given session base.
		 *
		 * @param request
		 *        The request being answered. A crawler needs absolute URLs, so the decorator has
		 *        to know the surface this server was reached under.
		 * @param html
		 *        The page as it would be sent, its <code>&lt;base href&gt;</code> already
		 *        rewritten.
		 * @param appBase
		 *        What the session lives below, see {@link SessionGuard#redirect(String, String, String)}.
		 * @param prefix
		 *        The first segment of the session base.
		 * @param token
		 *        The second segment, the opaque token.
		 * @return The page to send; the given one (or <code>null</code>) to send it unchanged.
		 */
		String decorate(HttpServletRequest request, String html, String appBase, String prefix, String token);
	}

	private PageDecorator _pageDecorator;

	/**
	 * Installs the {@link PageDecorator} asked before a page below a virtual session base is sent.
	 *
	 * <p>
	 * <code>null</code> (the default) sends the page as it is.
	 * </p>
	 */
	public void setPageDecorator(PageDecorator decorator) {
		_pageDecorator = decorator;
	}

	/**
	 * A resource the server answers below a virtual session base itself, see issue #104.
	 *
	 * <p>
	 * The preview picture a share link's card names lives below the link
	 * (<code>/s/&lt;token&gt;/cover.jpg</code>) and is no file of the web root, so it is asked for
	 * before the static resolution. What such a name means, and whether the token opens it, is
	 * again the JSON API's business.
	 * </p>
	 */
	@FunctionalInterface
	public interface SessionResource {

		/**
		 * Answers the request, if this is a resource of the session rather than a file of the
		 * application.
		 *
		 * @param appBase
		 *        What the session lives below, see {@link SessionGuard#redirect(String, String, String)}.
		 * @param prefix
		 *        The first segment of the session base.
		 * @param token
		 *        The second segment, the opaque token.
		 * @param relative
		 *        The path below the session base, with a leading slash
		 *        (<code>/cover.jpg</code>).
		 * @return Whether the request was answered here; <code>false</code> serves the
		 *         application's files as usual.
		 */
		boolean serve(HttpServletRequest request, HttpServletResponse response, String appBase, String prefix,
				String token, String relative) throws IOException;
	}

	private SessionResource _sessionResource;

	/**
	 * Installs the {@link SessionResource} asked before anything below a virtual session base is
	 * resolved against the web root.
	 *
	 * <p>
	 * <code>null</code> (the default) serves the application's files only.
	 * </p>
	 */
	public void setSessionResource(SessionResource resource) {
		_sessionResource = resource;
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		String pathInfo = request.getPathInfo();

		String virtualBase = WebRootResolver.virtualBase(pathInfo, _virtualPrefixes);
		// What the session lives below: the context root, or a space of a multi-space server.
		String appBase = "";
		String sessionBase = virtualBase;
		if (virtualBase == null) {
			// A session of a space is the longer base, so it is asked for before the space itself.
			virtualBase = WebRootResolver.spaceSessionBase(pathInfo, _baseSegments, _virtualPrefixes);
			if (virtualBase != null) {
				appBase = WebRootResolver.singleSegmentBase(pathInfo, _baseSegments);
				sessionBase = virtualBase.substring(appBase.length());
			}
		}
		if (virtualBase == null) {
			virtualBase = WebRootResolver.singleSegmentBase(pathInfo, _baseSegments);
		}
		String relative = virtualBase == null ? pathInfo : pathInfo.substring(virtualBase.length());
		if (virtualBase != null && relative.isEmpty()) {
			// "/s/<token>" (and "/i/<token>") without a trailing slash is the entry point, too.
			relative = "/";
		}

		String sessionPrefix = null;
		String sessionToken = null;
		if (sessionBase != null) {
			int slash = sessionBase.indexOf('/', 1);
			sessionPrefix = sessionBase.substring(1, slash);
			sessionToken = sessionBase.substring(slash + 1);
		}

		if (sessionPrefix != null && _sessionGuard != null && WebRootResolver.isRoute(relative)) {
			// A page load below a session base; an asset below it is served whatever the answer.
			String target = _sessionGuard.redirect(appBase, sessionPrefix, sessionToken);
			if (target != null) {
				response.sendRedirect(
					(request.getContextPath() == null ? "" : request.getContextPath()) + target);
				return;
			}
		}

		if (sessionPrefix != null && _sessionResource != null
			&& _sessionResource.serve(request, response, appBase, sessionPrefix, sessionToken, relative)) {
			// A resource of the session itself, which the web root does not hold.
			return;
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
					if (sessionPrefix != null && _pageDecorator != null && WebRootResolver.isRoute(relative)) {
						// What a messenger reads when it fetches the link, see issue #104.
						String decorated = _pageDecorator.decorate(request,
							new String(html, StandardCharsets.UTF_8), appBase, sessionPrefix, sessionToken);
						if (decorated != null) {
							html = decorated.getBytes(StandardCharsets.UTF_8);
						}
					}
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
	 *        {@link #ResourceServlet(Path, String, String...)}.
	 */
	static byte[] rebaseIndex(byte[] html, String base) {
		if (base == null || base.isEmpty() || base.equals("/")) {
			return html;
		}
		String page = new String(html, StandardCharsets.UTF_8);
		String rebased = page.replaceFirst("<base href=\"/\">", "<base href=\"" + base + "/\">");
		return rebased.getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * Whether a file is actually served at the given context-relative path.
	 *
	 * <p>
	 * The plain question, without the <code>index.html</code> fallback {@link WebRootResolver}
	 * makes for a client-side route: a caller that must tell an asset from a name somebody typed
	 * needs to know whether anything is really there (issue #82).
	 * </p>
	 */
	public boolean hasFile(String path) {
		String relative = WebRootResolver.normalize(path);
		if (relative == null) {
			return false;
		}
		String candidate = relative.isEmpty() || relative.endsWith("/")
			? relative + WebRootResolver.INDEX : relative;
		return exists(candidate);
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
