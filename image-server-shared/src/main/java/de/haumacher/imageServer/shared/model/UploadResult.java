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

	/** @see #getRefused() */
	private static final String REFUSED__PROP = "refused";

	private final java.util.List<de.haumacher.imageServer.shared.model.UploadedFile> _files = new java.util.ArrayList<>();

	private final java.util.List<de.haumacher.imageServer.shared.model.RefusedFile> _refused = new java.util.ArrayList<>();

	/**
	 * Creates a {@link UploadResult} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.UploadResult#create()
	 */
	protected UploadResult() {
		super();
	}

	/**
	 * One entry per stored or present file of the upload request, in the order they were received.
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

	/**
	 * The files of the request that were not taken, in the order they were received, see issue
	 * #186.
	 *
	 * <p>
	 * A file of a format the library does not hold (a <code>.webp</code>, a <code>.txt</code>) or
	 * of a name it never shows (a hidden name, a NAS's or a desktop's litter) is refused on its
	 * own: every other file of the request is stored as if it had come alone. Empty where
	 * everything was taken; a request that holds nothing the library takes is refused as a whole
	 * (<code>415</code> with an {@link ErrorInfo} naming the files) instead.
	 * </p>
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.RefusedFile> getRefused() {
		return _refused;
	}

	/**
	 * @see #getRefused()
	 */
	public de.haumacher.imageServer.shared.model.UploadResult setRefused(java.util.List<? extends de.haumacher.imageServer.shared.model.RefusedFile> value) {
		internalSetRefused(value);
		return this;
	}

	/** Internal setter for {@link #getRefused()} without chain call utility. */
	protected final void internalSetRefused(java.util.List<? extends de.haumacher.imageServer.shared.model.RefusedFile> value) {
		if (value == null) throw new IllegalArgumentException("Property 'refused' cannot be null.");
		_refused.clear();
		_refused.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getRefused()} list.
	 */
	public de.haumacher.imageServer.shared.model.UploadResult addRefused(de.haumacher.imageServer.shared.model.RefusedFile value) {
		internalAddRefused(value);
		return this;
	}

	/** Implementation of {@link #addRefused(de.haumacher.imageServer.shared.model.RefusedFile)} without chain call utility. */
	protected final void internalAddRefused(de.haumacher.imageServer.shared.model.RefusedFile value) {
		_refused.add(value);
	}

	/**
	 * Removes a value from the {@link #getRefused()} list.
	 */
	public final void removeRefused(de.haumacher.imageServer.shared.model.RefusedFile value) {
		_refused.remove(value);
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
		out.name(REFUSED__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.RefusedFile x : getRefused()) {
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
			case REFUSED__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addRefused(de.haumacher.imageServer.shared.model.RefusedFile.readRefusedFile(in));
				}
				in.endArray();
			}
			break;
			default: super.readField(in, field);
		}
	}

}
