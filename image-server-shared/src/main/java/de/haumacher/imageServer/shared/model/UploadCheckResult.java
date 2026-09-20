package de.haumacher.imageServer.shared.model;

/**
 * Answer to an {@link UploadCheck} naming those of the asked hashes the space already holds.
 *
 * <p>
 * Since issue #118 the answer is not limited to the addressed folder any more: a photo that was
 * synced into the inbox and then moved into an album is still <em>present</em>, wherever it went,
 * see {@link PresentFile#getName()}. A contribution through a share link is the exception and stays
 * confined to the shared folder: a link sees no more of the space than its folder.
 * </p>
 */
public class UploadCheckResult extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.UploadCheckResult} instance.
	 */
	public static de.haumacher.imageServer.shared.model.UploadCheckResult create() {
		return new de.haumacher.imageServer.shared.model.UploadCheckResult();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.UploadCheckResult} type in JSON format. */
	public static final String UPLOAD_CHECK_RESULT__TYPE = "UploadCheckResult";

	/** @see #getPresent() */
	private static final String PRESENT__PROP = "present";

	/** @see #getIndexed() */
	private static final String INDEXED__PROP = "indexed";

	private final java.util.List<de.haumacher.imageServer.shared.model.PresentFile> _present = new java.util.ArrayList<>();

	private de.haumacher.imageServer.shared.model.IndexProgress _indexed = null;

	/**
	 * Creates a {@link UploadCheckResult} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.UploadCheckResult#create()
	 */
	protected UploadCheckResult() {
		super();
	}

	/**
	 * The asked contents that are already present, in the order they were asked for.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.PresentFile> getPresent() {
		return _present;
	}

	/**
	 * @see #getPresent()
	 */
	public de.haumacher.imageServer.shared.model.UploadCheckResult setPresent(java.util.List<? extends de.haumacher.imageServer.shared.model.PresentFile> value) {
		internalSetPresent(value);
		return this;
	}

	/** Internal setter for {@link #getPresent()} without chain call utility. */
	protected final void internalSetPresent(java.util.List<? extends de.haumacher.imageServer.shared.model.PresentFile> value) {
		if (value == null) throw new IllegalArgumentException("Property 'present' cannot be null.");
		_present.clear();
		_present.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getPresent()} list.
	 */
	public de.haumacher.imageServer.shared.model.UploadCheckResult addPresent(de.haumacher.imageServer.shared.model.PresentFile value) {
		internalAddPresent(value);
		return this;
	}

	/** Implementation of {@link #addPresent(de.haumacher.imageServer.shared.model.PresentFile)} without chain call utility. */
	protected final void internalAddPresent(de.haumacher.imageServer.shared.model.PresentFile value) {
		_present.add(value);
	}

	/**
	 * Removes a value from the {@link #getPresent()} list.
	 */
	public final void removePresent(de.haumacher.imageServer.shared.model.PresentFile value) {
		_present.remove(value);
	}

	/**
	 * How far the space's hash index has got, see issue #118; <code>null</code> where the server
	 * keeps no index (an older build, or a share-link caller, which is answered from the shared
	 * folder alone).
	 *
	 * <p>
	 * A client that syncs a camera roll reads it before it transfers anything: while the index is
	 * incomplete, a photo that lies in a not-yet-indexed album is not recognised and would be
	 * uploaded a second time.
	 * </p>
	 */
	public final de.haumacher.imageServer.shared.model.IndexProgress getIndexed() {
		return _indexed;
	}

	/**
	 * @see #getIndexed()
	 */
	public de.haumacher.imageServer.shared.model.UploadCheckResult setIndexed(de.haumacher.imageServer.shared.model.IndexProgress value) {
		internalSetIndexed(value);
		return this;
	}

	/** Internal setter for {@link #getIndexed()} without chain call utility. */
	protected final void internalSetIndexed(de.haumacher.imageServer.shared.model.IndexProgress value) {
		_indexed = value;
	}

	/**
	 * Checks, whether {@link #getIndexed()} has a value.
	 */
	public final boolean hasIndexed() {
		return _indexed != null;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.UploadCheckResult readUploadCheckResult(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.UploadCheckResult result = new de.haumacher.imageServer.shared.model.UploadCheckResult();
		result.readContent(in);
		return result;
	}

	@Override
	public final void writeTo(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		writeContent(out);
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name(PRESENT__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.PresentFile x : getPresent()) {
			x.writeTo(out);
		}
		out.endArray();
		if (hasIndexed()) {
			out.name(INDEXED__PROP);
			getIndexed().writeTo(out);
		}
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case PRESENT__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addPresent(de.haumacher.imageServer.shared.model.PresentFile.readPresentFile(in));
				}
				in.endArray();
			}
			break;
			case INDEXED__PROP: setIndexed(de.haumacher.imageServer.shared.model.IndexProgress.readIndexProgress(in)); break;
			default: super.readField(in, field);
		}
	}

}
