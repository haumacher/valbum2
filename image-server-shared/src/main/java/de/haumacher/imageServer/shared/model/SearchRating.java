package de.haumacher.imageServer.shared.model;

/**
 * The photograph is rated at least the given {@link ImagePart#getRating()}.
 */
public class SearchRating extends SearchCriterion {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.SearchRating} instance.
	 */
	public static de.haumacher.imageServer.shared.model.SearchRating create() {
		return new de.haumacher.imageServer.shared.model.SearchRating();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.SearchRating} type in JSON format. */
	public static final String SEARCH_RATING__TYPE = "SearchRating";

	/** @see #getMin() */
	private static final String MIN__PROP = "min";

	private int _min = 0;

	/**
	 * Creates a {@link SearchRating} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.SearchRating#create()
	 */
	protected SearchRating() {
		super();
	}

	@Override
	public TypeKind kind() {
		return TypeKind.SEARCH_RATING;
	}

	/**
	 * The lowest rating that matches, from <code>-2</code> to <code>2</code>.
	 */
	public final int getMin() {
		return _min;
	}

	/**
	 * @see #getMin()
	 */
	public de.haumacher.imageServer.shared.model.SearchRating setMin(int value) {
		internalSetMin(value);
		return this;
	}

	/** Internal setter for {@link #getMin()} without chain call utility. */
	protected final void internalSetMin(int value) {
		_min = value;
	}

	@Override
	public String jsonType() {
		return SEARCH_RATING__TYPE;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.SearchRating readSearchRating(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.SearchRating result = new de.haumacher.imageServer.shared.model.SearchRating();
		result.readContent(in);
		return result;
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name(MIN__PROP);
		out.value(getMin());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case MIN__PROP: setMin(in.nextInt()); break;
			default: super.readField(in, field);
		}
	}

	@Override
	public <R,A,E extends Throwable> R visit(de.haumacher.imageServer.shared.model.SearchCriterion.Visitor<R,A,E> v, A arg) throws E {
		return v.visit(this, arg);
	}

}
