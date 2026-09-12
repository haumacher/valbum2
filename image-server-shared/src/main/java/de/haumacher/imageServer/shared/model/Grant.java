package de.haumacher.imageServer.shared.model;

/**
 * A sharing grant: who may do what on which subtree, see issue #49.
 *
 * <p>
 * The one sharing mechanism of this server. A grant is identified by its {@link #getOwner()}, its
 * {@link #getPath()} and its {@link #getSubject()}; granting again replaces the {@link #getRights()}, revoking
 * removes it. Grants are inherited downwards: a grant on a folder covers everything below it.
 * </p>
 *
 * <p>
 * Sent to <code>&lt;folder&gt;/?action=grant</code> and <code>&lt;folder&gt;/?action=revoke</code>,
 * where the {@link #getOwner()} and the {@link #getPath()} are taken from the URL and whatever the body says
 * about them is ignored.
 * </p>
 */
public class Grant extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.Grant} instance.
	 */
	public static de.haumacher.imageServer.shared.model.Grant create() {
		return new de.haumacher.imageServer.shared.model.Grant();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.Grant} type in JSON format. */
	public static final String GRANT__TYPE = "Grant";

	/** @see #getOwner() */
	private static final String OWNER__PROP = "owner";

	/** @see #getPath() */
	private static final String PATH__PROP = "path";

	/** @see #getSubject() */
	private static final String SUBJECT__PROP = "subject";

	/** @see #getRights() */
	private static final String RIGHTS__PROP = "rights";

	/** @see #getCreated() */
	private static final String CREATED__PROP = "created";

	private String _owner = "";

	private String _path = "";

	private String _subject = "";

	private final java.util.List<de.haumacher.imageServer.shared.model.RightName> _rights = new java.util.ArrayList<>();

	private String _created = "";

	/**
	 * Creates a {@link Grant} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.Grant#create()
	 */
	protected Grant() {
		super();
	}

	/**
	 * The name of the user in whose space the granted subtree lies.
	 */
	public final String getOwner() {
		return _owner;
	}

	/**
	 * @see #getOwner()
	 */
	public de.haumacher.imageServer.shared.model.Grant setOwner(String value) {
		internalSetOwner(value);
		return this;
	}

	/** Internal setter for {@link #getOwner()} without chain call utility. */
	protected final void internalSetOwner(String value) {
		_owner = value;
	}

	/**
	 * The granted folder, as a path relative to the owner's space; the empty string is the whole
	 * space.
	 */
	public final String getPath() {
		return _path;
	}

	/**
	 * @see #getPath()
	 */
	public de.haumacher.imageServer.shared.model.Grant setPath(String value) {
		internalSetPath(value);
		return this;
	}

	/** Internal setter for {@link #getPath()} without chain call utility. */
	protected final void internalSetPath(String value) {
		_path = value;
	}

	/**
	 * Who is granted: <code>user:&lt;name&gt;</code>, <code>group:&lt;name&gt;</code>,
	 * <code>anonymous</code> (everybody, signed in or not), or <code>token:&lt;id&gt;</code> (a
	 * share link, issue #51).
	 */
	public final String getSubject() {
		return _subject;
	}

	/**
	 * @see #getSubject()
	 */
	public de.haumacher.imageServer.shared.model.Grant setSubject(String value) {
		internalSetSubject(value);
		return this;
	}

	/** Internal setter for {@link #getSubject()} without chain call utility. */
	protected final void internalSetSubject(String value) {
		_subject = value;
	}

	/**
	 * What is granted: <code>view</code>, <code>download</code>, <code>contribute</code>,
	 * <code>edit</code>.
	 *
	 * <p>
	 * A list of messages, not a list of plain strings, see {@link FolderResource#getRights()}.
	 * </p>
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.RightName> getRights() {
		return _rights;
	}

	/**
	 * @see #getRights()
	 */
	public de.haumacher.imageServer.shared.model.Grant setRights(java.util.List<? extends de.haumacher.imageServer.shared.model.RightName> value) {
		internalSetRights(value);
		return this;
	}

	/** Internal setter for {@link #getRights()} without chain call utility. */
	protected final void internalSetRights(java.util.List<? extends de.haumacher.imageServer.shared.model.RightName> value) {
		if (value == null) throw new IllegalArgumentException("Property 'rights' cannot be null.");
		_rights.clear();
		_rights.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getRights()} list.
	 */
	public de.haumacher.imageServer.shared.model.Grant addRight(de.haumacher.imageServer.shared.model.RightName value) {
		internalAddRight(value);
		return this;
	}

	/** Implementation of {@link #addRight(de.haumacher.imageServer.shared.model.RightName)} without chain call utility. */
	protected final void internalAddRight(de.haumacher.imageServer.shared.model.RightName value) {
		_rights.add(value);
	}

	/**
	 * Removes a value from the {@link #getRights()} list.
	 */
	public final void removeRight(de.haumacher.imageServer.shared.model.RightName value) {
		_rights.remove(value);
	}

	/**
	 * When the grant was made, an ISO-8601 instant; answered by the server, ignored in a request.
	 */
	public final String getCreated() {
		return _created;
	}

	/**
	 * @see #getCreated()
	 */
	public de.haumacher.imageServer.shared.model.Grant setCreated(String value) {
		internalSetCreated(value);
		return this;
	}

	/** Internal setter for {@link #getCreated()} without chain call utility. */
	protected final void internalSetCreated(String value) {
		_created = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.Grant readGrant(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.Grant result = new de.haumacher.imageServer.shared.model.Grant();
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
		out.name(OWNER__PROP);
		out.value(getOwner());
		out.name(PATH__PROP);
		out.value(getPath());
		out.name(SUBJECT__PROP);
		out.value(getSubject());
		out.name(RIGHTS__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.RightName x : getRights()) {
			x.writeTo(out);
		}
		out.endArray();
		out.name(CREATED__PROP);
		out.value(getCreated());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case OWNER__PROP: setOwner(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case PATH__PROP: setPath(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case SUBJECT__PROP: setSubject(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case RIGHTS__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addRight(de.haumacher.imageServer.shared.model.RightName.readRightName(in));
				}
				in.endArray();
			}
			break;
			case CREATED__PROP: setCreated(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
