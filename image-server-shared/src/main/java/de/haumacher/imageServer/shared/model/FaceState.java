package de.haumacher.imageServer.shared.model;

/**
 * What somebody decided about one face, see {@link FaceTag} and issue #125.
 *
 * <p>
 * A rejection is a decision too: without it, a suggestion that was turned down would come back at
 * the next pass.
 * </p>
 */
public enum FaceState implements de.haumacher.msgbuf.data.ProtocolEnum {

	/**
	 * Nothing was decided about this face.
	 *
	 * <p>
	 * The first constant and therefore what a {@link FaceInfo} of an untouched detection answers,
	 * and what a client that does not know a value reads. It is never stored: a {@link FaceTag}
	 * <em>is</em> a decision, so an assignment carrying this state is refused.
	 * </p>
	 */
	UNDECIDED("UNDECIDED"),

	/**
	 * This face is {@link FaceTag#person}: somebody said so.
	 */
	CONFIRMED("CONFIRMED"),

	/**
	 * This face is <em>not</em> {@link FaceTag#person}: somebody said so.
	 *
	 * <p>
	 * The person stands in the tag, because that is what was rejected. A face may carry only one
	 * decision at a time, so a rejection is replaced by a confirmation when somebody names the
	 * face after all.
	 * </p>
	 */
	REJECTED("REJECTED"),

	/**
	 * There is no face here at all: what the detector found is a false positive.
	 */
	NOT_A_FACE("NOT_A_FACE"),

	;

	private final String _protocolName;

	private FaceState(String protocolName) {
		_protocolName = protocolName;
	}

	/**
	 * The protocol name of a {@link FaceState} constant.
	 *
	 * @see #valueOfProtocol(String)
	 */
	@Override
	public String protocolName() {
		return _protocolName;
	}

	/** Looks up a {@link FaceState} constant by it's protocol name. */
	public static FaceState valueOfProtocol(String protocolName) {
		if (protocolName == null) { return null; }
		switch (protocolName) {
			case "UNDECIDED": return UNDECIDED;
			case "CONFIRMED": return CONFIRMED;
			case "REJECTED": return REJECTED;
			case "NOT_A_FACE": return NOT_A_FACE;
		}
		return UNDECIDED;
	}

	/** Writes this instance to the given output. */
	public final void writeTo(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		out.value(protocolName());
	}

	/** Reads a new instance from the given reader. */
	public static FaceState readFaceState(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		return valueOfProtocol(in.nextString());
	}

	/** Writes this instance to the given binary output. */
	public final void writeTo(de.haumacher.msgbuf.binary.DataWriter out) throws java.io.IOException {
		switch (this) {
			case UNDECIDED: out.value(1); break;
			case CONFIRMED: out.value(2); break;
			case REJECTED: out.value(3); break;
			case NOT_A_FACE: out.value(4); break;
			default: out.value(0);
		}
	}

	/** Reads a new instance from the given binary reader. */
	public static FaceState readFaceState(de.haumacher.msgbuf.binary.DataReader in) throws java.io.IOException {
		switch (in.nextInt()) {
			case 1: return UNDECIDED;
			case 2: return CONFIRMED;
			case 3: return REJECTED;
			case 4: return NOT_A_FACE;
			default: return UNDECIDED;
		}
	}
}
