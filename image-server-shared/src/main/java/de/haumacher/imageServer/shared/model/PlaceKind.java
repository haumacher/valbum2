package de.haumacher.imageServer.shared.model;

/**
 * The level of a {@link PlaceTag}, from the largest to the smallest.
 */
public enum PlaceKind implements de.haumacher.msgbuf.data.ProtocolEnum {

	/**
	 * The country.
	 */
	COUNTRY("COUNTRY"),

	/**
	 * The first administrative division: a German state, a US state.
	 */
	ADM_1("ADM1"),

	/**
	 * The second: a German Regierungsbezirk, a US county.
	 */
	ADM_2("ADM2"),

	/**
	 * The third: a German Kreis.
	 */
	ADM_3("ADM3"),

	/**
	 * The fourth: a German Gemeinde.
	 */
	ADM_4("ADM4"),

	/**
	 * The populated place: a city, town or village.
	 */
	PLACE("PLACE"),

	/**
	 * The part of town.
	 */
	DISTRICT("DISTRICT"),

	/**
	 * A named feature close by: a park, lake, mountain, castle, church.
	 */
	FEATURE("FEATURE"),

	;

	private final String _protocolName;

	private PlaceKind(String protocolName) {
		_protocolName = protocolName;
	}

	/**
	 * The protocol name of a {@link PlaceKind} constant.
	 *
	 * @see #valueOfProtocol(String)
	 */
	@Override
	public String protocolName() {
		return _protocolName;
	}

	/** Looks up a {@link PlaceKind} constant by it's protocol name. */
	public static PlaceKind valueOfProtocol(String protocolName) {
		if (protocolName == null) { return null; }
		switch (protocolName) {
			case "COUNTRY": return COUNTRY;
			case "ADM1": return ADM_1;
			case "ADM2": return ADM_2;
			case "ADM3": return ADM_3;
			case "ADM4": return ADM_4;
			case "PLACE": return PLACE;
			case "DISTRICT": return DISTRICT;
			case "FEATURE": return FEATURE;
		}
		return COUNTRY;
	}

	/** Writes this instance to the given output. */
	public final void writeTo(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		out.value(protocolName());
	}

	/** Reads a new instance from the given reader. */
	public static PlaceKind readPlaceKind(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		return valueOfProtocol(in.nextString());
	}

	/** Writes this instance to the given binary output. */
	public final void writeTo(de.haumacher.msgbuf.binary.DataWriter out) throws java.io.IOException {
		switch (this) {
			case COUNTRY: out.value(1); break;
			case ADM_1: out.value(2); break;
			case ADM_2: out.value(3); break;
			case ADM_3: out.value(4); break;
			case ADM_4: out.value(5); break;
			case PLACE: out.value(6); break;
			case DISTRICT: out.value(7); break;
			case FEATURE: out.value(8); break;
			default: out.value(0);
		}
	}

	/** Reads a new instance from the given binary reader. */
	public static PlaceKind readPlaceKind(de.haumacher.msgbuf.binary.DataReader in) throws java.io.IOException {
		switch (in.nextInt()) {
			case 1: return COUNTRY;
			case 2: return ADM_1;
			case 3: return ADM_2;
			case 4: return ADM_3;
			case 5: return ADM_4;
			case 6: return PLACE;
			case 7: return DISTRICT;
			case 8: return FEATURE;
			default: return COUNTRY;
		}
	}
}
