package de.haumacher.imageServer.shared.model;

/**
 * Removing a way a contact is recognised (issue #208): by the contact themself at
 * <code>&lt;data&gt;/?action=remove-sign-in</code>, answered with their {@link ContactSignIns}, or by
 * a member who manages the contacts at <code>&lt;data&gt;/?action=remove-contact-sign-in</code>,
 * answered with the {@link Contact}.
 */
public class SignInRemove extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.SignInRemove} instance.
	 */
	public static de.haumacher.imageServer.shared.model.SignInRemove create() {
		return new de.haumacher.imageServer.shared.model.SignInRemove();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.SignInRemove} type in JSON format. */
	public static final String SIGN_IN_REMOVE__TYPE = "SignInRemove";

	/** @see #getContact() */
	private static final String CONTACT__PROP = "contact";

	/** @see #getMethod() */
	private static final String METHOD__PROP = "method";

	/** @see #getId() */
	private static final String ID__PROP = "id";

	private String _contact = "";

	private String _method = "";

	private String _id = "";

	/**
	 * Creates a {@link SignInRemove} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.SignInRemove#create()
	 */
	protected SignInRemove() {
		super();
	}

	/**
	 * The id of the contact; ignored where the contact removes their own.
	 */
	public final String getContact() {
		return _contact;
	}

	/**
	 * @see #getContact()
	 */
	public de.haumacher.imageServer.shared.model.SignInRemove setContact(String value) {
		internalSetContact(value);
		return this;
	}

	/** Internal setter for {@link #getContact()} without chain call utility. */
	protected final void internalSetContact(String value) {
		_contact = value;
	}

	/**
	 * <code>totp</code> for the authenticator app, <code>passkey</code> for a passkey (issue #204).
	 */
	public final String getMethod() {
		return _method;
	}

	/**
	 * @see #getMethod()
	 */
	public de.haumacher.imageServer.shared.model.SignInRemove setMethod(String value) {
		internalSetMethod(value);
		return this;
	}

	/** Internal setter for {@link #getMethod()} without chain call utility. */
	protected final void internalSetMethod(String value) {
		_method = value;
	}

	/**
	 * Which one of several: the {@link ContactPasskey#getId()} of a passkey; empty for the authenticator app.
	 */
	public final String getId() {
		return _id;
	}

	/**
	 * @see #getId()
	 */
	public de.haumacher.imageServer.shared.model.SignInRemove setId(String value) {
		internalSetId(value);
		return this;
	}

	/** Internal setter for {@link #getId()} without chain call utility. */
	protected final void internalSetId(String value) {
		_id = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.SignInRemove readSignInRemove(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.SignInRemove result = new de.haumacher.imageServer.shared.model.SignInRemove();
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
		out.name(METHOD__PROP);
		out.value(getMethod());
		out.name(ID__PROP);
		out.value(getId());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case CONTACT__PROP: setContact(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case METHOD__PROP: setMethod(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case ID__PROP: setId(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
