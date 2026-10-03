package de.haumacher.imageServer.shared.model;

/**
 * Sending one recipient of a personal link a fresh link, <code>&lt;folder&gt;/?action=resend</code> (issue #198).
 *
 * <p>
 * The recipient's earlier link is void from then on, and the answer is a {@link ShareLinkCreated}
 * carrying the one fresh {@link RecipientLink}.
 * </p>
 */
public class ShareResend extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.ShareResend} instance.
	 */
	public static de.haumacher.imageServer.shared.model.ShareResend create() {
		return new de.haumacher.imageServer.shared.model.ShareResend();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.ShareResend} type in JSON format. */
	public static final String SHARE_RESEND__TYPE = "ShareResend";

	/** @see #getLink() */
	private static final String LINK__PROP = "link";

	/** @see #getContact() */
	private static final String CONTACT__PROP = "contact";

	private String _link = "";

	private String _contact = "";

	/**
	 * Creates a {@link ShareResend} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.ShareResend#create()
	 */
	protected ShareResend() {
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
	public de.haumacher.imageServer.shared.model.ShareResend setLink(String value) {
		internalSetLink(value);
		return this;
	}

	/** Internal setter for {@link #getLink()} without chain call utility. */
	protected final void internalSetLink(String value) {
		_link = value;
	}

	/**
	 * The id of the contact to send again to; a recipient of the link.
	 */
	public final String getContact() {
		return _contact;
	}

	/**
	 * @see #getContact()
	 */
	public de.haumacher.imageServer.shared.model.ShareResend setContact(String value) {
		internalSetContact(value);
		return this;
	}

	/** Internal setter for {@link #getContact()} without chain call utility. */
	protected final void internalSetContact(String value) {
		_contact = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.ShareResend readShareResend(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.ShareResend result = new de.haumacher.imageServer.shared.model.ShareResend();
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
