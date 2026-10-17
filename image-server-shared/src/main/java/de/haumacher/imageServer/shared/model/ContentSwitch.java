package de.haumacher.imageServer.shared.model;

/**
 * The content search of a space, see {@link SpaceSettings#content}.
 */
public enum ContentSwitch implements de.haumacher.msgbuf.data.ProtocolEnum {

	/**
	 * Nothing said: what a write of an app that does not know the switch carries, and what an
	 * answer of a server that has none reads. The first constant, so that an unknown value keeps
	 * what the space says.
	 */
	KEEP("KEEP"),

	/**
	 * No photo of the space is described; nothing is sent to the embedder.
	 */
	OFF("OFF"),

	/**
	 * Every photo of the space is described in the background, see issue #257.
	 */
	ON("ON"),

	;

	private final String _protocolName;

	private ContentSwitch(String protocolName) {
		_protocolName = protocolName;
	}

	/**
	 * The protocol name of a {@link ContentSwitch} constant.
	 *
	 * @see #valueOfProtocol(String)
	 */
	@Override
	public String protocolName() {
		return _protocolName;
	}

	/** Looks up a {@link ContentSwitch} constant by it's protocol name. */
	public static ContentSwitch valueOfProtocol(String protocolName) {
		if (protocolName == null) { return null; }
		switch (protocolName) {
			case "KEEP": return KEEP;
			case "OFF": return OFF;
			case "ON": return ON;
		}
		return KEEP;
	}

	/** Writes this instance to the given output. */
	public final void writeTo(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		out.value(protocolName());
	}

	/** Reads a new instance from the given reader. */
	public static ContentSwitch readContentSwitch(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		return valueOfProtocol(in.nextString());
	}

	/** Writes this instance to the given binary output. */
	public final void writeTo(de.haumacher.msgbuf.binary.DataWriter out) throws java.io.IOException {
		switch (this) {
			case KEEP: out.value(1); break;
			case OFF: out.value(2); break;
			case ON: out.value(3); break;
			default: out.value(0);
		}
	}

	/** Reads a new instance from the given binary reader. */
	public static ContentSwitch readContentSwitch(de.haumacher.msgbuf.binary.DataReader in) throws java.io.IOException {
		switch (in.nextInt()) {
			case 1: return KEEP;
			case 2: return OFF;
			case 3: return ON;
			default: return KEEP;
		}
	}
}
