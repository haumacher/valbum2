package de.haumacher.imageServer.shared.model;

/**
 * A photograph of a {@link PhotoStack}.
 */
public class StackMember extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.StackMember} instance.
	 */
	public static de.haumacher.imageServer.shared.model.StackMember create() {
		return new de.haumacher.imageServer.shared.model.StackMember();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.StackMember} type in JSON format. */
	public static final String STACK_MEMBER__TYPE = "StackMember";

	/** @see #getName() */
	private static final String NAME__PROP = "name";

	private String _name = "";

	/**
	 * Creates a {@link StackMember} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.StackMember#create()
	 */
	protected StackMember() {
		super();
	}

	/**
	 * The {@link ImagePart#getName()} of the photograph in its album.
	 */
	public final String getName() {
		return _name;
	}

	/**
	 * @see #getName()
	 */
	public de.haumacher.imageServer.shared.model.StackMember setName(String value) {
		internalSetName(value);
		return this;
	}

	/** Internal setter for {@link #getName()} without chain call utility. */
	protected final void internalSetName(String value) {
		_name = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.StackMember readStackMember(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.StackMember result = new de.haumacher.imageServer.shared.model.StackMember();
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
