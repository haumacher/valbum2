package de.haumacher.imageServer.shared.model;

/**
 * A step of the background work, see {@link CatchUpStatus#step}.
 */
public enum CatchUpStep implements de.haumacher.msgbuf.data.ProtocolEnum {

	/**
	 * Nothing runs; the first constant, and what a client that does not know a value reads.
	 */
	IDLE("IDLE"),

	/**
	 * The content hashes of a folder's photos, see issue #235.
	 */
	HASH("HASH"),

	/**
	 * The previews of an album's photos, and the display renditions of HEIC and raw pictures.
	 */
	PREVIEWS("PREVIEWS"),

	/**
	 * The picture of an album's tile, and of the tiles of the folders above it.
	 */
	COVER("COVER"),

	/**
	 * Looking for faces, where the space has them on.
	 */
	FACES("FACES"),

	/**
	 * Where the photos were taken, see issue #234.
	 */
	PLACES("PLACES"),

	/**
	 * The teaser or the playback rendition of a video.
	 */
	VIDEOS("VIDEOS"),

	/**
	 * A step this description does not name.
	 */
	OTHER("OTHER"),

	;

	private final String _protocolName;

	private CatchUpStep(String protocolName) {
		_protocolName = protocolName;
	}

	/**
	 * The protocol name of a {@link CatchUpStep} constant.
	 *
	 * @see #valueOfProtocol(String)
	 */
	@Override
	public String protocolName() {
		return _protocolName;
	}

	/** Looks up a {@link CatchUpStep} constant by it's protocol name. */
	public static CatchUpStep valueOfProtocol(String protocolName) {
		if (protocolName == null) { return null; }
		switch (protocolName) {
			case "IDLE": return IDLE;
			case "HASH": return HASH;
			case "PREVIEWS": return PREVIEWS;
			case "COVER": return COVER;
			case "FACES": return FACES;
			case "PLACES": return PLACES;
			case "VIDEOS": return VIDEOS;
			case "OTHER": return OTHER;
		}
		return IDLE;
	}

	/** Writes this instance to the given output. */
	public final void writeTo(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		out.value(protocolName());
	}

	/** Reads a new instance from the given reader. */
	public static CatchUpStep readCatchUpStep(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		return valueOfProtocol(in.nextString());
	}

	/** Writes this instance to the given binary output. */
	public final void writeTo(de.haumacher.msgbuf.binary.DataWriter out) throws java.io.IOException {
		switch (this) {
			case IDLE: out.value(1); break;
			case HASH: out.value(2); break;
			case PREVIEWS: out.value(3); break;
			case COVER: out.value(4); break;
			case FACES: out.value(5); break;
			case PLACES: out.value(6); break;
			case VIDEOS: out.value(7); break;
			case OTHER: out.value(8); break;
			default: out.value(0);
		}
	}

	/** Reads a new instance from the given binary reader. */
	public static CatchUpStep readCatchUpStep(de.haumacher.msgbuf.binary.DataReader in) throws java.io.IOException {
		switch (in.nextInt()) {
			case 1: return IDLE;
			case 2: return HASH;
			case 3: return PREVIEWS;
			case 4: return COVER;
			case 5: return FACES;
			case 6: return PLACES;
			case 7: return VIDEOS;
			case 8: return OTHER;
			default: return IDLE;
		}
	}
}
