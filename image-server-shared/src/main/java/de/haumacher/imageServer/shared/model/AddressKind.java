package de.haumacher.imageServer.shared.model;

/**
 * What an address of a contact is, see {@link ContactAddress} and issue #198.
 */
public enum AddressKind implements de.haumacher.msgbuf.data.ProtocolEnum {

	/**
	 * An e-mail address, stored lower-cased.
	 */
	EMAIL("EMAIL"),

	/**
	 * A phone number, stored in E.164 (<code>+4917…</code>) where the input says its country, else as given.
	 */
	PHONE("PHONE"),

	;

	private final String _protocolName;

	private AddressKind(String protocolName) {
		_protocolName = protocolName;
	}

	/**
	 * The protocol name of a {@link AddressKind} constant.
	 *
	 * @see #valueOfProtocol(String)
	 */
	@Override
	public String protocolName() {
		return _protocolName;
	}

	/** Looks up a {@link AddressKind} constant by it's protocol name. */
	public static AddressKind valueOfProtocol(String protocolName) {
		if (protocolName == null) { return null; }
		switch (protocolName) {
			case "EMAIL": return EMAIL;
			case "PHONE": return PHONE;
		}
		return EMAIL;
	}

	/** Writes this instance to the given output. */
	public final void writeTo(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		out.value(protocolName());
	}

	/** Reads a new instance from the given reader. */
	public static AddressKind readAddressKind(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		return valueOfProtocol(in.nextString());
	}

	/** Writes this instance to the given binary output. */
	public final void writeTo(de.haumacher.msgbuf.binary.DataWriter out) throws java.io.IOException {
		switch (this) {
			case EMAIL: out.value(1); break;
			case PHONE: out.value(2); break;
			default: out.value(0);
		}
	}

	/** Reads a new instance from the given binary reader. */
	public static AddressKind readAddressKind(de.haumacher.msgbuf.binary.DataReader in) throws java.io.IOException {
		switch (in.nextInt()) {
			case 1: return EMAIL;
			case 2: return PHONE;
			default: return EMAIL;
		}
	}
}
