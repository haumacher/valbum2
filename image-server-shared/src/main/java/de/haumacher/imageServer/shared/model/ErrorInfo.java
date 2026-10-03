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

	/** @see #getIdentify() */
	private static final String IDENTIFY__PROP = "identify";

	private String _message = "";

	private de.haumacher.imageServer.shared.model.IdentifyRequired _identify = null;

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

	/**
	 * What a personal share link needs to know before it lets the caller in, see issue #198.
	 *
	 * <p>
	 * Set on the <code>401</code>/<code>403</code> a personal link answers a caller it does not
	 * recognise; <code>null</code> on every other refusal. It rides on the {@link ErrorInfo} rather
	 * than being a kind of its own, so that every client keeps reading the {@link #getMessage()}.
	 * </p>
	 */
	public final de.haumacher.imageServer.shared.model.IdentifyRequired getIdentify() {
		return _identify;
	}

	/**
	 * @see #getIdentify()
	 */
	public de.haumacher.imageServer.shared.model.ErrorInfo setIdentify(de.haumacher.imageServer.shared.model.IdentifyRequired value) {
		internalSetIdentify(value);
		return this;
	}

	/** Internal setter for {@link #getIdentify()} without chain call utility. */
	protected final void internalSetIdentify(de.haumacher.imageServer.shared.model.IdentifyRequired value) {
		_identify = value;
	}

	/**
	 * Checks, whether {@link #getIdentify()} has a value.
	 */
	public final boolean hasIdentify() {
		return _identify != null;
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
		if (hasIdentify()) {
			out.name(IDENTIFY__PROP);
			getIdentify().writeTo(out);
		}
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case MESSAGE__PROP: setMessage(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case IDENTIFY__PROP: setIdentify(de.haumacher.imageServer.shared.model.IdentifyRequired.readIdentifyRequired(in)); break;
			default: super.readField(in, field);
		}
	}

	@Override
	public <R,A,E extends Throwable> R visit(de.haumacher.imageServer.shared.model.Resource.Visitor<R,A,E> v, A arg) throws E {
		return v.visit(this, arg);
	}

}
