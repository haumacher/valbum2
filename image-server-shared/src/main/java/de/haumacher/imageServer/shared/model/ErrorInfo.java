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

	/** Identifier for the {@link ErrorInfo} type in JSON format. */
	public static final String ERROR_INFO__TYPE = "ErrorInfo";

	/** @see #getMessage() */
	private static final String MESSAGE = "message";

	private String _message = "";

	/**
	 * Creates a {@link ErrorInfo} instance.
	 *
	 * @see #create()
	 */
	protected ErrorInfo() {
		super();
	}

	@Override
	public TypeKind kind() {
		return TypeKind.ERROR_INFO;
	}

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
		return ERROR_INFO__TYPE;
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name(MESSAGE);
		out.value(getMessage());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case MESSAGE: setMessage(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

	@Override
	public <R,A> R visit(Resource.Visitor<R,A> v, A arg) {
		return v.visit(this, arg);
	}

}
