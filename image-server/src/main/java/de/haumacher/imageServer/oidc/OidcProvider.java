/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.oidc;

/**
 * A provider of OpenID Connect the server is configured with, see issue #200.
 *
 * <p>
 * Read from the environment by <code>ServerEnvironment</code>:
 * <code>VALBUM_OIDC_&lt;ID&gt;_CLIENT_ID</code>, <code>_CLIENT_SECRET</code>,
 * <code>_DISCOVERY_URL</code> (Google's is known) and <code>_LABEL</code>. A further provider is
 * further variables and no code.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class OidcProvider {

	/** The id of Google, the first provider. */
	public static final String GOOGLE = "google";

	/** Google's discovery document. */
	public static final String GOOGLE_DISCOVERY = "https://accounts.google.com/.well-known/openid-configuration";

	private final String _id;

	private final String _label;

	private final String _clientId;

	private final String _secret;

	private final String _discoveryUrl;

	/**
	 * Creates an {@link OidcProvider}.
	 *
	 * @param id
	 *        The provider's id, lower case: <code>google</code>.
	 * @param label
	 *        What a button calls it: <code>Google</code>.
	 * @param clientId
	 *        The id of this server's OAuth client at the provider.
	 * @param secret
	 *        Its secret.
	 * @param discoveryUrl
	 *        The provider's <code>.well-known/openid-configuration</code>.
	 */
	public OidcProvider(String id, String label, String clientId, String secret, String discoveryUrl) {
		_id = id;
		_label = label;
		_clientId = clientId;
		_secret = secret;
		_discoveryUrl = discoveryUrl;
	}

	/** The provider's id, as <code>oidc:&lt;id&gt;</code> names the method. */
	public String getId() {
		return _id;
	}

	/** What a button calls the provider, plain text. */
	public String getLabel() {
		return _label;
	}

	/** The id of this server's OAuth client. */
	public String getClientId() {
		return _clientId;
	}

	/** The client's secret; never printed. */
	String getSecret() {
		return _secret;
	}

	/** The provider's discovery document. */
	public String getDiscoveryUrl() {
		return _discoveryUrl;
	}

	/** The name of the method that signs in through this provider. */
	public String method() {
		return OidcLogins.METHOD_PREFIX + _id;
	}

	/** The provider as a start-up line names it: never the secret. */
	@Override
	public String toString() {
		return _label + " (" + _id + ")";
	}
}
