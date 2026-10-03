/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.oidc.OidcLogins;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * The one address a provider of OpenID Connect sends the browser back to,
 * <code>&lt;context&gt;/oidc/callback</code>, see issue #200.
 *
 * <p>
 * One address for every space: the space, the link and the person are in the <code>state</code>,
 * which {@link OidcLogins#callback} takes back exactly once. A sign-in that was under way is
 * answered <code>303</code> to the link it came from, its outcome behind <code>#oidc=</code>; a
 * state that was not is answered <code>400</code> with a short page saying so, which repeats
 * nothing of the request. Where no provider is offered (none configured, or no
 * <code>VALBUM_PUBLIC_URL</code>), the address is not there: <code>404</code>.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class OidcCallbackServlet extends HttpServlet {

	private static final Logger LOG = Logger.getLogger(OidcCallbackServlet.class.getName());

	/** What an address below <code>/oidc/</code> that is not there is answered. */
	static final String NOT_HERE = "There is nothing at this address.";

	private final OidcLogins _logins;

	/** Creates the callback of the given sign-in. */
	public OidcCallbackServlet(OidcLogins logins) {
		_logins = logins;
	}

	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
		String path = request.getPathInfo();
		if (!_logins.isAvailable() || !("/" + OidcLogins.CALLBACK).equals(path)) {
			LOG.warning("Refusing '" + OidcLogins.URL_SEGMENT + (path == null ? "" : path) + "': "
				+ (_logins.isAvailable() ? "no such address" : "no sign-in provider is offered") + ".");
			notFound(response);
			return;
		}
		response.setHeader("Cache-Control", "no-store");
		response.setHeader("Referrer-Policy", "no-referrer");
		String location;
		try {
			location = _logins.callback(request.getParameterMap());
		} catch (OidcLogins.Refused ex) {
			refusalPage(response, ex.getStatus(), ex.getMessage());
			return;
		}
		response.setStatus(HttpServletResponse.SC_SEE_OTHER);
		response.setHeader("Location", location);
	}

	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
		// The flow answers by redirect, never by a form post.
		notFound(response);
	}

	private static void notFound(HttpServletResponse response) throws IOException {
		response.setStatus(HttpServletResponse.SC_NOT_FOUND);
		response.setContentType("application/json");
		response.setCharacterEncoding("utf-8");
		response.setHeader("Cache-Control", "no-store");
		try (Writer writer = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8);
				JsonWriter out = new JsonWriter(new WriterAdapter(writer))) {
			ErrorInfo.create().setMessage(NOT_HERE).writeTo(out);
		}
	}

	/** A page for a person whose browser landed here: the sentence, escaped, and nothing of the request. */
	private static void refusalPage(HttpServletResponse response, int status, String message) throws IOException {
		response.setStatus(status);
		response.setContentType("text/html");
		response.setCharacterEncoding("utf-8");
		String text = SharePreview.escape(message);
		try (Writer writer = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8)) {
			writer.write("<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\">"
				+ "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
				+ "<title>VAlbum</title></head>\n<body style=\"font-family: sans-serif; margin: 2em; max-width: 40em\">"
				+ "<p>" + text + "</p></body></html>\n");
		}
	}
}
