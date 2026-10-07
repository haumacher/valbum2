package de.haumacher.imageServer.shared.model;

/**
 * What the search view offers to choose from in a folder, see issue #227 and
 * <code>&lt;folder&gt;/?type=search-options</code>.
 *
 * <p>
 * Derived from the photographs below the folder that the caller may see, never stored.
 * </p>
 */
public class SearchOptions extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.SearchOptions} instance.
	 */
	public static de.haumacher.imageServer.shared.model.SearchOptions create() {
		return new de.haumacher.imageServer.shared.model.SearchOptions();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.SearchOptions} type in JSON format. */
	public static final String SEARCH_OPTIONS__TYPE = "SearchOptions";

	/** @see #getPersons() */
	private static final String PERSONS__PROP = "persons";

	/** @see #getPlaces() */
	private static final String PLACES__PROP = "places";

	/** @see #getLabels() */
	private static final String LABELS__PROP = "labels";

	/** @see #getCameras() */
	private static final String CAMERAS__PROP = "cameras";

	private final java.util.List<de.haumacher.imageServer.shared.model.Person> _persons = new java.util.ArrayList<>();

	private final java.util.List<de.haumacher.imageServer.shared.model.PlaceTag> _places = new java.util.ArrayList<>();

	private final java.util.List<de.haumacher.imageServer.shared.model.LabelName> _labels = new java.util.ArrayList<>();

	private final java.util.List<de.haumacher.imageServer.shared.model.CameraName> _cameras = new java.util.ArrayList<>();

	/**
	 * Creates a {@link SearchOptions} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.SearchOptions#create()
	 */
	protected SearchOptions() {
		super();
	}

	/**
	 * The persons confirmed in at least one of the photographs, by name.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.Person> getPersons() {
		return _persons;
	}

	/**
	 * @see #getPersons()
	 */
	public de.haumacher.imageServer.shared.model.SearchOptions setPersons(java.util.List<? extends de.haumacher.imageServer.shared.model.Person> value) {
		internalSetPersons(value);
		return this;
	}

	/** Internal setter for {@link #getPersons()} without chain call utility. */
	protected final void internalSetPersons(java.util.List<? extends de.haumacher.imageServer.shared.model.Person> value) {
		if (value == null) throw new IllegalArgumentException("Property 'persons' cannot be null.");
		_persons.clear();
		_persons.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getPersons()} list.
	 */
	public de.haumacher.imageServer.shared.model.SearchOptions addPerson(de.haumacher.imageServer.shared.model.Person value) {
		internalAddPerson(value);
		return this;
	}

	/** Implementation of {@link #addPerson(de.haumacher.imageServer.shared.model.Person)} without chain call utility. */
	protected final void internalAddPerson(de.haumacher.imageServer.shared.model.Person value) {
		_persons.add(value);
	}

	/**
	 * Removes a value from the {@link #getPersons()} list.
	 */
	public final void removePerson(de.haumacher.imageServer.shared.model.Person value) {
		_persons.remove(value);
	}

	/**
	 * The places the photographs were taken in, every level, each once, in the caller's language.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.PlaceTag> getPlaces() {
		return _places;
	}

	/**
	 * @see #getPlaces()
	 */
	public de.haumacher.imageServer.shared.model.SearchOptions setPlaces(java.util.List<? extends de.haumacher.imageServer.shared.model.PlaceTag> value) {
		internalSetPlaces(value);
		return this;
	}

	/** Internal setter for {@link #getPlaces()} without chain call utility. */
	protected final void internalSetPlaces(java.util.List<? extends de.haumacher.imageServer.shared.model.PlaceTag> value) {
		if (value == null) throw new IllegalArgumentException("Property 'places' cannot be null.");
		_places.clear();
		_places.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getPlaces()} list.
	 */
	public de.haumacher.imageServer.shared.model.SearchOptions addPlace(de.haumacher.imageServer.shared.model.PlaceTag value) {
		internalAddPlace(value);
		return this;
	}

	/** Implementation of {@link #addPlace(de.haumacher.imageServer.shared.model.PlaceTag)} without chain call utility. */
	protected final void internalAddPlace(de.haumacher.imageServer.shared.model.PlaceTag value) {
		_places.add(value);
	}

	/**
	 * Removes a value from the {@link #getPlaces()} list.
	 */
	public final void removePlace(de.haumacher.imageServer.shared.model.PlaceTag value) {
		_places.remove(value);
	}

	/**
	 * The labels the photographs carry, each once, sorted.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.LabelName> getLabels() {
		return _labels;
	}

	/**
	 * @see #getLabels()
	 */
	public de.haumacher.imageServer.shared.model.SearchOptions setLabels(java.util.List<? extends de.haumacher.imageServer.shared.model.LabelName> value) {
		internalSetLabels(value);
		return this;
	}

	/** Internal setter for {@link #getLabels()} without chain call utility. */
	protected final void internalSetLabels(java.util.List<? extends de.haumacher.imageServer.shared.model.LabelName> value) {
		if (value == null) throw new IllegalArgumentException("Property 'labels' cannot be null.");
		_labels.clear();
		_labels.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getLabels()} list.
	 */
	public de.haumacher.imageServer.shared.model.SearchOptions addLabel(de.haumacher.imageServer.shared.model.LabelName value) {
		internalAddLabel(value);
		return this;
	}

	/** Implementation of {@link #addLabel(de.haumacher.imageServer.shared.model.LabelName)} without chain call utility. */
	protected final void internalAddLabel(de.haumacher.imageServer.shared.model.LabelName value) {
		_labels.add(value);
	}

	/**
	 * Removes a value from the {@link #getLabels()} list.
	 */
	public final void removeLabel(de.haumacher.imageServer.shared.model.LabelName value) {
		_labels.remove(value);
	}

	/**
	 * The cameras the photographs were taken with, each once, sorted.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.CameraName> getCameras() {
		return _cameras;
	}

	/**
	 * @see #getCameras()
	 */
	public de.haumacher.imageServer.shared.model.SearchOptions setCameras(java.util.List<? extends de.haumacher.imageServer.shared.model.CameraName> value) {
		internalSetCameras(value);
		return this;
	}

	/** Internal setter for {@link #getCameras()} without chain call utility. */
	protected final void internalSetCameras(java.util.List<? extends de.haumacher.imageServer.shared.model.CameraName> value) {
		if (value == null) throw new IllegalArgumentException("Property 'cameras' cannot be null.");
		_cameras.clear();
		_cameras.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getCameras()} list.
	 */
	public de.haumacher.imageServer.shared.model.SearchOptions addCamera(de.haumacher.imageServer.shared.model.CameraName value) {
		internalAddCamera(value);
		return this;
	}

	/** Implementation of {@link #addCamera(de.haumacher.imageServer.shared.model.CameraName)} without chain call utility. */
	protected final void internalAddCamera(de.haumacher.imageServer.shared.model.CameraName value) {
		_cameras.add(value);
	}

	/**
	 * Removes a value from the {@link #getCameras()} list.
	 */
	public final void removeCamera(de.haumacher.imageServer.shared.model.CameraName value) {
		_cameras.remove(value);
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.SearchOptions readSearchOptions(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.SearchOptions result = new de.haumacher.imageServer.shared.model.SearchOptions();
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
		out.name(PERSONS__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.Person x : getPersons()) {
			x.writeTo(out);
		}
		out.endArray();
		out.name(PLACES__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.PlaceTag x : getPlaces()) {
			x.writeTo(out);
		}
		out.endArray();
		out.name(LABELS__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.LabelName x : getLabels()) {
			x.writeTo(out);
		}
		out.endArray();
		out.name(CAMERAS__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.CameraName x : getCameras()) {
			x.writeTo(out);
		}
		out.endArray();
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case PERSONS__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addPerson(de.haumacher.imageServer.shared.model.Person.readPerson(in));
				}
				in.endArray();
			}
			break;
			case PLACES__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addPlace(de.haumacher.imageServer.shared.model.PlaceTag.readPlaceTag(in));
				}
				in.endArray();
			}
			break;
			case LABELS__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addLabel(de.haumacher.imageServer.shared.model.LabelName.readLabelName(in));
				}
				in.endArray();
			}
			break;
			case CAMERAS__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addCamera(de.haumacher.imageServer.shared.model.CameraName.readCameraName(in));
				}
				in.endArray();
			}
			break;
			default: super.readField(in, field);
		}
	}

}
