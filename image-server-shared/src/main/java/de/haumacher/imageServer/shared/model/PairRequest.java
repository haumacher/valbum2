package de.haumacher.imageServer.shared.model;

/**
 * Request to issue a device token, sent to <code>&lt;data&gt;/?action=pair</code>.
 */
public class PairRequest extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.PairRequest} instance.
	 */
	public static de.haumacher.imageServer.shared.model.PairRequest create() {
		return new de.haumacher.imageServer.shared.model.PairRequest();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.PairRequest} type in JSON format. */
	public static final String PAIR_REQUEST__TYPE = "PairRequest";

	/** @see #getSecret() */
	private static final String SECRET__PROP = "secret";

	/** @see #getDeviceName() */
	private static final String DEVICE_NAME__PROP = "deviceName";

	private String _secret = "";

	private String _deviceName = "";

	/**
	 * Creates a {@link PairRequest} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.PairRequest#create()
	 */
	protected PairRequest() {
		super();
	}

	/**
	 * The pairing secret the server was started with.
	 */
	public final String getSecret() {
		return _secret;
	}

	/**
	 * @see #getSecret()
	 */
	public de.haumacher.imageServer.shared.model.PairRequest setSecret(String value) {
		internalSetSecret(value);
		return this;
	}

	/** Internal setter for {@link #getSecret()} without chain call utility. */
	protected final void internalSetSecret(String value) {
		_secret = value;
	}

	/**
	 * The name the device announces itself with.
	 */
	public final String getDeviceName() {
		return _deviceName;
	}

	/**
	 * @see #getDeviceName()
	 */
	public de.haumacher.imageServer.shared.model.PairRequest setDeviceName(String value) {
		internalSetDeviceName(value);
		return this;
	}

	/** Internal setter for {@link #getDeviceName()} without chain call utility. */
	protected final void internalSetDeviceName(String value) {
		_deviceName = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.PairRequest readPairRequest(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.PairRequest result = new de.haumacher.imageServer.shared.model.PairRequest();
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
		out.name(DEVICE_NAME__PROP);
		out.value(getDeviceName());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case SECRET__PROP: setSecret(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case DEVICE_NAME__PROP: setDeviceName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
