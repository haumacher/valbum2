/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.mail.EmailProofs;
import de.haumacher.imageServer.mail.MailSettings;
import de.haumacher.imageServer.mail.SmtpMailer;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.util.Map;

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

	/** Nothing configured: no public address, no mail. */
	public static final ServerEnvironment NONE = new ServerEnvironment(null, null);

	/** Thrown for a setting the server cannot run with; the message names the variable. */
	public static final class Invalid extends Exception {

		/** Creates an {@link Invalid}. */
		public Invalid(String message) {
			super(message);
		}
	}

	private final String _publicUrl;

	private final MailSettings _mail;

	private ServerEnvironment(String publicUrl, MailSettings mail) {
		_publicUrl = publicUrl;
		_mail = mail;
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
		return new ServerEnvironment(publicUrl(value(env, PUBLIC_URL)), mail(env));
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
