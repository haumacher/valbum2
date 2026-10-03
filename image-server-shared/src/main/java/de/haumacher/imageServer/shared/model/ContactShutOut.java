package de.haumacher.imageServer.shared.model;

/**
 * Shutting a contact out of one link, <code>&lt;folder&gt;/?action=shut-out</code>, or out of the
 * whole space, <code>&lt;data&gt;/?action=block-contact</code> (issue #198).
 *
 * <p>
 * Either ends the matching contact credentials at once; <code>shutOut: false</code> lets the
 * contact in again (and issues nothing: a link to them is sent again with
 * <code>?action=resend</code>).
 * </p>
 */
public class ContactShutOut extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.ContactShutOut} instance.
	 */
	public static de.haumacher.imageServer.shared.model.ContactShutOut create() {
		return new de.haumacher.imageServer.shared.model.ContactShutOut();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.ContactShutOut} type in JSON format. */
	public static final String CONTACT_SHUT_OUT__TYPE = "ContactShutOut";

	/** @see #getLink() */
	private static final String LINK__PROP = "link";

	/** @see #getContact() */
	private static final String CONTACT__PROP = "contact";

	/** @see #isShutOut() */
	private static final String SHUT_OUT__PROP = "shutOut";

	private String _link = "";

	private String _contact = "";

	private boolean _shutOut = false;

	/**
	 * Creates a {@link ContactShutOut} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.ContactShutOut#create()
	 */
	protected ContactShutOut() {
		super();
	}

	/**
	 * The id of the share link; ignored by <code>?action=block-contact</code>.
	 */
	public final String getLink() {
		return _link;
	}

	/**
	 * @see #getLink()
	 */
	public de.haumacher.imageServer.shared.model.ContactShutOut setLink(String value) {
		internalSetLink(value);
		return this;
	}

	/** Internal setter for {@link #getLink()} without chain call utility. */
	protected final void internalSetLink(String value) {
		_link = value;
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
	public de.haumacher.imageServer.shared.model.ContactShutOut setContact(String value) {
		internalSetContact(value);
		return this;
	}

	/** Internal setter for {@link #getContact()} without chain call utility. */
	protected final void internalSetContact(String value) {
		_contact = value;
	}

	/**
	 * Whether the contact is shut out, or let in again.
	 */
	public final boolean isShutOut() {
		return _shutOut;
	}

	/**
	 * @see #isShutOut()
	 */
	public de.haumacher.imageServer.shared.model.ContactShutOut setShutOut(boolean value) {
		internalSetShutOut(value);
		return this;
	}

	/** Internal setter for {@link #isShutOut()} without chain call utility. */
	protected final void internalSetShutOut(boolean value) {
		_shutOut = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.ContactShutOut readContactShutOut(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.ContactShutOut result = new de.haumacher.imageServer.shared.model.ContactShutOut();
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
		out.name(LINK__PROP);
		out.value(getLink());
		out.name(CONTACT__PROP);
		out.value(getContact());
		out.name(SHUT_OUT__PROP);
		out.value(isShutOut());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case LINK__PROP: setLink(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case CONTACT__PROP: setContact(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case SHUT_OUT__PROP: setShutOut(in.nextBoolean()); break;
			default: super.readField(in, field);
		}
	}

}
