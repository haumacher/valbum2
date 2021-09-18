package de.haumacher.imageServer.shared.model;

public class ThumbnailInfo extends de.haumacher.msgbuf.data.AbstractDataObject implements de.haumacher.msgbuf.binary.BinaryDataObject {

	/**
	 * Creates a {@link ThumbnailInfo} instance.
	 */
	public static ThumbnailInfo create() {
		return new ThumbnailInfo();
	}

	/**
	 * Creates a {@link ThumbnailInfo} instance.
	 *
	 * @see #create()
	 */
	protected ThumbnailInfo() {
		super();
	}

	private String _image = "";

	private double _scale = 0.0d;

	private double _tx = 0.0d;

	private double _ty = 0.0d;

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
	public Object get(String field) {
		switch (field) {
			case "image": return getImage();
			case "scale": return getScale();
			case "tx": return getTx();
			case "ty": return getTy();
			default: return super.get(field);
		}
	}

	@Override
	public void set(String field, Object value) {
		switch (field) {
			case "image": setImage((String) value); break;
			case "scale": setScale((double) value); break;
			case "tx": setTx((double) value); break;
			case "ty": setTy((double) value); break;
		}
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name("image");
		out.value(getImage());
		out.name("scale");
		out.value(getScale());
		out.name("tx");
		out.value(getTx());
		out.name("ty");
		out.value(getTy());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case "image": setImage(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case "scale": setScale(in.nextDouble()); break;
			case "tx": setTx(in.nextDouble()); break;
			case "ty": setTy(in.nextDouble()); break;
			default: super.readField(in, field);
		}
	}

	@Override
	public final void writeTo(de.haumacher.msgbuf.binary.DataWriter out) throws java.io.IOException {
		out.beginObject();
		writeFields(out);
		out.endObject();
	}

	/**
	 * Serializes all fields of this instance to the given binary output.
	 *
	 * @param out
	 *        The binary output to write to.
	 * @throws java.io.IOException If writing fails.
	 */
	protected void writeFields(de.haumacher.msgbuf.binary.DataWriter out) throws java.io.IOException {
		out.name(1);
		out.value(getImage());
		out.name(2);
		out.value(getScale());
		out.name(3);
		out.value(getTx());
		out.name(4);
		out.value(getTy());
	}

	/** Consumes the value for the field with the given ID and assigns its value. */
	protected void readField(de.haumacher.msgbuf.binary.DataReader in, int field) throws java.io.IOException {
		switch (field) {
			case 1: setImage(in.nextString()); break;
			case 2: setScale(in.nextDouble()); break;
			case 3: setTx(in.nextDouble()); break;
			case 4: setTy(in.nextDouble()); break;
			default: in.skipValue(); 
		}
	}

	/** Reads a new instance from the given reader. */
	public static ThumbnailInfo readThumbnailInfo(de.haumacher.msgbuf.binary.DataReader in) throws java.io.IOException {
		in.beginObject();
		ThumbnailInfo result = new ThumbnailInfo();
		while (in.hasNext()) {
			int field = in.nextName();
			result.readField(in, field);
		}
		in.endObject();
		return result;
	}

}
