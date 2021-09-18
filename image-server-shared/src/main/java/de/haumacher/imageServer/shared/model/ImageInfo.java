package de.haumacher.imageServer.shared.model;

/**
 * Description of a single image resource part of an album.
 */
public class ImageInfo extends Resource {

	/**
	 * Creates a {@link ImageInfo} instance.
	 */
	public static ImageInfo create() {
		return new ImageInfo();
	}

	/**
	 * Creates a {@link ImageInfo} instance.
	 *
	 * @see #create()
	 */
	protected ImageInfo() {
		super();
	}

	private ImagePart _image = null;

	private String _previous = "";

	private String _next = "";

	private String _home = "";

	private String _end = "";

	/**
	 * Information about the image contents.
	 */
	public final ImagePart getImage() {
		return _image;
	}

	/**
	 * @see #getImage()
	 */
	public final ImageInfo setImage(ImagePart value) {
		_image = value;
		return this;
	}

	/**
	 * Checks, whether {@link #getImage()} has a value.
	 */
	public final boolean hasImage() {
		return _image != null;
	}

	/**
	 * The {@link #name} of the previous image in the {@link #owner}.
	 */
	public final String getPrevious() {
		return _previous;
	}

	/**
	 * @see #getPrevious()
	 */
	public final ImageInfo setPrevious(String value) {
		_previous = value;
		return this;
	}

	/**
	 * The {@link #name} of the next image in the {@link #owner}.
	 */
	public final String getNext() {
		return _next;
	}

	/**
	 * @see #getNext()
	 */
	public final ImageInfo setNext(String value) {
		_next = value;
		return this;
	}

	/**
	 * The {@link #name} of the first image of the {@link #owner}.
	 */
	public final String getHome() {
		return _home;
	}

	/**
	 * @see #getHome()
	 */
	public final ImageInfo setHome(String value) {
		_home = value;
		return this;
	}

	/**
	 * The {@link #name} of the last image of the {@link #owner}.
	 */
	public final String getEnd() {
		return _end;
	}

	/**
	 * @see #getEnd()
	 */
	public final ImageInfo setEnd(String value) {
		_end = value;
		return this;
	}

	/** Reads a new instance from the given reader. */
	public static ImageInfo readImageInfo(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		ImageInfo result = new ImageInfo();
		in.beginObject();
		result.readFields(in);
		in.endObject();
		return result;
	}

	@Override
	public String jsonType() {
		return "ImageInfo";
	}

	@Override
	public Object get(String field) {
		switch (field) {
			case "image": return getImage();
			case "previous": return getPrevious();
			case "next": return getNext();
			case "home": return getHome();
			case "end": return getEnd();
			default: return super.get(field);
		}
	}

	@Override
	public void set(String field, Object value) {
		switch (field) {
			case "image": setImage((ImagePart) value); break;
			case "previous": setPrevious((String) value); break;
			case "next": setNext((String) value); break;
			case "home": setHome((String) value); break;
			case "end": setEnd((String) value); break;
			default: super.set(field, value); break;
		}
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		if (hasImage()) {
			out.name("image");
			getImage().writeContent(out);
		}
		out.name("previous");
		out.value(getPrevious());
		out.name("next");
		out.value(getNext());
		out.name("home");
		out.value(getHome());
		out.name("end");
		out.value(getEnd());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case "image": setImage(ImagePart.readImagePart(in)); break;
			case "previous": setPrevious(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case "next": setNext(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case "home": setHome(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case "end": setEnd(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

	@Override
	public int typeId() {
		return 2;
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.binary.DataWriter out) throws java.io.IOException {
		super.writeFields(out);
		if (hasImage()) {
			out.name(2);
			getImage().writeTo(out);
		}
		out.name(3);
		out.value(getPrevious());
		out.name(4);
		out.value(getNext());
		out.name(5);
		out.value(getHome());
		out.name(6);
		out.value(getEnd());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.binary.DataReader in, int field) throws java.io.IOException {
		switch (field) {
			case 2: setImage(ImagePart.readImagePart(in)); break;
			case 3: setPrevious(in.nextString()); break;
			case 4: setNext(in.nextString()); break;
			case 5: setHome(in.nextString()); break;
			case 6: setEnd(in.nextString()); break;
			default: super.readField(in, field);
		}
	}

	/** Reads a new instance from the given reader. */
	public static ImageInfo readImageInfo(de.haumacher.msgbuf.binary.DataReader in) throws java.io.IOException {
		in.beginObject();
		ImageInfo result = new ImageInfo();
		while (in.hasNext()) {
			int field = in.nextName();
			result.readField(in, field);
		}
		in.endObject();
		return result;
	}

	@Override
	public <R,A> R visit(Resource.Visitor<R,A> v, A arg) {
		return v.visit(this, arg);
	}

}
