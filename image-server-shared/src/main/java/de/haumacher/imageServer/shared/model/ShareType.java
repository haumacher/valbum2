package de.haumacher.imageServer.shared.model;

/**
 * The kind of a share link, see {@link ShareLink#type} and issue #198.
 */
public enum ShareType implements de.haumacher.msgbuf.data.ProtocolEnum {

	/**
	 * Whoever holds the link opens it, as every link did before issue #198; the default, and what a
	 * link stored without a type is.
	 */
	ANONYMOUS("ANONYMOUS"),

	/**
	 * The person behind the link says who they are: a contact of the space.
	 *
	 * <p>
	 * With {@link ShareLink#recipients} it is <em>addressed</em>: only those contacts get in, each
	 * through a link of their own. Without, it is <em>open</em>: anybody who proves an address gets
	 * in (issues #199 and #200), and a contact the space already recognises does.
	 * </p>
	 */
	PERSONAL("PERSONAL"),

	;

	private final String _protocolName;

	private ShareType(String protocolName) {
		_protocolName = protocolName;
	}

	/**
	 * The protocol name of a {@link ShareType} constant.
	 *
	 * @see #valueOfProtocol(String)
	 */
	@Override
	public String protocolName() {
		return _protocolName;
	}

	/** Looks up a {@link ShareType} constant by it's protocol name. */
	public static ShareType valueOfProtocol(String protocolName) {
		if (protocolName == null) { return null; }
		switch (protocolName) {
			case "ANONYMOUS": return ANONYMOUS;
			case "PERSONAL": return PERSONAL;
		}
		return ANONYMOUS;
	}

	/** Writes this instance to the given output. */
	public final void writeTo(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		out.value(protocolName());
	}

	/** Reads a new instance from the given reader. */
	public static ShareType readShareType(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		return valueOfProtocol(in.nextString());
	}

	/** Writes this instance to the given binary output. */
	public final void writeTo(de.haumacher.msgbuf.binary.DataWriter out) throws java.io.IOException {
		switch (this) {
			case ANONYMOUS: out.value(1); break;
			case PERSONAL: out.value(2); break;
			default: out.value(0);
		}
	}

	/** Reads a new instance from the given binary reader. */
	public static ShareType readShareType(de.haumacher.msgbuf.binary.DataReader in) throws java.io.IOException {
		switch (in.nextInt()) {
			case 1: return ANONYMOUS;
			case 2: return PERSONAL;
			default: return ANONYMOUS;
		}
	}
}
