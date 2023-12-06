package de.haumacher.imageServer.shared.model;

/**
 * Kind of image.
 */
public enum ImageKind implements de.haumacher.msgbuf.data.ProtocolEnum {

	/**
	 * A JPEG image.
	 */
	IMAGE("IMAGE"),

	/**
	 * A mp4 video. 
	 *
	 * <p>For historical reason, this kind is named "video" and not "mp4".</p>
	 */
	VIDEO("VIDEO"),

	/**
	 * A quicktime video.
	 */
	QUICKTIME("QUICKTIME"),

	;

	private final String _protocolName;

	private ImageKind(String protocolName) {
		_protocolName = protocolName;
	}

	/**
	 * The protocol name of a {@link ImageKind} constant.
	 *
	 * @see #valueOfProtocol(String)
	 */
	@Override
	public String protocolName() {
		return _protocolName;
	}

	/** Looks up a {@link ImageKind} constant by it's protocol name. */
	public static ImageKind valueOfProtocol(String protocolName) {
		if (protocolName == null) { return null; }
		switch (protocolName) {
			case "IMAGE": return IMAGE;
			case "VIDEO": return VIDEO;
			case "QUICKTIME": return QUICKTIME;
		}
		return IMAGE;
	}

	/** Writes this instance to the given output. */
	public final void writeTo(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		out.value(protocolName());
	}

	/** Reads a new instance from the given reader. */
	public static ImageKind readImageKind(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		return valueOfProtocol(in.nextString());
	}

	/** Writes this instance to the given binary output. */
	public final void writeTo(de.haumacher.msgbuf.binary.DataWriter out) throws java.io.IOException {
		switch (this) {
			case IMAGE: out.value(1); break;
			case VIDEO: out.value(2); break;
			case QUICKTIME: out.value(3); break;
			default: out.value(0);
		}
	}

	/** Reads a new instance from the given binary reader. */
	public static ImageKind readImageKind(de.haumacher.msgbuf.binary.DataReader in) throws java.io.IOException {
		switch (in.nextInt()) {
			case 1: return IMAGE;
			case 2: return VIDEO;
			case 3: return QUICKTIME;
			default: return IMAGE;
		}
	}
}
