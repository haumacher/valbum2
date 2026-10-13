package de.haumacher.imageServer.shared.model;

/**
 * Taking one recipient off an addressed personal link, <code>&lt;folder&gt;/?action=remove-recipient</code>
 * (issue #269).
 *
 * <p>
 * The recipient's own link and its voided predecessors are from then on tokens this server never
 * issued, and their visits of the link are forgotten; the link stays addressed, the contact stays
 * a contact of the space, and what they uploaded keeps its attribution. Answered with the link as
 * {@link ShareLinkList} lists it.
 * </p>
 */
public class RecipientRemove extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.RecipientRemove} instance.
	 */
	public static de.haumacher.imageServer.shared.model.RecipientRemove create() {
		return new de.haumacher.imageServer.shared.model.RecipientRemove();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.RecipientRemove} type in JSON format. */
	public static final String RECIPIENT_REMOVE__TYPE = "RecipientRemove";

	/** @see #getLink() */
	private static final String LINK__PROP = "link";

	/** @see #getContact() */
	private static final String CONTACT__PROP = "contact";

	private String _link = "";

	private String _contact = "";

	/**
	 * Creates a {@link RecipientRemove} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.RecipientRemove#create()
	 */
	protected RecipientRemove() {
		super();
	}

	/**
	 * The id of the share link.
	 */
	public final String getLink() {
		return _link;
	}

	/**
	 * @see #getLink()
	 */
	public de.haumacher.imageServer.shared.model.RecipientRemove setLink(String value) {
		internalSetLink(value);
		return this;
	}

	/** Internal setter for {@link #getLink()} without chain call utility. */
	protected final void internalSetLink(String value) {
		_link = value;
	}

	/**
	 * The id of the contact to take off it; a recipient of the link.
	 */
	public final String getContact() {
		return _contact;
	}

	/**
	 * @see #getContact()
	 */
	public de.haumacher.imageServer.shared.model.RecipientRemove setContact(String value) {
		internalSetContact(value);
		return this;
	}

	/** Internal setter for {@link #getContact()} without chain call utility. */
	protected final void internalSetContact(String value) {
		_contact = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.RecipientRemove readRecipientRemove(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.RecipientRemove result = new de.haumacher.imageServer.shared.model.RecipientRemove();
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
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case LINK__PROP: setLink(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case CONTACT__PROP: setContact(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
