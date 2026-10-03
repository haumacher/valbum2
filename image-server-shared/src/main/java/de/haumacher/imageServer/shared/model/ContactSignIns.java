package de.haumacher.imageServer.shared.model;

/**
 * The ways a contact is recognised on another browser besides their link, as the contact sees
 * them: an authenticator app (issue #208) and passkeys (issue #204).
 *
 * <p>
 * Answered in {@link ShareInfo#getSignIns()} and by the requests that change them
 * (<code>?action=totp-confirm</code>, <code>?action=remove-sign-in</code>). Neither is ever the
 * default way in: the identification card offers each only to a contact who set it up.
 * </p>
 */
public class ContactSignIns extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.ContactSignIns} instance.
	 */
	public static de.haumacher.imageServer.shared.model.ContactSignIns create() {
		return new de.haumacher.imageServer.shared.model.ContactSignIns();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.ContactSignIns} type in JSON format. */
	public static final String CONTACT_SIGN_INS__TYPE = "ContactSignIns";

	/** @see #getAuthenticator() */
	private static final String AUTHENTICATOR__PROP = "authenticator";

	/** @see #getPasskeys() */
	private static final String PASSKEYS__PROP = "passkeys";

	/** @see #isPasskeysOffered() */
	private static final String PASSKEYS_OFFERED__PROP = "passkeysOffered";

	private String _authenticator = "";

	private final java.util.List<de.haumacher.imageServer.shared.model.ContactPasskey> _passkeys = new java.util.ArrayList<>();

	private boolean _passkeysOffered = false;

	/**
	 * Creates a {@link ContactSignIns} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.ContactSignIns#create()
	 */
	protected ContactSignIns() {
		super();
	}

	/**
	 * Since when an authenticator app signs the contact in, an ISO-8601 instant; empty while none does.
	 */
	public final String getAuthenticator() {
		return _authenticator;
	}

	/**
	 * @see #getAuthenticator()
	 */
	public de.haumacher.imageServer.shared.model.ContactSignIns setAuthenticator(String value) {
		internalSetAuthenticator(value);
		return this;
	}

	/** Internal setter for {@link #getAuthenticator()} without chain call utility. */
	protected final void internalSetAuthenticator(String value) {
		_authenticator = value;
	}

	/**
	 * The contact's passkeys (issue #204).
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.ContactPasskey> getPasskeys() {
		return _passkeys;
	}

	/**
	 * @see #getPasskeys()
	 */
	public de.haumacher.imageServer.shared.model.ContactSignIns setPasskeys(java.util.List<? extends de.haumacher.imageServer.shared.model.ContactPasskey> value) {
		internalSetPasskeys(value);
		return this;
	}

	/** Internal setter for {@link #getPasskeys()} without chain call utility. */
	protected final void internalSetPasskeys(java.util.List<? extends de.haumacher.imageServer.shared.model.ContactPasskey> value) {
		if (value == null) throw new IllegalArgumentException("Property 'passkeys' cannot be null.");
		_passkeys.clear();
		_passkeys.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getPasskeys()} list.
	 */
	public de.haumacher.imageServer.shared.model.ContactSignIns addPasskey(de.haumacher.imageServer.shared.model.ContactPasskey value) {
		internalAddPasskey(value);
		return this;
	}

	/** Implementation of {@link #addPasskey(de.haumacher.imageServer.shared.model.ContactPasskey)} without chain call utility. */
	protected final void internalAddPasskey(de.haumacher.imageServer.shared.model.ContactPasskey value) {
		_passkeys.add(value);
	}

	/**
	 * Removes a value from the {@link #getPasskeys()} list.
	 */
	public final void removePasskey(de.haumacher.imageServer.shared.model.ContactPasskey value) {
		_passkeys.remove(value);
	}

	/**
	 * Whether the server offers passkeys at all: only with a public address
	 * (<code>VALBUM_PUBLIC_URL</code>), whose host is the relying party (issue #204).
	 */
	public final boolean isPasskeysOffered() {
		return _passkeysOffered;
	}

	/**
	 * @see #isPasskeysOffered()
	 */
	public de.haumacher.imageServer.shared.model.ContactSignIns setPasskeysOffered(boolean value) {
		internalSetPasskeysOffered(value);
		return this;
	}

	/** Internal setter for {@link #isPasskeysOffered()} without chain call utility. */
	protected final void internalSetPasskeysOffered(boolean value) {
		_passkeysOffered = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.ContactSignIns readContactSignIns(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.ContactSignIns result = new de.haumacher.imageServer.shared.model.ContactSignIns();
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
		out.name(AUTHENTICATOR__PROP);
		out.value(getAuthenticator());
		out.name(PASSKEYS__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.ContactPasskey x : getPasskeys()) {
			x.writeTo(out);
		}
		out.endArray();
		out.name(PASSKEYS_OFFERED__PROP);
		out.value(isPasskeysOffered());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case AUTHENTICATOR__PROP: setAuthenticator(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case PASSKEYS__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addPasskey(de.haumacher.imageServer.shared.model.ContactPasskey.readContactPasskey(in));
				}
				in.endArray();
			}
			break;
			case PASSKEYS_OFFERED__PROP: setPasskeysOffered(in.nextBoolean()); break;
			default: super.readField(in, field);
		}
	}

}
