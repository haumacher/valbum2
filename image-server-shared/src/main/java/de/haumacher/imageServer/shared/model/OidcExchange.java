package de.haumacher.imageServer.shared.model;

/**
 * Finishing a sign-in through OpenID Connect, <code>&lt;data&gt;/?action=oidc-exchange</code>
 * (issue #200).
 *
 * <p>
 * Sent on the same link as {@link OidcStart}, with the code the callback put behind
 * <code>#oidc=</code> on the link's address. The code works once and for a few minutes only, and
 * only on the link and in the space it was started on. The answer is a {@link ContactCredential},
 * exactly as <code>?action=verify-email</code> answers it, or the refusal of the sign-in
 * ("this link was shared with someone else", "the provider has not confirmed this address").
 * </p>
 */
public class OidcExchange extends de.haumacher.msgbuf.data.AbstractDataObject {

	/**
	 * Creates a {@link de.haumacher.imageServer.shared.model.OidcExchange} instance.
	 */
	public static de.haumacher.imageServer.shared.model.OidcExchange create() {
		return new de.haumacher.imageServer.shared.model.OidcExchange();
	}

	/** Identifier for the {@link de.haumacher.imageServer.shared.model.OidcExchange} type in JSON format. */
	public static final String OIDC_EXCHANGE__TYPE = "OidcExchange";

	/** @see #getCode() */
	private static final String CODE__PROP = "code";

	/** @see #getBinding() */
	private static final String BINDING__PROP = "binding";

	/** @see #getDeviceName() */
	private static final String DEVICE_NAME__PROP = "deviceName";

	private String _code = "";

	private String _binding = "";

	private String _deviceName = "";

	/**
	 * Creates a {@link OidcExchange} instance.
	 *
	 * @see de.haumacher.imageServer.shared.model.OidcExchange#create()
	 */
	protected OidcExchange() {
		super();
	}

	/**
	 * The code from <code>#oidc=</code>.
	 */
	public final String getCode() {
		return _code;
	}

	/**
	 * @see #getCode()
	 */
	public de.haumacher.imageServer.shared.model.OidcExchange setCode(String value) {
		internalSetCode(value);
		return this;
	}

	/** Internal setter for {@link #getCode()} without chain call utility. */
	protected final void internalSetCode(String value) {
		_code = value;
	}

	/**
	 * The {@link OidcStarted#getBinding()} of the start.
	 */
	public final String getBinding() {
		return _binding;
	}

	/**
	 * @see #getBinding()
	 */
	public de.haumacher.imageServer.shared.model.OidcExchange setBinding(String value) {
		internalSetBinding(value);
		return this;
	}

	/** Internal setter for {@link #getBinding()} without chain call utility. */
	protected final void internalSetBinding(String value) {
		_binding = value;
	}

	/**
	 * The name of this browser as a member's device, where the proof names a member and signs them
	 * in (issue #233, see {@link ContactCredential#getMember()}); empty for "Unnamed device".
	 */
	public final String getDeviceName() {
		return _deviceName;
	}

	/**
	 * @see #getDeviceName()
	 */
	public de.haumacher.imageServer.shared.model.OidcExchange setDeviceName(String value) {
		internalSetDeviceName(value);
		return this;
	}

	/** Internal setter for {@link #getDeviceName()} without chain call utility. */
	protected final void internalSetDeviceName(String value) {
		_deviceName = value;
	}

	/** Reads a new instance from the given reader. */
	public static de.haumacher.imageServer.shared.model.OidcExchange readOidcExchange(de.haumacher.msgbuf.json.JsonReader in) throws java.io.IOException {
		de.haumacher.imageServer.shared.model.OidcExchange result = new de.haumacher.imageServer.shared.model.OidcExchange();
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
		out.name(CODE__PROP);
		out.value(getCode());
		out.name(BINDING__PROP);
		out.value(getBinding());
		out.name(DEVICE_NAME__PROP);
		out.value(getDeviceName());
	}

	@Override
	protected void readField(de.haumacher.msgbuf.json.JsonReader in, String field) throws java.io.IOException {
		switch (field) {
			case CODE__PROP: setCode(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case BINDING__PROP: setBinding(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			case DEVICE_NAME__PROP: setDeviceName(de.haumacher.msgbuf.json.JsonUtil.nextStringOptional(in)); break;
			default: super.readField(in, field);
		}
	}

}
