package de.haumacher.imageServer.shared.model;

/**
 * What an {@link AlbumInfo} is: an ordinary album, or the inbox of its space, see issues #131 and
 * #226.
 *
 * <p>
 * An inbox is a <em>kind of album</em> and not a resource of its own: the same folder, the same
 * <code>index.json</code>, the same parts, so that every mechanism an album has — upload, hashes,
 * moving, deleting, thumbnails, attribution — works there unchanged. Since issue #226 a space has
 * exactly one inbox, the folder its <code>space.json</code> names (<code>inbox</code>, default
 * <code>Inbox</code>), and the kind is <em>derived</em> from that place on every read: the folder
 * is answered {@link #INBOX}, every other album {@link #ALBUM}, whatever an older sidecar says.
 * It is never stored any more — a sidecar written before #226 that says <code>INBOX</code> is read
 * as an album unless it is the space's inbox, and loses the word on its next ordinary write.
 * </p>
 */
public enum AlbumKind implements de.haumacher.msgbuf.data.ProtocolEnum {

	/**
	 * An ordinary album: the author's order, the author's groups, the author's headings.
	 */
	ALBUM("ALBUM"),

	/**
	 * An inbox: photographs waiting to be sorted into albums.
	 *
	 * <p>
	 * The server answers an inbox {@link AlbumInfo#parts flat and by date} whatever the sidecar
	 * lists, gives it no {@link AlbumInfo#effectiveDate date} of its own, files it nowhere, and
	 * never shows it to anybody but a caller who may {@link RightName edit} it — a contributor
	 * sees their own contributions there and nobody else sees that it exists at all. It cannot be
	 * shared by a link.
	 * </p>
	 */
	INBOX("INBOX"),

	;

	private final String _protocolName;

	private AlbumKind(String protocolName) {
		_protocolName = protocolName;
	}

	/**
	 * The protocol name of a {@link AlbumKind} constant.
	 *
	 * @see #valueOfProtocol(String)
	 */
	@Override
	public String protocolName() {
		return _protocolName;
	}

	/** Looks up a {@link AlbumKind} constant by it's protocol name. */
	public static AlbumKind valueOfProtocol(String protocolName) {
		if (protocolName == null) { return null; }
		switch (protocolName) {
			case "ALBUM": return ALBUM;
			case "INBOX": return INBOX;
		}
		return ALBUM;
	}

	/** Writes this instance to the given output. */
	public final void writeTo(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		out.value(protocolName());
	}

	/** Reads a new instance from the given reader. */
	public static AlbumKind readAlbumKind(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		return valueOfProtocol(in.nextString());
	}

	/** Writes this instance to the given binary output. */
	public final void writeTo(de.haumacher.msgbuf.binary.DataWriter out) throws java.io.IOException {
		switch (this) {
			case ALBUM: out.value(1); break;
			case INBOX: out.value(2); break;
			default: out.value(0);
		}
	}

	/** Reads a new instance from the given binary reader. */
	public static AlbumKind readAlbumKind(de.haumacher.msgbuf.binary.DataReader in) throws java.io.IOException {
		switch (in.nextInt()) {
			case 1: return ALBUM;
			case 2: return INBOX;
			default: return ALBUM;
		}
	}
}
