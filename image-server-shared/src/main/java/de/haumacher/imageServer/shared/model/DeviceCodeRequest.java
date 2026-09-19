package de.haumacher.imageServer.shared.model;

/**
 * What a request to <code>&lt;data&gt;/?action=device-code</code> may carry, see issue #89.
 *
 * <p>
 * Nothing, and that is the ordinary case: a code for a further device of one's own needs no body,
 * because the token already says who is asking. An administrator may name somebody else instead —
 * the <em>recovery code</em> for a person who cleared their browser or reinstalled the app and
 * lost every device they had. It is the same code with the same ten minutes and the same single
 * use; only its target differs, and it still dies with the device that issued it.
 * </p>
 */
public class DeviceCodeRequest extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.DeviceCodeRequest} instance.
	 */
	public static de.haumacher.imageServer.shared.model.DeviceCodeRequest create() {
		return new de.haumacher.imageServer.shared.model.DeviceCodeRequest();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.DeviceCodeRequest} type in JSON format. */
	public static final String DEVICE_CODE_REQUEST__TYPE = "DeviceCodeRequest";

	/** @see #getUserName() */
	private static final String USER_NAME__PROP = "userName";

	private String _userName = "";

	/**
	 * Creates a {@link DeviceCodeRequest} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.DeviceCodeRequest#create()
	 */
	protected DeviceCodeRequest() {
		super();
	}

	/**
	 * The user the code signs in, empty for the caller themselves.
	 *
	 * <p>
	 * Only an administrator of the space may name somebody other than themselves; a user this
	 * space does not know is answered <code>404</code>.
	 * </p>
	 */
	public final String getUserName() {
		return _userName;
	}

	/**
	 * @see #getUserName()
	 */
	public de.haumacher.imageServer.shared.model.DeviceCodeRequest setUserName(String value) {
		internalSetUserName(value);
		return this;
	}

	/** Internal setter for {@link #getUserName()} without chain call utility. */
	protected final void internalSetUserName(String value) {
		_userName = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.DeviceCodeRequest readDeviceCodeRequest(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.DeviceCodeRequest result = new de.haumacher.imageServer.shared.model.DeviceCodeRequest();
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
		out.name(USER_NAME__PROP);
		out.value(getUserName());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case USER_NAME__PROP: setUserName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
