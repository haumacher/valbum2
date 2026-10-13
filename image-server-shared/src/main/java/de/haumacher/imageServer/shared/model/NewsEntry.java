package de.haumacher.imageServer.shared.model;

/**
 * One entry of {@link NewsInfo}: what one person made arrive in one album at one time.
 */
public class NewsEntry extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.NewsEntry} instance.
	 */
	public static de.haumacher.imageServer.shared.model.NewsEntry create() {
		return new de.haumacher.imageServer.shared.model.NewsEntry();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.NewsEntry} type in JSON format. */
	public static final String NEWS_ENTRY__TYPE = "NewsEntry";

	/** @see #getId() */
	private static final String ID__PROP = "id";

	/** @see #getAlbum() */
	private static final String ALBUM__PROP = "album";

	/** @see #getTitle() */
	private static final String TITLE__PROP = "title";

	/** @see #getWho() */
	private static final String WHO__PROP = "who";

	/** @see #getFirst() */
	private static final String FIRST__PROP = "first";

	/** @see #getLast() */
	private static final String LAST__PROP = "last";

	/** @see #isUnread() */
	private static final String UNREAD__PROP = "unread";

	/** @see #getPhotos() */
	private static final String PHOTOS__PROP = "photos";

	private String _id = "";

	private String _album = "";

	private String _title = "";

	private String _who = "";

	private long _first = 0L;

	private long _last = 0L;

	private boolean _unread = false;

	private final java.util.List<de.haumacher.imageServer.shared.model.NewsPhoto> _photos = new java.util.ArrayList<>();

	/**
	 * Creates a {@link NewsEntry} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.NewsEntry#create()
	 */
	protected NewsEntry() {
		super();
	}

	/**
	 * What names this entry in {@link NewsRead#getKeys()}: the same for every member and on every
	 * request, while the entry's first photograph stays where it is. Not called <code>key</code>:
	 * the Dart reader of the generator shadows a field of that name.
	 */
	public final String getId() {
		return _id;
	}

	/**
	 * @see #getId()
	 */
	public de.haumacher.imageServer.shared.model.NewsEntry setId(String value) {
		internalSetId(value);
		return this;
	}

	/** Internal setter for {@link #getId()} without chain call utility. */
	protected final void internalSetId(String value) {
		_id = value;
	}

	/**
	 * The album, its path below the root of the space with <code>/</code> between the folders.
	 */
	public final String getAlbum() {
		return _album;
	}

	/**
	 * @see #getAlbum()
	 */
	public de.haumacher.imageServer.shared.model.NewsEntry setAlbum(String value) {
		internalSetAlbum(value);
		return this;
	}

	/** Internal setter for {@link #getAlbum()} without chain call utility. */
	protected final void internalSetAlbum(String value) {
		_album = value;
	}

	/**
	 * The title of the album, its folder name where it has none.
	 */
	public final String getTitle() {
		return _title;
	}

	/**
	 * @see #getTitle()
	 */
	public de.haumacher.imageServer.shared.model.NewsEntry setTitle(String value) {
		internalSetTitle(value);
		return this;
	}

	/** Internal setter for {@link #getTitle()} without chain call utility. */
	protected final void internalSetTitle(String value) {
		_title = value;
	}

	/**
	 * Who made the photographs arrive, as named when they did: a member's name, a contact's, a link's label.
	 */
	public final String getWho() {
		return _who;
	}

	/**
	 * @see #getWho()
	 */
	public de.haumacher.imageServer.shared.model.NewsEntry setWho(String value) {
		internalSetWho(value);
		return this;
	}

	/** Internal setter for {@link #getWho()} without chain call utility. */
	protected final void internalSetWho(String value) {
		_who = value;
	}

	/**
	 * When the first photograph of the entry arrived, milliseconds since the epoch.
	 */
	public final long getFirst() {
		return _first;
	}

	/**
	 * @see #getFirst()
	 */
	public de.haumacher.imageServer.shared.model.NewsEntry setFirst(long value) {
		internalSetFirst(value);
		return this;
	}

	/** Internal setter for {@link #getFirst()} without chain call utility. */
	protected final void internalSetFirst(long value) {
		_first = value;
	}

	/**
	 * When the last photograph of the entry arrived, milliseconds since the epoch.
	 */
	public final long getLast() {
		return _last;
	}

	/**
	 * @see #getLast()
	 */
	public de.haumacher.imageServer.shared.model.NewsEntry setLast(long value) {
		internalSetLast(value);
		return this;
	}

	/** Internal setter for {@link #getLast()} without chain call utility. */
	protected final void internalSetLast(long value) {
		_last = value;
	}

	/**
	 * Whether any photograph of the entry is unread.
	 */
	public final boolean isUnread() {
		return _unread;
	}

	/**
	 * @see #isUnread()
	 */
	public de.haumacher.imageServer.shared.model.NewsEntry setUnread(boolean value) {
		internalSetUnread(value);
		return this;
	}

	/** Internal setter for {@link #isUnread()} without chain call utility. */
	protected final void internalSetUnread(boolean value) {
		_unread = value;
	}

	/**
	 * The photographs the caller may see, in the order of their album.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.NewsPhoto> getPhotos() {
		return _photos;
	}

	/**
	 * @see #getPhotos()
	 */
	public de.haumacher.imageServer.shared.model.NewsEntry setPhotos(java.util.List<? extends de.haumacher.imageServer.shared.model.NewsPhoto> value) {
		internalSetPhotos(value);
		return this;
	}

	/** Internal setter for {@link #getPhotos()} without chain call utility. */
	protected final void internalSetPhotos(java.util.List<? extends de.haumacher.imageServer.shared.model.NewsPhoto> value) {
		if (value == null) throw new IllegalArgumentException("Property 'photos' cannot be null.");
		_photos.clear();
		_photos.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getPhotos()} list.
	 */
	public de.haumacher.imageServer.shared.model.NewsEntry addPhoto(de.haumacher.imageServer.shared.model.NewsPhoto value) {
		internalAddPhoto(value);
		return this;
	}

	/** Implementation of {@link #addPhoto(de.haumacher.imageServer.shared.model.NewsPhoto)} without chain call utility. */
	protected final void internalAddPhoto(de.haumacher.imageServer.shared.model.NewsPhoto value) {
		_photos.add(value);
	}

	/**
	 * Removes a value from the {@link #getPhotos()} list.
	 */
	public final void removePhoto(de.haumacher.imageServer.shared.model.NewsPhoto value) {
		_photos.remove(value);
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.NewsEntry readNewsEntry(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.NewsEntry result = new de.haumacher.imageServer.shared.model.NewsEntry();
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
		out.name(ALBUM__PROP);
		out.value(getAlbum());
		out.name(TITLE__PROP);
		out.value(getTitle());
		out.name(WHO__PROP);
		out.value(getWho());
		out.name(FIRST__PROP);
		out.value(getFirst());
		out.name(LAST__PROP);
		out.value(getLast());
		out.name(UNREAD__PROP);
		out.value(isUnread());
		out.name(PHOTOS__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.NewsPhoto x : getPhotos()) {
			x.writeTo(out);
		}
		out.endArray();
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case ID__PROP: setId(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case ALBUM__PROP: setAlbum(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case TITLE__PROP: setTitle(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case WHO__PROP: setWho(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case FIRST__PROP: setFirst(in.nextLong()); break;
			case LAST__PROP: setLast(in.nextLong()); break;
			case UNREAD__PROP: setUnread(in.nextBoolean()); break;
			case PHOTOS__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addPhoto(de.haumacher.imageServer.shared.model.NewsPhoto.readNewsPhoto(in));
				}
				in.endArray();
			}
			break;
			default: super.readField(in, field);
		}
	}

}
