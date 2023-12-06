package de.haumacher.imageServer.shared.model;

/**
 * A heading row separating images in an album.
 */
public class Heading extends AlbumPart {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.Heading} instance.
	 */
	public static de.haumacher.imageServer.shared.model.Heading create() {
		return new de.haumacher.imageServer.shared.model.Heading();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.Heading} type in JSON format. */
	public static final String HEADING__TYPE = "Heading";

	/** @see #getText() */
	private static final String TEXT__PROP = "text";

	private String _text = "";

	/**
	 * Creates a {@link Heading} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.Heading#create()
	 */
	protected Heading() {
		super();
	}

	@Override
	public TypeKind kind() {
		return TypeKind.HEADING;
	}

	/**
	 * The text to display.
	 */
	public final String getText() {
		return _text;
	}

	/**
	 * @see #getText()
	 */
	public de.haumacher.imageServer.shared.model.Heading setText(String value) {
		internalSetText(value);
		return this;
	}

	/** Internal setter for {@link #getText()} without chain call utility. */
	protected final void internalSetText(String value) {
		_text = value;
	}

	@Override
	public de.haumacher.imageServer.shared.model.Heading setOwner(de.haumacher.imageServer.shared.model.AlbumInfo value) {
		internalSetOwner(value);
		return this;
	}

	@Override
	public String jsonType() {
		return HEADING__TYPE;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.Heading readHeading(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.Heading result = new de.haumacher.imageServer.shared.model.Heading();
		result.readContent(in);
		return result;
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name(TEXT__PROP);
		out.value(getText());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case TEXT__PROP: setText(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

	@Override
	public <R,A,E extends Throwable> R visit(de.haumacher.imageServer.shared.model.AlbumPart.Visitor<R,A,E> v, A arg) throws E {
		return v.visit(this, arg);
	}

}
