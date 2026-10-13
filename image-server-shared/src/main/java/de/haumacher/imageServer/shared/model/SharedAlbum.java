package de.haumacher.imageServer.shared.model;

/**
 * One link of {@link SharedWithYou}: what the link itself shows of its folder, see issue #267.
 *
 * <p>
 * Never a token: the link is opened through the contact credential, at
 * <code>&lt;app base&gt;/shared/&lt;link&gt;/</code>, whose requests carry
 * <code>X-VAlbum-Shared: &lt;link&gt;</code> beside the credential, and everything below is exactly
 * that link's view.
 * </p>
 */
public class SharedAlbum extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.SharedAlbum} instance.
	 */
	public static de.haumacher.imageServer.shared.model.SharedAlbum create() {
		return new de.haumacher.imageServer.shared.model.SharedAlbum();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.SharedAlbum} type in JSON format. */
	public static final String SHARED_ALBUM__TYPE = "SharedAlbum";

	/** @see #getLink() */
	private static final String LINK__PROP = "link";

	/** @see #getFolder() */
	private static final String FOLDER__PROP = "folder";

	/** @see #getSharedBy() */
	private static final String SHARED_BY__PROP = "sharedBy";

	/** @see #getExpires() */
	private static final String EXPIRES__PROP = "expires";

	/** @see #getRights() */
	private static final String RIGHTS__PROP = "rights";

	/** @see #getCreated() */
	private static final String CREATED__PROP = "created";

	private String _link = "";

	private de.haumacher.imageServer.shared.model.FolderInfo _folder = null;

	private String _sharedBy = "";

	private String _expires = "";

	private final java.util.List<de.haumacher.imageServer.shared.model.RightName> _rights = new java.util.ArrayList<>();

	private String _created = "";

	/**
	 * Creates a {@link SharedAlbum} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.SharedAlbum#create()
	 */
	protected SharedAlbum() {
		super();
	}

	/**
	 * The id of the link.
	 */
	public final String getLink() {
		return _link;
	}

	/**
	 * @see #getLink()
	 */
	public de.haumacher.imageServer.shared.model.SharedAlbum setLink(String value) {
		internalSetLink(value);
		return this;
	}

	/** Internal setter for {@link #getLink()} without chain call utility. */
	protected final void internalSetLink(String value) {
		_link = value;
	}

	/**
	 * The shared folder's tile as the link itself sees it: its title, and its picture as filtered
	 * for the link (privacy, rating floor, label), whose {@link ThumbnailInfo#getImage()} is relative to
	 * the shared folder, the root of the link's view; <code>null</code> where the folder is gone.
	 */
	public final de.haumacher.imageServer.shared.model.FolderInfo getFolder() {
		return _folder;
	}

	/**
	 * @see #getFolder()
	 */
	public de.haumacher.imageServer.shared.model.SharedAlbum setFolder(de.haumacher.imageServer.shared.model.FolderInfo value) {
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

	/**
	 * The member who shared it.
	 */
	public final String getSharedBy() {
		return _sharedBy;
	}

	/**
	 * @see #getSharedBy()
	 */
	public de.haumacher.imageServer.shared.model.SharedAlbum setSharedBy(String value) {
		internalSetSharedBy(value);
		return this;
	}

	/** Internal setter for {@link #getSharedBy()} without chain call utility. */
	protected final void internalSetSharedBy(String value) {
		_sharedBy = value;
	}

	/**
	 * When the link expires, an ISO-8601 instant; empty if it never does.
	 */
	public final String getExpires() {
		return _expires;
	}

	/**
	 * @see #getExpires()
	 */
	public de.haumacher.imageServer.shared.model.SharedAlbum setExpires(String value) {
		internalSetExpires(value);
		return this;
	}

	/** Internal setter for {@link #getExpires()} without chain call utility. */
	protected final void internalSetExpires(String value) {
		_expires = value;
	}

	/**
	 * What the link allows: <code>view</code>, <code>download</code>, <code>contribute</code>.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.RightName> getRights() {
		return _rights;
	}

	/**
	 * @see #getRights()
	 */
	public de.haumacher.imageServer.shared.model.SharedAlbum setRights(java.util.List<? extends de.haumacher.imageServer.shared.model.RightName> value) {
		internalSetRights(value);
		return this;
	}

	/** Internal setter for {@link #getRights()} without chain call utility. */
	protected final void internalSetRights(java.util.List<? extends de.haumacher.imageServer.shared.model.RightName> value) {
		if (value == null) throw new IllegalArgumentException("Property 'rights' cannot be null.");
		_rights.clear();
		_rights.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getRights()} list.
	 */
	public de.haumacher.imageServer.shared.model.SharedAlbum addRight(de.haumacher.imageServer.shared.model.RightName value) {
		internalAddRight(value);
		return this;
	}

	/** Implementation of {@link #addRight(de.haumacher.imageServer.shared.model.RightName)} without chain call utility. */
	protected final void internalAddRight(de.haumacher.imageServer.shared.model.RightName value) {
		_rights.add(value);
	}

	/**
	 * Removes a value from the {@link #getRights()} list.
	 */
	public final void removeRight(de.haumacher.imageServer.shared.model.RightName value) {
		_rights.remove(value);
	}

	/**
	 * When the link was shared, an ISO-8601 instant.
	 */
	public final String getCreated() {
		return _created;
	}

	/**
	 * @see #getCreated()
	 */
	public de.haumacher.imageServer.shared.model.SharedAlbum setCreated(String value) {
		internalSetCreated(value);
		return this;
	}

	/** Internal setter for {@link #getCreated()} without chain call utility. */
	protected final void internalSetCreated(String value) {
		_created = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.SharedAlbum readSharedAlbum(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.SharedAlbum result = new de.haumacher.imageServer.shared.model.SharedAlbum();
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
		out.name(LINK__PROP);
		out.value(getLink());
		if (hasFolder()) {
			out.name(FOLDER__PROP);
			getFolder().writeTo(out);
		}
		out.name(SHARED_BY__PROP);
		out.value(getSharedBy());
		out.name(EXPIRES__PROP);
		out.value(getExpires());
		out.name(RIGHTS__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.RightName x : getRights()) {
			x.writeTo(out);
		}
		out.endArray();
		out.name(CREATED__PROP);
		out.value(getCreated());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case LINK__PROP: setLink(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case FOLDER__PROP: setFolder(de.haumacher.imageServer.shared.model.FolderInfo.readFolderInfo(in)); break;
			case SHARED_BY__PROP: setSharedBy(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case EXPIRES__PROP: setExpires(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case RIGHTS__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addRight(de.haumacher.imageServer.shared.model.RightName.readRightName(in));
				}
				in.endArray();
			}
			break;
			case CREATED__PROP: setCreated(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
