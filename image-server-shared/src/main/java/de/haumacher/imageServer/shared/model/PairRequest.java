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

	/** @see #getUserName() */
	private static final String USER_NAME__PROP = "userName";

	/** @see #getInvitation() */
	private static final String INVITATION__PROP = "invitation";

	/** @see #getDeviceCode() */
	private static final String DEVICE_CODE__PROP = "deviceCode";

	private String _secret = "";

	private String _deviceName = "";

	private String _userName = "";

	private String _invitation = "";

	private String _deviceCode = "";

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

	/**
	 * The name of the user signing in, empty for "the library owner".
	 *
	 * <p>
	 * A request carrying the pairing secret signs in the library owner (the <code>admin</code>): an
	 * empty name means the owner, a non-empty one names the owner when it has no name yet and must
	 * match the stored name afterwards. An app from before issue #45 sends no name at all.
	 * </p>
	 *
	 * <p>
	 * With an {@link #getInvitation()} it is the name of the user to create, which must be free and must
	 * pass the server's name rule; it is not optional there.
	 * </p>
	 */
	public final String getUserName() {
		return _userName;
	}

	/**
	 * @see #getUserName()
	 */
	public de.haumacher.imageServer.shared.model.PairRequest setUserName(String value) {
		internalSetUserName(value);
		return this;
	}

	/** Internal setter for {@link #getUserName()} without chain call utility. */
	protected final void internalSetUserName(String value) {
		_userName = value;
	}

	/**
	 * The token of an {@link Invitation}, the alternative to the {@link #getSecret()} (issue #52).
	 *
	 * <p>
	 * Accepting an invitation is pairing: a live, unused invitation together with a free
	 * {@link #getUserName()} creates the user with the invitation's role, issues this device's token and
	 * marks the invitation used. Empty in every other request; a request carrying both is read as
	 * an invitation.
	 * </p>
	 */
	public final String getInvitation() {
		return _invitation;
	}

	/**
	 * @see #getInvitation()
	 */
	public de.haumacher.imageServer.shared.model.PairRequest setInvitation(String value) {
		internalSetInvitation(value);
		return this;
	}

	/** Internal setter for {@link #getInvitation()} without chain call utility. */
	protected final void internalSetInvitation(String value) {
		_invitation = value;
	}

	/**
	 * The code shown on a device that is already signed in, see {@link DeviceCodeCreated} (issue #65).
	 *
	 * <p>
	 * Adding a further device of one's own: the code is typed on the new device and pairs it as the
	 * <em>same user</em> as the device that showed it, which is exactly what an
	 * {@link #getInvitation()} must never do. It is no link and no bearer — it travels in this one
	 * request and nowhere else — it lives ten minutes and it works once. Spelled with or without
	 * the dash the other device shows, in any case.
	 * </p>
	 *
	 * <p>
	 * Empty in every other request. A request carrying an {@link #getInvitation()} as well is read as an
	 * invitation; one carrying a {@link #getSecret()} as well is read as a device code, and a
	 * {@link #getUserName()} naming somebody other than the code's user is refused.
	 * </p>
	 */
	public final String getDeviceCode() {
		return _deviceCode;
	}

	/**
	 * @see #getDeviceCode()
	 */
	public de.haumacher.imageServer.shared.model.PairRequest setDeviceCode(String value) {
		internalSetDeviceCode(value);
		return this;
	}

	/** Internal setter for {@link #getDeviceCode()} without chain call utility. */
	protected final void internalSetDeviceCode(String value) {
		_deviceCode = value;
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
		out.name(USER_NAME__PROP);
		out.value(getUserName());
		out.name(INVITATION__PROP);
		out.value(getInvitation());
		out.name(DEVICE_CODE__PROP);
		out.value(getDeviceCode());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case SECRET__PROP: setSecret(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case DEVICE_NAME__PROP: setDeviceName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case USER_NAME__PROP: setUserName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case INVITATION__PROP: setInvitation(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case DEVICE_CODE__PROP: setDeviceCode(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
