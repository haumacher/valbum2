package de.haumacher.imageServer.shared.model;

/**
 * One way to reach a contact, see {@link Contact#getAddresses()} and issue #198.
 */
public class ContactAddress extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.ContactAddress} instance.
	 */
	public static de.haumacher.imageServer.shared.model.ContactAddress create() {
		return new de.haumacher.imageServer.shared.model.ContactAddress();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.ContactAddress} type in JSON format. */
	public static final String CONTACT_ADDRESS__TYPE = "ContactAddress";

	/** @see #getKind() */
	private static final String KIND__PROP = "kind";

	/** @see #getValue() */
	private static final String VALUE__PROP = "value";

	/** @see #isProven() */
	private static final String PROVEN__PROP = "proven";

	private de.haumacher.imageServer.shared.model.AddressKind _kind = de.haumacher.imageServer.shared.model.AddressKind.EMAIL;

	private String _value = "";

	private boolean _proven = false;

	/**
	 * Creates a {@link ContactAddress} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.ContactAddress#create()
	 */
	protected ContactAddress() {
		super();
	}

	/**
	 * E-mail or phone.
	 */
	public final de.haumacher.imageServer.shared.model.AddressKind getKind() {
		return _kind;
	}

	/**
	 * @see #getKind()
	 */
	public de.haumacher.imageServer.shared.model.ContactAddress setKind(de.haumacher.imageServer.shared.model.AddressKind value) {
		internalSetKind(value);
		return this;
	}

	/** Internal setter for {@link #getKind()} without chain call utility. */
	protected final void internalSetKind(de.haumacher.imageServer.shared.model.AddressKind value) {
		if (value == null) throw new IllegalArgumentException("Property 'kind' cannot be null.");
		_kind = value;
	}

	/**
	 * The address, normalised by the server.
	 *
	 * <p>
	 * In a request an e-mail address may be written in the full form
	 * <code>Tante Petra &lt;petra@gmx.de&gt;</code>; the name then names a new contact that has none.
	 * </p>
	 */
	public final String getValue() {
		return _value;
	}

	/**
	 * @see #getValue()
	 */
	public de.haumacher.imageServer.shared.model.ContactAddress setValue(String value) {
		internalSetValue(value);
		return this;
	}

	/** Internal setter for {@link #getValue()} without chain call utility. */
	protected final void internalSetValue(String value) {
		_value = value;
	}

	/**
	 * Whether the contact proved the address (#199, #200), rather than the sharer giving it; answered by the server.
	 */
	public final boolean isProven() {
		return _proven;
	}

	/**
	 * @see #isProven()
	 */
	public de.haumacher.imageServer.shared.model.ContactAddress setProven(boolean value) {
		internalSetProven(value);
		return this;
	}

	/** Internal setter for {@link #isProven()} without chain call utility. */
	protected final void internalSetProven(boolean value) {
		_proven = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.ContactAddress readContactAddress(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.ContactAddress result = new de.haumacher.imageServer.shared.model.ContactAddress();
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
		out.name(KIND__PROP);
		getKind().writeTo(out);
		out.name(VALUE__PROP);
		out.value(getValue());
		out.name(PROVEN__PROP);
		out.value(isProven());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case KIND__PROP: setKind(de.haumacher.imageServer.shared.model.AddressKind.readAddressKind(in)); break;
			case VALUE__PROP: setValue(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case PROVEN__PROP: setProven(in.nextBoolean()); break;
			default: super.readField(in, field);
		}
	}

}
