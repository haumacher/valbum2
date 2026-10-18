package de.haumacher.imageServer.shared.model;

/**
 * One file or folder of a {@link StatusProblem}.
 */
public class StatusItem extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.StatusItem} instance.
	 */
	public static de.haumacher.imageServer.shared.model.StatusItem create() {
		return new de.haumacher.imageServer.shared.model.StatusItem();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.StatusItem} type in JSON format. */
	public static final String STATUS_ITEM__TYPE = "StatusItem";

	/** @see #getFolder() */
	private static final String FOLDER__PROP = "folder";

	/** @see #getName() */
	private static final String NAME__PROP = "name";

	/** @see #getPath() */
	private static final String PATH__PROP = "path";

	/** @see #getPhotos() */
	private static final String PHOTOS__PROP = "photos";

	/** @see #getWhat() */
	private static final String WHAT__PROP = "what";

	private String _folder = "";

	private String _name = "";

	private String _path = "";

	private int _photos = 0;

	private String _what = "";

	/**
	 * Creates a {@link StatusItem} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.StatusItem#create()
	 */
	protected StatusItem() {
		super();
	}

	/**
	 * The folder, relative to the root of the space; empty for the root.
	 */
	public final String getFolder() {
		return _folder;
	}

	/**
	 * @see #getFolder()
	 */
	public de.haumacher.imageServer.shared.model.StatusItem setFolder(String value) {
		internalSetFolder(value);
		return this;
	}

	/** Internal setter for {@link #getFolder()} without chain call utility. */
	protected final void internalSetFolder(String value) {
		_folder = value;
	}

	/**
	 * The file (or video) in it, empty where the problem is the folder's.
	 */
	public final String getName() {
		return _name;
	}

	/**
	 * @see #getName()
	 */
	public de.haumacher.imageServer.shared.model.StatusItem setName(String value) {
		internalSetName(value);
		return this;
	}

	/** Internal setter for {@link #getName()} without chain call utility. */
	protected final void internalSetName(String value) {
		_name = value;
	}

	/**
	 * The folder's path on the server, absolute, for {@link StatusProblemKind#UNWRITABLE}.
	 */
	public final String getPath() {
		return _path;
	}

	/**
	 * @see #getPath()
	 */
	public de.haumacher.imageServer.shared.model.StatusItem setPath(String value) {
		internalSetPath(value);
		return this;
	}

	/** Internal setter for {@link #getPath()} without chain call utility. */
	protected final void internalSetPath(String value) {
		_path = value;
	}

	/**
	 * How many photos and videos the folder holds, for {@link StatusProblemKind#UNWRITABLE}.
	 */
	public final int getPhotos() {
		return _photos;
	}

	/**
	 * @see #getPhotos()
	 */
	public de.haumacher.imageServer.shared.model.StatusItem setPhotos(int value) {
		internalSetPhotos(value);
		return this;
	}

	/** Internal setter for {@link #getPhotos()} without chain call utility. */
	protected final void internalSetPhotos(int value) {
		_photos = value;
	}

	/**
	 * What could not be stored there, for {@link StatusProblemKind#UNWRITABLE}.
	 */
	public final String getWhat() {
		return _what;
	}

	/**
	 * @see #getWhat()
	 */
	public de.haumacher.imageServer.shared.model.StatusItem setWhat(String value) {
		internalSetWhat(value);
		return this;
	}

	/** Internal setter for {@link #getWhat()} without chain call utility. */
	protected final void internalSetWhat(String value) {
		_what = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.StatusItem readStatusItem(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.StatusItem result = new de.haumacher.imageServer.shared.model.StatusItem();
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
		out.name(FOLDER__PROP);
		out.value(getFolder());
		out.name(NAME__PROP);
		out.value(getName());
		out.name(PATH__PROP);
		out.value(getPath());
		out.name(PHOTOS__PROP);
		out.value(getPhotos());
		out.name(WHAT__PROP);
		out.value(getWhat());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case FOLDER__PROP: setFolder(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case NAME__PROP: setName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case PATH__PROP: setPath(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case PHOTOS__PROP: setPhotos(in.nextInt()); break;
			case WHAT__PROP: setWhat(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
