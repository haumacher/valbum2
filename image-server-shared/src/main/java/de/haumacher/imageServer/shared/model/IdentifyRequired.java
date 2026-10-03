package de.haumacher.imageServer.shared.model;

/**
 * The refusal of a personal link to a caller the server does not recognise (issue #198).
 *
 * <p>
 * Carried by the {@link ErrorInfo#getIdentify()} of the <code>401</code> answered on every endpoint,
 * <code>?type=auth</code> included, and never with the album. {@link #isFirstOpen()} says that the presented token is a recipient's own link
 * that was never opened: <code>?action=identify</code> accepts it. Otherwise the caller proves an
 * address by one of the {@link #getMethods()} (a mailed code, issue #199); where there is none, what is
 * left is "ask the sharer to send the link again".
 * </p>
 */
public class IdentifyRequired extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.IdentifyRequired} instance.
	 */
	public static de.haumacher.imageServer.shared.model.IdentifyRequired create() {
		return new de.haumacher.imageServer.shared.model.IdentifyRequired();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.IdentifyRequired} type in JSON format. */
	public static final String IDENTIFY_REQUIRED__TYPE = "IdentifyRequired";

	/** @see #isFirstOpen() */
	private static final String FIRST_OPEN__PROP = "firstOpen";

	/** @see #getContact() */
	private static final String CONTACT__PROP = "contact";

	/** @see #getAddresses() */
	private static final String ADDRESSES__PROP = "addresses";

	/** @see #getMethods() */
	private static final String METHODS__PROP = "methods";

	/** @see #getLabel() */
	private static final String LABEL__PROP = "label";

	/** @see #getSharedBy() */
	private static final String SHARED_BY__PROP = "sharedBy";

	private boolean _firstOpen = false;

	private de.haumacher.imageServer.shared.model.ContactInfo _contact = null;

	private final java.util.List<de.haumacher.imageServer.shared.model.MaskedAddress> _addresses = new java.util.ArrayList<>();

	private final java.util.List<de.haumacher.imageServer.shared.model.ProofMethod> _methods = new java.util.ArrayList<>();

	private String _label = "";

	private String _sharedBy = "";

	/**
	 * Creates a {@link IdentifyRequired} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.IdentifyRequired#create()
	 */
	protected IdentifyRequired() {
		super();
	}

	/**
	 * Whether the token is a recipient's own link that has not been opened yet.
	 */
	public final boolean isFirstOpen() {
		return _firstOpen;
	}

	/**
	 * @see #isFirstOpen()
	 */
	public de.haumacher.imageServer.shared.model.IdentifyRequired setFirstOpen(boolean value) {
		internalSetFirstOpen(value);
		return this;
	}

	/** Internal setter for {@link #isFirstOpen()} without chain call utility. */
	protected final void internalSetFirstOpen(boolean value) {
		_firstOpen = value;
	}

	/**
	 * Whose own link the token is, <code>null</code> for the link's own token.
	 */
	public final de.haumacher.imageServer.shared.model.ContactInfo getContact() {
		return _contact;
	}

	/**
	 * @see #getContact()
	 */
	public de.haumacher.imageServer.shared.model.IdentifyRequired setContact(de.haumacher.imageServer.shared.model.ContactInfo value) {
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
	 * The contact's addresses, masked (<code>p•••@gmx.de</code>); empty for a first open.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.MaskedAddress> getAddresses() {
		return _addresses;
	}

	/**
	 * @see #getAddresses()
	 */
	public de.haumacher.imageServer.shared.model.IdentifyRequired setAddresses(java.util.List<? extends de.haumacher.imageServer.shared.model.MaskedAddress> value) {
		internalSetAddresses(value);
		return this;
	}

	/** Internal setter for {@link #getAddresses()} without chain call utility. */
	protected final void internalSetAddresses(java.util.List<? extends de.haumacher.imageServer.shared.model.MaskedAddress> value) {
		if (value == null) throw new IllegalArgumentException("Property 'addresses' cannot be null.");
		_addresses.clear();
		_addresses.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getAddresses()} list.
	 */
	public de.haumacher.imageServer.shared.model.IdentifyRequired addAddresse(de.haumacher.imageServer.shared.model.MaskedAddress value) {
		internalAddAddresse(value);
		return this;
	}

	/** Implementation of {@link #addAddresse(de.haumacher.imageServer.shared.model.MaskedAddress)} without chain call utility. */
	protected final void internalAddAddresse(de.haumacher.imageServer.shared.model.MaskedAddress value) {
		_addresses.add(value);
	}

	/**
	 * Removes a value from the {@link #getAddresses()} list.
	 */
	public final void removeAddresse(de.haumacher.imageServer.shared.model.MaskedAddress value) {
		_addresses.remove(value);
	}

	/**
	 * The ways the server can prove an address here (issue #199): <code>mail-code</code> where the
	 * server can mail a code and either the link is open or the contact has an e-mail address;
	 * empty for a first open, which needs no proof.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.ProofMethod> getMethods() {
		return _methods;
	}

	/**
	 * @see #getMethods()
	 */
	public de.haumacher.imageServer.shared.model.IdentifyRequired setMethods(java.util.List<? extends de.haumacher.imageServer.shared.model.ProofMethod> value) {
		internalSetMethods(value);
		return this;
	}

	/** Internal setter for {@link #getMethods()} without chain call utility. */
	protected final void internalSetMethods(java.util.List<? extends de.haumacher.imageServer.shared.model.ProofMethod> value) {
		if (value == null) throw new IllegalArgumentException("Property 'methods' cannot be null.");
		_methods.clear();
		_methods.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getMethods()} list.
	 */
	public de.haumacher.imageServer.shared.model.IdentifyRequired addMethod(de.haumacher.imageServer.shared.model.ProofMethod value) {
		internalAddMethod(value);
		return this;
	}

	/** Implementation of {@link #addMethod(de.haumacher.imageServer.shared.model.ProofMethod)} without chain call utility. */
	protected final void internalAddMethod(de.haumacher.imageServer.shared.model.ProofMethod value) {
		_methods.add(value);
	}

	/**
	 * Removes a value from the {@link #getMethods()} list.
	 */
	public final void removeMethod(de.haumacher.imageServer.shared.model.ProofMethod value) {
		_methods.remove(value);
	}

	/**
	 * The label of the link.
	 */
	public final String getLabel() {
		return _label;
	}

	/**
	 * @see #getLabel()
	 */
	public de.haumacher.imageServer.shared.model.IdentifyRequired setLabel(String value) {
		internalSetLabel(value);
		return this;
	}

	/** Internal setter for {@link #getLabel()} without chain call utility. */
	protected final void internalSetLabel(String value) {
		_label = value;
	}

	/**
	 * The member who shared the link, for "… will see your name with the photos you add".
	 */
	public final String getSharedBy() {
		return _sharedBy;
	}

	/**
	 * @see #getSharedBy()
	 */
	public de.haumacher.imageServer.shared.model.IdentifyRequired setSharedBy(String value) {
		internalSetSharedBy(value);
		return this;
	}

	/** Internal setter for {@link #getSharedBy()} without chain call utility. */
	protected final void internalSetSharedBy(String value) {
		_sharedBy = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.IdentifyRequired readIdentifyRequired(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.IdentifyRequired result = new de.haumacher.imageServer.shared.model.IdentifyRequired();
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
		out.name(FIRST_OPEN__PROP);
		out.value(isFirstOpen());
		if (hasContact()) {
			out.name(CONTACT__PROP);
			getContact().writeTo(out);
		}
		out.name(ADDRESSES__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.MaskedAddress x : getAddresses()) {
			x.writeTo(out);
		}
		out.endArray();
		out.name(METHODS__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.ProofMethod x : getMethods()) {
			x.writeTo(out);
		}
		out.endArray();
		out.name(LABEL__PROP);
		out.value(getLabel());
		out.name(SHARED_BY__PROP);
		out.value(getSharedBy());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case FIRST_OPEN__PROP: setFirstOpen(in.nextBoolean()); break;
			case CONTACT__PROP: setContact(de.haumacher.imageServer.shared.model.ContactInfo.readContactInfo(in)); break;
			case ADDRESSES__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addAddresse(de.haumacher.imageServer.shared.model.MaskedAddress.readMaskedAddress(in));
				}
				in.endArray();
			}
			break;
			case METHODS__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addMethod(de.haumacher.imageServer.shared.model.ProofMethod.readProofMethod(in));
				}
				in.endArray();
			}
			break;
			case LABEL__PROP: setLabel(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case SHARED_BY__PROP: setSharedBy(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
