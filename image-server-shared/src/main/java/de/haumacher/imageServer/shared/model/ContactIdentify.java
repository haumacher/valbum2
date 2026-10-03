package de.haumacher.imageServer.shared.model;

/**
 * The first open of a recipient's own link, <code>&lt;data&gt;/?action=identify</code> (issue #198).
 *
 * <p>
 * Sent with the recipient's token as the bearer: the link identifies the recipient once, and the
 * answer is a {@link ContactCredential} that recognises them from then on.
 * </p>
 */
public class ContactIdentify extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.ContactIdentify} instance.
	 */
	public static de.haumacher.imageServer.shared.model.ContactIdentify create() {
		return new de.haumacher.imageServer.shared.model.ContactIdentify();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.ContactIdentify} type in JSON format. */
	public static final String CONTACT_IDENTIFY__TYPE = "ContactIdentify";

	/** @see #isRemember() */
	private static final String REMEMBER__PROP = "remember";

	/** @see #getDisplayName() */
	private static final String DISPLAY_NAME__PROP = "displayName";

	private boolean _remember = false;

	private String _displayName = "";

	/**
	 * Creates a {@link ContactIdentify} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.ContactIdentify#create()
	 */
	protected ContactIdentify() {
		super();
	}

	/**
	 * Whether to remember this browser: 90 days renewed on use, else 24 hours.
	 */
	public final boolean isRemember() {
		return _remember;
	}

	/**
	 * @see #isRemember()
	 */
	public de.haumacher.imageServer.shared.model.ContactIdentify setRemember(boolean value) {
		internalSetRemember(value);
		return this;
	}

	/** Internal setter for {@link #isRemember()} without chain call utility. */
	protected final void internalSetRemember(boolean value) {
		_remember = value;
	}

	/**
	 * The name the contact wants to be greeted by; empty keeps what the space calls them.
	 */
	public final String getDisplayName() {
		return _displayName;
	}

	/**
	 * @see #getDisplayName()
	 */
	public de.haumacher.imageServer.shared.model.ContactIdentify setDisplayName(String value) {
		internalSetDisplayName(value);
		return this;
	}

	/** Internal setter for {@link #getDisplayName()} without chain call utility. */
	protected final void internalSetDisplayName(String value) {
		_displayName = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.ContactIdentify readContactIdentify(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.ContactIdentify result = new de.haumacher.imageServer.shared.model.ContactIdentify();
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
		out.name(REMEMBER__PROP);
		out.value(isRemember());
		out.name(DISPLAY_NAME__PROP);
		out.value(getDisplayName());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case REMEMBER__PROP: setRemember(in.nextBoolean()); break;
			case DISPLAY_NAME__PROP: setDisplayName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
