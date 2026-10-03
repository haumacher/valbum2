package de.haumacher.imageServer.shared.model;

/**
 * A way to prove an address, see {@link IdentifyRequired#getMethods()}: <code>mail-code</code> (issue #199), <code>oidc:google</code>, …
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

	/** @see #getLabel() */
	private static final String LABEL__PROP = "label";

	private String _name = "";

	private String _label = "";

	/**
	 * Creates a {@link ProofMethod} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.ProofMethod#create()
	 */
	protected ProofMethod() {
		super();
	}

	/**
	 * The name of the method: <code>mail-code</code> (issue #199), or <code>oidc:&lt;provider&gt;</code>
	 * for a sign-in through OpenID Connect (issue #200), whose provider id is what
	 * {@link OidcStart#getProvider()} names.
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

	/**
	 * What to call the method on a button, for a provider of OpenID Connect its name as the server's
	 * configuration spells it ("Google"); empty for <code>mail-code</code>. Plain text: a page that
	 * shows it escapes it like any other text.
	 */
	public final String getLabel() {
		return _label;
	}

	/**
	 * @see #getLabel()
	 */
	public de.haumacher.imageServer.shared.model.ProofMethod setLabel(String value) {
		internalSetLabel(value);
		return this;
	}

	/** Internal setter for {@link #getLabel()} without chain call utility. */
	protected final void internalSetLabel(String value) {
		_label = value;
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
		out.name(LABEL__PROP);
		out.value(getLabel());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case NAME__PROP: setName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case LABEL__PROP: setLabel(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
