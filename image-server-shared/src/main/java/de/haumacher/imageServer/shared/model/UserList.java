package de.haumacher.imageServer.shared.model;

/**
 * The users of this server, answered by <code>&lt;data&gt;/?type=users</code>.
 *
 * <p>
 * Needed to share: a member picks whom to grant something to. Members and the admin may ask,
 * guests and anonymous callers may not.
 * </p>
 */
public class UserList extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.UserList} instance.
	 */
	public static de.haumacher.imageServer.shared.model.UserList create() {
		return new de.haumacher.imageServer.shared.model.UserList();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.UserList} type in JSON format. */
	public static final String USER_LIST__TYPE = "UserList";

	/** @see #getUsers() */
	private static final String USERS__PROP = "users";

	private final java.util.List<de.haumacher.imageServer.shared.model.UserEntry> _users = new java.util.ArrayList<>();

	/**
	 * Creates a {@link UserList} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.UserList#create()
	 */
	protected UserList() {
		super();
	}

	/**
	 * The users, in the order they were created.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.UserEntry> getUsers() {
		return _users;
	}

	/**
	 * @see #getUsers()
	 */
	public de.haumacher.imageServer.shared.model.UserList setUsers(java.util.List<? extends de.haumacher.imageServer.shared.model.UserEntry> value) {
		internalSetUsers(value);
		return this;
	}

	/** Internal setter for {@link #getUsers()} without chain call utility. */
	protected final void internalSetUsers(java.util.List<? extends de.haumacher.imageServer.shared.model.UserEntry> value) {
		if (value == null) throw new IllegalArgumentException("Property 'users' cannot be null.");
		_users.clear();
		_users.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getUsers()} list.
	 */
	public de.haumacher.imageServer.shared.model.UserList addUser(de.haumacher.imageServer.shared.model.UserEntry value) {
		internalAddUser(value);
		return this;
	}

	/** Implementation of {@link #addUser(de.haumacher.imageServer.shared.model.UserEntry)} without chain call utility. */
	protected final void internalAddUser(de.haumacher.imageServer.shared.model.UserEntry value) {
		_users.add(value);
	}

	/**
	 * Removes a value from the {@link #getUsers()} list.
	 */
	public final void removeUser(de.haumacher.imageServer.shared.model.UserEntry value) {
		_users.remove(value);
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.UserList readUserList(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.UserList result = new de.haumacher.imageServer.shared.model.UserList();
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
		out.name(USERS__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.UserEntry x : getUsers()) {
			x.writeTo(out);
		}
		out.endArray();
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case USERS__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addUser(de.haumacher.imageServer.shared.model.UserEntry.readUserEntry(in));
				}
				in.endArray();
			}
			break;
			default: super.readField(in, field);
		}
	}

}
