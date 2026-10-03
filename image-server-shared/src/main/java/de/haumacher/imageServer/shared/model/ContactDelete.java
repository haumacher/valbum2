package de.haumacher.imageServer.shared.model;

/**
 * Deletes a contact from the space, <code>&lt;data&gt;/?action=delete-contact</code> (issue
 * #203).
 *
 * <p>
 * Deleting deletes: the contact's name, addresses and sessions go from the register, their own
 * links of every personal link become tokens nobody issued, and the links they were sent stay
 * closed to everybody else. What they uploaded keeps the name that was copied onto it at the
 * upload. Answered with the {@link Contact} as it was.
 * </p>
 */
public class ContactDelete extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.ContactDelete} instance.
	 */
	public static de.haumacher.imageServer.shared.model.ContactDelete create() {
		return new de.haumacher.imageServer.shared.model.ContactDelete();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.ContactDelete} type in JSON format. */
	public static final String CONTACT_DELETE__TYPE = "ContactDelete";

	/** @see #getContact() */
	private static final String CONTACT__PROP = "contact";

	private String _contact = "";

	/**
	 * Creates a {@link ContactDelete} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.ContactDelete#create()
	 */
	protected ContactDelete() {
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
	public de.haumacher.imageServer.shared.model.ContactDelete setContact(String value) {
		internalSetContact(value);
		return this;
	}

	/** Internal setter for {@link #getContact()} without chain call utility. */
	protected final void internalSetContact(String value) {
		_contact = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.ContactDelete readContactDelete(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.ContactDelete result = new de.haumacher.imageServer.shared.model.ContactDelete();
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
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case CONTACT__PROP: setContact(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
