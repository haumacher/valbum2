package de.haumacher.imageServer.shared.model;

/**
 * Base class for a resource being displayed as view in a photo album.
 */
public abstract class Resource extends de.haumacher.msgbuf.data.AbstractDataObject {

	/** Type codes for the {@link Resource} hierarchy. */
	public enum TypeKind {

		/** Type literal for {@link AlbumInfo}. */
		ALBUM_INFO,

		/** Type literal for {@link ListingInfo}. */
		LISTING_INFO,

		/** Type literal for {@link Heading}. */
		HEADING,

		/** Type literal for {@link ImageGroup}. */
		IMAGE_GROUP,

		/** Type literal for {@link ImagePart}. */
		IMAGE_PART,

		/** Type literal for {@link ErrorInfo}. */
		ERROR_INFO,
		;

	}

	/** Visitor interface for the {@link Resource} hierarchy.*/
	public interface Visitor<R,A,E extends Throwable> extends FolderResource.Visitor<R,A,E>, AlbumPart.Visitor<R,A,E> {

		/** Visit case for {@link ErrorInfo}.*/
		R visit(ErrorInfo self, A arg) throws E;

	}

	/**
	 * Creates a {@link Resource} instance.
	 */
	protected Resource() {
		super();
	}

	/** The type code of this instance. */
	public abstract TypeKind kind();

	/** The type identifier for this concrete subtype. */
	public abstract String jsonType();

	/** Reads a new instance from the given reader. */
	public static Resource readResource(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		Resource result;
		in.beginArray();
		String type = in.nextString();
		switch (type) {
			case ErrorInfo.ERROR_INFO__TYPE: result = de.haumacher.imageServer.shared.model.ErrorInfo.readErrorInfo(in); break;
			case AlbumInfo.ALBUM_INFO__TYPE: result = de.haumacher.imageServer.shared.model.AlbumInfo.readAlbumInfo(in); break;
			case ListingInfo.LISTING_INFO__TYPE: result = de.haumacher.imageServer.shared.model.ListingInfo.readListingInfo(in); break;
			case Heading.HEADING__TYPE: result = de.haumacher.imageServer.shared.model.Heading.readHeading(in); break;
			case ImageGroup.IMAGE_GROUP__TYPE: result = de.haumacher.imageServer.shared.model.ImageGroup.readImageGroup(in); break;
			case ImagePart.IMAGE_PART__TYPE: result = de.haumacher.imageServer.shared.model.ImagePart.readImagePart(in); break;
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

	/** Accepts the given visitor. */
	public abstract <R,A,E extends Throwable> R visit(Visitor<R,A,E> v, A arg) throws E;


}
