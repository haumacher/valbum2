package de.haumacher.imageServer.shared.model;

/**
 * The region of a photograph that is shown, see issue #212.
 *
 * <p>
 * Normalised to <code>0..1</code> of the picture <em>as it is shown</em>: the file's own EXIF
 * orientation applied and the {@link ImagePart#getOrientation() orientation} stored beside it
 * applied too &mdash; the upright frame the crop editor draws the picture in, so that
 * {@link #getW()}&nbsp;&times;&nbsp;{@link #getH()} of the shown picture is the aspect the album lays the
 * tile out at. The rectangle lies inside the picture (<code>x + w &lt;= 1</code>,
 * <code>y + h &lt;= 1</code>) and has a positive width and height.
 * </p>
 *
 * <p>
 * Where the same rectangle is spoken of in the frame of the server's rendition (the file upright,
	 * the stored orientation <em>not</em> applied &mdash; the frame of a <code>?type=tn</code> and of
 * every face box), it is named as such, see {@link ThumbnailInfo#getCrop()}.
 * </p>
 */
public class Crop extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.Crop} instance.
	 */
	public static de.haumacher.imageServer.shared.model.Crop create() {
		return new de.haumacher.imageServer.shared.model.Crop();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.Crop} type in JSON format. */
	public static final String CROP__TYPE = "Crop";

	/** @see #getX() */
	private static final String X__PROP = "x";

	/** @see #getY() */
	private static final String Y__PROP = "y";

	/** @see #getW() */
	private static final String W__PROP = "w";

	/** @see #getH() */
	private static final String H__PROP = "h";

	private double _x = 0.0d;

	private double _y = 0.0d;

	private double _w = 0.0d;

	private double _h = 0.0d;

	/**
	 * Creates a {@link Crop} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.Crop#create()
	 */
	protected Crop() {
		super();
	}

	/**
	 * The left edge, as a fraction of the shown picture's width.
	 */
	public final double getX() {
		return _x;
	}

	/**
	 * @see #getX()
	 */
	public de.haumacher.imageServer.shared.model.Crop setX(double value) {
		internalSetX(value);
		return this;
	}

	/** Internal setter for {@link #getX()} without chain call utility. */
	protected final void internalSetX(double value) {
		_x = value;
	}

	/**
	 * The top edge, as a fraction of the shown picture's height.
	 */
	public final double getY() {
		return _y;
	}

	/**
	 * @see #getY()
	 */
	public de.haumacher.imageServer.shared.model.Crop setY(double value) {
		internalSetY(value);
		return this;
	}

	/** Internal setter for {@link #getY()} without chain call utility. */
	protected final void internalSetY(double value) {
		_y = value;
	}

	/**
	 * The width, as a fraction of the shown picture's width.
	 */
	public final double getW() {
		return _w;
	}

	/**
	 * @see #getW()
	 */
	public de.haumacher.imageServer.shared.model.Crop setW(double value) {
		internalSetW(value);
		return this;
	}

	/** Internal setter for {@link #getW()} without chain call utility. */
	protected final void internalSetW(double value) {
		_w = value;
	}

	/**
	 * The height, as a fraction of the shown picture's height.
	 */
	public final double getH() {
		return _h;
	}

	/**
	 * @see #getH()
	 */
	public de.haumacher.imageServer.shared.model.Crop setH(double value) {
		internalSetH(value);
		return this;
	}

	/** Internal setter for {@link #getH()} without chain call utility. */
	protected final void internalSetH(double value) {
		_h = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.Crop readCrop(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.Crop result = new de.haumacher.imageServer.shared.model.Crop();
		result.readContent(in);
		return result;
	}

	@Override
	public final void writeTo(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		writeContent(out);
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name(X__PROP);
		out.value(getX());
		out.name(Y__PROP);
		out.value(getY());
		out.name(W__PROP);
		out.value(getW());
		out.name(H__PROP);
		out.value(getH());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case X__PROP: setX(in.nextDouble()); break;
			case Y__PROP: setY(in.nextDouble()); break;
			case W__PROP: setW(in.nextDouble()); break;
			case H__PROP: setH(in.nextDouble()); break;
			default: super.readField(in, field);
		}
	}

}
