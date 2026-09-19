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
	 * The pairing secret the server was started with; retired by issue #89.
	 *
	 * <p>
	 * There is no pairing secret any more: the server issues an ordinary single-use
	 * {@link #getDeviceCode()} for the administrator of a space that has no signed-in device yet and
	 * prints it at start-up. The field is read for one release and a request carrying it — and no
	 * {@link #getDeviceCode()} — is answered <code>410 Gone</code> with an {@link ErrorInfo} naming the
	 * code, so that an app that was not updated says something useful instead of failing silently.
	 * </p>
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
	 * The name of the user signing in, empty where the code already says who.
	 *
	 * <p>
	 * A {@link #getDeviceCode()} names its own user, so the name is not a choice but a check: a name
	 * that is not the code's user is refused. The one exception is a code for a user who has
	 * <em>no name yet</em> — the seat code the server prints for the administrator of a fresh
	 * space (issue #89), and the code of an invitation. There the name is what the user will be
	 * known by in the space, it must be free, and a pairing without it is refused
	 * <code>400</code> so that the app can ask for one.
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
	 * The token of an {@link Invitation}; an alias of {@link #getDeviceCode()}, retired by issue #89.
	 *
	 * <p>
	 * An invitation is a pending user carrying a code: the user is created when the invitation is
	 * issued and the link carries the single-use code that adds their first device, so accepting
	 * an invitation is the ordinary pairing and the token belongs in {@link #getDeviceCode()}. This
	 * field is read for one release &mdash; a request carrying it and no {@link #getDeviceCode()} is
	 * redeemed exactly as if it had carried one &mdash; so that an app from before the change
	 * keeps joining.
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
	 * Since issue #89 this is the one way a device is signed in: the seat code the server prints
	 * for the administrator of a space that has no device yet, a code from a device of one's own,
	 * and the recovery code an administrator makes for somebody who lost theirs are all the same
	 * single-use secret with the same lifetime, told apart only by who issued them.
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
