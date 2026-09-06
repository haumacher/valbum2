package de.haumacher.imageServer.shared.model;

/**
 * The name of a single entry to move, see {@link MoveRequest#getNames()}.
 */
public class MoveName extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.MoveName} instance.
	 */
	public static de.haumacher.imageServer.shared.model.MoveName create() {
		return new de.haumacher.imageServer.shared.model.MoveName();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.MoveName} type in JSON format. */
	public static final String MOVE_NAME__TYPE = "MoveName";

	/** @see #getName() */
	private static final String NAME__PROP = "name";

	private String _name = "";

	/**
	 * Creates a {@link MoveName} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.MoveName#create()
	 */
	protected MoveName() {
		super();
	}

	/**
	 * The name of the entry in the source folder.
	 */
	public final String getName() {
		return _name;
	}

	/**
	 * @see #getName()
	 */
	public de.haumacher.imageServer.shared.model.MoveName setName(String value) {
		internalSetName(value);
		return this;
	}

	/** Internal setter for {@link #getName()} without chain call utility. */
	protected final void internalSetName(String value) {
		_name = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.MoveName readMoveName(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.MoveName result = new de.haumacher.imageServer.shared.model.MoveName();
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
