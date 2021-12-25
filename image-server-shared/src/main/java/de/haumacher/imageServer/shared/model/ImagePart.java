package de.haumacher.imageServer.shared.model;

/**
 * {@link Resource} describing a single image or video file.
 */
public class ImagePart extends AbstractImage {

	/**
	 * Kind of image.
	 */
	public enum Kind implements de.haumacher.msgbuf.data.ProtocolEnum {

		/**
		 * A JPEG image.
		 */
		IMAGE("IMAGE"),

		/**
		 * A mp4 video. 
		 *
		 * <p>For historical reason, this kind is named "video" and not "mp4".</p>
		 */
		VIDEO("VIDEO"),

		/**
		 * A quicktime video.
		 */
		QUICKTIME("QUICKTIME"),

		;

		private final String _protocolName;

		private Kind(String protocolName) {
			_protocolName = protocolName;
		}

		/**
		 * The protocol name of a {@link Kind} constant.
		 *
		 * @see #valueOfProtocol(String)
		 */
		@Override
		public String protocolName() {
			return _protocolName;
		}

		/** Looks up a {@link Kind} constant by it's protocol name. */
		public static Kind valueOfProtocol(String protocolName) {
			if (protocolName == null) { return null; }
			switch (protocolName) {
				case "IMAGE": return IMAGE;
				case "VIDEO": return VIDEO;
				case "QUICKTIME": return QUICKTIME;
			}
			return IMAGE;
		}

		/** Writes this instance to the given output. */
		public final void writeTo(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
			out.value(protocolName());
		}

		/** Reads a new instance from the given reader. */
		public static Kind readKind(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
			return valueOfProtocol(in.nextString());
		}

		/** Writes this instance to the given binary output. */
		public final void writeTo(de.haumacher.msgbuf.binary.DataWriter out) throws java.io.IOException {
			switch (this) {
				case IMAGE: out.value(1); break;
				case VIDEO: out.value(2); break;
				case QUICKTIME: out.value(3); break;
				default: out.value(0);
			}
		}

