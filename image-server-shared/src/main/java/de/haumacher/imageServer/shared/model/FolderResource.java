package de.haumacher.imageServer.shared.model;

/**
 * {@link Resource} representing a directory.
 */
public abstract class FolderResource extends Resource {

	/** Visitor interface for the {@link de.haumacher.imageServer.shared.model.FolderResource} hierarchy.*/
	public interface Visitor<R,A,E extends Throwable> {

		/** Visit case for {@link de.haumacher.imageServer.shared.model.AlbumInfo}.*/
		R visit(de.haumacher.imageServer.shared.model.AlbumInfo self, A arg) throws E;

		/** Visit case for {@link de.haumacher.imageServer.shared.model.ListingInfo}.*/
		R visit(de.haumacher.imageServer.shared.model.ListingInfo self, A arg) throws E;

	}

	/** @see #getRights() */
	private static final String RIGHTS__PROP = "rights";

	private transient String _path = "";

	private final java.util.List<de.haumacher.imageServer.shared.model.RightName> _rights = new java.util.ArrayList<>();

	/**
	 * Creates a {@link FolderResource} instance.
	 */
	protected FolderResource() {
		super();
	}

	/**
	 * The path where the {@link Resource} is located on the server relative to it's base directory
	 */
	public final String getPath() {
		return _path;
	}

	/**
	 * @see #getPath()
	 */
	public de.haumacher.imageServer.shared.model.FolderResource setPath(String value) {
		internalSetPath(value);
		return this;
	}

	/** Internal setter for {@link #getPath()} without chain call utility. */
	protected final void internalSetPath(String value) {
		_path = value;
	}

	/**
	 * What the caller may do with this folder, see issue #49.
	 *
	 * <p>
	 * The {@link RightName#getName() names} of the rights the caller holds here: <code>view</code>,
	 * <code>download</code>, <code>contribute</code>, <code>edit</code>. The stronger rights imply
	 * the weaker ones, so an editor is answered with all four and a reader with
	 * <code>view</code> alone. It travels with every folder answer, so that the app can show what
	 * the caller may do without asking a second time.
	 * </p>
	 *
	 * <p>
	 * Derived by the server on every read from the grants on this folder and its ancestors, exactly
	 * like {@link AlbumInfo#getEffectiveDate()}, and never stored: the server clears this field before a
	 * sidecar is written, so that a round trip through a client cannot freeze somebody's rights into
	 * <code>index.json</code>. A sidecar that carries it nevertheless is read without complaint and
	 * answered with the derived value.
	 * </p>
	 *
	 * <p>
	 * A list of messages, not a list of plain strings: the Dart backend of the model generator
	 * mis-types a <code>repeated string</code> field, see {@link UploadCheck#getHashes()}.
	 * </p>
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.RightName> getRights() {
		return _rights;
	}

	/**
	 * @see #getRights()
	 */
	public de.haumacher.imageServer.shared.model.FolderResource setRights(java.util.List<? extends de.haumacher.imageServer.shared.model.RightName> value) {
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
	public de.haumacher.imageServer.shared.model.FolderResource addRight(de.haumacher.imageServer.shared.model.RightName value) {
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

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.FolderResource readFolderResource(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.FolderResource result;
		in.beginArray();
		String type = in.nextString();
		switch (type) {
			case AlbumInfo.ALBUM_INFO__TYPE: result = de.haumacher.imageServer.shared.model.AlbumInfo.readAlbumInfo(in); break;
			case ListingInfo.LISTING_INFO__TYPE: result = de.haumacher.imageServer.shared.model.ListingInfo.readListingInfo(in); break;
			default: in.skipValue(); result = null; break;
		}
		in.endArray();
		return result;
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name(RIGHTS__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.RightName x : getRights()) {
			x.writeTo(out);
		}
		out.endArray();
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case RIGHTS__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addRight(de.haumacher.imageServer.shared.model.RightName.readRightName(in));
				}
				in.endArray();
			}
			break;
			default: super.readField(in, field);
		}
	}

	/** Accepts the given visitor. */
	public abstract <R,A,E extends Throwable> R visit(Visitor<R,A,E> v, A arg) throws E;

	@Override
	public final <R,A,E extends Throwable> R visit(de.haumacher.imageServer.shared.model.Resource.Visitor<R,A,E> v, A arg) throws E {
		return visit((de.haumacher.imageServer.shared.model.FolderResource.Visitor<R,A,E>) v, arg);
	}

}
