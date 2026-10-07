package de.haumacher.imageServer.shared.model;

/**
 * One GeoNames entry a photograph's position lies in or close to, see issue #234.
 *
 * <p>
 * An entry and not a string: two places of one name are two ids, so that a filter can later ask
 * for everything in one of them.
 * </p>
 */
public class PlaceTag extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.PlaceTag} instance.
	 */
	public static de.haumacher.imageServer.shared.model.PlaceTag create() {
		return new de.haumacher.imageServer.shared.model.PlaceTag();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.PlaceTag} type in JSON format. */
	public static final String PLACE_TAG__TYPE = "PlaceTag";

	/** @see #getGeonameId() */
	private static final String GEONAME_ID__PROP = "geonameId";

	/** @see #getName() */
	private static final String NAME__PROP = "name";

	/** @see #getKind() */
	private static final String KIND__PROP = "kind";

	/** @see #getFeatureCode() */
	private static final String FEATURE_CODE__PROP = "featureCode";

	/** @see #getCountry() */
	private static final String COUNTRY__PROP = "country";

	private int _geonameId = 0;

	private String _name = "";

	private de.haumacher.imageServer.shared.model.PlaceKind _kind = de.haumacher.imageServer.shared.model.PlaceKind.COUNTRY;

	private String _featureCode = "";

	private String _country = "";

	/**
	 * Creates a {@link PlaceTag} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.PlaceTag#create()
	 */
	protected PlaceTag() {
		super();
	}

	/**
	 * The GeoNames id; for {@link PlaceKind#COUNTRY} the one <code>countryInfo.txt</code> names.
	 */
	public final int getGeonameId() {
		return _geonameId;
	}

	/**
	 * @see #getGeonameId()
	 */
	public de.haumacher.imageServer.shared.model.PlaceTag setGeonameId(int value) {
		internalSetGeonameId(value);
		return this;
	}

	/** Internal setter for {@link #getGeonameId()} without chain call utility. */
	protected final void internalSetGeonameId(int value) {
		_geonameId = value;
	}

	/**
	 * The GeoNames name (its main <code>name</code> column, mostly in Latin script).
	 */
	public final String getName() {
		return _name;
	}

	/**
	 * @see #getName()
	 */
	public de.haumacher.imageServer.shared.model.PlaceTag setName(String value) {
		internalSetName(value);
		return this;
	}

	/** Internal setter for {@link #getName()} without chain call utility. */
	protected final void internalSetName(String value) {
		_name = value;
	}

	/**
	 * The level in the hierarchy.
	 */
	public final de.haumacher.imageServer.shared.model.PlaceKind getKind() {
		return _kind;
	}

	/**
	 * @see #getKind()
	 */
	public de.haumacher.imageServer.shared.model.PlaceTag setKind(de.haumacher.imageServer.shared.model.PlaceKind value) {
		internalSetKind(value);
		return this;
	}

	/** Internal setter for {@link #getKind()} without chain call utility. */
	protected final void internalSetKind(de.haumacher.imageServer.shared.model.PlaceKind value) {
		if (value == null) throw new IllegalArgumentException("Property 'kind' cannot be null.");
		_kind = value;
	}

	/**
	 * The GeoNames feature code, e.g. <code>PPLA2</code>, <code>PRK</code>, <code>PCL</code>.
	 */
	public final String getFeatureCode() {
		return _featureCode;
	}

	/**
	 * @see #getFeatureCode()
	 */
	public de.haumacher.imageServer.shared.model.PlaceTag setFeatureCode(String value) {
		internalSetFeatureCode(value);
		return this;
	}

	/** Internal setter for {@link #getFeatureCode()} without chain call utility. */
	protected final void internalSetFeatureCode(String value) {
		_featureCode = value;
	}

	/**
	 * The ISO code of the country the entry belongs to, e.g. <code>DE</code>.
	 */
	public final String getCountry() {
		return _country;
	}

	/**
	 * @see #getCountry()
	 */
	public de.haumacher.imageServer.shared.model.PlaceTag setCountry(String value) {
		internalSetCountry(value);
		return this;
	}

	/** Internal setter for {@link #getCountry()} without chain call utility. */
	protected final void internalSetCountry(String value) {
		_country = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.PlaceTag readPlaceTag(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.PlaceTag result = new de.haumacher.imageServer.shared.model.PlaceTag();
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
		out.name(GEONAME_ID__PROP);
		out.value(getGeonameId());
		out.name(NAME__PROP);
		out.value(getName());
		out.name(KIND__PROP);
		getKind().writeTo(out);
		out.name(FEATURE_CODE__PROP);
		out.value(getFeatureCode());
		out.name(COUNTRY__PROP);
		out.value(getCountry());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case GEONAME_ID__PROP: setGeonameId(in.nextInt()); break;
			case NAME__PROP: setName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case KIND__PROP: setKind(de.haumacher.imageServer.shared.model.PlaceKind.readPlaceKind(in)); break;
			case FEATURE_CODE__PROP: setFeatureCode(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case COUNTRY__PROP: setCountry(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
