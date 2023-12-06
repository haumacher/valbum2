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

	/** @see #getIndexPicture() */
	private static final String INDEX_PICTURE__PROP = "indexPicture";

	private String _name = "";

	private String _title = "";

	private String _subTitle = "";

	private de.haumacher.imageServer.shared.model.ThumbnailInfo _indexPicture = null;

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
		if (hasIndexPicture()) {
			out.name(INDEX_PICTURE__PROP);
			getIndexPicture().writeTo(out);
		}
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case NAME__PROP: setName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case TITLE__PROP: setTitle(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case SUB_TITLE__PROP: setSubTitle(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case INDEX_PICTURE__PROP: setIndexPicture(de.haumacher.imageServer.shared.model.ThumbnailInfo.readThumbnailInfo(in)); break;
			default: super.readField(in, field);
		}
	}

}
