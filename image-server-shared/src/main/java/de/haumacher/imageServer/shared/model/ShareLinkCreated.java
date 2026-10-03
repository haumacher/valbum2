package de.haumacher.imageServer.shared.model;

/**
 * The answer to <code>&lt;folder&gt;/?action=share</code>: the new link, with its token.
 *
 * <p>
 * The one and only time the {@link #getToken()} is answered; the server keeps its hash and can never
 * show it again. A lost link is withdrawn and created anew.
 * </p>
 */
public class ShareLinkCreated extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.ShareLinkCreated} instance.
	 */
	public static de.haumacher.imageServer.shared.model.ShareLinkCreated create() {
		return new de.haumacher.imageServer.shared.model.ShareLinkCreated();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.ShareLinkCreated} type in JSON format. */
	public static final String SHARE_LINK_CREATED__TYPE = "ShareLinkCreated";

	/** @see #getLink() */
	private static final String LINK__PROP = "link";

	/** @see #getToken() */
	private static final String TOKEN__PROP = "token";

	/** @see #getUrl() */
	private static final String URL__PROP = "url";

	/** @see #getRecipients() */
	private static final String RECIPIENTS__PROP = "recipients";

	private de.haumacher.imageServer.shared.model.ShareLink _link = null;

	private String _token = "";

	private String _url = "";

	private final java.util.List<de.haumacher.imageServer.shared.model.RecipientLink> _recipients = new java.util.ArrayList<>();

	/**
	 * Creates a {@link ShareLinkCreated} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.ShareLinkCreated#create()
	 */
	protected ShareLinkCreated() {
		super();
	}

	/**
	 * The link that was created, as {@link ShareLinkList} lists it.
	 */
	public final de.haumacher.imageServer.shared.model.ShareLink getLink() {
		return _link;
	}

	/**
	 * @see #getLink()
	 */
	public de.haumacher.imageServer.shared.model.ShareLinkCreated setLink(de.haumacher.imageServer.shared.model.ShareLink value) {
		internalSetLink(value);
		return this;
	}

	/** Internal setter for {@link #getLink()} without chain call utility. */
	protected final void internalSetLink(de.haumacher.imageServer.shared.model.ShareLink value) {
		_link = value;
	}

	/**
	 * Checks, whether {@link #getLink()} has a value.
	 */
	public final boolean hasLink() {
		return _link != null;
	}

	/**
	 * The token to open the link with, answered exactly once and never stored.
	 */
	public final String getToken() {
		return _token;
	}

	/**
	 * @see #getToken()
	 */
	public de.haumacher.imageServer.shared.model.ShareLinkCreated setToken(String value) {
		internalSetToken(value);
		return this;
	}

	/** Internal setter for {@link #getToken()} without chain call utility. */
	protected final void internalSetToken(String value) {
		_token = value;
	}

	/**
	 * The link's path on this server: <code>&lt;context&gt;/s/&lt;token&gt;/</code>.
	 */
	public final String getUrl() {
		return _url;
	}

	/**
	 * @see #getUrl()
	 */
	public de.haumacher.imageServer.shared.model.ShareLinkCreated setUrl(String value) {
		internalSetUrl(value);
		return this;
	}

	/** Internal setter for {@link #getUrl()} without chain call utility. */
	protected final void internalSetUrl(String value) {
		_url = value;
	}

	/**
	 * For an addressed personal link, one link of their own per recipient (issue #198).
	 *
	 * <p>
	 * Answered exactly once, like {@link #getToken()}: by <code>?action=share</code> for every
	 * recipient and by <code>?action=resend</code> for the one recipient sent to again. The link's
	 * own {@link #getToken()} opens nothing without a contact credential; these are what the sharer
	 * sends.
	 * </p>
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.RecipientLink> getRecipients() {
		return _recipients;
	}

	/**
	 * @see #getRecipients()
	 */
	public de.haumacher.imageServer.shared.model.ShareLinkCreated setRecipients(java.util.List<? extends de.haumacher.imageServer.shared.model.RecipientLink> value) {
		internalSetRecipients(value);
		return this;
	}

	/** Internal setter for {@link #getRecipients()} without chain call utility. */
	protected final void internalSetRecipients(java.util.List<? extends de.haumacher.imageServer.shared.model.RecipientLink> value) {
		if (value == null) throw new IllegalArgumentException("Property 'recipients' cannot be null.");
		_recipients.clear();
		_recipients.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getRecipients()} list.
	 */
	public de.haumacher.imageServer.shared.model.ShareLinkCreated addRecipient(de.haumacher.imageServer.shared.model.RecipientLink value) {
		internalAddRecipient(value);
		return this;
	}

	/** Implementation of {@link #addRecipient(de.haumacher.imageServer.shared.model.RecipientLink)} without chain call utility. */
	protected final void internalAddRecipient(de.haumacher.imageServer.shared.model.RecipientLink value) {
		_recipients.add(value);
	}

	/**
	 * Removes a value from the {@link #getRecipients()} list.
	 */
	public final void removeRecipient(de.haumacher.imageServer.shared.model.RecipientLink value) {
		_recipients.remove(value);
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.ShareLinkCreated readShareLinkCreated(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.ShareLinkCreated result = new de.haumacher.imageServer.shared.model.ShareLinkCreated();
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
		if (hasLink()) {
			out.name(LINK__PROP);
			getLink().writeTo(out);
		}
		out.name(TOKEN__PROP);
		out.value(getToken());
		out.name(URL__PROP);
		out.value(getUrl());
		out.name(RECIPIENTS__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.RecipientLink x : getRecipients()) {
			x.writeTo(out);
		}
		out.endArray();
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case LINK__PROP: setLink(de.haumacher.imageServer.shared.model.ShareLink.readShareLink(in)); break;
			case TOKEN__PROP: setToken(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case URL__PROP: setUrl(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case RECIPIENTS__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addRecipient(de.haumacher.imageServer.shared.model.RecipientLink.readRecipientLink(in));
				}
				in.endArray();
			}
			break;
			default: super.readField(in, field);
		}
	}

}
