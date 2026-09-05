package de.haumacher.imageServer.shared.model;

/**
 * The hash of a single content, see {@link UploadCheck}.
 */
public class ContentHash extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.ContentHash} instance.
	 */
	public static de.haumacher.imageServer.shared.model.ContentHash create() {
		return new de.haumacher.imageServer.shared.model.ContentHash();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.ContentHash} type in JSON format. */
	public static final String CONTENT_HASH__TYPE = "ContentHash";

	/** @see #getHash() */
	private static final String HASH__PROP = "hash";

	private String _hash = "";

	/**
	 * Creates a {@link ContentHash} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.ContentHash#create()
	 */
	protected ContentHash() {
		super();
	}

	/**
	 * The SHA-256 hash (lower-case hex) of the content.
	 */
	public final String getHash() {
		return _hash;
	}

	/**
	 * @see #getHash()
	 */
	public de.haumacher.imageServer.shared.model.ContentHash setHash(String value) {
		internalSetHash(value);
		return this;
	}

	/** Internal setter for {@link #getHash()} without chain call utility. */
	protected final void internalSetHash(String value) {
		_hash = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.ContentHash readContentHash(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.ContentHash result = new de.haumacher.imageServer.shared.model.ContentHash();
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
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case HASH__PROP: setHash(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
