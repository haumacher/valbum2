package de.haumacher.imageServer.shared.model;

/**
 * One key of {@link NewsRead#getKeys()}.
 */
public class NewsKey extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.NewsKey} instance.
	 */
	public static de.haumacher.imageServer.shared.model.NewsKey create() {
		return new de.haumacher.imageServer.shared.model.NewsKey();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.NewsKey} type in JSON format. */
	public static final String NEWS_KEY__TYPE = "NewsKey";

	/** @see #getId() */
	private static final String ID__PROP = "id";

	private String _id = "";

	/**
	 * Creates a {@link NewsKey} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.NewsKey#create()
	 */
	protected NewsKey() {
		super();
	}

	/**
	 * The {@link NewsEntry#getId()}.
	 */
	public final String getId() {
		return _id;
	}

	/**
	 * @see #getId()
	 */
	public de.haumacher.imageServer.shared.model.NewsKey setId(String value) {
		internalSetId(value);
		return this;
	}

	/** Internal setter for {@link #getId()} without chain call utility. */
	protected final void internalSetId(String value) {
		_id = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.NewsKey readNewsKey(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.NewsKey result = new de.haumacher.imageServer.shared.model.NewsKey();
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
		out.name(ID__PROP);
		out.value(getId());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case ID__PROP: setId(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
