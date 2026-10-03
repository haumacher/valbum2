package de.haumacher.imageServer.shared.model;

/**
 * A recipient of a personal share link, see {@link ShareLink#getRecipients()} and issue #198.
 *
 * <p>
 * In a request either an existing contact named by {@link #getContact()}, or a new one described by
 * {@link #getName()} and {@link #getAddresses()}; a new contact whose address the register already holds
 * <em>is</em> that contact. In an answer the contact as the register knows it, and what became of
 * the recipient's own link &mdash; never its token.
 * </p>
 */
public class ShareRecipient extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.ShareRecipient} instance.
	 */
	public static de.haumacher.imageServer.shared.model.ShareRecipient create() {
		return new de.haumacher.imageServer.shared.model.ShareRecipient();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.ShareRecipient} type in JSON format. */
	public static final String SHARE_RECIPIENT__TYPE = "ShareRecipient";

	/** @see #getContact() */
	private static final String CONTACT__PROP = "contact";

	/** @see #getName() */
	private static final String NAME__PROP = "name";

	/** @see #getAddresses() */
	private static final String ADDRESSES__PROP = "addresses";

	/** @see #getIssued() */
	private static final String ISSUED__PROP = "issued";

	/** @see #getOpened() */
	private static final String OPENED__PROP = "opened";

	/** @see #getShutOut() */
	private static final String SHUT_OUT__PROP = "shutOut";

	/** @see #getFirstOpened() */
	private static final String FIRST_OPENED__PROP = "firstOpened";

	/** @see #getLastSeen() */
	private static final String LAST_SEEN__PROP = "lastSeen";

	/** @see #getUploads() */
	private static final String UPLOADS__PROP = "uploads";

	private String _contact = "";

	private String _name = "";

	private final java.util.List<de.haumacher.imageServer.shared.model.ContactAddress> _addresses = new java.util.ArrayList<>();

	private String _issued = "";

	private String _opened = "";

	private String _shutOut = "";

	private String _firstOpened = "";

	private String _lastSeen = "";

	private int _uploads = 0;

	/**
	 * Creates a {@link ShareRecipient} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.ShareRecipient#create()
	 */
	protected ShareRecipient() {
		super();
	}

	/**
	 * The id of the contact; empty in a request describing a new one.
	 */
	public final String getContact() {
		return _contact;
	}

	/**
	 * @see #getContact()
	 */
	public de.haumacher.imageServer.shared.model.ShareRecipient setContact(String value) {
		internalSetContact(value);
		return this;
	}

	/** Internal setter for {@link #getContact()} without chain call utility. */
	protected final void internalSetContact(String value) {
		_contact = value;
	}

	/**
	 * The name the space gives the contact; a new contact without one is named by its first address.
	 */
	public final String getName() {
		return _name;
	}

	/**
	 * @see #getName()
	 */
	public de.haumacher.imageServer.shared.model.ShareRecipient setName(String value) {
		internalSetName(value);
		return this;
	}

	/** Internal setter for {@link #getName()} without chain call utility. */
	protected final void internalSetName(String value) {
		_name = value;
	}

	/**
	 * The contact's addresses: what a new contact is created with, and what it holds.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.ContactAddress> getAddresses() {
		return _addresses;
	}

	/**
	 * @see #getAddresses()
	 */
	public de.haumacher.imageServer.shared.model.ShareRecipient setAddresses(java.util.List<? extends de.haumacher.imageServer.shared.model.ContactAddress> value) {
		internalSetAddresses(value);
		return this;
	}

	/** Internal setter for {@link #getAddresses()} without chain call utility. */
	protected final void internalSetAddresses(java.util.List<? extends de.haumacher.imageServer.shared.model.ContactAddress> value) {
		if (value == null) throw new IllegalArgumentException("Property 'addresses' cannot be null.");
		_addresses.clear();
		_addresses.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getAddresses()} list.
	 */
	public de.haumacher.imageServer.shared.model.ShareRecipient addAddresse(de.haumacher.imageServer.shared.model.ContactAddress value) {
		internalAddAddresse(value);
		return this;
	}

	/** Implementation of {@link #addAddresse(de.haumacher.imageServer.shared.model.ContactAddress)} without chain call utility. */
	protected final void internalAddAddresse(de.haumacher.imageServer.shared.model.ContactAddress value) {
		_addresses.add(value);
	}

	/**
	 * Removes a value from the {@link #getAddresses()} list.
	 */
	public final void removeAddresse(de.haumacher.imageServer.shared.model.ContactAddress value) {
		_addresses.remove(value);
	}

	/**
	 * When the recipient's current link was issued, an ISO-8601 instant; answered by the server.
	 */
	public final String getIssued() {
		return _issued;
	}

	/**
	 * @see #getIssued()
	 */
	public de.haumacher.imageServer.shared.model.ShareRecipient setIssued(String value) {
		internalSetIssued(value);
		return this;
	}

	/** Internal setter for {@link #getIssued()} without chain call utility. */
	protected final void internalSetIssued(String value) {
		_issued = value;
	}

	/**
	 * When the recipient's current link was first opened, empty while it was not; answered by the server.
	 */
	public final String getOpened() {
		return _opened;
	}

	/**
	 * @see #getOpened()
	 */
	public de.haumacher.imageServer.shared.model.ShareRecipient setOpened(String value) {
		internalSetOpened(value);
		return this;
	}

	/** Internal setter for {@link #getOpened()} without chain call utility. */
	protected final void internalSetOpened(String value) {
		_opened = value;
	}

	/**
	 * When the contact was shut out of this link, empty while they are not; answered by the server.
	 */
	public final String getShutOut() {
		return _shutOut;
	}

	/**
	 * @see #getShutOut()
	 */
	public de.haumacher.imageServer.shared.model.ShareRecipient setShutOut(String value) {
		internalSetShutOut(value);
		return this;
	}

	/** Internal setter for {@link #getShutOut()} without chain call utility. */
	protected final void internalSetShutOut(String value) {
		_shutOut = value;
	}

	/**
	 * When the recipient first came in through this link, an ISO-8601 instant; empty while they
	 * never did. Unlike {@link #getOpened()} it is kept when the link is sent again (issue #203).
	 */
	public final String getFirstOpened() {
		return _firstOpened;
	}

	/**
	 * @see #getFirstOpened()
	 */
	public de.haumacher.imageServer.shared.model.ShareRecipient setFirstOpened(String value) {
		internalSetFirstOpened(value);
		return this;
	}

	/** Internal setter for {@link #getFirstOpened()} without chain call utility. */
	protected final void internalSetFirstOpened(String value) {
		_firstOpened = value;
	}

	/**
	 * When the recipient last came in through this link, to the hour; empty while they never did (issue #203).
	 */
	public final String getLastSeen() {
		return _lastSeen;
	}

	/**
	 * @see #getLastSeen()
	 */
	public de.haumacher.imageServer.shared.model.ShareRecipient setLastSeen(String value) {
		internalSetLastSeen(value);
		return this;
	}

	/** Internal setter for {@link #getLastSeen()} without chain call utility. */
	protected final void internalSetLastSeen(String value) {
		_lastSeen = value;
	}

	/**
	 * How many photographs the recipient added through this link that the space still holds, see
	 * {@link LinkVisitor#getUploads()} and issue #203.
	 */
	public final int getUploads() {
		return _uploads;
	}

	/**
	 * @see #getUploads()
	 */
	public de.haumacher.imageServer.shared.model.ShareRecipient setUploads(int value) {
		internalSetUploads(value);
		return this;
	}

	/** Internal setter for {@link #getUploads()} without chain call utility. */
	protected final void internalSetUploads(int value) {
		_uploads = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.ShareRecipient readShareRecipient(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.ShareRecipient result = new de.haumacher.imageServer.shared.model.ShareRecipient();
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
		out.name(ADDRESSES__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.ContactAddress x : getAddresses()) {
			x.writeTo(out);
		}
		out.endArray();
		out.name(ISSUED__PROP);
		out.value(getIssued());
		out.name(OPENED__PROP);
		out.value(getOpened());
		out.name(SHUT_OUT__PROP);
		out.value(getShutOut());
		out.name(FIRST_OPENED__PROP);
		out.value(getFirstOpened());
		out.name(LAST_SEEN__PROP);
		out.value(getLastSeen());
		out.name(UPLOADS__PROP);
		out.value(getUploads());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case CONTACT__PROP: setContact(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case NAME__PROP: setName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case ADDRESSES__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addAddresse(de.haumacher.imageServer.shared.model.ContactAddress.readContactAddress(in));
				}
				in.endArray();
			}
			break;
			case ISSUED__PROP: setIssued(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case OPENED__PROP: setOpened(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case SHUT_OUT__PROP: setShutOut(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case FIRST_OPENED__PROP: setFirstOpened(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case LAST_SEEN__PROP: setLastSeen(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case UPLOADS__PROP: setUploads(in.nextInt()); break;
			default: super.readField(in, field);
		}
	}

}
