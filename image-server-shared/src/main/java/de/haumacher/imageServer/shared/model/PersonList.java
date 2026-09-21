package de.haumacher.imageServer.shared.model;

/**
 * The people of a space, answered by <code>?type=people</code>.
 */
public class PersonList extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.PersonList} instance.
	 */
	public static de.haumacher.imageServer.shared.model.PersonList create() {
		return new de.haumacher.imageServer.shared.model.PersonList();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.PersonList} type in JSON format. */
	public static final String PERSON_LIST__TYPE = "PersonList";

	/** @see #getPeople() */
	private static final String PEOPLE__PROP = "people";

	private final java.util.List<de.haumacher.imageServer.shared.model.Person> _people = new java.util.ArrayList<>();

	/**
	 * Creates a {@link PersonList} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.PersonList#create()
	 */
	protected PersonList() {
		super();
	}

	/**
	 * Every person of the register, in the order they were created; merged ones are not here.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.Person> getPeople() {
		return _people;
	}

	/**
	 * @see #getPeople()
	 */
	public de.haumacher.imageServer.shared.model.PersonList setPeople(java.util.List<? extends de.haumacher.imageServer.shared.model.Person> value) {
		internalSetPeople(value);
		return this;
	}

	/** Internal setter for {@link #getPeople()} without chain call utility. */
	protected final void internalSetPeople(java.util.List<? extends de.haumacher.imageServer.shared.model.Person> value) {
		if (value == null) throw new IllegalArgumentException("Property 'people' cannot be null.");
		_people.clear();
		_people.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getPeople()} list.
	 */
	public de.haumacher.imageServer.shared.model.PersonList addPeople(de.haumacher.imageServer.shared.model.Person value) {
		internalAddPeople(value);
		return this;
	}

	/** Implementation of {@link #addPeople(de.haumacher.imageServer.shared.model.Person)} without chain call utility. */
	protected final void internalAddPeople(de.haumacher.imageServer.shared.model.Person value) {
		_people.add(value);
	}

	/**
	 * Removes a value from the {@link #getPeople()} list.
	 */
	public final void removePeople(de.haumacher.imageServer.shared.model.Person value) {
		_people.remove(value);
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.PersonList readPersonList(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.PersonList result = new de.haumacher.imageServer.shared.model.PersonList();
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
		out.name(PEOPLE__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.Person x : getPeople()) {
			x.writeTo(out);
		}
		out.endArray();
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case PEOPLE__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addPeople(de.haumacher.imageServer.shared.model.Person.readPerson(in));
				}
				in.endArray();
			}
			break;
			default: super.readField(in, field);
		}
	}

}
