package de.haumacher.imageServer.shared.model;

/**
 * The authentication state of the caller, answered by <code>&lt;data&gt;/?type=auth</code>.
 */
public class AuthInfo extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.AuthInfo} instance.
	 */
	public static de.haumacher.imageServer.shared.model.AuthInfo create() {
		return new de.haumacher.imageServer.shared.model.AuthInfo();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.AuthInfo} type in JSON format. */
	public static final String AUTH_INFO__TYPE = "AuthInfo";

	/** @see #getMode() */
	private static final String MODE__PROP = "mode";

	/** @see #getDeviceName() */
	private static final String DEVICE_NAME__PROP = "deviceName";

	/** @see #isWriteAllowed() */
	private static final String WRITE_ALLOWED__PROP = "writeAllowed";

	/** @see #getUserName() */
	private static final String USER_NAME__PROP = "userName";

	/** @see #getRole() */
	private static final String ROLE__PROP = "role";

	/** @see #getSpace() */
	private static final String SPACE__PROP = "space";

	private String _mode = "";

	private String _deviceName = "";

	private boolean _writeAllowed = false;

	private String _userName = "";

	private String _role = "";

	private String _space = "";

	/**
	 * Creates a {@link AuthInfo} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.AuthInfo#create()
	 */
	protected AuthInfo() {
		super();
	}

	/**
	 * The authentication mode of the server: <code>off</code>, <code>writes</code>, or <code>all</code>.
	 */
	public final String getMode() {
		return _mode;
	}

	/**
	 * @see #getMode()
	 */
	public de.haumacher.imageServer.shared.model.AuthInfo setMode(String value) {
		internalSetMode(value);
		return this;
	}

	/** Internal setter for {@link #getMode()} without chain call utility. */
	protected final void internalSetMode(String value) {
		_mode = value;
	}

	/**
	 * The name of the device the caller is paired as, empty if the caller is anonymous.
	 */
	public final String getDeviceName() {
		return _deviceName;
	}

	/**
	 * @see #getDeviceName()
	 */
	public de.haumacher.imageServer.shared.model.AuthInfo setDeviceName(String value) {
		internalSetDeviceName(value);
		return this;
	}

	/** Internal setter for {@link #getDeviceName()} without chain call utility. */
	protected final void internalSetDeviceName(String value) {
		_deviceName = value;
	}

	/**
	 * Whether the caller may perform write requests.
	 */
	public final boolean isWriteAllowed() {
		return _writeAllowed;
	}

	/**
	 * @see #isWriteAllowed()
	 */
	public de.haumacher.imageServer.shared.model.AuthInfo setWriteAllowed(boolean value) {
		internalSetWriteAllowed(value);
		return this;
	}

	/** Internal setter for {@link #isWriteAllowed()} without chain call utility. */
	protected final void internalSetWriteAllowed(boolean value) {
		_writeAllowed = value;
	}

	/**
	 * The name of the signed-in user, empty for an anonymous caller or an owner without a name yet.
	 */
	public final String getUserName() {
		return _userName;
	}

	/**
	 * @see #getUserName()
	 */
	public de.haumacher.imageServer.shared.model.AuthInfo setUserName(String value) {
		internalSetUserName(value);
		return this;
	}

	/** Internal setter for {@link #getUserName()} without chain call utility. */
	protected final void internalSetUserName(String value) {
		_userName = value;
	}

	/**
	 * The caller's role: <code>admin</code>, <code>member</code> or <code>guest</code>; empty for an anonymous caller.
	 */
	public final String getRole() {
		return _role;
	}

	/**
	 * @see #getRole()
	 */
	public de.haumacher.imageServer.shared.model.AuthInfo setRole(String value) {
		internalSetRole(value);
		return this;
	}

	/** Internal setter for {@link #getRole()} without chain call utility. */
	protected final void internalSetRole(String value) {
		_role = value;
	}

	/**
	 * The folder below the server's base folder the caller's requests are resolved against, empty for the base folder itself.
	 */
	public final String getSpace() {
		return _space;
	}

	/**
	 * @see #getSpace()
	 */
	public de.haumacher.imageServer.shared.model.AuthInfo setSpace(String value) {
		internalSetSpace(value);
		return this;
	}

	/** Internal setter for {@link #getSpace()} without chain call utility. */
	protected final void internalSetSpace(String value) {
		_space = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.AuthInfo readAuthInfo(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.AuthInfo result = new de.haumacher.imageServer.shared.model.AuthInfo();
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
		out.name(MODE__PROP);
		out.value(getMode());
		out.name(DEVICE_NAME__PROP);
		out.value(getDeviceName());
		out.name(WRITE_ALLOWED__PROP);
		out.value(isWriteAllowed());
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
			case MODE__PROP: setMode(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case DEVICE_NAME__PROP: setDeviceName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case WRITE_ALLOWED__PROP: setWriteAllowed(in.nextBoolean()); break;
			case USER_NAME__PROP: setUserName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case ROLE__PROP: setRole(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case SPACE__PROP: setSpace(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
