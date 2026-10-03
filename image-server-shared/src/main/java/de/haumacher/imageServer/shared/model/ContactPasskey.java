package de.haumacher.imageServer.shared.model;

/**
 * A passkey of a contact, see issue #204: when it was made and last used, never a key.
 */
public class ContactPasskey extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.ContactPasskey} instance.
	 */
	public static de.haumacher.imageServer.shared.model.ContactPasskey create() {
		return new de.haumacher.imageServer.shared.model.ContactPasskey();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.ContactPasskey} type in JSON format. */
	public static final String CONTACT_PASSKEY__TYPE = "ContactPasskey";

	/** @see #getId() */
	private static final String ID__PROP = "id";

	/** @see #getCreated() */
	private static final String CREATED__PROP = "created";

	/** @see #getLastUsed() */
	private static final String LAST_USED__PROP = "lastUsed";

	private String _id = "";

	private String _created = "";

	private String _lastUsed = "";

	/**
	 * Creates a {@link ContactPasskey} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.ContactPasskey#create()
	 */
	protected ContactPasskey() {
		super();
	}

	/**
	 * The credential id, base64url; what <code>?action=remove-sign-in</code> names.
	 */
	public final String getId() {
		return _id;
	}

	/**
	 * @see #getId()
	 */
	public de.haumacher.imageServer.shared.model.ContactPasskey setId(String value) {
		internalSetId(value);
		return this;
	}

	/** Internal setter for {@link #getId()} without chain call utility. */
	protected final void internalSetId(String value) {
		_id = value;
	}

	/**
	 * When it was registered, an ISO-8601 instant.
	 */
	public final String getCreated() {
		return _created;
	}

	/**
	 * @see #getCreated()
	 */
	public de.haumacher.imageServer.shared.model.ContactPasskey setCreated(String value) {
		internalSetCreated(value);
		return this;
	}

	/** Internal setter for {@link #getCreated()} without chain call utility. */
	protected final void internalSetCreated(String value) {
		_created = value;
	}

	/**
	 * When it last signed in, empty while it never did.
	 */
	public final String getLastUsed() {
		return _lastUsed;
	}

	/**
	 * @see #getLastUsed()
	 */
	public de.haumacher.imageServer.shared.model.ContactPasskey setLastUsed(String value) {
		internalSetLastUsed(value);
		return this;
	}

	/** Internal setter for {@link #getLastUsed()} without chain call utility. */
	protected final void internalSetLastUsed(String value) {
		_lastUsed = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.ContactPasskey readContactPasskey(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.ContactPasskey result = new de.haumacher.imageServer.shared.model.ContactPasskey();
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
		out.name(CREATED__PROP);
		out.value(getCreated());
		out.name(LAST_USED__PROP);
		out.value(getLastUsed());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case ID__PROP: setId(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case CREATED__PROP: setCreated(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case LAST_USED__PROP: setLastUsed(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
