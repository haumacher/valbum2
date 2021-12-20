package de.haumacher.imageServer.shared.model;

/**
 * Part of a {@link FolderInfo} describing the thumbnail image for displaying this folder in a {@link ListingInfo}.
 */
public class ThumbnailInfo extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link ThumbnailInfo} instance.
	 */
	public static ThumbnailInfo create() {
		return new ThumbnailInfo();
	}

	/** Identifier for the {@link ThumbnailInfo} type in JSON format. */
	public static final String THUMBNAIL_INFO__TYPE = "ThumbnailInfo";

	/** @see #getImage() */
	private static final String IMAGE = "image";

	/** @see #getScale() */
	private static final String SCALE = "scale";

	/** @see #getTx() */
	private static final String TX = "tx";

	/** @see #getTy() */
	private static final String TY = "ty";

	private String _image = "";

	private double _scale = 0.0d;

	private double _tx = 0.0d;

	private double _ty = 0.0d;

	/**
	 * Creates a {@link ThumbnailInfo} instance.
	 *
	 * @see #create()
	 */
	protected ThumbnailInfo() {
		super();
	}

	/**
	 * Name of the image to use as thumbnail.
	 */
	public final String getImage() {
		return _image;
	}

	/**
	 * @see #getImage()
	 */
	public final ThumbnailInfo setImage(String value) {
		_image = value;
		return this;
	}

	/**
	 * The factor to scale the original image for producing the thumbnail image.
	 */
	public final double getScale() {
		return _scale;
	}

	/**
	 * @see #getScale()
	 */
	public final ThumbnailInfo setScale(double value) {
		_scale = value;
		return this;
	}

	/**
	 * The translation in X to apply to the the original image for producing the thumbnail image.
	 */
	public final double getTx() {
		return _tx;
	}

	/**
	 * @see #getTx()
	 */
	public final ThumbnailInfo setTx(double value) {
		_tx = value;
		return this;
	}

	/**
	 * The translation in Y to apply to the the original image for producing the thumbnail image.
	 */
	public final double getTy() {
		return _ty;
	}

	/**
	 * @see #getTy()
	 */
	public final ThumbnailInfo setTy(double value) {
		_ty = value;
		return this;
	}

	/** Reads a new instance from the given reader. */
	public static ThumbnailInfo readThumbnailInfo(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		ThumbnailInfo result = new ThumbnailInfo();
		in.beginObject();
		result.readFields(in);
		in.endObject();
		return result;
	}

	@Override
	public final void writeTo(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		writeContent(out);
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name(IMAGE);
		out.value(getImage());
		out.name(SCALE);
		out.value(getScale());
		out.name(TX);
		out.value(getTx());
		out.name(TY);
		out.value(getTy());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case IMAGE: setImage(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case SCALE: setScale(in.nextDouble()); break;
			case TX: setTx(in.nextDouble()); break;
			case TY: setTy(in.nextDouble()); break;
			default: super.readField(in, field);
		}
	}

}
