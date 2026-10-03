package de.haumacher.imageServer.shared.model;

/**
 * One label of a photograph, see {@link ImagePart#getLabels()} and issue #213.
 */
public class LabelName extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.LabelName} instance.
	 */
	public static de.haumacher.imageServer.shared.model.LabelName create() {
		return new de.haumacher.imageServer.shared.model.LabelName();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.LabelName} type in JSON format. */
	public static final String LABEL_NAME__TYPE = "LabelName";

	/** @see #getName() */
	private static final String NAME__PROP = "name";

	private String _name = "";

	/**
	 * Creates a {@link LabelName} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.LabelName#create()
	 */
	protected LabelName() {
		super();
	}

	/**
	 * The label as the author typed it, compared exactly.
	 */
	public final String getName() {
		return _name;
	}

	/**
	 * @see #getName()
	 */
	public de.haumacher.imageServer.shared.model.LabelName setName(String value) {
		internalSetName(value);
		return this;
	}

	/** Internal setter for {@link #getName()} without chain call utility. */
	protected final void internalSetName(String value) {
		_name = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.LabelName readLabelName(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.LabelName result = new de.haumacher.imageServer.shared.model.LabelName();
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
