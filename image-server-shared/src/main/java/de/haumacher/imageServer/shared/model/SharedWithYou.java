package de.haumacher.imageServer.shared.model;

/**
 * Everything shared with a contact in this space, the answer of <code>&lt;data&gt;/?type=shared</code>,
 * see issue #267.
 *
 * <p>
 * Asked by the overview at <code>&lt;app base&gt;/shared/</code> with the contact credential alone
 * (<code>X-VAlbum-Contact</code>, beside <code>X-VAlbum-Shared: all</code> and no bearer). One
 * {@link SharedAlbum} per link the contact may open: a personal link addressed to them, and an open
 * personal link they opened &mdash; neither expired, deleted nor shut to them. Rights are never
 * merged: two links on the same or nested folders are two albums.
 * </p>
 */
public class SharedWithYou extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.SharedWithYou} instance.
	 */
	public static de.haumacher.imageServer.shared.model.SharedWithYou create() {
		return new de.haumacher.imageServer.shared.model.SharedWithYou();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.SharedWithYou} type in JSON format. */
	public static final String SHARED_WITH_YOU__TYPE = "SharedWithYou";

	/** @see #getContact() */
	private static final String CONTACT__PROP = "contact";

	/** @see #getAlbums() */
	private static final String ALBUMS__PROP = "albums";

	/** @see #getOtherSessions() */
	private static final String OTHER_SESSIONS__PROP = "otherSessions";

	/** @see #getSignIns() */
	private static final String SIGN_INS__PROP = "signIns";

	private de.haumacher.imageServer.shared.model.ContactInfo _contact = null;

	private final java.util.List<de.haumacher.imageServer.shared.model.SharedAlbum> _albums = new java.util.ArrayList<>();

	private int _otherSessions = 0;

	private de.haumacher.imageServer.shared.model.ContactSignIns _signIns = null;

	/**
	 * Creates a {@link SharedWithYou} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.SharedWithYou#create()
	 */
	protected SharedWithYou() {
		super();
	}

	/**
	 * Who the server takes the caller to be.
	 */
	public final de.haumacher.imageServer.shared.model.ContactInfo getContact() {
		return _contact;
	}

	/**
	 * @see #getContact()
	 */
	public de.haumacher.imageServer.shared.model.SharedWithYou setContact(de.haumacher.imageServer.shared.model.ContactInfo value) {
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
	 * The albums, the most recently shared first.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.SharedAlbum> getAlbums() {
		return _albums;
	}

	/**
	 * @see #getAlbums()
	 */
	public de.haumacher.imageServer.shared.model.SharedWithYou setAlbums(java.util.List<? extends de.haumacher.imageServer.shared.model.SharedAlbum> value) {
		internalSetAlbums(value);
		return this;
	}

	/** Internal setter for {@link #getAlbums()} without chain call utility. */
	protected final void internalSetAlbums(java.util.List<? extends de.haumacher.imageServer.shared.model.SharedAlbum> value) {
		if (value == null) throw new IllegalArgumentException("Property 'albums' cannot be null.");
		_albums.clear();
		_albums.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getAlbums()} list.
	 */
	public de.haumacher.imageServer.shared.model.SharedWithYou addAlbum(de.haumacher.imageServer.shared.model.SharedAlbum value) {
		internalAddAlbum(value);
		return this;
	}

	/** Implementation of {@link #addAlbum(de.haumacher.imageServer.shared.model.SharedAlbum)} without chain call utility. */
	protected final void internalAddAlbum(de.haumacher.imageServer.shared.model.SharedAlbum value) {
		_albums.add(value);
	}

	/**
	 * Removes a value from the {@link #getAlbums()} list.
	 */
	public final void removeAlbum(de.haumacher.imageServer.shared.model.SharedAlbum value) {
		_albums.remove(value);
	}

	/**
	 * On how many other browsers the contact is signed in, see {@link ShareInfo#getOtherSessions()}.
	 */
	public final int getOtherSessions() {
		return _otherSessions;
	}

	/**
	 * @see #getOtherSessions()
	 */
	public de.haumacher.imageServer.shared.model.SharedWithYou setOtherSessions(int value) {
		internalSetOtherSessions(value);
		return this;
	}

	/** Internal setter for {@link #getOtherSessions()} without chain call utility. */
	protected final void internalSetOtherSessions(int value) {
		_otherSessions = value;
	}

	/**
	 * How the contact may be recognised on another browser, see {@link ShareInfo#getSignIns()}.
	 */
	public final de.haumacher.imageServer.shared.model.ContactSignIns getSignIns() {
		return _signIns;
	}

	/**
	 * @see #getSignIns()
	 */
	public de.haumacher.imageServer.shared.model.SharedWithYou setSignIns(de.haumacher.imageServer.shared.model.ContactSignIns value) {
		internalSetSignIns(value);
		return this;
	}

	/** Internal setter for {@link #getSignIns()} without chain call utility. */
	protected final void internalSetSignIns(de.haumacher.imageServer.shared.model.ContactSignIns value) {
		_signIns = value;
	}

	/**
	 * Checks, whether {@link #getSignIns()} has a value.
	 */
	public final boolean hasSignIns() {
		return _signIns != null;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.SharedWithYou readSharedWithYou(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.SharedWithYou result = new de.haumacher.imageServer.shared.model.SharedWithYou();
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
		if (hasContact()) {
			out.name(CONTACT__PROP);
			getContact().writeTo(out);
		}
		out.name(ALBUMS__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.SharedAlbum x : getAlbums()) {
			x.writeTo(out);
		}
		out.endArray();
		out.name(OTHER_SESSIONS__PROP);
		out.value(getOtherSessions());
		if (hasSignIns()) {
			out.name(SIGN_INS__PROP);
			getSignIns().writeTo(out);
		}
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case CONTACT__PROP: setContact(de.haumacher.imageServer.shared.model.ContactInfo.readContactInfo(in)); break;
			case ALBUMS__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addAlbum(de.haumacher.imageServer.shared.model.SharedAlbum.readSharedAlbum(in));
				}
				in.endArray();
			}
			break;
			case OTHER_SESSIONS__PROP: setOtherSessions(in.nextInt()); break;
			case SIGN_INS__PROP: setSignIns(de.haumacher.imageServer.shared.model.ContactSignIns.readContactSignIns(in)); break;
			default: super.readField(in, field);
		}
	}

}
