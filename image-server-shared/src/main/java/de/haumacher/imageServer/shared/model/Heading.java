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

	/**
	 * Creates a {@link Heading} instance.
	 *
	 * @see #create()
	 */
	protected Heading() {
		super();
	}

	private String _text = "";

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
		return "Heading";
	}

	@Override
	public Object get(String field) {
		switch (field) {
			case "text": return getText();
			default: return super.get(field);
		}
	}

	@Override
	public void set(String field, Object value) {
		switch (field) {
			case "text": setText((String) value); break;
			default: super.set(field, value); break;
		}
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name("text");
		out.value(getText());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case "text": setText(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

	@Override
	public int typeId() {
		return 3;
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.binary.DataWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name(1);
		out.value(getText());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.binary.DataReader in, int field) throws java.io.IOException {
		switch (field) {
			case 1: setText(in.nextString()); break;
			default: super.readField(in, field);
		}
	}

	/** Reads a new instance from the given reader. */
	public static Heading readHeading(de.haumacher.msgbuf.binary.DataReader in) throws java.io.IOException {
		in.beginObject();
		Heading result = new Heading();
		while (in.hasNext()) {
			int field = in.nextName();
			result.readField(in, field);
		}
		in.endObject();
		return result;
	}

	@Override
	public <R,A> R visit(AlbumPart.Visitor<R,A> v, A arg) {
		return v.visit(this, arg);
	}

}
