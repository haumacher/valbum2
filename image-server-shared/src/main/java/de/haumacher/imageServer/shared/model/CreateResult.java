package de.haumacher.imageServer.shared.model;

/**
 * Answer to a <code>PUT</code> that created a new album folder, telling where the album landed, see
 * issue #48.
 *
 * <p>
 * The folder a client asks for is not necessarily the folder the album ends up in: when the folder
 * above it carries a {@link ListingInfo#getPlacement()} rule, the album is filed into its year (or
 * month) folder. A client that ignores this answer would look for its new album where it is not.
 * </p>
 */
public class CreateResult extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.CreateResult} instance.
	 */
	public static de.haumacher.imageServer.shared.model.CreateResult create() {
		return new de.haumacher.imageServer.shared.model.CreateResult();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.CreateResult} type in JSON format. */
	public static final String CREATE_RESULT__TYPE = "CreateResult";

	/** @see #getPath() */
	private static final String PATH__PROP = "path";

	/** @see #getMessage() */
	private static final String MESSAGE__PROP = "message";

	private String _path = "";

	private String _message = "";

	/**
	 * Creates a {@link CreateResult} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.CreateResult#create()
	 */
	protected CreateResult() {
		super();
	}

	/**
	 * The path of the created folder, relative to the caller's space; never empty.
	 *
	 * <p>
	 * The same coordinates a {@link MoveRequest#getTarget()} is given in.
	 * </p>
	 */
	public final String getPath() {
		return _path;
	}

	/**
	 * @see #getPath()
	 */
	public de.haumacher.imageServer.shared.model.CreateResult setPath(String value) {
		internalSetPath(value);
		return this;
	}

	/** Internal setter for {@link #getPath()} without chain call utility. */
	protected final void internalSetPath(String value) {
		_path = value;
	}

	/**
	 * Why the album is not where it was asked for; empty when it was created exactly there.
	 *
	 * <p>
	 * Nothing happens silently: an album that was filed away by a rule says so here.
	 * </p>
	 */
	public final String getMessage() {
		return _message;
	}

	/**
	 * @see #getMessage()
	 */
	public de.haumacher.imageServer.shared.model.CreateResult setMessage(String value) {
		internalSetMessage(value);
		return this;
	}

	/** Internal setter for {@link #getMessage()} without chain call utility. */
	protected final void internalSetMessage(String value) {
		_message = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.CreateResult readCreateResult(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.CreateResult result = new de.haumacher.imageServer.shared.model.CreateResult();
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
		out.name(PATH__PROP);
		out.value(getPath());
		out.name(MESSAGE__PROP);
		out.value(getMessage());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case PATH__PROP: setPath(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case MESSAGE__PROP: setMessage(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
