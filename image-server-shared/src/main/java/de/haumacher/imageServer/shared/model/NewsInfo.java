package de.haumacher.imageServer.shared.model;

/**
 * What arrived from others, the bell of issue #268: <code>&lt;data&gt;/?type=news</code>, answered
 * to a member of the space only.
 *
 * <p>
 * An entry is the photographs one person made arrive in one album in one burst of time &mdash; an
 * upload (by a member, a contact through a link, a device into a shared album) or a move out of the
 * inbox into an album, the moment the photographs appear for everybody else. The caller's own
 * uploads and moves are never an entry for them, and an entry holds only what the caller may see
 * in its album (rights, clearance, never the trash): a photograph hidden from them is neither
 * shown nor counted, and an entry of hidden photographs alone is not answered at all.
 * </p>
 *
 * <p>
 * Derived on every request from the index of the space, where the events are read from the upload
 * attribution of the albums' <code>.hashes.json</code> and the read marks are kept per member:
 * the same on every device of the member, and gone with a rebuild of the index, after which what
 * arrived before the rebuild counts as read.
 * </p>
 */
public class NewsInfo extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.NewsInfo} instance.
	 */
	public static de.haumacher.imageServer.shared.model.NewsInfo create() {
		return new de.haumacher.imageServer.shared.model.NewsInfo();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.NewsInfo} type in JSON format. */
	public static final String NEWS_INFO__TYPE = "NewsInfo";

	/** @see #getCount() */
	private static final String COUNT__PROP = "count";

	/** @see #getEntries() */
	private static final String ENTRIES__PROP = "entries";

	private int _count = 0;

	private final java.util.List<de.haumacher.imageServer.shared.model.NewsEntry> _entries = new java.util.ArrayList<>();

	/**
	 * Creates a {@link NewsInfo} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.NewsInfo#create()
	 */
	protected NewsInfo() {
		super();
	}

	/**
	 * How many {@link #getEntries()} are unread; all that <code>?type=news-count</code> answers.
	 */
	public final int getCount() {
		return _count;
	}

	/**
	 * @see #getCount()
	 */
	public de.haumacher.imageServer.shared.model.NewsInfo setCount(int value) {
		internalSetCount(value);
		return this;
	}

	/** Internal setter for {@link #getCount()} without chain call utility. */
	protected final void internalSetCount(int value) {
		_count = value;
	}

	/**
	 * The entries, the unread ones first, each part the most recent first; empty for
	 * <code>?type=news-count</code>. What arrived more than 90 days ago is left out, and so is
	 * everything beyond the first 200 entries.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.NewsEntry> getEntries() {
		return _entries;
	}

	/**
	 * @see #getEntries()
	 */
	public de.haumacher.imageServer.shared.model.NewsInfo setEntries(java.util.List<? extends de.haumacher.imageServer.shared.model.NewsEntry> value) {
		internalSetEntries(value);
		return this;
	}

	/** Internal setter for {@link #getEntries()} without chain call utility. */
	protected final void internalSetEntries(java.util.List<? extends de.haumacher.imageServer.shared.model.NewsEntry> value) {
		if (value == null) throw new IllegalArgumentException("Property 'entries' cannot be null.");
		_entries.clear();
		_entries.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getEntries()} list.
	 */
	public de.haumacher.imageServer.shared.model.NewsInfo addEntrie(de.haumacher.imageServer.shared.model.NewsEntry value) {
		internalAddEntrie(value);
		return this;
	}

	/** Implementation of {@link #addEntrie(de.haumacher.imageServer.shared.model.NewsEntry)} without chain call utility. */
	protected final void internalAddEntrie(de.haumacher.imageServer.shared.model.NewsEntry value) {
		_entries.add(value);
	}

	/**
	 * Removes a value from the {@link #getEntries()} list.
	 */
	public final void removeEntrie(de.haumacher.imageServer.shared.model.NewsEntry value) {
		_entries.remove(value);
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.NewsInfo readNewsInfo(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.NewsInfo result = new de.haumacher.imageServer.shared.model.NewsInfo();
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
		out.name(ENTRIES__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.NewsEntry x : getEntries()) {
			x.writeTo(out);
		}
		out.endArray();
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case COUNT__PROP: setCount(in.nextInt()); break;
			case ENTRIES__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addEntrie(de.haumacher.imageServer.shared.model.NewsEntry.readNewsEntry(in));
				}
				in.endArray();
			}
			break;
			default: super.readField(in, field);
		}
	}

}
