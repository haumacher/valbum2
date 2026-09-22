package de.haumacher.imageServer.shared.model;

/**
 * What <code>?action=rename-person</code> asks for.
 */
public class PersonRename extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.PersonRename} instance.
	 */
	public static de.haumacher.imageServer.shared.model.PersonRename create() {
		return new de.haumacher.imageServer.shared.model.PersonRename();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.PersonRename} type in JSON format. */
	public static final String PERSON_RENAME__TYPE = "PersonRename";

	/** @see #getId() */
	private static final String ID__PROP = "id";

	/** @see #getName() */
	private static final String NAME__PROP = "name";

	private String _id = "";

	private String _name = "";

	/**
	 * Creates a {@link PersonRename} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.PersonRename#create()
	 */
	protected PersonRename() {
		super();
	}

	/**
	 * The {@link Person#getId()} to rename; an alias of a person names that person.
	 */
	public final String getId() {
		return _id;
	}

	/**
	 * @see #getId()
	 */
	public de.haumacher.imageServer.shared.model.PersonRename setId(String value) {
		internalSetId(value);
		return this;
	}

	/** Internal setter for {@link #getId()} without chain call utility. */
	protected final void internalSetId(String value) {
		_id = value;
	}

	/**
	 * The new name; blanks are trimmed and an empty name is refused.
	 *
	 * <p>
	 * The typed string, split like {@link PersonCreate#getName()}: a name without a parenthesis
	 * clears the {@link Person#getNickname()} the person had, see issue #146.
	 * </p>
	 */
	public final String getName() {
		return _name;
	}

	/**
	 * @see #getName()
	 */
	public de.haumacher.imageServer.shared.model.PersonRename setName(String value) {
		internalSetName(value);
		return this;
	}

	/** Internal setter for {@link #getName()} without chain call utility. */
	protected final void internalSetName(String value) {
		_name = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.PersonRename readPersonRename(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.PersonRename result = new de.haumacher.imageServer.shared.model.PersonRename();
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
		out.name(ID__PROP);
		out.value(getId());
		out.name(NAME__PROP);
		out.value(getName());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case ID__PROP: setId(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case NAME__PROP: setName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
