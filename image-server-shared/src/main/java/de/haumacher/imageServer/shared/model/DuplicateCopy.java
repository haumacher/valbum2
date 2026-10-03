package de.haumacher.imageServer.shared.model;

/**
 * One copy of a {@link DuplicateGroup}: a photograph of one album.
 */
public class DuplicateCopy extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.DuplicateCopy} instance.
	 */
	public static de.haumacher.imageServer.shared.model.DuplicateCopy create() {
		return new de.haumacher.imageServer.shared.model.DuplicateCopy();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.DuplicateCopy} type in JSON format. */
	public static final String DUPLICATE_COPY__TYPE = "DuplicateCopy";

	/** @see #getAlbum() */
	private static final String ALBUM__PROP = "album";

	/** @see #getTitle() */
	private static final String TITLE__PROP = "title";

	/** @see #getAlbumDate() */
	private static final String ALBUM_DATE__PROP = "albumDate";

	/** @see #getName() */
	private static final String NAME__PROP = "name";

	private String _album = "";

	private String _title = "";

	private long _albumDate = 0L;

	private String _name = "";

	/**
	 * Creates a {@link DuplicateCopy} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.DuplicateCopy#create()
	 */
	protected DuplicateCopy() {
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
	public de.haumacher.imageServer.shared.model.DuplicateCopy setAlbum(String value) {
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
	public de.haumacher.imageServer.shared.model.DuplicateCopy setTitle(String value) {
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
	public de.haumacher.imageServer.shared.model.DuplicateCopy setAlbumDate(long value) {
		internalSetAlbumDate(value);
		return this;
	}

	/** Internal setter for {@link #getAlbumDate()} without chain call utility. */
	protected final void internalSetAlbumDate(long value) {
		_albumDate = value;
	}

	/**
	 * The name of the photograph in the album, the name it is addressed and opened by: of a raw
	 * standing beside its JPEG (issue #191) the JPEG's, because the two are one photograph.
	 */
	public final String getName() {
		return _name;
	}

	/**
	 * @see #getName()
	 */
	public de.haumacher.imageServer.shared.model.DuplicateCopy setName(String value) {
		internalSetName(value);
		return this;
	}

	/** Internal setter for {@link #getName()} without chain call utility. */
	protected final void internalSetName(String value) {
		_name = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.DuplicateCopy readDuplicateCopy(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.DuplicateCopy result = new de.haumacher.imageServer.shared.model.DuplicateCopy();
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
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case ALBUM__PROP: setAlbum(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case TITLE__PROP: setTitle(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case ALBUM_DATE__PROP: setAlbumDate(in.nextLong()); break;
			case NAME__PROP: setName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
