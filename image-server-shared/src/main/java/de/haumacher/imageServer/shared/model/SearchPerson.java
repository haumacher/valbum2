package de.haumacher.imageServer.shared.model;

/**
 * The given person is in the photograph: somebody confirmed a face of the photograph as that
 * person (a {@link FaceTag} of {@link FaceState#CONFIRMED}), see issue #125.
 *
 * <p>
 * Compared through the merges of the register: a person merged into another one afterwards is
 * the one it was merged into, see {@link Person#getAliases()}.
 * </p>
 */
public class SearchPerson extends SearchCriterion {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.SearchPerson} instance.
	 */
	public static de.haumacher.imageServer.shared.model.SearchPerson create() {
		return new de.haumacher.imageServer.shared.model.SearchPerson();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.SearchPerson} type in JSON format. */
	public static final String SEARCH_PERSON__TYPE = "SearchPerson";

	/** @see #getPerson() */
	private static final String PERSON__PROP = "person";

	private String _person = "";

	/**
	 * Creates a {@link SearchPerson} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.SearchPerson#create()
	 */
	protected SearchPerson() {
		super();
	}

	@Override
	public TypeKind kind() {
		return TypeKind.SEARCH_PERSON;
	}

	/**
	 * The {@link Person#getId()}.
	 */
	public final String getPerson() {
		return _person;
	}

	/**
	 * @see #getPerson()
	 */
	public de.haumacher.imageServer.shared.model.SearchPerson setPerson(String value) {
		internalSetPerson(value);
		return this;
	}

	/** Internal setter for {@link #getPerson()} without chain call utility. */
	protected final void internalSetPerson(String value) {
		_person = value;
	}

	@Override
	public String jsonType() {
		return SEARCH_PERSON__TYPE;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.SearchPerson readSearchPerson(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.SearchPerson result = new de.haumacher.imageServer.shared.model.SearchPerson();
		result.readContent(in);
		return result;
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name(PERSON__PROP);
		out.value(getPerson());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case PERSON__PROP: setPerson(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

	@Override
	public <R,A,E extends Throwable> R visit(de.haumacher.imageServer.shared.model.SearchCriterion.Visitor<R,A,E> v, A arg) throws E {
		return v.visit(this, arg);
	}

}
