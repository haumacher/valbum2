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

	/**
	 * A collection, see issues #221 and #307: an album that holds <em>references</em> to photographs
	 * lying in other albums of the space and no file of its own ("best of 2026").
	 *
	 * <p>
	 * Since issue #307 every album may hold references beside its own files (see
		 * {@link ImagePart#ref}), and a collection is simply an album without files of its own: it is
	 * shown, shared, downloaded and written exactly as any album, and a file uploaded or moved into
	 * it makes it an ordinary album. The kind is <b>stored</b> in <code>index.json</code> for the
	 * builds before issue #307, which know references in a collection only: the server writes it
	 * while the album holds no file of its own and was a collection or holds a reference, and
	 * {@link #ALBUM} otherwise &mdash; never what a client says. It is answered as stored, and the
	 * app shows it as an album. A build before issue #307 reads a collection unchanged; an album
	 * holding files <em>and</em> references it reads without the references, and its next write of
	 * that album drops them (the sidecar it replaces stays beside it as the timestamped backup every
	 * write leaves).
	 * </p>
	 *
	 * <p>
	 * Order, headings, the album picture, the labels and a description of a reference are the
	 * album's own; everything else a photograph says &mdash; its turn, crop, rating, privacy, time
	 * and faces &mdash; is the photograph's own, answered from and written to the album it lies in.
	 * Nothing is ever copied on disk.
	 * </p>
	 */
	COLLECTION("COLLECTION"),

	/**
	 * A saved search, see issue #227: an album that holds nothing but a {@link AlbumInfo#query}
	 * and shows, whenever it is opened, the photographs of the space that match it.
	 *
	 * <p>
	 * Like {@link #COLLECTION} this kind is <b>stored</b> in <code>index.json</code>, written once
	 * when the saved search is created and never changed afterwards. Its sidecar holds the query,
	 * the title, the date, the star and the chosen album picture &mdash; never a part and never a
	 * reference: the parts are answered live, each photograph as its own album shows it to the
	 * caller, named by its path below the folder the search looks in (the folder the saved search
	 * lies in, the root of the space for one at the root). Everything a photograph says &mdash; its
	 * turn, crop, description, rating, privacy, labels &mdash; is the photograph's own, answered
	 * from and written to the album it lies in.
	 * </p>
	 */
	SEARCH("SEARCH"),

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
			case "COLLECTION": return COLLECTION;
			case "SEARCH": return SEARCH;
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
			case COLLECTION: out.value(3); break;
			case SEARCH: out.value(4); break;
			default: out.value(0);
		}
	}

	/** Reads a new instance from the given binary reader. */
	public static AlbumKind readAlbumKind(de.haumacher.msgbuf.binary.DataReader in) throws java.io.IOException {
		switch (in.nextInt()) {
			case 1: return ALBUM;
			case 2: return INBOX;
			case 3: return COLLECTION;
			case 4: return SEARCH;
			default: return ALBUM;
		}
	}
}
