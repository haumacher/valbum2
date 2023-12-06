package de.haumacher.imageServer.shared.model;

/**
 * {@link Resource} describing a single image or video file.
 */
public class ImagePart extends AbstractImage {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.ImagePart} instance.
	 */
	public static de.haumacher.imageServer.shared.model.ImagePart create() {
		return new de.haumacher.imageServer.shared.model.ImagePart();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.ImagePart} type in JSON format. */
	public static final String IMAGE_PART__TYPE = "ImagePart";

	/** @see #getKind() */
	private static final String KIND__PROP = "kind";

	/** @see #getName() */
	private static final String NAME__PROP = "name";

	/** @see #getDate() */
	private static final String DATE__PROP = "date";

	/** @see #getWidth() */
	private static final String WIDTH__PROP = "width";

	/** @see #getHeight() */
	private static final String HEIGHT__PROP = "height";

	/** @see #getOrientation() */
	private static final String ORIENTATION__PROP = "orientation";

	/** @see #getRating() */
	private static final String RATING__PROP = "rating";

	/** @see #getPrivacy() */
	private static final String PRIVACY__PROP = "privacy";

	/** @see #getComment() */
	private static final String COMMENT__PROP = "comment";

	private de.haumacher.imageServer.shared.model.ImageKind _kind = de.haumacher.imageServer.shared.model.ImageKind.IMAGE;

	private String _name = "";

	private long _date = 0L;

	private int _width = 0;

	private int _height = 0;

	private de.haumacher.imageServer.shared.model.Orientation _orientation = de.haumacher.imageServer.shared.model.Orientation.IDENTITY;

	private int _rating = 0;

	private int _privacy = 0;

	private String _comment = "";

	private transient de.haumacher.imageServer.shared.model.ImageGroup _group = null;

	/**
	 * Creates a {@link ImagePart} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.ImagePart#create()
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
	public final de.haumacher.imageServer.shared.model.ImageKind getKind() {
		return _kind;
	}

	/**
	 * @see #getKind()
	 */
	public de.haumacher.imageServer.shared.model.ImagePart setKind(de.haumacher.imageServer.shared.model.ImageKind value) {
		internalSetKind(value);
		return this;
	}

	/** Internal setter for {@link #getKind()} without chain call utility. */
	protected final void internalSetKind(de.haumacher.imageServer.shared.model.ImageKind value) {
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
	public de.haumacher.imageServer.shared.model.ImagePart setName(String value) {
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
	public de.haumacher.imageServer.shared.model.ImagePart setDate(long value) {
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
	public de.haumacher.imageServer.shared.model.ImagePart setWidth(int value) {
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
	public de.haumacher.imageServer.shared.model.ImagePart setHeight(int value) {
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
	public final de.haumacher.imageServer.shared.model.Orientation getOrientation() {
		return _orientation;
	}

	/**
	 * @see #getOrientation()
	 */
	public de.haumacher.imageServer.shared.model.ImagePart setOrientation(de.haumacher.imageServer.shared.model.Orientation value) {
		internalSetOrientation(value);
		return this;
	}

	/** Internal setter for {@link #getOrientation()} without chain call utility. */
	protected final void internalSetOrientation(de.haumacher.imageServer.shared.model.Orientation value) {
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
	public de.haumacher.imageServer.shared.model.ImagePart setRating(int value) {
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
	public de.haumacher.imageServer.shared.model.ImagePart setPrivacy(int value) {
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
	public de.haumacher.imageServer.shared.model.ImagePart setComment(String value) {
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
	public final de.haumacher.imageServer.shared.model.ImageGroup getGroup() {
		return _group;
	}

	/**
	 * @see #getGroup()
	 */
	public de.haumacher.imageServer.shared.model.ImagePart setGroup(de.haumacher.imageServer.shared.model.ImageGroup value) {
		internalSetGroup(value);
		return this;
	}

	/** Internal setter for {@link #getGroup()} without chain call utility. */
	protected final void internalSetGroup(de.haumacher.imageServer.shared.model.ImageGroup value) {
		_group = value;
	}

	/**
	 * Checks, whether {@link #getGroup()} has a value.
	 */
	public final boolean hasGroup() {
		return _group != null;
	}

	@Override
	public de.haumacher.imageServer.shared.model.ImagePart setPrevious(de.haumacher.imageServer.shared.model.AbstractImage value) {
		internalSetPrevious(value);
		return this;
	}

	@Override
	public de.haumacher.imageServer.shared.model.ImagePart setNext(de.haumacher.imageServer.shared.model.AbstractImage value) {
		internalSetNext(value);
		return this;
	}

	@Override
	public de.haumacher.imageServer.shared.model.ImagePart setHome(de.haumacher.imageServer.shared.model.AbstractImage value) {
		internalSetHome(value);
		return this;
	}

	@Override
	public de.haumacher.imageServer.shared.model.ImagePart setEnd(de.haumacher.imageServer.shared.model.AbstractImage value) {
		internalSetEnd(value);
		return this;
	}

	@Override
	public de.haumacher.imageServer.shared.model.ImagePart setOwner(de.haumacher.imageServer.shared.model.AlbumInfo value) {
		internalSetOwner(value);
		return this;
	}

	@Override
	public String jsonType() {
		return IMAGE_PART__TYPE;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.ImagePart readImagePart(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.ImagePart result = new de.haumacher.imageServer.shared.model.ImagePart();
		result.readContent(in);
		return result;
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name(KIND__PROP);
		getKind().writeTo(out);
		out.name(NAME__PROP);
		out.value(getName());
		out.name(DATE__PROP);
		out.value(getDate());
		out.name(WIDTH__PROP);
		out.value(getWidth());
		out.name(HEIGHT__PROP);
		out.value(getHeight());
		out.name(ORIENTATION__PROP);
		getOrientation().writeTo(out);
		out.name(RATING__PROP);
		out.value(getRating());
		out.name(PRIVACY__PROP);
		out.value(getPrivacy());
		out.name(COMMENT__PROP);
		out.value(getComment());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case KIND__PROP: setKind(de.haumacher.imageServer.shared.model.ImageKind.readImageKind(in)); break;
			case NAME__PROP: setName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case DATE__PROP: setDate(in.nextLong()); break;
			case WIDTH__PROP: setWidth(in.nextInt()); break;
			case HEIGHT__PROP: setHeight(in.nextInt()); break;
			case ORIENTATION__PROP: setOrientation(de.haumacher.imageServer.shared.model.Orientation.readOrientation(in)); break;
			case RATING__PROP: setRating(in.nextInt()); break;
			case PRIVACY__PROP: setPrivacy(in.nextInt()); break;
			case COMMENT__PROP: setComment(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

	@Override
	public <R,A,E extends Throwable> R visit(de.haumacher.imageServer.shared.model.AbstractImage.Visitor<R,A,E> v, A arg) throws E {
		return v.visit(this, arg);
	}

}
