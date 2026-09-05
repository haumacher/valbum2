package de.haumacher.imageServer.shared.model;

/**
 * Answer to a successful {@link PairRequest}.
 */
public class PairResponse extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.PairResponse} instance.
	 */
	public static de.haumacher.imageServer.shared.model.PairResponse create() {
		return new de.haumacher.imageServer.shared.model.PairResponse();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.PairResponse} type in JSON format. */
	public static final String PAIR_RESPONSE__TYPE = "PairResponse";

	/** @see #getToken() */
	private static final String TOKEN__PROP = "token";

	/** @see #getDeviceName() */
	private static final String DEVICE_NAME__PROP = "deviceName";

	private String _token = "";

	private String _deviceName = "";

	/**
	 * Creates a {@link PairResponse} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.PairResponse#create()
	 */
	protected PairResponse() {
		super();
	}

	/**
	 * The token to send as <code>Authorization: Bearer &lt;token&gt;</code> from now on.
	 */
	public final String getToken() {
		return _token;
	}

	/**
	 * @see #getToken()
	 */
	public de.haumacher.imageServer.shared.model.PairResponse setToken(String value) {
		internalSetToken(value);
		return this;
	}

	/** Internal setter for {@link #getToken()} without chain call utility. */
	protected final void internalSetToken(String value) {
		_token = value;
	}

	/**
	 * The name the token was stored under.
	 */
	public final String getDeviceName() {
		return _deviceName;
	}

	/**
	 * @see #getDeviceName()
	 */
	public de.haumacher.imageServer.shared.model.PairResponse setDeviceName(String value) {
		internalSetDeviceName(value);
		return this;
	}

	/** Internal setter for {@link #getDeviceName()} without chain call utility. */
	protected final void internalSetDeviceName(String value) {
		_deviceName = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.PairResponse readPairResponse(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.PairResponse result = new de.haumacher.imageServer.shared.model.PairResponse();
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
		out.name(TOKEN__PROP);
		out.value(getToken());
		out.name(DEVICE_NAME__PROP);
		out.value(getDeviceName());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case TOKEN__PROP: setToken(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case DEVICE_NAME__PROP: setDeviceName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
