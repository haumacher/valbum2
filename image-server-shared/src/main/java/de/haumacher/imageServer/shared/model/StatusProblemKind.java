package de.haumacher.imageServer.shared.model;

/**
 * What a {@link StatusProblem} is about.
 */
public enum StatusProblemKind implements de.haumacher.msgbuf.data.ProtocolEnum {

	/**
	 * A step of the background failed on files or folders; the first constant, also for a kind a client does not know.
	 */
	FAILURE("FAILURE"),

	/**
	 * Folders the server's user cannot write: nothing generated can be stored beside their photos.
	 */
	UNWRITABLE("UNWRITABLE"),

	/**
	 * The embedder of the content search does not answer, see issue #257.
	 */
	EMBEDDER("EMBEDDER"),

	/**
	 * The space asked for faces, and this machine cannot detect them.
	 */
	FACES("FACES"),

	/**
	 * This machine cannot make the renditions of videos.
	 */
	VIDEOS("VIDEOS"),

	/**
	 * A file of place names could not be downloaded, see issue #234.
	 */
	PLACES("PLACES"),

	;

	private final String _protocolName;

	private StatusProblemKind(String protocolName) {
		_protocolName = protocolName;
	}

	/**
	 * The protocol name of a {@link StatusProblemKind} constant.
	 *
	 * @see #valueOfProtocol(String)
	 */
	@Override
	public String protocolName() {
		return _protocolName;
	}

	/** Looks up a {@link StatusProblemKind} constant by it's protocol name. */
	public static StatusProblemKind valueOfProtocol(String protocolName) {
		if (protocolName == null) { return null; }
		switch (protocolName) {
			case "FAILURE": return FAILURE;
			case "UNWRITABLE": return UNWRITABLE;
			case "EMBEDDER": return EMBEDDER;
			case "FACES": return FACES;
			case "VIDEOS": return VIDEOS;
			case "PLACES": return PLACES;
		}
		return FAILURE;
	}

	/** Writes this instance to the given output. */
	public final void writeTo(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		out.value(protocolName());
	}

	/** Reads a new instance from the given reader. */
	public static StatusProblemKind readStatusProblemKind(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		return valueOfProtocol(in.nextString());
	}

	/** Writes this instance to the given binary output. */
	public final void writeTo(de.haumacher.msgbuf.binary.DataWriter out) throws java.io.IOException {
		switch (this) {
			case FAILURE: out.value(1); break;
			case UNWRITABLE: out.value(2); break;
			case EMBEDDER: out.value(3); break;
			case FACES: out.value(4); break;
			case VIDEOS: out.value(5); break;
			case PLACES: out.value(6); break;
			default: out.value(0);
		}
	}

	/** Reads a new instance from the given binary reader. */
	public static StatusProblemKind readStatusProblemKind(de.haumacher.msgbuf.binary.DataReader in) throws java.io.IOException {
		switch (in.nextInt()) {
			case 1: return FAILURE;
			case 2: return UNWRITABLE;
			case 3: return EMBEDDER;
			case 4: return FACES;
			case 5: return VIDEOS;
			case 6: return PLACES;
			default: return FAILURE;
		}
	}
}
