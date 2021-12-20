package de.haumacher.imageServer.shared.model;

/**
 * A heading row separating images in an album.
 */
public class Heading extends AlbumPart {

	/**
	 * Creates a {@link Heading} instance.
	 */
	public static Heading create() {
		return new Heading();
	}

	/** Identifier for the {@link Heading} type in JSON format. */
	public static final String HEADING__TYPE = "Heading";

	/** @see #getText() */
	private static final String TEXT = "text";

	private String _text = "";

	/**
	 * Creates a {@link Heading} instance.
	 *
	 * @see #create()
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
	public final Heading setText(String value) {
		_text = value;
		return this;
	}

	/** Reads a new instance from the given reader. */
	public static Heading readHeading(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		Heading result = new Heading();
		in.beginObject();
		result.readFields(in);
		in.endObject();
		return result;
	}

	@Override
	public String jsonType() {
		return HEADING__TYPE;
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name(TEXT);
		out.value(getText());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case TEXT: setText(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

	@Override
	public <R,A> R visit(AlbumPart.Visitor<R,A> v, A arg) {
		return v.visit(this, arg);
	}

}
