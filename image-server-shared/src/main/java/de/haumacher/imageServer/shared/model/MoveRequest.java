package de.haumacher.imageServer.shared.model;

/**
 * Request to move entries of one folder into another one, sent to
 * <code>&lt;source folder&gt;/?action=move</code>.
 *
 * <p>
 * Moving is a rename: the pixels of an original are never touched, and everything the album knows
 * about a moved image (rating, privacy level, comment, orientation) travels with it, see issue
 * #47.
 * </p>
 */
public class MoveRequest extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.MoveRequest} instance.
	 */
	public static de.haumacher.imageServer.shared.model.MoveRequest create() {
		return new de.haumacher.imageServer.shared.model.MoveRequest();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.MoveRequest} type in JSON format. */
	public static final String MOVE_REQUEST__TYPE = "MoveRequest";

	/** @see #getTarget() */
	private static final String TARGET__PROP = "target";

	/** @see #getNames() */
	private static final String NAMES__PROP = "names";

	private String _target = "";

	private final java.util.List<de.haumacher.imageServer.shared.model.MoveName> _names = new java.util.ArrayList<>();

	/**
	 * Creates a {@link MoveRequest} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.MoveRequest#create()
	 */
	protected MoveRequest() {
		super();
	}

	/**
	 * The folder the named entries are moved into, as a path relative to the caller's space.
	 *
	 * <p>
	 * The empty string is the space root itself. A path leaving the caller's space is refused, as
	 * it is on every other endpoint.
	 * </p>
	 */
	public final String getTarget() {
		return _target;
	}

	/**
	 * @see #getTarget()
	 */
	public de.haumacher.imageServer.shared.model.MoveRequest setTarget(String value) {
		internalSetTarget(value);
		return this;
	}

	/** Internal setter for {@link #getTarget()} without chain call utility. */
	protected final void internalSetTarget(String value) {
		_target = value;
	}

	/**
	 * The entries of the addressed folder to move.
	 *
	 * <p>
	 * Either the {@link ImagePart#getName()} of an image or video file, or the name of a sub-folder (an
	 * album or a folder of folders). Naming the representative of an {@link ImageGroup} moves the
	 * whole group; naming another member of it takes only that member out of the group.
	 * </p>
	 *
	 * <p>
	 * A list of messages, not a list of plain strings: the Dart backend of the model generator
	 * mis-types a <code>repeated string</code> field, see {@link UploadCheck#getHashes()}.
	 * </p>
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.MoveName> getNames() {
		return _names;
	}

	/**
	 * @see #getNames()
	 */
	public de.haumacher.imageServer.shared.model.MoveRequest setNames(java.util.List<? extends de.haumacher.imageServer.shared.model.MoveName> value) {
		internalSetNames(value);
		return this;
	}

	/** Internal setter for {@link #getNames()} without chain call utility. */
	protected final void internalSetNames(java.util.List<? extends de.haumacher.imageServer.shared.model.MoveName> value) {
		if (value == null) throw new IllegalArgumentException("Property 'names' cannot be null.");
		_names.clear();
		_names.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getNames()} list.
	 */
	public de.haumacher.imageServer.shared.model.MoveRequest addName(de.haumacher.imageServer.shared.model.MoveName value) {
		internalAddName(value);
		return this;
	}

	/** Implementation of {@link #addName(de.haumacher.imageServer.shared.model.MoveName)} without chain call utility. */
	protected final void internalAddName(de.haumacher.imageServer.shared.model.MoveName value) {
		_names.add(value);
	}

	/**
	 * Removes a value from the {@link #getNames()} list.
	 */
	public final void removeName(de.haumacher.imageServer.shared.model.MoveName value) {
		_names.remove(value);
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.MoveRequest readMoveRequest(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.MoveRequest result = new de.haumacher.imageServer.shared.model.MoveRequest();
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
		out.name(TARGET__PROP);
		out.value(getTarget());
		out.name(NAMES__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.MoveName x : getNames()) {
			x.writeTo(out);
		}
		out.endArray();
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case TARGET__PROP: setTarget(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case NAMES__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addName(de.haumacher.imageServer.shared.model.MoveName.readMoveName(in));
				}
				in.endArray();
			}
			break;
			default: super.readField(in, field);
		}
	}

}
