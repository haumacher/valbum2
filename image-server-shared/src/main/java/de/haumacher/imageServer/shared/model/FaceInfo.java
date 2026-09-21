package de.haumacher.imageServer.shared.model;

/**
 * One face found in an {@link ImagePart}, see issue #124.
 */
public class FaceInfo extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.FaceInfo} instance.
	 */
	public static de.haumacher.imageServer.shared.model.FaceInfo create() {
		return new de.haumacher.imageServer.shared.model.FaceInfo();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.FaceInfo} type in JSON format. */
	public static final String FACE_INFO__TYPE = "FaceInfo";

	/** @see #getIndex() */
	private static final String INDEX__PROP = "index";

	/** @see #getX() */
	private static final String X__PROP = "x";

	/** @see #getY() */
	private static final String Y__PROP = "y";

	/** @see #getW() */
	private static final String W__PROP = "w";

	/** @see #getH() */
	private static final String H__PROP = "h";

	/** @see #getCluster() */
	private static final String CLUSTER__PROP = "cluster";

	/** @see #getPerson() */
	private static final String PERSON__PROP = "person";

	/** @see #isConfirmed() */
	private static final String CONFIRMED__PROP = "confirmed";

	/** @see #getState() */
	private static final String STATE__PROP = "state";

	private int _index = 0;

	private double _x = 0.0d;

	private double _y = 0.0d;

	private double _w = 0.0d;

	private double _h = 0.0d;

	private String _cluster = "";

	private String _person = "";

	private boolean _confirmed = false;

	private de.haumacher.imageServer.shared.model.FaceState _state = de.haumacher.imageServer.shared.model.FaceState.UNDECIDED;

	/**
	 * Creates a {@link FaceInfo} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.FaceInfo#create()
	 */
	protected FaceInfo() {
		super();
	}

	/**
	 * The position of this face among the faces of its image, counting from zero.
	 *
	 * <p>
	 * What <code>?type=face&amp;face=&lt;index&gt;</code> asks the crop of. It is the position in
	 * {@link ImagePart#getFaces()} as the server found them and is stable while the image and the model
	 * are; a re-detection may renumber them, which is why it is never a name.
	 * </p>
	 */
	public final int getIndex() {
		return _index;
	}

	/**
	 * @see #getIndex()
	 */
	public de.haumacher.imageServer.shared.model.FaceInfo setIndex(int value) {
		internalSetIndex(value);
		return this;
	}

	/** Internal setter for {@link #getIndex()} without chain call utility. */
	protected final void internalSetIndex(int value) {
		_index = value;
	}

	/**
	 * The left edge of the face, as a fraction of the image width.
	 *
	 * <p>
	 * The box is normalised to <code>0..1</code> in the <em>raw raster of the file</em> — the pixels
	 * as they are stored, before the EXIF orientation and before
	 * {@link ImagePart#getOrientation()}. So a rotation the user applies changes nothing stored, and a
	 * client draws the box by applying to it the very transform it applies to the picture.
	 * </p>
	 */
	public final double getX() {
		return _x;
	}

	/**
	 * @see #getX()
	 */
	public de.haumacher.imageServer.shared.model.FaceInfo setX(double value) {
		internalSetX(value);
		return this;
	}

	/** Internal setter for {@link #getX()} without chain call utility. */
	protected final void internalSetX(double value) {
		_x = value;
	}

	/**
	 * The top edge of the face, as a fraction of the image height, see {@link #getX()}.
	 */
	public final double getY() {
		return _y;
	}

	/**
	 * @see #getY()
	 */
	public de.haumacher.imageServer.shared.model.FaceInfo setY(double value) {
		internalSetY(value);
		return this;
	}

	/** Internal setter for {@link #getY()} without chain call utility. */
	protected final void internalSetY(double value) {
		_y = value;
	}

	/**
	 * The width of the face, as a fraction of the image width, see {@link #getX()}.
	 */
	public final double getW() {
		return _w;
	}

	/**
	 * @see #getW()
	 */
	public de.haumacher.imageServer.shared.model.FaceInfo setW(double value) {
		internalSetW(value);
		return this;
	}

	/** Internal setter for {@link #getW()} without chain call utility. */
	protected final void internalSetW(double value) {
		_w = value;
	}

	/**
	 * The height of the face, as a fraction of the image height, see {@link #getX()}.
	 */
	public final double getH() {
		return _h;
	}

	/**
	 * @see #getH()
	 */
	public de.haumacher.imageServer.shared.model.FaceInfo setH(double value) {
		internalSetH(value);
		return this;
	}

	/** Internal setter for {@link #getH()} without chain call utility. */
	protected final void internalSetH(double value) {
		_h = value;
	}

	/**
	 * Which group of faces of this album the server believes this face belongs to.
	 *
	 * <p>
	 * An identifier of the album's clustering, not of a person: it says &quot;these faces are most
	 * likely the same person&quot; and nothing about who that is. It is stable only as far as the
	 * set of faces of the album is; naming a person is issue #125. Empty while the album is not
	 * clustered yet.
	 * </p>
	 */
	public final String getCluster() {
		return _cluster;
	}

	/**
	 * @see #getCluster()
	 */
	public de.haumacher.imageServer.shared.model.FaceInfo setCluster(String value) {
		internalSetCluster(value);
		return this;
	}

	/** Internal setter for {@link #getCluster()} without chain call utility. */
	protected final void internalSetCluster(String value) {
		_cluster = value;
	}

	/**
	 * The person this face is, <code>id</code> of a {@link Person} of the space (issue #125).
	 *
	 * <p>
	 * Derived on every read from the {@link ImagePart#getTags()} of the photograph: a detection whose
	 * box overlaps a stored tag is answered that tag's person. Empty when nobody said anything
	 * about this face, and empty for a {@link FaceState#NOT_A_FACE} tag, which is about nobody.
	 * </p>
	 *
	 * <p>
	 * Always the <em>surviving</em> person: a tag naming a person that was merged into another one
	 * is answered with the one it was merged into, so that a merge never has to rewrite an album,
	 * see {@link Person#getId()}.
	 * </p>
	 *
	 * <p>
	 * A suggestion is not a person: what a recogniser believes is issue #127 and will be a field of
	 * its own. This one is somebody's decision and nothing else.
	 * </p>
	 */
	public final String getPerson() {
		return _person;
	}

	/**
	 * @see #getPerson()
	 */
	public de.haumacher.imageServer.shared.model.FaceInfo setPerson(String value) {
		internalSetPerson(value);
		return this;
	}

	/** Internal setter for {@link #getPerson()} without chain call utility. */
	protected final void internalSetPerson(String value) {
		_person = value;
	}

	/**
	 * Whether {@link #getPerson()} is a confirmation rather than anything else, see {@link #getState()}.
	 *
	 * <p>
	 * The plain question the application asks most often &mdash; &quot;may I write this name under
	 * this face?&quot; &mdash; answered without reading the state: <code>true</code> exactly for
	 * {@link FaceState#CONFIRMED}.
	 * </p>
	 */
	public final boolean isConfirmed() {
		return _confirmed;
	}

	/**
	 * @see #isConfirmed()
	 */
	public de.haumacher.imageServer.shared.model.FaceInfo setConfirmed(boolean value) {
		internalSetConfirmed(value);
		return this;
	}

	/** Internal setter for {@link #isConfirmed()} without chain call utility. */
	protected final void internalSetConfirmed(boolean value) {
		_confirmed = value;
	}

	/**
	 * What was decided about this face, {@link FaceState#UNDECIDED} while nothing was (issue #125).
	 *
	 * <p>
	 * {@link FaceState#REJECTED} says that this face is <em>not</em> {@link #getPerson()} &mdash; the
	 * person stands in the answer so that the application can go on hiding the suggestion and issue
	 * #127 never offers it again. {@link FaceState#NOT_A_FACE} says that there is no face here at
	 * all, so the application hides the box.
	 * </p>
	 */
	public final de.haumacher.imageServer.shared.model.FaceState getState() {
		return _state;
	}

	/**
	 * @see #getState()
	 */
	public de.haumacher.imageServer.shared.model.FaceInfo setState(de.haumacher.imageServer.shared.model.FaceState value) {
		internalSetState(value);
		return this;
	}

	/** Internal setter for {@link #getState()} without chain call utility. */
	protected final void internalSetState(de.haumacher.imageServer.shared.model.FaceState value) {
		if (value == null) throw new IllegalArgumentException("Property 'state' cannot be null.");
		_state = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.FaceInfo readFaceInfo(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.FaceInfo result = new de.haumacher.imageServer.shared.model.FaceInfo();
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
		out.name(INDEX__PROP);
		out.value(getIndex());
		out.name(X__PROP);
		out.value(getX());
		out.name(Y__PROP);
		out.value(getY());
		out.name(W__PROP);
		out.value(getW());
		out.name(H__PROP);
		out.value(getH());
		out.name(CLUSTER__PROP);
		out.value(getCluster());
		out.name(PERSON__PROP);
		out.value(getPerson());
		out.name(CONFIRMED__PROP);
		out.value(isConfirmed());
		out.name(STATE__PROP);
		getState().writeTo(out);
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case INDEX__PROP: setIndex(in.nextInt()); break;
			case X__PROP: setX(in.nextDouble()); break;
			case Y__PROP: setY(in.nextDouble()); break;
			case W__PROP: setW(in.nextDouble()); break;
			case H__PROP: setH(in.nextDouble()); break;
			case CLUSTER__PROP: setCluster(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case PERSON__PROP: setPerson(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case CONFIRMED__PROP: setConfirmed(in.nextBoolean()); break;
			case STATE__PROP: setState(de.haumacher.imageServer.shared.model.FaceState.readFaceState(in)); break;
			default: super.readField(in, field);
		}
	}

}
