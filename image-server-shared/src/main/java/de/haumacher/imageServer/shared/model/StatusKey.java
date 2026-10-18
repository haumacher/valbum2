package de.haumacher.imageServer.shared.model;

/**
 * A name or an id in a list, see {@link StatusInfo#getPlacesLoading()} and {@link StatusSeen#getIds()}.
 */
public class StatusKey extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.StatusKey} instance.
	 */
	public static de.haumacher.imageServer.shared.model.StatusKey create() {
		return new de.haumacher.imageServer.shared.model.StatusKey();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.StatusKey} type in JSON format. */
	public static final String STATUS_KEY__TYPE = "StatusKey";

	/** @see #getId() */
	private static final String ID__PROP = "id";

	private String _id = "";

	/**
	 * Creates a {@link StatusKey} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.StatusKey#create()
	 */
	protected StatusKey() {
		super();
	}

	/**
	 * The name or id.
	 */
	public final String getId() {
		return _id;
	}

	/**
	 * @see #getId()
	 */
	public de.haumacher.imageServer.shared.model.StatusKey setId(String value) {
		internalSetId(value);
		return this;
	}

	/** Internal setter for {@link #getId()} without chain call utility. */
	protected final void internalSetId(String value) {
		_id = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.StatusKey readStatusKey(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.StatusKey result = new de.haumacher.imageServer.shared.model.StatusKey();
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
