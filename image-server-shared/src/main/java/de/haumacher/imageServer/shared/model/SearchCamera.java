package de.haumacher.imageServer.shared.model;

/**
 * The photograph was taken with the given camera, compared exactly, see {@link ImagePart#getCamera()}.
 */
public class SearchCamera extends SearchCriterion {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.SearchCamera} instance.
	 */
	public static de.haumacher.imageServer.shared.model.SearchCamera create() {
		return new de.haumacher.imageServer.shared.model.SearchCamera();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.SearchCamera} type in JSON format. */
	public static final String SEARCH_CAMERA__TYPE = "SearchCamera";

	/** @see #getCamera() */
	private static final String CAMERA__PROP = "camera";

	private String _camera = "";

	/**
	 * Creates a {@link SearchCamera} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.SearchCamera#create()
	 */
	protected SearchCamera() {
		super();
	}

	@Override
	public TypeKind kind() {
		return TypeKind.SEARCH_CAMERA;
	}

	/**
	 * The camera label; the empty label matches nothing.
	 */
	public final String getCamera() {
		return _camera;
	}

	/**
	 * @see #getCamera()
	 */
	public de.haumacher.imageServer.shared.model.SearchCamera setCamera(String value) {
		internalSetCamera(value);
		return this;
	}

	/** Internal setter for {@link #getCamera()} without chain call utility. */
	protected final void internalSetCamera(String value) {
		_camera = value;
	}

	@Override
	public String jsonType() {
		return SEARCH_CAMERA__TYPE;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.SearchCamera readSearchCamera(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.SearchCamera result = new de.haumacher.imageServer.shared.model.SearchCamera();
		result.readContent(in);
		return result;
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name(CAMERA__PROP);
		out.value(getCamera());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case CAMERA__PROP: setCamera(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

	@Override
	public <R,A,E extends Throwable> R visit(de.haumacher.imageServer.shared.model.SearchCriterion.Visitor<R,A,E> v, A arg) throws E {
		return v.visit(this, arg);
	}

}
