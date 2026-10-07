package de.haumacher.imageServer.shared.model;

/**
 * The photograph was taken in the given place: one of its {@link ImagePart#getPlaces() place tags},
 * at any level, is the given GeoNames entry (issue #234). A state matches every photograph of its
 * towns.
 */
public class SearchPlace extends SearchCriterion {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.SearchPlace} instance.
	 */
	public static de.haumacher.imageServer.shared.model.SearchPlace create() {
		return new de.haumacher.imageServer.shared.model.SearchPlace();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.SearchPlace} type in JSON format. */
	public static final String SEARCH_PLACE__TYPE = "SearchPlace";

	/** @see #getGeonameId() */
	private static final String GEONAME_ID__PROP = "geonameId";

	private int _geonameId = 0;

	/**
	 * Creates a {@link SearchPlace} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.SearchPlace#create()
	 */
	protected SearchPlace() {
		super();
	}

	@Override
	public TypeKind kind() {
		return TypeKind.SEARCH_PLACE;
	}

	/**
	 * The {@link PlaceTag#getGeonameId()}.
	 */
	public final int getGeonameId() {
		return _geonameId;
	}

	/**
	 * @see #getGeonameId()
	 */
	public de.haumacher.imageServer.shared.model.SearchPlace setGeonameId(int value) {
		internalSetGeonameId(value);
		return this;
	}

	/** Internal setter for {@link #getGeonameId()} without chain call utility. */
	protected final void internalSetGeonameId(int value) {
		_geonameId = value;
	}

	@Override
	public String jsonType() {
		return SEARCH_PLACE__TYPE;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.SearchPlace readSearchPlace(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.SearchPlace result = new de.haumacher.imageServer.shared.model.SearchPlace();
		result.readContent(in);
		return result;
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name(GEONAME_ID__PROP);
		out.value(getGeonameId());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case GEONAME_ID__PROP: setGeonameId(in.nextInt()); break;
			default: super.readField(in, field);
		}
	}

	@Override
	public <R,A,E extends Throwable> R visit(de.haumacher.imageServer.shared.model.SearchCriterion.Visitor<R,A,E> v, A arg) throws E {
		return v.visit(this, arg);
	}

}
