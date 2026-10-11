package de.haumacher.imageServer.shared.model;

/**
 * Whether a photograph without a description shows the place it was taken in as its caption, see
 * {@link AlbumInfo#placeCaption} and {@link ImagePart#placeCaption}.
 *
 * <p>
 * The caption of a photograph is its {@link ImagePart#comment description} where it has one;
 * where it has none and its place is shown, the names of its {@link ImagePart#places places}
 * stand there instead, with a link to the map. The album says it for all its photographs, a
 * photograph may say otherwise for itself.
 * </p>
 */
public enum PlaceCaption implements de.haumacher.msgbuf.data.ProtocolEnum {

	/**
	 * Nothing said: a photograph follows its album, and an album shows the places.
	 *
	 * <p>
	 * The first constant and therefore what a sidecar written before the field reads as. Not
	 * named <code>DEFAULT</code>: that is a reserved word of Dart, which the app's model is
	 * generated in.
	 * </p>
	 */
	UNSET("UNSET"),

	/**
	 * The place is shown where there is no description.
	 */
	SHOWN("SHOWN"),

	/**
	 * The place is never shown as caption.
	 */
	HIDDEN("HIDDEN"),

	;

	private final String _protocolName;

	private PlaceCaption(String protocolName) {
		_protocolName = protocolName;
	}

	/**
	 * The protocol name of a {@link PlaceCaption} constant.
	 *
	 * @see #valueOfProtocol(String)
	 */
	@Override
	public String protocolName() {
		return _protocolName;
	}

	/** Looks up a {@link PlaceCaption} constant by it's protocol name. */
	public static PlaceCaption valueOfProtocol(String protocolName) {
		if (protocolName == null) { return null; }
		switch (protocolName) {
			case "UNSET": return UNSET;
			case "SHOWN": return SHOWN;
			case "HIDDEN": return HIDDEN;
		}
		return UNSET;
	}

	/** Writes this instance to the given output. */
	public final void writeTo(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		out.value(protocolName());
	}

	/** Reads a new instance from the given reader. */
	public static PlaceCaption readPlaceCaption(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		return valueOfProtocol(in.nextString());
	}

	/** Writes this instance to the given binary output. */
	public final void writeTo(de.haumacher.msgbuf.binary.DataWriter out) throws java.io.IOException {
		switch (this) {
			case UNSET: out.value(1); break;
			case SHOWN: out.value(2); break;
			case HIDDEN: out.value(3); break;
			default: out.value(0);
		}
	}

	/** Reads a new instance from the given binary reader. */
	public static PlaceCaption readPlaceCaption(de.haumacher.msgbuf.binary.DataReader in) throws java.io.IOException {
		switch (in.nextInt()) {
			case 1: return UNSET;
			case 2: return SHOWN;
			case 3: return HIDDEN;
			default: return UNSET;
		}
	}
}
