package de.haumacher.imageServer.shared.model;

/**
 * The answer of <code>?action=overview-link</code>, see {@link OverviewLinkRequest} and issue #267.
 */
public class OverviewLink extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.OverviewLink} instance.
	 */
	public static de.haumacher.imageServer.shared.model.OverviewLink create() {
		return new de.haumacher.imageServer.shared.model.OverviewLink();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.OverviewLink} type in JSON format. */
	public static final String OVERVIEW_LINK__TYPE = "OverviewLink";

	/** @see #getUrl() */
	private static final String URL__PROP = "url";

	/** @see #getContact() */
	private static final String CONTACT__PROP = "contact";

	private String _url = "";

	private de.haumacher.imageServer.shared.model.Contact _contact = null;

	/**
	 * Creates a {@link OverviewLink} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.OverviewLink#create()
	 */
	protected OverviewLink() {
		super();
	}

	/**
	 * The contact's new overview link, server-relative (<code>&lt;context&gt;/s/&lt;token&gt;/</code>),
	 * answered exactly once; empty after a withdrawal.
	 */
	public final String getUrl() {
		return _url;
	}

	/**
	 * @see #getUrl()
	 */
	public de.haumacher.imageServer.shared.model.OverviewLink setUrl(String value) {
		internalSetUrl(value);
		return this;
	}

	/** Internal setter for {@link #getUrl()} without chain call utility. */
	protected final void internalSetUrl(String value) {
		_url = value;
	}

	/**
	 * The contact as the register now holds them.
	 */
	public final de.haumacher.imageServer.shared.model.Contact getContact() {
		return _contact;
	}

	/**
	 * @see #getContact()
	 */
	public de.haumacher.imageServer.shared.model.OverviewLink setContact(de.haumacher.imageServer.shared.model.Contact value) {
		internalSetContact(value);
		return this;
	}

	/** Internal setter for {@link #getContact()} without chain call utility. */
	protected final void internalSetContact(de.haumacher.imageServer.shared.model.Contact value) {
		_contact = value;
	}

	/**
	 * Checks, whether {@link #getContact()} has a value.
	 */
	public final boolean hasContact() {
		return _contact != null;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.OverviewLink readOverviewLink(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.OverviewLink result = new de.haumacher.imageServer.shared.model.OverviewLink();
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
		out.name(URL__PROP);
		out.value(getUrl());
		if (hasContact()) {
			out.name(CONTACT__PROP);
			getContact().writeTo(out);
		}
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case URL__PROP: setUrl(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case CONTACT__PROP: setContact(de.haumacher.imageServer.shared.model.Contact.readContact(in)); break;
			default: super.readField(in, field);
		}
	}

}
