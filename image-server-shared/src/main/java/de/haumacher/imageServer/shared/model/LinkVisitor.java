package de.haumacher.imageServer.shared.model;

/**
 * A contact who came in through a personal link, see {@link ShareLink#getVisitors()} and issue #203.
 */
public class LinkVisitor extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.LinkVisitor} instance.
	 */
	public static de.haumacher.imageServer.shared.model.LinkVisitor create() {
		return new de.haumacher.imageServer.shared.model.LinkVisitor();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.LinkVisitor} type in JSON format. */
	public static final String LINK_VISITOR__TYPE = "LinkVisitor";

	/** @see #getContact() */
	private static final String CONTACT__PROP = "contact";

	/** @see #getName() */
	private static final String NAME__PROP = "name";

	/** @see #getFirstSeen() */
	private static final String FIRST_SEEN__PROP = "firstSeen";

	/** @see #getLastSeen() */
	private static final String LAST_SEEN__PROP = "lastSeen";

	/** @see #getUploads() */
	private static final String UPLOADS__PROP = "uploads";

	/** @see #getShutOut() */
	private static final String SHUT_OUT__PROP = "shutOut";

	private String _contact = "";

	private String _name = "";

	private String _firstSeen = "";

	private String _lastSeen = "";

	private int _uploads = 0;

	private String _shutOut = "";

	/**
	 * Creates a {@link LinkVisitor} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.LinkVisitor#create()
	 */
	protected LinkVisitor() {
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
	public de.haumacher.imageServer.shared.model.LinkVisitor setContact(String value) {
		internalSetContact(value);
		return this;
	}

	/** Internal setter for {@link #getContact()} without chain call utility. */
	protected final void internalSetContact(String value) {
		_contact = value;
	}

	/**
	 * The name the space gives the contact.
	 */
	public final String getName() {
		return _name;
	}

	/**
	 * @see #getName()
	 */
	public de.haumacher.imageServer.shared.model.LinkVisitor setName(String value) {
		internalSetName(value);
		return this;
	}

	/** Internal setter for {@link #getName()} without chain call utility. */
	protected final void internalSetName(String value) {
		_name = value;
	}

	/**
	 * When the contact first came in through the link, an ISO-8601 instant.
	 */
	public final String getFirstSeen() {
		return _firstSeen;
	}

	/**
	 * @see #getFirstSeen()
	 */
	public de.haumacher.imageServer.shared.model.LinkVisitor setFirstSeen(String value) {
		internalSetFirstSeen(value);
		return this;
	}

	/** Internal setter for {@link #getFirstSeen()} without chain call utility. */
	protected final void internalSetFirstSeen(String value) {
		_firstSeen = value;
	}

	/**
	 * When the contact last came in through the link, to the hour.
	 */
	public final String getLastSeen() {
		return _lastSeen;
	}

	/**
	 * @see #getLastSeen()
	 */
	public de.haumacher.imageServer.shared.model.LinkVisitor setLastSeen(String value) {
		internalSetLastSeen(value);
		return this;
	}

	/** Internal setter for {@link #getLastSeen()} without chain call utility. */
	protected final void internalSetLastSeen(String value) {
		_lastSeen = value;
	}

	/**
	 * How many photographs the contact added through the link that the space still holds: the
	 * entries of the <code>.hashes.json</code> sidecars attributed to the contact and recorded with
	 * this link.
	 */
	public final int getUploads() {
		return _uploads;
	}

	/**
	 * @see #getUploads()
	 */
	public de.haumacher.imageServer.shared.model.LinkVisitor setUploads(int value) {
		internalSetUploads(value);
		return this;
	}

	/** Internal setter for {@link #getUploads()} without chain call utility. */
	protected final void internalSetUploads(int value) {
		_uploads = value;
	}

	/**
	 * When the contact was shut out of this link, empty while they are not.
	 */
	public final String getShutOut() {
		return _shutOut;
	}

	/**
	 * @see #getShutOut()
	 */
	public de.haumacher.imageServer.shared.model.LinkVisitor setShutOut(String value) {
		internalSetShutOut(value);
		return this;
	}

	/** Internal setter for {@link #getShutOut()} without chain call utility. */
	protected final void internalSetShutOut(String value) {
		_shutOut = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.LinkVisitor readLinkVisitor(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.LinkVisitor result = new de.haumacher.imageServer.shared.model.LinkVisitor();
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
		out.name(FIRST_SEEN__PROP);
		out.value(getFirstSeen());
		out.name(LAST_SEEN__PROP);
		out.value(getLastSeen());
		out.name(UPLOADS__PROP);
		out.value(getUploads());
		out.name(SHUT_OUT__PROP);
		out.value(getShutOut());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case CONTACT__PROP: setContact(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case NAME__PROP: setName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case FIRST_SEEN__PROP: setFirstSeen(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case LAST_SEEN__PROP: setLastSeen(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case UPLOADS__PROP: setUploads(in.nextInt()); break;
			case SHUT_OUT__PROP: setShutOut(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
