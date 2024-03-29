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

	private transient String _path = "";

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
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
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
