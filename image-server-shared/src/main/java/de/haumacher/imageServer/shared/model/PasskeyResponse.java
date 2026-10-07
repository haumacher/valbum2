package de.haumacher.imageServer.shared.model;

/**
 * The browser's answer to a passkey ceremony (issue #204):
 * <code>&lt;data&gt;/?action=passkey-register</code> stores the passkey and answers the contact's
 * {@link ContactSignIns}; <code>&lt;data&gt;/?action=passkey-verify</code> signs a visitor in and
 * answers a {@link ContactCredential}.
 */
public class PasskeyResponse extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.PasskeyResponse} instance.
	 */
	public static de.haumacher.imageServer.shared.model.PasskeyResponse create() {
		return new de.haumacher.imageServer.shared.model.PasskeyResponse();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.PasskeyResponse} type in JSON format. */
	public static final String PASSKEY_RESPONSE__TYPE = "PasskeyResponse";

	/** @see #getTicket() */
	private static final String TICKET__PROP = "ticket";

	/** @see #getResponse() */
	private static final String RESPONSE__PROP = "response";

	/** @see #isRemember() */
	private static final String REMEMBER__PROP = "remember";

	/** @see #getDisplayName() */
	private static final String DISPLAY_NAME__PROP = "displayName";

	/** @see #getDeviceName() */
	private static final String DEVICE_NAME__PROP = "deviceName";

	private String _ticket = "";

	private String _response = "";

	private boolean _remember = false;

	private String _displayName = "";

	private String _deviceName = "";

	/**
	 * Creates a {@link PasskeyResponse} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.PasskeyResponse#create()
	 */
	protected PasskeyResponse() {
		super();
	}

	/**
	 * The {@link PasskeyOptions#getTicket()} of the ceremony.
	 */
	public final String getTicket() {
		return _ticket;
	}

	/**
	 * @see #getTicket()
	 */
	public de.haumacher.imageServer.shared.model.PasskeyResponse setTicket(String value) {
		internalSetTicket(value);
		return this;
	}

	/** Internal setter for {@link #getTicket()} without chain call utility. */
	protected final void internalSetTicket(String value) {
		_ticket = value;
	}

	/**
	 * What <code>navigator.credentials</code> answered, in the JSON form of a
	 * <code>PublicKeyCredential</code> (binary values base64url).
	 */
	public final String getResponse() {
		return _response;
	}

	/**
	 * @see #getResponse()
	 */
	public de.haumacher.imageServer.shared.model.PasskeyResponse setResponse(String value) {
		internalSetResponse(value);
		return this;
	}

	/** Internal setter for {@link #getResponse()} without chain call utility. */
	protected final void internalSetResponse(String value) {
		_response = value;
	}

	/**
	 * Whether to remember this browser: 90 days renewed on use, else 24 hours (sign-in only).
	 */
	public final boolean isRemember() {
		return _remember;
	}

	/**
	 * @see #isRemember()
	 */
	public de.haumacher.imageServer.shared.model.PasskeyResponse setRemember(boolean value) {
		internalSetRemember(value);
		return this;
	}

	/** Internal setter for {@link #isRemember()} without chain call utility. */
	protected final void internalSetRemember(boolean value) {
		_remember = value;
	}

	/**
	 * The name the contact wants to be greeted by; empty keeps what they had (sign-in only).
	 */
	public final String getDisplayName() {
		return _displayName;
	}

	/**
	 * @see #getDisplayName()
	 */
	public de.haumacher.imageServer.shared.model.PasskeyResponse setDisplayName(String value) {
		internalSetDisplayName(value);
		return this;
	}

	/** Internal setter for {@link #getDisplayName()} without chain call utility. */
	protected final void internalSetDisplayName(String value) {
		_displayName = value;
	}

	/**
	 * The name of this browser as a member's device, where the proof names a member and signs them
	 * in (issue #233, see {@link ContactCredential#getMember()}); empty for "Unnamed device".
	 */
	public final String getDeviceName() {
		return _deviceName;
	}

	/**
	 * @see #getDeviceName()
	 */
	public de.haumacher.imageServer.shared.model.PasskeyResponse setDeviceName(String value) {
		internalSetDeviceName(value);
		return this;
	}

	/** Internal setter for {@link #getDeviceName()} without chain call utility. */
	protected final void internalSetDeviceName(String value) {
		_deviceName = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.PasskeyResponse readPasskeyResponse(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.PasskeyResponse result = new de.haumacher.imageServer.shared.model.PasskeyResponse();
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
		out.name(RESPONSE__PROP);
		out.value(getResponse());
		out.name(REMEMBER__PROP);
		out.value(isRemember());
		out.name(DISPLAY_NAME__PROP);
		out.value(getDisplayName());
		out.name(DEVICE_NAME__PROP);
		out.value(getDeviceName());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case TICKET__PROP: setTicket(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case RESPONSE__PROP: setResponse(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case REMEMBER__PROP: setRemember(in.nextBoolean()); break;
			case DISPLAY_NAME__PROP: setDisplayName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case DEVICE_NAME__PROP: setDeviceName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
