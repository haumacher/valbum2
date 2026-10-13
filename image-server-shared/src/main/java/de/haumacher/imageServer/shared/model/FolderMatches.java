package de.haumacher.imageServer.shared.model;

/**
 * The folders below a folder that match what was typed into the folder picker's search field, see
 * issue #261 and <code>&lt;folder&gt;/?type=folders&amp;q=&lt;text&gt;</code>.
 *
 * <p>
 * Answered from what the server knows of every folder without opening an image &mdash; the
 * sidecar, the folder name and what the background recorded &mdash;, limited to the folders the
 * caller may see. Derived on every request, never stored.
 * </p>
 */
public class FolderMatches extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.FolderMatches} instance.
	 */
	public static de.haumacher.imageServer.shared.model.FolderMatches create() {
		return new de.haumacher.imageServer.shared.model.FolderMatches();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.FolderMatches} type in JSON format. */
	public static final String FOLDER_MATCHES__TYPE = "FolderMatches";

	/** @see #getFolders() */
	private static final String FOLDERS__PROP = "folders";

	/** @see #isMore() */
	private static final String MORE__PROP = "more";

	private final java.util.List<de.haumacher.imageServer.shared.model.FolderMatch> _folders = new java.util.ArrayList<>();

	private boolean _more = false;

	/**
	 * Creates a {@link FolderMatches} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.FolderMatches#create()
	 */
	protected FolderMatches() {
		super();
	}

	/**
	 * The matching folders, the best first, at most as many as were asked for.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.FolderMatch> getFolders() {
		return _folders;
	}

	/**
	 * @see #getFolders()
	 */
	public de.haumacher.imageServer.shared.model.FolderMatches setFolders(java.util.List<? extends de.haumacher.imageServer.shared.model.FolderMatch> value) {
		internalSetFolders(value);
		return this;
	}

	/** Internal setter for {@link #getFolders()} without chain call utility. */
	protected final void internalSetFolders(java.util.List<? extends de.haumacher.imageServer.shared.model.FolderMatch> value) {
		if (value == null) throw new IllegalArgumentException("Property 'folders' cannot be null.");
		_folders.clear();
		_folders.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getFolders()} list.
	 */
	public de.haumacher.imageServer.shared.model.FolderMatches addFolder(de.haumacher.imageServer.shared.model.FolderMatch value) {
		internalAddFolder(value);
		return this;
	}

	/** Implementation of {@link #addFolder(de.haumacher.imageServer.shared.model.FolderMatch)} without chain call utility. */
	protected final void internalAddFolder(de.haumacher.imageServer.shared.model.FolderMatch value) {
		_folders.add(value);
	}

	/**
	 * Removes a value from the {@link #getFolders()} list.
	 */
	public final void removeFolder(de.haumacher.imageServer.shared.model.FolderMatch value) {
		_folders.remove(value);
	}

	/**
	 * Whether more folders matched than {@link #getFolders()} holds.
	 */
	public final boolean isMore() {
		return _more;
	}

	/**
	 * @see #isMore()
	 */
	public de.haumacher.imageServer.shared.model.FolderMatches setMore(boolean value) {
		internalSetMore(value);
		return this;
	}

	/** Internal setter for {@link #isMore()} without chain call utility. */
	protected final void internalSetMore(boolean value) {
		_more = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.FolderMatches readFolderMatches(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.FolderMatches result = new de.haumacher.imageServer.shared.model.FolderMatches();
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
		out.name(FOLDERS__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.FolderMatch x : getFolders()) {
			x.writeTo(out);
		}
		out.endArray();
		out.name(MORE__PROP);
		out.value(isMore());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case FOLDERS__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addFolder(de.haumacher.imageServer.shared.model.FolderMatch.readFolderMatch(in));
				}
				in.endArray();
			}
			break;
			case MORE__PROP: setMore(in.nextBoolean()); break;
			default: super.readField(in, field);
		}
	}

}
