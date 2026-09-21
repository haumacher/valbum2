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

	/** @see #getPerson() */
	private static final String PERSON__PROP = "person";

	/** @see #getState() */
	private static final String STATE__PROP = "state";

	private String _image = "";

	private int _face = 0;

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
	 * never sends coordinates and can never invent a face that was not answered to it.
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
			case PERSON__PROP: setPerson(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case STATE__PROP: setState(de.haumacher.imageServer.shared.model.FaceState.readFaceState(in)); break;
			default: super.readField(in, field);
		}
	}

}
