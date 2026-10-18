package de.haumacher.imageServer.shared.model;

/**
 * A photograph that a delete moved into an album referencing it instead of into the trash, see
 * issue #307 and {@link MoveOutcome#getKept()}.
 */
public class KeptPhoto extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.KeptPhoto} instance.
	 */
	public static de.haumacher.imageServer.shared.model.KeptPhoto create() {
		return new de.haumacher.imageServer.shared.model.KeptPhoto();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.KeptPhoto} type in JSON format. */
	public static final String KEPT_PHOTO__TYPE = "KeptPhoto";

	/** @see #getPhoto() */
	private static final String PHOTO__PROP = "photo";

	/** @see #getAlbum() */
	private static final String ALBUM__PROP = "album";

	/** @see #getTitle() */
	private static final String TITLE__PROP = "title";

	/** @see #getName() */
	private static final String NAME__PROP = "name";

	private String _photo = "";

	private String _album = "";

	private String _title = "";

	private String _name = "";

	/**
	 * Creates a {@link KeptPhoto} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.KeptPhoto#create()
	 */
	protected KeptPhoto() {
		super();
	}

	/**
	 * The photograph as it was named in the folder deleted from: its name, or its path below the
	 * deleted album (<code>2020 Trip/b.jpg</code>).
	 */
	public final String getPhoto() {
		return _photo;
	}

	/**
	 * @see #getPhoto()
	 */
	public de.haumacher.imageServer.shared.model.KeptPhoto setPhoto(String value) {
		internalSetPhoto(value);
		return this;
	}

	/** Internal setter for {@link #getPhoto()} without chain call utility. */
	protected final void internalSetPhoto(String value) {
		_photo = value;
	}

	/**
	 * The album it lies in now, in the coordinates of the request, like {@link CreateResult#getPath()}.
	 */
	public final String getAlbum() {
		return _album;
	}

	/**
	 * @see #getAlbum()
	 */
	public de.haumacher.imageServer.shared.model.KeptPhoto setAlbum(String value) {
		internalSetAlbum(value);
		return this;
	}

	/** Internal setter for {@link #getAlbum()} without chain call utility. */
	protected final void internalSetAlbum(String value) {
		_album = value;
	}

	/**
	 * The title of that album, its folder name where it has none.
	 */
	public final String getTitle() {
		return _title;
	}

	/**
	 * @see #getTitle()
	 */
	public de.haumacher.imageServer.shared.model.KeptPhoto setTitle(String value) {
		internalSetTitle(value);
		return this;
	}

	/** Internal setter for {@link #getTitle()} without chain call utility. */
	protected final void internalSetTitle(String value) {
		_title = value;
	}

	/**
	 * The name the photograph has in that album now: the name of the reference it replaced.
	 */
	public final String getName() {
		return _name;
	}

	/**
	 * @see #getName()
	 */
	public de.haumacher.imageServer.shared.model.KeptPhoto setName(String value) {
		internalSetName(value);
		return this;
	}

	/** Internal setter for {@link #getName()} without chain call utility. */
	protected final void internalSetName(String value) {
		_name = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.KeptPhoto readKeptPhoto(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.KeptPhoto result = new de.haumacher.imageServer.shared.model.KeptPhoto();
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
		out.name(PHOTO__PROP);
		out.value(getPhoto());
		out.name(ALBUM__PROP);
		out.value(getAlbum());
		out.name(TITLE__PROP);
		out.value(getTitle());
		out.name(NAME__PROP);
		out.value(getName());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case PHOTO__PROP: setPhoto(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case ALBUM__PROP: setAlbum(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case TITLE__PROP: setTitle(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case NAME__PROP: setName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
