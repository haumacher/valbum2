package de.haumacher.imageServer.shared.model;

/**
 * Base class for a resource being displayed as view in a photo album.
 */
public abstract class Resource extends de.haumacher.msgbuf.data.AbstractDataObject {

	/** Type codes for the {@link Resource} hierarchy. */
	public enum TypeKind {

		/** Type literal for {@link AlbumInfo}. */
		ALBUM_INFO,

		/** Type literal for {@link ImageInfo}. */
		IMAGE_INFO,

		/** Type literal for {@link ListingInfo}. */
		LISTING_INFO,

		/** Type literal for {@link ErrorInfo}. */
		ERROR_INFO,
		;

	}

	/** Visitor interface for the {@link Resource} hierarchy.*/
	public interface Visitor<R,A> {

		/** Visit case for {@link AlbumInfo}.*/
		R visit(AlbumInfo self, A arg);

		/** Visit case for {@link ImageInfo}.*/
		R visit(ImageInfo self, A arg);

		/** Visit case for {@link ListingInfo}.*/
		R visit(ListingInfo self, A arg);

		/** Visit case for {@link ErrorInfo}.*/
		R visit(ErrorInfo self, A arg);

	}

	/** @see #getDepth() */
	private static final String DEPTH = "depth";

	private int _depth = 0;

	/**
	 * Creates a {@link Resource} instance.
	 */
	protected Resource() {
		super();
	}

	/** The type code of this instance. */
	public abstract TypeKind kind();

	/**
	 * The nesting level of this {@link Resource}.
	 */
	public final int getDepth() {
		return _depth;
	}

	/**
	 * @see #getDepth()
	 */
	public final Resource setDepth(int value) {
		_depth = value;
		return this;
	}

	/** Reads a new instance from the given reader. */
	public static Resource readResource(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		Resource result;
		in.beginArray();
		String type = in.nextString();
		switch (type) {
			case AlbumInfo.ALBUM_INFO__TYPE: result = AlbumInfo.readAlbumInfo(in); break;
			case ImageInfo.IMAGE_INFO__TYPE: result = ImageInfo.readImageInfo(in); break;
			case ListingInfo.LISTING_INFO__TYPE: result = ListingInfo.readListingInfo(in); break;
			case ErrorInfo.ERROR_INFO__TYPE: result = ErrorInfo.readErrorInfo(in); break;
			default: in.skipValue(); result = null; break;
		}
		in.endArray();
		return result;
	}

	@Override
	public final void writeTo(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		out.beginArray();
		out.value(jsonType());
		writeContent(out);
		out.endArray();
	}

	/** The type identifier for this concrete subtype. */
	public abstract String jsonType();

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name(DEPTH);
		out.value(getDepth());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case DEPTH: setDepth(in.nextInt()); break;
			default: super.readField(in, field);
		}
	}

	/** Accepts the given visitor. */
	public abstract <R,A> R visit(Visitor<R,A> v, A arg);


}
