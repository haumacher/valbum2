package de.haumacher.imageServer.shared.model;

/**
 * Photographs of a {@link SimilarList} that look alike, in at least two albums.
 */
public class SimilarGroup extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.SimilarGroup} instance.
	 */
	public static de.haumacher.imageServer.shared.model.SimilarGroup create() {
		return new de.haumacher.imageServer.shared.model.SimilarGroup();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.SimilarGroup} type in JSON format. */
	public static final String SIMILAR_GROUP__TYPE = "SimilarGroup";

	/** @see #getDate() */
	private static final String DATE__PROP = "date";

	/** @see #getPhotos() */
	private static final String PHOTOS__PROP = "photos";

	private long _date = 0L;

	private final java.util.List<de.haumacher.imageServer.shared.model.SimilarPhoto> _photos = new java.util.ArrayList<>();

	/**
	 * Creates a {@link SimilarGroup} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.SimilarGroup#create()
	 */
	protected SimilarGroup() {
		super();
	}

	/**
	 * The date of the first photograph, milliseconds since the epoch, 0 if unknown.
	 */
	public final long getDate() {
		return _date;
	}

	/**
	 * @see #getDate()
	 */
	public de.haumacher.imageServer.shared.model.SimilarGroup setDate(long value) {
		internalSetDate(value);
		return this;
	}

	/** Internal setter for {@link #getDate()} without chain call utility. */
	protected final void internalSetDate(long value) {
		_date = value;
	}

	/**
	 * The photographs, at least two, by their album's date, then its path, then their name.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.SimilarPhoto> getPhotos() {
		return _photos;
	}

	/**
	 * @see #getPhotos()
	 */
	public de.haumacher.imageServer.shared.model.SimilarGroup setPhotos(java.util.List<? extends de.haumacher.imageServer.shared.model.SimilarPhoto> value) {
		internalSetPhotos(value);
		return this;
	}

	/** Internal setter for {@link #getPhotos()} without chain call utility. */
	protected final void internalSetPhotos(java.util.List<? extends de.haumacher.imageServer.shared.model.SimilarPhoto> value) {
		if (value == null) throw new IllegalArgumentException("Property 'photos' cannot be null.");
		_photos.clear();
		_photos.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getPhotos()} list.
	 */
	public de.haumacher.imageServer.shared.model.SimilarGroup addPhoto(de.haumacher.imageServer.shared.model.SimilarPhoto value) {
		internalAddPhoto(value);
		return this;
	}

	/** Implementation of {@link #addPhoto(de.haumacher.imageServer.shared.model.SimilarPhoto)} without chain call utility. */
	protected final void internalAddPhoto(de.haumacher.imageServer.shared.model.SimilarPhoto value) {
		_photos.add(value);
	}

	/**
	 * Removes a value from the {@link #getPhotos()} list.
	 */
	public final void removePhoto(de.haumacher.imageServer.shared.model.SimilarPhoto value) {
		_photos.remove(value);
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.SimilarGroup readSimilarGroup(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.SimilarGroup result = new de.haumacher.imageServer.shared.model.SimilarGroup();
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
		out.name(DATE__PROP);
		out.value(getDate());
		out.name(PHOTOS__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.SimilarPhoto x : getPhotos()) {
			x.writeTo(out);
		}
		out.endArray();
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case DATE__PROP: setDate(in.nextLong()); break;
			case PHOTOS__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addPhoto(de.haumacher.imageServer.shared.model.SimilarPhoto.readSimilarPhoto(in));
				}
				in.endArray();
			}
			break;
			default: super.readField(in, field);
		}
	}

}
