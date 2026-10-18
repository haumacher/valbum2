package de.haumacher.imageServer.shared.model;

/**
 * Tries again what a problem of the status failed at, now, <code>POST &lt;data&gt;/?action=status-retry</code>:
 * for a cause fixed elsewhere (the embedder started, the permissions mended). Answered with the
 * {@link StatusInfo} as it is then; a problem that is fixed leaves the list once the retry is done.
 * An administrator's write.
 */
public class StatusRetry extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.StatusRetry} instance.
	 */
	public static de.haumacher.imageServer.shared.model.StatusRetry create() {
		return new de.haumacher.imageServer.shared.model.StatusRetry();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.StatusRetry} type in JSON format. */
	public static final String STATUS_RETRY__TYPE = "StatusRetry";

	/** @see #getId() */
	private static final String ID__PROP = "id";

	private String _id = "";

	/**
	 * Creates a {@link StatusRetry} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.StatusRetry#create()
	 */
	protected StatusRetry() {
		super();
	}

	/**
	 * The {@link StatusProblem#getId()} of the problem.
	 */
	public final String getId() {
		return _id;
	}

	/**
	 * @see #getId()
	 */
	public de.haumacher.imageServer.shared.model.StatusRetry setId(String value) {
		internalSetId(value);
		return this;
	}

	/** Internal setter for {@link #getId()} without chain call utility. */
	protected final void internalSetId(String value) {
		_id = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.StatusRetry readStatusRetry(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.StatusRetry result = new de.haumacher.imageServer.shared.model.StatusRetry();
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
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case ID__PROP: setId(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
