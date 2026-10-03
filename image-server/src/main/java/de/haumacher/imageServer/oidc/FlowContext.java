/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.oidc;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.pac4j.core.context.Cookie;
import org.pac4j.core.context.WebContext;

/**
 * The one request pac4j is shown of a sign-in, see issue #200.
 *
 * <p>
 * pac4j asks its {@link WebContext} for the request's parameters and little else: the start reads
 * nothing of the request at all, and the callback reads the parameters the provider sent back.
 * This hands it exactly those and nothing of the servlet request &mdash; no header, no cookie, no
 * session &mdash; so that what pac4j decides depends on nothing the server did not choose to give
 * it. Everything it would write to a response is kept here and never sent: the servlet answers
 * for itself.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
final class FlowContext implements WebContext {

	private final String _method;

	private final String _url;

	private final Map<String, String[]> _parameters;

	private final Map<String, Object> _attributes = new HashMap<>();

	private final Map<String, String> _responseHeaders = new HashMap<>();

	/**
	 * Creates a {@link FlowContext}.
	 *
	 * @param method
	 *        The HTTP method the request is taken to be made with.
	 * @param url
	 *        The address the request is taken to be made to.
	 * @param parameters
	 *        Its parameters.
	 */
	FlowContext(String method, String url, Map<String, String[]> parameters) {
		_method = method;
		_url = url;
		_parameters = Collections.unmodifiableMap(new HashMap<>(parameters));
	}

	@Override
	public Optional<String> getRequestParameter(String name) {
		String[] values = _parameters.get(name);
		return values == null || values.length == 0 ? Optional.empty() : Optional.ofNullable(values[0]);
	}

	@Override
	public Map<String, String[]> getRequestParameters() {
		return _parameters;
	}

	@Override
	public Optional<Object> getRequestAttribute(String name) {
		return Optional.ofNullable(_attributes.get(name));
	}

	@Override
	public void setRequestAttribute(String name, Object value) {
		_attributes.put(name, value);
	}

	@Override
	public Optional<String> getRequestHeader(String name) {
		return Optional.empty();
	}

	@Override
	public String getRequestMethod() {
		return _method;
	}

	@Override
	public String getRemoteAddr() {
		return "";
	}

	@Override
	public void setResponseHeader(String name, String value) {
		_responseHeaders.put(name, value);
	}

	@Override
	public Optional<String> getResponseHeader(String name) {
		return Optional.ofNullable(_responseHeaders.get(name));
	}

	@Override
	public void setResponseContentType(String content) {
		// Never sent: the servlet answers for itself.
	}

	@Override
	public String getServerName() {
		return java.net.URI.create(_url).getHost();
	}

	@Override
	public int getServerPort() {
		return java.net.URI.create(_url).getPort();
	}

	@Override
	public String getScheme() {
		return java.net.URI.create(_url).getScheme();
	}

	@Override
	public boolean isSecure() {
		return "https".equalsIgnoreCase(getScheme());
	}

	@Override
	public String getFullRequestURL() {
		return _url;
	}

	@Override
	public Collection<Cookie> getRequestCookies() {
		return List.of();
	}

	@Override
	public void addResponseCookie(Cookie cookie) {
		// Never sent: a sign-in of this server keeps nothing in the browser.
	}

	@Override
	public String getPath() {
		return java.net.URI.create(_url).getPath();
	}
}
