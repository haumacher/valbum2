package de.haumacher.imageServer.shared.model;

/**
 * What a {@link FolderInfo} of a listing stands for, see {@link FolderInfo#kind} and issue #133.
 *
 * <p>
 * The kind is derived from the disk on every read, exactly like {@link FolderInfo#effectiveDate}:
 * the folder's own sidecar says it ({@link AlbumInfo} or {@link ListingInfo}), and a folder without
 * one is what the server would answer for it — an album as soon as it holds images.
 * </p>
 *
 * <p>
 * {@link #ALBUM} is the first constant and therefore the value a listing from a server that does
 * not know this field yet reads as: such a listing behaves exactly as it did before, every entry
 * an album.
 * </p>
 */
public enum FolderKind implements de.haumacher.msgbuf.data.ProtocolEnum {

	/**
	 * The entry is an album: a folder of photographs, and the only kind that has a date.
	 */
	ALBUM("ALBUM"),

	/**
	 * The entry is a folder of folders: it holds albums (and further folders), and it has no date
	 * of its own.
	 *
	 * <p>
	 * Such a folder still carries an {@link FolderInfo#effectiveDate}, because that is what the
	 * listing is sorted by — a folder named <code>2026</code> sorts with the year it names. It is a
	 * sort key and not a day anything happened on, so nothing shows it as a date, see issue #133.
	 * </p>
	 */
	FOLDER("FOLDER"),

	;

	private final String _protocolName;

	private FolderKind(String protocolName) {
		_protocolName = protocolName;
	}

	/**
	 * The protocol name of a {@link FolderKind} constant.
	 *
	 * @see #valueOfProtocol(String)
	 */
	@Override
	public String protocolName() {
		return _protocolName;
	}

	/** Looks up a {@link FolderKind} constant by it's protocol name. */
	public static FolderKind valueOfProtocol(String protocolName) {
		if (protocolName == null) { return null; }
		switch (protocolName) {
			case "ALBUM": return ALBUM;
			case "FOLDER": return FOLDER;
		}
		return ALBUM;
	}

	/** Writes this instance to the given output. */
	public final void writeTo(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		out.value(protocolName());
	}

	/** Reads a new instance from the given reader. */
	public static FolderKind readFolderKind(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		return valueOfProtocol(in.nextString());
	}

	/** Writes this instance to the given binary output. */
	public final void writeTo(de.haumacher.msgbuf.binary.DataWriter out) throws java.io.IOException {
		switch (this) {
			case ALBUM: out.value(1); break;
			case FOLDER: out.value(2); break;
			default: out.value(0);
		}
	}

	/** Reads a new instance from the given binary reader. */
	public static FolderKind readFolderKind(de.haumacher.msgbuf.binary.DataReader in) throws java.io.IOException {
		switch (in.nextInt()) {
			case 1: return ALBUM;
			case 2: return FOLDER;
			default: return ALBUM;
		}
	}
}
