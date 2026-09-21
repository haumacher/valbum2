package de.haumacher.imageServer.shared.model;

/**
 * The face a {@link Person} is shown by, see {@link Person#getCover()}.
 */
public class PersonCover extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.PersonCover} instance.
	 */
	public static de.haumacher.imageServer.shared.model.PersonCover create() {
		return new de.haumacher.imageServer.shared.model.PersonCover();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.PersonCover} type in JSON format. */
	public static final String PERSON_COVER__TYPE = "PersonCover";

	/** @see #getPath() */
	private static final String PATH__PROP = "path";

	/** @see #getFace() */
	private static final String FACE__PROP = "face";

	private String _path = "";

	private int _face = 0;

	/**
	 * Creates a {@link PersonCover} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.PersonCover#create()
	 */
	protected PersonCover() {
		super();
	}

	/**
	 * The photograph, as a path relative to the space root, <code>/</code> as separator.
	 */
	public final String getPath() {
		return _path;
	}

	/**
	 * @see #getPath()
	 */
	public de.haumacher.imageServer.shared.model.PersonCover setPath(String value) {
		internalSetPath(value);
		return this;
	}

	/** Internal setter for {@link #getPath()} without chain call utility. */
	protected final void internalSetPath(String value) {
		_path = value;
	}

	/**
	 * Which face of it, see {@link FaceInfo#getIndex()}.
	 */
	public final int getFace() {
		return _face;
	}

	/**
	 * @see #getFace()
	 */
	public de.haumacher.imageServer.shared.model.PersonCover setFace(int value) {
		internalSetFace(value);
		return this;
	}

	/** Internal setter for {@link #getFace()} without chain call utility. */
	protected final void internalSetFace(int value) {
		_face = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.PersonCover readPersonCover(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.PersonCover result = new de.haumacher.imageServer.shared.model.PersonCover();
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
		out.name(FACE__PROP);
		out.value(getFace());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case PATH__PROP: setPath(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case FACE__PROP: setFace(in.nextInt()); break;
			default: super.readField(in, field);
		}
	}

}
