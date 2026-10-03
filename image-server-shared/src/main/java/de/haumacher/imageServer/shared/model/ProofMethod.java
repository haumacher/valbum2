package de.haumacher.imageServer.shared.model;

/**
 * A way to prove an address, see {@link IdentifyRequired#getMethods()}: <code>mail-code</code>, <code>oidc:google</code>, …
 */
public class ProofMethod extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.ProofMethod} instance.
	 */
	public static de.haumacher.imageServer.shared.model.ProofMethod create() {
		return new de.haumacher.imageServer.shared.model.ProofMethod();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.ProofMethod} type in JSON format. */
	public static final String PROOF_METHOD__TYPE = "ProofMethod";

	/** @see #getName() */
	private static final String NAME__PROP = "name";

	private String _name = "";

	/**
	 * Creates a {@link ProofMethod} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.ProofMethod#create()
	 */
	protected ProofMethod() {
		super();
	}

	/**
	 * The name of the method.
	 */
	public final String getName() {
		return _name;
	}

	/**
	 * @see #getName()
	 */
	public de.haumacher.imageServer.shared.model.ProofMethod setName(String value) {
		internalSetName(value);
		return this;
	}

	/** Internal setter for {@link #getName()} without chain call utility. */
	protected final void internalSetName(String value) {
		_name = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.ProofMethod readProofMethod(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.ProofMethod result = new de.haumacher.imageServer.shared.model.ProofMethod();
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
		out.name(NAME__PROP);
		out.value(getName());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case NAME__PROP: setName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
