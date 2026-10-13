package de.haumacher.imageServer.shared.model;

/**
 * The target of a {@link MoveRequest} was created for it, see {@link MoveRequest#getCreated()}.
 *
 * <p>
 * The target is then given its cover in the same step as the photographs arrive, under the names
 * they arrived with, and only where it has no cover yet: (1) the first best-rated photograph that
 * arrived where its rating is above normal; else (2) the photograph of {@link #getCover()}; else (3)
 * the first photograph that arrived in the order of their dates. A trashed or unreadable
 * photograph never counts. Where the hinted photograph was set aside because the target already
 * held the identical file, the copy that is there stands for it.
 * </p>
 */
public class CreatedTarget extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.CreatedTarget} instance.
	 */
	public static de.haumacher.imageServer.shared.model.CreatedTarget create() {
		return new de.haumacher.imageServer.shared.model.CreatedTarget();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.CreatedTarget} type in JSON format. */
	public static final String CREATED_TARGET__TYPE = "CreatedTarget";

	/** @see #getCover() */
	private static final String COVER__PROP = "cover";

	private String _cover = "";

	/**
	 * Creates a {@link CreatedTarget} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.CreatedTarget#create()
	 */
	protected CreatedTarget() {
		super();
	}

	/**
	 * The photograph the request was opened on (the context menu of a photo, see issue #273): one
	 * of {@link MoveRequest#getNames()}, as it is named in the addressed folder; empty for none (the
	 * selection bar has no photograph under the pointer).
	 */
	public final String getCover() {
		return _cover;
	}

	/**
	 * @see #getCover()
	 */
	public de.haumacher.imageServer.shared.model.CreatedTarget setCover(String value) {
		internalSetCover(value);
		return this;
	}

	/** Internal setter for {@link #getCover()} without chain call utility. */
	protected final void internalSetCover(String value) {
		_cover = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.CreatedTarget readCreatedTarget(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.CreatedTarget result = new de.haumacher.imageServer.shared.model.CreatedTarget();
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
		out.name(COVER__PROP);
		out.value(getCover());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case COVER__PROP: setCover(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
