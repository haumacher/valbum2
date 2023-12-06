package de.haumacher.imageServer.shared.model;

/**
 * A group of multiple images showing the same content.
 */
public class ImageGroup extends AbstractImage {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.ImageGroup} instance.
	 */
	public static de.haumacher.imageServer.shared.model.ImageGroup create() {
		return new de.haumacher.imageServer.shared.model.ImageGroup();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.ImageGroup} type in JSON format. */
	public static final String IMAGE_GROUP__TYPE = "ImageGroup";

	/** @see #getRepresentative() */
	private static final String REPRESENTATIVE__PROP = "representative";

	/** @see #getImages() */
	private static final String IMAGES__PROP = "images";

	private int _representative = 0;

	private final java.util.List<de.haumacher.imageServer.shared.model.ImagePart> _images = new java.util.ArrayList<>();

	/**
	 * Creates a {@link ImageGroup} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.ImageGroup#create()
	 */
	protected ImageGroup() {
		super();
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
	public de.haumacher.imageServer.shared.model.ImageGroup setRepresentative(int value) {
		internalSetRepresentative(value);
		return this;
	}

	/** Internal setter for {@link #getRepresentative()} without chain call utility. */
	protected final void internalSetRepresentative(int value) {
		_representative = value;
	}

	/**
	 * List of images that all show the same content. Only the image with  in this album.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.ImagePart> getImages() {
		return _images;
	}

	/**
	 * @see #getImages()
	 */
	public de.haumacher.imageServer.shared.model.ImageGroup setImages(java.util.List<? extends de.haumacher.imageServer.shared.model.ImagePart> value) {
		internalSetImages(value);
		return this;
	}

	/** Internal setter for {@link #getImages()} without chain call utility. */
	protected final void internalSetImages(java.util.List<? extends de.haumacher.imageServer.shared.model.ImagePart> value) {
		if (value == null) throw new IllegalArgumentException("Property 'images' cannot be null.");
		_images.clear();
		_images.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getImages()} list.
	 */
	public de.haumacher.imageServer.shared.model.ImageGroup addImage(de.haumacher.imageServer.shared.model.ImagePart value) {
		internalAddImage(value);
		return this;
	}

	/** Implementation of {@link #addImage(de.haumacher.imageServer.shared.model.ImagePart)} without chain call utility. */
	protected final void internalAddImage(de.haumacher.imageServer.shared.model.ImagePart value) {
		_images.add(value);
	}

	/**
	 * Removes a value from the {@link #getImages()} list.
	 */
	public final void removeImage(de.haumacher.imageServer.shared.model.ImagePart value) {
		_images.remove(value);
	}

	@Override
	public de.haumacher.imageServer.shared.model.ImageGroup setPrevious(de.haumacher.imageServer.shared.model.AbstractImage value) {
		internalSetPrevious(value);
		return this;
	}

	@Override
	public de.haumacher.imageServer.shared.model.ImageGroup setNext(de.haumacher.imageServer.shared.model.AbstractImage value) {
		internalSetNext(value);
		return this;
	}

	@Override
	public de.haumacher.imageServer.shared.model.ImageGroup setHome(de.haumacher.imageServer.shared.model.AbstractImage value) {
		internalSetHome(value);
		return this;
	}

	@Override
	public de.haumacher.imageServer.shared.model.ImageGroup setEnd(de.haumacher.imageServer.shared.model.AbstractImage value) {
		internalSetEnd(value);
		return this;
	}

	@Override
	public de.haumacher.imageServer.shared.model.ImageGroup setOwner(de.haumacher.imageServer.shared.model.AlbumInfo value) {
		internalSetOwner(value);
		return this;
	}

	@Override
	public String jsonType() {
		return IMAGE_GROUP__TYPE;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.ImageGroup readImageGroup(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.ImageGroup result = new de.haumacher.imageServer.shared.model.ImageGroup();
		result.readContent(in);
		return result;
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name(REPRESENTATIVE__PROP);
		out.value(getRepresentative());
		out.name(IMAGES__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.ImagePart x : getImages()) {
			x.writeContent(out);
		}
		out.endArray();
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case REPRESENTATIVE__PROP: setRepresentative(in.nextInt()); break;
			case IMAGES__PROP: {
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
	public <R,A,E extends Throwable> R visit(de.haumacher.imageServer.shared.model.AbstractImage.Visitor<R,A,E> v, A arg) throws E {
		return v.visit(this, arg);
	}

}
