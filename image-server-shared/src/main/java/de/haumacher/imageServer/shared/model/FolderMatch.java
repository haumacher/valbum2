package de.haumacher.imageServer.shared.model;

/**
 * One folder of {@link FolderMatches}.
 */
public class FolderMatch extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.FolderMatch} instance.
	 */
	public static de.haumacher.imageServer.shared.model.FolderMatch create() {
		return new de.haumacher.imageServer.shared.model.FolderMatch();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.FolderMatch} type in JSON format. */
	public static final String FOLDER_MATCH__TYPE = "FolderMatch";

	/** @see #getPath() */
	private static final String PATH__PROP = "path";

	/** @see #getFolder() */
	private static final String FOLDER__PROP = "folder";

	private String _path = "";

	private de.haumacher.imageServer.shared.model.FolderInfo _folder = null;

	/**
	 * Creates a {@link FolderMatch} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.FolderMatch#create()
	 */
	protected FolderMatch() {
		super();
	}

	/**
	 * The folder's path below the folder that was asked, its names separated by <code>/</code>,
	 * never empty.
	 */
	public final String getPath() {
		return _path;
	}

	/**
	 * @see #getPath()
	 */
	public de.haumacher.imageServer.shared.model.FolderMatch setPath(String value) {
		internalSetPath(value);
		return this;
	}

	/** Internal setter for {@link #getPath()} without chain call utility. */
	protected final void internalSetPath(String value) {
		_path = value;
	}

	/**
	 * The folder as the listing above it shows its tile: its name, title, subtitle, date and kind,
	 * never a picture.
	 */
	public final de.haumacher.imageServer.shared.model.FolderInfo getFolder() {
		return _folder;
	}

	/**
	 * @see #getFolder()
	 */
	public de.haumacher.imageServer.shared.model.FolderMatch setFolder(de.haumacher.imageServer.shared.model.FolderInfo value) {
		internalSetFolder(value);
		return this;
	}

	/** Internal setter for {@link #getFolder()} without chain call utility. */
	protected final void internalSetFolder(de.haumacher.imageServer.shared.model.FolderInfo value) {
		_folder = value;
	}

	/**
	 * Checks, whether {@link #getFolder()} has a value.
	 */
	public final boolean hasFolder() {
		return _folder != null;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.FolderMatch readFolderMatch(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.FolderMatch result = new de.haumacher.imageServer.shared.model.FolderMatch();
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
		out.name(PATH__PROP);
		out.value(getPath());
		if (hasFolder()) {
			out.name(FOLDER__PROP);
			getFolder().writeTo(out);
		}
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case PATH__PROP: setPath(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case FOLDER__PROP: setFolder(de.haumacher.imageServer.shared.model.FolderInfo.readFolderInfo(in)); break;
			default: super.readField(in, field);
		}
	}

}
