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

	/** @see #getCamera() */
	private static final String CAMERA__PROP = "camera";

	/** @see #getLocation() */
	private static final String LOCATION__PROP = "location";

	/** @see #getContributor() */
	private static final String CONTRIBUTOR__PROP = "contributor";

	/** @see #getContributorLabel() */
	private static final String CONTRIBUTOR_LABEL__PROP = "contributorLabel";

	/** @see #getFaces() */
	private static final String FACES__PROP = "faces";

	private de.haumacher.imageServer.shared.model.ImageKind _kind = de.haumacher.imageServer.shared.model.ImageKind.IMAGE;

	private String _name = "";

	private long _date = 0L;

	private int _width = 0;

	private int _height = 0;

	private de.haumacher.imageServer.shared.model.Orientation _orientation = de.haumacher.imageServer.shared.model.Orientation.IDENTITY;

	private int _rating = 0;

	private int _privacy = 0;

	private String _comment = "";

	private String _camera = "";

	private de.haumacher.imageServer.shared.model.GeoLocation _location = null;

	private transient de.haumacher.imageServer.shared.model.ImageGroup _group = null;

	private String _contributor = "";

	private String _contributorLabel = "";

	private final java.util.List<de.haumacher.imageServer.shared.model.FaceInfo> _faces = new java.util.ArrayList<>();

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
	 * The camera that took this image, see issue #78.
	 *
	 * <p>
	 * A short label built from the EXIF <code>Make</code> and <code>Model</code> of the original:
	 * both trimmed, joined with a single blank, and the make left out when the model already
	 * starts with it (<code>Canon</code> and <code>Canon EOS 5D</code> make
	 * <code>Canon EOS 5D</code>, not <code>Canon Canon EOS 5D</code>). Phones say the same way
	 * what they are (<code>SAMSUNG SM-G991B</code>). The empty string when the file says neither,
	 * which is the case for every video whose container carries no make and model.
	 * </p>
	 *
	 * <p>
	 * A label, not an identifier: it is only ever compared for equality, to select every image of
	 * one camera, and an empty label never matches another empty one.
	 * </p>
	 *
	 * <p>
	 * Read when the image is analysed and <em>stored</em> in the sidecar, exactly like
	 * {@link #getDate()}: a part a sidecar already lists is never analysed again, so an album written
	 * before this field existed keeps its parts without one until they are analysed afresh.
	 * </p>
	 */
	public final String getCamera() {
		return _camera;
	}

	/**
	 * @see #getCamera()
	 */
	public de.haumacher.imageServer.shared.model.ImagePart setCamera(String value) {
		internalSetCamera(value);
		return this;
	}

	/** Internal setter for {@link #getCamera()} without chain call utility. */
	protected final void internalSetCamera(String value) {
		_camera = value;
	}

	/**
	 * Where this image was taken, <code>null</code> when the file says nowhere (issue #112).
	 *
	 * <p>
	 * Read from the EXIF GPS tags of the original when the image is analysed, and from the
	 * container of a video where that carries a position. The absent message is what "the file
	 * carries no position" means — see {@link GeoLocation}, where a pair of zeroes would be a real
	 * place off the coast of Africa.
	 * </p>
	 *
	 * <p>
	 * <em>Stored</em> in the sidecar, exactly like {@link #getDate() date} and {@link #getCamera()
	 * camera}: a part a sidecar already lists is never analysed again, so an album written before
	 * this field existed keeps its parts without a position until they are analysed afresh. A
	 * round trip read &rarr; write &rarr; read keeps it unchanged, so a client that stores an
	 * album back never loses where its photos were taken.
	 * </p>
	 *
	 * <p>
	 * It is answered to whoever may see the image and to nobody else: the position follows the
	 * image's own {@link #getPrivacy() privacy level} and nothing besides, so a caller the
	 * {@link #getPrivacy() privacy} filter hands the image to is handed its position with it.
	 * </p>
	 */
	public final de.haumacher.imageServer.shared.model.GeoLocation getLocation() {
		return _location;
	}

	/**
	 * @see #getLocation()
	 */
	public de.haumacher.imageServer.shared.model.ImagePart setLocation(de.haumacher.imageServer.shared.model.GeoLocation value) {
		internalSetLocation(value);
		return this;
	}

	/** Internal setter for {@link #getLocation()} without chain call utility. */
	protected final void internalSetLocation(de.haumacher.imageServer.shared.model.GeoLocation value) {
		_location = value;
	}

	/**
	 * Checks, whether {@link #getLocation()} has a value.
	 */
	public final boolean hasLocation() {
		return _location != null;
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

	/**
	 * Who uploaded this image, see issue #53.
	 *
	 * <p>
	 * The subject of the caller that stored the file: <code>user:&lt;name&gt;</code>,
	 * <code>token:&lt;id&gt;</code> for a contribution made through a share link, or
	 * <code>anonymous</code> on a server running without authentication. The empty string for a
	 * photo that never came through an upload — one that was in the folder before this build, or
	 * that was copied in with a file manager.
	 * </p>
	 *
	 * <p>
	 * Recorded once, at the upload, in the hash sidecar beside the photos, and carried along when
	 * the photo is moved to another folder. An upload of contents the folder already holds keeps
	 * the first contributor: whoever brought the photo here is who brought it here.
	 * </p>
	 *
	 * <p>
	 * Derived by the server on every read, exactly like {@link AlbumInfo#getEffectiveDate()}, and never
	 * stored: the server clears this field before an <code>index.json</code> is written, so that a
	 * round trip through a client can neither freeze an attribution into the album nor lose one.
	 * </p>
	 */
	public final String getContributor() {
		return _contributor;
	}

	/**
	 * @see #getContributor()
	 */
	public de.haumacher.imageServer.shared.model.ImagePart setContributor(String value) {
		internalSetContributor(value);
		return this;
	}

	/** Internal setter for {@link #getContributor()} without chain call utility. */
	protected final void internalSetContributor(String value) {
		_contributor = value;
	}

	/**
	 * What to show as the contributor of this image, see issue #53 and {@link #getContributor()}.
	 *
	 * <p>
	 * The name of the user, or the label of the share link a guest contributed through, as it
	 * stood at the moment of the upload; the empty string when nothing is known. The label is
	 * copied rather than looked up, so a link that was renamed or withdrawn still says who
	 * contributed.
	 * </p>
	 *
	 * <p>
	 * Derived on every read and never stored, exactly like {@link #getContributor()}.
	 * </p>
	 */
	public final String getContributorLabel() {
		return _contributorLabel;
	}

	/**
	 * @see #getContributorLabel()
	 */
	public de.haumacher.imageServer.shared.model.ImagePart setContributorLabel(String value) {
		internalSetContributorLabel(value);
		return this;
	}

	/** Internal setter for {@link #getContributorLabel()} without chain call utility. */
	protected final void internalSetContributorLabel(String value) {
		_contributorLabel = value;
	}

	/**
	 * The faces the server found in this photograph, see issue #124.
	 *
	 * <p>
	 * Empty for a video (videos are never looked at), for a space whose face index is switched off,
	 * and for every caller that is not a signed-in member: an anonymous visitor of an open space and
	 * a share link are answered no face at all, in the spirit of issue #96.
	 * </p>
	 *
	 * <p>
	 * Derived on every read from the album's <code>.vacache/faces.json</code> and never stored: the
	 * server clears this field before an <code>index.json</code> is written, exactly like
	 * {@link #getContributor()}, so a round trip through a client can neither freeze a detection into the
	 * album nor lose one. The embeddings the detection produced never leave the server.
	 * </p>
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.FaceInfo> getFaces() {
		return _faces;
	}

	/**
	 * @see #getFaces()
	 */
	public de.haumacher.imageServer.shared.model.ImagePart setFaces(java.util.List<? extends de.haumacher.imageServer.shared.model.FaceInfo> value) {
		internalSetFaces(value);
		return this;
	}

	/** Internal setter for {@link #getFaces()} without chain call utility. */
	protected final void internalSetFaces(java.util.List<? extends de.haumacher.imageServer.shared.model.FaceInfo> value) {
		if (value == null) throw new IllegalArgumentException("Property 'faces' cannot be null.");
		_faces.clear();
		_faces.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getFaces()} list.
	 */
	public de.haumacher.imageServer.shared.model.ImagePart addFace(de.haumacher.imageServer.shared.model.FaceInfo value) {
		internalAddFace(value);
		return this;
	}

	/** Implementation of {@link #addFace(de.haumacher.imageServer.shared.model.FaceInfo)} without chain call utility. */
	protected final void internalAddFace(de.haumacher.imageServer.shared.model.FaceInfo value) {
		_faces.add(value);
	}

	/**
	 * Removes a value from the {@link #getFaces()} list.
	 */
	public final void removeFace(de.haumacher.imageServer.shared.model.FaceInfo value) {
		_faces.remove(value);
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
		out.name(CAMERA__PROP);
		out.value(getCamera());
		if (hasLocation()) {
			out.name(LOCATION__PROP);
			getLocation().writeTo(out);
		}
		out.name(CONTRIBUTOR__PROP);
		out.value(getContributor());
		out.name(CONTRIBUTOR_LABEL__PROP);
		out.value(getContributorLabel());
		out.name(FACES__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.FaceInfo x : getFaces()) {
			x.writeTo(out);
		}
		out.endArray();
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
			case CAMERA__PROP: setCamera(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case LOCATION__PROP: setLocation(de.haumacher.imageServer.shared.model.GeoLocation.readGeoLocation(in)); break;
			case CONTRIBUTOR__PROP: setContributor(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case CONTRIBUTOR_LABEL__PROP: setContributorLabel(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case FACES__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addFace(de.haumacher.imageServer.shared.model.FaceInfo.readFaceInfo(in));
				}
				in.endArray();
			}
			break;
			default: super.readField(in, field);
		}
	}

	@Override
	public <R,A,E extends Throwable> R visit(de.haumacher.imageServer.shared.model.AbstractImage.Visitor<R,A,E> v, A arg) throws E {
		return v.visit(this, arg);
	}

}
