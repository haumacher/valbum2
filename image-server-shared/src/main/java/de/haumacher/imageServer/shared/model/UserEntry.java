package de.haumacher.imageServer.shared.model;

/**
 * A user of this server as another user may see them, see {@link UserList}.
 *
 * <p>
 * The name and the role, and since issue #55 the three things a management screen shows beside
 * them: where the user's library lies, since when they are here, and how many devices they signed
 * in on. Never a token and never a device of theirs — what a device is called and when it was
 * paired is answered to its own owner only, see {@link DeviceList}.
 * </p>
 */
public class UserEntry extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.UserEntry} instance.
	 */
	public static de.haumacher.imageServer.shared.model.UserEntry create() {
		return new de.haumacher.imageServer.shared.model.UserEntry();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.UserEntry} type in JSON format. */
	public static final String USER_ENTRY__TYPE = "UserEntry";

	/** @see #getName() */
	private static final String NAME__PROP = "name";

	/** @see #getRole() */
	private static final String ROLE__PROP = "role";

	/** @see #getSpace() */
	private static final String SPACE__PROP = "space";

	/** @see #getCreated() */
	private static final String CREATED__PROP = "created";

	/** @see #getDevices() */
	private static final String DEVICES__PROP = "devices";

	/** @see #getClearance() */
	private static final String CLEARANCE__PROP = "clearance";

	/** @see #isMayShare() */
	private static final String MAY_SHARE__PROP = "mayShare";

	private String _name = "";

	private String _role = "";

	private String _space = "";

	private String _created = "";

	private int _devices = 0;

	private String _clearance = "";

	private boolean _mayShare = false;

	/**
	 * Creates a {@link UserEntry} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.UserEntry#create()
	 */
	protected UserEntry() {
		super();
	}

	/**
	 * The user's name, which is what a <code>user:&lt;name&gt;</code> subject names.
	 */
	public final String getName() {
		return _name;
	}

	/**
	 * @see #getName()
	 */
	public de.haumacher.imageServer.shared.model.UserEntry setName(String value) {
		internalSetName(value);
		return this;
	}

	/** Internal setter for {@link #getName()} without chain call utility. */
	protected final void internalSetName(String value) {
		_name = value;
	}

	/**
	 * The user's role: <code>admin</code>, <code>member</code> or <code>guest</code>.
	 */
	public final String getRole() {
		return _role;
	}

	/**
	 * @see #getRole()
	 */
	public de.haumacher.imageServer.shared.model.UserEntry setRole(String value) {
		internalSetRole(value);
		return this;
	}

	/** Internal setter for {@link #getRole()} without chain call utility. */
	protected final void internalSetRole(String value) {
		_role = value;
	}

	/**
	 * The folder below the server's base folder this user's requests are resolved against (issue #55).
	 *
	 * <p>
	 * Empty for a guest, who has no library of their own, and for the owner of a library that was
	 * never migrated, whose space is the base folder itself.
	 * </p>
	 */
	public final String getSpace() {
		return _space;
	}

	/**
	 * @see #getSpace()
	 */
	public de.haumacher.imageServer.shared.model.UserEntry setSpace(String value) {
		internalSetSpace(value);
		return this;
	}

	/** Internal setter for {@link #getSpace()} without chain call utility. */
	protected final void internalSetSpace(String value) {
		_space = value;
	}

	/**
	 * When the user was created, an ISO-8601 instant; empty if the server never recorded one (issue #55).
	 */
	public final String getCreated() {
		return _created;
	}

	/**
	 * @see #getCreated()
	 */
	public de.haumacher.imageServer.shared.model.UserEntry setCreated(String value) {
		internalSetCreated(value);
		return this;
	}

	/** Internal setter for {@link #getCreated()} without chain call utility. */
	protected final void internalSetCreated(String value) {
		_created = value;
	}

	/**
	 * How many devices the user is signed in on, answered by the server (issue #55).
	 */
	public final int getDevices() {
		return _devices;
	}

	/**
	 * @see #getDevices()
	 */
	public de.haumacher.imageServer.shared.model.UserEntry setDevices(int value) {
		internalSetDevices(value);
		return this;
	}

	/** Internal setter for {@link #getDevices()} without chain call utility. */
	protected final void internalSetDevices(int value) {
		_devices = value;
	}

	/**
	 * Which privacy levels this user may see: <code>public</code>, <code>nonPrivate</code> or
	 * <code>all</code> (issue #82).
	 */
	public final String getClearance() {
		return _clearance;
	}

	/**
	 * @see #getClearance()
	 */
	public de.haumacher.imageServer.shared.model.UserEntry setClearance(String value) {
		internalSetClearance(value);
		return this;
	}

	/** Internal setter for {@link #getClearance()} without chain call utility. */
	protected final void internalSetClearance(String value) {
		_clearance = value;
	}

	/**
	 * Whether this user may create share links (issue #82).
	 */
	public final boolean isMayShare() {
		return _mayShare;
	}

	/**
	 * @see #isMayShare()
	 */
	public de.haumacher.imageServer.shared.model.UserEntry setMayShare(boolean value) {
		internalSetMayShare(value);
		return this;
	}

	/** Internal setter for {@link #isMayShare()} without chain call utility. */
	protected final void internalSetMayShare(boolean value) {
		_mayShare = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.UserEntry readUserEntry(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.UserEntry result = new de.haumacher.imageServer.shared.model.UserEntry();
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
		out.name(SPACE__PROP);
		out.value(getSpace());
		out.name(CREATED__PROP);
		out.value(getCreated());
		out.name(DEVICES__PROP);
		out.value(getDevices());
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
			case SPACE__PROP: setSpace(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case CREATED__PROP: setCreated(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case DEVICES__PROP: setDevices(in.nextInt()); break;
			case CLEARANCE__PROP: setClearance(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case MAY_SHARE__PROP: setMayShare(in.nextBoolean()); break;
			default: super.readField(in, field);
		}
	}

}
