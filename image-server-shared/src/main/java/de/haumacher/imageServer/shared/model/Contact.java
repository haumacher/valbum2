package de.haumacher.imageServer.shared.model;

/**
 * A person behind a personal share link: a contact of the space, see issue #198.
 *
 * <p>
 * A contact is not a user: no role, no device, no clearance. What they may see and do is exactly
 * what the link they opened allows. Stored in <code>&lt;space&gt;/.valbum/contacts.json</code> and
 * answered by <code>&lt;data&gt;/?type=contacts</code> to every signed-in member of the space
 * &mdash; "when you share photos, you also share contacts" &mdash; and never to a link or an
 * anonymous caller.
 * </p>
 */
public class Contact extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.Contact} instance.
	 */
	public static de.haumacher.imageServer.shared.model.Contact create() {
		return new de.haumacher.imageServer.shared.model.Contact();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.Contact} type in JSON format. */
	public static final String CONTACT__TYPE = "Contact";

	/** @see #getId() */
	private static final String ID__PROP = "id";

	/** @see #getName() */
	private static final String NAME__PROP = "name";

	/** @see #getDisplayName() */
	private static final String DISPLAY_NAME__PROP = "displayName";

	/** @see #getAddresses() */
	private static final String ADDRESSES__PROP = "addresses";

	/** @see #getCreated() */
	private static final String CREATED__PROP = "created";

	/** @see #getCreatedBy() */
	private static final String CREATED_BY__PROP = "createdBy";

	/** @see #getFirstSeen() */
	private static final String FIRST_SEEN__PROP = "firstSeen";

	/** @see #getLastSeen() */
	private static final String LAST_SEEN__PROP = "lastSeen";

	/** @see #getBlocked() */
	private static final String BLOCKED__PROP = "blocked";

	/** @see #getSessions() */
	private static final String SESSIONS__PROP = "sessions";

	private String _id = "";

	private String _name = "";

	private String _displayName = "";

	private final java.util.List<de.haumacher.imageServer.shared.model.ContactAddress> _addresses = new java.util.ArrayList<>();

	private String _created = "";

	private String _createdBy = "";

	private String _firstSeen = "";

	private String _lastSeen = "";

	private String _blocked = "";

	private final java.util.List<de.haumacher.imageServer.shared.model.ContactSession> _sessions = new java.util.ArrayList<>();

	/**
	 * Creates a {@link Contact} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.Contact#create()
	 */
	protected Contact() {
		super();
	}

	/**
	 * The id of the contact; an upload of theirs is attributed to <code>contact:&lt;id&gt;</code>.
	 */
	public final String getId() {
		return _id;
	}

	/**
	 * @see #getId()
	 */
	public de.haumacher.imageServer.shared.model.Contact setId(String value) {
		internalSetId(value);
		return this;
	}

	/** Internal setter for {@link #getId()} without chain call utility. */
	protected final void internalSetId(String value) {
		_id = value;
	}

	/**
	 * The name the space gives the contact ("Tante Petra"); what their uploads are labelled with.
	 */
	public final String getName() {
		return _name;
	}

	/**
	 * @see #getName()
	 */
	public de.haumacher.imageServer.shared.model.Contact setName(String value) {
		internalSetName(value);
		return this;
	}

	/** Internal setter for {@link #getName()} without chain call utility. */
	protected final void internalSetName(String value) {
		_name = value;
	}

	/**
	 * The name the contact gave themselves at the first open, empty while they gave none.
	 */
	public final String getDisplayName() {
		return _displayName;
	}

	/**
	 * @see #getDisplayName()
	 */
	public de.haumacher.imageServer.shared.model.Contact setDisplayName(String value) {
		internalSetDisplayName(value);
		return this;
	}

	/** Internal setter for {@link #getDisplayName()} without chain call utility. */
	protected final void internalSetDisplayName(String value) {
		_displayName = value;
	}

	/**
	 * The contact's addresses, no two contacts sharing one.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.ContactAddress> getAddresses() {
		return _addresses;
	}

	/**
	 * @see #getAddresses()
	 */
	public de.haumacher.imageServer.shared.model.Contact setAddresses(java.util.List<? extends de.haumacher.imageServer.shared.model.ContactAddress> value) {
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
	public de.haumacher.imageServer.shared.model.Contact addAddresse(de.haumacher.imageServer.shared.model.ContactAddress value) {
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
	 * When the contact was entered, an ISO-8601 instant.
	 */
	public final String getCreated() {
		return _created;
	}

	/**
	 * @see #getCreated()
	 */
	public de.haumacher.imageServer.shared.model.Contact setCreated(String value) {
		internalSetCreated(value);
		return this;
	}

	/** Internal setter for {@link #getCreated()} without chain call utility. */
	protected final void internalSetCreated(String value) {
		_created = value;
	}

	/**
	 * The member who entered the contact; <code>link:&lt;share id&gt;</code> for the visitor of an
	 * open personal link who entered themselves by proving an address (issue #199).
	 */
	public final String getCreatedBy() {
		return _createdBy;
	}

	/**
	 * @see #getCreatedBy()
	 */
	public de.haumacher.imageServer.shared.model.Contact setCreatedBy(String value) {
		internalSetCreatedBy(value);
		return this;
	}

	/** Internal setter for {@link #getCreatedBy()} without chain call utility. */
	protected final void internalSetCreatedBy(String value) {
		_createdBy = value;
	}

	/**
	 * When the contact first opened a link, empty while they never did.
	 */
	public final String getFirstSeen() {
		return _firstSeen;
	}

	/**
	 * @see #getFirstSeen()
	 */
	public de.haumacher.imageServer.shared.model.Contact setFirstSeen(String value) {
		internalSetFirstSeen(value);
		return this;
	}

	/** Internal setter for {@link #getFirstSeen()} without chain call utility. */
	protected final void internalSetFirstSeen(String value) {
		_firstSeen = value;
	}

	/**
	 * When the contact was last seen, to the hour; empty while they never were.
	 */
	public final String getLastSeen() {
		return _lastSeen;
	}

	/**
	 * @see #getLastSeen()
	 */
	public de.haumacher.imageServer.shared.model.Contact setLastSeen(String value) {
		internalSetLastSeen(value);
		return this;
	}

	/** Internal setter for {@link #getLastSeen()} without chain call utility. */
	protected final void internalSetLastSeen(String value) {
		_lastSeen = value;
	}

	/**
	 * When the contact was shut out of every link of the space, empty while they are not.
	 */
	public final String getBlocked() {
		return _blocked;
	}

	/**
	 * @see #getBlocked()
	 */
	public de.haumacher.imageServer.shared.model.Contact setBlocked(String value) {
		internalSetBlocked(value);
		return this;
	}

	/** Internal setter for {@link #getBlocked()} without chain call utility. */
	protected final void internalSetBlocked(String value) {
		_blocked = value;
	}

	/**
	 * The browsers the contact is recognised on: their contact credentials, never a secret.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.ContactSession> getSessions() {
		return _sessions;
	}

	/**
	 * @see #getSessions()
	 */
	public de.haumacher.imageServer.shared.model.Contact setSessions(java.util.List<? extends de.haumacher.imageServer.shared.model.ContactSession> value) {
		internalSetSessions(value);
		return this;
	}

	/** Internal setter for {@link #getSessions()} without chain call utility. */
	protected final void internalSetSessions(java.util.List<? extends de.haumacher.imageServer.shared.model.ContactSession> value) {
		if (value == null) throw new IllegalArgumentException("Property 'sessions' cannot be null.");
		_sessions.clear();
		_sessions.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getSessions()} list.
	 */
	public de.haumacher.imageServer.shared.model.Contact addSession(de.haumacher.imageServer.shared.model.ContactSession value) {
		internalAddSession(value);
		return this;
	}

	/** Implementation of {@link #addSession(de.haumacher.imageServer.shared.model.ContactSession)} without chain call utility. */
	protected final void internalAddSession(de.haumacher.imageServer.shared.model.ContactSession value) {
		_sessions.add(value);
	}

	/**
	 * Removes a value from the {@link #getSessions()} list.
	 */
	public final void removeSession(de.haumacher.imageServer.shared.model.ContactSession value) {
		_sessions.remove(value);
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.Contact readContact(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.Contact result = new de.haumacher.imageServer.shared.model.Contact();
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
		out.name(NAME__PROP);
		out.value(getName());
		out.name(DISPLAY_NAME__PROP);
		out.value(getDisplayName());
		out.name(ADDRESSES__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.ContactAddress x : getAddresses()) {
			x.writeTo(out);
		}
		out.endArray();
		out.name(CREATED__PROP);
		out.value(getCreated());
		out.name(CREATED_BY__PROP);
		out.value(getCreatedBy());
		out.name(FIRST_SEEN__PROP);
		out.value(getFirstSeen());
		out.name(LAST_SEEN__PROP);
		out.value(getLastSeen());
		out.name(BLOCKED__PROP);
		out.value(getBlocked());
		out.name(SESSIONS__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.ContactSession x : getSessions()) {
			x.writeTo(out);
		}
		out.endArray();
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case ID__PROP: setId(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case NAME__PROP: setName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case DISPLAY_NAME__PROP: setDisplayName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case ADDRESSES__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addAddresse(de.haumacher.imageServer.shared.model.ContactAddress.readContactAddress(in));
				}
				in.endArray();
			}
			break;
			case CREATED__PROP: setCreated(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case CREATED_BY__PROP: setCreatedBy(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case FIRST_SEEN__PROP: setFirstSeen(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case LAST_SEEN__PROP: setLastSeen(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case BLOCKED__PROP: setBlocked(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case SESSIONS__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addSession(de.haumacher.imageServer.shared.model.ContactSession.readContactSession(in));
				}
				in.endArray();
			}
			break;
			default: super.readField(in, field);
		}
	}

}
