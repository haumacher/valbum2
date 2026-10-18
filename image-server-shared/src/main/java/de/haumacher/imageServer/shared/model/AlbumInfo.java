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

	/** @see #getKind() */
	private static final String KIND__PROP = "kind";

	/** @see #getTitle() */
	private static final String TITLE__PROP = "title";

	/** @see #getSubTitle() */
	private static final String SUB_TITLE__PROP = "subTitle";

	/** @see #getDate() */
	private static final String DATE__PROP = "date";

	/** @see #getEffectiveDate() */
	private static final String EFFECTIVE_DATE__PROP = "effectiveDate";

	/** @see #isFacesPending() */
	private static final String FACES_PENDING__PROP = "facesPending";

	/** @see #isPreparing() */
	private static final String PREPARING__PROP = "preparing";

	/** @see #getIndexPicture() */
	private static final String INDEX_PICTURE__PROP = "indexPicture";

	/** @see #isStarred() */
	private static final String STARRED__PROP = "starred";

	/** @see #getQuery() */
	private static final String QUERY__PROP = "query";

	/** @see #getSearchProblem() */
	private static final String SEARCH_PROBLEM__PROP = "searchProblem";

	/** @see #getStacks() */
	private static final String STACKS__PROP = "stacks";

	/** @see #getPlaceCaption() */
	private static final String PLACE_CAPTION__PROP = "placeCaption";

	/** @see #getParts() */
	private static final String PARTS__PROP = "parts";

	private de.haumacher.imageServer.shared.model.AlbumKind _kind = de.haumacher.imageServer.shared.model.AlbumKind.ALBUM;

	private String _title = "";

	private String _subTitle = "";

	private long _date = 0L;

	private long _effectiveDate = 0L;

	private boolean _facesPending = false;

	private boolean _preparing = false;

	private de.haumacher.imageServer.shared.model.ThumbnailInfo _indexPicture = null;

	private boolean _starred = false;

	private de.haumacher.imageServer.shared.model.SearchQuery _query = null;

	private de.haumacher.imageServer.shared.model.SearchProblem _searchProblem = de.haumacher.imageServer.shared.model.SearchProblem.NONE;

	private final java.util.List<de.haumacher.imageServer.shared.model.PhotoStack> _stacks = new java.util.ArrayList<>();

	private de.haumacher.imageServer.shared.model.PlaceCaption _placeCaption = de.haumacher.imageServer.shared.model.PlaceCaption.UNSET;

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
	 * Whether this is an ordinary album or the inbox of its space, see issues #131 and #226, a
	 * collection (issues #221 and #307) or a saved search (issue #227).
	 *
	 * <p>
	 * The inbox is derived on every read from where the folder lies — the one folder the space's
	 * <code>space.json</code> names is the inbox, nothing else is — and never stored. A collection
	 * is what the album holds, see {@link AlbumKind#COLLECTION}; a saved search is stated once, when
	 * it is created. What a client sends here is ignored.
	 * </p>
	 */
	public final de.haumacher.imageServer.shared.model.AlbumKind getKind() {
		return _kind;
	}

	/**
	 * @see #getKind()
	 */
	public de.haumacher.imageServer.shared.model.AlbumInfo setKind(de.haumacher.imageServer.shared.model.AlbumKind value) {
		internalSetKind(value);
		return this;
	}

	/** Internal setter for {@link #getKind()} without chain call utility. */
	protected final void internalSetKind(de.haumacher.imageServer.shared.model.AlbumKind value) {
		if (value == null) throw new IllegalArgumentException("Property 'kind' cannot be null.");
		_kind = value;
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
	 * Whether the server is still looking for faces in this album, see issue #124.
	 *
	 * <p>
	 * <code>true</code> while the space has the face index switched on
	 * (<code>space.json</code> <code>faces: on</code>) and not every photograph of this album is
	 * indexed yet, so that the application can say &quot;still looking&quot; and come back, exactly
	 * as it comes back for a video rendition that is not ready (issue #74). The
	 * {@link ImagePart#getFaces()} that are already known are answered meanwhile.
	 * </p>
	 *
	 * <p>
	 * On the album and not on a listing entry, because the album is what the face editor of issue
	 * #126 stands in: a listing shows folders, and a folder tile has nothing to do with a face.
	 * Derived on every read and never stored, exactly like {@link #getEffectiveDate()}, and answered
	 * only to a caller that is answered faces at all.
	 * </p>
	 */
	public final boolean isFacesPending() {
		return _facesPending;
	}

	/**
	 * @see #isFacesPending()
	 */
	public de.haumacher.imageServer.shared.model.AlbumInfo setFacesPending(boolean value) {
		internalSetFacesPending(value);
		return this;
	}

	/** Internal setter for {@link #isFacesPending()} without chain call utility. */
	protected final void internalSetFacesPending(boolean value) {
		_facesPending = value;
	}

	/**
	 * Whether the background work of the space has not recorded this folder yet, see issue #253.
	 *
	 * <p>
	 * A request never lists a folder nor opens a photograph: it answers what the background
	 * recorded of the folder (its analysis record beside the previews) and what the album's own
	 * <code>index.json</code> says. A folder the background has not reached yet is answered with
	 * the parts its <code>index.json</code> lists (an album an older build wrote), or empty, and
	 * this flag: the app says that the album is being prepared and asks again a moment later.
	 * </p>
	 *
	 * <p>
	 * Derived on every read and never stored, exactly like {@link #getEffectiveDate()}.
	 * </p>
	 */
	public final boolean isPreparing() {
		return _preparing;
	}

	/**
	 * @see #isPreparing()
	 */
	public de.haumacher.imageServer.shared.model.AlbumInfo setPreparing(boolean value) {
		internalSetPreparing(value);
		return this;
	}

	/** Internal setter for {@link #isPreparing()} without chain call utility. */
	protected final void internalSetPreparing(boolean value) {
		_preparing = value;
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
	 * Whether this album stands out in the folder it lies in, see issue #239.
	 *
	 * <p>
	 * A statement of whoever may edit the album, stored in its own <code>index.json</code> and
	 * written by the ordinary sidecar <code>PUT</code>, like its {@link #getTitle()}. The listing above
	 * answers it on the album's tile ({@link FolderInfo#isStarred()}), which the app draws four times
	 * the size of an ordinary one. A sidecar written before the field reads as not starred. An
	 * album and a collection carry it alike; an inbox is never a tile of a listing, so a star on it
	 * is shown nowhere.
	 * </p>
	 */
	public final boolean isStarred() {
		return _starred;
	}

	/**
	 * @see #isStarred()
	 */
	public de.haumacher.imageServer.shared.model.AlbumInfo setStarred(boolean value) {
		internalSetStarred(value);
		return this;
	}

	/** Internal setter for {@link #isStarred()} without chain call utility. */
	protected final void internalSetStarred(boolean value) {
		_starred = value;
	}

	/**
	 * What a saved search ({@link AlbumKind#SEARCH}, issue #227) looks for; <code>null</code> for
	 * every other kind of album.
	 *
	 * <p>
	 * <b>Stored</b> in the saved search's <code>index.json</code>, and the one thing it stores
	 * besides its title, date, star and picture. Answered to whoever may see the saved search, so
	 * that an editor can change it ("Edit search") with an ordinary sidecar <code>PUT</code>.
	 * </p>
	 */
	public final de.haumacher.imageServer.shared.model.SearchQuery getQuery() {
		return _query;
	}

	/**
	 * @see #getQuery()
	 */
	public de.haumacher.imageServer.shared.model.AlbumInfo setQuery(de.haumacher.imageServer.shared.model.SearchQuery value) {
		internalSetQuery(value);
		return this;
	}

	/** Internal setter for {@link #getQuery()} without chain call utility. */
	protected final void internalSetQuery(de.haumacher.imageServer.shared.model.SearchQuery value) {
		_query = value;
	}

	/**
	 * Checks, whether {@link #getQuery()} has a value.
	 */
	public final boolean hasQuery() {
		return _query != null;
	}

	/**
	 * Why a saved search (or the answer of <code>?action=search</code>) holding a
	 * {@link SearchSimilar} criterion found nothing, see issue #257: the photograph it compares with
	 * is not there for the caller, or has not been described yet. {@link SearchProblem#NONE} for
	 * every other answer.
	 *
	 * <p>
	 * Such an answer holds no photograph and is no error: the saved search opens with its title and
	 * says why, in the app's words. Derived on every read and <b>never stored</b>.
	 * </p>
	 */
	public final de.haumacher.imageServer.shared.model.SearchProblem getSearchProblem() {
		return _searchProblem;
	}

	/**
	 * @see #getSearchProblem()
	 */
	public de.haumacher.imageServer.shared.model.AlbumInfo setSearchProblem(de.haumacher.imageServer.shared.model.SearchProblem value) {
		internalSetSearchProblem(value);
		return this;
	}

	/** Internal setter for {@link #getSearchProblem()} without chain call utility. */
	protected final void internalSetSearchProblem(de.haumacher.imageServer.shared.model.SearchProblem value) {
		if (value == null) throw new IllegalArgumentException("Property 'searchProblem' cannot be null.");
		_searchProblem = value;
	}

	/**
	 * The bursts of this album, see issue #257: photographs that look alike and were taken within a
	 * few minutes of each other, which the app shows as one tile, the best shot on top.
	 *
	 * <p>
	 * <b>Derived on every read and never stored</b>, exactly like {@link #getEffectiveDate()}: the
	 * server chains the photographs of each section of the album (the parts between two
		 * {@link Heading}s) in the order they were taken, and two neighbours belong to one stack where
	 * the cosine of their pictures' vectors is at least the burst threshold and they were taken at
	 * most the burst gap apart (<code>ContentSearch#BURST_THRESHOLD</code>,
	 * <code>ContentSearch#BURST_GAP</code>). Only where the space has the content search on and the
	 * photographs have their vectors; a video, a photograph without a vector or without a date, a
	 * group's member and the trash never join a stack. Built from what the caller is answered: a
	 * stack never names a photograph the caller is not shown, and holds at least two.
	 * </p>
	 *
	 * <p>
	 * Presentation only: a stack hides nothing from the viewer, the downloads, the counts or the
	 * index picture, and an ordinary or a saved search, a collection and the inbox carry none. A
	 * reference ({@link ImagePart#getRef()}, issue #307) never joins a stack: a burst is a run of shots
	 * of the album they lie in, which stacks them there. A client that writes the album back has the
	 * field cleared before the sidecar is written.
	 * </p>
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.PhotoStack> getStacks() {
		return _stacks;
	}

	/**
	 * @see #getStacks()
	 */
	public de.haumacher.imageServer.shared.model.AlbumInfo setStacks(java.util.List<? extends de.haumacher.imageServer.shared.model.PhotoStack> value) {
		internalSetStacks(value);
		return this;
	}

	/** Internal setter for {@link #getStacks()} without chain call utility. */
	protected final void internalSetStacks(java.util.List<? extends de.haumacher.imageServer.shared.model.PhotoStack> value) {
		if (value == null) throw new IllegalArgumentException("Property 'stacks' cannot be null.");
		_stacks.clear();
		_stacks.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getStacks()} list.
	 */
	public de.haumacher.imageServer.shared.model.AlbumInfo addStack(de.haumacher.imageServer.shared.model.PhotoStack value) {
		internalAddStack(value);
		return this;
	}

	/** Implementation of {@link #addStack(de.haumacher.imageServer.shared.model.PhotoStack)} without chain call utility. */
	protected final void internalAddStack(de.haumacher.imageServer.shared.model.PhotoStack value) {
		_stacks.add(value);
	}

	/**
	 * Removes a value from the {@link #getStacks()} list.
	 */
	public final void removeStack(de.haumacher.imageServer.shared.model.PhotoStack value) {
		_stacks.remove(value);
	}

	/**
	 * Whether the photographs of this album show the place they were taken in as their caption
	 * where they have no description.
	 *
	 * <p>
	 * {@link PlaceCaption#UNSET} means <b>shown</b>: an album written before the field shows the
	 * places, and an album where they are unwanted says {@link PlaceCaption#HIDDEN} once. A
	 * photograph may say otherwise for itself, see {@link ImagePart#getPlaceCaption()}. Stored in the
	 * album's <code>index.json</code> and written by the ordinary sidecar <code>PUT</code>, like
	 * its {@link #getTitle()}; a collection and a saved search carry it as their own statement.
	 * </p>
	 */
	public final de.haumacher.imageServer.shared.model.PlaceCaption getPlaceCaption() {
		return _placeCaption;
	}

	/**
	 * @see #getPlaceCaption()
	 */
	public de.haumacher.imageServer.shared.model.AlbumInfo setPlaceCaption(de.haumacher.imageServer.shared.model.PlaceCaption value) {
		internalSetPlaceCaption(value);
		return this;
	}

	/** Internal setter for {@link #getPlaceCaption()} without chain call utility. */
	protected final void internalSetPlaceCaption(de.haumacher.imageServer.shared.model.PlaceCaption value) {
		if (value == null) throw new IllegalArgumentException("Property 'placeCaption' cannot be null.");
		_placeCaption = value;
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
	public de.haumacher.imageServer.shared.model.AlbumInfo setRights(java.util.List<? extends de.haumacher.imageServer.shared.model.RightName> value) {
		internalSetRights(value);
		return this;
	}

	@Override
	public de.haumacher.imageServer.shared.model.AlbumInfo addRight(de.haumacher.imageServer.shared.model.RightName value) {
		internalAddRight(value);
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
		out.name(KIND__PROP);
		getKind().writeTo(out);
		out.name(TITLE__PROP);
		out.value(getTitle());
		out.name(SUB_TITLE__PROP);
		out.value(getSubTitle());
		out.name(DATE__PROP);
		out.value(getDate());
		out.name(EFFECTIVE_DATE__PROP);
		out.value(getEffectiveDate());
		out.name(FACES_PENDING__PROP);
		out.value(isFacesPending());
		out.name(PREPARING__PROP);
		out.value(isPreparing());
		if (hasIndexPicture()) {
			out.name(INDEX_PICTURE__PROP);
			getIndexPicture().writeTo(out);
		}
		out.name(STARRED__PROP);
		out.value(isStarred());
		if (hasQuery()) {
			out.name(QUERY__PROP);
			getQuery().writeTo(out);
		}
		out.name(SEARCH_PROBLEM__PROP);
		getSearchProblem().writeTo(out);
		out.name(STACKS__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.PhotoStack x : getStacks()) {
			x.writeTo(out);
		}
		out.endArray();
		out.name(PLACE_CAPTION__PROP);
		getPlaceCaption().writeTo(out);
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
			case KIND__PROP: setKind(de.haumacher.imageServer.shared.model.AlbumKind.readAlbumKind(in)); break;
			case TITLE__PROP: setTitle(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case SUB_TITLE__PROP: setSubTitle(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case DATE__PROP: setDate(in.nextLong()); break;
			case EFFECTIVE_DATE__PROP: setEffectiveDate(in.nextLong()); break;
			case FACES_PENDING__PROP: setFacesPending(in.nextBoolean()); break;
			case PREPARING__PROP: setPreparing(in.nextBoolean()); break;
			case INDEX_PICTURE__PROP: setIndexPicture(de.haumacher.imageServer.shared.model.ThumbnailInfo.readThumbnailInfo(in)); break;
			case STARRED__PROP: setStarred(in.nextBoolean()); break;
			case QUERY__PROP: setQuery(de.haumacher.imageServer.shared.model.SearchQuery.readSearchQuery(in)); break;
			case SEARCH_PROBLEM__PROP: setSearchProblem(de.haumacher.imageServer.shared.model.SearchProblem.readSearchProblem(in)); break;
			case STACKS__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addStack(de.haumacher.imageServer.shared.model.PhotoStack.readPhotoStack(in));
				}
				in.endArray();
			}
			break;
			case PLACE_CAPTION__PROP: setPlaceCaption(de.haumacher.imageServer.shared.model.PlaceCaption.readPlaceCaption(in)); break;
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
