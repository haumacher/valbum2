package de.haumacher.imageServer.shared.model;

/**
 * The link of one recipient of a personal share link, answered exactly once (issue #198).
 */
public class RecipientLink extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.RecipientLink} instance.
	 */
	public static de.haumacher.imageServer.shared.model.RecipientLink create() {
		return new de.haumacher.imageServer.shared.model.RecipientLink();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.RecipientLink} type in JSON format. */
	public static final String RECIPIENT_LINK__TYPE = "RecipientLink";

	/** @see #getContact() */
	private static final String CONTACT__PROP = "contact";

	/** @see #getName() */
	private static final String NAME__PROP = "name";

	/** @see #getAddresses() */
	private static final String ADDRESSES__PROP = "addresses";

	/** @see #getToken() */
	private static final String TOKEN__PROP = "token";

	/** @see #getUrl() */
	private static final String URL__PROP = "url";

	private String _contact = "";

	private String _name = "";

	private final java.util.List<de.haumacher.imageServer.shared.model.ContactAddress> _addresses = new java.util.ArrayList<>();

	private String _token = "";

	private String _url = "";

	/**
	 * Creates a {@link RecipientLink} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.RecipientLink#create()
	 */
	protected RecipientLink() {
		super();
	}

	/**
	 * The id of the contact the link is for.
	 */
	public final String getContact() {
		return _contact;
	}

	/**
	 * @see #getContact()
	 */
	public de.haumacher.imageServer.shared.model.RecipientLink setContact(String value) {
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
	public de.haumacher.imageServer.shared.model.RecipientLink setName(String value) {
		internalSetName(value);
		return this;
	}

	/** Internal setter for {@link #getName()} without chain call utility. */
	protected final void internalSetName(String value) {
		_name = value;
	}

	/**
	 * The contact's addresses, so that the app can offer a <code>mailto:</code> or a chat per address.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.ContactAddress> getAddresses() {
		return _addresses;
	}

	/**
	 * @see #getAddresses()
	 */
	public de.haumacher.imageServer.shared.model.RecipientLink setAddresses(java.util.List<? extends de.haumacher.imageServer.shared.model.ContactAddress> value) {
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
	public de.haumacher.imageServer.shared.model.RecipientLink addAddresse(de.haumacher.imageServer.shared.model.ContactAddress value) {
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
	 * The recipient's own token, answered exactly once and never stored.
	 */
	public final String getToken() {
		return _token;
	}

	/**
	 * @see #getToken()
	 */
	public de.haumacher.imageServer.shared.model.RecipientLink setToken(String value) {
		internalSetToken(value);
		return this;
	}

	/** Internal setter for {@link #getToken()} without chain call utility. */
	protected final void internalSetToken(String value) {
		_token = value;
	}

	/**
	 * The recipient's own link on this server: <code>&lt;context&gt;[/&lt;space&gt;]/s/&lt;token&gt;/</code>.
	 */
	public final String getUrl() {
		return _url;
	}

	/**
	 * @see #getUrl()
	 */
	public de.haumacher.imageServer.shared.model.RecipientLink setUrl(String value) {
		internalSetUrl(value);
		return this;
	}

	/** Internal setter for {@link #getUrl()} without chain call utility. */
	protected final void internalSetUrl(String value) {
		_url = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.RecipientLink readRecipientLink(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.RecipientLink result = new de.haumacher.imageServer.shared.model.RecipientLink();
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
		out.name(TOKEN__PROP);
		out.value(getToken());
		out.name(URL__PROP);
		out.value(getUrl());
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
			case TOKEN__PROP: setToken(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case URL__PROP: setUrl(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
