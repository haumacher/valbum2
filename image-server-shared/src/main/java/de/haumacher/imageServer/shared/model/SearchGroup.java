package de.haumacher.imageServer.shared.model;

/**
 * A key the photographs found by a {@link SearchQuery} are grouped by, see issue #244.
 */
public enum SearchGroup implements de.haumacher.msgbuf.data.ProtocolEnum {

	/**
	 * The album the photograph lies in, by its path.
	 */
	ALBUM("ALBUM"),

	/**
	 * The year of the recording time, in the zone of the device showing it.
	 */
	YEAR("YEAR"),

	/**
	 * The month of the recording time, in the zone of the device showing it.
	 */
	MONTH("MONTH"),

	/**
	 * The country ({@link PlaceKind#COUNTRY}).
	 */
	COUNTRY("COUNTRY"),

	/**
	 * The region, the first administrative level ({@link PlaceKind#ADM1}).
	 */
	REGION("REGION"),

	/**
	 * The town ({@link PlaceKind#PLACE}).
	 */
	TOWN("TOWN"),

	/**
	 * The camera ({@link ImagePart#camera}).
	 */
	CAMERA("CAMERA"),

	;

	private final String _protocolName;

	private SearchGroup(String protocolName) {
		_protocolName = protocolName;
	}

	/**
	 * The protocol name of a {@link SearchGroup} constant.
	 *
	 * @see #valueOfProtocol(String)
	 */
	@Override
	public String protocolName() {
		return _protocolName;
	}

	/** Looks up a {@link SearchGroup} constant by it's protocol name. */
	public static SearchGroup valueOfProtocol(String protocolName) {
		if (protocolName == null) { return null; }
		switch (protocolName) {
			case "ALBUM": return ALBUM;
			case "YEAR": return YEAR;
			case "MONTH": return MONTH;
			case "COUNTRY": return COUNTRY;
			case "REGION": return REGION;
			case "TOWN": return TOWN;
			case "CAMERA": return CAMERA;
		}
		return ALBUM;
	}

	/** Writes this instance to the given output. */
	public final void writeTo(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		out.value(protocolName());
	}

	/** Reads a new instance from the given reader. */
	public static SearchGroup readSearchGroup(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		return valueOfProtocol(in.nextString());
	}

	/** Writes this instance to the given binary output. */
	public final void writeTo(de.haumacher.msgbuf.binary.DataWriter out) throws java.io.IOException {
		switch (this) {
			case ALBUM: out.value(1); break;
			case YEAR: out.value(2); break;
			case MONTH: out.value(3); break;
			case COUNTRY: out.value(4); break;
			case REGION: out.value(5); break;
			case TOWN: out.value(6); break;
			case CAMERA: out.value(7); break;
			default: out.value(0);
		}
	}

	/** Reads a new instance from the given binary reader. */
	public static SearchGroup readSearchGroup(de.haumacher.msgbuf.binary.DataReader in) throws java.io.IOException {
		switch (in.nextInt()) {
			case 1: return ALBUM;
			case 2: return YEAR;
			case 3: return MONTH;
			case 4: return COUNTRY;
			case 5: return REGION;
			case 6: return TOWN;
			case 7: return CAMERA;
			default: return ALBUM;
		}
	}
}
