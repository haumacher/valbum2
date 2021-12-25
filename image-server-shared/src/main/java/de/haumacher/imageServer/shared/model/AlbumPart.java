package de.haumacher.imageServer.shared.model;

/**
 * Base class for contents of an {@link AlbumInfo}.
 */
public abstract class AlbumPart<S extends AlbumPart<S>> extends Resource {

	/** Visitor interface for the {@link AlbumPart} hierarchy.*/
	public interface Visitor<R,A,E extends Throwable> extends AbstractImage.Visitor<R,A,E> {

		/** Visit case for {@link Heading}.*/
		R visit(Heading self, A arg) throws E;

	}

	/** @see #getOwner() */
	private static final String OWNER = "owner";

	private transient AlbumInfo _owner = null;

	/**
	 * Creates a {@link AlbumPart} instance.
	 */
	protected AlbumPart() {
		super();
	}

	/** This instance with the concrete type. */
	protected abstract S self();

	/**
	 * The {@link AlbumInfo}, this one is part of.
	 */
	public final AlbumInfo getOwner() {
		return _owner;
	}

	/**
	 * @see #getOwner()
	 */
	public final S setOwner(AlbumInfo value) {
		_owner = value;
		return self();
	}

	/**
	 * Checks, whether {@link #getOwner()} has a value.
	 */
	public final boolean hasOwner() {
		return _owner != null;
	}

	@Override
	public abstract String jsonType();

	/** Reads a new instance from the given reader. */
	public static AlbumPart<?> readAlbumPart(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		AlbumPart<?> result;
		in.beginArray();
		String type = in.nextString();
		switch (type) {
			case Heading.HEADING__TYPE: result = de.haumacher.imageServer.shared.model.Heading.readHeading(in); break;
			case ImageGroup.IMAGE_GROUP__TYPE: result = de.haumacher.imageServer.shared.model.ImageGroup.readImageGroup(in); break;
			case ImagePart.IMAGE_PART__TYPE: result = de.haumacher.imageServer.shared.model.ImagePart.readImagePart(in); break;
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
	public final <R,A,E extends Throwable> R visit(Resource.Visitor<R,A,E> v, A arg) throws E {
		return visit((Visitor<R,A,E>) v, arg);
	}

}
