package de.haumacher.imageServer.shared.model;

/**
 * A user of this server as another user may see them, see {@link UserList}.
 *
 * <p>
 * The name and the role, and nothing else: devices, tokens and spaces are not shared.
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

	private String _name = "";

	private String _role = "";

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
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case NAME__PROP: setName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case ROLE__PROP: setRole(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
