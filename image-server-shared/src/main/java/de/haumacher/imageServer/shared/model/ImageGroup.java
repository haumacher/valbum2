package de.haumacher.imageServer.shared.model;

/**
 * A group of multiple images showing the same content.
 */
public class ImageGroup extends AbstractImage {

	/**
	 * Creates a {@link ImageGroup} instance.
	 */
	public static ImageGroup create() {
		return new ImageGroup();
	}

	/**
	 * Creates a {@link ImageGroup} instance.
	 *
	 * @see #create()
	 */
	protected ImageGroup() {
		super();
	}

	private int _representative = 0;

	private final java.util.List<ImagePart> _images = new java.util.ArrayList<>();

	/**
	 * The index of the {@link ImageInfo} in {@link #getImages()} of the image that should be displayed when displaying this {@link ImageGroup} in an album.
	 */
	public final int getRepresentative() {
		return _representative;
	}

	/**
	 * @see #getRepresentative()
	 */
	public final ImageGroup setRepresentative(int value) {
		_representative = value;
		return this;
	}

	/**
	 * List of images that all show the same content. Only the image with  in this album.
	 */
	public final java.util.List<ImagePart> getImages() {
		return _images;
	}

	/**
	 * @see #getImages()
	 */
	public final ImageGroup setImages(java.util.List<ImagePart> value) {
		_images.clear();
		_images.addAll(value);
		return this;
	}

	/**
	 * Adds a value to the {@link #getImages()} list.
	 */
	public final ImageGroup addImage(ImagePart value) {
		_images.add(value);
		return this;
	}

	/** Reads a new instance from the given reader. */
	public static ImageGroup readImageGroup(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		ImageGroup result = new ImageGroup();
		in.beginObject();
		result.readFields(in);
		in.endObject();
		return result;
	}

	@Override
	public String jsonType() {
		return "ImageGroup";
	}

	@Override
	public Object get(String field) {
		switch (field) {
			case "representative": return getRepresentative();
			case "images": return getImages();
			default: return super.get(field);
		}
	}

	@Override
	public void set(String field, Object value) {
		switch (field) {
			case "representative": setRepresentative((int) value); break;
			case "images": setImages((java.util.List<ImagePart>) value); break;
			default: super.set(field, value); break;
		}
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name("representative");
		out.value(getRepresentative());
		out.name("images");
		out.beginArray();
		for (ImagePart x : getImages()) {
			x.writeContent(out);
		}
		out.endArray();
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case "representative": setRepresentative(in.nextInt()); break;
			case "images": {
				in.beginArray();
				while (in.hasNext()) {
					addImage(ImagePart.readImagePart(in));
				}
				in.endArray();
			}
			break;
			default: super.readField(in, field);
		}
	}

	@Override
	public int typeId() {
		return 1;
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.binary.DataWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name(1);
		out.value(getRepresentative());
		out.name(2);
		{
			java.util.List<ImagePart> values = getImages();
			out.beginArray(de.haumacher.msgbuf.binary.DataType.OBJECT, values.size());
			for (ImagePart x : values) {
				x.writeTo(out);
			}
			out.endArray();
		}
	}

	@Override
	protected void readField(de.haumacher.msgbuf.binary.DataReader in, int field) throws java.io.IOException {
		switch (field) {
			case 1: setRepresentative(in.nextInt()); break;
			case 2: {
				in.beginArray();
				while (in.hasNext()) {
					addImage(ImagePart.readImagePart(in));
				}
				in.endArray();
			}
			break;
			default: super.readField(in, field);
		}
	}

	/** Reads a new instance from the given reader. */
	public static ImageGroup readImageGroup(de.haumacher.msgbuf.binary.DataReader in) throws java.io.IOException {
		in.beginObject();
		ImageGroup result = new ImageGroup();
		while (in.hasNext()) {
			int field = in.nextName();
			result.readField(in, field);
		}
		in.endObject();
		return result;
	}

	@Override
	public <R,A> R visit(AbstractImage.Visitor<R,A> v, A arg) {
		return v.visit(this, arg);
	}

}
