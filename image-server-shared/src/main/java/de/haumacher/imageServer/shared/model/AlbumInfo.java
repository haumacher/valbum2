package de.haumacher.imageServer.shared.model;

/**
 * {@link Resource} describing a collection of {@link AlbumPart}s.
 */
public class AlbumInfo extends FolderResource {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.AlbumInfo} instance.
	 */
	public static de.haumacher.imageServer.shared.model.AlbumInfo create() {
		return new de.haumacher.imageServer.shared.model.AlbumInfo();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.AlbumInfo} type in JSON format. */
	public static final String ALBUM_INFO__TYPE = "AlbumInfo";

	/** @see #getTitle() */
	private static final String TITLE__PROP = "title";

	/** @see #getSubTitle() */
	private static final String SUB_TITLE__PROP = "subTitle";

	/** @see #getDate() */
	private static final String DATE__PROP = "date";

	/** @see #getEffectiveDate() */
	private static final String EFFECTIVE_DATE__PROP = "effectiveDate";

	/** @see #getIndexPicture() */
	private static final String INDEX_PICTURE__PROP = "indexPicture";

	/** @see #getParts() */
	private static final String PARTS__PROP = "parts";

	private String _title = "";

	private String _subTitle = "";

	private long _date = 0L;

	private long _effectiveDate = 0L;

	private de.haumacher.imageServer.shared.model.ThumbnailInfo _indexPicture = null;

	private final java.util.List<de.haumacher.imageServer.shared.model.AlbumPart> _parts = new java.util.ArrayList<>();

	private transient final java.util.Map<String, de.haumacher.imageServer.shared.model.ImagePart> _imageByName = new java.util.HashMap<>();

	private transient int _minRating = 0;

	/**
	 * Creates a {@link AlbumInfo} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.AlbumInfo#create()
	 */
	protected AlbumInfo() {
		super();
	}

	@Override
	public TypeKind kind() {
		return TypeKind.ALBUM_INFO;
	}

	/**
	 * The title of this album.
	 */
	public final String getTitle() {
		return _title;
	}

	/**
	 * @see #getTitle()
	 */
	public de.haumacher.imageServer.shared.model.AlbumInfo setTitle(String value) {
		internalSetTitle(value);
		return this;
	}

	/** Internal setter for {@link #getTitle()} without chain call utility. */
	protected final void internalSetTitle(String value) {
		_title = value;
	}

	/**
	 * The subtitle of this album.
	 */
	public final String getSubTitle() {
		return _subTitle;
	}

	/**
	 * @see #getSubTitle()
	 */
	public de.haumacher.imageServer.shared.model.AlbumInfo setSubTitle(String value) {
		internalSetSubTitle(value);
		return this;
	}

	/** Internal setter for {@link #getSubTitle()} without chain call utility. */
	protected final void internalSetSubTitle(String value) {
		_subTitle = value;
	}

	/**
	 * The date this album is filed under, in milliseconds since the epoch, <code>0</code> when the
	 * author has set none.
	 *
	 * <p>
	 * The explicit date and only the explicit one: this is what <code>index.json</code> stores and
	 * what the album's properties edit. What the album is actually sorted and placed by is
	 * {@link #getEffectiveDate()}, see issue #48.
	 * </p>
	 */
	public final long getDate() {
		return _date;
	}

	/**
	 * @see #getDate()
	 */
	public de.haumacher.imageServer.shared.model.AlbumInfo setDate(long value) {
		internalSetDate(value);
		return this;
	}

	/** Internal setter for {@link #getDate()} without chain call utility. */
	protected final void internalSetDate(long value) {
		_date = value;
	}

	/**
	 * The date this album is sorted and placed by, in milliseconds since the epoch, <code>0</code>
	 * when nothing says when it happened.
	 *
	 * <p>
	 * Derived by the server on every read: the explicit {@link #getDate()}, else the leading date of the
	 * album's folder name (<code>YYYY-MM-DD</code>, <code>YYYY-MM</code> or <code>YYYY</code>),
	 * else the earliest {@link ImagePart#getDate()} of the images the album holds.
	 * </p>
	 *
	 * <p>
	 * Derived data is never stored: the server clears this field before a sidecar is written, so
	 * that a round trip through a client cannot freeze a derived date into <code>index.json</code>.
	 * A sidecar that carries one nevertheless is read without complaint and answered with the
	 * derived value.
	 * </p>
	 */
	public final long getEffectiveDate() {
		return _effectiveDate;
	}

	/**
	 * @see #getEffectiveDate()
	 */
	public de.haumacher.imageServer.shared.model.AlbumInfo setEffectiveDate(long value) {
		internalSetEffectiveDate(value);
		return this;
	}

	/** Internal setter for {@link #getEffectiveDate()} without chain call utility. */
	protected final void internalSetEffectiveDate(long value) {
		_effectiveDate = value;
	}

	/**
	 * Description of the image used to display this whole album in a listing.
	 */
	public final de.haumacher.imageServer.shared.model.ThumbnailInfo getIndexPicture() {
		return _indexPicture;
	}

	/**
	 * @see #getIndexPicture()
	 */
	public de.haumacher.imageServer.shared.model.AlbumInfo setIndexPicture(de.haumacher.imageServer.shared.model.ThumbnailInfo value) {
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
	 * The list of images in this album.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.AlbumPart> getParts() {
		return _parts;
	}

	/**
	 * @see #getParts()
	 */
	public de.haumacher.imageServer.shared.model.AlbumInfo setParts(java.util.List<? extends de.haumacher.imageServer.shared.model.AlbumPart> value) {
		internalSetParts(value);
		return this;
	}

	/** Internal setter for {@link #getParts()} without chain call utility. */
	protected final void internalSetParts(java.util.List<? extends de.haumacher.imageServer.shared.model.AlbumPart> value) {
		if (value == null) throw new IllegalArgumentException("Property 'parts' cannot be null.");
		_parts.clear();
		_parts.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getParts()} list.
	 */
	public de.haumacher.imageServer.shared.model.AlbumInfo addPart(de.haumacher.imageServer.shared.model.AlbumPart value) {
		internalAddPart(value);
		return this;
	}

	/** Implementation of {@link #addPart(de.haumacher.imageServer.shared.model.AlbumPart)} without chain call utility. */
	protected final void internalAddPart(de.haumacher.imageServer.shared.model.AlbumPart value) {
		_parts.add(value);
	}

	/**
	 * Removes a value from the {@link #getParts()} list.
	 */
	public final void removePart(de.haumacher.imageServer.shared.model.AlbumPart value) {
		_parts.remove(value);
	}

	/**
	 * All {@link ImagePart}s indexed by their {@link ImagePart#getName()}.
	 */
	public final java.util.Map<String, de.haumacher.imageServer.shared.model.ImagePart> getImageByName() {
		return _imageByName;
	}

	/**
	 * @see #getImageByName()
	 */
	public de.haumacher.imageServer.shared.model.AlbumInfo setImageByName(java.util.Map<String, de.haumacher.imageServer.shared.model.ImagePart> value) {
		internalSetImageByName(value);
		return this;
	}

	/** Internal setter for {@link #getImageByName()} without chain call utility. */
	protected final void internalSetImageByName(java.util.Map<String, de.haumacher.imageServer.shared.model.ImagePart> value) {
		if (value == null) throw new IllegalArgumentException("Property 'imageByName' cannot be null.");
		_imageByName.clear();
		_imageByName.putAll(value);
	}

	/**
	 * Adds a key value pair to the {@link #getImageByName()} map.
	 */
	public de.haumacher.imageServer.shared.model.AlbumInfo putImageByName(String key, de.haumacher.imageServer.shared.model.ImagePart value) {
		internalPutImageByName(key, value);
		return this;
	}

	/** Implementation of {@link #putImageByName(String, de.haumacher.imageServer.shared.model.ImagePart)} without chain call utility. */
	protected final void  internalPutImageByName(String key, de.haumacher.imageServer.shared.model.ImagePart value) {
		if (_imageByName.containsKey(key)) {
			throw new IllegalArgumentException("Property 'imageByName' already contains a value for key '" + key + "'.");
		}
		_imageByName.put(key, value);
	}

	/**
	 * Removes a key from the {@link #getImageByName()} map.
	 */
	public final void removeImageByName(String key) {
		_imageByName.remove(key);
	}

	/**
	 * The minimum {@link ImagePart#getRating()} of an {@link ImagePart} to be displayed.
	 *
	 * <p>The value is set by the UI to remember the current display settings of an {@link AlbumInfo} while browsing its contents</p>
	 */
	public final int getMinRating() {
		return _minRating;
	}

	/**
	 * @see #getMinRating()
	 */
	public de.haumacher.imageServer.shared.model.AlbumInfo setMinRating(int value) {
		internalSetMinRating(value);
		return this;
	}

	/** Internal setter for {@link #getMinRating()} without chain call utility. */
	protected final void internalSetMinRating(int value) {
		_minRating = value;
	}

	@Override
	public de.haumacher.imageServer.shared.model.AlbumInfo setPath(String value) {
		internalSetPath(value);
		return this;
	}

	@Override
	public String jsonType() {
		return ALBUM_INFO__TYPE;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.AlbumInfo readAlbumInfo(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.AlbumInfo result = new de.haumacher.imageServer.shared.model.AlbumInfo();
		result.readContent(in);
		return result;
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name(TITLE__PROP);
		out.value(getTitle());
		out.name(SUB_TITLE__PROP);
		out.value(getSubTitle());
		out.name(DATE__PROP);
		out.value(getDate());
		out.name(EFFECTIVE_DATE__PROP);
		out.value(getEffectiveDate());
		if (hasIndexPicture()) {
			out.name(INDEX_PICTURE__PROP);
			getIndexPicture().writeTo(out);
		}
		out.name(PARTS__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.AlbumPart x : getParts()) {
			x.writeTo(out);
		}
		out.endArray();
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case TITLE__PROP: setTitle(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case SUB_TITLE__PROP: setSubTitle(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case DATE__PROP: setDate(in.nextLong()); break;
			case EFFECTIVE_DATE__PROP: setEffectiveDate(in.nextLong()); break;
			case INDEX_PICTURE__PROP: setIndexPicture(de.haumacher.imageServer.shared.model.ThumbnailInfo.readThumbnailInfo(in)); break;
			case PARTS__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addPart(de.haumacher.imageServer.shared.model.AlbumPart.readAlbumPart(in));
				}
				in.endArray();
			}
			break;
			default: super.readField(in, field);
		}
	}

	@Override
	public <R,A,E extends Throwable> R visit(de.haumacher.imageServer.shared.model.FolderResource.Visitor<R,A,E> v, A arg) throws E {
		return v.visit(this, arg);
	}

}
