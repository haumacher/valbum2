package de.haumacher.imageServer.shared.model;

public abstract class Resource extends de.haumacher.msgbuf.data.AbstractDataObject implements de.haumacher.msgbuf.binary.BinaryDataObject {

	/** Visitor interface for the {@link Resource} hierarchy.*/
	public interface Visitor<R,A> {

		/** Visit case for {@link ImageInfo}.*/
		R visit(ImageInfo self, A arg);

		/** Visit case for {@link AlbumInfo}.*/
		R visit(AlbumInfo self, A arg);

		/** Visit case for {@link ListingInfo}.*/
		R visit(ListingInfo self, A arg);

		/** Visit case for {@link ErrorInfo}.*/
		R visit(ErrorInfo self, A arg);

	}

	/**
	 * Creates a {@link Resource} instance.
	 */
	protected Resource() {
		super();
	}

	/** Reads a new instance from the given reader. */
	public static Resource readResource(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		Resource result;
		in.beginArray();
		String type = in.nextString();
		switch (type) {
			case "ImageInfo": result = ImageInfo.readImageInfo(in); break;
			case "AlbumInfo": result = AlbumInfo.readAlbumInfo(in); break;
			case "ListingInfo": result = ListingInfo.readListingInfo(in); break;
			case "ErrorInfo": result = ErrorInfo.readErrorInfo(in); break;
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
	public final void writeTo(de.haumacher.msgbuf.binary.DataWriter out) throws java.io.IOException {
		out.beginObject();
		out.name(0);
		out.value(typeId());
		writeFields(out);
		out.endObject();
	}

	/** The binary identifier for this concrete type in the polymorphic {@link Resource} hierarchy. */
	public abstract int typeId();

	/**
	 * Serializes all fields of this instance to the given binary output.
	 *
	 * @param out
	 *        The binary output to write to.
	 * @throws java.io.IOException If writing fails.
	 */
	protected void writeFields(de.haumacher.msgbuf.binary.DataWriter out) throws java.io.IOException {
		// No fields to write, hook for subclasses.
	}

	/** Consumes the value for the field with the given ID and assigns its value. */
	protected void readField(de.haumacher.msgbuf.binary.DataReader in, int field) throws java.io.IOException {
		switch (field) {
			default: in.skipValue(); 
		}
	}

	/** Reads a new instance from the given reader. */
	public static Resource readResource(de.haumacher.msgbuf.binary.DataReader in) throws java.io.IOException {
		in.beginObject();
		Resource result;
		int typeField = in.nextName();
		assert typeField == 0;
		int type = in.nextInt();
		switch (type) {
			case 1: result = ImageInfo.create(); break;
			case 2: result = AlbumInfo.create(); break;
			case 3: result = ListingInfo.create(); break;
			case 4: result = ErrorInfo.create(); break;
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


}
