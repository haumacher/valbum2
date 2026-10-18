package de.haumacher.imageServer.shared.model;

/**
 * What happened to a single entry of a {@link MoveRequest}.
 */
public class MoveOutcome extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.MoveOutcome} instance.
	 */
	public static de.haumacher.imageServer.shared.model.MoveOutcome create() {
		return new de.haumacher.imageServer.shared.model.MoveOutcome();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.MoveOutcome} type in JSON format. */
	public static final String MOVE_OUTCOME__TYPE = "MoveOutcome";

	/** @see #getName() */
	private static final String NAME__PROP = "name";

	/** @see #getNewName() */
	private static final String NEW_NAME__PROP = "newName";

	/** @see #getMessage() */
	private static final String MESSAGE__PROP = "message";

	/** @see #getKept() */
	private static final String KEPT__PROP = "kept";

	private String _name = "";

	private String _newName = "";

	private String _message = "";

	private final java.util.List<de.haumacher.imageServer.shared.model.KeptPhoto> _kept = new java.util.ArrayList<>();

	/**
	 * Creates a {@link MoveOutcome} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.MoveOutcome#create()
	 */
	protected MoveOutcome() {
		super();
	}

	/**
	 * The name as it was asked for in {@link MoveRequest#getNames()}.
	 */
	public final String getName() {
		return _name;
	}

	/**
	 * @see #getName()
	 */
	public de.haumacher.imageServer.shared.model.MoveOutcome setName(String value) {
		internalSetName(value);
		return this;
	}

	/** Internal setter for {@link #getName()} without chain call utility. */
	protected final void internalSetName(String value) {
		_name = value;
	}

	/**
	 * The name the entry has in the target folder now, empty if it was not moved.
	 *
	 * <p>
	 * It differs from {@link #getName()} when the target folder already held that name with different
	 * contents: the moved file is renamed exactly as a colliding upload is. When the target
	 * already held the very same contents, this is the name of the file that has them there.
	 * </p>
	 *
	 * <p>
	 * A folder that the target's {@link ListingInfo#getPlacement()} rule filed away reports the path it
	 * has below the target folder (<code>2020/2020 Trip</code>), not just its name, see issue #48.
	 * </p>
	 */
	public final String getNewName() {
		return _newName;
	}

	/**
	 * @see #getNewName()
	 */
	public de.haumacher.imageServer.shared.model.MoveOutcome setNewName(String value) {
		internalSetNewName(value);
		return this;
	}

	/** Internal setter for {@link #getNewName()} without chain call utility. */
	protected final void internalSetNewName(String value) {
		_newName = value;
	}

	/**
	 * Why the entry was not moved, or what happened to it besides being moved; empty when it moved
	 * plainly.
	 *
	 * <p>
	 * Nothing declines silently: an entry that did not move always says why here.
	 * </p>
	 */
	public final String getMessage() {
		return _message;
	}

	/**
	 * @see #getMessage()
	 */
	public de.haumacher.imageServer.shared.model.MoveOutcome setMessage(String value) {
		internalSetMessage(value);
		return this;
	}

	/** Internal setter for {@link #getMessage()} without chain call utility. */
	protected final void internalSetMessage(String value) {
		_message = value;
	}

	/**
	 * The photographs of this entry a delete moved into an album that references them instead of
	 * into the trash, see issue #307; empty for every other outcome.
	 *
	 * <p>
	 * A photograph shown in other albums is never deleted into nothing: it goes to the album of its
	 * oldest reference and takes that reference's place there. For a photograph named by the
	 * request, at most one entry; for an album, one per such photograph below it.
	 * </p>
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.KeptPhoto> getKept() {
		return _kept;
	}

	/**
	 * @see #getKept()
	 */
	public de.haumacher.imageServer.shared.model.MoveOutcome setKept(java.util.List<? extends de.haumacher.imageServer.shared.model.KeptPhoto> value) {
		internalSetKept(value);
		return this;
	}

	/** Internal setter for {@link #getKept()} without chain call utility. */
	protected final void internalSetKept(java.util.List<? extends de.haumacher.imageServer.shared.model.KeptPhoto> value) {
		if (value == null) throw new IllegalArgumentException("Property 'kept' cannot be null.");
		_kept.clear();
		_kept.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getKept()} list.
	 */
	public de.haumacher.imageServer.shared.model.MoveOutcome addKept(de.haumacher.imageServer.shared.model.KeptPhoto value) {
		internalAddKept(value);
		return this;
	}

	/** Implementation of {@link #addKept(de.haumacher.imageServer.shared.model.KeptPhoto)} without chain call utility. */
	protected final void internalAddKept(de.haumacher.imageServer.shared.model.KeptPhoto value) {
		_kept.add(value);
	}

	/**
	 * Removes a value from the {@link #getKept()} list.
	 */
	public final void removeKept(de.haumacher.imageServer.shared.model.KeptPhoto value) {
		_kept.remove(value);
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.MoveOutcome readMoveOutcome(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.MoveOutcome result = new de.haumacher.imageServer.shared.model.MoveOutcome();
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
		out.name(NAME__PROP);
		out.value(getName());
		out.name(NEW_NAME__PROP);
		out.value(getNewName());
		out.name(MESSAGE__PROP);
		out.value(getMessage());
		out.name(KEPT__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.KeptPhoto x : getKept()) {
			x.writeTo(out);
		}
		out.endArray();
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case NAME__PROP: setName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case NEW_NAME__PROP: setNewName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case MESSAGE__PROP: setMessage(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case KEPT__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addKept(de.haumacher.imageServer.shared.model.KeptPhoto.readKeptPhoto(in));
				}
				in.endArray();
			}
			break;
			default: super.readField(in, field);
		}
	}

}
