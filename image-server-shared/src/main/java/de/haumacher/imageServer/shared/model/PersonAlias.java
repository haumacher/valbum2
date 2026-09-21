package de.haumacher.imageServer.shared.model;

/**
 * One identifier a {@link Person} answers to besides their own, see {@link Person#getAliases()}.
 *
 * <p>
 * A message and not a plain string: the Dart backend of the model generator mis-types a
 * <code>repeated string</code> field, see {@link UploadCheck#getHashes()}.
 * </p>
 */
public class PersonAlias extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.PersonAlias} instance.
	 */
	public static de.haumacher.imageServer.shared.model.PersonAlias create() {
		return new de.haumacher.imageServer.shared.model.PersonAlias();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.PersonAlias} type in JSON format. */
	public static final String PERSON_ALIAS__TYPE = "PersonAlias";

	/** @see #getId() */
	private static final String ID__PROP = "id";

	private String _id = "";

	/**
	 * Creates a {@link PersonAlias} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.PersonAlias#create()
	 */
	protected PersonAlias() {
		super();
	}

	/**
	 * The identifier that was merged away.
	 */
	public final String getId() {
		return _id;
	}

	/**
	 * @see #getId()
	 */
	public de.haumacher.imageServer.shared.model.PersonAlias setId(String value) {
		internalSetId(value);
		return this;
	}

	/** Internal setter for {@link #getId()} without chain call utility. */
	protected final void internalSetId(String value) {
		_id = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.PersonAlias readPersonAlias(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.PersonAlias result = new de.haumacher.imageServer.shared.model.PersonAlias();
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
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case ID__PROP: setId(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
