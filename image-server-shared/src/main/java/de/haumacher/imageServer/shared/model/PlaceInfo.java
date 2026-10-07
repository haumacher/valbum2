package de.haumacher.imageServer.shared.model;

/**
 * The places of an {@link ImagePart}, or why there are none yet, see issue #234.
 */
public class PlaceInfo extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.PlaceInfo} instance.
	 */
	public static de.haumacher.imageServer.shared.model.PlaceInfo create() {
		return new de.haumacher.imageServer.shared.model.PlaceInfo();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.PlaceInfo} type in JSON format. */
	public static final String PLACE_INFO__TYPE = "PlaceInfo";

	/** @see #getTags() */
	private static final String TAGS__PROP = "tags";

	/** @see #getPending() */
	private static final String PENDING__PROP = "pending";

	private final java.util.List<de.haumacher.imageServer.shared.model.PlaceTag> _tags = new java.util.ArrayList<>();

	private String _pending = "";

	/**
	 * Creates a {@link PlaceInfo} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.PlaceInfo#create()
	 */
	protected PlaceInfo() {
		super();
	}

	/**
	 * The GeoNames entries the position lies in or close to, from the largest ({@link PlaceKind#COUNTRY})
	 * to the smallest; empty while {@link #getPending()} says why.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.PlaceTag> getTags() {
		return _tags;
	}

	/**
	 * @see #getTags()
	 */
	public de.haumacher.imageServer.shared.model.PlaceInfo setTags(java.util.List<? extends de.haumacher.imageServer.shared.model.PlaceTag> value) {
		internalSetTags(value);
		return this;
	}

	/** Internal setter for {@link #getTags()} without chain call utility. */
	protected final void internalSetTags(java.util.List<? extends de.haumacher.imageServer.shared.model.PlaceTag> value) {
		if (value == null) throw new IllegalArgumentException("Property 'tags' cannot be null.");
		_tags.clear();
		_tags.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getTags()} list.
	 */
	public de.haumacher.imageServer.shared.model.PlaceInfo addTag(de.haumacher.imageServer.shared.model.PlaceTag value) {
		internalAddTag(value);
		return this;
	}

	/** Implementation of {@link #addTag(de.haumacher.imageServer.shared.model.PlaceTag)} without chain call utility. */
	protected final void internalAddTag(de.haumacher.imageServer.shared.model.PlaceTag value) {
		_tags.add(value);
	}

	/**
	 * Removes a value from the {@link #getTags()} list.
	 */
	public final void removeTag(de.haumacher.imageServer.shared.model.PlaceTag value) {
		_tags.remove(value);
	}

	/**
	 * Why there are no tags yet, a sentence the app shows as it stands ("Place names for China are
	 * being loaded."); the empty string where {@link #getTags()} is the answer.
	 */
	public final String getPending() {
		return _pending;
	}

	/**
	 * @see #getPending()
	 */
	public de.haumacher.imageServer.shared.model.PlaceInfo setPending(String value) {
		internalSetPending(value);
		return this;
	}

	/** Internal setter for {@link #getPending()} without chain call utility. */
	protected final void internalSetPending(String value) {
		_pending = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.PlaceInfo readPlaceInfo(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.PlaceInfo result = new de.haumacher.imageServer.shared.model.PlaceInfo();
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
		out.name(TAGS__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.PlaceTag x : getTags()) {
			x.writeTo(out);
		}
		out.endArray();
		out.name(PENDING__PROP);
		out.value(getPending());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case TAGS__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addTag(de.haumacher.imageServer.shared.model.PlaceTag.readPlaceTag(in));
				}
				in.endArray();
			}
			break;
			case PENDING__PROP: setPending(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
