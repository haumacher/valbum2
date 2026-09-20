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

	/** @see #getClearance() */
	private static final String CLEARANCE__PROP = "clearance";

	/** @see #isMayShare() */
	private static final String MAY_SHARE__PROP = "mayShare";

	/** @see #getMapUrl() */
	private static final String MAP_URL__PROP = "mapUrl";

	/** @see #getShare() */
	private static final String SHARE__PROP = "share";

	/** @see #getInvitation() */
	private static final String INVITATION__PROP = "invitation";

	private String _mode = "";

	private String _deviceName = "";

	private boolean _writeAllowed = false;

	private String _userName = "";

	private String _role = "";

	private String _space = "";

	private String _clearance = "";

	private boolean _mayShare = false;

	private String _mapUrl = "";

	private de.haumacher.imageServer.shared.model.ShareInfo _share = null;

	private de.haumacher.imageServer.shared.model.InvitationInfo _invitation = null;

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
	 * The space the caller is in: the folder below the server's base folder their requests are
	 * resolved against, empty for the base folder itself.
	 *
	 * <p>
	 * On a multi-space server (issue #82) this is the space of the address the request was sent to
	 * — the first segment of <code>&lt;context&gt;/&lt;space&gt;/data/...</code> — and every user,
	 * device and album the answer speaks of belongs to it. On a single-space server it is empty,
	 * as it was for a library that was never migrated.
	 * </p>
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

	/**
	 * Which privacy levels the caller may see: <code>public</code>, <code>nonPrivate</code> or
	 * <code>all</code>; empty for an anonymous caller (issue #82).
	 *
	 * <p>
	 * One of the two axes of the permission model of Phase 6, stored with the user and enforced by
	 * issue #83. It is compared against an image's privacy level, which does not change.
	 * </p>
	 */
	public final String getClearance() {
		return _clearance;
	}

	/**
	 * @see #getClearance()
	 */
	public de.haumacher.imageServer.shared.model.AuthInfo setClearance(String value) {
		internalSetClearance(value);
		return this;
	}

	/** Internal setter for {@link #getClearance()} without chain call utility. */
	protected final void internalSetClearance(String value) {
		_clearance = value;
	}

	/**
	 * Whether the caller may create share links, see issue #82; false for an anonymous caller.
	 */
	public final boolean isMayShare() {
		return _mayShare;
	}

	/**
	 * @see #isMayShare()
	 */
	public de.haumacher.imageServer.shared.model.AuthInfo setMayShare(boolean value) {
		internalSetMayShare(value);
		return this;
	}

	/** Internal setter for {@link #isMayShare()} without chain call utility. */
	protected final void internalSetMayShare(boolean value) {
		_mayShare = value;
	}

	/**
	 * How a position is shown on a map in this space, see issue #112.
	 *
	 * <p>
	 * A URL template carrying <code>{lat}</code> and <code>{lon}</code>, which the app substitutes
	 * with the decimal degrees of an {@link ImagePart#getLocation() image's position} — a dot as
	 * the decimal separator, whatever the locale of the device. The default is
	 * <code>https://www.google.com/maps?q={lat},{lon}</code>; OpenStreetMap, Apple Maps or a map
	 * of one's own are simply other templates, which is why there is no provider to choose from.
	 * </p>
	 *
	 * <p>
	 * A property of the <em>space</em>, read from its <code>.valbum/space.json</code> beside the
	 * name and the anonymous access, and answered here because <code>?type=auth</code> is the one
	 * request the app makes anyway. An older server answers nothing, and the app then applies the
	 * same default itself.
	 * </p>
	 */
	public final String getMapUrl() {
		return _mapUrl;
	}

	/**
	 * @see #getMapUrl()
	 */
	public de.haumacher.imageServer.shared.model.AuthInfo setMapUrl(String value) {
		internalSetMapUrl(value);
		return this;
	}

	/** Internal setter for {@link #getMapUrl()} without chain call utility. */
	protected final void internalSetMapUrl(String value) {
		_mapUrl = value;
	}

	/**
	 * The share link this caller opened, <code>null</code> for everybody else (issue #51).
	 *
	 * <p>
	 * Its presence is what tells the app that it is a session inside one shared subtree: the
	 * link's target is the root of the tree, there is no edit mode and no settings prompt, and
	 * {@link #isWriteAllowed()} says whether the link allows contributions.
	 * </p>
	 */
	public final de.haumacher.imageServer.shared.model.ShareInfo getShare() {
		return _share;
	}

	/**
	 * @see #getShare()
	 */
	public de.haumacher.imageServer.shared.model.AuthInfo setShare(de.haumacher.imageServer.shared.model.ShareInfo value) {
		internalSetShare(value);
		return this;
	}

	/** Internal setter for {@link #getShare()} without chain call utility. */
	protected final void internalSetShare(de.haumacher.imageServer.shared.model.ShareInfo value) {
		_share = value;
	}

	/**
	 * Checks, whether {@link #getShare()} has a value.
	 */
	public final boolean hasShare() {
		return _share != null;
	}

	/**
	 * The invitation this caller presented, <code>null</code> for everybody else (issue #52).
	 *
	 * <p>
	 * An invitation token is no login: the caller is anonymous on every other endpoint, and this
	 * is the one place the server says what the invitation offers, so that the app can name the
	 * inviter and the role before it asks for a user name. An invitation that expired, was used or
	 * was withdrawn is answered <code>410 Gone</code> here instead.
	 * </p>
	 */
	public final de.haumacher.imageServer.shared.model.InvitationInfo getInvitation() {
		return _invitation;
	}

	/**
	 * @see #getInvitation()
	 */
	public de.haumacher.imageServer.shared.model.AuthInfo setInvitation(de.haumacher.imageServer.shared.model.InvitationInfo value) {
		internalSetInvitation(value);
		return this;
	}

	/** Internal setter for {@link #getInvitation()} without chain call utility. */
	protected final void internalSetInvitation(de.haumacher.imageServer.shared.model.InvitationInfo value) {
		_invitation = value;
	}

	/**
	 * Checks, whether {@link #getInvitation()} has a value.
	 */
	public final boolean hasInvitation() {
		return _invitation != null;
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
		out.name(CLEARANCE__PROP);
		out.value(getClearance());
		out.name(MAY_SHARE__PROP);
		out.value(isMayShare());
		out.name(MAP_URL__PROP);
		out.value(getMapUrl());
		if (hasShare()) {
			out.name(SHARE__PROP);
			getShare().writeTo(out);
		}
		if (hasInvitation()) {
			out.name(INVITATION__PROP);
			getInvitation().writeTo(out);
		}
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
			case CLEARANCE__PROP: setClearance(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case MAY_SHARE__PROP: setMayShare(in.nextBoolean()); break;
			case MAP_URL__PROP: setMapUrl(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case SHARE__PROP: setShare(de.haumacher.imageServer.shared.model.ShareInfo.readShareInfo(in)); break;
			case INVITATION__PROP: setInvitation(de.haumacher.imageServer.shared.model.InvitationInfo.readInvitationInfo(in)); break;
			default: super.readField(in, field);
		}
	}

}
