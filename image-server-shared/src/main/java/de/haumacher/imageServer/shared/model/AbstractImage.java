package de.haumacher.imageServer.shared.model;

public abstract class AbstractImage extends AlbumPart {

	/** Visitor interface for the {@link AbstractImage} hierarchy.*/
	public interface Visitor<R,A> {

		/** Visit case for {@link ImageGroup}.*/
		R visit(ImageGroup self, A arg);

		/** Visit case for {@link ImagePart}.*/
		R visit(ImagePart self, A arg);

	}

	/**
	 * Creates a {@link AbstractImage} instance.
	 */
	protected AbstractImage() {
		super();
	}

	/** Reads a new instance from the given reader. */
	public static AbstractImage readAbstractImage(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		AbstractImage result;
		in.beginArray();
		String type = in.nextString();
		switch (type) {
			case "ImageGroup": result = ImageGroup.readImageGroup(in); break;
			case "ImagePart": result = ImagePart.readImagePart(in); break;
			default: in.skipValue(); result = null; break;
		}
		in.endArray();
		return result;
	}

	/** Reads a new instance from the given reader. */
	public static AbstractImage readAbstractImage(de.haumacher.msgbuf.binary.DataReader in) throws java.io.IOException {
		in.beginObject();
		AbstractImage result;
		int typeField = in.nextName();
		assert typeField == 0;
		int type = in.nextInt();
		switch (type) {
			case 1: result = ImageGroup.create(); break;
			case 2: result = ImagePart.create(); break;
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
	public final <R,A> R visit(AlbumPart.Visitor<R,A> v, A arg) {
		return visit((Visitor<R,A>) v, arg);
	}

}
