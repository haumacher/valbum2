package de.haumacher.imageServer.shared.model;

/**
 * A sign-in through OpenID Connect was started, the answer of <code>?action=oidc-start</code> (issue #200).
 */
public class OidcStarted extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.OidcStarted} instance.
	 */
	public static de.haumacher.imageServer.shared.model.OidcStarted create() {
		return new de.haumacher.imageServer.shared.model.OidcStarted();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.OidcStarted} type in JSON format. */
	public static final String OIDC_STARTED__TYPE = "OidcStarted";

	/** @see #getUrl() */
	private static final String URL__PROP = "url";

	/** @see #getBinding() */
	private static final String BINDING__PROP = "binding";

	/** @see #getExpires() */
	private static final String EXPIRES__PROP = "expires";

	/** @see #getReturnUrl() */
	private static final String RETURN_URL__PROP = "returnUrl";

	private String _url = "";

	private String _binding = "";

	private String _expires = "";

	private String _returnUrl = "";

	/**
	 * Creates a {@link OidcStarted} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.OidcStarted#create()
	 */
	protected OidcStarted() {
		super();
	}

	/**
	 * The provider's address to navigate the browser to.
	 */
	public final String getUrl() {
		return _url;
	}

	/**
	 * @see #getUrl()
	 */
	public de.haumacher.imageServer.shared.model.OidcStarted setUrl(String value) {
		internalSetUrl(value);
		return this;
	}

	/** Internal setter for {@link #getUrl()} without chain call utility. */
	protected final void internalSetUrl(String value) {
		_url = value;
	}

	/**
	 * The secret that ties the sign-in to whoever started it: kept by the page (in its session
	 * storage, across the provider's pages) and sent with {@link OidcExchange#getBinding()}. A sign-in
	 * finished in a browser that did not start it ends in nothing.
	 */
	public final String getBinding() {
		return _binding;
	}

	/**
	 * @see #getBinding()
	 */
	public de.haumacher.imageServer.shared.model.OidcStarted setBinding(String value) {
		internalSetBinding(value);
		return this;
	}

	/** Internal setter for {@link #getBinding()} without chain call utility. */
	protected final void internalSetBinding(String value) {
		_binding = value;
	}

	/**
	 * Until when the sign-in may be finished, an ISO-8601 instant.
	 */
	public final String getExpires() {
		return _expires;
	}

	/**
	 * @see #getExpires()
	 */
	public de.haumacher.imageServer.shared.model.OidcStarted setExpires(String value) {
		internalSetExpires(value);
		return this;
	}

	/** Internal setter for {@link #getExpires()} without chain call utility. */
	protected final void internalSetExpires(String value) {
		_expires = value;
	}

	/**
	 * The page the browser comes back to, <code>#oidc=&lt;code&gt;</code> appended (issue #233):
	 * spelled from <code>VALBUM_PUBLIC_URL</code>. A page whose own address has another origin cannot
	 * finish the sign-in &mdash; what it kept for the return lies in its own storage &mdash; and
	 * says so before it leaves rather than after.
	 */
	public final String getReturnUrl() {
		return _returnUrl;
	}

	/**
	 * @see #getReturnUrl()
	 */
	public de.haumacher.imageServer.shared.model.OidcStarted setReturnUrl(String value) {
		internalSetReturnUrl(value);
		return this;
	}

	/** Internal setter for {@link #getReturnUrl()} without chain call utility. */
	protected final void internalSetReturnUrl(String value) {
		_returnUrl = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.OidcStarted readOidcStarted(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.OidcStarted result = new de.haumacher.imageServer.shared.model.OidcStarted();
		result.readContent(in);
		return result;
	}

	@Override
	public final void writeTo(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		writeContent(out);
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name(URL__PROP);
		out.value(getUrl());
		out.name(BINDING__PROP);
		out.value(getBinding());
		out.name(EXPIRES__PROP);
		out.value(getExpires());
		out.name(RETURN_URL__PROP);
		out.value(getReturnUrl());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case URL__PROP: setUrl(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case BINDING__PROP: setBinding(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case EXPIRES__PROP: setExpires(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case RETURN_URL__PROP: setReturnUrl(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
