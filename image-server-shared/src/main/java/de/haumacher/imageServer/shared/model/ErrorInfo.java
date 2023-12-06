package de.haumacher.imageServer.shared.model;

/**
 * {@link Resource} that produced a server-side error while loading.
 */
public class ErrorInfo extends Resource {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.ErrorInfo} instance.
	 */
	public static de.haumacher.imageServer.shared.model.ErrorInfo create() {
		return new de.haumacher.imageServer.shared.model.ErrorInfo();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.ErrorInfo} type in JSON format. */
	public static final String ERROR_INFO__TYPE = "ErrorInfo";

	/** @see #getMessage() */
	private static final String MESSAGE__PROP = "message";

	private String _message = "";

	/**
	 * Creates a {@link ErrorInfo} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.ErrorInfo#create()
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
	public de.haumacher.imageServer.shared.model.ErrorInfo setMessage(String value) {
		internalSetMessage(value);
		return this;
	}

	/** Internal setter for {@link #getMessage()} without chain call utility. */
	protected final void internalSetMessage(String value) {
		_message = value;
	}

	@Override
	public String jsonType() {
		return ERROR_INFO__TYPE;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.ErrorInfo readErrorInfo(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.ErrorInfo result = new de.haumacher.imageServer.shared.model.ErrorInfo();
		result.readContent(in);
		return result;
	}

	@Override
	protected void writeFields(de.haumacher.msgbuf.json.JsonWriter out) throws java.io.IOException {
		super.writeFields(out);
		out.name(MESSAGE__PROP);
		out.value(getMessage());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case MESSAGE__PROP: setMessage(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

	@Override
	public <R,A,E extends Throwable> R visit(de.haumacher.imageServer.shared.model.Resource.Visitor<R,A,E> v, A arg) throws E {
		return v.visit(this, arg);
	}

}
