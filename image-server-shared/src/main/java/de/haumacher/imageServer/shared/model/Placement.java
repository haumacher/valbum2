package de.haumacher.imageServer.shared.model;

/**
 * How a {@link ListingInfo} files the albums that land in it, see {@link ListingInfo#placement}.
 *
 * <p>
 * A folder named after a year or a month is an ordinary folder: it is created on demand and carries
 * no rule of its own.
 * </p>
 */
public enum Placement implements de.haumacher.msgbuf.data.ProtocolEnum {

	/**
	 * Nothing is filed: an album stays where it was created or moved to.
	 */
	NONE("NONE"),

	/**
	 * An album with a date lands in a folder named after its year (<code>2020</code>).
	 */
	BY_YEAR("BY_YEAR"),

	/**
	 * An album with a date lands in a folder named after its month, inside its year folder
	 * (<code>2020/2020-05</code>).
	 *
	 * <p>
	 * The month folder names its year too, so that it reads on its own and sorts anywhere. An album
	 * whose date is only known to the year (its folder is named <code>2020 Trip</code>) lands in the
	 * year folder itself: the server does not invent a month it was not told.
	 * </p>
	 */
	BY_YEAR_MONTH("BY_YEAR_MONTH"),

	;

	private final String _protocolName;

	private Placement(String protocolName) {
		_protocolName = protocolName;
	}

	/**
	 * The protocol name of a {@link Placement} constant.
	 *
	 * @see #valueOfProtocol(String)
	 */
	@Override
	public String protocolName() {
		return _protocolName;
	}

	/** Looks up a {@link Placement} constant by it's protocol name. */
	public static Placement valueOfProtocol(String protocolName) {
		if (protocolName == null) { return null; }
		switch (protocolName) {
			case "NONE": return NONE;
			case "BY_YEAR": return BY_YEAR;
			case "BY_YEAR_MONTH": return BY_YEAR_MONTH;
		}
		return NONE;
	}

	/** Writes this instance to the given output. */
	public final void writeTo(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		out.value(protocolName());
	}

	/** Reads a new instance from the given reader. */
	public static Placement readPlacement(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		return valueOfProtocol(in.nextString());
	}

	/** Writes this instance to the given binary output. */
	public final void writeTo(de.haumacher.msgbuf.binary.DataWriter out) throws java.io.IOException {
		switch (this) {
			case NONE: out.value(1); break;
			case BY_YEAR: out.value(2); break;
			case BY_YEAR_MONTH: out.value(3); break;
			default: out.value(0);
		}
	}

	/** Reads a new instance from the given binary reader. */
	public static Placement readPlacement(de.haumacher.msgbuf.binary.DataReader in) throws java.io.IOException {
		switch (in.nextInt()) {
			case 1: return NONE;
			case 2: return BY_YEAR;
			case 3: return BY_YEAR_MONTH;
			default: return NONE;
		}
	}
}
