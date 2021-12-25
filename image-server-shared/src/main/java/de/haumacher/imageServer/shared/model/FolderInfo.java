package de.haumacher.imageServer.shared.model;

/**
 * Part of a {@link ListingInfo} describing a reference to a single album directory.
 */
public class FolderInfo extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link FolderInfo} instance.
	 */
	public static FolderInfo create() {
		return new FolderInfo();
	}

	/** Identifier for the {@link FolderInfo} type in JSON format. */
	public static final String FOLDER_INFO__TYPE = "FolderInfo";

	/** @see #getName() */
	private static final String NAME = "name";

	/** @see #getTitle() */
	private static final String TITLE = "title";

	/** @see #getSubTitle() */
	private static final String SUB_TITLE = "subTitle";

	/** @see #getIndexPicture() */
	private static final String INDEX_PICTURE = "indexPicture";

	private String _name = "";

	private String _title = "";

	private String _subTitle = "";

	private ThumbnailInfo _indexPicture = null;

	/**
	 * Creates a {@link FolderInfo} instance.
	 *
	 * @see #create()
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
	public FolderInfo setName(String value) {
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
	public FolderInfo setTitle(String value) {
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
	public FolderInfo setSubTitle(String value) {
		internalSetSubTitle(value);
		return this;
	}
	/** Internal setter for {@link #getSubTitle()} without chain call utility. */
	protected final void internalSetSubTitle(String value) {
		_subTitle = value;
	}


	/**
	 * The index picture of the {@link AlbumInfo} referenced by this {@link FolderInfo}.
	 */
	public final ThumbnailInfo getIndexPicture() {
		return _indexPicture;
	}

	/**
	 * @see #getIndexPicture()
	 */
	public FolderInfo setIndexPicture(ThumbnailInfo value) {
		internalSetIndexPicture(value);
		return this;
	}
	/** Internal setter for {@link #getIndexPicture()} without chain call utility. */
	protected final void internalSetIndexPicture(ThumbnailInfo value) {
		_indexPicture = value;
	}


	/**
	 * Checks, whether {@link #getIndexPicture()} has a value.
	 */
	public final boolean hasIndexPicture() {
		return _indexPicture != null;
	}

	/** The type identifier for this concrete subtype. */
	public String jsonType() {
		return FOLDER_INFO__TYPE;
	}

	/** Reads a new instance from the given reader. */
	public static FolderInfo readFolderInfo(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		FolderInfo result = new FolderInfo();
		in.beginObject();
		result.readFields(in);
		in.endObject();
		return result;
	}

	@Override
	public final void writeTo(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		writeContent(out);
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name(NAME);
		out.value(getName());
		out.name(TITLE);
		out.value(getTitle());
		out.name(SUB_TITLE);
		out.value(getSubTitle());
		if (hasIndexPicture()) {
			out.name(INDEX_PICTURE);
			getIndexPicture().writeTo(out);
		}
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case NAME: setName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case TITLE: setTitle(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case SUB_TITLE: setSubTitle(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case INDEX_PICTURE: setIndexPicture(de.haumacher.imageServer.shared.model.ThumbnailInfo.readThumbnailInfo(in)); break;
			default: super.readField(in, field);
		}
	}

}
