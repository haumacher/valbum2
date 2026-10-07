package de.haumacher.imageServer.shared.model;

/**
 * The photograph is a video, or a still picture, see {@link ImagePart#getKind()}.
 */
public class SearchMedia extends SearchCriterion {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.SearchMedia} instance.
	 */
	public static de.haumacher.imageServer.shared.model.SearchMedia create() {
		return new de.haumacher.imageServer.shared.model.SearchMedia();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.SearchMedia} type in JSON format. */
	public static final String SEARCH_MEDIA__TYPE = "SearchMedia";

	/** @see #isVideo() */
	private static final String VIDEO__PROP = "video";

	private boolean _video = false;

	/**
	 * Creates a {@link SearchMedia} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.SearchMedia#create()
	 */
	protected SearchMedia() {
		super();
	}

	@Override
	public TypeKind kind() {
		return TypeKind.SEARCH_MEDIA;
	}

	/**
	 * <code>true</code> for videos, <code>false</code> for still pictures.
	 */
	public final boolean isVideo() {
		return _video;
	}

	/**
	 * @see #isVideo()
	 */
	public de.haumacher.imageServer.shared.model.SearchMedia setVideo(boolean value) {
		internalSetVideo(value);
		return this;
	}

	/** Internal setter for {@link #isVideo()} without chain call utility. */
	protected final void internalSetVideo(boolean value) {
		_video = value;
	}

	@Override
	public String jsonType() {
		return SEARCH_MEDIA__TYPE;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.SearchMedia readSearchMedia(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.SearchMedia result = new de.haumacher.imageServer.shared.model.SearchMedia();
		result.readContent(in);
		return result;
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name(VIDEO__PROP);
		out.value(isVideo());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case VIDEO__PROP: setVideo(in.nextBoolean()); break;
			default: super.readField(in, field);
		}
	}

	@Override
	public <R,A,E extends Throwable> R visit(de.haumacher.imageServer.shared.model.SearchCriterion.Visitor<R,A,E> v, A arg) throws E {
		return v.visit(this, arg);
	}

}
