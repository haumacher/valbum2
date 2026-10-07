package de.haumacher.imageServer.shared.model;

/**
 * The given text occurs, ignoring case, in the photograph's {@link ImagePart#getComment()} or in the
 * {@link AlbumInfo#getTitle()} or {@link AlbumInfo#getSubTitle()} of the album it lies in.
 */
public class SearchText extends SearchCriterion {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.SearchText} instance.
	 */
	public static de.haumacher.imageServer.shared.model.SearchText create() {
		return new de.haumacher.imageServer.shared.model.SearchText();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.SearchText} type in JSON format. */
	public static final String SEARCH_TEXT__TYPE = "SearchText";

	/** @see #getText() */
	private static final String TEXT__PROP = "text";

	private String _text = "";

	/**
	 * Creates a {@link SearchText} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.SearchText#create()
	 */
	protected SearchText() {
		super();
	}

	@Override
	public TypeKind kind() {
		return TypeKind.SEARCH_TEXT;
	}

	/**
	 * The text looked for; blanks around it are ignored, and an empty text matches everything.
	 */
	public final String getText() {
		return _text;
	}

	/**
	 * @see #getText()
	 */
	public de.haumacher.imageServer.shared.model.SearchText setText(String value) {
		internalSetText(value);
		return this;
	}

	/** Internal setter for {@link #getText()} without chain call utility. */
	protected final void internalSetText(String value) {
		_text = value;
	}

	@Override
	public String jsonType() {
		return SEARCH_TEXT__TYPE;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.SearchText readSearchText(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.SearchText result = new de.haumacher.imageServer.shared.model.SearchText();
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
