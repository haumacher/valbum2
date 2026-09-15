/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.InvitationStore;
import de.haumacher.imageServer.auth.ShareStore;
import de.haumacher.imageServer.auth.Spaces;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.ui.Settings;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import de.haumacher.util.servlet.ResourceServlet;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The front door of a multi-space server: the first path segment says which space (issue #82).
 *
 * <p>
 * <code>&lt;context&gt;/&lt;space&gt;/data/...</code> is the data root of that space and
 * <code>&lt;context&gt;/&lt;space&gt;/</code> is the application with its
 * <code>&lt;base href&gt;</code> rebased onto it, exactly as a share session at
 * <code>&lt;context&gt;/s/&lt;token&gt;/</code> is. Everything else — the application's own assets,
 * the share and invitation sessions, the context root — is served as it always was.
 * </p>
 *
 * <p>
 * A space's requests reach that space's own {@link ImageServlet}, built on that space's folder and
 * that space's {@link de.haumacher.imageServer.auth.AuthService}. The request handed on says where
 * it is: its servlet path is <code>/&lt;space&gt;/data</code>, so every path the answer spells —
 * a redirect, a {@link de.haumacher.imageServer.auth.AuthService.Location}, the URL of a share
 * link — is spelled in the coordinates the caller used.
 * </p>
 *
 * <p>
 * An address naming a space this server does not host is answered <code>404</code> with an
 * {@link ErrorInfo} saying so, never with the application: a person who mistyped a space is told
 * that there is no such space, not shown an album browser that cannot load anything.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class SpaceServlet extends HttpServlet {

	/** The message an address naming an unknown space is refused with. */
	public static String noSuchSpace(String segment) {
		return "This server hosts no space '" + segment + "'.";
	}

	/**
	 * The message a session address at the context root is refused with, see issue #82.
	 *
	 * <p>
	 * A share link and an invitation belong to a space and are opened under it. At the context
	 * root of a multi-space server there is no data root, so the application served there could
	 * only fail to load anything; saying where such a link lives is the useful answer.
	 * </p>
	 */
	public static String sessionNeedsSpace(String segment) {
		return "This server hosts no space '" + segment + "'; a share link or an invitation lives "
			+ "under its space, as '<space>/" + segment + "/<token>/'.";
	}

	private final Spaces _spaces;

	private final ResourceServlet _app;

	private final Map<String, ImageServlet> _data = new LinkedHashMap<>();

	/**
	 * Creates a {@link SpaceServlet}.
	 *
	 * @param spaces
	 *        The spaces of this server, see {@link Spaces#detect}.
	 * @param app
	 *        The static content of the application, already told which segments are spaces.
	 */
	public SpaceServlet(Spaces spaces, ResourceServlet app) throws IOException {
		_spaces = spaces;
		_app = app;
		for (Spaces.Space space : spaces.getSpaces()) {
			_data.put(space.getSegment(), new ImageServlet(space.getRoot().toFile(), space.getAuth(),
				space.getSegment()));
		}
	}

	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);
		// The servlets below this one are built here, not mapped, so they are initialised here.
		_app.init(config);
		for (ImageServlet servlet : _data.values()) {
			servlet.init(config);
		}
	}

	@Override
	public void destroy() {
		for (ImageServlet servlet : _data.values()) {
			servlet.destroy();
		}
		_app.destroy();
		super.destroy();
	}

	@Override
	protected void service(HttpServletRequest request, HttpServletResponse response)
			throws ServletException, IOException {
		String pathInfo = request.getPathInfo();
		String segment = firstSegment(pathInfo);

		if (segment != null && _spaces.isSpace(segment)) {
			String rest = pathInfo.substring(1 + segment.length());
			if (isData(rest)) {
				String inSpace = rest.substring(Settings.DATA_PREFIX.length());
				_data.get(segment).service(new InSpace(request, "/" + segment + Settings.DATA_PREFIX, inSpace),
					response);
				return;
			}
			// The application, rebased onto "/<space>/" by the resource servlet.
			_app.service(request, response);
			return;
		}

		if (segment != null && isData(pathInfo.substring(1 + segment.length()))) {
			// "/<something>/data/..." can only ever have meant a space.
			refuseSpace(response, noSuchSpace(segment));
			return;
		}

		if (segment != null && isSessionPrefix(segment)) {
			// A session at the context root: on a multi-space server it belongs under its space.
			refuseSpace(response, sessionNeedsSpace(segment));
			return;
		}

		if (looksLikeASpace(pathInfo, segment)) {
			refuseSpace(response, noSuchSpace(segment));
			return;
		}

		_app.service(request, response);
	}

	/**
	 * Whether the given path is a person opening a space that does not exist.
	 *
	 * <p>
	 * On a multi-space server the first segment of a path a person types <em>is</em> a space. What
	 * saves the application's own files from that rule is that they exist: an asset is served, a
	 * share or invitation session keeps its own prefix, and the context root itself carries no
	 * segment at all. What is left is a name that was meant to be a space and is not one.
	 * </p>
	 */
	private boolean looksLikeASpace(String pathInfo, String segment) {
		if (segment == null || segment.isEmpty()) {
			return false;
		}
		return !_app.hasFile(pathInfo);
	}

	/** Whether the given first segment is the prefix of a share or an invitation session. */
	private static boolean isSessionPrefix(String segment) {
		return ShareStore.URL_SEGMENT.equals(segment) || InvitationStore.URL_SEGMENT.equals(segment);
	}

	private void refuseSpace(HttpServletResponse response, String message) throws IOException {
		response.setStatus(HttpServletResponse.SC_NOT_FOUND);
		response.setContentType("application/json");
		response.setHeader("Access-Control-Allow-Origin", "*");
		response.setHeader("Cache-Control", "no-store");
		try (Writer writer = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8);
				JsonWriter out = new JsonWriter(new WriterAdapter(writer))) {
			ErrorInfo.create().setMessage(message).writeTo(out);
		}
	}

	private static boolean isData(String rest) {
		return rest.equals(Settings.DATA_PREFIX) || rest.startsWith(Settings.DATA_PREFIX + "/")
			|| rest.startsWith(Settings.DATA_PREFIX + "?");
	}

	/** The first segment of the given path, <code>null</code> if there is none. */
	static String firstSegment(String pathInfo) {
		if (pathInfo == null || !pathInfo.startsWith("/") || pathInfo.length() == 1) {
			return null;
		}
		int end = pathInfo.indexOf('/', 1);
		String segment = end < 0 ? pathInfo.substring(1) : pathInfo.substring(1, end);
		return segment.isEmpty() ? null : segment;
	}

	/** The request as the space's own servlet sees it: below <code>/&lt;space&gt;/data</code>. */
	static final class InSpace extends HttpServletRequestWrapper {

		private final String _servletPath;

		private final String _pathInfo;

		InSpace(HttpServletRequest request, String servletPath, String pathInfo) {
			super(request);
			_servletPath = servletPath;
			_pathInfo = pathInfo;
		}

		@Override
		public String getServletPath() {
			return _servletPath;
		}

		@Override
		public String getPathInfo() {
			return _pathInfo.isEmpty() ? null : _pathInfo;
		}
	}

}
