/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.mail.EmailProofs;
import de.haumacher.imageServer.mail.MailSettings;
import de.haumacher.imageServer.mail.SmtpMailer;
import de.haumacher.imageServer.oidc.OidcLogins;
import de.haumacher.imageServer.oidc.OidcProvider;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The server-wide settings that come from the environment, see issue #199.
 *
 * <p>
 * The Debian package's <code>/etc/default/valbum</code> (handed to the service by systemd's
 * <code>EnvironmentFile=</code> and exported by <code>valbum-server</code>) and the
 * <code>environment:</code> of a container. Never the command line &mdash; a password there is
 * visible to every user of the machine in the process list, the reason <code>--pairing-secret</code>
 * was retired (<code>Main.retiredSecret</code>) &mdash; and never the library, which is the
 * photos' and not the machine's.
 * </p>
 *
 * <ul>
 * <li><code>VALBUM_PUBLIC_URL</code>: the address the album is reached at from outside, its context
 * root (<code>https://photos.example.org/valbum</code>); where an absolute address for an outside
 * party is needed it is spelled from this instead of from the request, see {@link #getPublicUrl()}.</li>
 * <li><code>VALBUM_SMTP_HOST</code>, <code>_PORT</code>, <code>_USER</code>, <code>_PASSWORD</code>,
 * <code>_FROM</code>, <code>_TLS</code> (<code>starttls</code>, the default, or <code>tls</code>;
 * <code>none</code> for a relay on this machine only): the mail account the codes of issue #199 are
 * sent through. Without <code>VALBUM_SMTP_HOST</code> no code is offered anywhere.</li>
 * <li><code>VALBUM_OIDC_&lt;ID&gt;_CLIENT_ID</code>, <code>_CLIENT_SECRET</code>,
 * <code>_DISCOVERY_URL</code>, <code>_LABEL</code>: a provider of OpenID Connect a visitor of a
 * personal link proves their address through (issue #200), <code>GOOGLE</code> the one whose
 * discovery address and label are known. A further provider is further variables. Offered only
 * with <code>VALBUM_PUBLIC_URL</code>, which the redirect address is spelled from.</li>
 * </ul>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class ServerEnvironment {

	/** The public address of the album. */
	public static final String PUBLIC_URL = "VALBUM_PUBLIC_URL";

	/** The mail server; without it no mail is sent. */
	public static final String SMTP_HOST = "VALBUM_SMTP_HOST";

	/** Its port; 587 for STARTTLS and 465 for TLS where it is empty. */
	public static final String SMTP_PORT = "VALBUM_SMTP_PORT";

	/** The account to sign in with. */
	public static final String SMTP_USER = "VALBUM_SMTP_USER";

	/** Its password. */
	public static final String SMTP_PASSWORD = "VALBUM_SMTP_PASSWORD";

	/** The sender; the account where it is an address and this is empty. */
	public static final String SMTP_FROM = "VALBUM_SMTP_FROM";

	/** <code>starttls</code> (the default), <code>tls</code>, or <code>none</code> for a local relay. */
	public static final String SMTP_TLS = "VALBUM_SMTP_TLS";

	/** The prefix of the variables of a provider of OpenID Connect, see issue #200. */
	public static final String OIDC_PREFIX = "VALBUM_OIDC_";

	/** The suffix of a provider's client id: <code>VALBUM_OIDC_GOOGLE_CLIENT_ID</code>. */
	public static final String OIDC_CLIENT_ID = "_CLIENT_ID";

	/** The suffix of a provider's client secret. */
	public static final String OIDC_CLIENT_SECRET = "_CLIENT_SECRET";

	/** The suffix of a provider's discovery address; Google's is known. */
	public static final String OIDC_DISCOVERY_URL = "_DISCOVERY_URL";

	/** The suffix of what a button calls the provider; "Google" for Google, else the id. */
	public static final String OIDC_LABEL = "_LABEL";

	private static final Pattern OIDC_VARIABLE = Pattern.compile(
		"VALBUM_OIDC_([A-Z0-9]+(?:_[A-Z0-9]+)*?)(_CLIENT_ID|_CLIENT_SECRET|_DISCOVERY_URL|_LABEL)");

	/** Nothing configured: no public address, no mail, no provider. */
	public static final ServerEnvironment NONE = new ServerEnvironment(null, null, List.of());

	/** Thrown for a setting the server cannot run with; the message names the variable. */
	public static final class Invalid extends Exception {

		/** Creates an {@link Invalid}. */
		public Invalid(String message) {
			super(message);
		}
	}

	private final String _publicUrl;

	private final MailSettings _mail;

	private final List<OidcProvider> _providers;

	private ServerEnvironment(String publicUrl, MailSettings mail, List<OidcProvider> providers) {
		_publicUrl = publicUrl;
		_mail = mail;
		_providers = Collections.unmodifiableList(providers);
	}

	/**
	 * The providers of OpenID Connect configured, see issue #200; offered only where
	 * {@link #getPublicUrl()} is set, see {@link #oidcLogins()}.
	 */
	public List<OidcProvider> getOidcProviders() {
		return _providers;
	}

	/**
	 * The sign-in through OpenID Connect this environment allows: {@link OidcLogins#NONE} without a
	 * provider, and without a public address, which the redirect address is spelled from.
	 */
	public OidcLogins oidcLogins() {
		return _publicUrl == null || _providers.isEmpty() ? OidcLogins.NONE
			: new OidcLogins(_publicUrl, _providers, Clock.systemUTC());
	}

	/**
	 * The public address of the album's context root without a trailing slash, <code>null</code>
	 * where none is configured (then {@link SharePreview#origin} spells it from each request).
	 */
	public String getPublicUrl() {
		return _publicUrl;
	}

	/** The mail account, <code>null</code> where none is configured. */
	public MailSettings getMail() {
		return _mail;
	}

	/** The proof by mailed code this environment allows: {@link EmailProofs#NONE} without a mail account. */
	public EmailProofs emailProofs() {
		return _mail == null ? EmailProofs.NONE : new EmailProofs(new SmtpMailer(_mail), Clock.systemUTC());
	}

	/**
	 * Reads the settings from the given environment.
	 *
	 * @throws Invalid
	 *         For a setting that is there but unusable: refused at start-up, never ignored, because
	 *         an administrator who configured mail believes it works.
	 */
	public static ServerEnvironment read(Map<String, String> env) throws Invalid {
		return new ServerEnvironment(publicUrl(value(env, PUBLIC_URL)), mail(env), providers(env));
	}

	private static String value(Map<String, String> env, String name) {
		String value = env.get(name);
		return value == null ? "" : value.trim();
	}

	private static String publicUrl(String value) throws Invalid {
		if (value.isEmpty()) {
			return null;
		}
		URI uri;
		try {
			uri = new URI(value);
		} catch (URISyntaxException ex) {
			throw new Invalid(PUBLIC_URL + " is no address: '" + value + "'.");
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
		if (!(scheme.equals("https") || scheme.equals("http")) || uri.getHost() == null || uri.getQuery() != null
			|| uri.getFragment() != null || uri.getUserInfo() != null) {
			throw new Invalid(PUBLIC_URL + " must be an http or https address without a query, for example "
				+ "'https://photos.example.org/valbum', not '" + value + "'.");
		}
		String result = value;
		while (result.endsWith("/")) {
			result = result.substring(0, result.length() - 1);
		}
		return result;
	}

	/**
	 * The providers of OpenID Connect the given environment names, by id; a variable that is set
	 * but empty is no setting (the commented-out lines of <code>/etc/default/valbum</code> filled in
	 * half). A provider with a client id needs its secret, and one that is not Google its discovery
	 * address, else the start is refused naming the variable.
	 */
	private static List<OidcProvider> providers(Map<String, String> env) throws Invalid {
		Map<String, Map<String, String>> byId = new TreeMap<>();
		for (Map.Entry<String, String> entry : env.entrySet()) {
			if (!entry.getKey().startsWith(OIDC_PREFIX)) {
				continue;
			}
			Matcher matcher = OIDC_VARIABLE.matcher(entry.getKey());
			if (!matcher.matches()) {
				throw new Invalid(entry.getKey() + " is no setting of a sign-in provider: it is "
					+ OIDC_PREFIX + "<ID>" + OIDC_CLIENT_ID + ", " + OIDC_CLIENT_SECRET + ", " + OIDC_DISCOVERY_URL
					+ " or " + OIDC_LABEL + ", for example VALBUM_OIDC_GOOGLE_CLIENT_ID.");
			}
			String text = OIDC_CLIENT_SECRET.equals(matcher.group(2)) ? entry.getValue()
				: entry.getValue() == null ? "" : entry.getValue().trim();
			if (text == null || text.isEmpty()) {
				continue;
			}
			byId.computeIfAbsent(matcher.group(1), id -> new TreeMap<>()).put(matcher.group(2), text);
		}
		List<OidcProvider> result = new ArrayList<>();
		for (Map.Entry<String, Map<String, String>> entry : byId.entrySet()) {
			String name = OIDC_PREFIX + entry.getKey();
			Map<String, String> values = entry.getValue();
			String clientId = values.get(OIDC_CLIENT_ID);
			if (clientId == null) {
				throw new Invalid(name + OIDC_CLIENT_ID + " is needed beside " + name
					+ values.keySet().iterator().next() + ": the id of the OAuth client created at the provider.");
			}
			String secret = values.get(OIDC_CLIENT_SECRET);
			if (secret == null) {
				throw new Invalid(name + OIDC_CLIENT_SECRET + " is needed beside " + name + OIDC_CLIENT_ID
					+ ": the secret of that OAuth client.");
			}
			String id = entry.getKey().toLowerCase(Locale.ROOT);
			boolean google = OidcProvider.GOOGLE.equals(id);
			String discovery = values.get(OIDC_DISCOVERY_URL);
			if (discovery == null) {
				if (!google) {
					throw new Invalid(name + OIDC_DISCOVERY_URL + " is needed: the provider's address ending in "
						+ "/.well-known/openid-configuration.");
				}
				discovery = OidcProvider.GOOGLE_DISCOVERY;
			}
			checkDiscovery(name + OIDC_DISCOVERY_URL, discovery);
			String label = values.get(OIDC_LABEL);
			if (label == null) {
				label = google ? "Google" : id.substring(0, 1).toUpperCase(Locale.ROOT) + id.substring(1);
			} else if (!label.equals(label.replaceAll("\\p{Cc}", ""))) {
				throw new Invalid(name + OIDC_LABEL + " must be plain text on one line.");
			}
			result.add(new OidcProvider(id, label, clientId, secret, discovery));
		}
		return result;
	}

	/** A discovery address must be https; plain http only on this machine (a provider under test). */
	private static void checkDiscovery(String variable, String value) throws Invalid {
		URI uri;
		try {
			uri = new URI(value);
		} catch (URISyntaxException ex) {
			throw new Invalid(variable + " is no address: '" + value + "'.");
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		String host = uri.getHost() == null ? "" : uri.getHost();
		boolean local = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
		if (!(scheme.equals("https") || (scheme.equals("http") && local)) || host.isEmpty()) {
			throw new Invalid(variable + " must be an https address, for example "
				+ "'https://login.example.org/.well-known/openid-configuration', not '" + value + "'.");
		}
	}

	private static MailSettings mail(Map<String, String> env) throws Invalid {
		String host = value(env, SMTP_HOST);
		if (host.isEmpty()) {
			return null;
		}
		String tls = value(env, SMTP_TLS);
		MailSettings.Security security = tls.isEmpty() ? MailSettings.Security.STARTTLS : MailSettings.Security.parse(tls);
		if (security == null) {
			throw new Invalid(SMTP_TLS + " must be 'starttls' or 'tls' ('none' only for a mail server on this "
				+ "machine), not '" + tls + "'.");
		}
		String portText = value(env, SMTP_PORT);
		int port;
		if (portText.isEmpty()) {
			port = security.defaultPort();
		} else {
			try {
				port = Integer.parseInt(portText);
			} catch (NumberFormatException ex) {
				port = -1;
			}
			if (port <= 0 || port > 65535) {
				throw new Invalid(SMTP_PORT + " must be a port number, not '" + portText + "'.");
			}
		}
		String user = value(env, SMTP_USER);
		// A password may begin or end with a blank; it is taken as it stands.
		String password = env.get(SMTP_PASSWORD) == null ? "" : env.get(SMTP_PASSWORD);
		String from = value(env, SMTP_FROM);
		if (from.isEmpty()) {
			if (!user.contains("@")) {
				throw new Invalid(SMTP_FROM + " is needed: the address the codes are sent from.");
			}
			from = user;
		}
		try {
			new jakarta.mail.internet.InternetAddress(from, true);
		} catch (jakarta.mail.internet.AddressException ex) {
			throw new Invalid(SMTP_FROM + " is no e-mail address: '" + from + "'.");
		}
		MailSettings result = new MailSettings(host, port, user, password, from, security);
		String problem = result.check();
		if (problem != null) {
			throw new Invalid(problem);
		}
		return result;
	}
}
