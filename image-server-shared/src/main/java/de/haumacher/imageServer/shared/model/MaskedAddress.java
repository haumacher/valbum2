package de.haumacher.imageServer.shared.model;

/**
 * A masked address of a contact, see {@link IdentifyRequired#getAddresses()}.
 */
public class MaskedAddress extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.MaskedAddress} instance.
	 */
	public static de.haumacher.imageServer.shared.model.MaskedAddress create() {
		return new de.haumacher.imageServer.shared.model.MaskedAddress();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.MaskedAddress} type in JSON format. */
	public static final String MASKED_ADDRESS__TYPE = "MaskedAddress";

	/** @see #getKind() */
	private static final String KIND__PROP = "kind";

	/** @see #getMasked() */
	private static final String MASKED__PROP = "masked";

	private de.haumacher.imageServer.shared.model.AddressKind _kind = de.haumacher.imageServer.shared.model.AddressKind.EMAIL;

	private String _masked = "";

	/**
	 * Creates a {@link MaskedAddress} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.MaskedAddress#create()
	 */
	protected MaskedAddress() {
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
	public de.haumacher.imageServer.shared.model.MaskedAddress setKind(de.haumacher.imageServer.shared.model.AddressKind value) {
		internalSetKind(value);
		return this;
	}

	/** Internal setter for {@link #getKind()} without chain call utility. */
	protected final void internalSetKind(de.haumacher.imageServer.shared.model.AddressKind value) {
		if (value == null) throw new IllegalArgumentException("Property 'kind' cannot be null.");
		_kind = value;
	}

	/**
	 * The address with most of it masked.
	 */
	public final String getMasked() {
		return _masked;
	}

	/**
	 * @see #getMasked()
	 */
	public de.haumacher.imageServer.shared.model.MaskedAddress setMasked(String value) {
		internalSetMasked(value);
		return this;
	}

	/** Internal setter for {@link #getMasked()} without chain call utility. */
	protected final void internalSetMasked(String value) {
		_masked = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.MaskedAddress readMaskedAddress(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.MaskedAddress result = new de.haumacher.imageServer.shared.model.MaskedAddress();
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
		out.name(MASKED__PROP);
		out.value(getMasked());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case KIND__PROP: setKind(de.haumacher.imageServer.shared.model.AddressKind.readAddressKind(in)); break;
			case MASKED__PROP: setMasked(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
