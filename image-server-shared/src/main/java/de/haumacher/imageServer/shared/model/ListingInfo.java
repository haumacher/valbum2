package de.haumacher.imageServer.shared.model;

/**
 * {@link Resource} describing collection {@link FolderInfo}s found in a directory.
 */
public class ListingInfo extends FolderResource<ListingInfo> {

	/**
	 * Creates a {@link ListingInfo} instance.
	 */
	public static ListingInfo create() {
		return new ListingInfo();
	}

	/** Identifier for the {@link ListingInfo} type in JSON format. */
	public static final String LISTING_INFO__TYPE = "ListingInfo";

	/** @see #getTitle() */
	private static final String TITLE = "title";

	/** @see #getFolders() */
	private static final String FOLDERS = "folders";

	private String _title = "";

	private final java.util.List<FolderInfo> _folders = new java.util.ArrayList<>();

	/**
	 * Creates a {@link ListingInfo} instance.
	 *
	 * @see #create()
	 */
	protected ListingInfo() {
		super();
	}

	@Override
	protected final ListingInfo self() {
		return this;
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
	public final ListingInfo setTitle(String value) {
		_title = value;
		return this;
	}

	/**
	 * Description of the folders within this {@link ListingInfo}.
	 */
	public final java.util.List<FolderInfo> getFolders() {
		return _folders;
	}

	/**
	 * @see #getFolders()
	 */
	public final ListingInfo setFolders(java.util.List<FolderInfo> value) {
		if (value == null) throw new IllegalArgumentException("Property 'folders' cannot be null.");
		_folders.clear();
		_folders.addAll(value);
		return this;
	}

	/**
	 * Adds a value to the {@link #getFolders()} list.
	 */
	public final ListingInfo addFolder(FolderInfo value) {
		_folders.add(value);
		return this;
	}

	/**
	 * Removes a value from the {@link #getFolders()} list.
	 */
	public final ListingInfo removeFolder(FolderInfo value) {
		_folders.remove(value);
		return this;
	}

	@Override
	public String jsonType() {
		return LISTING_INFO__TYPE;
	}

	/** Reads a new instance from the given reader. */
	public static ListingInfo readListingInfo(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		ListingInfo result = new ListingInfo();
		in.beginObject();
		result.readFields(in);
		in.endObject();
		return result;
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name(TITLE);
		out.value(getTitle());
		out.name(FOLDERS);
		out.beginArray();
		for (FolderInfo x : getFolders()) {
			x.writeTo(out);
		}
		out.endArray();
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case TITLE: setTitle(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case FOLDERS: {
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
	public <R,A,E extends Throwable> R visit(FolderResource.Visitor<R,A,E> v, A arg) throws E {
		return v.visit(this, arg);
	}

}
