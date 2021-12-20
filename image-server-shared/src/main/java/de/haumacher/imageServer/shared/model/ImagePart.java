package de.haumacher.imageServer.shared.model;

/**
 * {@link Resource} describing a single image or video file.
 */
public class ImagePart extends AbstractImage {

	/**
	 * Kind of image.
	 */
	public enum Kind {

		/**
		 * A still image.
		 */
		IMAGE,

		/**
		 * A video.
		 */
		VIDEO,

		;

		/** Writes this instance to the given output. */
		public final void writeTo(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
			out.value(name());
		}

		/** Reads a new instance from the given reader. */
		public static Kind readKind(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
			return valueOf(in.nextString());
		}

		/** Writes this instance to the given binary output. */
		public final void writeTo(de.haumacher.msgbuf.binary.DataWriter out) throws java.io.IOException {
			switch (this) {
				case IMAGE: out.value(1); break;
				case VIDEO: out.value(2); break;
				default: out.value(0);
			}
		}

		/** Reads a new instance from the given binary reader. */
		public static Kind readKind(de.haumacher.msgbuf.binary.DataReader in) throws java.io.IOException {
			switch (in.nextInt()) {
				case 1: return IMAGE;
				case 2: return VIDEO;
				default: return IMAGE;
			}
		}
	}

	/**
	 * Creates a {@link ImagePart} instance.
	 */
	public static ImagePart create() {
		return new ImagePart();
	}

	/** Identifier for the {@link ImagePart} type in JSON format. */
	public static final String IMAGE_PART__TYPE = "ImagePart";

	/** @see #getOwner() */
	private static final String OWNER = "owner";

	/** @see #getKind() */
	private static final String KIND = "kind";

	/** @see #getName() */
	private static final String NAME = "name";

	/** @see #getDate() */
	private static final String DATE = "date";

	/** @see #getWidth() */
	private static final String WIDTH = "width";

	/** @see #getHeight() */
	private static final String HEIGHT = "height";

	/** @see #getRating() */
	private static final String RATING = "rating";

	/** @see #getComment() */
	private static final String COMMENT = "comment";

	/** @see #getPrevious() */
	private static final String PREVIOUS = "previous";

	/** @see #getNext() */
	private static final String NEXT = "next";

	/** @see #getHome() */
	private static final String HOME = "home";

	/** @see #getEnd() */
	private static final String END = "end";

	private transient AlbumInfo _owner = null;

	private Kind _kind = Kind.IMAGE;

	private String _name = "";

	private long _date = 0L;

	private int _width = 0;

	private int _height = 0;

	private int _rating = 0;

	private String _comment = "";

	private transient String _previous = "";

	private transient String _next = "";

	private transient String _home = "";

	private transient String _end = "";

	/**
	 * Creates a {@link ImagePart} instance.
	 *
	 * @see #create()
	 */
	protected ImagePart() {
		super();
	}

	@Override
	public TypeKind kind() {
		return TypeKind.IMAGE_PART;
	}

	/**
	 * The {@link AlbumInfo} this {@link ImageInfo} is part of.
	 */
	public final AlbumInfo getOwner() {
		return _owner;
	}

	/**
	 * @see #getOwner()
	 */
	public final ImagePart setOwner(AlbumInfo value) {
		_owner = value;
		return this;
	}

	/**
	 * Checks, whether {@link #getOwner()} has a value.
	 */
	public final boolean hasOwner() {
		return _owner != null;
	}

	/**
	 * The kind of this {@link ImageInfo}.
	 */
	public final Kind getKind() {
		return _kind;
	}

	/**
	 * @see #getKind()
	 */
	public final ImagePart setKind(Kind value) {
		if (value == null) throw new IllegalArgumentException("Property 'kind' cannot be null.");
		_kind = value;
		return this;
	}

	/**
	 * The image (file) name.
	 */
	public final String getName() {
		return _name;
	}

	/**
	 * @see #getName()
	 */
	public final ImagePart setName(String value) {
		_name = value;
		return this;
	}

	/**
	 * The last modification date of the image in milliseconds since epoch.
	 */
	public final long getDate() {
		return _date;
	}

	/**
	 * @see #getDate()
	 */
	public final ImagePart setDate(long value) {
		_date = value;
		return this;
	}

	/**
	 * The width of the original image in pixels.
	 */
	public final int getWidth() {
		return _width;
	}

	/**
	 * @see #getWidth()
	 */
	public final ImagePart setWidth(int value) {
		_width = value;
		return this;
	}

	/**
	 * The height of the original image in pixels.
	 */
	public final int getHeight() {
		return _height;
	}

	/**
	 * @see #getHeight()
	 */
	public final ImagePart setHeight(int value) {
		_height = value;
		return this;
	}

	/**
	 * A rating of this image from -2 to 2.
	 */
	public final int getRating() {
		return _rating;
	}

	/**
	 * @see #getRating()
	 */
	public final ImagePart setRating(int value) {
		_rating = value;
		return this;
	}

	/**
	 * A comment describing what this image contains.
	 */
	public final String getComment() {
		return _comment;
	}

	/**
	 * @see #getComment()
	 */
	public final ImagePart setComment(String value) {
		_comment = value;
		return this;
	}

	/**
	 * The {@link #getName()} of the previous image in the {@link #getOwner()}.
	 */
	public final String getPrevious() {
		return _previous;
	}

	/**
	 * @see #getPrevious()
	 */
	public final ImagePart setPrevious(String value) {
		_previous = value;
		return this;
	}

	/**
	 * The {@link #getName()} of the next image in the {@link #getOwner()}.
	 */
	public final String getNext() {
		return _next;
	}

	/**
	 * @see #getNext()
	 */
	public final ImagePart setNext(String value) {
		_next = value;
		return this;
	}

	/**
	 * The {@link #getName()} of the first image of the {@link #getOwner()}.
	 */
	public final String getHome() {
		return _home;
	}

	/**
	 * @see #getHome()
	 */
	public final ImagePart setHome(String value) {
		_home = value;
		return this;
	}

	/**
	 * The {@link #getName()} of the last image of the {@link #getOwner()}.
	 */
	public final String getEnd() {
		return _end;
	}

	/**
	 * @see #getEnd()
	 */
	public final ImagePart setEnd(String value) {
		_end = value;
		return this;
	}

	/** Reads a new instance from the given reader. */
	public static ImagePart readImagePart(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		ImagePart result = new ImagePart();
		in.beginObject();
		result.readFields(in);
		in.endObject();
		return result;
	}

	@Override
	public String jsonType() {
		return IMAGE_PART__TYPE;
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name(KIND);
		getKind().writeTo(out);
		out.name(NAME);
		out.value(getName());
		out.name(DATE);
		out.value(getDate());
		out.name(WIDTH);
		out.value(getWidth());
		out.name(HEIGHT);
		out.value(getHeight());
		out.name(RATING);
		out.value(getRating());
		out.name(COMMENT);
		out.value(getComment());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case KIND: setKind(Kind.readKind(in)); break;
			case NAME: setName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case DATE: setDate(in.nextLong()); break;
			case WIDTH: setWidth(in.nextInt()); break;
			case HEIGHT: setHeight(in.nextInt()); break;
			case RATING: setRating(in.nextInt()); break;
			case COMMENT: setComment(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

	@Override
	public <R,A> R visit(AbstractImage.Visitor<R,A> v, A arg) {
		return v.visit(this, arg);
	}

}
