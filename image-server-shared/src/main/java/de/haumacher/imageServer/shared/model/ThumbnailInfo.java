package de.haumacher.imageServer.shared.model;

/**
 * Part of a {@link FolderInfo} describing the thumbnail image for displaying this folder in a {@link ListingInfo}.
 */
public class ThumbnailInfo extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.ThumbnailInfo} instance.
	 */
	public static de.haumacher.imageServer.shared.model.ThumbnailInfo create() {
		return new de.haumacher.imageServer.shared.model.ThumbnailInfo();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.ThumbnailInfo} type in JSON format. */
	public static final String THUMBNAIL_INFO__TYPE = "ThumbnailInfo";

	/** @see #getImage() */
	private static final String IMAGE__PROP = "image";

	/** @see #getScale() */
	private static final String SCALE__PROP = "scale";

	/** @see #getTx() */
	private static final String TX__PROP = "tx";

	/** @see #getTy() */
	private static final String TY__PROP = "ty";

	private String _image = "";

	private double _scale = 0.0d;

	private double _tx = 0.0d;

	private double _ty = 0.0d;

	/**
	 * Creates a {@link ThumbnailInfo} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.ThumbnailInfo#create()
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
	public de.haumacher.imageServer.shared.model.ThumbnailInfo setImage(String value) {
		internalSetImage(value);
		return this;
	}

	/** Internal setter for {@link #getImage()} without chain call utility. */
	protected final void internalSetImage(String value) {
		_image = value;
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
	public de.haumacher.imageServer.shared.model.ThumbnailInfo setScale(double value) {
		internalSetScale(value);
		return this;
	}

	/** Internal setter for {@link #getScale()} without chain call utility. */
	protected final void internalSetScale(double value) {
		_scale = value;
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
	public de.haumacher.imageServer.shared.model.ThumbnailInfo setTx(double value) {
		internalSetTx(value);
		return this;
	}

	/** Internal setter for {@link #getTx()} without chain call utility. */
	protected final void internalSetTx(double value) {
		_tx = value;
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
	public de.haumacher.imageServer.shared.model.ThumbnailInfo setTy(double value) {
		internalSetTy(value);
		return this;
	}

	/** Internal setter for {@link #getTy()} without chain call utility. */
	protected final void internalSetTy(double value) {
		_ty = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.ThumbnailInfo readThumbnailInfo(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.ThumbnailInfo result = new de.haumacher.imageServer.shared.model.ThumbnailInfo();
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
		out.name(IMAGE__PROP);
		out.value(getImage());
		out.name(SCALE__PROP);
		out.value(getScale());
		out.name(TX__PROP);
		out.value(getTx());
		out.name(TY__PROP);
		out.value(getTy());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case IMAGE__PROP: setImage(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case SCALE__PROP: setScale(in.nextDouble()); break;
			case TX__PROP: setTx(in.nextDouble()); break;
			case TY__PROP: setTy(in.nextDouble()); break;
			default: super.readField(in, field);
		}
	}

}
