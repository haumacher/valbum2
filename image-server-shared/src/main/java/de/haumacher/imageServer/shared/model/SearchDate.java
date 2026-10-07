package de.haumacher.imageServer.shared.model;

/**
 * The photograph was taken in the given span of time: {@link ImagePart#getDate()} at or after
 * {@link #getFrom()} and before {@link #getTo()}. A photograph without a date never matches.
 */
public class SearchDate extends SearchCriterion {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.SearchDate} instance.
	 */
	public static de.haumacher.imageServer.shared.model.SearchDate create() {
		return new de.haumacher.imageServer.shared.model.SearchDate();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.SearchDate} type in JSON format. */
	public static final String SEARCH_DATE__TYPE = "SearchDate";

	/** @see #getFrom() */
	private static final String FROM__PROP = "from";

	/** @see #getTo() */
	private static final String TO__PROP = "to";

	private long _from = 0L;

	private long _to = 0L;

	/**
	 * Creates a {@link SearchDate} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.SearchDate#create()
	 */
	protected SearchDate() {
		super();
	}

	@Override
	public TypeKind kind() {
		return TypeKind.SEARCH_DATE;
	}

	/**
	 * The first instant, in milliseconds since the epoch; <code>0</code> for no lower bound.
	 */
	public final long getFrom() {
		return _from;
	}

	/**
	 * @see #getFrom()
	 */
	public de.haumacher.imageServer.shared.model.SearchDate setFrom(long value) {
		internalSetFrom(value);
		return this;
	}

	/** Internal setter for {@link #getFrom()} without chain call utility. */
	protected final void internalSetFrom(long value) {
		_from = value;
	}

	/**
	 * The first instant no longer in the span (exclusive); <code>0</code> for no upper bound.
	 */
	public final long getTo() {
		return _to;
	}

	/**
	 * @see #getTo()
	 */
	public de.haumacher.imageServer.shared.model.SearchDate setTo(long value) {
		internalSetTo(value);
		return this;
	}

	/** Internal setter for {@link #getTo()} without chain call utility. */
	protected final void internalSetTo(long value) {
		_to = value;
	}

	@Override
	public String jsonType() {
		return SEARCH_DATE__TYPE;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.SearchDate readSearchDate(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.SearchDate result = new de.haumacher.imageServer.shared.model.SearchDate();
		result.readContent(in);
		return result;
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name(FROM__PROP);
		out.value(getFrom());
		out.name(TO__PROP);
		out.value(getTo());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case FROM__PROP: setFrom(in.nextLong()); break;
			case TO__PROP: setTo(in.nextLong()); break;
			default: super.readField(in, field);
		}
	}

	@Override
	public <R,A,E extends Throwable> R visit(de.haumacher.imageServer.shared.model.SearchCriterion.Visitor<R,A,E> v, A arg) throws E {
		return v.visit(this, arg);
	}

}
