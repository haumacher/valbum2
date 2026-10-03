package de.haumacher.imageServer.shared.model;

/**
 * Where a part of a collection points to, see {@link ImagePart#getRef()} and issue #221.
 *
 * <p>
 * The content hash is the identity: it is what survives a move, a rename of the album and a rename
 * of the file. The path is a hint, the place the photograph was last seen, which is looked at
 * first and refreshed whenever the collection is written; where the hint no longer holds those
 * contents, the space's hash index (issue #118) says where they are now.
 * </p>
 */
public class PhotoRef extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.PhotoRef} instance.
	 */
	public static de.haumacher.imageServer.shared.model.PhotoRef create() {
		return new de.haumacher.imageServer.shared.model.PhotoRef();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.PhotoRef} type in JSON format. */
	public static final String PHOTO_REF__TYPE = "PhotoRef";

	/** @see #getHash() */
	private static final String HASH__PROP = "hash";

	/** @see #getPath() */
	private static final String PATH__PROP = "path";

	private String _hash = "";

	private String _path = "";

	/**
	 * Creates a {@link PhotoRef} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.PhotoRef#create()
	 */
	protected PhotoRef() {
		super();
	}

	/**
	 * The SHA-256 of the photograph's contents, lower-case hex.
	 */
	public final String getHash() {
		return _hash;
	}

	/**
	 * @see #getHash()
	 */
	public de.haumacher.imageServer.shared.model.PhotoRef setHash(String value) {
		internalSetHash(value);
		return this;
	}

	/** Internal setter for {@link #getHash()} without chain call utility. */
	protected final void internalSetHash(String value) {
		_hash = value;
	}

	/**
	 * The photograph's path relative to the space root, <code>/</code>-separated, as last seen.
	 */
	public final String getPath() {
		return _path;
	}

	/**
	 * @see #getPath()
	 */
	public de.haumacher.imageServer.shared.model.PhotoRef setPath(String value) {
		internalSetPath(value);
		return this;
	}

	/** Internal setter for {@link #getPath()} without chain call utility. */
	protected final void internalSetPath(String value) {
		_path = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.PhotoRef readPhotoRef(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.PhotoRef result = new de.haumacher.imageServer.shared.model.PhotoRef();
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
		out.name(PATH__PROP);
		out.value(getPath());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case HASH__PROP: setHash(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case PATH__PROP: setPath(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
