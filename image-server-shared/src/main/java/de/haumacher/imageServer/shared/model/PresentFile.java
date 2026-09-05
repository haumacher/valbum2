package de.haumacher.imageServer.shared.model;

/**
 * A content of an {@link UploadCheckResult} that the folder already holds.
 */
public class PresentFile extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.PresentFile} instance.
	 */
	public static de.haumacher.imageServer.shared.model.PresentFile create() {
		return new de.haumacher.imageServer.shared.model.PresentFile();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.PresentFile} type in JSON format. */
	public static final String PRESENT_FILE__TYPE = "PresentFile";

	/** @see #getHash() */
	private static final String HASH__PROP = "hash";

	/** @see #getName() */
	private static final String NAME__PROP = "name";

	private String _hash = "";

	private String _name = "";

	/**
	 * Creates a {@link PresentFile} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.PresentFile#create()
	 */
	protected PresentFile() {
		super();
	}

	/**
	 * The SHA-256 hash (lower-case hex) that was asked for.
	 */
	public final String getHash() {
		return _hash;
	}

	/**
	 * @see #getHash()
	 */
	public de.haumacher.imageServer.shared.model.PresentFile setHash(String value) {
		internalSetHash(value);
		return this;
	}

	/** Internal setter for {@link #getHash()} without chain call utility. */
	protected final void internalSetHash(String value) {
		_hash = value;
	}

	/**
	 * The name of the file in the folder that has this content.
	 */
	public final String getName() {
		return _name;
	}

	/**
	 * @see #getName()
	 */
	public de.haumacher.imageServer.shared.model.PresentFile setName(String value) {
		internalSetName(value);
		return this;
	}

	/** Internal setter for {@link #getName()} without chain call utility. */
	protected final void internalSetName(String value) {
		_name = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.PresentFile readPresentFile(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.PresentFile result = new de.haumacher.imageServer.shared.model.PresentFile();
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
		out.name(HASH__PROP);
		out.value(getHash());
		out.name(NAME__PROP);
		out.value(getName());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case HASH__PROP: setHash(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case NAME__PROP: setName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
