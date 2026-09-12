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

	private de.haumacher.imageServer.shared.model.ShareLink _link = null;

	private String _token = "";

	private String _url = "";

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
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case LINK__PROP: setLink(de.haumacher.imageServer.shared.model.ShareLink.readShareLink(in)); break;
			case TOKEN__PROP: setToken(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case URL__PROP: setUrl(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
