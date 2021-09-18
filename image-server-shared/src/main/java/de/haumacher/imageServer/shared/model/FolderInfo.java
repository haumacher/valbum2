package de.haumacher.imageServer.shared.model;

/**
 * Part of a {@link ListingInfo} describing a reference to a single album directory.
 */
public class FolderInfo extends de.haumacher.msgbuf.data.AbstractDataObject implements de.haumacher.msgbuf.binary.BinaryDataObject {

	/**
	 * Creates a {@link FolderInfo} instance.
	 */
	public static FolderInfo create() {
		return new FolderInfo();
	}

	/**
	 * Creates a {@link FolderInfo} instance.
	 *
	 * @see #create()
	 */
	protected FolderInfo() {
		super();
	}

	private String _name = "";

	private String _title = "";

	private String _subTitle = "";

	private ThumbnailInfo _indexPicture = null;

	/**
	 * The directory name of this {@link FolderInfo}.
	 */
	public final String getName() {
		return _name;
	}

	/**
	 * @see #getName()
	 */
	public final FolderInfo setName(String value) {
		_name = value;
		return this;
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
	public final FolderInfo setTitle(String value) {
		_title = value;
		return this;
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
	public final FolderInfo setSubTitle(String value) {
		_subTitle = value;
		return this;
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
	public final FolderInfo setIndexPicture(ThumbnailInfo value) {
		_indexPicture = value;
		return this;
	}

	/**
	 * Checks, whether {@link #getIndexPicture()} has a value.
	 */
	public final boolean hasIndexPicture() {
		return _indexPicture != null;
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
	public Object get(String field) {
		switch (field) {
			case "name": return getName();
			case "title": return getTitle();
			case "subTitle": return getSubTitle();
			case "indexPicture": return getIndexPicture();
			default: return super.get(field);
		}
	}

	@Override
	public void set(String field, Object value) {
		switch (field) {
			case "name": setName((String) value); break;
			case "title": setTitle((String) value); break;
			case "subTitle": setSubTitle((String) value); break;
			case "indexPicture": setIndexPicture((ThumbnailInfo) value); break;
		}
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name("name");
		out.value(getName());
		out.name("title");
		out.value(getTitle());
		out.name("subTitle");
		out.value(getSubTitle());
		if (hasIndexPicture()) {
			out.name("indexPicture");
			getIndexPicture().writeTo(out);
		}
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case "name": setName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case "title": setTitle(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case "subTitle": setSubTitle(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case "indexPicture": setIndexPicture(ThumbnailInfo.readThumbnailInfo(in)); break;
			default: super.readField(in, field);
		}
	}

	@Override
	public final void writeTo(de.haumacher.msgbuf.binary.DataWriter out) throws java.io.IOException {
		out.beginObject();
		writeFields(out);
		out.endObject();
	}

	/**
	 * Serializes all fields of this instance to the given binary output.
	 *
	 * @param out
	 *        The binary output to write to.
	 * @throws java.io.IOException If writing fails.
	 */
	protected void writeFields(de.haumacher.msgbuf.binary.DataWriter out) throws java.io.IOException {
		out.name(1);
		out.value(getName());
		out.name(2);
		out.value(getTitle());
		out.name(3);
		out.value(getSubTitle());
		if (hasIndexPicture()) {
			out.name(4);
			getIndexPicture().writeTo(out);
		}
	}

	/** Consumes the value for the field with the given ID and assigns its value. */
	protected void readField(de.haumacher.msgbuf.binary.DataReader in, int field) throws java.io.IOException {
		switch (field) {
			case 1: setName(in.nextString()); break;
			case 2: setTitle(in.nextString()); break;
			case 3: setSubTitle(in.nextString()); break;
			case 4: setIndexPicture(ThumbnailInfo.readThumbnailInfo(in)); break;
			default: in.skipValue(); 
		}
	}

	/** Reads a new instance from the given reader. */
	public static FolderInfo readFolderInfo(de.haumacher.msgbuf.binary.DataReader in) throws java.io.IOException {
		in.beginObject();
		FolderInfo result = new FolderInfo();
		while (in.hasNext()) {
			int field = in.nextName();
			result.readField(in, field);
		}
		in.endObject();
		return result;
	}

}
