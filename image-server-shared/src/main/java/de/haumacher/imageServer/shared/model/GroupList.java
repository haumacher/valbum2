package de.haumacher.imageServer.shared.model;

/**
 * The groups the caller owns and the groups they are in, answered by
 * <code>&lt;data&gt;/?type=groups</code>.
 */
public class GroupList extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.GroupList} instance.
	 */
	public static de.haumacher.imageServer.shared.model.GroupList create() {
		return new de.haumacher.imageServer.shared.model.GroupList();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.GroupList} type in JSON format. */
	public static final String GROUP_LIST__TYPE = "GroupList";

	/** @see #getGroups() */
	private static final String GROUPS__PROP = "groups";

	private final java.util.List<de.haumacher.imageServer.shared.model.Group> _groups = new java.util.ArrayList<>();

	/**
	 * Creates a {@link GroupList} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.GroupList#create()
	 */
	protected GroupList() {
		super();
	}

	/**
	 * The groups, those the caller owns first.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.Group> getGroups() {
		return _groups;
	}

	/**
	 * @see #getGroups()
	 */
	public de.haumacher.imageServer.shared.model.GroupList setGroups(java.util.List<? extends de.haumacher.imageServer.shared.model.Group> value) {
		internalSetGroups(value);
		return this;
	}

	/** Internal setter for {@link #getGroups()} without chain call utility. */
	protected final void internalSetGroups(java.util.List<? extends de.haumacher.imageServer.shared.model.Group> value) {
		if (value == null) throw new IllegalArgumentException("Property 'groups' cannot be null.");
		_groups.clear();
		_groups.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getGroups()} list.
	 */
	public de.haumacher.imageServer.shared.model.GroupList addGroup(de.haumacher.imageServer.shared.model.Group value) {
		internalAddGroup(value);
		return this;
	}

	/** Implementation of {@link #addGroup(de.haumacher.imageServer.shared.model.Group)} without chain call utility. */
	protected final void internalAddGroup(de.haumacher.imageServer.shared.model.Group value) {
		_groups.add(value);
	}

	/**
	 * Removes a value from the {@link #getGroups()} list.
	 */
	public final void removeGroup(de.haumacher.imageServer.shared.model.Group value) {
		_groups.remove(value);
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.GroupList readGroupList(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.GroupList result = new de.haumacher.imageServer.shared.model.GroupList();
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
		out.name(GROUPS__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.Group x : getGroups()) {
			x.writeTo(out);
		}
		out.endArray();
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case GROUPS__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addGroup(de.haumacher.imageServer.shared.model.Group.readGroup(in));
				}
				in.endArray();
			}
			break;
			default: super.readField(in, field);
		}
	}

}
