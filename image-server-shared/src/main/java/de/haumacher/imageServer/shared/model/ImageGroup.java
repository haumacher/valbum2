package de.haumacher.imageServer.shared.model;

/**
 * A group of multiple images showing the same content.
 */
public class ImageGroup extends AbstractImage<ImageGroup> {

	/**
	 * Creates a {@link ImageGroup} instance.
	 */
	public static ImageGroup create() {
		return new ImageGroup();
	}

	/** Identifier for the {@link ImageGroup} type in JSON format. */
	public static final String IMAGE_GROUP__TYPE = "ImageGroup";

	/** @see #getRepresentative() */
	private static final String REPRESENTATIVE = "representative";

	/** @see #getImages() */
	private static final String IMAGES = "images";

	private int _representative = 0;

	private final java.util.List<ImagePart> _images = new java.util.ArrayList<>();

	/**
	 * Creates a {@link ImageGroup} instance.
	 *
	 * @see #create()
	 */
	protected ImageGroup() {
		super();
	}

	@Override
	protected final ImageGroup self() {
		return this;
	}

	@Override
	public TypeKind kind() {
		return TypeKind.IMAGE_GROUP;
	}

	/**
	 * The index of the {@link ImagePart} in {@link #getImages()} of the image that should be displayed when displaying this {@link ImageGroup} in an album.
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
		if (value == null) throw new IllegalArgumentException("Property 'images' cannot be null.");
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

	/**
	 * Removes a value from the {@link #getImages()} list.
	 */
	public final ImageGroup removeImage(ImagePart value) {
		_images.remove(value);
		return this;
	}

	@Override
	public String jsonType() {
		return IMAGE_GROUP__TYPE;
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
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name(REPRESENTATIVE);
		out.value(getRepresentative());
		out.name(IMAGES);
		out.beginArray();
		for (ImagePart x : getImages()) {
			x.writeContent(out);
		}
		out.endArray();
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case REPRESENTATIVE: setRepresentative(in.nextInt()); break;
			case IMAGES: {
				in.beginArray();
				while (in.hasNext()) {
					addImage(de.haumacher.imageServer.shared.model.ImagePart.readImagePart(in));
				}
				in.endArray();
			}
			break;
			default: super.readField(in, field);
		}
	}

	@Override
	public <R,A,E extends Throwable> R visit(AbstractImage.Visitor<R,A,E> v, A arg) throws E {
		return v.visit(this, arg);
	}

}
