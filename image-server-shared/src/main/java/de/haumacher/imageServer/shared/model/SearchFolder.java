package de.haumacher.imageServer.shared.model;

/**
 * The photograph lies in the given folder or below it.
 *
 * <p>
 * Not offered by the app in this build: where a saved search lies already says where it looks.
 * Kept in the format for a later search over several folders.
 * </p>
 */
public class SearchFolder extends SearchCriterion {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.SearchFolder} instance.
	 */
	public static de.haumacher.imageServer.shared.model.SearchFolder create() {
		return new de.haumacher.imageServer.shared.model.SearchFolder();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.SearchFolder} type in JSON format. */
	public static final String SEARCH_FOLDER__TYPE = "SearchFolder";

	/** @see #getPath() */
	private static final String PATH__PROP = "path";

	private String _path = "";

	/**
	 * Creates a {@link SearchFolder} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.SearchFolder#create()
	 */
	protected SearchFolder() {
		super();
	}

	@Override
	public TypeKind kind() {
		return TypeKind.SEARCH_FOLDER;
	}

	/**
	 * The folder relative to the root of the space, <code>/</code>-separated; empty for the root.
	 */
	public final String getPath() {
		return _path;
	}

	/**
	 * @see #getPath()
	 */
	public de.haumacher.imageServer.shared.model.SearchFolder setPath(String value) {
		internalSetPath(value);
		return this;
	}

	/** Internal setter for {@link #getPath()} without chain call utility. */
	protected final void internalSetPath(String value) {
		_path = value;
	}

	@Override
	public String jsonType() {
		return SEARCH_FOLDER__TYPE;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.SearchFolder readSearchFolder(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.SearchFolder result = new de.haumacher.imageServer.shared.model.SearchFolder();
		result.readContent(in);
		return result;
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name(PATH__PROP);
		out.value(getPath());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case PATH__PROP: setPath(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

	@Override
	public <R,A,E extends Throwable> R visit(de.haumacher.imageServer.shared.model.SearchCriterion.Visitor<R,A,E> v, A arg) throws E {
		return v.visit(this, arg);
	}

}
