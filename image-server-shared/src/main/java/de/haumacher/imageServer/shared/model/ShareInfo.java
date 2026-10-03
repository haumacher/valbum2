package de.haumacher.imageServer.shared.model;

/**
 * The share link the caller opened, see {@link AuthInfo#getShare()} and issue #51.
 *
 * <p>
 * What the app needs to confine itself and to name what it shows; the token itself is never
 * answered, and neither are the link's privacy and rating limits — those are applied on the
 * server, on the way out.
 * </p>
 */
public class ShareInfo extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.ShareInfo} instance.
	 */
	public static de.haumacher.imageServer.shared.model.ShareInfo create() {
		return new de.haumacher.imageServer.shared.model.ShareInfo();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.ShareInfo} type in JSON format. */
	public static final String SHARE_INFO__TYPE = "ShareInfo";

	/** @see #getLabel() */
	private static final String LABEL__PROP = "label";

	/** @see #getExpires() */
	private static final String EXPIRES__PROP = "expires";

	/** @see #getRights() */
	private static final String RIGHTS__PROP = "rights";

	/** @see #getPath() */
	private static final String PATH__PROP = "path";

	/** @see #getType() */
	private static final String TYPE__PROP = "type";

	/** @see #getContact() */
	private static final String CONTACT__PROP = "contact";

	/** @see #getMethods() */
	private static final String METHODS__PROP = "methods";

	/** @see #isContactHasEmail() */
	private static final String CONTACT_HAS_EMAIL__PROP = "contactHasEmail";

	/** @see #getOtherSessions() */
	private static final String OTHER_SESSIONS__PROP = "otherSessions";

	/** @see #getSignIns() */
	private static final String SIGN_INS__PROP = "signIns";

	private String _label = "";

	private String _expires = "";

	private final java.util.List<de.haumacher.imageServer.shared.model.RightName> _rights = new java.util.ArrayList<>();

	private String _path = "";

	private de.haumacher.imageServer.shared.model.ShareType _type = de.haumacher.imageServer.shared.model.ShareType.ANONYMOUS;

	private de.haumacher.imageServer.shared.model.ContactInfo _contact = null;

	private final java.util.List<de.haumacher.imageServer.shared.model.ProofMethod> _methods = new java.util.ArrayList<>();

	private boolean _contactHasEmail = false;

	private int _otherSessions = 0;

	private de.haumacher.imageServer.shared.model.ContactSignIns _signIns = null;

	/**
	 * Creates a {@link ShareInfo} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.ShareInfo#create()
	 */
	protected ShareInfo() {
		super();
	}

	/**
	 * The label the link was created with, empty if it was created without one.
	 */
	public final String getLabel() {
		return _label;
	}

	/**
	 * @see #getLabel()
	 */
	public de.haumacher.imageServer.shared.model.ShareInfo setLabel(String value) {
		internalSetLabel(value);
		return this;
	}

	/** Internal setter for {@link #getLabel()} without chain call utility. */
	protected final void internalSetLabel(String value) {
		_label = value;
	}

	/**
	 * When the link expires, an ISO-8601 instant; empty if it never does.
	 */
	public final String getExpires() {
		return _expires;
	}

	/**
	 * @see #getExpires()
	 */
	public de.haumacher.imageServer.shared.model.ShareInfo setExpires(String value) {
		internalSetExpires(value);
		return this;
	}

	/** Internal setter for {@link #getExpires()} without chain call utility. */
	protected final void internalSetExpires(String value) {
		_expires = value;
	}

	/**
	 * What the link allows: <code>view</code>, <code>download</code>, <code>contribute</code>.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.RightName> getRights() {
		return _rights;
	}

	/**
	 * @see #getRights()
	 */
	public de.haumacher.imageServer.shared.model.ShareInfo setRights(java.util.List<? extends de.haumacher.imageServer.shared.model.RightName> value) {
		internalSetRights(value);
		return this;
	}

	/** Internal setter for {@link #getRights()} without chain call utility. */
	protected final void internalSetRights(java.util.List<? extends de.haumacher.imageServer.shared.model.RightName> value) {
		if (value == null) throw new IllegalArgumentException("Property 'rights' cannot be null.");
		_rights.clear();
		_rights.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getRights()} list.
	 */
	public de.haumacher.imageServer.shared.model.ShareInfo addRight(de.haumacher.imageServer.shared.model.RightName value) {
		internalAddRight(value);
		return this;
	}

	/** Implementation of {@link #addRight(de.haumacher.imageServer.shared.model.RightName)} without chain call utility. */
	protected final void internalAddRight(de.haumacher.imageServer.shared.model.RightName value) {
		_rights.add(value);
	}

	/**
	 * Removes a value from the {@link #getRights()} list.
	 */
	public final void removeRight(de.haumacher.imageServer.shared.model.RightName value) {
		_rights.remove(value);
	}

	/**
	 * The canonical <code>~&lt;owner&gt;/&lt;path&gt;</code> of the link's target, so the app can name it.
	 */
	public final String getPath() {
		return _path;
	}

	/**
	 * @see #getPath()
	 */
	public de.haumacher.imageServer.shared.model.ShareInfo setPath(String value) {
		internalSetPath(value);
		return this;
	}

	/** Internal setter for {@link #getPath()} without chain call utility. */
	protected final void internalSetPath(String value) {
		_path = value;
	}

	/**
	 * Whether the link is anonymous or personal, see {@link ShareType} and issue #198.
	 */
	public final de.haumacher.imageServer.shared.model.ShareType getType() {
		return _type;
	}

	/**
	 * @see #getType()
	 */
	public de.haumacher.imageServer.shared.model.ShareInfo setType(de.haumacher.imageServer.shared.model.ShareType value) {
		internalSetType(value);
		return this;
	}

	/** Internal setter for {@link #getType()} without chain call utility. */
	protected final void internalSetType(de.haumacher.imageServer.shared.model.ShareType value) {
		if (value == null) throw new IllegalArgumentException("Property 'type' cannot be null.");
		_type = value;
	}

	/**
	 * The contact this session is, <code>null</code> for an anonymous link (issue #198).
	 *
	 * <p>
	 * Set exactly where a personal link was opened with a contact credential that it admits: who
	 * the server takes the caller to be, and therefore whom it attributes their uploads to. The
	 * app names them ("Not you?") and nothing more is said about the contact.
	 * </p>
	 */
	public final de.haumacher.imageServer.shared.model.ContactInfo getContact() {
		return _contact;
	}

	/**
	 * @see #getContact()
	 */
	public de.haumacher.imageServer.shared.model.ShareInfo setContact(de.haumacher.imageServer.shared.model.ContactInfo value) {
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
	 * The ways the contact of this session may prove a further address, see issue #199.
	 *
	 * <p>
	 * Answered beside {@link #getContact()} only: <code>mail-code</code> where the server can mail a
	 * code, so that the app offers "Add your e-mail so we recognise you on other devices", and
	 * <code>oidc:&lt;provider&gt;</code> per provider of OpenID Connect (issue #200); empty where
	 * there is neither, and for every caller who is no contact.
	 * </p>
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.ProofMethod> getMethods() {
		return _methods;
	}

	/**
	 * @see #getMethods()
	 */
	public de.haumacher.imageServer.shared.model.ShareInfo setMethods(java.util.List<? extends de.haumacher.imageServer.shared.model.ProofMethod> value) {
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
	public de.haumacher.imageServer.shared.model.ShareInfo addMethod(de.haumacher.imageServer.shared.model.ProofMethod value) {
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
	 * Whether the contact of this session has an e-mail address saved in the space, proven or not
	 * (issue #211); <code>false</code> for every caller who is no contact.
	 *
	 * <p>
	 * The app offers "Add your e-mail so we recognise you on other devices" only where this is
	 * <code>false</code>. Nothing is said about the address itself, and nothing about anybody else.
	 * </p>
	 */
	public final boolean isContactHasEmail() {
		return _contactHasEmail;
	}

	/**
	 * @see #isContactHasEmail()
	 */
	public de.haumacher.imageServer.shared.model.ShareInfo setContactHasEmail(boolean value) {
		internalSetContactHasEmail(value);
		return this;
	}

	/** Internal setter for {@link #isContactHasEmail()} without chain call utility. */
	protected final void internalSetContactHasEmail(boolean value) {
		_contactHasEmail = value;
	}

	/**
	 * On how many other browsers the contact of this session is signed in, see issue #203;
	 * <code>0</code> for every caller who is no contact.
	 *
	 * <p>
	 * The live contact credentials of the contact besides the one this request came with. The app
	 * offers "Also signed in on n other browsers &mdash; sign out others" where it is not
	 * <code>0</code>, which <code>&lt;data&gt;/?action=end-other-sessions</code> does.
	 * </p>
	 */
	public final int getOtherSessions() {
		return _otherSessions;
	}

	/**
	 * @see #getOtherSessions()
	 */
	public de.haumacher.imageServer.shared.model.ShareInfo setOtherSessions(int value) {
		internalSetOtherSessions(value);
		return this;
	}

	/** Internal setter for {@link #getOtherSessions()} without chain call utility. */
	protected final void internalSetOtherSessions(int value) {
		_otherSessions = value;
	}

	/**
	 * How the contact of this session may be recognised on another browser besides their link
	 * (issues #208, #204); <code>null</code> for every caller who is no contact.
	 */
	public final de.haumacher.imageServer.shared.model.ContactSignIns getSignIns() {
		return _signIns;
	}

	/**
	 * @see #getSignIns()
	 */
	public de.haumacher.imageServer.shared.model.ShareInfo setSignIns(de.haumacher.imageServer.shared.model.ContactSignIns value) {
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
	public static de.haumacher.imageServer.shared.model.ShareInfo readShareInfo(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.ShareInfo result = new de.haumacher.imageServer.shared.model.ShareInfo();
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
		out.name(LABEL__PROP);
		out.value(getLabel());
		out.name(EXPIRES__PROP);
		out.value(getExpires());
		out.name(RIGHTS__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.RightName x : getRights()) {
			x.writeTo(out);
		}
		out.endArray();
		out.name(PATH__PROP);
		out.value(getPath());
		out.name(TYPE__PROP);
		getType().writeTo(out);
		if (hasContact()) {
			out.name(CONTACT__PROP);
			getContact().writeTo(out);
		}
		out.name(METHODS__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.ProofMethod x : getMethods()) {
			x.writeTo(out);
		}
		out.endArray();
		out.name(CONTACT_HAS_EMAIL__PROP);
		out.value(isContactHasEmail());
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
			case LABEL__PROP: setLabel(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case EXPIRES__PROP: setExpires(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case RIGHTS__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addRight(de.haumacher.imageServer.shared.model.RightName.readRightName(in));
				}
				in.endArray();
			}
			break;
			case PATH__PROP: setPath(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case TYPE__PROP: setType(de.haumacher.imageServer.shared.model.ShareType.readShareType(in)); break;
			case CONTACT__PROP: setContact(de.haumacher.imageServer.shared.model.ContactInfo.readContactInfo(in)); break;
			case METHODS__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addMethod(de.haumacher.imageServer.shared.model.ProofMethod.readProofMethod(in));
				}
				in.endArray();
			}
			break;
			case CONTACT_HAS_EMAIL__PROP: setContactHasEmail(in.nextBoolean()); break;
			case OTHER_SESSIONS__PROP: setOtherSessions(in.nextInt()); break;
			case SIGN_INS__PROP: setSignIns(de.haumacher.imageServer.shared.model.ContactSignIns.readContactSignIns(in)); break;
			default: super.readField(in, field);
		}
	}

}
