package de.haumacher.imageServer.shared.model;

/**
 * A share link: a scoped token opening one subtree to whoever holds it, see issue #51.
 *
 * <p>
 * Sent to <code>&lt;folder&gt;/?action=share</code> to create one, where the target is taken from
 * the URL and whatever the body says about {@link #getPath()} is ignored; answered by
 * <code>&lt;folder&gt;/?type=shares</code> and by <code>&lt;folder&gt;/?action=unshare</code>,
 * which names the link to withdraw by its {@link #getId()}.
 * </p>
 *
 * <p>
 * The token is never part of this message: it is answered exactly once, in a
 * {@link ShareLinkCreated}, and the server stores nothing but its hash.
 * </p>
 */
public class ShareLink extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.ShareLink} instance.
	 */
	public static de.haumacher.imageServer.shared.model.ShareLink create() {
		return new de.haumacher.imageServer.shared.model.ShareLink();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.ShareLink} type in JSON format. */
	public static final String SHARE_LINK__TYPE = "ShareLink";

	/** @see #getId() */
	private static final String ID__PROP = "id";

	/** @see #getLabel() */
	private static final String LABEL__PROP = "label";

	/** @see #getExpires() */
	private static final String EXPIRES__PROP = "expires";

	/** @see #getMaxPrivacy() */
	private static final String MAX_PRIVACY__PROP = "maxPrivacy";

	/** @see #getMinRating() */
	private static final String MIN_RATING__PROP = "minRating";

	/** @see #getRights() */
	private static final String RIGHTS__PROP = "rights";

	/** @see #getPath() */
	private static final String PATH__PROP = "path";

	/** @see #getCreatedBy() */
	private static final String CREATED_BY__PROP = "createdBy";

	/** @see #getCreated() */
	private static final String CREATED__PROP = "created";

	/** @see #getRevoked() */
	private static final String REVOKED__PROP = "revoked";

	private String _id = "";

	private String _label = "";

	private String _expires = "";

	private int _maxPrivacy = 0;

	private int _minRating = 0;

	private final java.util.List<de.haumacher.imageServer.shared.model.RightName> _rights = new java.util.ArrayList<>();

	private String _path = "";

	private String _createdBy = "";

	private String _created = "";

	private String _revoked = "";

	/**
	 * Creates a {@link ShareLink} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.ShareLink#create()
	 */
	protected ShareLink() {
		super();
	}

	/**
	 * The short id of the link; answered by the server, and what names it in a request.
	 */
	public final String getId() {
		return _id;
	}

	/**
	 * @see #getId()
	 */
	public de.haumacher.imageServer.shared.model.ShareLink setId(String value) {
		internalSetId(value);
		return this;
	}

	/** Internal setter for {@link #getId()} without chain call utility. */
	protected final void internalSetId(String value) {
		_id = value;
	}

	/**
	 * The label the link was created with, shown wherever the link is listed.
	 */
	public final String getLabel() {
		return _label;
	}

	/**
	 * @see #getLabel()
	 */
	public de.haumacher.imageServer.shared.model.ShareLink setLabel(String value) {
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
	public de.haumacher.imageServer.shared.model.ShareLink setExpires(String value) {
		internalSetExpires(value);
		return this;
	}

	/** Internal setter for {@link #getExpires()} without chain call utility. */
	protected final void internalSetExpires(String value) {
		_expires = value;
	}

	/**
	 * The highest {@link ImagePart#getPrivacy()} the link shows: <code>0</code>..<code>2</code>.
	 */
	public final int getMaxPrivacy() {
		return _maxPrivacy;
	}

	/**
	 * @see #getMaxPrivacy()
	 */
	public de.haumacher.imageServer.shared.model.ShareLink setMaxPrivacy(int value) {
		internalSetMaxPrivacy(value);
		return this;
	}

	/** Internal setter for {@link #getMaxPrivacy()} without chain call utility. */
	protected final void internalSetMaxPrivacy(int value) {
		_maxPrivacy = value;
	}

	/**
	 * The lowest {@link ImagePart#getRating()} the link shows: <code>-2</code>..<code>2</code>.
	 */
	public final int getMinRating() {
		return _minRating;
	}

	/**
	 * @see #getMinRating()
	 */
	public de.haumacher.imageServer.shared.model.ShareLink setMinRating(int value) {
		internalSetMinRating(value);
		return this;
	}

	/** Internal setter for {@link #getMinRating()} without chain call utility. */
	protected final void internalSetMinRating(int value) {
		_minRating = value;
	}

	/**
	 * What the link allows: <code>view</code>, <code>download</code>, <code>contribute</code>.
	 *
	 * <p>
	 * Never <code>edit</code>: a link is not an account. An empty list means <code>view</code>.
	 * </p>
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.RightName> getRights() {
		return _rights;
	}

	/**
	 * @see #getRights()
	 */
	public de.haumacher.imageServer.shared.model.ShareLink setRights(java.util.List<? extends de.haumacher.imageServer.shared.model.RightName> value) {
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
	public de.haumacher.imageServer.shared.model.ShareLink addRight(de.haumacher.imageServer.shared.model.RightName value) {
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
	 * The path of the link's target inside the space; answered by the server.
	 */
	public final String getPath() {
		return _path;
	}

	/**
	 * @see #getPath()
	 */
	public de.haumacher.imageServer.shared.model.ShareLink setPath(String value) {
		internalSetPath(value);
		return this;
	}

	/** Internal setter for {@link #getPath()} without chain call utility. */
	protected final void internalSetPath(String value) {
		_path = value;
	}

	/**
	 * The name of the user who created the link; answered by the server (issue #84).
	 *
	 * <p>
	 * A link belongs to whoever handed it out: they see it in the listing of the folder and may
	 * withdraw it, and so may an administrator of the space. Empty for a link made before this
	 * field existed, which only an administrator sees.
	 * </p>
	 */
	public final String getCreatedBy() {
		return _createdBy;
	}

	/**
	 * @see #getCreatedBy()
	 */
	public de.haumacher.imageServer.shared.model.ShareLink setCreatedBy(String value) {
		internalSetCreatedBy(value);
		return this;
	}

	/** Internal setter for {@link #getCreatedBy()} without chain call utility. */
	protected final void internalSetCreatedBy(String value) {
		_createdBy = value;
	}

	/**
	 * When the link was created, an ISO-8601 instant; answered by the server.
	 */
	public final String getCreated() {
		return _created;
	}

	/**
	 * @see #getCreated()
	 */
	public de.haumacher.imageServer.shared.model.ShareLink setCreated(String value) {
		internalSetCreated(value);
		return this;
	}

	/** Internal setter for {@link #getCreated()} without chain call utility. */
	protected final void internalSetCreated(String value) {
		_created = value;
	}

	/**
	 * When the link was withdrawn, an ISO-8601 instant; empty while the link is live.
	 */
	public final String getRevoked() {
		return _revoked;
	}

	/**
	 * @see #getRevoked()
	 */
	public de.haumacher.imageServer.shared.model.ShareLink setRevoked(String value) {
		internalSetRevoked(value);
		return this;
	}

	/** Internal setter for {@link #getRevoked()} without chain call utility. */
	protected final void internalSetRevoked(String value) {
		_revoked = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.ShareLink readShareLink(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.ShareLink result = new de.haumacher.imageServer.shared.model.ShareLink();
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
		out.name(ID__PROP);
		out.value(getId());
		out.name(LABEL__PROP);
		out.value(getLabel());
		out.name(EXPIRES__PROP);
		out.value(getExpires());
		out.name(MAX_PRIVACY__PROP);
		out.value(getMaxPrivacy());
		out.name(MIN_RATING__PROP);
		out.value(getMinRating());
		out.name(RIGHTS__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.RightName x : getRights()) {
			x.writeTo(out);
		}
		out.endArray();
		out.name(PATH__PROP);
		out.value(getPath());
		out.name(CREATED_BY__PROP);
		out.value(getCreatedBy());
		out.name(CREATED__PROP);
		out.value(getCreated());
		out.name(REVOKED__PROP);
		out.value(getRevoked());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case ID__PROP: setId(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case LABEL__PROP: setLabel(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case EXPIRES__PROP: setExpires(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case MAX_PRIVACY__PROP: setMaxPrivacy(in.nextInt()); break;
			case MIN_RATING__PROP: setMinRating(in.nextInt()); break;
			case RIGHTS__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addRight(de.haumacher.imageServer.shared.model.RightName.readRightName(in));
				}
				in.endArray();
			}
			break;
			case PATH__PROP: setPath(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case CREATED_BY__PROP: setCreatedBy(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case CREATED__PROP: setCreated(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case REVOKED__PROP: setRevoked(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
