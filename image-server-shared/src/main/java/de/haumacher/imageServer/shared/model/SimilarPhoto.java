package de.haumacher.imageServer.shared.model;

/**
 * One photograph of a {@link SimilarGroup}.
 */
public class SimilarPhoto extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.SimilarPhoto} instance.
	 */
	public static de.haumacher.imageServer.shared.model.SimilarPhoto create() {
		return new de.haumacher.imageServer.shared.model.SimilarPhoto();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.SimilarPhoto} type in JSON format. */
	public static final String SIMILAR_PHOTO__TYPE = "SimilarPhoto";

	/** @see #getAlbum() */
	private static final String ALBUM__PROP = "album";

	/** @see #getTitle() */
	private static final String TITLE__PROP = "title";

	/** @see #getAlbumDate() */
	private static final String ALBUM_DATE__PROP = "albumDate";

	/** @see #getName() */
	private static final String NAME__PROP = "name";

	/** @see #getImage() */
	private static final String IMAGE__PROP = "image";

	private String _album = "";

	private String _title = "";

	private long _albumDate = 0L;

	private String _name = "";

	private de.haumacher.imageServer.shared.model.ImagePart _image = null;

	/**
	 * Creates a {@link SimilarPhoto} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.SimilarPhoto#create()
	 */
	protected SimilarPhoto() {
		super();
	}

	/**
	 * The album's path relative to the root of the caller's space, segments separated by
	 * <code>/</code>; the empty string for the root itself.
	 */
	public final String getAlbum() {
		return _album;
	}

	/**
	 * @see #getAlbum()
	 */
	public de.haumacher.imageServer.shared.model.SimilarPhoto setAlbum(String value) {
		internalSetAlbum(value);
		return this;
	}

	/** Internal setter for {@link #getAlbum()} without chain call utility. */
	protected final void internalSetAlbum(String value) {
		_album = value;
	}

	/**
	 * The album's title, empty where it has none (the app then shows the folder name).
	 */
	public final String getTitle() {
		return _title;
	}

	/**
	 * @see #getTitle()
	 */
	public de.haumacher.imageServer.shared.model.SimilarPhoto setTitle(String value) {
		internalSetTitle(value);
		return this;
	}

	/** Internal setter for {@link #getTitle()} without chain call utility. */
	protected final void internalSetTitle(String value) {
		_title = value;
	}

	/**
	 * The album's {@link AlbumInfo#getEffectiveDate()}, 0 where it has none.
	 */
	public final long getAlbumDate() {
		return _albumDate;
	}

	/**
	 * @see #getAlbumDate()
	 */
	public de.haumacher.imageServer.shared.model.SimilarPhoto setAlbumDate(long value) {
		internalSetAlbumDate(value);
		return this;
	}

	/** Internal setter for {@link #getAlbumDate()} without chain call utility. */
	protected final void internalSetAlbumDate(long value) {
		_albumDate = value;
	}

	/**
	 * The name of the photograph in the album, the name it is addressed and opened by.
	 */
	public final String getName() {
		return _name;
	}

	/**
	 * @see #getName()
	 */
	public de.haumacher.imageServer.shared.model.SimilarPhoto setName(String value) {
		internalSetName(value);
		return this;
	}

	/** Internal setter for {@link #getName()} without chain call utility. */
	protected final void internalSetName(String value) {
		_name = value;
	}

	/**
	 * The photograph, as much as its thumbnail needs: name, kind, date, size, orientation, crop and
	 * poster &mdash; nothing else of the album's description.
	 */
	public final de.haumacher.imageServer.shared.model.ImagePart getImage() {
		return _image;
	}

	/**
	 * @see #getImage()
	 */
	public de.haumacher.imageServer.shared.model.SimilarPhoto setImage(de.haumacher.imageServer.shared.model.ImagePart value) {
		internalSetImage(value);
		return this;
	}

	/** Internal setter for {@link #getImage()} without chain call utility. */
	protected final void internalSetImage(de.haumacher.imageServer.shared.model.ImagePart value) {
		_image = value;
	}

	/**
	 * Checks, whether {@link #getImage()} has a value.
	 */
	public final boolean hasImage() {
		return _image != null;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.SimilarPhoto readSimilarPhoto(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.SimilarPhoto result = new de.haumacher.imageServer.shared.model.SimilarPhoto();
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
		out.name(ALBUM__PROP);
		out.value(getAlbum());
		out.name(TITLE__PROP);
		out.value(getTitle());
		out.name(ALBUM_DATE__PROP);
		out.value(getAlbumDate());
		out.name(NAME__PROP);
		out.value(getName());
		if (hasImage()) {
			out.name(IMAGE__PROP);
			getImage().writeContent(out);
		}
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case ALBUM__PROP: setAlbum(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case TITLE__PROP: setTitle(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case ALBUM_DATE__PROP: setAlbumDate(in.nextLong()); break;
			case NAME__PROP: setName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case IMAGE__PROP: setImage(de.haumacher.imageServer.shared.model.ImagePart.readImagePart(in)); break;
			default: super.readField(in, field);
		}
	}

}
