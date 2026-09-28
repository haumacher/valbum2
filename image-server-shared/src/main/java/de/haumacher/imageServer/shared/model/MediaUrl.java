package de.haumacher.imageServer.shared.model;

/**
 * The answer to <code>&lt;video&gt;?type=media-url&amp;for=video|teaser|original</code>: an address
 * a player may fetch without the <code>Authorization</code> header, see issue #185.
 *
 * <p>
 * On the web the app hands a video to an HTML <code>&lt;video&gt;</code> element, which fetches the
 * address itself and cannot send the device's bearer. So the device asks, with its bearer, for a
 * short-lived signature over exactly this path and this kind of file, and the element fetches the
 * ordinary address with <code>&amp;media=&lt;signature&gt;</code> appended. The rights are checked
 * when the signature is issued, exactly as for the plain request; a request carrying it is served
 * that one file and nothing else. The device token itself never appears in an address.
 * </p>
 */
public class MediaUrl extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.MediaUrl} instance.
	 */
	public static de.haumacher.imageServer.shared.model.MediaUrl create() {
		return new de.haumacher.imageServer.shared.model.MediaUrl();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.MediaUrl} type in JSON format. */
	public static final String MEDIA_URL__TYPE = "MediaUrl";

	/** @see #getUrl() */
	private static final String URL__PROP = "url";

	/** @see #getMedia() */
	private static final String MEDIA__PROP = "media";

	/** @see #getExpires() */
	private static final String EXPIRES__PROP = "expires";

	private String _url = "";

	private String _media = "";

	private String _expires = "";

	/**
	 * Creates a {@link MediaUrl} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.MediaUrl#create()
	 */
	protected MediaUrl() {
		super();
	}

	/**
	 * The address to play: the ordinary address of the file, path-absolute
	 * (<code>/valbum/data/2026/a.mp4?type=video&amp;media=…</code>), carrying {@link #getMedia()}.
	 */
	public final String getUrl() {
		return _url;
	}

	/**
	 * @see #getUrl()
	 */
	public de.haumacher.imageServer.shared.model.MediaUrl setUrl(String value) {
		internalSetUrl(value);
		return this;
	}

	/** Internal setter for {@link #getUrl()} without chain call utility. */
	protected final void internalSetUrl(String value) {
		_url = value;
	}

	/**
	 * The value of the <code>media</code> parameter alone, for a client that builds the address
	 * itself (the app appends it to the address it already has, so a proxy that rewrites the
	 * path changes nothing).
	 */
	public final String getMedia() {
		return _media;
	}

	/**
	 * @see #getMedia()
	 */
	public de.haumacher.imageServer.shared.model.MediaUrl setMedia(String value) {
		internalSetMedia(value);
		return this;
	}

	/** Internal setter for {@link #getMedia()} without chain call utility. */
	protected final void internalSetMedia(String value) {
		_media = value;
	}

	/**
	 * When the signature stops working, an ISO-8601 instant; ten minutes after it was issued.
	 */
	public final String getExpires() {
		return _expires;
	}

	/**
	 * @see #getExpires()
	 */
	public de.haumacher.imageServer.shared.model.MediaUrl setExpires(String value) {
		internalSetExpires(value);
		return this;
	}

	/** Internal setter for {@link #getExpires()} without chain call utility. */
	protected final void internalSetExpires(String value) {
		_expires = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.MediaUrl readMediaUrl(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.MediaUrl result = new de.haumacher.imageServer.shared.model.MediaUrl();
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
		out.name(MEDIA__PROP);
		out.value(getMedia());
		out.name(EXPIRES__PROP);
		out.value(getExpires());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case URL__PROP: setUrl(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case MEDIA__PROP: setMedia(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case EXPIRES__PROP: setExpires(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
