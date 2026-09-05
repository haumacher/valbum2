package de.haumacher.imageServer.shared.model;

/**
 * Request asking a folder which of the given contents it already holds, sent to
 * <code>&lt;folder&gt;/?action=check</code>.
 *
 * <p>
 * Asking is a read: it only reveals what the folder contains. A client sends it before an upload
 * so that it can skip transferring what is already there.
 * </p>
 */
public class UploadCheck extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.UploadCheck} instance.
	 */
	public static de.haumacher.imageServer.shared.model.UploadCheck create() {
		return new de.haumacher.imageServer.shared.model.UploadCheck();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.UploadCheck} type in JSON format. */
	public static final String UPLOAD_CHECK__TYPE = "UploadCheck";

	/** @see #getHashes() */
	private static final String HASHES__PROP = "hashes";

	private final java.util.List<de.haumacher.imageServer.shared.model.ContentHash> _hashes = new java.util.ArrayList<>();

	/**
	 * Creates a {@link UploadCheck} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.UploadCheck#create()
	 */
	protected UploadCheck() {
		super();
	}

	/**
	 * The contents the client intends to upload.
	 *
	 * <p>
	 * A list of messages, not a list of plain strings: the Dart backend of the model generator
	 * mis-types a <code>repeated string</code> field.
	 * </p>
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.ContentHash> getHashes() {
		return _hashes;
	}

	/**
	 * @see #getHashes()
	 */
	public de.haumacher.imageServer.shared.model.UploadCheck setHashes(java.util.List<? extends de.haumacher.imageServer.shared.model.ContentHash> value) {
		internalSetHashes(value);
		return this;
	}

	/** Internal setter for {@link #getHashes()} without chain call utility. */
	protected final void internalSetHashes(java.util.List<? extends de.haumacher.imageServer.shared.model.ContentHash> value) {
		if (value == null) throw new IllegalArgumentException("Property 'hashes' cannot be null.");
		_hashes.clear();
		_hashes.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getHashes()} list.
	 */
	public de.haumacher.imageServer.shared.model.UploadCheck addHashe(de.haumacher.imageServer.shared.model.ContentHash value) {
		internalAddHashe(value);
		return this;
	}

	/** Implementation of {@link #addHashe(de.haumacher.imageServer.shared.model.ContentHash)} without chain call utility. */
	protected final void internalAddHashe(de.haumacher.imageServer.shared.model.ContentHash value) {
		_hashes.add(value);
	}

	/**
	 * Removes a value from the {@link #getHashes()} list.
	 */
	public final void removeHashe(de.haumacher.imageServer.shared.model.ContentHash value) {
		_hashes.remove(value);
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.UploadCheck readUploadCheck(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.UploadCheck result = new de.haumacher.imageServer.shared.model.UploadCheck();
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
		out.name(HASHES__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.ContentHash x : getHashes()) {
			x.writeTo(out);
		}
		out.endArray();
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case HASHES__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addHashe(de.haumacher.imageServer.shared.model.ContentHash.readContentHash(in));
				}
				in.endArray();
			}
			break;
			default: super.readField(in, field);
		}
	}

}
