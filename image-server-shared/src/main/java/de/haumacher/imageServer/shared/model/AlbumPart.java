package de.haumacher.imageServer.shared.model;

/**
 * Base class for contents of an {@link AlbumInfo}.
 */
public abstract class AlbumPart extends de.haumacher.msgbuf.data.AbstractDataObject {

	/** Type codes for the {@link AlbumPart} hierarchy. */
	public enum TypeKind {

		/** Type literal for {@link ImageGroup}. */
		IMAGE_GROUP,

		/** Type literal for {@link ImagePart}. */
		IMAGE_PART,

		/** Type literal for {@link Heading}. */
		HEADING,
		;

	}

	/** Visitor interface for the {@link AlbumPart} hierarchy.*/
	public interface Visitor<R,A> extends AbstractImage.Visitor<R,A> {

		/** Visit case for {@link Heading}.*/
		R visit(Heading self, A arg);

	}

	/**
	 * Creates a {@link AlbumPart} instance.
	 */
	protected AlbumPart() {
		super();
	}

	/** The type code of this instance. */
	public abstract TypeKind kind();

	/** Reads a new instance from the given reader. */
	public static AlbumPart readAlbumPart(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		AlbumPart result;
		in.beginArray();
		String type = in.nextString();
		switch (type) {
			case Heading.HEADING__TYPE: result = Heading.readHeading(in); break;
			case ImageGroup.IMAGE_GROUP__TYPE: result = ImageGroup.readImageGroup(in); break;
			case ImagePart.IMAGE_PART__TYPE: result = ImagePart.readImagePart(in); break;
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

	/** Accepts the given visitor. */
	public abstract <R,A> R visit(Visitor<R,A> v, A arg);


}
