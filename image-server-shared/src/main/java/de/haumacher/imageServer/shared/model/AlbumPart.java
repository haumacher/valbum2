package de.haumacher.imageServer.shared.model;

/**
 * Base class for contents of an {@link AlbumInfo}.
 */
public abstract class AlbumPart extends Resource {

	/** Visitor interface for the {@link AlbumPart} hierarchy.*/
	public interface Visitor<R,A> {

		/** Visit case for {@link ImageGroup}.*/
		R visit(ImageGroup self, A arg);

		/** Visit case for {@link ImageInfo}.*/
		R visit(ImageInfo self, A arg);

	}

	/**
	 * Creates a {@link AlbumPart} instance.
	 */
	protected AlbumPart() {
		super();
	}

	/** Reads a new instance from the given reader. */
	public static AlbumPart readAlbumPart(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		AlbumPart result;
		in.beginArray();
		String type = in.nextString();
		switch (type) {
			case "ImageGroup": result = ImageGroup.readImageGroup(in); break;
			case "ImageInfo": result = ImageInfo.readImageInfo(in); break;
			default: in.skipValue(); result = null; break;
		}
		in.endArray();
		return result;
	}

	/** Reads a new instance from the given reader. */
	public static AlbumPart readAlbumPart(de.haumacher.msgbuf.binary.DataReader in) throws java.io.IOException {
		in.beginObject();
		AlbumPart result;
		int typeField = in.nextName();
		assert typeField == 0;
		int type = in.nextInt();
		switch (type) {
			case 2: result = ImageGroup.create(); break;
			case 3: result = ImageInfo.create(); break;
			default: while (in.hasNext()) {in.skipValue(); } in.endObject(); return null;
		}
		while (in.hasNext()) {
			int field = in.nextName();
			result.readField(in, field);
		}
		in.endObject();
		return result;
	}

	/** Accepts the given visitor. */
	public abstract <R,A> R visit(Visitor<R,A> v, A arg);


	@Override
	public final <R,A> R visit(Resource.Visitor<R,A> v, A arg) {
		return visit((Visitor<R,A>) v, arg);
	}

}
