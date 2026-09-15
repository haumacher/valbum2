package de.haumacher.imageServer.shared.model;

/**
 * The answer to <code>&lt;data&gt;/?action=device-code</code>: a code to type on a further device
 * of one's own, see issue #65.
 *
 * <p>
 * A device credential, never an invitation: whoever types this code is signed in as the user who
 * asked for it, so it is deliberately nothing that can be forwarded — no link, no URL and no
 * bearer token, but eight characters shown on the screen of a device that is already signed in.
 * It lives ten minutes and it works once, and the device it pairs appears in
 * {@link DeviceList} at once, where it can be signed out again.
 * </p>
 *
 * <p>
 * The one and only time the {@link #getCode()} is answered; the server keeps its hash and can never
 * show it again. A code that was not typed in time is simply asked for anew.
 * </p>
 */
public class DeviceCodeCreated extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.DeviceCodeCreated} instance.
	 */
	public static de.haumacher.imageServer.shared.model.DeviceCodeCreated create() {
		return new de.haumacher.imageServer.shared.model.DeviceCodeCreated();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.DeviceCodeCreated} type in JSON format. */
	public static final String DEVICE_CODE_CREATED__TYPE = "DeviceCodeCreated";

	/** @see #getCode() */
	private static final String CODE__PROP = "code";

	/** @see #getExpires() */
	private static final String EXPIRES__PROP = "expires";

	private String _code = "";

	private String _expires = "";

	/**
	 * Creates a {@link DeviceCodeCreated} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.DeviceCodeCreated#create()
	 */
	protected DeviceCodeCreated() {
		super();
	}

	/**
	 * The code to type on the other device, grouped as <code>XXXX-XXXX</code>; the dash is decoration.
	 */
	public final String getCode() {
		return _code;
	}

	/**
	 * @see #getCode()
	 */
	public de.haumacher.imageServer.shared.model.DeviceCodeCreated setCode(String value) {
		internalSetCode(value);
		return this;
	}

	/** Internal setter for {@link #getCode()} without chain call utility. */
	protected final void internalSetCode(String value) {
		_code = value;
	}

	/**
	 * When the code stops working, an ISO-8601 instant; ten minutes after it was issued.
	 */
	public final String getExpires() {
		return _expires;
	}

	/**
	 * @see #getExpires()
	 */
	public de.haumacher.imageServer.shared.model.DeviceCodeCreated setExpires(String value) {
		internalSetExpires(value);
		return this;
	}

	/** Internal setter for {@link #getExpires()} without chain call utility. */
	protected final void internalSetExpires(String value) {
		_expires = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.DeviceCodeCreated readDeviceCodeCreated(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.DeviceCodeCreated result = new de.haumacher.imageServer.shared.model.DeviceCodeCreated();
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
		out.name(EXPIRES__PROP);
		out.value(getExpires());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case CODE__PROP: setCode(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case EXPIRES__PROP: setExpires(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
