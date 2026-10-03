package de.haumacher.imageServer.shared.model;

/**
 * Asking for a code that proves an e-mail address, <code>&lt;data&gt;/?action=prove-email</code> (issue #199).
 *
 * <p>
 * Sent on a personal share link. With a recipient's own link that was opened before, the address is
 * one the space saved with that contact and the request names only which: the masked form
 * {@link IdentifyRequired#getAddresses()} showed, or its position there in {@link #getChoice()}; a typed
 * address is refused. On an open personal link, and for a contact who is already recognised and
 * adds an address, it is any address. The answer is an {@link EmailProofSent}, the same whether the
 * address is known to the space or not.
 * </p>
 */
public class EmailProof extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.EmailProof} instance.
	 */
	public static de.haumacher.imageServer.shared.model.EmailProof create() {
		return new de.haumacher.imageServer.shared.model.EmailProof();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.EmailProof} type in JSON format. */
	public static final String EMAIL_PROOF__TYPE = "EmailProof";

	/** @see #getAddress() */
	private static final String ADDRESS__PROP = "address";

	/** @see #getChoice() */
	private static final String CHOICE__PROP = "choice";

	private String _address = "";

	private int _choice = 0;

	/**
	 * Creates a {@link EmailProof} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.EmailProof#create()
	 */
	protected EmailProof() {
		super();
	}

	/**
	 * The address, or on a recipient's own link the masked form of one of the contact's addresses.
	 */
	public final String getAddress() {
		return _address;
	}

	/**
	 * @see #getAddress()
	 */
	public de.haumacher.imageServer.shared.model.EmailProof setAddress(String value) {
		internalSetAddress(value);
		return this;
	}

	/** Internal setter for {@link #getAddress()} without chain call utility. */
	protected final void internalSetAddress(String value) {
		_address = value;
	}

	/**
	 * On a recipient's own link, the position of the address in {@link IdentifyRequired#getAddresses()},
	 * counted from <code>1</code>; <code>0</code> where {@link #getAddress()} names it.
	 */
	public final int getChoice() {
		return _choice;
	}

	/**
	 * @see #getChoice()
	 */
	public de.haumacher.imageServer.shared.model.EmailProof setChoice(int value) {
		internalSetChoice(value);
		return this;
	}

	/** Internal setter for {@link #getChoice()} without chain call utility. */
	protected final void internalSetChoice(int value) {
		_choice = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.EmailProof readEmailProof(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.EmailProof result = new de.haumacher.imageServer.shared.model.EmailProof();
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
		out.name(ADDRESS__PROP);
		out.value(getAddress());
		out.name(CHOICE__PROP);
		out.value(getChoice());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case ADDRESS__PROP: setAddress(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case CHOICE__PROP: setChoice(in.nextInt()); break;
			default: super.readField(in, field);
		}
	}

}
