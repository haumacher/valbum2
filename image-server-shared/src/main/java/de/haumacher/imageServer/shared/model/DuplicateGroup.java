package de.haumacher.imageServer.shared.model;

/**
 * One photograph of a {@link DuplicateList}, with every album it lies in.
 */
public class DuplicateGroup extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.DuplicateGroup} instance.
	 */
	public static de.haumacher.imageServer.shared.model.DuplicateGroup create() {
		return new de.haumacher.imageServer.shared.model.DuplicateGroup();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.DuplicateGroup} type in JSON format. */
	public static final String DUPLICATE_GROUP__TYPE = "DuplicateGroup";

	/** @see #getHash() */
	private static final String HASH__PROP = "hash";

	/** @see #getDate() */
	private static final String DATE__PROP = "date";

	/** @see #getCopies() */
	private static final String COPIES__PROP = "copies";

	/** @see #getImage() */
	private static final String IMAGE__PROP = "image";

	private String _hash = "";

	private long _date = 0L;

	private final java.util.List<de.haumacher.imageServer.shared.model.DuplicateCopy> _copies = new java.util.ArrayList<>();

	private de.haumacher.imageServer.shared.model.ImagePart _image = null;

	/**
	 * Creates a {@link DuplicateGroup} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.DuplicateGroup#create()
	 */
	protected DuplicateGroup() {
		super();
	}

	/**
	 * The SHA-256 hash (lower-case hex) of the content the group was found by; a stable key, and
	 * nothing to address. Of a raw photograph standing beside its JPEG (issue #191) one of the two.
	 */
	public final String getHash() {
		return _hash;
	}

	/**
	 * @see #getHash()
	 */
	public de.haumacher.imageServer.shared.model.DuplicateGroup setHash(String value) {
		internalSetHash(value);
		return this;
	}

	/** Internal setter for {@link #getHash()} without chain call utility. */
	protected final void internalSetHash(String value) {
		_hash = value;
	}

	/**
	 * The date of the photograph (of its first copy), milliseconds since the epoch, 0 if unknown.
	 */
	public final long getDate() {
		return _date;
	}

	/**
	 * @see #getDate()
	 */
	public de.haumacher.imageServer.shared.model.DuplicateGroup setDate(long value) {
		internalSetDate(value);
		return this;
	}

	/** Internal setter for {@link #getDate()} without chain call utility. */
	protected final void internalSetDate(long value) {
		_date = value;
	}

	/**
	 * Every visible copy, at least two, in different albums; the first one stands for the group.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.DuplicateCopy> getCopies() {
		return _copies;
	}

	/**
	 * @see #getCopies()
	 */
	public de.haumacher.imageServer.shared.model.DuplicateGroup setCopies(java.util.List<? extends de.haumacher.imageServer.shared.model.DuplicateCopy> value) {
		internalSetCopies(value);
		return this;
	}

	/** Internal setter for {@link #getCopies()} without chain call utility. */
	protected final void internalSetCopies(java.util.List<? extends de.haumacher.imageServer.shared.model.DuplicateCopy> value) {
		if (value == null) throw new IllegalArgumentException("Property 'copies' cannot be null.");
		_copies.clear();
		_copies.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getCopies()} list.
	 */
	public de.haumacher.imageServer.shared.model.DuplicateGroup addCopie(de.haumacher.imageServer.shared.model.DuplicateCopy value) {
		internalAddCopie(value);
		return this;
	}

	/** Implementation of {@link #addCopie(de.haumacher.imageServer.shared.model.DuplicateCopy)} without chain call utility. */
	protected final void internalAddCopie(de.haumacher.imageServer.shared.model.DuplicateCopy value) {
		_copies.add(value);
	}

	/**
	 * Removes a value from the {@link #getCopies()} list.
	 */
	public final void removeCopie(de.haumacher.imageServer.shared.model.DuplicateCopy value) {
		_copies.remove(value);
	}

	/**
	 * The photograph of the first copy, as much as its thumbnail needs: name, kind, date, size,
	 * orientation and crop &mdash; nothing else of the album's description.
	 */
	public final de.haumacher.imageServer.shared.model.ImagePart getImage() {
		return _image;
	}

	/**
	 * @see #getImage()
	 */
	public de.haumacher.imageServer.shared.model.DuplicateGroup setImage(de.haumacher.imageServer.shared.model.ImagePart value) {
		internalSetImage(value);
		return this;
	}

	/** Internal setter for {@link #getImage()} without chain call utility. */
	protected final void internalSetImage(de.haumacher.imageServer.shared.model.ImagePart value) {
		_image = value;
	}

	/**
	 * Checks, whether {@link #getImage()} has a value.
	 */
	public final boolean hasImage() {
		return _image != null;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.DuplicateGroup readDuplicateGroup(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.DuplicateGroup result = new de.haumacher.imageServer.shared.model.DuplicateGroup();
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
		out.name(HASH__PROP);
		out.value(getHash());
		out.name(DATE__PROP);
		out.value(getDate());
		out.name(COPIES__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.DuplicateCopy x : getCopies()) {
			x.writeTo(out);
		}
		out.endArray();
		if (hasImage()) {
			out.name(IMAGE__PROP);
			getImage().writeContent(out);
		}
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case HASH__PROP: setHash(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case DATE__PROP: setDate(in.nextLong()); break;
			case COPIES__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addCopie(de.haumacher.imageServer.shared.model.DuplicateCopy.readDuplicateCopy(in));
				}
				in.endArray();
			}
			break;
			case IMAGE__PROP: setImage(de.haumacher.imageServer.shared.model.ImagePart.readImagePart(in)); break;
			default: super.readField(in, field);
		}
	}

}
