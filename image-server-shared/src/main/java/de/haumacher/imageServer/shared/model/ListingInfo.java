package de.haumacher.imageServer.shared.model;

/**
 * {@link Resource} describing collection {@link FolderInfo}s found in a directory.
 */
public class ListingInfo extends FolderResource {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.ListingInfo} instance.
	 */
	public static de.haumacher.imageServer.shared.model.ListingInfo create() {
		return new de.haumacher.imageServer.shared.model.ListingInfo();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.ListingInfo} type in JSON format. */
	public static final String LISTING_INFO__TYPE = "ListingInfo";

	/** @see #getTitle() */
	private static final String TITLE__PROP = "title";

	/** @see #getFolders() */
	private static final String FOLDERS__PROP = "folders";

	private String _title = "";

	private final java.util.List<de.haumacher.imageServer.shared.model.FolderInfo> _folders = new java.util.ArrayList<>();

	/**
	 * Creates a {@link ListingInfo} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.ListingInfo#create()
	 */
	protected ListingInfo() {
		super();
	}

	@Override
	public TypeKind kind() {
		return TypeKind.LISTING_INFO;
	}

	/**
	 * The title to display for this {@link ListingInfo}.
	 */
	public final String getTitle() {
		return _title;
	}

	/**
	 * @see #getTitle()
	 */
	public de.haumacher.imageServer.shared.model.ListingInfo setTitle(String value) {
		internalSetTitle(value);
		return this;
	}

	/** Internal setter for {@link #getTitle()} without chain call utility. */
	protected final void internalSetTitle(String value) {
		_title = value;
	}

	/**
	 * Description of the folders within this {@link ListingInfo}.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.FolderInfo> getFolders() {
		return _folders;
	}

	/**
	 * @see #getFolders()
	 */
	public de.haumacher.imageServer.shared.model.ListingInfo setFolders(java.util.List<? extends de.haumacher.imageServer.shared.model.FolderInfo> value) {
		internalSetFolders(value);
		return this;
	}

	/** Internal setter for {@link #getFolders()} without chain call utility. */
	protected final void internalSetFolders(java.util.List<? extends de.haumacher.imageServer.shared.model.FolderInfo> value) {
		if (value == null) throw new IllegalArgumentException("Property 'folders' cannot be null.");
		_folders.clear();
		_folders.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getFolders()} list.
	 */
	public de.haumacher.imageServer.shared.model.ListingInfo addFolder(de.haumacher.imageServer.shared.model.FolderInfo value) {
		internalAddFolder(value);
		return this;
	}

	/** Implementation of {@link #addFolder(de.haumacher.imageServer.shared.model.FolderInfo)} without chain call utility. */
	protected final void internalAddFolder(de.haumacher.imageServer.shared.model.FolderInfo value) {
		_folders.add(value);
	}

	/**
	 * Removes a value from the {@link #getFolders()} list.
	 */
	public final void removeFolder(de.haumacher.imageServer.shared.model.FolderInfo value) {
		_folders.remove(value);
	}

	@Override
	public de.haumacher.imageServer.shared.model.ListingInfo setPath(String value) {
		internalSetPath(value);
		return this;
	}

	@Override
	public String jsonType() {
		return LISTING_INFO__TYPE;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.ListingInfo readListingInfo(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.ListingInfo result = new de.haumacher.imageServer.shared.model.ListingInfo();
		result.readContent(in);
		return result;
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name(TITLE__PROP);
		out.value(getTitle());
		out.name(FOLDERS__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.FolderInfo x : getFolders()) {
			x.writeTo(out);
		}
		out.endArray();
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case TITLE__PROP: setTitle(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case FOLDERS__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addFolder(de.haumacher.imageServer.shared.model.FolderInfo.readFolderInfo(in));
				}
				in.endArray();
			}
			break;
			default: super.readField(in, field);
		}
	}

	@Override
	public <R,A,E extends Throwable> R visit(de.haumacher.imageServer.shared.model.FolderResource.Visitor<R,A,E> v, A arg) throws E {
		return v.visit(this, arg);
	}

}
