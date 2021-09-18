package de.haumacher.imageServer.shared.model;

/**
 * {@link Resource} that produced a server-side error while loading.
 */
public class ErrorInfo extends Resource {

	/**
	 * Creates a {@link ErrorInfo} instance.
	 */
	public static ErrorInfo create() {
		return new ErrorInfo();
	}

	/**
	 * Creates a {@link ErrorInfo} instance.
	 *
	 * @see #create()
	 */
	protected ErrorInfo() {
		super();
	}

	private String _message = "";

	/**
	 * The error message.
	 */
	public final String getMessage() {
		return _message;
	}

	/**
	 * @see #getMessage()
	 */
	public final ErrorInfo setMessage(String value) {
		_message = value;
		return this;
	}

	/** Reads a new instance from the given reader. */
	public static ErrorInfo readErrorInfo(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		ErrorInfo result = new ErrorInfo();
		in.beginObject();
		result.readFields(in);
		in.endObject();
		return result;
	}

	@Override
	public String jsonType() {
		return "ErrorInfo";
	}

	@Override
	public Object get(String field) {
		switch (field) {
			case "message": return getMessage();
			default: return super.get(field);
		}
	}

	@Override
	public void set(String field, Object value) {
		switch (field) {
			case "message": setMessage((String) value); break;
			default: super.set(field, value); break;
		}
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name("message");
		out.value(getMessage());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case "message": setMessage(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

	@Override
	public int typeId() {
		return 5;
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.binary.DataWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name(2);
		out.value(getMessage());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.binary.DataReader in, int field) throws java.io.IOException {
		switch (field) {
			case 2: setMessage(in.nextString()); break;
			default: super.readField(in, field);
		}
	}

	/** Reads a new instance from the given reader. */
	public static ErrorInfo readErrorInfo(de.haumacher.msgbuf.binary.DataReader in) throws java.io.IOException {
		in.beginObject();
		ErrorInfo result = new ErrorInfo();
		while (in.hasNext()) {
			int field = in.nextName();
			result.readField(in, field);
		}
		in.endObject();
		return result;
	}

	@Override
	public <R,A> R visit(Resource.Visitor<R,A> v, A arg) {
		return v.visit(this, arg);
	}

}
