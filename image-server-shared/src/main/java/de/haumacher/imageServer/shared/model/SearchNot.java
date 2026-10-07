package de.haumacher.imageServer.shared.model;

/**
 * The given condition does not hold.
 */
public class SearchNot extends SearchCriterion {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.SearchNot} instance.
	 */
	public static de.haumacher.imageServer.shared.model.SearchNot create() {
		return new de.haumacher.imageServer.shared.model.SearchNot();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.SearchNot} type in JSON format. */
	public static final String SEARCH_NOT__TYPE = "SearchNot";

	/** @see #getCriterion() */
	private static final String CRITERION__PROP = "criterion";

	private de.haumacher.imageServer.shared.model.SearchCriterion _criterion = null;

	/**
	 * Creates a {@link SearchNot} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.SearchNot#create()
	 */
	protected SearchNot() {
		super();
	}

	@Override
	public TypeKind kind() {
		return TypeKind.SEARCH_NOT;
	}

	/**
	 * The condition that must not hold; never absent, see {@link SearchQuery#getRoot()}.
	 */
	public final de.haumacher.imageServer.shared.model.SearchCriterion getCriterion() {
		return _criterion;
	}

	/**
	 * @see #getCriterion()
	 */
	public de.haumacher.imageServer.shared.model.SearchNot setCriterion(de.haumacher.imageServer.shared.model.SearchCriterion value) {
		internalSetCriterion(value);
		return this;
	}

	/** Internal setter for {@link #getCriterion()} without chain call utility. */
	protected final void internalSetCriterion(de.haumacher.imageServer.shared.model.SearchCriterion value) {
		_criterion = value;
	}

	/**
	 * Checks, whether {@link #getCriterion()} has a value.
	 */
	public final boolean hasCriterion() {
		return _criterion != null;
	}

	@Override
	public String jsonType() {
		return SEARCH_NOT__TYPE;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.SearchNot readSearchNot(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.SearchNot result = new de.haumacher.imageServer.shared.model.SearchNot();
		result.readContent(in);
		return result;
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		if (hasCriterion()) {
			out.name(CRITERION__PROP);
			getCriterion().writeTo(out);
		}
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case CRITERION__PROP: setCriterion(de.haumacher.imageServer.shared.model.SearchCriterion.readSearchCriterion(in)); break;
			default: super.readField(in, field);
		}
	}

	@Override
	public <R,A,E extends Throwable> R visit(de.haumacher.imageServer.shared.model.SearchCriterion.Visitor<R,A,E> v, A arg) throws E {
		return v.visit(this, arg);
	}

}
