package de.haumacher.imageServer.shared.model;

/**
 * What <code>&lt;data&gt;/?action=end-other-sessions</code> did, asked by a contact in a session
 * of a personal link (issue #203): their other browsers are signed out.
 */
public class OtherSessionsEnded extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.OtherSessionsEnded} instance.
	 */
	public static de.haumacher.imageServer.shared.model.OtherSessionsEnded create() {
		return new de.haumacher.imageServer.shared.model.OtherSessionsEnded();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.OtherSessionsEnded} type in JSON format. */
	public static final String OTHER_SESSIONS_ENDED__TYPE = "OtherSessionsEnded";

	/** @see #getEnded() */
	private static final String ENDED__PROP = "ended";

	private int _ended = 0;

	/**
	 * Creates a {@link OtherSessionsEnded} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.OtherSessionsEnded#create()
	 */
	protected OtherSessionsEnded() {
		super();
	}

	/**
	 * How many sessions were ended.
	 */
	public final int getEnded() {
		return _ended;
	}

	/**
	 * @see #getEnded()
	 */
	public de.haumacher.imageServer.shared.model.OtherSessionsEnded setEnded(int value) {
		internalSetEnded(value);
		return this;
	}

	/** Internal setter for {@link #getEnded()} without chain call utility. */
	protected final void internalSetEnded(int value) {
		_ended = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.OtherSessionsEnded readOtherSessionsEnded(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.OtherSessionsEnded result = new de.haumacher.imageServer.shared.model.OtherSessionsEnded();
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
		out.name(ENDED__PROP);
		out.value(getEnded());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case ENDED__PROP: setEnded(in.nextInt()); break;
			default: super.readField(in, field);
		}
	}

}
