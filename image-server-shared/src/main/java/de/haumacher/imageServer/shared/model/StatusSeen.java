package de.haumacher.imageServer.shared.model;

/**
 * Marks problems of the status as seen, <code>POST &lt;data&gt;/?action=status-seen</code>, see
 * {@link StatusProblem#isSeen()}; answered with the {@link StatusInfo} as it is then. An
 * administrator's write.
 */
public class StatusSeen extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.StatusSeen} instance.
	 */
	public static de.haumacher.imageServer.shared.model.StatusSeen create() {
		return new de.haumacher.imageServer.shared.model.StatusSeen();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.StatusSeen} type in JSON format. */
	public static final String STATUS_SEEN__TYPE = "StatusSeen";

	/** @see #getIds() */
	private static final String IDS__PROP = "ids";

	/** @see #isAll() */
	private static final String ALL__PROP = "all";

	private final java.util.List<de.haumacher.imageServer.shared.model.StatusKey> _ids = new java.util.ArrayList<>();

	private boolean _all = false;

	/**
	 * Creates a {@link StatusSeen} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.StatusSeen#create()
	 */
	protected StatusSeen() {
		super();
	}

	/**
	 * The {@link StatusProblem#getId() ids} of the problems marked.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.StatusKey> getIds() {
		return _ids;
	}

	/**
	 * @see #getIds()
	 */
	public de.haumacher.imageServer.shared.model.StatusSeen setIds(java.util.List<? extends de.haumacher.imageServer.shared.model.StatusKey> value) {
		internalSetIds(value);
		return this;
	}

	/** Internal setter for {@link #getIds()} without chain call utility. */
	protected final void internalSetIds(java.util.List<? extends de.haumacher.imageServer.shared.model.StatusKey> value) {
		if (value == null) throw new IllegalArgumentException("Property 'ids' cannot be null.");
		_ids.clear();
		_ids.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getIds()} list.
	 */
	public de.haumacher.imageServer.shared.model.StatusSeen addId(de.haumacher.imageServer.shared.model.StatusKey value) {
		internalAddId(value);
		return this;
	}

	/** Implementation of {@link #addId(de.haumacher.imageServer.shared.model.StatusKey)} without chain call utility. */
	protected final void internalAddId(de.haumacher.imageServer.shared.model.StatusKey value) {
		_ids.add(value);
	}

	/**
	 * Removes a value from the {@link #getIds()} list.
	 */
	public final void removeId(de.haumacher.imageServer.shared.model.StatusKey value) {
		_ids.remove(value);
	}

	/**
	 * Whether every problem is marked ("Mark all as seen").
	 */
	public final boolean isAll() {
		return _all;
	}

	/**
	 * @see #isAll()
	 */
	public de.haumacher.imageServer.shared.model.StatusSeen setAll(boolean value) {
		internalSetAll(value);
		return this;
	}

	/** Internal setter for {@link #isAll()} without chain call utility. */
	protected final void internalSetAll(boolean value) {
		_all = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.StatusSeen readStatusSeen(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.StatusSeen result = new de.haumacher.imageServer.shared.model.StatusSeen();
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
		out.name(IDS__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.StatusKey x : getIds()) {
			x.writeTo(out);
		}
		out.endArray();
		out.name(ALL__PROP);
		out.value(isAll());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case IDS__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addId(de.haumacher.imageServer.shared.model.StatusKey.readStatusKey(in));
				}
				in.endArray();
			}
			break;
			case ALL__PROP: setAll(in.nextBoolean()); break;
			default: super.readField(in, field);
		}
	}

}
