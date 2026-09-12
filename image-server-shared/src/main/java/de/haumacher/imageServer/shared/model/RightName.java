package de.haumacher.imageServer.shared.model;

/**
 * The name of a single right, see {@link FolderResource#getRights()}.
 */
public class RightName extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.RightName} instance.
	 */
	public static de.haumacher.imageServer.shared.model.RightName create() {
		return new de.haumacher.imageServer.shared.model.RightName();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.RightName} type in JSON format. */
	public static final String RIGHT_NAME__TYPE = "RightName";

	/** @see #getName() */
	private static final String NAME__PROP = "name";

	private String _name = "";

	/**
	 * Creates a {@link RightName} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.RightName#create()
	 */
	protected RightName() {
		super();
	}

	/**
	 * One of <code>view</code>, <code>download</code>, <code>contribute</code>, <code>edit</code>.
	 */
	public final String getName() {
		return _name;
	}

	/**
	 * @see #getName()
	 */
	public de.haumacher.imageServer.shared.model.RightName setName(String value) {
		internalSetName(value);
		return this;
	}

	/** Internal setter for {@link #getName()} without chain call utility. */
	protected final void internalSetName(String value) {
		_name = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.RightName readRightName(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.RightName result = new de.haumacher.imageServer.shared.model.RightName();
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
