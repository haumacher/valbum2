package de.haumacher.imageServer.shared.model;

/**
 * Who sees a space without signing in, see {@link SpaceSettings#anonymous}.
 */
public enum AnonymousAccess implements de.haumacher.msgbuf.data.ProtocolEnum {

	/**
	 * Nobody sees anything without signing in; the first constant, and what an unknown value reads.
	 */
	NONE("NONE"),

	/**
	 * A visitor sees the public photos, and changes nothing.
	 */
	PUBLIC("PUBLIC"),

	;

	private final String _protocolName;

	private AnonymousAccess(String protocolName) {
		_protocolName = protocolName;
	}

	/**
	 * The protocol name of a {@link AnonymousAccess} constant.
	 *
	 * @see #valueOfProtocol(String)
	 */
	@Override
	public String protocolName() {
		return _protocolName;
	}

	/** Looks up a {@link AnonymousAccess} constant by it's protocol name. */
	public static AnonymousAccess valueOfProtocol(String protocolName) {
		if (protocolName == null) { return null; }
		switch (protocolName) {
			case "NONE": return NONE;
			case "PUBLIC": return PUBLIC;
		}
		return NONE;
	}

	/** Writes this instance to the given output. */
	public final void writeTo(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		out.value(protocolName());
	}

	/** Reads a new instance from the given reader. */
	public static AnonymousAccess readAnonymousAccess(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		return valueOfProtocol(in.nextString());
	}

	/** Writes this instance to the given binary output. */
	public final void writeTo(de.haumacher.msgbuf.binary.DataWriter out) throws java.io.IOException {
		switch (this) {
			case NONE: out.value(1); break;
			case PUBLIC: out.value(2); break;
			default: out.value(0);
		}
	}

	/** Reads a new instance from the given binary reader. */
	public static AnonymousAccess readAnonymousAccess(de.haumacher.msgbuf.binary.DataReader in) throws java.io.IOException {
		switch (in.nextInt()) {
			case 1: return NONE;
			case 2: return PUBLIC;
			default: return NONE;
		}
	}
}
