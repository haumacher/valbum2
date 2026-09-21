package de.haumacher.imageServer.shared.model;

/**
 * What <code>?action=tag-faces</code> asks for: decisions about the faces of one album.
 *
 * <p>
 * Every assignment is carried out on its own and the whole request is refused if any one of them
 * cannot be: nothing is written until every name, index and person in it is known, so an album is
 * never left half tagged.
 * </p>
 */
public class TagFaces extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.TagFaces} instance.
	 */
	public static de.haumacher.imageServer.shared.model.TagFaces create() {
		return new de.haumacher.imageServer.shared.model.TagFaces();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.TagFaces} type in JSON format. */
	public static final String TAG_FACES__TYPE = "TagFaces";

	/** @see #getFaces() */
	private static final String FACES__PROP = "faces";

	private final java.util.List<de.haumacher.imageServer.shared.model.FaceAssignment> _faces = new java.util.ArrayList<>();

	/**
	 * Creates a {@link TagFaces} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.TagFaces#create()
	 */
	protected TagFaces() {
		super();
	}

	/**
	 * The decisions to store.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.FaceAssignment> getFaces() {
		return _faces;
	}

	/**
	 * @see #getFaces()
	 */
	public de.haumacher.imageServer.shared.model.TagFaces setFaces(java.util.List<? extends de.haumacher.imageServer.shared.model.FaceAssignment> value) {
		internalSetFaces(value);
		return this;
	}

	/** Internal setter for {@link #getFaces()} without chain call utility. */
	protected final void internalSetFaces(java.util.List<? extends de.haumacher.imageServer.shared.model.FaceAssignment> value) {
		if (value == null) throw new IllegalArgumentException("Property 'faces' cannot be null.");
		_faces.clear();
		_faces.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getFaces()} list.
	 */
	public de.haumacher.imageServer.shared.model.TagFaces addFace(de.haumacher.imageServer.shared.model.FaceAssignment value) {
		internalAddFace(value);
		return this;
	}

	/** Implementation of {@link #addFace(de.haumacher.imageServer.shared.model.FaceAssignment)} without chain call utility. */
	protected final void internalAddFace(de.haumacher.imageServer.shared.model.FaceAssignment value) {
		_faces.add(value);
	}

	/**
	 * Removes a value from the {@link #getFaces()} list.
	 */
	public final void removeFace(de.haumacher.imageServer.shared.model.FaceAssignment value) {
		_faces.remove(value);
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.TagFaces readTagFaces(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.TagFaces result = new de.haumacher.imageServer.shared.model.TagFaces();
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
		out.name(FACES__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.FaceAssignment x : getFaces()) {
			x.writeTo(out);
		}
		out.endArray();
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case FACES__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addFace(de.haumacher.imageServer.shared.model.FaceAssignment.readFaceAssignment(in));
				}
				in.endArray();
			}
			break;
			default: super.readField(in, field);
		}
	}

}
