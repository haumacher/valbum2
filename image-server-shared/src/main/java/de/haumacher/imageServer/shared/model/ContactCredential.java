package de.haumacher.imageServer.shared.model;

/**
 * A contact credential, answered once by <code>?action=identify</code> (issue #198).
 *
 * <p>
 * Sent beside the link's token as the header <code>X-VAlbum-Contact: &lt;credential&gt;</code> on
 * every request of a personal link's session, and never as a bearer: it opens no endpoint by
 * itself, only the personal links of its space that admit the contact.
 * </p>
 */
public class ContactCredential extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.ContactCredential} instance.
	 */
	public static de.haumacher.imageServer.shared.model.ContactCredential create() {
		return new de.haumacher.imageServer.shared.model.ContactCredential();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.ContactCredential} type in JSON format. */
	public static final String CONTACT_CREDENTIAL__TYPE = "ContactCredential";

	/** @see #getCredential() */
	private static final String CREDENTIAL__PROP = "credential";

	/** @see #getExpires() */
	private static final String EXPIRES__PROP = "expires";

	/** @see #isRemember() */
	private static final String REMEMBER__PROP = "remember";

	/** @see #getContact() */
	private static final String CONTACT__PROP = "contact";

	/** @see #getMember() */
	private static final String MEMBER__PROP = "member";

	private String _credential = "";

	private String _expires = "";

	private boolean _remember = false;

	private de.haumacher.imageServer.shared.model.ContactInfo _contact = null;

	private de.haumacher.imageServer.shared.model.PairResponse _member = null;

	/**
	 * Creates a {@link ContactCredential} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.ContactCredential#create()
	 */
	protected ContactCredential() {
		super();
	}

	/**
	 * The credential, answered exactly once and stored only as a hash.
	 */
	public final String getCredential() {
		return _credential;
	}

	/**
	 * @see #getCredential()
	 */
	public de.haumacher.imageServer.shared.model.ContactCredential setCredential(String value) {
		internalSetCredential(value);
		return this;
	}

	/** Internal setter for {@link #getCredential()} without chain call utility. */
	protected final void internalSetCredential(String value) {
		_credential = value;
	}

	/**
	 * When it ends, an ISO-8601 instant; renewed on use where {@link #isRemember()} holds.
	 */
	public final String getExpires() {
		return _expires;
	}

	/**
	 * @see #getExpires()
	 */
	public de.haumacher.imageServer.shared.model.ContactCredential setExpires(String value) {
		internalSetExpires(value);
		return this;
	}

	/** Internal setter for {@link #getExpires()} without chain call utility. */
	protected final void internalSetExpires(String value) {
		_expires = value;
	}

	/**
	 * Whether it is remembered for 90 days.
	 */
	public final boolean isRemember() {
		return _remember;
	}

	/**
	 * @see #isRemember()
	 */
	public de.haumacher.imageServer.shared.model.ContactCredential setRemember(boolean value) {
		internalSetRemember(value);
		return this;
	}

	/** Internal setter for {@link #isRemember()} without chain call utility. */
	protected final void internalSetRemember(boolean value) {
		_remember = value;
	}

	/**
	 * The contact it identifies.
	 */
	public final de.haumacher.imageServer.shared.model.ContactInfo getContact() {
		return _contact;
	}

	/**
	 * @see #getContact()
	 */
	public de.haumacher.imageServer.shared.model.ContactCredential setContact(de.haumacher.imageServer.shared.model.ContactInfo value) {
		internalSetContact(value);
		return this;
	}

	/** Internal setter for {@link #getContact()} without chain call utility. */
	protected final void internalSetContact(de.haumacher.imageServer.shared.model.ContactInfo value) {
		_contact = value;
	}

	/**
	 * Checks, whether {@link #getContact()} has a value.
	 */
	public final boolean hasContact() {
		return _contact != null;
	}

	/**
	 * Set where the proof named a member rather than a contact (issue #233): the browser is signed
	 * in as that member &mdash; a new device, exactly as redeeming a code adds one &mdash; and
	 * {@link #getCredential()} is empty. One identity: a member's address, authenticator app or passkey
	 * never creates or identifies a contact, on any link. The app stores the token as a code
	 * redemption stores it and continues as the member.
	 */
	public final de.haumacher.imageServer.shared.model.PairResponse getMember() {
		return _member;
	}

	/**
	 * @see #getMember()
	 */
	public de.haumacher.imageServer.shared.model.ContactCredential setMember(de.haumacher.imageServer.shared.model.PairResponse value) {
		internalSetMember(value);
		return this;
	}

	/** Internal setter for {@link #getMember()} without chain call utility. */
	protected final void internalSetMember(de.haumacher.imageServer.shared.model.PairResponse value) {
		_member = value;
	}

	/**
	 * Checks, whether {@link #getMember()} has a value.
	 */
	public final boolean hasMember() {
		return _member != null;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.ContactCredential readContactCredential(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.ContactCredential result = new de.haumacher.imageServer.shared.model.ContactCredential();
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
		out.name(CREDENTIAL__PROP);
		out.value(getCredential());
		out.name(EXPIRES__PROP);
		out.value(getExpires());
		out.name(REMEMBER__PROP);
		out.value(isRemember());
		if (hasContact()) {
			out.name(CONTACT__PROP);
			getContact().writeTo(out);
		}
		if (hasMember()) {
			out.name(MEMBER__PROP);
			getMember().writeTo(out);
		}
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case CREDENTIAL__PROP: setCredential(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case EXPIRES__PROP: setExpires(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case REMEMBER__PROP: setRemember(in.nextBoolean()); break;
			case CONTACT__PROP: setContact(de.haumacher.imageServer.shared.model.ContactInfo.readContactInfo(in)); break;
			case MEMBER__PROP: setMember(de.haumacher.imageServer.shared.model.PairResponse.readPairResponse(in)); break;
			default: super.readField(in, field);
		}
	}

}
