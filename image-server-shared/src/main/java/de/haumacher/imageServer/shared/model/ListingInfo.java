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

	/** @see #getPlacement() */
	private static final String PLACEMENT__PROP = "placement";

	/** @see #getIndex() */
	private static final String INDEX__PROP = "index";

	/** @see #getFolders() */
	private static final String FOLDERS__PROP = "folders";

	private String _title = "";

	private de.haumacher.imageServer.shared.model.Placement _placement = de.haumacher.imageServer.shared.model.Placement.NONE;

	private String _index = "";

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
	 * How this folder files what lands in it, see issue #48.
	 *
	 * <p>
	 * Stored in this folder's own <code>index.json</code>. The rule places, it does not police: it
	 * is applied to an album created in this folder and to an entry moved into it, and to what is
	 * already here only when the owner asks for it (<code>&lt;folder&gt;/?action=place</code>).
	 * Whatever is filed by hand afterwards stays where it was put.
	 * </p>
	 */
	public final de.haumacher.imageServer.shared.model.Placement getPlacement() {
		return _placement;
	}

	/**
	 * @see #getPlacement()
	 */
	public de.haumacher.imageServer.shared.model.ListingInfo setPlacement(de.haumacher.imageServer.shared.model.Placement value) {
		internalSetPlacement(value);
		return this;
	}

	/** Internal setter for {@link #getPlacement()} without chain call utility. */
	protected final void internalSetPlacement(de.haumacher.imageServer.shared.model.Placement value) {
		if (value == null) throw new IllegalArgumentException("Property 'placement' cannot be null.");
		_placement = value;
	}

	/**
	 * The name of the child entry whose picture stands for this folder, see issue #110.
	 *
	 * <p>
	 * A folder of folders holds no photograph of its own, so it can only be shown by one that
	 * lies below it. Which one is a statement of the author and nothing the server guesses: this
	 * field names a direct child of this folder — an album, whose
	 * {@link AlbumInfo#getIndexPicture() index picture} is taken, or a further folder, which is
	 * asked the same question again. The empty string is the answer "none", and then the folder is
	 * drawn with the folder icon as it always was.
	 * </p>
	 *
	 * <p>
	 * Stored in this folder's own <code>index.json</code> beside the {@link #getPlacement()
	 * placement rule}, and written by the ordinary sidecar <code>PUT</code>. What is derived from
	 * it is the {@link FolderInfo#getIndexPicture() cover} of the tile this folder is shown with,
	 * whose {@link ThumbnailInfo#getImage() image} then carries the path from this folder down to
	 * the photograph (<code>A/a.jpg</code>). A choice that leads nowhere — a child that is gone,
	 * one without a sidecar, one that shows no picture — is simply no picture; nothing fails and
	 * nothing is rewritten.
	 * </p>
	 */
	public final String getIndex() {
		return _index;
	}

	/**
	 * @see #getIndex()
	 */
	public de.haumacher.imageServer.shared.model.ListingInfo setIndex(String value) {
		internalSetIndex(value);
		return this;
	}

	/** Internal setter for {@link #getIndex()} without chain call utility. */
	protected final void internalSetIndex(String value) {
		_index = value;
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
	public de.haumacher.imageServer.shared.model.ListingInfo setRights(java.util.List<? extends de.haumacher.imageServer.shared.model.RightName> value) {
		internalSetRights(value);
		return this;
	}

	@Override
	public de.haumacher.imageServer.shared.model.ListingInfo addRight(de.haumacher.imageServer.shared.model.RightName value) {
		internalAddRight(value);
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
		out.name(PLACEMENT__PROP);
		getPlacement().writeTo(out);
		out.name(INDEX__PROP);
		out.value(getIndex());
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
			case PLACEMENT__PROP: setPlacement(de.haumacher.imageServer.shared.model.Placement.readPlacement(in)); break;
			case INDEX__PROP: setIndex(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
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
