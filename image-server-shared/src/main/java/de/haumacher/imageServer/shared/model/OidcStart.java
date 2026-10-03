package de.haumacher.imageServer.shared.model;

/**
 * Starting a sign-in through OpenID Connect on a personal share link,
 * <code>&lt;data&gt;/?action=oidc-start</code> (issue #200).
 *
 * <p>
 * Sent like <code>?action=prove-email</code>, with the link's token as the bearer (and a recognised
 * contact's credential beside it, who adds an address). The answer is an {@link OidcStarted}: the
 * page navigates to its {@link OidcStarted#getUrl()}, the provider sends the browser back through the
 * server's one callback <code>&lt;context&gt;/oidc/callback</code>, and that lands on the link again
 * as <code>&lt;link base&gt;#oidc=&lt;code&gt;</code>, which <code>?action=oidc-exchange</code>
 * turns into the {@link ContactCredential} a mailed code would have given.
 * </p>
 */
public class OidcStart extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.OidcStart} instance.
	 */
	public static de.haumacher.imageServer.shared.model.OidcStart create() {
		return new de.haumacher.imageServer.shared.model.OidcStart();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.OidcStart} type in JSON format. */
	public static final String OIDC_START__TYPE = "OidcStart";

	/** @see #getProvider() */
	private static final String PROVIDER__PROP = "provider";

	/** @see #isRemember() */
	private static final String REMEMBER__PROP = "remember";

	/** @see #getDisplayName() */
	private static final String DISPLAY_NAME__PROP = "displayName";

	private String _provider = "";

	private boolean _remember = false;

	private String _displayName = "";

	/**
	 * Creates a {@link OidcStart} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.OidcStart#create()
	 */
	protected OidcStart() {
		super();
	}

	/**
	 * The id of the provider, as the <code>oidc:&lt;id&gt;</code> of a {@link ProofMethod} names it.
	 */
	public final String getProvider() {
		return _provider;
	}

	/**
	 * @see #getProvider()
	 */
	public de.haumacher.imageServer.shared.model.OidcStart setProvider(String value) {
		internalSetProvider(value);
		return this;
	}

	/** Internal setter for {@link #getProvider()} without chain call utility. */
	protected final void internalSetProvider(String value) {
		_provider = value;
	}

	/**
	 * Whether to remember this browser: 90 days renewed on use, else 24 hours.
	 */
	public final boolean isRemember() {
		return _remember;
	}

	/**
	 * @see #isRemember()
	 */
	public de.haumacher.imageServer.shared.model.OidcStart setRemember(boolean value) {
		internalSetRemember(value);
		return this;
	}

	/** Internal setter for {@link #isRemember()} without chain call utility. */
	protected final void internalSetRemember(boolean value) {
		_remember = value;
	}

	/**
	 * The name the contact wants to be greeted by; empty takes the name the provider knows them by.
	 * Ignored where the caller is recognised already.
	 */
	public final String getDisplayName() {
		return _displayName;
	}

	/**
	 * @see #getDisplayName()
	 */
	public de.haumacher.imageServer.shared.model.OidcStart setDisplayName(String value) {
		internalSetDisplayName(value);
		return this;
	}

	/** Internal setter for {@link #getDisplayName()} without chain call utility. */
	protected final void internalSetDisplayName(String value) {
		_displayName = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.OidcStart readOidcStart(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.OidcStart result = new de.haumacher.imageServer.shared.model.OidcStart();
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
		out.name(PROVIDER__PROP);
		out.value(getProvider());
		out.name(REMEMBER__PROP);
		out.value(isRemember());
		out.name(DISPLAY_NAME__PROP);
		out.value(getDisplayName());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case PROVIDER__PROP: setProvider(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case REMEMBER__PROP: setRemember(in.nextBoolean()); break;
			case DISPLAY_NAME__PROP: setDisplayName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
