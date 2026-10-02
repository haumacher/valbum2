package de.haumacher.imageServer.shared.model;

/**
 * A file of an upload that was not stored, see {@link UploadResult#getRefused()}.
 */
public class RefusedFile extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.RefusedFile} instance.
	 */
	public static de.haumacher.imageServer.shared.model.RefusedFile create() {
		return new de.haumacher.imageServer.shared.model.RefusedFile();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.RefusedFile} type in JSON format. */
	public static final String REFUSED_FILE__TYPE = "RefusedFile";

	/** @see #getName() */
	private static final String NAME__PROP = "name";

	/** @see #getReason() */
	private static final String REASON__PROP = "reason";

	private String _name = "";

	private String _reason = "";

	/**
	 * Creates a {@link RefusedFile} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.RefusedFile#create()
	 */
	protected RefusedFile() {
		super();
	}

	/**
	 * The file name as it was sent by the client.
	 */
	public final String getName() {
		return _name;
	}

	/**
	 * @see #getName()
	 */
	public de.haumacher.imageServer.shared.model.RefusedFile setName(String value) {
		internalSetName(value);
		return this;
	}

	/** Internal setter for {@link #getName()} without chain call utility. */
	protected final void internalSetName(String value) {
		_name = value;
	}

	/**
	 * Why it was not stored, a sentence naming the file, meant for the user.
	 */
	public final String getReason() {
		return _reason;
	}

	/**
	 * @see #getReason()
	 */
	public de.haumacher.imageServer.shared.model.RefusedFile setReason(String value) {
		internalSetReason(value);
		return this;
	}

	/** Internal setter for {@link #getReason()} without chain call utility. */
	protected final void internalSetReason(String value) {
		_reason = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.RefusedFile readRefusedFile(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.RefusedFile result = new de.haumacher.imageServer.shared.model.RefusedFile();
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
		out.name(REASON__PROP);
		out.value(getReason());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case NAME__PROP: setName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case REASON__PROP: setReason(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
