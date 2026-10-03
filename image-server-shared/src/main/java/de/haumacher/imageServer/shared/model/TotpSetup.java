package de.haumacher.imageServer.shared.model;

/**
 * Setting up an authenticator app, the answer of <code>&lt;data&gt;/?action=totp-setup</code> (issue
 * #208).
 *
 * <p>
 * Asked by a contact in a session of a personal link. The secret is pending until
 * <code>?action=totp-confirm</code> receives one code of it: a secret that never reached an app
 * signs nobody in. The app offers the same secret three ways: the {@link #getUri()} as a link that opens
 * the authenticator on the same phone, the {@link #getSecret()} to type ("Enter a setup key"), and on a
 * computer the QR code of the {@link #getUri()}.
 * </p>
 */
public class TotpSetup extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.TotpSetup} instance.
	 */
	public static de.haumacher.imageServer.shared.model.TotpSetup create() {
		return new de.haumacher.imageServer.shared.model.TotpSetup();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.TotpSetup} type in JSON format. */
	public static final String TOTP_SETUP__TYPE = "TotpSetup";

	/** @see #getSecret() */
	private static final String SECRET__PROP = "secret";

	/** @see #getUri() */
	private static final String URI__PROP = "uri";

	/** @see #getIssuer() */
	private static final String ISSUER__PROP = "issuer";

	/** @see #getAccount() */
	private static final String ACCOUNT__PROP = "account";

	private String _secret = "";

	private String _uri = "";

	private String _issuer = "";

	private String _account = "";

	/**
	 * Creates a {@link TotpSetup} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.TotpSetup#create()
	 */
	protected TotpSetup() {
		super();
	}

	/**
	 * The secret, Base32 without padding (160 bits, 32 characters).
	 */
	public final String getSecret() {
		return _secret;
	}

	/**
	 * @see #getSecret()
	 */
	public de.haumacher.imageServer.shared.model.TotpSetup setSecret(String value) {
		internalSetSecret(value);
		return this;
	}

	/** Internal setter for {@link #getSecret()} without chain call utility. */
	protected final void internalSetSecret(String value) {
		_secret = value;
	}

	/**
	 * The <code>otpauth://totp/&lt;issuer&gt;:&lt;account&gt;?secret=…&amp;issuer=…</code> address
	 * of the secret, with the defaults spelled out: SHA-1, six digits, thirty seconds.
	 */
	public final String getUri() {
		return _uri;
	}

	/**
	 * @see #getUri()
	 */
	public de.haumacher.imageServer.shared.model.TotpSetup setUri(String value) {
		internalSetUri(value);
		return this;
	}

	/** Internal setter for {@link #getUri()} without chain call utility. */
	protected final void internalSetUri(String value) {
		_uri = value;
	}

	/**
	 * The name the app files the entry under: the space's.
	 */
	public final String getIssuer() {
		return _issuer;
	}

	/**
	 * @see #getIssuer()
	 */
	public de.haumacher.imageServer.shared.model.TotpSetup setIssuer(String value) {
		internalSetIssuer(value);
		return this;
	}

	/** Internal setter for {@link #getIssuer()} without chain call utility. */
	protected final void internalSetIssuer(String value) {
		_issuer = value;
	}

	/**
	 * The name of the entry within the issuer: the contact's.
	 */
	public final String getAccount() {
		return _account;
	}

	/**
	 * @see #getAccount()
	 */
	public de.haumacher.imageServer.shared.model.TotpSetup setAccount(String value) {
		internalSetAccount(value);
		return this;
	}

	/** Internal setter for {@link #getAccount()} without chain call utility. */
	protected final void internalSetAccount(String value) {
		_account = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.TotpSetup readTotpSetup(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.TotpSetup result = new de.haumacher.imageServer.shared.model.TotpSetup();
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
		out.name(SECRET__PROP);
		out.value(getSecret());
		out.name(URI__PROP);
		out.value(getUri());
		out.name(ISSUER__PROP);
		out.value(getIssuer());
		out.name(ACCOUNT__PROP);
		out.value(getAccount());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case SECRET__PROP: setSecret(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case URI__PROP: setUri(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case ISSUER__PROP: setIssuer(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case ACCOUNT__PROP: setAccount(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
