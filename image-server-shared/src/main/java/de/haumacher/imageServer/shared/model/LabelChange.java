package de.haumacher.imageServer.shared.model;

/**
 * What <code>?action=relabel</code> asks for: one label of an album renamed or removed on every
 * photograph carrying it, see issue #213.
 */
public class LabelChange extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.LabelChange} instance.
	 */
	public static de.haumacher.imageServer.shared.model.LabelChange create() {
		return new de.haumacher.imageServer.shared.model.LabelChange();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.LabelChange} type in JSON format. */
	public static final String LABEL_CHANGE__TYPE = "LabelChange";

	/** @see #getFrom() */
	private static final String FROM__PROP = "from";

	/** @see #getTo() */
	private static final String TO__PROP = "to";

	private String _from = "";

	private String _to = "";

	/**
	 * Creates a {@link LabelChange} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.LabelChange#create()
	 */
	protected LabelChange() {
		super();
	}

	/**
	 * The label as the photographs carry it.
	 */
	public final String getFrom() {
		return _from;
	}

	/**
	 * @see #getFrom()
	 */
	public de.haumacher.imageServer.shared.model.LabelChange setFrom(String value) {
		internalSetFrom(value);
		return this;
	}

	/** Internal setter for {@link #getFrom()} without chain call utility. */
	protected final void internalSetFrom(String value) {
		_from = value;
	}

	/**
	 * The new label; empty removes the label from every photograph.
	 *
	 * <p>
	 * A share link on the album showing {@link #getFrom()} is carried along to the new name; a removal
	 * leaves such a link showing nothing.
	 * </p>
	 */
	public final String getTo() {
		return _to;
	}

	/**
	 * @see #getTo()
	 */
	public de.haumacher.imageServer.shared.model.LabelChange setTo(String value) {
		internalSetTo(value);
		return this;
	}

	/** Internal setter for {@link #getTo()} without chain call utility. */
	protected final void internalSetTo(String value) {
		_to = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.LabelChange readLabelChange(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.LabelChange result = new de.haumacher.imageServer.shared.model.LabelChange();
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
		out.name(FROM__PROP);
		out.value(getFrom());
		out.name(TO__PROP);
		out.value(getTo());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case FROM__PROP: setFrom(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case TO__PROP: setTo(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
