package de.haumacher.imageServer.shared.model;

/**
 * JPEG orientation tag.
 *
 * <pre>
 *   1        2       3      4         5            6           7          8
 *
 * 888888  888888      88  88      8888888888  88                  88  8888888888
 * 88          88      88  88      88  88      88  88          88  88      88  88
 * 8888      8888    8888  8888    88          8888888888  8888888888          88
 * 88          88      88  88
 * 88          88  888888  888888
 * </pre>
 *
 * @see "http://sylvana.net/jpegcrop/exif_orientation.html"
 */
public enum Orientation implements de.haumacher.msgbuf.data.ProtocolEnum {

	IDENTITY("IDENTITY"),

	FLIP_H("FLIP_H"),

	ROT_180("ROT_180"),

	FLIP_V("FLIP_V"),

	ROT_L_FLIP_V("ROT_L_FLIP_V"),

	ROT_L("ROT_L"),

	ROT_L_FLIP_H("ROT_L_FLIP_H"),

	ROT_R("ROT_R"),

	;

	private final String _protocolName;

	private Orientation(String protocolName) {
		_protocolName = protocolName;
	}

	/**
	 * The protocol name of a {@link Orientation} constant.
	 *
	 * @see #valueOfProtocol(String)
	 */
	@Override
	public String protocolName() {
		return _protocolName;
	}

	/** Looks up a {@link Orientation} constant by it's protocol name. */
	public static Orientation valueOfProtocol(String protocolName) {
		if (protocolName == null) { return null; }
		switch (protocolName) {
			case "IDENTITY": return IDENTITY;
			case "FLIP_H": return FLIP_H;
			case "ROT_180": return ROT_180;
			case "FLIP_V": return FLIP_V;
			case "ROT_L_FLIP_V": return ROT_L_FLIP_V;
			case "ROT_L": return ROT_L;
			case "ROT_L_FLIP_H": return ROT_L_FLIP_H;
			case "ROT_R": return ROT_R;
		}
		return IDENTITY;
	}

	/** Writes this instance to the given output. */
	public final void writeTo(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		out.value(protocolName());
	}

	/** Reads a new instance from the given reader. */
	public static Orientation readOrientation(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		return valueOfProtocol(in.nextString());
	}

	/** Writes this instance to the given binary output. */
	public final void writeTo(de.haumacher.msgbuf.binary.DataWriter out) throws java.io.IOException {
		switch (this) {
			case IDENTITY: out.value(1); break;
			case FLIP_H: out.value(2); break;
			case ROT_180: out.value(3); break;
			case FLIP_V: out.value(4); break;
			case ROT_L_FLIP_V: out.value(5); break;
			case ROT_L: out.value(6); break;
			case ROT_L_FLIP_H: out.value(7); break;
			case ROT_R: out.value(8); break;
			default: out.value(0);
		}
	}

	/** Reads a new instance from the given binary reader. */
	public static Orientation readOrientation(de.haumacher.msgbuf.binary.DataReader in) throws java.io.IOException {
		switch (in.nextInt()) {
			case 1: return IDENTITY;
			case 2: return FLIP_H;
			case 3: return ROT_180;
			case 4: return FLIP_V;
			case 5: return ROT_L_FLIP_V;
			case 6: return ROT_L;
			case 7: return ROT_L_FLIP_H;
			case 8: return ROT_R;
			default: return IDENTITY;
		}
	}
}
