package de.haumacher.imageServer.shared.model;

/**
 * What <code>?action=link-person</code> asks for: that a person of the register <em>is</em> a
 * member of the space, see issue #128.
 *
 * <p>
 * The link is stored on the person ({@link Person#getUser()}) and nowhere else, so there is one place
 * to write and one to read; {@link UserEntry#getPerson()} is the same link answered from the other end.
 * </p>
 *
 * <p>
 * An administrator links anybody to anybody. A member who may edit links a person to
 * <em>themselves</em> and to nobody else: saying "this is me" is a statement about oneself, and
 * saying "this is Anna" about somebody else's account is not.
 * </p>
 */
public class PersonLink extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.PersonLink} instance.
	 */
	public static de.haumacher.imageServer.shared.model.PersonLink create() {
		return new de.haumacher.imageServer.shared.model.PersonLink();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.PersonLink} type in JSON format. */
	public static final String PERSON_LINK__TYPE = "PersonLink";

	/** @see #getId() */
	private static final String ID__PROP = "id";

	/** @see #getUser() */
	private static final String USER__PROP = "user";

	private String _id = "";

	private String _user = "";

	/**
	 * Creates a {@link PersonLink} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.PersonLink#create()
	 */
	protected PersonLink() {
		super();
	}

	/**
	 * The {@link Person#getId()} to link; an alias of a person names that person.
	 */
	public final String getId() {
		return _id;
	}

	/**
	 * @see #getId()
	 */
	public de.haumacher.imageServer.shared.model.PersonLink setId(String value) {
		internalSetId(value);
		return this;
	}

	/** Internal setter for {@link #getId()} without chain call utility. */
	protected final void internalSetId(String value) {
		_id = value;
	}

	/**
	 * The {@link UserEntry#getName()} of the member this person is; the empty string unlinks.
	 *
	 * <p>
	 * One member is at most one person: linking a member that another person already claims is
	 * refused, and so is merging two people who are both linked &mdash; whoever wants that says
	 * first which of the two links is the wrong one.
	 * </p>
	 */
	public final String getUser() {
		return _user;
	}

	/**
	 * @see #getUser()
	 */
	public de.haumacher.imageServer.shared.model.PersonLink setUser(String value) {
		internalSetUser(value);
		return this;
	}

	/** Internal setter for {@link #getUser()} without chain call utility. */
	protected final void internalSetUser(String value) {
		_user = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.PersonLink readPersonLink(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.PersonLink result = new de.haumacher.imageServer.shared.model.PersonLink();
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
		out.name(ID__PROP);
		out.value(getId());
		out.name(USER__PROP);
		out.value(getUser());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case ID__PROP: setId(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case USER__PROP: setUser(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
