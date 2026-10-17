package de.haumacher.imageServer.shared.model;

/**
 * What <code>&lt;folder&gt;/?type=image-count</code> answers, see issue #286: how many photographs
 * the caller is shown on opening the folder.
 *
 * <p>
 * The number {@link FolderInfo#getImageCount()} gives where a listing can afford it, asked of one
 * folder on demand where it cannot &mdash; a collection, whose references are resolved for the
 * caller exactly as opening it resolves them, a gone photograph left out. An album answers what
 * the caller is shown of it, an inbox what the caller is shown of it (a contributor their own
 * contributions), a saved search its matches for the caller, a folder of folders <code>0</code>:
 * opening it shows no photograph of its own.
 * </p>
 *
 * <p>
 * Asked as opening the folder is asked: a caller who may not open the folder is refused, with the
 * same reason, and never told a number.
 * </p>
 */
public class ImageCount extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.ImageCount} instance.
	 */
	public static de.haumacher.imageServer.shared.model.ImageCount create() {
		return new de.haumacher.imageServer.shared.model.ImageCount();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.ImageCount} type in JSON format. */
	public static final String IMAGE_COUNT__TYPE = "ImageCount";

	/** @see #getCount() */
	private static final String COUNT__PROP = "count";

	private int _count = 0;

	/**
	 * Creates a {@link ImageCount} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.ImageCount#create()
	 */
	protected ImageCount() {
		super();
	}

	/**
	 * How many photographs opening the folder shows the caller.
	 */
	public final int getCount() {
		return _count;
	}

	/**
	 * @see #getCount()
	 */
	public de.haumacher.imageServer.shared.model.ImageCount setCount(int value) {
		internalSetCount(value);
		return this;
	}

	/** Internal setter for {@link #getCount()} without chain call utility. */
	protected final void internalSetCount(int value) {
		_count = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.ImageCount readImageCount(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.ImageCount result = new de.haumacher.imageServer.shared.model.ImageCount();
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
		out.name(COUNT__PROP);
		out.value(getCount());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case COUNT__PROP: setCount(in.nextInt()); break;
			default: super.readField(in, field);
		}
	}

}
