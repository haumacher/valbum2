package de.haumacher.imageServer.shared.model;

/**
 * The register of contacts of a space, answered by <code>&lt;data&gt;/?type=contacts</code> (issue #198).
 */
public class ContactList extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.ContactList} instance.
	 */
	public static de.haumacher.imageServer.shared.model.ContactList create() {
		return new de.haumacher.imageServer.shared.model.ContactList();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.ContactList} type in JSON format. */
	public static final String CONTACT_LIST__TYPE = "ContactList";

	/** @see #getContacts() */
	private static final String CONTACTS__PROP = "contacts";

	private final java.util.List<de.haumacher.imageServer.shared.model.Contact> _contacts = new java.util.ArrayList<>();

	/**
	 * Creates a {@link ContactList} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.ContactList#create()
	 */
	protected ContactList() {
		super();
	}

	/**
	 * Every contact, in the order they were entered.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.Contact> getContacts() {
		return _contacts;
	}

	/**
	 * @see #getContacts()
	 */
	public de.haumacher.imageServer.shared.model.ContactList setContacts(java.util.List<? extends de.haumacher.imageServer.shared.model.Contact> value) {
		internalSetContacts(value);
		return this;
	}

	/** Internal setter for {@link #getContacts()} without chain call utility. */
	protected final void internalSetContacts(java.util.List<? extends de.haumacher.imageServer.shared.model.Contact> value) {
		if (value == null) throw new IllegalArgumentException("Property 'contacts' cannot be null.");
		_contacts.clear();
		_contacts.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getContacts()} list.
	 */
	public de.haumacher.imageServer.shared.model.ContactList addContact(de.haumacher.imageServer.shared.model.Contact value) {
		internalAddContact(value);
		return this;
	}

	/** Implementation of {@link #addContact(de.haumacher.imageServer.shared.model.Contact)} without chain call utility. */
	protected final void internalAddContact(de.haumacher.imageServer.shared.model.Contact value) {
		_contacts.add(value);
	}

	/**
	 * Removes a value from the {@link #getContacts()} list.
	 */
	public final void removeContact(de.haumacher.imageServer.shared.model.Contact value) {
		_contacts.remove(value);
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.ContactList readContactList(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.ContactList result = new de.haumacher.imageServer.shared.model.ContactList();
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
		out.name(CONTACTS__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.Contact x : getContacts()) {
			x.writeTo(out);
		}
		out.endArray();
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case CONTACTS__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addContact(de.haumacher.imageServer.shared.model.Contact.readContact(in));
				}
				in.endArray();
			}
			break;
			default: super.readField(in, field);
		}
	}

}
