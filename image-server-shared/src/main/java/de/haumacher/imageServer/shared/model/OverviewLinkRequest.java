package de.haumacher.imageServer.shared.model;

/**
 * Issuing a contact their own link to the overview of everything shared with them,
 * <code>&lt;data&gt;/?action=overview-link</code>, see issue #267.
 *
 * <p>
 * Each issue voids the contact's earlier overview link; <code>withdraw</code> voids it without a
 * new one. Answered by an {@link OverviewLink}.
 * </p>
 */
public class OverviewLinkRequest extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.OverviewLinkRequest} instance.
	 */
	public static de.haumacher.imageServer.shared.model.OverviewLinkRequest create() {
		return new de.haumacher.imageServer.shared.model.OverviewLinkRequest();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.OverviewLinkRequest} type in JSON format. */
	public static final String OVERVIEW_LINK_REQUEST__TYPE = "OverviewLinkRequest";

	/** @see #getContact() */
	private static final String CONTACT__PROP = "contact";

	/** @see #isWithdraw() */
	private static final String WITHDRAW__PROP = "withdraw";

	private String _contact = "";

	private boolean _withdraw = false;

	/**
	 * Creates a {@link OverviewLinkRequest} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.OverviewLinkRequest#create()
	 */
	protected OverviewLinkRequest() {
		super();
	}

	/**
	 * The id of the contact.
	 */
	public final String getContact() {
		return _contact;
	}

	/**
	 * @see #getContact()
	 */
	public de.haumacher.imageServer.shared.model.OverviewLinkRequest setContact(String value) {
		internalSetContact(value);
		return this;
	}

	/** Internal setter for {@link #getContact()} without chain call utility. */
	protected final void internalSetContact(String value) {
		_contact = value;
	}

	/**
	 * Whether to void the contact's overview link without issuing a new one.
	 */
	public final boolean isWithdraw() {
		return _withdraw;
	}

	/**
	 * @see #isWithdraw()
	 */
	public de.haumacher.imageServer.shared.model.OverviewLinkRequest setWithdraw(boolean value) {
		internalSetWithdraw(value);
		return this;
	}

	/** Internal setter for {@link #isWithdraw()} without chain call utility. */
	protected final void internalSetWithdraw(boolean value) {
		_withdraw = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.OverviewLinkRequest readOverviewLinkRequest(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.OverviewLinkRequest result = new de.haumacher.imageServer.shared.model.OverviewLinkRequest();
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
		out.name(WITHDRAW__PROP);
		out.value(isWithdraw());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case CONTACT__PROP: setContact(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case WITHDRAW__PROP: setWithdraw(in.nextBoolean()); break;
			default: super.readField(in, field);
		}
	}

}
