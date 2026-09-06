package de.haumacher.imageServer.shared.model;

/**
 * Answer to a {@link MoveRequest}: what happened to every name it asked for.
 *
 * <p>
 * A refusal that concerns a single entry is reported here, not as an error: the other entries did
 * move. Only a request that could not be carried out at all (an unreadable body, a folder that
 * does not exist, a caller that may not write) is answered with an {@link ErrorInfo}.
 * </p>
 */
public class MoveResult extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.MoveResult} instance.
	 */
	public static de.haumacher.imageServer.shared.model.MoveResult create() {
		return new de.haumacher.imageServer.shared.model.MoveResult();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.MoveResult} type in JSON format. */
	public static final String MOVE_RESULT__TYPE = "MoveResult";

	/** @see #getOutcomes() */
	private static final String OUTCOMES__PROP = "outcomes";

	private final java.util.List<de.haumacher.imageServer.shared.model.MoveOutcome> _outcomes = new java.util.ArrayList<>();

	/**
	 * Creates a {@link MoveResult} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.MoveResult#create()
	 */
	protected MoveResult() {
		super();
	}

	/**
	 * One entry per {@link MoveRequest#getNames()}, in the order they were asked for.
	 */
	public final java.util.List<de.haumacher.imageServer.shared.model.MoveOutcome> getOutcomes() {
		return _outcomes;
	}

	/**
	 * @see #getOutcomes()
	 */
	public de.haumacher.imageServer.shared.model.MoveResult setOutcomes(java.util.List<? extends de.haumacher.imageServer.shared.model.MoveOutcome> value) {
		internalSetOutcomes(value);
		return this;
	}

	/** Internal setter for {@link #getOutcomes()} without chain call utility. */
	protected final void internalSetOutcomes(java.util.List<? extends de.haumacher.imageServer.shared.model.MoveOutcome> value) {
		if (value == null) throw new IllegalArgumentException("Property 'outcomes' cannot be null.");
		_outcomes.clear();
		_outcomes.addAll(value);
	}

	/**
	 * Adds a value to the {@link #getOutcomes()} list.
	 */
	public de.haumacher.imageServer.shared.model.MoveResult addOutcome(de.haumacher.imageServer.shared.model.MoveOutcome value) {
		internalAddOutcome(value);
		return this;
	}

	/** Implementation of {@link #addOutcome(de.haumacher.imageServer.shared.model.MoveOutcome)} without chain call utility. */
	protected final void internalAddOutcome(de.haumacher.imageServer.shared.model.MoveOutcome value) {
		_outcomes.add(value);
	}

	/**
	 * Removes a value from the {@link #getOutcomes()} list.
	 */
	public final void removeOutcome(de.haumacher.imageServer.shared.model.MoveOutcome value) {
		_outcomes.remove(value);
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.MoveResult readMoveResult(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.MoveResult result = new de.haumacher.imageServer.shared.model.MoveResult();
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
		out.name(OUTCOMES__PROP);
		out.beginArray();
		for (de.haumacher.imageServer.shared.model.MoveOutcome x : getOutcomes()) {
			x.writeTo(out);
		}
		out.endArray();
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case OUTCOMES__PROP: {
				in.beginArray();
				while (in.hasNext()) {
					addOutcome(de.haumacher.imageServer.shared.model.MoveOutcome.readMoveOutcome(in));
				}
				in.endArray();
			}
			break;
			default: super.readField(in, field);
		}
	}

}
