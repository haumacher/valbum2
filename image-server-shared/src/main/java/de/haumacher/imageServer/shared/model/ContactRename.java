package de.haumacher.imageServer.shared.model;

/**
 * Gives a contact another name in the space, <code>&lt;data&gt;/?action=rename-contact</code>
 * (issue #203).
 *
 * <p>
 * What their uploads are labelled with was copied at the upload and stays. Answered with the
 * {@link Contact}.
 * </p>
 */
public class ContactRename extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.ContactRename} instance.
	 */
	public static de.haumacher.imageServer.shared.model.ContactRename create() {
		return new de.haumacher.imageServer.shared.model.ContactRename();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.ContactRename} type in JSON format. */
	public static final String CONTACT_RENAME__TYPE = "ContactRename";

	/** @see #getContact() */
	private static final String CONTACT__PROP = "contact";

	/** @see #getName() */
	private static final String NAME__PROP = "name";

	private String _contact = "";

	private String _name = "";

	/**
	 * Creates a {@link ContactRename} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.ContactRename#create()
	 */
	protected ContactRename() {
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
	public de.haumacher.imageServer.shared.model.ContactRename setContact(String value) {
		internalSetContact(value);
		return this;
	}

	/** Internal setter for {@link #getContact()} without chain call utility. */
	protected final void internalSetContact(String value) {
		_contact = value;
	}

	/**
	 * The new name; a blank one is refused.
	 */
	public final String getName() {
		return _name;
	}

	/**
	 * @see #getName()
	 */
	public de.haumacher.imageServer.shared.model.ContactRename setName(String value) {
		internalSetName(value);
		return this;
	}

	/** Internal setter for {@link #getName()} without chain call utility. */
	protected final void internalSetName(String value) {
		_name = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.ContactRename readContactRename(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.ContactRename result = new de.haumacher.imageServer.shared.model.ContactRename();
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
		out.name(NAME__PROP);
		out.value(getName());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case CONTACT__PROP: setContact(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case NAME__PROP: setName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
