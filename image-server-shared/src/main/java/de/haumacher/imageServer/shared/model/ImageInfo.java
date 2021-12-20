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

	/** Identifier for the {@link ImageInfo} type in JSON format. */
	public static final String IMAGE_INFO__TYPE = "ImageInfo";

	/** @see #getImage() */
	private static final String IMAGE = "image";

	/** @see #getPrevious() */
	private static final String PREVIOUS = "previous";

	/** @see #getNext() */
	private static final String NEXT = "next";

	/** @see #getHome() */
	private static final String HOME = "home";

	/** @see #getEnd() */
	private static final String END = "end";

	private ImagePart _image = null;

	private String _previous = "";

	private String _next = "";

	private String _home = "";

	private String _end = "";

	/**
	 * Creates a {@link ImageInfo} instance.
	 *
	 * @see #create()
	 */
	protected ImageInfo() {
		super();
	}

	@Override
	public TypeKind kind() {
		return TypeKind.IMAGE_INFO;
	}

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
		return IMAGE_INFO__TYPE;
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		if (hasImage()) {
			out.name(IMAGE);
			getImage().writeContent(out);
		}
		out.name(PREVIOUS);
		out.value(getPrevious());
		out.name(NEXT);
		out.value(getNext());
		out.name(HOME);
		out.value(getHome());
		out.name(END);
		out.value(getEnd());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case IMAGE: setImage(ImagePart.readImagePart(in)); break;
			case PREVIOUS: setPrevious(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case NEXT: setNext(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case HOME: setHome(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case END: setEnd(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

	@Override
	public <R,A> R visit(Resource.Visitor<R,A> v, A arg) {
		return v.visit(this, arg);
	}

}
