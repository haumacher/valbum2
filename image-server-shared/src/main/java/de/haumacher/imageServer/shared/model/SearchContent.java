package de.haumacher.imageServer.shared.model;

/**
 * The photograph shows what the given text describes, see issue #257: the content search.
 *
 * <p>
 * Unlike every other criterion it <em>ranks</em>: the text is turned into a vector by the
 * embedder of the server, and the photographs are answered by how near their vectors are to it,
 * the nearest first ({@link ImagePart#getScore()}), those too far from it cut. It stands alone as the
 * {@link SearchQuery#getRoot()}, or once among the members of the root's {@link SearchAnd}, whose other
 * members narrow what is ranked; inside a {@link SearchOr} or a {@link SearchNot} it is refused.
 * A saved search stores the text, never a vector: a new model only embeds the text again.
 * </p>
 */
public class SearchContent extends SearchCriterion {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.SearchContent} instance.
	 */
	public static de.haumacher.imageServer.shared.model.SearchContent create() {
		return new de.haumacher.imageServer.shared.model.SearchContent();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.SearchContent} type in JSON format. */
	public static final String SEARCH_CONTENT__TYPE = "SearchContent";

	/** @see #getText() */
	private static final String TEXT__PROP = "text";

	private String _text = "";

	/**
	 * Creates a {@link SearchContent} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.SearchContent#create()
	 */
	protected SearchContent() {
		super();
	}

	@Override
	public TypeKind kind() {
		return TypeKind.SEARCH_CONTENT;
	}

	/**
	 * What the photographs show, in any language; blanks around it are ignored, and it is never empty.
	 */
	public final String getText() {
		return _text;
	}

	/**
	 * @see #getText()
	 */
	public de.haumacher.imageServer.shared.model.SearchContent setText(String value) {
		internalSetText(value);
		return this;
	}

	/** Internal setter for {@link #getText()} without chain call utility. */
	protected final void internalSetText(String value) {
		_text = value;
	}

	@Override
	public String jsonType() {
		return SEARCH_CONTENT__TYPE;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.SearchContent readSearchContent(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.SearchContent result = new de.haumacher.imageServer.shared.model.SearchContent();
		result.readContent(in);
		return result;
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name(TEXT__PROP);
		out.value(getText());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case TEXT__PROP: setText(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

	@Override
	public <R,A,E extends Throwable> R visit(de.haumacher.imageServer.shared.model.SearchCriterion.Visitor<R,A,E> v, A arg) throws E {
		return v.visit(this, arg);
	}

}
