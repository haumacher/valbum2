package de.haumacher.imageServer.shared.model;

/**
 * How far the hash index of a space has got, see {@link UploadCheckResult#getIndexed()} and issue #118.
 *
 * <p>
 * Folders, not photos: the index walks the space one folder at a time, and a folder is the unit a
 * person recognises in a progress line. {@link #getDone()} equals {@link #getTotal()} exactly when the index
 * is complete; while the index has not even said how much there is to do, {@link #getTotal()} is one
 * more than {@link #getDone()}, so that an incomplete index never reads as a complete one.
 * </p>
 */
public class IndexProgress extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.IndexProgress} instance.
	 */
	public static de.haumacher.imageServer.shared.model.IndexProgress create() {
		return new de.haumacher.imageServer.shared.model.IndexProgress();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.IndexProgress} type in JSON format. */
	public static final String INDEX_PROGRESS__TYPE = "IndexProgress";

	/** @see #getDone() */
	private static final String DONE__PROP = "done";

	/** @see #getTotal() */
	private static final String TOTAL__PROP = "total";

	private int _done = 0;

	private int _total = 0;

	/**
	 * Creates a {@link IndexProgress} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.IndexProgress#create()
	 */
	protected IndexProgress() {
		super();
	}

	/**
	 * The number of folders that are indexed.
	 */
	public final int getDone() {
		return _done;
	}

	/**
	 * @see #getDone()
	 */
	public de.haumacher.imageServer.shared.model.IndexProgress setDone(int value) {
		internalSetDone(value);
		return this;
	}

	/** Internal setter for {@link #getDone()} without chain call utility. */
	protected final void internalSetDone(int value) {
		_done = value;
	}

	/**
	 * The number of folders holding images; never less than {@link #getDone()}.
	 */
	public final int getTotal() {
		return _total;
	}

	/**
	 * @see #getTotal()
	 */
	public de.haumacher.imageServer.shared.model.IndexProgress setTotal(int value) {
		internalSetTotal(value);
		return this;
	}

	/** Internal setter for {@link #getTotal()} without chain call utility. */
	protected final void internalSetTotal(int value) {
		_total = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.IndexProgress readIndexProgress(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.IndexProgress result = new de.haumacher.imageServer.shared.model.IndexProgress();
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
		out.name(DONE__PROP);
		out.value(getDone());
		out.name(TOTAL__PROP);
		out.value(getTotal());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case DONE__PROP: setDone(in.nextInt()); break;
			case TOTAL__PROP: setTotal(in.nextInt()); break;
			default: super.readField(in, field);
		}
	}

}
