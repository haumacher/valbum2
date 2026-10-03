package de.haumacher.imageServer.shared.model;

/**
 * A code was mailed, the answer of <code>?action=prove-email</code> (issue #199).
 */
public class EmailProofSent extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.EmailProofSent} instance.
	 */
	public static de.haumacher.imageServer.shared.model.EmailProofSent create() {
		return new de.haumacher.imageServer.shared.model.EmailProofSent();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.EmailProofSent} type in JSON format. */
	public static final String EMAIL_PROOF_SENT__TYPE = "EmailProofSent";

	/** @see #getAddress() */
	private static final String ADDRESS__PROP = "address";

	/** @see #getExpires() */
	private static final String EXPIRES__PROP = "expires";

	/** @see #getAttempts() */
	private static final String ATTEMPTS__PROP = "attempts";

	private String _address = "";

	private String _expires = "";

	private int _attempts = 0;

	/**
	 * Creates a {@link EmailProofSent} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.EmailProofSent#create()
	 */
	protected EmailProofSent() {
		super();
	}

	/**
	 * The address the code went to, masked.
	 */
	public final String getAddress() {
		return _address;
	}

	/**
	 * @see #getAddress()
	 */
	public de.haumacher.imageServer.shared.model.EmailProofSent setAddress(String value) {
		internalSetAddress(value);
		return this;
	}

	/** Internal setter for {@link #getAddress()} without chain call utility. */
	protected final void internalSetAddress(String value) {
		_address = value;
	}

	/**
	 * When the code runs out, an ISO-8601 instant.
	 */
	public final String getExpires() {
		return _expires;
	}

	/**
	 * @see #getExpires()
	 */
	public de.haumacher.imageServer.shared.model.EmailProofSent setExpires(String value) {
		internalSetExpires(value);
		return this;
	}

	/** Internal setter for {@link #getExpires()} without chain call utility. */
	protected final void internalSetExpires(String value) {
		_expires = value;
	}

	/**
	 * How many attempts the code allows.
	 */
	public final int getAttempts() {
		return _attempts;
	}

	/**
	 * @see #getAttempts()
	 */
	public de.haumacher.imageServer.shared.model.EmailProofSent setAttempts(int value) {
		internalSetAttempts(value);
		return this;
	}

	/** Internal setter for {@link #getAttempts()} without chain call utility. */
	protected final void internalSetAttempts(int value) {
		_attempts = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.EmailProofSent readEmailProofSent(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.EmailProofSent result = new de.haumacher.imageServer.shared.model.EmailProofSent();
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
		out.name(ADDRESS__PROP);
		out.value(getAddress());
		out.name(EXPIRES__PROP);
		out.value(getExpires());
		out.name(ATTEMPTS__PROP);
		out.value(getAttempts());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case ADDRESS__PROP: setAddress(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case EXPIRES__PROP: setExpires(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case ATTEMPTS__PROP: setAttempts(in.nextInt()); break;
			default: super.readField(in, field);
		}
	}

}
