package de.haumacher.imageServer.shared.model;

/**
 * The photograph carries the given label, compared exactly, see {@link ImagePart#getLabels()}.
 */
public class SearchLabel extends SearchCriterion {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.SearchLabel} instance.
	 */
	public static de.haumacher.imageServer.shared.model.SearchLabel create() {
		return new de.haumacher.imageServer.shared.model.SearchLabel();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.SearchLabel} type in JSON format. */
	public static final String SEARCH_LABEL__TYPE = "SearchLabel";

	/** @see #getLabel() */
	private static final String LABEL__PROP = "label";

	private String _label = "";

	/**
	 * Creates a {@link SearchLabel} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.SearchLabel#create()
	 */
	protected SearchLabel() {
		super();
	}

	@Override
	public TypeKind kind() {
		return TypeKind.SEARCH_LABEL;
	}

	/**
	 * The label.
	 */
	public final String getLabel() {
		return _label;
	}

	/**
	 * @see #getLabel()
	 */
	public de.haumacher.imageServer.shared.model.SearchLabel setLabel(String value) {
		internalSetLabel(value);
		return this;
	}

	/** Internal setter for {@link #getLabel()} without chain call utility. */
	protected final void internalSetLabel(String value) {
		_label = value;
	}

	@Override
	public String jsonType() {
		return SEARCH_LABEL__TYPE;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.SearchLabel readSearchLabel(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.SearchLabel result = new de.haumacher.imageServer.shared.model.SearchLabel();
		result.readContent(in);
		return result;
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name(LABEL__PROP);
		out.value(getLabel());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case LABEL__PROP: setLabel(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

	@Override
	public <R,A,E extends Throwable> R visit(de.haumacher.imageServer.shared.model.SearchCriterion.Visitor<R,A,E> v, A arg) throws E {
		return v.visit(this, arg);
	}

}
