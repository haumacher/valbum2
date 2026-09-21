package de.haumacher.imageServer.shared.model;

/**
 * What <code>?action=create-person</code> asks for.
 */
public class PersonCreate extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.PersonCreate} instance.
	 */
	public static de.haumacher.imageServer.shared.model.PersonCreate create() {
		return new de.haumacher.imageServer.shared.model.PersonCreate();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.PersonCreate} type in JSON format. */
	public static final String PERSON_CREATE__TYPE = "PersonCreate";

	/** @see #getName() */
	private static final String NAME__PROP = "name";

	private String _name = "";

	/**
	 * Creates a {@link PersonCreate} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.PersonCreate#create()
	 */
	protected PersonCreate() {
		super();
	}

	/**
	 * The name of the new person; blanks are trimmed and an empty name is refused.
	 */
	public final String getName() {
		return _name;
	}

	/**
	 * @see #getName()
	 */
	public de.haumacher.imageServer.shared.model.PersonCreate setName(String value) {
		internalSetName(value);
		return this;
	}

	/** Internal setter for {@link #getName()} without chain call utility. */
	protected final void internalSetName(String value) {
		_name = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.PersonCreate readPersonCreate(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.PersonCreate result = new de.haumacher.imageServer.shared.model.PersonCreate();
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
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case NAME__PROP: setName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
