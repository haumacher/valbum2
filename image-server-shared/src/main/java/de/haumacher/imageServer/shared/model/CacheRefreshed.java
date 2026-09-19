package de.haumacher.imageServer.shared.model;

/**
 * Answer to <code>&lt;folder&gt;/?action=refresh-cache</code>, see issue #98.
 *
 * <p>
 * An administrator throws the generated files of one folder away — the thumbnails and the video
 * renditions the server made itself — and the server makes them anew the next time they are asked
 * for. Nothing else in the folder is touched, so the number below is the whole of what happened.
 * </p>
 */
public class CacheRefreshed extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.CacheRefreshed} instance.
	 */
	public static de.haumacher.imageServer.shared.model.CacheRefreshed create() {
		return new de.haumacher.imageServer.shared.model.CacheRefreshed();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.CacheRefreshed} type in JSON format. */
	public static final String CACHE_REFRESHED__TYPE = "CacheRefreshed";

	/** @see #getRemoved() */
	private static final String REMOVED__PROP = "removed";

	private int _removed = 0;

	/**
	 * Creates a {@link CacheRefreshed} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.CacheRefreshed#create()
	 */
	protected CacheRefreshed() {
		super();
	}

	/**
	 * How many generated files were deleted; zero when the folder had no cache at all.
	 *
	 * <p>
	 * Worth showing: it is the only evidence the caller gets that the broken thumbnail they were
	 * looking at is really gone.
	 * </p>
	 */
	public final int getRemoved() {
		return _removed;
	}

	/**
	 * @see #getRemoved()
	 */
	public de.haumacher.imageServer.shared.model.CacheRefreshed setRemoved(int value) {
		internalSetRemoved(value);
		return this;
	}

	/** Internal setter for {@link #getRemoved()} without chain call utility. */
	protected final void internalSetRemoved(int value) {
		_removed = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.CacheRefreshed readCacheRefreshed(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.CacheRefreshed result = new de.haumacher.imageServer.shared.model.CacheRefreshed();
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
		out.name(REMOVED__PROP);
		out.value(getRemoved());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case REMOVED__PROP: setRemoved(in.nextInt()); break;
			default: super.readField(in, field);
		}
	}

}
