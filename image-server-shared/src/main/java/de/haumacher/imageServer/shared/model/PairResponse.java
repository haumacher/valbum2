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

	/** @see #getUserName() */
	private static final String USER_NAME__PROP = "userName";

	/** @see #getRole() */
	private static final String ROLE__PROP = "role";

	/** @see #getSpace() */
	private static final String SPACE__PROP = "space";

	private String _token = "";

	private String _deviceName = "";

	private String _userName = "";

	private String _role = "";

	private String _space = "";

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

	/**
	 * The name of the user this device now belongs to, empty while the owner is unnamed.
	 */
	public final String getUserName() {
		return _userName;
	}

	/**
	 * @see #getUserName()
	 */
	public de.haumacher.imageServer.shared.model.PairResponse setUserName(String value) {
		internalSetUserName(value);
		return this;
	}

	/** Internal setter for {@link #getUserName()} without chain call utility. */
	protected final void internalSetUserName(String value) {
		_userName = value;
	}

	/**
	 * The role of the signed-in user: <code>admin</code>, <code>member</code> or <code>guest</code>.
	 */
	public final String getRole() {
		return _role;
	}

	/**
	 * @see #getRole()
	 */
	public de.haumacher.imageServer.shared.model.PairResponse setRole(String value) {
		internalSetRole(value);
		return this;
	}

	/** Internal setter for {@link #getRole()} without chain call utility. */
	protected final void internalSetRole(String value) {
		_role = value;
	}

	/**
	 * The folder below the server's base folder the user's library is rooted at, empty for the base folder itself.
	 */
	public final String getSpace() {
		return _space;
	}

	/**
	 * @see #getSpace()
	 */
	public de.haumacher.imageServer.shared.model.PairResponse setSpace(String value) {
		internalSetSpace(value);
		return this;
	}

	/** Internal setter for {@link #getSpace()} without chain call utility. */
	protected final void internalSetSpace(String value) {
		_space = value;
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
		out.name(USER_NAME__PROP);
		out.value(getUserName());
		out.name(ROLE__PROP);
		out.value(getRole());
		out.name(SPACE__PROP);
		out.value(getSpace());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case TOKEN__PROP: setToken(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case DEVICE_NAME__PROP: setDeviceName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case USER_NAME__PROP: setUserName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case ROLE__PROP: setRole(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case SPACE__PROP: setSpace(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
