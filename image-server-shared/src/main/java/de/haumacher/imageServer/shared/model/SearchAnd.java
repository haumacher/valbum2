package de.haumacher.imageServer.shared.model;

/**
 * Every one of the given conditions holds; an empty list holds for every photograph.
 */
public class SearchAnd extends SearchCriterion {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.SearchAnd} instance.
	 */
	public static de.haumacher.imageServer.shared.model.SearchAnd create() {
		return new de.haumacher.imageServer.shared.model.SearchAnd();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.SearchAnd} type in JSON format. */
	public static final String SEARCH_AND__TYPE = "SearchAnd";

	/** @see #getCriteria() */
	private static final String CRITERIA__PROP = "criteria";

	private final java.util.List<de.haumacher.imageServer.shared.model.SearchCriterion> _criteria = new java.util.ArrayList<>();

	/**
	 * Creates a {@link SearchAnd} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.SearchAnd#create()
	 */
	protected SearchAnd() {
		super();
	}

	@Override
	public TypeKind kind() {
		return TypeKind.SEARCH_AND;
	}

	/**
	 * The conditions that must all hold.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.SearchCriterion> getCriteria() {
		return _criteria;
	}

	/**
	 * @see #getCriteria()
	 */
	public de.haumacher.imageServer.shared.model.SearchAnd setCriteria(java.util.List<? extends de.haumacher.imageServer.shared.model.SearchCriterion> value) {
		internalSetCriteria(value);
		return this;
	}

	/** Internal setter for {@link #getCriteria()} without chain call utility. */
	protected final void internalSetCriteria(java.util.List<? extends de.haumacher.imageServer.shared.model.SearchCriterion> value) {
		if (value == null) throw new IllegalArgumentException("Property 'criteria' cannot be null.");
		_criteria.clear();
		_criteria.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getCriteria()} list.
	 */
	public de.haumacher.imageServer.shared.model.SearchAnd addCriteria(de.haumacher.imageServer.shared.model.SearchCriterion value) {
		internalAddCriteria(value);
		return this;
	}

	/** Implementation of {@link #addCriteria(de.haumacher.imageServer.shared.model.SearchCriterion)} without chain call utility. */
	protected final void internalAddCriteria(de.haumacher.imageServer.shared.model.SearchCriterion value) {
		_criteria.add(value);
	}

	/**
	 * Removes a value from the {@link #getCriteria()} list.
	 */
	public final void removeCriteria(de.haumacher.imageServer.shared.model.SearchCriterion value) {
		_criteria.remove(value);
	}

	@Override
	public String jsonType() {
		return SEARCH_AND__TYPE;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.SearchAnd readSearchAnd(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.SearchAnd result = new de.haumacher.imageServer.shared.model.SearchAnd();
		result.readContent(in);
		return result;
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name(CRITERIA__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.SearchCriterion x : getCriteria()) {
			x.writeTo(out);
		}
		out.endArray();
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case CRITERIA__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addCriteria(de.haumacher.imageServer.shared.model.SearchCriterion.readSearchCriterion(in));
				}
				in.endArray();
			}
			break;
			default: super.readField(in, field);
		}
	}

	@Override
	public <R,A,E extends Throwable> R visit(de.haumacher.imageServer.shared.model.SearchCriterion.Visitor<R,A,E> v, A arg) throws E {
		return v.visit(this, arg);
	}

}
