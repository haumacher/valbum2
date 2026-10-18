package de.haumacher.imageServer.shared.model;

/**
 * Why a search found nothing that is no refusal, see {@link AlbumInfo#searchProblem} and issue #257.
 */
public enum SearchProblem implements de.haumacher.msgbuf.data.ProtocolEnum {

	/**
	 * Nothing is wrong.
	 */
	NONE("NONE"),

	/**
	 * The photograph of a <code>SearchSimilar</code> is gone, or the caller may not see it: the two are
	 * answered alike, so that nobody learns of a photograph they may not see.
	 */
	PHOTO_NOT_FOUND("PHOTO_NOT_FOUND"),

	/**
	 * The photograph of a <code>SearchSimilar</code> has no vector yet: the background has not described
	 * it (or content search was switched on only lately).
	 */
	PHOTO_NOT_DESCRIBED("PHOTO_NOT_DESCRIBED"),

	;

	private final String _protocolName;

	private SearchProblem(String protocolName) {
		_protocolName = protocolName;
	}

	/**
	 * The protocol name of a {@link SearchProblem} constant.
	 *
	 * @see #valueOfProtocol(String)
	 */
	@Override
	public String protocolName() {
		return _protocolName;
	}

	/** Looks up a {@link SearchProblem} constant by it's protocol name. */
	public static SearchProblem valueOfProtocol(String protocolName) {
		if (protocolName == null) { return null; }
		switch (protocolName) {
			case "NONE": return NONE;
			case "PHOTO_NOT_FOUND": return PHOTO_NOT_FOUND;
			case "PHOTO_NOT_DESCRIBED": return PHOTO_NOT_DESCRIBED;
		}
		return NONE;
	}

	/** Writes this instance to the given output. */
	public final void writeTo(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		out.value(protocolName());
	}

	/** Reads a new instance from the given reader. */
	public static SearchProblem readSearchProblem(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		return valueOfProtocol(in.nextString());
	}

	/** Writes this instance to the given binary output. */
	public final void writeTo(de.haumacher.msgbuf.binary.DataWriter out) throws java.io.IOException {
		switch (this) {
			case NONE: out.value(1); break;
			case PHOTO_NOT_FOUND: out.value(2); break;
			case PHOTO_NOT_DESCRIBED: out.value(3); break;
			default: out.value(0);
		}
	}

	/** Reads a new instance from the given binary reader. */
	public static SearchProblem readSearchProblem(de.haumacher.msgbuf.binary.DataReader in) throws java.io.IOException {
		switch (in.nextInt()) {
			case 1: return NONE;
			case 2: return PHOTO_NOT_FOUND;
			case 3: return PHOTO_NOT_DESCRIBED;
			default: return NONE;
		}
	}
}
