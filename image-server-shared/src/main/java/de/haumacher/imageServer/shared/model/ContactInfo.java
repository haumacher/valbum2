package de.haumacher.imageServer.shared.model;

/**
 * The contact a session of a personal link is, see {@link ShareInfo#getContact()} and issue #198.
 */
public class ContactInfo extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.ContactInfo} instance.
	 */
	public static de.haumacher.imageServer.shared.model.ContactInfo create() {
		return new de.haumacher.imageServer.shared.model.ContactInfo();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.ContactInfo} type in JSON format. */
	public static final String CONTACT_INFO__TYPE = "ContactInfo";

	/** @see #getId() */
	private static final String ID__PROP = "id";

	/** @see #getDisplayName() */
	private static final String DISPLAY_NAME__PROP = "displayName";

	private String _id = "";

	private String _displayName = "";

	/**
	 * Creates a {@link ContactInfo} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.ContactInfo#create()
	 */
	protected ContactInfo() {
		super();
	}

	/**
	 * The id of the contact in the space's register.
	 */
	public final String getId() {
		return _id;
	}

	/**
	 * @see #getId()
	 */
	public de.haumacher.imageServer.shared.model.ContactInfo setId(String value) {
		internalSetId(value);
		return this;
	}

	/** Internal setter for {@link #getId()} without chain call utility. */
	protected final void internalSetId(String value) {
		_id = value;
	}

	/**
	 * The name to greet the contact by: their own display name, else the name the space gives them.
	 *
	 * <p>
	 * The space's name is what the sharer wrote into the address field the link was sent with, so
	 * it is no secret to the recipient; the contact's own name replaces it once they gave one.
	 * </p>
	 */
	public final String getDisplayName() {
		return _displayName;
	}

	/**
	 * @see #getDisplayName()
	 */
	public de.haumacher.imageServer.shared.model.ContactInfo setDisplayName(String value) {
		internalSetDisplayName(value);
		return this;
	}

	/** Internal setter for {@link #getDisplayName()} without chain call utility. */
	protected final void internalSetDisplayName(String value) {
		_displayName = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.ContactInfo readContactInfo(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.ContactInfo result = new de.haumacher.imageServer.shared.model.ContactInfo();
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
		out.name(DISPLAY_NAME__PROP);
		out.value(getDisplayName());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case ID__PROP: setId(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case DISPLAY_NAME__PROP: setDisplayName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
