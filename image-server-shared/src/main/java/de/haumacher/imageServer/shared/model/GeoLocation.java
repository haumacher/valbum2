package de.haumacher.imageServer.shared.model;

/**
 * Where a photo was taken, see issue #112.
 *
 * <p>
 * Decimal degrees in WGS 84, positive to the north and to the east, exactly as the EXIF GPS tags
 * of the original say it (<code>GPSLatitude</code>/<code>GPSLongitude</code> with their reference
 * letters, resolved into one signed number each by metadata-extractor).
 * </p>
 *
 * <p>
 * "No position" is the absent {@link ImagePart#getLocation() location}. A pair of zeroes is no
 * position either (issue #161): a camera with geotagging switched on and no fix yet writes a GPS
 * IFD of zeroes, so <code>0/0</code> says "not filled in" far more often than it says "the Gulf of
 * Guinea". The analysis never answers it, the loader drops it from an older sidecar, and the app
 * shows nothing for it.
 * </p>
 */
public class GeoLocation extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.GeoLocation} instance.
	 */
	public static de.haumacher.imageServer.shared.model.GeoLocation create() {
		return new de.haumacher.imageServer.shared.model.GeoLocation();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.GeoLocation} type in JSON format. */
	public static final String GEO_LOCATION__TYPE = "GeoLocation";

	/** @see #getLatitude() */
	private static final String LATITUDE__PROP = "latitude";

	/** @see #getLongitude() */
	private static final String LONGITUDE__PROP = "longitude";

	private double _latitude = 0.0d;

	private double _longitude = 0.0d;

	/**
	 * Creates a {@link GeoLocation} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.GeoLocation#create()
	 */
	protected GeoLocation() {
		super();
	}

	/**
	 * The latitude in decimal degrees, positive to the north of the equator.
	 */
	public final double getLatitude() {
		return _latitude;
	}

	/**
	 * @see #getLatitude()
	 */
	public de.haumacher.imageServer.shared.model.GeoLocation setLatitude(double value) {
		internalSetLatitude(value);
		return this;
	}

	/** Internal setter for {@link #getLatitude()} without chain call utility. */
	protected final void internalSetLatitude(double value) {
		_latitude = value;
	}

	/**
	 * The longitude in decimal degrees, positive to the east of Greenwich.
	 */
	public final double getLongitude() {
		return _longitude;
	}

	/**
	 * @see #getLongitude()
	 */
	public de.haumacher.imageServer.shared.model.GeoLocation setLongitude(double value) {
		internalSetLongitude(value);
		return this;
	}

	/** Internal setter for {@link #getLongitude()} without chain call utility. */
	protected final void internalSetLongitude(double value) {
		_longitude = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.GeoLocation readGeoLocation(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.GeoLocation result = new de.haumacher.imageServer.shared.model.GeoLocation();
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
		out.name(LATITUDE__PROP);
		out.value(getLatitude());
		out.name(LONGITUDE__PROP);
		out.value(getLongitude());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case LATITUDE__PROP: setLatitude(in.nextDouble()); break;
			case LONGITUDE__PROP: setLongitude(in.nextDouble()); break;
			default: super.readField(in, field);
		}
	}

}
