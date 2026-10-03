package de.haumacher.imageServer.shared.model;

/**
 * Ends a browser session of a contact, <code>&lt;data&gt;/?action=end-contact-session</code>
 * (issue #203).
 *
 * <p>
 * The credential of that session opens nothing from then on; the contact is asked who they are
 * the next time that browser opens a link. Answered with the {@link Contact}.
 * </p>
 */
public class ContactSessionEnd extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.ContactSessionEnd} instance.
	 */
	public static de.haumacher.imageServer.shared.model.ContactSessionEnd create() {
		return new de.haumacher.imageServer.shared.model.ContactSessionEnd();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.ContactSessionEnd} type in JSON format. */
	public static final String CONTACT_SESSION_END__TYPE = "ContactSessionEnd";

	/** @see #getContact() */
	private static final String CONTACT__PROP = "contact";

	/** @see #getSession() */
	private static final String SESSION__PROP = "session";

	private String _contact = "";

	private String _session = "";

	/**
	 * Creates a {@link ContactSessionEnd} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.ContactSessionEnd#create()
	 */
	protected ContactSessionEnd() {
		super();
	}

	/**
	 * The id of the contact.
	 */
	public final String getContact() {
		return _contact;
	}

	/**
	 * @see #getContact()
	 */
	public de.haumacher.imageServer.shared.model.ContactSessionEnd setContact(String value) {
		internalSetContact(value);
		return this;
	}

	/** Internal setter for {@link #getContact()} without chain call utility. */
	protected final void internalSetContact(String value) {
		_contact = value;
	}

	/**
	 * The id of the session, see {@link ContactSession#getId()}; empty ends every session of the contact.
	 */
	public final String getSession() {
		return _session;
	}

	/**
	 * @see #getSession()
	 */
	public de.haumacher.imageServer.shared.model.ContactSessionEnd setSession(String value) {
		internalSetSession(value);
		return this;
	}

	/** Internal setter for {@link #getSession()} without chain call utility. */
	protected final void internalSetSession(String value) {
		_session = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.ContactSessionEnd readContactSessionEnd(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.ContactSessionEnd result = new de.haumacher.imageServer.shared.model.ContactSessionEnd();
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
		out.name(CONTACT__PROP);
		out.value(getContact());
		out.name(SESSION__PROP);
		out.value(getSession());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case CONTACT__PROP: setContact(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case SESSION__PROP: setSession(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
