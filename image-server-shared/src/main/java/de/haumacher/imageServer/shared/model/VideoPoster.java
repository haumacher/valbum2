package de.haumacher.imageServer.shared.model;

/**
 * The moment of a video its poster shows, see issue #285 and {@link ImagePart#getPoster()}.
 *
 * <p>
 * A message rather than a number beside the other fields of the part, so that a video without a
 * chosen moment carries nothing at all in its sidecar, exactly as a sidecar written before this
 * existed.
 * </p>
 */
public class VideoPoster extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.VideoPoster} instance.
	 */
	public static de.haumacher.imageServer.shared.model.VideoPoster create() {
		return new de.haumacher.imageServer.shared.model.VideoPoster();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.VideoPoster} type in JSON format. */
	public static final String VIDEO_POSTER__TYPE = "VideoPoster";

	/** @see #getMillis() */
	private static final String MILLIS__PROP = "millis";

	private long _millis = 0L;

	/**
	 * Creates a {@link VideoPoster} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.VideoPoster#create()
	 */
	protected VideoPoster() {
		super();
	}

	/**
	 * The position in the video in milliseconds from its start: the poster is the frame shown at
	 * this moment (the first one at or after it), never the key frame before it.
	 */
	public final long getMillis() {
		return _millis;
	}

	/**
	 * @see #getMillis()
	 */
	public de.haumacher.imageServer.shared.model.VideoPoster setMillis(long value) {
		internalSetMillis(value);
		return this;
	}

	/** Internal setter for {@link #getMillis()} without chain call utility. */
	protected final void internalSetMillis(long value) {
		_millis = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.VideoPoster readVideoPoster(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.VideoPoster result = new de.haumacher.imageServer.shared.model.VideoPoster();
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
		out.name(MILLIS__PROP);
		out.value(getMillis());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case MILLIS__PROP: setMillis(in.nextLong()); break;
			default: super.readField(in, field);
		}
	}

}
