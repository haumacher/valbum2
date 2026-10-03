package de.haumacher.imageServer.shared.model;

/**
 * A browser a contact is recognised on, see {@link Contact#getSessions()} and issue #198.
 */
public class ContactSession extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.ContactSession} instance.
	 */
	public static de.haumacher.imageServer.shared.model.ContactSession create() {
		return new de.haumacher.imageServer.shared.model.ContactSession();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.ContactSession} type in JSON format. */
	public static final String CONTACT_SESSION__TYPE = "ContactSession";

	/** @see #getId() */
	private static final String ID__PROP = "id";

	/** @see #getLink() */
	private static final String LINK__PROP = "link";

	/** @see #getCreated() */
	private static final String CREATED__PROP = "created";

	/** @see #getExpires() */
	private static final String EXPIRES__PROP = "expires";

	/** @see #isRemember() */
	private static final String REMEMBER__PROP = "remember";

	/** @see #getLastUsed() */
	private static final String LAST_USED__PROP = "lastUsed";

	private String _id = "";

	private String _link = "";

	private String _created = "";

	private String _expires = "";

	private boolean _remember = false;

	private String _lastUsed = "";

	/**
	 * Creates a {@link ContactSession} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.ContactSession#create()
	 */
	protected ContactSession() {
		super();
	}

	/**
	 * The id of the session.
	 */
	public final String getId() {
		return _id;
	}

	/**
	 * @see #getId()
	 */
	public de.haumacher.imageServer.shared.model.ContactSession setId(String value) {
		internalSetId(value);
		return this;
	}

	/** Internal setter for {@link #getId()} without chain call utility. */
	protected final void internalSetId(String value) {
		_id = value;
	}

	/**
	 * The id of the share link the session was opened through.
	 */
	public final String getLink() {
		return _link;
	}

	/**
	 * @see #getLink()
	 */
	public de.haumacher.imageServer.shared.model.ContactSession setLink(String value) {
		internalSetLink(value);
		return this;
	}

	/** Internal setter for {@link #getLink()} without chain call utility. */
	protected final void internalSetLink(String value) {
		_link = value;
	}

	/**
	 * When the session began, an ISO-8601 instant.
	 */
	public final String getCreated() {
		return _created;
	}

	/**
	 * @see #getCreated()
	 */
	public de.haumacher.imageServer.shared.model.ContactSession setCreated(String value) {
		internalSetCreated(value);
		return this;
	}

	/** Internal setter for {@link #getCreated()} without chain call utility. */
	protected final void internalSetCreated(String value) {
		_created = value;
	}

	/**
	 * When the session ends, an ISO-8601 instant.
	 */
	public final String getExpires() {
		return _expires;
	}

	/**
	 * @see #getExpires()
	 */
	public de.haumacher.imageServer.shared.model.ContactSession setExpires(String value) {
		internalSetExpires(value);
		return this;
	}

	/** Internal setter for {@link #getExpires()} without chain call utility. */
	protected final void internalSetExpires(String value) {
		_expires = value;
	}

	/**
	 * Whether the contact asked to be remembered: 90 days renewed on use, else 24 hours.
	 */
	public final boolean isRemember() {
		return _remember;
	}

	/**
	 * @see #isRemember()
	 */
	public de.haumacher.imageServer.shared.model.ContactSession setRemember(boolean value) {
		internalSetRemember(value);
		return this;
	}

	/** Internal setter for {@link #isRemember()} without chain call utility. */
	protected final void internalSetRemember(boolean value) {
		_remember = value;
	}

	/**
	 * When the session was last used, to the hour.
	 */
	public final String getLastUsed() {
		return _lastUsed;
	}

	/**
	 * @see #getLastUsed()
	 */
	public de.haumacher.imageServer.shared.model.ContactSession setLastUsed(String value) {
		internalSetLastUsed(value);
		return this;
	}

	/** Internal setter for {@link #getLastUsed()} without chain call utility. */
	protected final void internalSetLastUsed(String value) {
		_lastUsed = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.ContactSession readContactSession(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.ContactSession result = new de.haumacher.imageServer.shared.model.ContactSession();
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
		out.name(LINK__PROP);
		out.value(getLink());
		out.name(CREATED__PROP);
		out.value(getCreated());
		out.name(EXPIRES__PROP);
		out.value(getExpires());
		out.name(REMEMBER__PROP);
		out.value(isRemember());
		out.name(LAST_USED__PROP);
		out.value(getLastUsed());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case ID__PROP: setId(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case LINK__PROP: setLink(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case CREATED__PROP: setCreated(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case EXPIRES__PROP: setExpires(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case REMEMBER__PROP: setRemember(in.nextBoolean()); break;
			case LAST_USED__PROP: setLastUsed(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
