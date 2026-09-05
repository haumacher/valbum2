package de.haumacher.imageServer.shared.model;

/**
 * What happened to a single file of an upload, see {@link UploadResult}.
 */
public class UploadedFile extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.UploadedFile} instance.
	 */
	public static de.haumacher.imageServer.shared.model.UploadedFile create() {
		return new de.haumacher.imageServer.shared.model.UploadedFile();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.UploadedFile} type in JSON format. */
	public static final String UPLOADED_FILE__TYPE = "UploadedFile";

	/** @see #getName() */
	private static final String NAME__PROP = "name";

	/** @see #getStoredAs() */
	private static final String STORED_AS__PROP = "storedAs";

	/** @see #getHash() */
	private static final String HASH__PROP = "hash";

	/** @see #getStatus() */
	private static final String STATUS__PROP = "status";

	private String _name = "";

	private String _storedAs = "";

	private String _hash = "";

	private String _status = "";

	/**
	 * Creates a {@link UploadedFile} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.UploadedFile#create()
	 */
	protected UploadedFile() {
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
	public de.haumacher.imageServer.shared.model.UploadedFile setName(String value) {
		internalSetName(value);
		return this;
	}

	/** Internal setter for {@link #getName()} without chain call utility. */
	protected final void internalSetName(String value) {
		_name = value;
	}

	/**
	 * The name of the file on the server: the (potentially de-duplicated) name the contents were
	 * stored under, or the name of the existing file that already had these contents.
	 */
	public final String getStoredAs() {
		return _storedAs;
	}

	/**
	 * @see #getStoredAs()
	 */
	public de.haumacher.imageServer.shared.model.UploadedFile setStoredAs(String value) {
		internalSetStoredAs(value);
		return this;
	}

	/** Internal setter for {@link #getStoredAs()} without chain call utility. */
	protected final void internalSetStoredAs(String value) {
		_storedAs = value;
	}

	/**
	 * The SHA-256 hash (lower-case hex) of the received contents, as computed by the server.
	 */
	public final String getHash() {
		return _hash;
	}

	/**
	 * @see #getHash()
	 */
	public de.haumacher.imageServer.shared.model.UploadedFile setHash(String value) {
		internalSetHash(value);
		return this;
	}

	/** Internal setter for {@link #getHash()} without chain call utility. */
	protected final void internalSetHash(String value) {
		_hash = value;
	}

	/**
	 * <code>stored</code> if the contents were written to the album, <code>present</code> if the
	 * folder already held them and nothing was written.
	 */
	public final String getStatus() {
		return _status;
	}

	/**
	 * @see #getStatus()
	 */
	public de.haumacher.imageServer.shared.model.UploadedFile setStatus(String value) {
		internalSetStatus(value);
		return this;
	}

	/** Internal setter for {@link #getStatus()} without chain call utility. */
	protected final void internalSetStatus(String value) {
		_status = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.UploadedFile readUploadedFile(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.UploadedFile result = new de.haumacher.imageServer.shared.model.UploadedFile();
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
		out.name(STORED_AS__PROP);
		out.value(getStoredAs());
		out.name(HASH__PROP);
		out.value(getHash());
		out.name(STATUS__PROP);
		out.value(getStatus());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case NAME__PROP: setName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case STORED_AS__PROP: setStoredAs(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case HASH__PROP: setHash(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case STATUS__PROP: setStatus(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
