package de.haumacher.imageServer.shared.model;

/**
 * {@link Resource} describing a collection of {@link AlbumPart}s.
 */
public class AlbumInfo extends FolderResource<AlbumInfo> {

	/**
	 * Creates a {@link AlbumInfo} instance.
	 */
	public static AlbumInfo create() {
		return new AlbumInfo();
	}

	/** Identifier for the {@link AlbumInfo} type in JSON format. */
	public static final String ALBUM_INFO__TYPE = "AlbumInfo";

	/** @see #getTitle() */
	private static final String TITLE = "title";

	/** @see #getSubTitle() */
	private static final String SUB_TITLE = "subTitle";

	/** @see #getIndexPicture() */
	private static final String INDEX_PICTURE = "indexPicture";

	/** @see #getParts() */
	private static final String PARTS = "parts";

	/** @see #getImageByName() */
	private static final String IMAGE_BY_NAME = "imageByName";

	/** @see #getMinRating() */
	private static final String MIN_RATING = "minRating";

	private String _title = "";

	private String _subTitle = "";

	private ThumbnailInfo _indexPicture = null;

	private final java.util.List<AlbumPart<?>> _parts = new java.util.ArrayList<>();

	private transient final java.util.Map<String, ImagePart> _imageByName = new java.util.HashMap<>();

	private transient int _minRating = 0;

	/**
	 * Creates a {@link AlbumInfo} instance.
	 *
	 * @see #create()
	 */
	protected AlbumInfo() {
		super();
	}

	@Override
	protected final AlbumInfo self() {
		return this;
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
	public final AlbumInfo setTitle(String value) {
		_title = value;
		return this;
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
	public final AlbumInfo setSubTitle(String value) {
		_subTitle = value;
		return this;
	}

	/**
	 * Description of the image used to display this whole album in a listing.
	 */
	public final ThumbnailInfo getIndexPicture() {
		return _indexPicture;
	}

	/**
	 * @see #getIndexPicture()
	 */
	public final AlbumInfo setIndexPicture(ThumbnailInfo value) {
		_indexPicture = value;
		return this;
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
	public final java.util.List<AlbumPart<?>> getParts() {
		return _parts;
	}

	/**
	 * @see #getParts()
	 */
	public final AlbumInfo setParts(java.util.List<AlbumPart<?>> value) {
		if (value == null) throw new IllegalArgumentException("Property 'parts' cannot be null.");
		_parts.clear();
		_parts.addAll(value);
		return this;
	}

	/**
	 * Adds a value to the {@link #getParts()} list.
	 */
	public final AlbumInfo addPart(AlbumPart<?> value) {
		_parts.add(value);
		return this;
	}

	/**
	 * Removes a value from the {@link #getParts()} list.
	 */
	public final AlbumInfo removePart(AlbumPart<?> value) {
		_parts.remove(value);
		return this;
	}

	/**
	 * All {@link ImageInfo}s indexed by their {@link ImageInfo#name}.
	 */
	public final java.util.Map<String, ImagePart> getImageByName() {
		return _imageByName;
	}

	/**
	 * @see #getImageByName()
	 */
	public final AlbumInfo setImageByName(java.util.Map<String, ImagePart> value) {
		if (value == null) throw new IllegalArgumentException("Property 'imageByName' cannot be null.");
		_imageByName.clear();
		_imageByName.putAll(value);
		return this;
	}

	/**
	 * Adds a value to the {@link #getImageByName()} map.
	 */
	public final AlbumInfo putImageByName(String key, ImagePart value) {
		if (_imageByName.containsKey(key)) {
			throw new IllegalArgumentException("Property 'imageByName' already contains a value for key '" + key + "'.");
		}
		_imageByName.put(key, value);
		return this;
	}

	/**
	 * Removes a key from the {@link #getImageByName()} map.
	 */
	public final AlbumInfo removeImageByName(String key) {
		_imageByName.remove(key);
		return this;
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
	public final AlbumInfo setMinRating(int value) {
		_minRating = value;
		return this;
	}

	@Override
	public String jsonType() {
		return ALBUM_INFO__TYPE;
	}

	/** Reads a new instance from the given reader. */
	public static AlbumInfo readAlbumInfo(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		AlbumInfo result = new AlbumInfo();
		in.beginObject();
		result.readFields(in);
		in.endObject();
		return result;
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name(TITLE);
		out.value(getTitle());
		out.name(SUB_TITLE);
		out.value(getSubTitle());
		if (hasIndexPicture()) {
			out.name(INDEX_PICTURE);
			getIndexPicture().writeTo(out);
		}
		out.name(PARTS);
		out.beginArray();
		for (AlbumPart<?> x : getParts()) {
			x.writeTo(out);
		}
		out.endArray();
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case TITLE: setTitle(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case SUB_TITLE: setSubTitle(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case INDEX_PICTURE: setIndexPicture(de.haumacher.imageServer.shared.model.ThumbnailInfo.readThumbnailInfo(in)); break;
			case PARTS: {
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
	public <R,A,E extends Throwable> R visit(FolderResource.Visitor<R,A,E> v, A arg) throws E {
		return v.visit(this, arg);
	}

}
