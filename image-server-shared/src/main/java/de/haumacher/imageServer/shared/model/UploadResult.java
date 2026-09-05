package de.haumacher.imageServer.shared.model;

/**
 * Answer to an upload, telling for every received file whether it was stored or was already
 * present.
 *
 * <p>
 * An upload is idempotent: a retry after a lost connection reports the files as
 * {@link UploadedFile#getStatus() present} instead of storing them a second time.
 * </p>
 */
public class UploadResult extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.UploadResult} instance.
	 */
	public static de.haumacher.imageServer.shared.model.UploadResult create() {
		return new de.haumacher.imageServer.shared.model.UploadResult();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.UploadResult} type in JSON format. */
	public static final String UPLOAD_RESULT__TYPE = "UploadResult";

	/** @see #getFiles() */
	private static final String FILES__PROP = "files";

	private final java.util.List<de.haumacher.imageServer.shared.model.UploadedFile> _files = new java.util.ArrayList<>();

	/**
	 * Creates a {@link UploadResult} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.UploadResult#create()
	 */
	protected UploadResult() {
		super();
	}

	/**
	 * One entry per file of the upload request, in the order they were received.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.UploadedFile> getFiles() {
		return _files;
	}

	/**
	 * @see #getFiles()
	 */
	public de.haumacher.imageServer.shared.model.UploadResult setFiles(java.util.List<? extends de.haumacher.imageServer.shared.model.UploadedFile> value) {
		internalSetFiles(value);
		return this;
	}

	/** Internal setter for {@link #getFiles()} without chain call utility. */
	protected final void internalSetFiles(java.util.List<? extends de.haumacher.imageServer.shared.model.UploadedFile> value) {
		if (value == null) throw new IllegalArgumentException("Property 'files' cannot be null.");
		_files.clear();
		_files.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getFiles()} list.
	 */
	public de.haumacher.imageServer.shared.model.UploadResult addFile(de.haumacher.imageServer.shared.model.UploadedFile value) {
		internalAddFile(value);
		return this;
	}

	/** Implementation of {@link #addFile(de.haumacher.imageServer.shared.model.UploadedFile)} without chain call utility. */
	protected final void internalAddFile(de.haumacher.imageServer.shared.model.UploadedFile value) {
		_files.add(value);
	}

	/**
	 * Removes a value from the {@link #getFiles()} list.
	 */
	public final void removeFile(de.haumacher.imageServer.shared.model.UploadedFile value) {
		_files.remove(value);
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.UploadResult readUploadResult(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.UploadResult result = new de.haumacher.imageServer.shared.model.UploadResult();
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
		out.name(FILES__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.UploadedFile x : getFiles()) {
			x.writeTo(out);
		}
		out.endArray();
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case FILES__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addFile(de.haumacher.imageServer.shared.model.UploadedFile.readUploadedFile(in));
				}
				in.endArray();
			}
			break;
			default: super.readField(in, field);
		}
	}

}
