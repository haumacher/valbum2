package de.haumacher.imageServer.shared.model;

/**
 * What somebody said about one face of one photograph, stored in the album, see issue #125.
 *
 * <p>
 * The box is the detector's box <em>as it stood when the tag was made</em>, copied out of the
 * {@link FaceInfo} the tagging request named and written in the same frame: normalised to
 * <code>0..1</code> in the raw raster of the file, before the EXIF orientation and before
 * {@link ImagePart#getOrientation()}. So the tag outlives the cache it came from, the model that found
 * it and any rotation the user applies; a later detection is matched to it by the overlap of the
 * two boxes.
 * </p>
 */
public class FaceTag extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.FaceTag} instance.
	 */
	public static de.haumacher.imageServer.shared.model.FaceTag create() {
		return new de.haumacher.imageServer.shared.model.FaceTag();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.FaceTag} type in JSON format. */
	public static final String FACE_TAG__TYPE = "FaceTag";

	/** @see #getX() */
	private static final String X__PROP = "x";

	/** @see #getY() */
	private static final String Y__PROP = "y";

	/** @see #getW() */
	private static final String W__PROP = "w";

	/** @see #getH() */
	private static final String H__PROP = "h";

	/** @see #getPerson() */
	private static final String PERSON__PROP = "person";

	/** @see #getState() */
	private static final String STATE__PROP = "state";

	private double _x = 0.0d;

	private double _y = 0.0d;

	private double _w = 0.0d;

	private double _h = 0.0d;

	private String _person = "";

	private de.haumacher.imageServer.shared.model.FaceState _state = de.haumacher.imageServer.shared.model.FaceState.UNDECIDED;

	/**
	 * Creates a {@link FaceTag} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.FaceTag#create()
	 */
	protected FaceTag() {
		super();
	}

	/**
	 * The left edge of the face, as a fraction of the image width, see {@link FaceInfo#getX()}.
	 */
	public final double getX() {
		return _x;
	}

	/**
	 * @see #getX()
	 */
	public de.haumacher.imageServer.shared.model.FaceTag setX(double value) {
		internalSetX(value);
		return this;
	}

	/** Internal setter for {@link #getX()} without chain call utility. */
	protected final void internalSetX(double value) {
		_x = value;
	}

	/**
	 * The top edge of the face, as a fraction of the image height, see {@link FaceInfo#getX()}.
	 */
	public final double getY() {
		return _y;
	}

	/**
	 * @see #getY()
	 */
	public de.haumacher.imageServer.shared.model.FaceTag setY(double value) {
		internalSetY(value);
		return this;
	}

	/** Internal setter for {@link #getY()} without chain call utility. */
	protected final void internalSetY(double value) {
		_y = value;
	}

	/**
	 * The width of the face, as a fraction of the image width, see {@link FaceInfo#getX()}.
	 */
	public final double getW() {
		return _w;
	}

	/**
	 * @see #getW()
	 */
	public de.haumacher.imageServer.shared.model.FaceTag setW(double value) {
		internalSetW(value);
		return this;
	}

	/** Internal setter for {@link #getW()} without chain call utility. */
	protected final void internalSetW(double value) {
		_w = value;
	}

	/**
	 * The height of the face, as a fraction of the image height, see {@link FaceInfo#getX()}.
	 */
	public final double getH() {
		return _h;
	}

	/**
	 * @see #getH()
	 */
	public de.haumacher.imageServer.shared.model.FaceTag setH(double value) {
		internalSetH(value);
		return this;
	}

	/** Internal setter for {@link #getH()} without chain call utility. */
	protected final void internalSetH(double value) {
		_h = value;
	}

	/**
	 * The {@link Person#getId()} this decision is about; empty for {@link FaceState#NOT_A_FACE}.
	 *
	 * <p>
	 * Stored as the person's own id at the moment of the tagging. A person that is merged into
	 * another one afterwards keeps this album untouched: the read path resolves the id through the
	 * {@link Person#getAliases()} of the register, see {@link FaceInfo#getPerson()}.
	 * </p>
	 */
	public final String getPerson() {
		return _person;
	}

	/**
	 * @see #getPerson()
	 */
	public de.haumacher.imageServer.shared.model.FaceTag setPerson(String value) {
		internalSetPerson(value);
		return this;
	}

	/** Internal setter for {@link #getPerson()} without chain call utility. */
	protected final void internalSetPerson(String value) {
		_person = value;
	}

	/**
	 * What was decided.
	 *
	 * <p>
	 * Undecided ({@link FaceState#UNDECIDED}) only on a region somebody marked by hand and nobody
	 * decided about (issue #155), see there; before that issue a stored tag was never undecided.
	 * </p>
	 */
	public final de.haumacher.imageServer.shared.model.FaceState getState() {
		return _state;
	}

	/**
	 * @see #getState()
	 */
	public de.haumacher.imageServer.shared.model.FaceTag setState(de.haumacher.imageServer.shared.model.FaceState value) {
		internalSetState(value);
		return this;
	}

	/** Internal setter for {@link #getState()} without chain call utility. */
	protected final void internalSetState(de.haumacher.imageServer.shared.model.FaceState value) {
		if (value == null) throw new IllegalArgumentException("Property 'state' cannot be null.");
		_state = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.FaceTag readFaceTag(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.FaceTag result = new de.haumacher.imageServer.shared.model.FaceTag();
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
		out.name(X__PROP);
		out.value(getX());
		out.name(Y__PROP);
		out.value(getY());
		out.name(W__PROP);
		out.value(getW());
		out.name(H__PROP);
		out.value(getH());
		out.name(PERSON__PROP);
		out.value(getPerson());
		out.name(STATE__PROP);
		getState().writeTo(out);
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case X__PROP: setX(in.nextDouble()); break;
			case Y__PROP: setY(in.nextDouble()); break;
			case W__PROP: setW(in.nextDouble()); break;
			case H__PROP: setH(in.nextDouble()); break;
			case PERSON__PROP: setPerson(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case STATE__PROP: setState(de.haumacher.imageServer.shared.model.FaceState.readFaceState(in)); break;
			default: super.readField(in, field);
		}
	}

}
