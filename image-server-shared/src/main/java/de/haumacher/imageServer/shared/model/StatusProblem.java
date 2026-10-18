package de.haumacher.imageServer.shared.model;

/**
 * One problem of the server, see {@link StatusInfo#getProblems()}: one cause, with every file or folder
 * it concerns.
 */
public class StatusProblem extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.StatusProblem} instance.
	 */
	public static de.haumacher.imageServer.shared.model.StatusProblem create() {
		return new de.haumacher.imageServer.shared.model.StatusProblem();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.StatusProblem} type in JSON format. */
	public static final String STATUS_PROBLEM__TYPE = "StatusProblem";

	/** @see #getId() */
	private static final String ID__PROP = "id";

	/** @see #getKind() */
	private static final String KIND__PROP = "kind";

	/** @see #getStep() */
	private static final String STEP__PROP = "step";

	/** @see #getStepName() */
	private static final String STEP_NAME__PROP = "stepName";

	/** @see #getReason() */
	private static final String REASON__PROP = "reason";

	/** @see #getFiles() */
	private static final String FILES__PROP = "files";

	/** @see #getAlbums() */
	private static final String ALBUMS__PROP = "albums";

	/** @see #getItems() */
	private static final String ITEMS__PROP = "items";

	/** @see #isSeen() */
	private static final String SEEN__PROP = "seen";

	/** @see #isRetryable() */
	private static final String RETRYABLE__PROP = "retryable";

	/** @see #getRetryAt() */
	private static final String RETRY_AT__PROP = "retryAt";

	private String _id = "";

	private de.haumacher.imageServer.shared.model.StatusProblemKind _kind = de.haumacher.imageServer.shared.model.StatusProblemKind.FAILURE;

	private de.haumacher.imageServer.shared.model.CatchUpStep _step = de.haumacher.imageServer.shared.model.CatchUpStep.IDLE;

	private String _stepName = "";

	private String _reason = "";

	private int _files = 0;

	private int _albums = 0;

	private final java.util.List<de.haumacher.imageServer.shared.model.StatusItem> _items = new java.util.ArrayList<>();

	private boolean _seen = false;

	private boolean _retryable = false;

	private long _retryAt = 0L;

	/**
	 * Creates a {@link StatusProblem} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.StatusProblem#create()
	 */
	protected StatusProblem() {
		super();
	}

	/**
	 * What identifies the cause: the kind, the step and the reason. The same while the cause is the
	 * same, so that the app can mark it as seen.
	 */
	public final String getId() {
		return _id;
	}

	/**
	 * @see #getId()
	 */
	public de.haumacher.imageServer.shared.model.StatusProblem setId(String value) {
		internalSetId(value);
		return this;
	}

	/** Internal setter for {@link #getId()} without chain call utility. */
	protected final void internalSetId(String value) {
		_id = value;
	}

	/**
	 * What the problem is about.
	 */
	public final de.haumacher.imageServer.shared.model.StatusProblemKind getKind() {
		return _kind;
	}

	/**
	 * @see #getKind()
	 */
	public de.haumacher.imageServer.shared.model.StatusProblem setKind(de.haumacher.imageServer.shared.model.StatusProblemKind value) {
		internalSetKind(value);
		return this;
	}

	/** Internal setter for {@link #getKind()} without chain call utility. */
	protected final void internalSetKind(de.haumacher.imageServer.shared.model.StatusProblemKind value) {
		if (value == null) throw new IllegalArgumentException("Property 'kind' cannot be null.");
		_kind = value;
	}

	/**
	 * The step that failed, for {@link StatusProblemKind#FAILURE}.
	 */
	public final de.haumacher.imageServer.shared.model.CatchUpStep getStep() {
		return _step;
	}

	/**
	 * @see #getStep()
	 */
	public de.haumacher.imageServer.shared.model.StatusProblem setStep(de.haumacher.imageServer.shared.model.CatchUpStep value) {
		internalSetStep(value);
		return this;
	}

	/** Internal setter for {@link #getStep()} without chain call utility. */
	protected final void internalSetStep(de.haumacher.imageServer.shared.model.CatchUpStep value) {
		if (value == null) throw new IllegalArgumentException("Property 'step' cannot be null.");
		_step = value;
	}

	/**
	 * The step's own name, for {@link StatusProblemKind#FAILURE}.
	 */
	public final String getStepName() {
		return _stepName;
	}

	/**
	 * @see #getStepName()
	 */
	public de.haumacher.imageServer.shared.model.StatusProblem setStepName(String value) {
		internalSetStepName(value);
		return this;
	}

	/** Internal setter for {@link #getStepName()} without chain call utility. */
	protected final void internalSetStepName(String value) {
		_stepName = value;
	}

	/**
	 * Why, as the server words it.
	 */
	public final String getReason() {
		return _reason;
	}

	/**
	 * @see #getReason()
	 */
	public de.haumacher.imageServer.shared.model.StatusProblem setReason(String value) {
		internalSetReason(value);
		return this;
	}

	/** Internal setter for {@link #getReason()} without chain call utility. */
	protected final void internalSetReason(String value) {
		_reason = value;
	}

	/**
	 * How many files (or folders, where the cause is a folder's) it concerns: {@link #getItems()}.
	 */
	public final int getFiles() {
		return _files;
	}

	/**
	 * @see #getFiles()
	 */
	public de.haumacher.imageServer.shared.model.StatusProblem setFiles(int value) {
		internalSetFiles(value);
		return this;
	}

	/** Internal setter for {@link #getFiles()} without chain call utility. */
	protected final void internalSetFiles(int value) {
		_files = value;
	}

	/**
	 * In how many albums.
	 */
	public final int getAlbums() {
		return _albums;
	}

	/**
	 * @see #getAlbums()
	 */
	public de.haumacher.imageServer.shared.model.StatusProblem setAlbums(int value) {
		internalSetAlbums(value);
		return this;
	}

	/** Internal setter for {@link #getAlbums()} without chain call utility. */
	protected final void internalSetAlbums(int value) {
		_albums = value;
	}

	/**
	 * Every file or folder it concerns, by folder and name; for {@link StatusProblemKind#UNWRITABLE}
	 * every folder with its path on the server and how many photos it holds.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.StatusItem> getItems() {
		return _items;
	}

	/**
	 * @see #getItems()
	 */
	public de.haumacher.imageServer.shared.model.StatusProblem setItems(java.util.List<? extends de.haumacher.imageServer.shared.model.StatusItem> value) {
		internalSetItems(value);
		return this;
	}

	/** Internal setter for {@link #getItems()} without chain call utility. */
	protected final void internalSetItems(java.util.List<? extends de.haumacher.imageServer.shared.model.StatusItem> value) {
		if (value == null) throw new IllegalArgumentException("Property 'items' cannot be null.");
		_items.clear();
		_items.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getItems()} list.
	 */
	public de.haumacher.imageServer.shared.model.StatusProblem addItem(de.haumacher.imageServer.shared.model.StatusItem value) {
		internalAddItem(value);
		return this;
	}

	/** Implementation of {@link #addItem(de.haumacher.imageServer.shared.model.StatusItem)} without chain call utility. */
	protected final void internalAddItem(de.haumacher.imageServer.shared.model.StatusItem value) {
		_items.add(value);
	}

	/**
	 * Removes a value from the {@link #getItems()} list.
	 */
	public final void removeItem(de.haumacher.imageServer.shared.model.StatusItem value) {
		_items.remove(value);
	}

	/**
	 * Whether an administrator marked the problem as seen, and it did not change since: no new file,
	 * no other reason.
	 */
	public final boolean isSeen() {
		return _seen;
	}

	/**
	 * @see #isSeen()
	 */
	public de.haumacher.imageServer.shared.model.StatusProblem setSeen(boolean value) {
		internalSetSeen(value);
		return this;
	}

	/** Internal setter for {@link #isSeen()} without chain call utility. */
	protected final void internalSetSeen(boolean value) {
		_seen = value;
	}

	/**
	 * Whether <code>?action=status-retry</code> can do something for it now.
	 */
	public final boolean isRetryable() {
		return _retryable;
	}

	/**
	 * @see #isRetryable()
	 */
	public de.haumacher.imageServer.shared.model.StatusProblem setRetryable(boolean value) {
		internalSetRetryable(value);
		return this;
	}

	/** Internal setter for {@link #isRetryable()} without chain call utility. */
	protected final void internalSetRetryable(boolean value) {
		_retryable = value;
	}

	/**
	 * When it is tried again by itself, in milliseconds since the epoch; <code>0</code> for not by itself.
	 */
	public final long getRetryAt() {
		return _retryAt;
	}

	/**
	 * @see #getRetryAt()
	 */
	public de.haumacher.imageServer.shared.model.StatusProblem setRetryAt(long value) {
		internalSetRetryAt(value);
		return this;
	}

	/** Internal setter for {@link #getRetryAt()} without chain call utility. */
	protected final void internalSetRetryAt(long value) {
		_retryAt = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.StatusProblem readStatusProblem(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.StatusProblem result = new de.haumacher.imageServer.shared.model.StatusProblem();
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
		out.name(ID__PROP);
		out.value(getId());
		out.name(KIND__PROP);
		getKind().writeTo(out);
		out.name(STEP__PROP);
		getStep().writeTo(out);
		out.name(STEP_NAME__PROP);
		out.value(getStepName());
		out.name(REASON__PROP);
		out.value(getReason());
		out.name(FILES__PROP);
		out.value(getFiles());
		out.name(ALBUMS__PROP);
		out.value(getAlbums());
		out.name(ITEMS__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.StatusItem x : getItems()) {
			x.writeTo(out);
		}
		out.endArray();
		out.name(SEEN__PROP);
		out.value(isSeen());
		out.name(RETRYABLE__PROP);
		out.value(isRetryable());
		out.name(RETRY_AT__PROP);
		out.value(getRetryAt());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case ID__PROP: setId(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case KIND__PROP: setKind(de.haumacher.imageServer.shared.model.StatusProblemKind.readStatusProblemKind(in)); break;
			case STEP__PROP: setStep(de.haumacher.imageServer.shared.model.CatchUpStep.readCatchUpStep(in)); break;
			case STEP_NAME__PROP: setStepName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case REASON__PROP: setReason(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case FILES__PROP: setFiles(in.nextInt()); break;
			case ALBUMS__PROP: setAlbums(in.nextInt()); break;
			case ITEMS__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addItem(de.haumacher.imageServer.shared.model.StatusItem.readStatusItem(in));
				}
				in.endArray();
			}
			break;
			case SEEN__PROP: setSeen(in.nextBoolean()); break;
			case RETRYABLE__PROP: setRetryable(in.nextBoolean()); break;
			case RETRY_AT__PROP: setRetryAt(in.nextLong()); break;
			default: super.readField(in, field);
		}
	}

}
