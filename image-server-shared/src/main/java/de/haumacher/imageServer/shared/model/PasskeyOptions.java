package de.haumacher.imageServer.shared.model;

/**
 * A passkey ceremony was started (issue #204): the answer of
 * <code>&lt;data&gt;/?action=passkey-register-start</code> (a contact in their session registers a
 * passkey) and of <code>&lt;data&gt;/?action=passkey-start</code> (a visitor not recognised yet
 * signs in with one).
 */
public class PasskeyOptions extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.PasskeyOptions} instance.
	 */
	public static de.haumacher.imageServer.shared.model.PasskeyOptions create() {
		return new de.haumacher.imageServer.shared.model.PasskeyOptions();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.PasskeyOptions} type in JSON format. */
	public static final String PASSKEY_OPTIONS__TYPE = "PasskeyOptions";

	/** @see #getTicket() */
	private static final String TICKET__PROP = "ticket";

	/** @see #getOptions() */
	private static final String OPTIONS__PROP = "options";

	/** @see #getExpires() */
	private static final String EXPIRES__PROP = "expires";

	private String _ticket = "";

	private String _options = "";

	private String _expires = "";

	/**
	 * Creates a {@link PasskeyOptions} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.PasskeyOptions#create()
	 */
	protected PasskeyOptions() {
		super();
	}

	/**
	 * What the answer names: single use, for a few minutes, in this space and for this caller.
	 */
	public final String getTicket() {
		return _ticket;
	}

	/**
	 * @see #getTicket()
	 */
	public de.haumacher.imageServer.shared.model.PasskeyOptions setTicket(String value) {
		internalSetTicket(value);
		return this;
	}

	/** Internal setter for {@link #getTicket()} without chain call utility. */
	protected final void internalSetTicket(String value) {
		_ticket = value;
	}

	/**
	 * The options for <code>navigator.credentials.create</code> or <code>.get</code> in their JSON
	 * form (WebAuthn Level 3, binary values base64url), a string the page decodes.
	 */
	public final String getOptions() {
		return _options;
	}

	/**
	 * @see #getOptions()
	 */
	public de.haumacher.imageServer.shared.model.PasskeyOptions setOptions(String value) {
		internalSetOptions(value);
		return this;
	}

	/** Internal setter for {@link #getOptions()} without chain call utility. */
	protected final void internalSetOptions(String value) {
		_options = value;
	}

	/**
	 * Until when the answer is taken, an ISO-8601 instant.
	 */
	public final String getExpires() {
		return _expires;
	}

	/**
	 * @see #getExpires()
	 */
	public de.haumacher.imageServer.shared.model.PasskeyOptions setExpires(String value) {
		internalSetExpires(value);
		return this;
	}

	/** Internal setter for {@link #getExpires()} without chain call utility. */
	protected final void internalSetExpires(String value) {
		_expires = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.PasskeyOptions readPasskeyOptions(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.PasskeyOptions result = new de.haumacher.imageServer.shared.model.PasskeyOptions();
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
		out.name(TICKET__PROP);
		out.value(getTicket());
		out.name(OPTIONS__PROP);
		out.value(getOptions());
		out.name(EXPIRES__PROP);
		out.value(getExpires());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case TICKET__PROP: setTicket(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case OPTIONS__PROP: setOptions(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case EXPIRES__PROP: setExpires(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
