package de.haumacher.imageServer.shared.model;

/**
 * The renaming of a {@link Group}, sent to <code>&lt;data&gt;/?action=regroup</code>, see issue #55.
 *
 * <p>
 * A rename is its own request because it is more than a change of the group's name: every grant
 * made out to the group is rewritten in the same step, so that nothing that was shared with the
 * group stops working because it was given a better name. The answer is the renamed group, as
 * <code>?action=group</code> answers it.
 * </p>
 */
public class GroupRename extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.GroupRename} instance.
	 */
	public static de.haumacher.imageServer.shared.model.GroupRename create() {
		return new de.haumacher.imageServer.shared.model.GroupRename();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.GroupRename} type in JSON format. */
	public static final String GROUP_RENAME__TYPE = "GroupRename";

	/** @see #getName() */
	private static final String NAME__PROP = "name";

	/** @see #getNewName() */
	private static final String NEW_NAME__PROP = "newName";

	private String _name = "";

	private String _newName = "";

	/**
	 * Creates a {@link GroupRename} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.GroupRename#create()
	 */
	protected GroupRename() {
		super();
	}

	/**
	 * The name of the group to rename.
	 */
	public final String getName() {
		return _name;
	}

	/**
	 * @see #getName()
	 */
	public de.haumacher.imageServer.shared.model.GroupRename setName(String value) {
		internalSetName(value);
		return this;
	}

	/** Internal setter for {@link #getName()} without chain call utility. */
	protected final void internalSetName(String value) {
		_name = value;
	}

	/**
	 * The name it should have, following the rules a user name follows.
	 */
	public final String getNewName() {
		return _newName;
	}

	/**
	 * @see #getNewName()
	 */
	public de.haumacher.imageServer.shared.model.GroupRename setNewName(String value) {
		internalSetNewName(value);
		return this;
	}

	/** Internal setter for {@link #getNewName()} without chain call utility. */
	protected final void internalSetNewName(String value) {
		_newName = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.GroupRename readGroupRename(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.GroupRename result = new de.haumacher.imageServer.shared.model.GroupRename();
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
		out.name(NEW_NAME__PROP);
		out.value(getNewName());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case NAME__PROP: setName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case NEW_NAME__PROP: setNewName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