		/** Reads a new instance from the given binary reader. */
		public static Kind readKind(de.haumacher.msgbuf.binary.DataReader in) throws java.io.IOException {
			switch (in.nextInt()) {
				case 1: return IMAGE;
				case 2: return VIDEO;
				case 3: return QUICKTIME;
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

	/** @see #getOrientation() */
	private static final String ORIENTATION = "orientation";

	/** @see #getRating() */
	private static final String RATING = "rating";

	/** @see #getPrivacy() */
	private static final String PRIVACY = "privacy";

	/** @see #getComment() */
	private static final String COMMENT = "comment";

	/** @see #getGroup() */
	private static final String GROUP = "group";

	private Kind _kind = de.haumacher.imageServer.shared.model.ImagePart.Kind.IMAGE;

	private String _name = "";

	private long _date = 0L;

	private int _width = 0;

	private int _height = 0;

	private Orientation _orientation = de.haumacher.imageServer.shared.model.Orientation.IDENTITY;

	private int _rating = 0;

	private int _privacy = 0;

	private String _comment = "";

	private transient ImageGroup _group = null;

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
	 * The kind of this {@link ImagePart}.
	 */
	public final Kind getKind() {
		return _kind;
	}

	/**
	 * @see #getKind()
	 */
	public ImagePart setKind(Kind value) {
		internalSetKind(value);
		return this;
	}
	/** Internal setter for {@link #getKind()} without chain call utility. */
	protected final void internalSetKind(Kind value) {
		if (value == null) throw new IllegalArgumentException("Property 'kind' cannot be null.");
		_kind = value;
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
	public ImagePart setName(String value) {
		internalSetName(value);
		return this;
	}
	/** Internal setter for {@link #getName()} without chain call utility. */
	protected final void internalSetName(String value) {
		_name = value;
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
	public ImagePart setDate(long value) {
		internalSetDate(value);
		return this;
	}
	/** Internal setter for {@link #getDate()} without chain call utility. */
	protected final void internalSetDate(long value) {
		_date = value;
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
	public ImagePart setWidth(int value) {
		internalSetWidth(value);
		return this;
	}
	/** Internal setter for {@link #getWidth()} without chain call utility. */
	protected final void internalSetWidth(int value) {
		_width = value;
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
	public ImagePart setHeight(int value) {
		internalSetHeight(value);
		return this;
	}
	/** Internal setter for {@link #getHeight()} without chain call utility. */
	protected final void internalSetHeight(int value) {
		_height = value;
	}


	/**
	 * A transformation applied to the image (in addition to the transformation encoded in the image itself).
	 */
	public final Orientation getOrientation() {
		return _orientation;
	}

	/**
	 * @see #getOrientation()
	 */
	public ImagePart setOrientation(Orientation value) {
		internalSetOrientation(value);
		return this;
	}
	/** Internal setter for {@link #getOrientation()} without chain call utility. */
	protected final void internalSetOrientation(Orientation value) {
		if (value == null) throw new IllegalArgumentException("Property 'orientation' cannot be null.");
		_orientation = value;
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
	public ImagePart setRating(int value) {
		internalSetRating(value);
		return this;
	}
	/** Internal setter for {@link #getRating()} without chain call utility. */
	protected final void internalSetRating(int value) {
		_rating = value;
	}


	/**
	 * A privacy level from 0 to 2.
	 */
	public final int getPrivacy() {
		return _privacy;
	}

	/**
	 * @see #getPrivacy()
	 */
	public ImagePart setPrivacy(int value) {
		internalSetPrivacy(value);
		return this;
	}
	/** Internal setter for {@link #getPrivacy()} without chain call utility. */
	protected final void internalSetPrivacy(int value) {
		_privacy = value;
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
	public ImagePart setComment(String value) {
		internalSetComment(value);
		return this;
	}
	/** Internal setter for {@link #getComment()} without chain call utility. */
	protected final void internalSetComment(String value) {
		_comment = value;
	}


	/**
	 * The {@link ImageGroup}, this {@link ImagePart} is part of, or <code>null</code>, if this {@link ImagePart} is not part of a group.
	 */
	public final ImageGroup getGroup() {
		return _group;
	}

	/**
	 * @see #getGroup()
	 */
	public ImagePart setGroup(ImageGroup value) {
		internalSetGroup(value);
		return this;
	}
	/** Internal setter for {@link #getGroup()} without chain call utility. */
	protected final void internalSetGroup(ImageGroup value) {
		_group = value;
	}


	/**
	 * Checks, whether {@link #getGroup()} has a value.
	 */
	public final boolean hasGroup() {
		return _group != null;
	}

	@Override
	public ImagePart setPrevious(AbstractImage value) {
		internalSetPrevious(value);
		return this;
	}

	@Override
	public ImagePart setNext(AbstractImage value) {
		internalSetNext(value);
		return this;
	}

	@Override
	public ImagePart setHome(AbstractImage value) {
		internalSetHome(value);
		return this;
	}

	@Override
	public ImagePart setEnd(AbstractImage value) {
		internalSetEnd(value);
		return this;
	}

	@Override
	public ImagePart setOwner(AlbumInfo value) {
		internalSetOwner(value);
		return this;
	}

	@Override
	public String jsonType() {
		return IMAGE_PART__TYPE;
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
		out.name(ORIENTATION);
		getOrientation().writeTo(out);
		out.name(RATING);
		out.value(getRating());
		out.name(PRIVACY);
		out.value(getPrivacy());
		out.name(COMMENT);
		out.value(getComment());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case KIND: setKind(de.haumacher.imageServer.shared.model.ImagePart.Kind.readKind(in)); break;
			case NAME: setName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case DATE: setDate(in.nextLong()); break;
			case WIDTH: setWidth(in.nextInt()); break;
			case HEIGHT: setHeight(in.nextInt()); break;
			case ORIENTATION: setOrientation(de.haumacher.imageServer.shared.model.Orientation.readOrientation(in)); break;
			case RATING: setRating(in.nextInt()); break;
			case PRIVACY: setPrivacy(in.nextInt()); break;
			case COMMENT: setComment(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

	@Override
	public <R,A,E extends Throwable> R visit(AbstractImage.Visitor<R,A,E> v, A arg) throws E {
		return v.visit(this, arg);
	}

}
