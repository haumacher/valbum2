package de.haumacher.imageServer.shared.model;

/**
 * One photograph of a {@link NewsEntry}.
 */
public class NewsPhoto extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.NewsPhoto} instance.
	 */
	public static de.haumacher.imageServer.shared.model.NewsPhoto create() {
		return new de.haumacher.imageServer.shared.model.NewsPhoto();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.NewsPhoto} type in JSON format. */
	public static final String NEWS_PHOTO__TYPE = "NewsPhoto";

	/** @see #getName() */
	private static final String NAME__PROP = "name";

	/** @see #isUnread() */
	private static final String UNREAD__PROP = "unread";

	private String _name = "";

	private boolean _unread = false;

	/**
	 * Creates a {@link NewsPhoto} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.NewsPhoto#create()
	 */
	protected NewsPhoto() {
		super();
	}

	/**
	 * Its file name in the album.
	 */
	public final String getName() {
		return _name;
	}

	/**
	 * @see #getName()
	 */
	public de.haumacher.imageServer.shared.model.NewsPhoto setName(String value) {
		internalSetName(value);
		return this;
	}

	/** Internal setter for {@link #getName()} without chain call utility. */
	protected final void internalSetName(String value) {
		_name = value;
	}

	/**
	 * Whether it arrived after what the caller marked as read.
	 */
	public final boolean isUnread() {
		return _unread;
	}

	/**
	 * @see #isUnread()
	 */
	public de.haumacher.imageServer.shared.model.NewsPhoto setUnread(boolean value) {
		internalSetUnread(value);
		return this;
	}

	/** Internal setter for {@link #isUnread()} without chain call utility. */
	protected final void internalSetUnread(boolean value) {
		_unread = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.NewsPhoto readNewsPhoto(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.NewsPhoto result = new de.haumacher.imageServer.shared.model.NewsPhoto();
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
		out.name(NAME__PROP);
		out.value(getName());
		out.name(UNREAD__PROP);
		out.value(isUnread());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case NAME__PROP: setName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case UNREAD__PROP: setUnread(in.nextBoolean()); break;
			default: super.readField(in, field);
		}
	}

}
