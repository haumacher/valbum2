package de.haumacher.imageServer.shared.model;

/**
 * One decision about one face, see {@link TagFaces}.
 */
public class FaceAssignment extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.FaceAssignment} instance.
	 */
	public static de.haumacher.imageServer.shared.model.FaceAssignment create() {
		return new de.haumacher.imageServer.shared.model.FaceAssignment();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.FaceAssignment} type in JSON format. */
	public static final String FACE_ASSIGNMENT__TYPE = "FaceAssignment";

	/** @see #getImage() */
	private static final String IMAGE__PROP = "image";

	/** @see #getFace() */
	private static final String FACE__PROP = "face";

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

	private String _image = "";

	private int _face = 0;

	private double _x = 0.0d;

	private double _y = 0.0d;

	private double _w = 0.0d;

	private double _h = 0.0d;

	private String _person = "";

	private de.haumacher.imageServer.shared.model.FaceState _state = de.haumacher.imageServer.shared.model.FaceState.UNDECIDED;

	/**
	 * Creates a {@link FaceAssignment} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.FaceAssignment#create()
	 */
	protected FaceAssignment() {
		super();
	}

	/**
	 * The {@link ImagePart#getName()} of the photograph in the addressed album.
	 */
	public final String getImage() {
		return _image;
	}

	/**
	 * @see #getImage()
	 */
	public de.haumacher.imageServer.shared.model.FaceAssignment setImage(String value) {
		internalSetImage(value);
		return this;
	}

	/** Internal setter for {@link #getImage()} without chain call utility. */
	protected final void internalSetImage(String value) {
		_image = value;
	}

	/**
	 * Which of its faces, the {@link FaceInfo#getIndex()} of the answer this client read.
	 *
	 * <p>
	 * The box is copied from that {@link FaceInfo} into the stored {@link FaceTag}, so a client
	 * that decides about a face the server found never sends coordinates at all.
	 * </p>
	 *
	 * <p>
	 * Ignored where a box is given, see {@link #getX()}: a hand-marked face is <em>not</em> one of the
	 * answered faces, so there is no index that could name it.
	 * </p>
	 */
	public final int getFace() {
		return _face;
	}

	/**
	 * @see #getFace()
	 */
	public de.haumacher.imageServer.shared.model.FaceAssignment setFace(int value) {
		internalSetFace(value);
		return this;
	}

	/** Internal setter for {@link #getFace()} without chain call utility. */
	protected final void internalSetFace(int value) {
		_face = value;
	}

	/**
	 * The left edge of a hand-marked face, as a fraction of the width of the picture as it is
	 * shown; issue #147.
	 *
	 * <p>
	 * <b>A box is given exactly when any of {@link #getX()}, {@link #getY()}, {@link #getW()} and {@link #getH()} is
	 * not zero</b>, and it is then read instead of {@link #getFace()}. That is how somebody marks a face
	 * the detector missed: the viewer's edit-persons mode draws a rectangle on the photograph and
	 * names it, and what arrives here is that rectangle.
	 * </p>
	 *
	 * <p>
	 * The frame is the one the client draws in and the one every {@link FaceInfo} is answered in
	 * (issue #142): normalised to <code>0..1</code> of the picture <em>upright</em> — the EXIF
	 * orientation of the file applied, {@link ImagePart#getOrientation()} not. The server turns it into
	 * the raw raster of the file, which is the one frame a {@link FaceTag} is ever stored in.
	 * </p>
	 *
	 * <p>
	 * A box that meets one of the answered faces by the overlap that makes two boxes the same face
	 * is that face's decision and carries that face's own box, so marking a face that was found
	 * after all is the very same thing as naming it. A box nothing meets becomes a tag of its own,
	 * which is answered as a {@link FaceInfo} without a cluster and without a crop, and which a
	 * {@link FaceState#UNDECIDED} on the same box takes away again.
	 * </p>
	 *
	 * <p>
	 * A box outside <code>0..1</code>, or one with a width or a height that is not positive, is
	 * refused: a face is somewhere on the photograph or it is nowhere.
	 * </p>
	 */
	public final double getX() {
		return _x;
	}

	/**
	 * @see #getX()
	 */
	public de.haumacher.imageServer.shared.model.FaceAssignment setX(double value) {
		internalSetX(value);
		return this;
	}

	/** Internal setter for {@link #getX()} without chain call utility. */
	protected final void internalSetX(double value) {
		_x = value;
	}

	/**
	 * The top edge of a hand-marked face, as a fraction of the height, see {@link #getX()}.
	 */
	public final double getY() {
		return _y;
	}

	/**
	 * @see #getY()
	 */
	public de.haumacher.imageServer.shared.model.FaceAssignment setY(double value) {
		internalSetY(value);
		return this;
	}

	/** Internal setter for {@link #getY()} without chain call utility. */
	protected final void internalSetY(double value) {
		_y = value;
	}

	/**
	 * The width of a hand-marked face, as a fraction of the width, see {@link #getX()}.
	 */
	public final double getW() {
		return _w;
	}

	/**
	 * @see #getW()
	 */
	public de.haumacher.imageServer.shared.model.FaceAssignment setW(double value) {
		internalSetW(value);
		return this;
	}

	/** Internal setter for {@link #getW()} without chain call utility. */
	protected final void internalSetW(double value) {
		_w = value;
	}

	/**
	 * The height of a hand-marked face, as a fraction of the height, see {@link #getX()}.
	 */
	public final double getH() {
		return _h;
	}

	/**
	 * @see #getH()
	 */
	public de.haumacher.imageServer.shared.model.FaceAssignment setH(double value) {
		internalSetH(value);
		return this;
	}

	/** Internal setter for {@link #getH()} without chain call utility. */
	protected final void internalSetH(double value) {
		_h = value;
	}

	/**
	 * The {@link Person#getId()} the decision is about; empty exactly for {@link FaceState#NOT_A_FACE}.
	 */
	public final String getPerson() {
		return _person;
	}

	/**
	 * @see #getPerson()
	 */
	public de.haumacher.imageServer.shared.model.FaceAssignment setPerson(String value) {
		internalSetPerson(value);
		return this;
	}

	/** Internal setter for {@link #getPerson()} without chain call utility. */
	protected final void internalSetPerson(String value) {
		_person = value;
	}

	/**
	 * What is decided; {@link FaceState#UNDECIDED} takes the decision on this box back (issue #138).
	 *
	 * <p>
	 * Forgetting is idempotent: an <code>UNDECIDED</code> for a box that carries no tag changes
	 * nothing and is no error. The {@link #getPerson()} is ignored for it &mdash; what is forgotten is
	 * the decision, whoever it was about.
	 * </p>
	 */
	public final de.haumacher.imageServer.shared.model.FaceState getState() {
		return _state;
	}

	/**
	 * @see #getState()
	 */
	public de.haumacher.imageServer.shared.model.FaceAssignment setState(de.haumacher.imageServer.shared.model.FaceState value) {
		internalSetState(value);
		return this;
	}

	/** Internal setter for {@link #getState()} without chain call utility. */
	protected final void internalSetState(de.haumacher.imageServer.shared.model.FaceState value) {
		if (value == null) throw new IllegalArgumentException("Property 'state' cannot be null.");
		_state = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.FaceAssignment readFaceAssignment(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.FaceAssignment result = new de.haumacher.imageServer.shared.model.FaceAssignment();
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
		out.name(IMAGE__PROP);
		out.value(getImage());
		out.name(FACE__PROP);
		out.value(getFace());
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
			case IMAGE__PROP: setImage(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case FACE__PROP: setFace(in.nextInt()); break;
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
