package de.haumacher.imageServer.shared.model;

/**
 * Marks entries of the bell as read, see {@link NewsInfo}: <code>POST &lt;data&gt;/?action=news-read</code>,
 * answered with the {@link NewsInfo} as it is now.
 */
public class NewsRead extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.NewsRead} instance.
	 */
	public static de.haumacher.imageServer.shared.model.NewsRead create() {
		return new de.haumacher.imageServer.shared.model.NewsRead();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.NewsRead} type in JSON format. */
	public static final String NEWS_READ__TYPE = "NewsRead";

	/** @see #getKeys() */
	private static final String KEYS__PROP = "keys";

	/** @see #isAll() */
	private static final String ALL__PROP = "all";

	private final java.util.List<de.haumacher.imageServer.shared.model.NewsKey> _keys = new java.util.ArrayList<>();

	private boolean _all = false;

	/**
	 * Creates a {@link NewsRead} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.NewsRead#create()
	 */
	protected NewsRead() {
		super();
	}

	/**
	 * The {@link NewsEntry#getId() ids} of the entries visited.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.NewsKey> getKeys() {
		return _keys;
	}

	/**
	 * @see #getKeys()
	 */
	public de.haumacher.imageServer.shared.model.NewsRead setKeys(java.util.List<? extends de.haumacher.imageServer.shared.model.NewsKey> value) {
		internalSetKeys(value);
		return this;
	}

	/** Internal setter for {@link #getKeys()} without chain call utility. */
	protected final void internalSetKeys(java.util.List<? extends de.haumacher.imageServer.shared.model.NewsKey> value) {
		if (value == null) throw new IllegalArgumentException("Property 'keys' cannot be null.");
		_keys.clear();
		_keys.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getKeys()} list.
	 */
	public de.haumacher.imageServer.shared.model.NewsRead addKey(de.haumacher.imageServer.shared.model.NewsKey value) {
		internalAddKey(value);
		return this;
	}

	/** Implementation of {@link #addKey(de.haumacher.imageServer.shared.model.NewsKey)} without chain call utility. */
	protected final void internalAddKey(de.haumacher.imageServer.shared.model.NewsKey value) {
		_keys.add(value);
	}

	/**
	 * Removes a value from the {@link #getKeys()} list.
	 */
	public final void removeKey(de.haumacher.imageServer.shared.model.NewsKey value) {
		_keys.remove(value);
	}

	/**
	 * Whether every entry is marked as read ("Mark all as read").
	 */
	public final boolean isAll() {
		return _all;
	}

	/**
	 * @see #isAll()
	 */
	public de.haumacher.imageServer.shared.model.NewsRead setAll(boolean value) {
		internalSetAll(value);
		return this;
	}

	/** Internal setter for {@link #isAll()} without chain call utility. */
	protected final void internalSetAll(boolean value) {
		_all = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.NewsRead readNewsRead(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.NewsRead result = new de.haumacher.imageServer.shared.model.NewsRead();
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
		out.name(KEYS__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.NewsKey x : getKeys()) {
			x.writeTo(out);
		}
		out.endArray();
		out.name(ALL__PROP);
		out.value(isAll());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case KEYS__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addKey(de.haumacher.imageServer.shared.model.NewsKey.readNewsKey(in));
				}
				in.endArray();
			}
			break;
			case ALL__PROP: setAll(in.nextBoolean()); break;
			default: super.readField(in, field);
		}
	}

}
