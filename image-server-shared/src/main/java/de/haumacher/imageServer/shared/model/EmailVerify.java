package de.haumacher.imageServer.shared.model;

/**
 * Proving an e-mail address with the mailed code, <code>&lt;data&gt;/?action=verify-email</code> (issue #199).
 *
 * <p>
 * Names the address exactly as {@link EmailProof} did and carries the code. Success marks the
 * address proven and answers a {@link ContactCredential}: for a visitor not yet recognised a fresh
 * credential (on an open personal link the contact holding the address, or a new one); for a
 * contact who is recognised already the address is added to them and the credential they hold
 * stays, so the answer's {@link ContactCredential#getCredential()} is empty.
 * </p>
 */
public class EmailVerify extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.EmailVerify} instance.
	 */
	public static de.haumacher.imageServer.shared.model.EmailVerify create() {
		return new de.haumacher.imageServer.shared.model.EmailVerify();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.EmailVerify} type in JSON format. */
	public static final String EMAIL_VERIFY__TYPE = "EmailVerify";

	/** @see #getAddress() */
	private static final String ADDRESS__PROP = "address";

	/** @see #getChoice() */
	private static final String CHOICE__PROP = "choice";

	/** @see #getCode() */
	private static final String CODE__PROP = "code";

	/** @see #isRemember() */
	private static final String REMEMBER__PROP = "remember";

	/** @see #getDisplayName() */
	private static final String DISPLAY_NAME__PROP = "displayName";

	/** @see #getDeviceName() */
	private static final String DEVICE_NAME__PROP = "deviceName";

	private String _address = "";

	private int _choice = 0;

	private String _code = "";

	private boolean _remember = false;

	private String _displayName = "";

	private String _deviceName = "";

	/**
	 * Creates a {@link EmailVerify} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.EmailVerify#create()
	 */
	protected EmailVerify() {
		super();
	}

	/**
	 * The address, as {@link EmailProof#getAddress()} named it.
	 */
	public final String getAddress() {
		return _address;
	}

	/**
	 * @see #getAddress()
	 */
	public de.haumacher.imageServer.shared.model.EmailVerify setAddress(String value) {
		internalSetAddress(value);
		return this;
	}

	/** Internal setter for {@link #getAddress()} without chain call utility. */
	protected final void internalSetAddress(String value) {
		_address = value;
	}

	/**
	 * The position of the address, as {@link EmailProof#getChoice()} named it.
	 */
	public final int getChoice() {
		return _choice;
	}

	/**
	 * @see #getChoice()
	 */
	public de.haumacher.imageServer.shared.model.EmailVerify setChoice(int value) {
		internalSetChoice(value);
		return this;
	}

	/** Internal setter for {@link #getChoice()} without chain call utility. */
	protected final void internalSetChoice(int value) {
		_choice = value;
	}

	/**
	 * The six digits of the mail.
	 */
	public final String getCode() {
		return _code;
	}

	/**
	 * @see #getCode()
	 */
	public de.haumacher.imageServer.shared.model.EmailVerify setCode(String value) {
		internalSetCode(value);
		return this;
	}

	/** Internal setter for {@link #getCode()} without chain call utility. */
	protected final void internalSetCode(String value) {
		_code = value;
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
	public de.haumacher.imageServer.shared.model.EmailVerify setRemember(boolean value) {
		internalSetRemember(value);
		return this;
	}

	/** Internal setter for {@link #isRemember()} without chain call utility. */
	protected final void internalSetRemember(boolean value) {
		_remember = value;
	}

	/**
	 * The name the contact wants to be greeted by; empty keeps what they had. A visitor of an open
	 * link who is nobody yet is entered under it (else under the address); ignored where the caller
	 * is recognised already.
	 */
	public final String getDisplayName() {
		return _displayName;
	}

	/**
	 * @see #getDisplayName()
	 */
	public de.haumacher.imageServer.shared.model.EmailVerify setDisplayName(String value) {
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
	public de.haumacher.imageServer.shared.model.EmailVerify setDeviceName(String value) {
		internalSetDeviceName(value);
		return this;
	}

	/** Internal setter for {@link #getDeviceName()} without chain call utility. */
	protected final void internalSetDeviceName(String value) {
		_deviceName = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.EmailVerify readEmailVerify(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.EmailVerify result = new de.haumacher.imageServer.shared.model.EmailVerify();
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
		out.name(ADDRESS__PROP);
		out.value(getAddress());
		out.name(CHOICE__PROP);
		out.value(getChoice());
		out.name(CODE__PROP);
		out.value(getCode());
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
			case ADDRESS__PROP: setAddress(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case CHOICE__PROP: setChoice(in.nextInt()); break;
			case CODE__PROP: setCode(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case REMEMBER__PROP: setRemember(in.nextBoolean()); break;
			case DISPLAY_NAME__PROP: setDisplayName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case DEVICE_NAME__PROP: setDeviceName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
