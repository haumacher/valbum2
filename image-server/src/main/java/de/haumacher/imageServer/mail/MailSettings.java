/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.mail;

import java.util.Locale;
import java.util.Properties;

/**
 * The mail account the server sends its codes through, see issue #199.
 *
 * <p>
 * Read from the environment by {@link de.haumacher.imageServer.ServerEnvironment}: the Debian
 * package's <code>/etc/default/valbum</code> or the <code>environment:</code> of a container, never
 * the command line and never the library. The password is never printed, see {@link #toString()}.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class MailSettings {

	/** How the connection to the mail server is secured. */
	public enum Security {
		/** SMTP submission (port 587 by default), upgraded with STARTTLS, which is required. */
		STARTTLS("starttls", 587),

		/** SMTP over TLS from the first byte (port 465 by default). */
		TLS("tls", 465),

		/**
		 * No encryption: only for a mail server on this very machine (a local relay), which is why
		 * {@link MailSettings#check()} refuses it for any other host.
		 */
		NONE("none", 25);

		private final String _name;

		private final int _port;

		Security(String name, int port) {
			_name = name;
			_port = port;
		}

		/** The value of <code>VALBUM_SMTP_TLS</code> naming this. */
		public String externalName() {
			return _name;
		}

		/** The port used where none is configured. */
		public int defaultPort() {
			return _port;
		}

		/** The security of the given name, <code>null</code> for an unknown one. */
		public static Security parse(String name) {
			for (Security security : values()) {
				if (security._name.equals(name.trim().toLowerCase(Locale.ROOT))) {
					return security;
				}
			}
			return null;
		}
	}

	/** How long connecting, reading and writing may take before a send is given up, in milliseconds. */
	public static final int TIMEOUT_MILLIS = 15_000;

	private final String _host;

	private final int _port;

	private final String _user;

	private final String _password;

	private final String _from;

	private final Security _security;

	/** Creates {@link MailSettings}. */
	public MailSettings(String host, int port, String user, String password, String from, Security security) {
		_host = host;
		_port = port;
		_user = user == null ? "" : user;
		_password = password == null ? "" : password;
		_from = from;
		_security = security;
	}

	/** The mail server. */
	public String getHost() {
		return _host;
	}

	/** Its port. */
	public int getPort() {
		return _port;
	}

	/** The account to sign in with, empty for none. */
	public String getUser() {
		return _user;
	}

	/** The account's password, empty for none. */
	public String getPassword() {
		return _password;
	}

	/** The sender of the mails. */
	public String getFrom() {
		return _from;
	}

	/** How the connection is secured. */
	public Security getSecurity() {
		return _security;
	}

	/** The Jakarta Mail protocol: <code>smtps</code> for {@link Security#TLS}, <code>smtp</code> otherwise. */
	public String protocol() {
		return _security == Security.TLS ? "smtps" : "smtp";
	}

	/**
	 * The properties of the Jakarta Mail session.
	 *
	 * <p>
	 * STARTTLS is <em>required</em>, not merely tried, so that a server (or somebody between) who
	 * does not offer it never gets the password in the clear; the server's certificate must name
	 * the host. Every step of a send is bounded by {@link #TIMEOUT_MILLIS}.
	 * </p>
	 */
	public Properties sessionProperties() {
		String prefix = "mail." + protocol() + ".";
		Properties result = new Properties();
		result.setProperty("mail.transport.protocol", protocol());
		result.setProperty(prefix + "host", _host);
		result.setProperty(prefix + "port", Integer.toString(_port));
		result.setProperty(prefix + "auth", Boolean.toString(!_user.isEmpty()));
		result.setProperty(prefix + "connectiontimeout", Integer.toString(TIMEOUT_MILLIS));
		result.setProperty(prefix + "timeout", Integer.toString(TIMEOUT_MILLIS));
		result.setProperty(prefix + "writetimeout", Integer.toString(TIMEOUT_MILLIS));
		switch (_security) {
			case STARTTLS:
				result.setProperty(prefix + "starttls.enable", "true");
				result.setProperty(prefix + "starttls.required", "true");
				result.setProperty(prefix + "ssl.checkserveridentity", "true");
				break;
			case TLS:
				result.setProperty(prefix + "ssl.enable", "true");
				result.setProperty(prefix + "ssl.checkserveridentity", "true");
				break;
			case NONE:
				break;
		}
		return result;
	}

	/**
	 * Why these settings cannot be used, <code>null</code> if they can.
	 *
	 * <p>
	 * An unencrypted connection is refused for every host but this machine: the password and the
	 * codes would cross the network in the clear.
	 * </p>
	 */
	public String check() {
		if (_security == Security.NONE && !isLocal(_host)) {
			return "VALBUM_SMTP_TLS=none is only allowed for a mail server on this machine "
				+ "(localhost), not for '" + _host + "'; use starttls or tls.";
		}
		return null;
	}

	private static boolean isLocal(String host) {
		String name = host.trim().toLowerCase(Locale.ROOT);
		return name.equals("localhost") || name.equals("127.0.0.1") || name.equals("::1") || name.equals("[::1]");
	}

	/** The settings as they may be printed: everything but the password. */
	@Override
	public String toString() {
		return _host + ":" + _port + " (" + _security.externalName() + ")"
			+ (_user.isEmpty() ? "" : ", user '" + _user + "'") + ", from '" + _from + "'";
	}
}
