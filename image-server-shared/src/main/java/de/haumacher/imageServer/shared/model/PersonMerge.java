package de.haumacher.imageServer.shared.model;

/**
 * What <code>?action=merge-persons</code> asks for: two people who are one.
 *
 * <p>
 * The one that is kept keeps its id, its name and its cover; the other becomes an alias of it and
 * is gone from every listing. No album is rewritten, see {@link Person#getAliases()}.
 * </p>
 */
public class PersonMerge extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.PersonMerge} instance.
	 */
	public static de.haumacher.imageServer.shared.model.PersonMerge create() {
		return new de.haumacher.imageServer.shared.model.PersonMerge();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.PersonMerge} type in JSON format. */
	public static final String PERSON_MERGE__TYPE = "PersonMerge";

	/** @see #getInto() */
	private static final String INTO__PROP = "into";

	/** @see #getFrom() */
	private static final String FROM__PROP = "from";

	private String _into = "";

	private String _from = "";

	/**
	 * Creates a {@link PersonMerge} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.PersonMerge#create()
	 */
	protected PersonMerge() {
		super();
	}

	/**
	 * The {@link Person#getId()} that survives.
	 */
	public final String getInto() {
		return _into;
	}

	/**
	 * @see #getInto()
	 */
	public de.haumacher.imageServer.shared.model.PersonMerge setInto(String value) {
		internalSetInto(value);
		return this;
	}

	/** Internal setter for {@link #getInto()} without chain call utility. */
	protected final void internalSetInto(String value) {
		_into = value;
	}

	/**
	 * The {@link Person#getId()} that becomes an alias of {@link #getInto()}.
	 */
	public final String getFrom() {
		return _from;
	}

	/**
	 * @see #getFrom()
	 */
	public de.haumacher.imageServer.shared.model.PersonMerge setFrom(String value) {
		internalSetFrom(value);
		return this;
	}

	/** Internal setter for {@link #getFrom()} without chain call utility. */
	protected final void internalSetFrom(String value) {
		_from = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.PersonMerge readPersonMerge(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.PersonMerge result = new de.haumacher.imageServer.shared.model.PersonMerge();
		result.readContent(in);
		return result;
	}

	@Override
	public final void writeTo(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		writeContent(out);
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name(INTO__PROP);
		out.value(getInto());
		out.name(FROM__PROP);
		out.value(getFrom());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case INTO__PROP: setInto(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case FROM__PROP: setFrom(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
