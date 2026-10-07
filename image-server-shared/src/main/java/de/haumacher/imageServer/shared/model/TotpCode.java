package de.haumacher.imageServer.shared.model;

/**
 * A code from an authenticator app (issue #208): <code>&lt;data&gt;/?action=totp-confirm</code>
 * confirms an app being set up, <code>&lt;data&gt;/?action=totp-verify</code> signs a contact in on
 * a personal link and answers a {@link ContactCredential}.
 */
public class TotpCode extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.TotpCode} instance.
	 */
	public static de.haumacher.imageServer.shared.model.TotpCode create() {
		return new de.haumacher.imageServer.shared.model.TotpCode();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.TotpCode} type in JSON format. */
	public static final String TOTP_CODE__TYPE = "TotpCode";

	/** @see #getCode() */
	private static final String CODE__PROP = "code";

	/** @see #getAddress() */
	private static final String ADDRESS__PROP = "address";

	/** @see #isRemember() */
	private static final String REMEMBER__PROP = "remember";

	/** @see #getDisplayName() */
	private static final String DISPLAY_NAME__PROP = "displayName";

	/** @see #getDeviceName() */
	private static final String DEVICE_NAME__PROP = "deviceName";

	private String _code = "";

	private String _address = "";

	private boolean _remember = false;

	private String _displayName = "";

	private String _deviceName = "";

	/**
	 * Creates a {@link TotpCode} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.TotpCode#create()
	 */
	protected TotpCode() {
		super();
	}

	/**
	 * The six digits the app shows.
	 */
	public final String getCode() {
		return _code;
	}

	/**
	 * @see #getCode()
	 */
	public de.haumacher.imageServer.shared.model.TotpCode setCode(String value) {
		internalSetCode(value);
		return this;
	}

	/** Internal setter for {@link #getCode()} without chain call utility. */
	protected final void internalSetCode(String value) {
		_code = value;
	}

	/**
	 * On an open personal link and on the group link of issue #211, which name nobody: the e-mail
	 * address saved with the contact the code is to sign in. Ignored on a recipient's own link.
	 */
	public final String getAddress() {
		return _address;
	}

	/**
	 * @see #getAddress()
	 */
	public de.haumacher.imageServer.shared.model.TotpCode setAddress(String value) {
		internalSetAddress(value);
		return this;
	}

	/** Internal setter for {@link #getAddress()} without chain call utility. */
	protected final void internalSetAddress(String value) {
		_address = value;
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
	public de.haumacher.imageServer.shared.model.TotpCode setRemember(boolean value) {
		internalSetRemember(value);
		return this;
	}

	/** Internal setter for {@link #isRemember()} without chain call utility. */
	protected final void internalSetRemember(boolean value) {
		_remember = value;
	}

	/**
	 * The name the contact wants to be greeted by; empty keeps what they had.
	 */
	public final String getDisplayName() {
		return _displayName;
	}

	/**
	 * @see #getDisplayName()
	 */
	public de.haumacher.imageServer.shared.model.TotpCode setDisplayName(String value) {
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
	public de.haumacher.imageServer.shared.model.TotpCode setDeviceName(String value) {
		internalSetDeviceName(value);
		return this;
	}

	/** Internal setter for {@link #getDeviceName()} without chain call utility. */
	protected final void internalSetDeviceName(String value) {
		_deviceName = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.TotpCode readTotpCode(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.TotpCode result = new de.haumacher.imageServer.shared.model.TotpCode();
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
		out.name(CODE__PROP);
		out.value(getCode());
		out.name(ADDRESS__PROP);
		out.value(getAddress());
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
			case CODE__PROP: setCode(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case ADDRESS__PROP: setAddress(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case REMEMBER__PROP: setRemember(in.nextBoolean()); break;
			case DISPLAY_NAME__PROP: setDisplayName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case DEVICE_NAME__PROP: setDeviceName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
