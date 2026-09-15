package de.haumacher.imageServer.shared.model;

/**
 * What a user of this space may do, sent to <code>&lt;data&gt;/?action=set-permission</code>
 * (issue #83).
 *
 * <p>
 * The administrator's decision, and the only way a permission ever changes. The last administrator
 * of a space cannot be demoted.
 * </p>
 */
public class UserPermission extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.UserPermission} instance.
	 */
	public static de.haumacher.imageServer.shared.model.UserPermission create() {
		return new de.haumacher.imageServer.shared.model.UserPermission();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.UserPermission} type in JSON format. */
	public static final String USER_PERMISSION__TYPE = "UserPermission";

	/** @see #getName() */
	private static final String NAME__PROP = "name";

	/** @see #getRole() */
	private static final String ROLE__PROP = "role";

	/** @see #getClearance() */
	private static final String CLEARANCE__PROP = "clearance";

	/** @see #isMayShare() */
	private static final String MAY_SHARE__PROP = "mayShare";

	private String _name = "";

	private String _role = "";

	private String _clearance = "";

	private boolean _mayShare = false;

	/**
	 * Creates a {@link UserPermission} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.UserPermission#create()
	 */
	protected UserPermission() {
		super();
	}

	/**
	 * The name of the user whose permission is set.
	 */
	public final String getName() {
		return _name;
	}

	/**
	 * @see #getName()
	 */
	public de.haumacher.imageServer.shared.model.UserPermission setName(String value) {
		internalSetName(value);
		return this;
	}

	/** Internal setter for {@link #getName()} without chain call utility. */
	protected final void internalSetName(String value) {
		_name = value;
	}

	/**
	 * The role to give them: <code>admin</code>, <code>edit</code>, <code>contribute</code> or <code>view</code>.
	 */
	public final String getRole() {
		return _role;
	}

	/**
	 * @see #getRole()
	 */
	public de.haumacher.imageServer.shared.model.UserPermission setRole(String value) {
		internalSetRole(value);
		return this;
	}

	/** Internal setter for {@link #getRole()} without chain call utility. */
	protected final void internalSetRole(String value) {
		_role = value;
	}

	/**
	 * The clearance to give them: <code>public</code>, <code>nonPrivate</code> or <code>all</code>.
	 */
	public final String getClearance() {
		return _clearance;
	}

	/**
	 * @see #getClearance()
	 */
	public de.haumacher.imageServer.shared.model.UserPermission setClearance(String value) {
		internalSetClearance(value);
		return this;
	}

	/** Internal setter for {@link #getClearance()} without chain call utility. */
	protected final void internalSetClearance(String value) {
		_clearance = value;
	}

	/**
	 * Whether they may create share links.
	 */
	public final boolean isMayShare() {
		return _mayShare;
	}

	/**
	 * @see #isMayShare()
	 */
	public de.haumacher.imageServer.shared.model.UserPermission setMayShare(boolean value) {
		internalSetMayShare(value);
		return this;
	}

	/** Internal setter for {@link #isMayShare()} without chain call utility. */
	protected final void internalSetMayShare(boolean value) {
		_mayShare = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.UserPermission readUserPermission(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.UserPermission result = new de.haumacher.imageServer.shared.model.UserPermission();
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
		out.name(NAME__PROP);
		out.value(getName());
		out.name(ROLE__PROP);
		out.value(getRole());
		out.name(CLEARANCE__PROP);
		out.value(getClearance());
		out.name(MAY_SHARE__PROP);
		out.value(isMayShare());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case NAME__PROP: setName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case ROLE__PROP: setRole(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case CLEARANCE__PROP: setClearance(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case MAY_SHARE__PROP: setMayShare(in.nextBoolean()); break;
			default: super.readField(in, field);
		}
	}

}
