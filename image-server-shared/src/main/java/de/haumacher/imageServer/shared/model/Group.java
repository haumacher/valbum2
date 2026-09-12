package de.haumacher.imageServer.shared.model;

/**
 * A named list of users, usable wherever a single user can be named, see issue #49.
 *
 * <p>
 * Created by any member and owned by its creator; sent to <code>&lt;data&gt;/?action=group</code>
 * to create it or to replace its members, and to <code>&lt;data&gt;/?action=ungroup</code> to
 * remove it.
 * </p>
 */
public class Group extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.Group} instance.
	 */
	public static de.haumacher.imageServer.shared.model.Group create() {
		return new de.haumacher.imageServer.shared.model.Group();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.Group} type in JSON format. */
	public static final String GROUP__TYPE = "Group";

	/** @see #getName() */
	private static final String NAME__PROP = "name";

	/** @see #getOwner() */
	private static final String OWNER__PROP = "owner";

	/** @see #getMembers() */
	private static final String MEMBERS__PROP = "members";

	/** @see #getCreated() */
	private static final String CREATED__PROP = "created";

	private String _name = "";

	private String _owner = "";

	private final java.util.List<de.haumacher.imageServer.shared.model.MemberName> _members = new java.util.ArrayList<>();

	private String _created = "";

	/**
	 * Creates a {@link Group} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.Group#create()
	 */
	protected Group() {
		super();
	}

	/**
	 * The name of the group, following the rules a user name follows.
	 */
	public final String getName() {
		return _name;
	}

	/**
	 * @see #getName()
	 */
	public de.haumacher.imageServer.shared.model.Group setName(String value) {
		internalSetName(value);
		return this;
	}

	/** Internal setter for {@link #getName()} without chain call utility. */
	protected final void internalSetName(String value) {
		_name = value;
	}

	/**
	 * The name of the user who owns the group; answered by the server, ignored in a request.
	 */
	public final String getOwner() {
		return _owner;
	}

	/**
	 * @see #getOwner()
	 */
	public de.haumacher.imageServer.shared.model.Group setOwner(String value) {
		internalSetOwner(value);
		return this;
	}

	/** Internal setter for {@link #getOwner()} without chain call utility. */
	protected final void internalSetOwner(String value) {
		_owner = value;
	}

	/**
	 * The names of the users in the group.
	 *
	 * <p>
	 * A list of messages, not a list of plain strings, see {@link FolderResource#getRights()}.
	 * </p>
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.MemberName> getMembers() {
		return _members;
	}

	/**
	 * @see #getMembers()
	 */
	public de.haumacher.imageServer.shared.model.Group setMembers(java.util.List<? extends de.haumacher.imageServer.shared.model.MemberName> value) {
		internalSetMembers(value);
		return this;
	}

	/** Internal setter for {@link #getMembers()} without chain call utility. */
	protected final void internalSetMembers(java.util.List<? extends de.haumacher.imageServer.shared.model.MemberName> value) {
		if (value == null) throw new IllegalArgumentException("Property 'members' cannot be null.");
		_members.clear();
		_members.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getMembers()} list.
	 */
	public de.haumacher.imageServer.shared.model.Group addMember(de.haumacher.imageServer.shared.model.MemberName value) {
		internalAddMember(value);
		return this;
	}

	/** Implementation of {@link #addMember(de.haumacher.imageServer.shared.model.MemberName)} without chain call utility. */
	protected final void internalAddMember(de.haumacher.imageServer.shared.model.MemberName value) {
		_members.add(value);
	}

	/**
	 * Removes a value from the {@link #getMembers()} list.
	 */
	public final void removeMember(de.haumacher.imageServer.shared.model.MemberName value) {
		_members.remove(value);
	}

	/**
	 * When the group was created, an ISO-8601 instant; answered by the server, ignored in a request.
	 */
	public final String getCreated() {
		return _created;
	}

	/**
	 * @see #getCreated()
	 */
	public de.haumacher.imageServer.shared.model.Group setCreated(String value) {
		internalSetCreated(value);
		return this;
	}

	/** Internal setter for {@link #getCreated()} without chain call utility. */
	protected final void internalSetCreated(String value) {
		_created = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.Group readGroup(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.Group result = new de.haumacher.imageServer.shared.model.Group();
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
		out.name(OWNER__PROP);
		out.value(getOwner());
		out.name(MEMBERS__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.MemberName x : getMembers()) {
			x.writeTo(out);
		}
		out.endArray();
		out.name(CREATED__PROP);
		out.value(getCreated());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case NAME__PROP: setName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case OWNER__PROP: setOwner(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case MEMBERS__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addMember(de.haumacher.imageServer.shared.model.MemberName.readMemberName(in));
				}
				in.endArray();
			}
			break;
			case CREATED__PROP: setCreated(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
