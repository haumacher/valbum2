package de.haumacher.imageServer.shared.model;

/**
 * {@link Resource} describing collection of {@link ImageInfo}s found in a directory.
 */
public class AlbumInfo extends Resource {

	/**
	 * Creates a {@link AlbumInfo} instance.
	 */
	public static AlbumInfo create() {
		return new AlbumInfo();
	}

	/**
	 * Creates a {@link AlbumInfo} instance.
	 *
	 * @see #create()
	 */
	protected AlbumInfo() {
		super();
	}

	private String _title = "";

	private String _subTitle = "";

	private ThumbnailInfo _indexPicture = null;

	private final java.util.List<ImageInfo> _images = new java.util.ArrayList<>();

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
	public final java.util.List<ImageInfo> getImages() {
		return _images;
	}

	/**
	 * @see #getImages()
	 */
	public final AlbumInfo setImages(java.util.List<ImageInfo> value) {
		_images.clear();
		_images.addAll(value);
		return this;
	}

	/**
	 * Adds a value to the {@link #getImages()} list.
	 */
	public final AlbumInfo addImage(ImageInfo value) {
		_images.add(value);
		return this;
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
	public String jsonType() {
		return "AlbumInfo";
	}

	@Override
	public Object get(String field) {
		switch (field) {
			case "title": return getTitle();
			case "subTitle": return getSubTitle();
			case "indexPicture": return getIndexPicture();
			case "images": return getImages();
			default: return super.get(field);
		}
	}

	@Override
	public void set(String field, Object value) {
		switch (field) {
			case "title": setTitle((String) value); break;
			case "subTitle": setSubTitle((String) value); break;
			case "indexPicture": setIndexPicture((ThumbnailInfo) value); break;
			case "images": setImages((java.util.List<ImageInfo>) value); break;
			default: super.set(field, value); break;
		}
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name("title");
		out.value(getTitle());
		out.name("subTitle");
		out.value(getSubTitle());
		if (hasIndexPicture()) {
			out.name("indexPicture");
			getIndexPicture().writeTo(out);
		}
		out.name("images");
		out.beginArray();
		for (ImageInfo x : getImages()) {
			x.writeContent(out);
		}
		out.endArray();
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case "title": setTitle(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case "subTitle": setSubTitle(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case "indexPicture": setIndexPicture(ThumbnailInfo.readThumbnailInfo(in)); break;
			case "images": {
				in.beginArray();
				while (in.hasNext()) {
					addImage(ImageInfo.readImageInfo(in));
				}
				in.endArray();
			}
			break;
			default: super.readField(in, field);
		}
	}

	@Override
	public int typeId() {
		return 2;
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.binary.DataWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name(2);
		out.value(getTitle());
		out.name(3);
		out.value(getSubTitle());
		if (hasIndexPicture()) {
			out.name(4);
			getIndexPicture().writeTo(out);
		}
		out.name(5);
		{
			java.util.List<ImageInfo> values = getImages();
			out.beginArray(de.haumacher.msgbuf.binary.DataType.OBJECT, values.size());
			for (ImageInfo x : values) {
				x.writeTo(out);
			}
			out.endArray();
		}
	}

	@Override
	protected void readField(de.haumacher.msgbuf.binary.DataReader in, int field) throws java.io.IOException {
		switch (field) {
			case 2: setTitle(in.nextString()); break;
			case 3: setSubTitle(in.nextString()); break;
			case 4: setIndexPicture(ThumbnailInfo.readThumbnailInfo(in)); break;
			case 5: {
				in.beginArray();
				while (in.hasNext()) {
					addImage(ImageInfo.readImageInfo(in));
				}
				in.endArray();
			}
			break;
			default: super.readField(in, field);
		}
	}

	/** Reads a new instance from the given reader. */
	public static AlbumInfo readAlbumInfo(de.haumacher.msgbuf.binary.DataReader in) throws java.io.IOException {
		in.beginObject();
		AlbumInfo result = new AlbumInfo();
		while (in.hasNext()) {
			int field = in.nextName();
			result.readField(in, field);
		}
		in.endObject();
		return result;
	}

	@Override
	public <R,A> R visit(Resource.Visitor<R,A> v, A arg) {
		return v.visit(this, arg);
	}

}
