package de.haumacher.imageServer.shared.model;

/**
 * Part of a {@link ListingInfo} describing a reference to a single album directory.
 */
public class FolderInfo extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.FolderInfo} instance.
	 */
	public static de.haumacher.imageServer.shared.model.FolderInfo create() {
		return new de.haumacher.imageServer.shared.model.FolderInfo();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.FolderInfo} type in JSON format. */
	public static final String FOLDER_INFO__TYPE = "FolderInfo";

	/** @see #getName() */
	private static final String NAME__PROP = "name";

	/** @see #getTitle() */
	private static final String TITLE__PROP = "title";

	/** @see #getSubTitle() */
	private static final String SUB_TITLE__PROP = "subTitle";

	/** @see #getEffectiveDate() */
	private static final String EFFECTIVE_DATE__PROP = "effectiveDate";

	/** @see #getKind() */
	private static final String KIND__PROP = "kind";

	/** @see #getIndexPicture() */
	private static final String INDEX_PICTURE__PROP = "indexPicture";

	/** @see #getLink() */
	private static final String LINK__PROP = "link";

	private String _name = "";

	private String _title = "";

	private String _subTitle = "";

	private long _effectiveDate = 0L;

	private de.haumacher.imageServer.shared.model.FolderKind _kind = de.haumacher.imageServer.shared.model.FolderKind.ALBUM;

	private de.haumacher.imageServer.shared.model.ThumbnailInfo _indexPicture = null;

	private String _link = "";

	/**
	 * Creates a {@link FolderInfo} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.FolderInfo#create()
	 */
	protected FolderInfo() {
		super();
	}

	/**
	 * The directory name of this {@link FolderInfo}.
	 */
	public final String getName() {
		return _name;
	}

	/**
	 * @see #getName()
	 */
	public de.haumacher.imageServer.shared.model.FolderInfo setName(String value) {
		internalSetName(value);
		return this;
	}

	/** Internal setter for {@link #getName()} without chain call utility. */
	protected final void internalSetName(String value) {
		_name = value;
	}

	/**
	 * The title of the {@link AlbumInfo} referenced by this {@link FolderInfo}.
	 */
	public final String getTitle() {
		return _title;
	}

	/**
	 * @see #getTitle()
	 */
	public de.haumacher.imageServer.shared.model.FolderInfo setTitle(String value) {
		internalSetTitle(value);
		return this;
	}

	/** Internal setter for {@link #getTitle()} without chain call utility. */
	protected final void internalSetTitle(String value) {
		_title = value;
	}

	/**
	 * The subtitle of the {@link AlbumInfo} referenced by this {@link FolderInfo}.
	 */
	public final String getSubTitle() {
		return _subTitle;
	}

	/**
	 * @see #getSubTitle()
	 */
	public de.haumacher.imageServer.shared.model.FolderInfo setSubTitle(String value) {
		internalSetSubTitle(value);
		return this;
	}

	/** Internal setter for {@link #getSubTitle()} without chain call utility. */
	protected final void internalSetSubTitle(String value) {
		_subTitle = value;
	}

	/**
	 * The date this folder is sorted by in its {@link ListingInfo}, in milliseconds since the epoch,
	 * <code>0</code> when nothing cheap says when it happened.
	 *
	 * <p>
	 * Read from the folder's own sidecar (an explicit {@link AlbumInfo#getDate()}) and from the folder
	 * name, and from nothing else: building a listing never opens the images of the albums it shows.
	 * An album with neither therefore carries <code>0</code> here although the album itself answers
	 * with an {@link AlbumInfo#getEffectiveDate()} derived from its images; that is what a listing of a
	 * thousand albums costs, see issue #48.
	 * </p>
	 */
	public final long getEffectiveDate() {
		return _effectiveDate;
	}

	/**
	 * @see #getEffectiveDate()
	 */
	public de.haumacher.imageServer.shared.model.FolderInfo setEffectiveDate(long value) {
		internalSetEffectiveDate(value);
		return this;
	}

	/** Internal setter for {@link #getEffectiveDate()} without chain call utility. */
	protected final void internalSetEffectiveDate(long value) {
		_effectiveDate = value;
	}

	/**
	 * Whether this entry is an album or a folder of folders, see issue #133.
	 *
	 * <p>
	 * Derived on every read like {@link #getEffectiveDate()} and never stored in a sidecar: what a
	 * folder is, is a question about the disk, and the answer is rebuilt whenever the listing is.
	 * </p>
	 *
	 * <p>
	 * The one thing that tells a reader whether {@link #getEffectiveDate()} is a date to show or merely
	 * the key this entry is sorted by: only an album happened on a day.
	 * </p>
	 */
	public final de.haumacher.imageServer.shared.model.FolderKind getKind() {
		return _kind;
	}

	/**
	 * @see #getKind()
	 */
	public de.haumacher.imageServer.shared.model.FolderInfo setKind(de.haumacher.imageServer.shared.model.FolderKind value) {
		internalSetKind(value);
		return this;
	}

	/** Internal setter for {@link #getKind()} without chain call utility. */
	protected final void internalSetKind(de.haumacher.imageServer.shared.model.FolderKind value) {
		if (value == null) throw new IllegalArgumentException("Property 'kind' cannot be null.");
		_kind = value;
	}

	/**
	 * The index picture of the {@link AlbumInfo} referenced by this {@link FolderInfo}.
	 */
	public final de.haumacher.imageServer.shared.model.ThumbnailInfo getIndexPicture() {
		return _indexPicture;
	}

	/**
	 * @see #getIndexPicture()
	 */
	public de.haumacher.imageServer.shared.model.FolderInfo setIndexPicture(de.haumacher.imageServer.shared.model.ThumbnailInfo value) {
		internalSetIndexPicture(value);
		return this;
	}

	/** Internal setter for {@link #getIndexPicture()} without chain call utility. */
	protected final void internalSetIndexPicture(de.haumacher.imageServer.shared.model.ThumbnailInfo value) {
		_indexPicture = value;
	}

	/**
	 * Checks, whether {@link #getIndexPicture()} has a value.
	 */
	public final boolean hasIndexPicture() {
		return _indexPicture != null;
	}

	/**
	 * The album this entry is a link to, in its owner's coordinates, see issue #50.
	 *
	 * <p>
	 * Empty for an ordinary folder on disk, and <code>~&lt;owner&gt;/&lt;path&gt;</code> for a link:
	 * a shared album somebody granted the caller a right on, showing in the caller's own tree under
	 * the {@link #getName()} the caller gave it. Everything else this tile carries (its {@link #getTitle()},
		 * its {@link #getSubTitle()}, its {@link #getIndexPicture()} and its {@link #getEffectiveDate()}) is read from
	 * the target, so a link looks like what it points at.
	 * </p>
	 *
	 * <p>
	 * The link is what the app marks the tile as shared with and what it names the owner from; it
	 * is never a path the app has to follow. Navigating into a link is ordinary navigation:
	 * <code>&lt;listing&gt;/&lt;name&gt;/</code> resolves through the link on the server, so the URL
	 * the app shows stays the viewer's own path. This is the canonical form a share link and a
	 * copied URL use, see issue #49.
	 * </p>
	 *
	 * <p>
	 * Derived on every read like {@link FolderResource#getRights()}, and never stored in a sidecar: a
	 * link lives in the folder's <code>.links.json</code>, not in its <code>index.json</code>.
	 * </p>
	 */
	public final String getLink() {
		return _link;
	}

	/**
	 * @see #getLink()
	 */
	public de.haumacher.imageServer.shared.model.FolderInfo setLink(String value) {
		internalSetLink(value);
		return this;
	}

	/** Internal setter for {@link #getLink()} without chain call utility. */
	protected final void internalSetLink(String value) {
		_link = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.FolderInfo readFolderInfo(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.FolderInfo result = new de.haumacher.imageServer.shared.model.FolderInfo();
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
		out.name(NAME__PROP);
		out.value(getName());
		out.name(TITLE__PROP);
		out.value(getTitle());
		out.name(SUB_TITLE__PROP);
		out.value(getSubTitle());
		out.name(EFFECTIVE_DATE__PROP);
		out.value(getEffectiveDate());
		out.name(KIND__PROP);
		getKind().writeTo(out);
		if (hasIndexPicture()) {
			out.name(INDEX_PICTURE__PROP);
			getIndexPicture().writeTo(out);
		}
		out.name(LINK__PROP);
		out.value(getLink());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case NAME__PROP: setName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case TITLE__PROP: setTitle(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case SUB_TITLE__PROP: setSubTitle(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case EFFECTIVE_DATE__PROP: setEffectiveDate(in.nextLong()); break;
			case KIND__PROP: setKind(de.haumacher.imageServer.shared.model.FolderKind.readFolderKind(in)); break;
			case INDEX_PICTURE__PROP: setIndexPicture(de.haumacher.imageServer.shared.model.ThumbnailInfo.readThumbnailInfo(in)); break;
			case LINK__PROP: setLink(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
