package de.haumacher.imageServer.shared.model;

/**
 * Somebody the photographs of this space are of, see issue #125.
 *
 * <p>
 * The register lives per space in <code>.valbum/people.json</code> and is the only place a person
 * is named: a {@link FaceTag} carries an {@link #getId()} and never a name, so renaming a person is one
 * write and merging two is one write, whatever either of them is tagged in.
 * </p>
 */
public class Person extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.Person} instance.
	 */
	public static de.haumacher.imageServer.shared.model.Person create() {
		return new de.haumacher.imageServer.shared.model.Person();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.Person} type in JSON format. */
	public static final String PERSON__TYPE = "Person";

	/** @see #getId() */
	private static final String ID__PROP = "id";

	/** @see #getName() */
	private static final String NAME__PROP = "name";

	/** @see #getNickname() */
	private static final String NICKNAME__PROP = "nickname";

	/** @see #getCover() */
	private static final String COVER__PROP = "cover";

	/** @see #getUser() */
	private static final String USER__PROP = "user";

	/** @see #getAliases() */
	private static final String ALIASES__PROP = "aliases";

	private String _id = "";

	private String _name = "";

	private String _nickname = "";

	private de.haumacher.imageServer.shared.model.PersonCover _cover = null;

	private String _user = "";

	private final java.util.List<de.haumacher.imageServer.shared.model.PersonAlias> _aliases = new java.util.ArrayList<>();

	/**
	 * Creates a {@link Person} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.Person#create()
	 */
	protected Person() {
		super();
	}

	/**
	 * The identifier of this person, unique within the space and never reused.
	 *
	 * <p>
	 * Sixteen random bytes, Base64url without padding &mdash; opaque, and deliberately not derived
	 * from the name, which is editable. A person that was merged into another one keeps their id as
	 * an alias of the surviving one, so an id that was ever handed out goes on resolving, see
	 * {@link #getAliases()}.
	 * </p>
	 */
	public final String getId() {
		return _id;
	}

	/**
	 * @see #getId()
	 */
	public de.haumacher.imageServer.shared.model.Person setId(String value) {
		internalSetId(value);
		return this;
	}

	/** Internal setter for {@link #getId()} without chain call utility. */
	protected final void internalSetId(String value) {
		_id = value;
	}

	/**
	 * The canonical name of this person; editable, and unique within the space ignoring case.
	 *
	 * <p>
	 * The full name, the one a person is <em>identified</em> by &mdash; it is what
	 * {@link UserEntry#getPersonName()} answers and what a chooser shows beside the
	 * {@link #getNickname()}. What is written under a face in an album is {@link #getNickname()} where there
	 * is one and this otherwise, see issue #146.
	 * </p>
	 */
	public final String getName() {
		return _name;
	}

	/**
	 * @see #getName()
	 */
	public de.haumacher.imageServer.shared.model.Person setName(String value) {
		internalSetName(value);
		return this;
	}

	/** Internal setter for {@link #getName()} without chain call utility. */
	protected final void internalSetName(String value) {
		_name = value;
	}

	/**
	 * What to call this person on a photograph, empty where they are called by their
	 * {@link #getName()} (issue #146).
	 *
	 * <p>
	 * Typed with the {@link #getName()} in one field &mdash; <code>Berta M&uuml;ller (Tante
	 * Berta)</code> &mdash; and split by the server, which is the one place the convention lives
	 * (<code>PeopleStore.parseName</code>); <code>?action=create-person</code> and
	 * <code>?action=rename-person</code> therefore go on carrying one string.
	 * </p>
	 *
	 * <p>
	 * Unlike the {@link #getName()} a nickname need <b>not</b> be unique &mdash; two grandmothers are
	 * both &quot;Oma&quot; &mdash; so a client that shows two such people side by side names them
	 * by both.
	 * </p>
	 */
	public final String getNickname() {
		return _nickname;
	}

	/**
	 * @see #getNickname()
	 */
	public de.haumacher.imageServer.shared.model.Person setNickname(String value) {
		internalSetNickname(value);
		return this;
	}

	/** Internal setter for {@link #getNickname()} without chain call utility. */
	protected final void internalSetNickname(String value) {
		_nickname = value;
	}

	/**
	 * The face to show this person by, <code>null</code> while nobody chose one (issue #126).
	 */
	public final de.haumacher.imageServer.shared.model.PersonCover getCover() {
		return _cover;
	}

	/**
	 * @see #getCover()
	 */
	public de.haumacher.imageServer.shared.model.Person setCover(de.haumacher.imageServer.shared.model.PersonCover value) {
		internalSetCover(value);
		return this;
	}

	/** Internal setter for {@link #getCover()} without chain call utility. */
	protected final void internalSetCover(de.haumacher.imageServer.shared.model.PersonCover value) {
		_cover = value;
	}

	/**
	 * Checks, whether {@link #getCover()} has a value.
	 */
	public final boolean hasCover() {
		return _cover != null;
	}

	/**
	 * The member of the space this person is, empty when they are nobody in particular.
	 *
	 * <p>
	 * The {@link UserEntry#getName()} of a user. Written by issue #128 and carried here unread: this
	 * build stores it and answers it and draws no conclusion from it.
	 * </p>
	 */
	public final String getUser() {
		return _user;
	}

	/**
	 * @see #getUser()
	 */
	public de.haumacher.imageServer.shared.model.Person setUser(String value) {
		internalSetUser(value);
		return this;
	}

	/** Internal setter for {@link #getUser()} without chain call utility. */
	protected final void internalSetUser(String value) {
		_user = value;
	}

	/**
	 * The ids that were merged into this person, see <code>?action=merge-persons</code>.
	 *
	 * <p>
	 * Answered so that a client can recognise a tag it read before a merge. The server resolves
	 * them itself on every read, so nothing has to.
	 * </p>
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.PersonAlias> getAliases() {
		return _aliases;
	}

	/**
	 * @see #getAliases()
	 */
	public de.haumacher.imageServer.shared.model.Person setAliases(java.util.List<? extends de.haumacher.imageServer.shared.model.PersonAlias> value) {
		internalSetAliases(value);
		return this;
	}

	/** Internal setter for {@link #getAliases()} without chain call utility. */
	protected final void internalSetAliases(java.util.List<? extends de.haumacher.imageServer.shared.model.PersonAlias> value) {
		if (value == null) throw new IllegalArgumentException("Property 'aliases' cannot be null.");
		_aliases.clear();
		_aliases.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getAliases()} list.
	 */
	public de.haumacher.imageServer.shared.model.Person addAliase(de.haumacher.imageServer.shared.model.PersonAlias value) {
		internalAddAliase(value);
		return this;
	}

	/** Implementation of {@link #addAliase(de.haumacher.imageServer.shared.model.PersonAlias)} without chain call utility. */
	protected final void internalAddAliase(de.haumacher.imageServer.shared.model.PersonAlias value) {
		_aliases.add(value);
	}

	/**
	 * Removes a value from the {@link #getAliases()} list.
	 */
	public final void removeAliase(de.haumacher.imageServer.shared.model.PersonAlias value) {
		_aliases.remove(value);
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.Person readPerson(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.Person result = new de.haumacher.imageServer.shared.model.Person();
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
		out.name(NAME__PROP);
		out.value(getName());
		out.name(NICKNAME__PROP);
		out.value(getNickname());
		if (hasCover()) {
			out.name(COVER__PROP);
			getCover().writeTo(out);
		}
		out.name(USER__PROP);
		out.value(getUser());
		out.name(ALIASES__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.PersonAlias x : getAliases()) {
			x.writeTo(out);
		}
		out.endArray();
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case ID__PROP: setId(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case NAME__PROP: setName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case NICKNAME__PROP: setNickname(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case COVER__PROP: setCover(de.haumacher.imageServer.shared.model.PersonCover.readPersonCover(in)); break;
			case USER__PROP: setUser(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case ALIASES__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addAliase(de.haumacher.imageServer.shared.model.PersonAlias.readPersonAlias(in));
				}
				in.endArray();
			}
			break;
			default: super.readField(in, field);
		}
	}

}
