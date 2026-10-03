/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.mail;

import de.haumacher.imageServer.auth.UserStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Proves an e-mail address by a mailed six-digit code, see issue #199.
 *
 * <p>
 * The mechanism only: a code is mailed to an address for a <em>scope</em> (who asked, for what:
 * the servlet's business, see <code>AddressProof</code>) and is checked against the same scope and
 * address. A code lives {@link #LIFETIME} and allows {@link #ATTEMPTS} attempts, after which it is
 * void; it is stored as a salted hash, in memory only, so a restart voids every code. One server
 * holds one instance, so that its rate limits count across every space.
 * </p>
 *
 * <p>
 * <b>Rate limits</b>, in memory as well: at most {@link #PER_ADDRESS} codes per address,
 * {@link #PER_LINK} per share link and {@link #PER_CLIENT} per client address within
 * {@link #WINDOW}, and at most {@link #WRONG_PER_CLIENT} wrong codes per client address within the
 * same window. A refusal is the same sentence whichever limit was reached and whether or not the
 * space knows the address, and it says when to try again (<code>Retry-After</code>).
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class EmailProofs {

	private static final Logger LOG = Logger.getLogger(EmailProofs.class.getName());

	/** The name of the method in <code>IdentifyRequired.methods</code> and <code>ShareInfo.methods</code>. */
	public static final String METHOD = "mail-code";

	/** How long a code is valid. */
	public static final Duration LIFETIME = Duration.ofMinutes(10);

	/** How many attempts a code allows; after them it is void. */
	public static final int ATTEMPTS = 5;

	/** The window of the rate limits. */
	public static final Duration WINDOW = Duration.ofHours(1);

	/** Codes per address within {@link #WINDOW}. */
	public static final int PER_ADDRESS = 5;

	/** Codes per share link within {@link #WINDOW}. */
	public static final int PER_LINK = 30;

	/** Codes per client address within {@link #WINDOW}. */
	public static final int PER_CLIENT = 10;

	/** Wrong codes per client address within {@link #WINDOW}. */
	public static final int WRONG_PER_CLIENT = 30;

	/** The digits of a code. */
	public static final int DIGITS = 6;

	/** What every proof is answered where no mail account is configured. */
	public static final String NOT_CONFIGURED =
		"This server cannot send e-mail, so it cannot prove an address. Ask the person who shared the link.";

	/** What a request beyond a rate limit is answered, whichever limit it was. */
	public static final String RATE_LIMITED = "Too many codes were asked for. Wait a while and try again.";

	/** What a verification beyond the limit of wrong codes is answered. */
	public static final String TOO_MANY_WRONG = "Too many wrong codes. Wait a while and try again.";

	/** What a code is answered that ran out, was used up or was never sent. */
	public static final String CODE_VOID = "This code is no longer valid. Ask for a new one.";

	/** What a wrong code is answered. */
	public static final String CODE_WRONG = "This code is not right.";

	/** What a mail that could not be sent is answered. */
	public static final String MAIL_FAILED = "The code could not be sent. Try again later.";

	/** An instance that sends nothing: every proof is {@link #NOT_CONFIGURED}. */
	public static final EmailProofs NONE = new EmailProofs(null, Clock.systemUTC());

	/** Why a code was not sent or not accepted. */
	public static final class Refused extends Exception {

		private final int _status;

		private final long _retryAfter;

		Refused(int status, String message, long retryAfter) {
			super(message);
			_status = status;
			_retryAfter = retryAfter;
		}

		/** The HTTP status to answer with. */
		public int getStatus() {
			return _status;
		}

		/** The seconds to wait before trying again, <code>0</code> where waiting does not help. */
		public long getRetryAfter() {
			return _retryAfter;
		}
	}

	/** A code that was sent. */
	public static final class Sent {

		private final Instant _expires;

		Sent(Instant expires) {
			_expires = expires;
		}

		/** When it runs out. */
		public Instant getExpires() {
			return _expires;
		}
	}

	private static final class Pending {

		final String _salt;

		final String _hash;

		final Instant _expires;

		int _attempts;

		Pending(String salt, String hash, Instant expires) {
			_salt = salt;
			_hash = hash;
			_expires = expires;
		}
	}

	private final Mailer _mailer;

	private final Clock _clock;

	private final SecureRandom _random = new SecureRandom();

	private final Map<String, Pending> _pending = new HashMap<>();

	private final RateLimit _perAddress = new RateLimit(PER_ADDRESS, WINDOW);

	private final RateLimit _perLink = new RateLimit(PER_LINK, WINDOW);

	private final RateLimit _perClient = new RateLimit(PER_CLIENT, WINDOW);

	private final RateLimit _wrongPerClient = new RateLimit(WRONG_PER_CLIENT, WINDOW);

	/**
	 * Creates {@link EmailProofs}.
	 *
	 * @param mailer
	 *        What sends the mail, <code>null</code> where none is configured.
	 * @param clock
	 *        The time, which a test turns forward.
	 */
	public EmailProofs(Mailer mailer, Clock clock) {
		_mailer = mailer;
		_clock = clock;
	}

	/** Whether a code can be mailed at all. */
	public boolean isAvailable() {
		return _mailer != null;
	}

	/**
	 * Mails a fresh code to the given address, replacing one sent before for the same scope.
	 *
	 * @param scope
	 *        Who asks and for what; {@link #verify} must name the same.
	 * @param address
	 *        The normalised address the code goes to.
	 * @param link
	 *        The id of the share link the request came through, for its rate limit.
	 * @param client
	 *        The client's network address, for its rate limit.
	 * @param mail
	 *        The text, given the code.
	 */
	public Sent send(String scope, String address, String link, String client, MailText mail)
			throws Refused {
		if (_mailer == null) {
			throw new Refused(501, NOT_CONFIGURED, 0);
		}
		String code;
		Instant expires;
		String key = key(scope, address);
		synchronized (this) {
			Instant now = _clock.instant();
			prune(now);
			Duration wait = max(max(_perAddress.wait(address, now), _perLink.wait(link, now)),
				_perClient.wait(client, now));
			if (!wait.isZero()) {
				LOG.warning("Refusing a code beyond a rate limit (link " + link + ", client " + client + ").");
				throw new Refused(429, RATE_LIMITED, seconds(wait));
			}
			_perAddress.record(address, now);
			_perLink.record(link, now);
			_perClient.record(client, now);
			code = code();
			String salt = salt();
			expires = now.plus(LIFETIME);
			_pending.put(key, new Pending(salt, hash(salt, code), expires));
		}
		CodeMail text = mail.of(code, LIFETIME.toMinutes());
		try {
			_mailer.send(address, text.getSubject(), text.getText());
		} catch (IOException ex) {
			LOG.warning("Cannot mail a code: " + ex.getMessage());
			synchronized (this) {
				_pending.remove(key);
			}
			throw new Refused(503, MAIL_FAILED, 0);
		}
		return new Sent(expires);
	}

	/**
	 * Checks a code: <code>true</code> or a {@link Refused}.
	 *
	 * <p>
	 * A right code is used up by this check. A wrong one counts against the code's
	 * {@link #ATTEMPTS} and against the client's {@link #WRONG_PER_CLIENT}; the attempt after the
	 * last one finds the code void, right or wrong.
	 * </p>
	 */
	public boolean verify(String scope, String address, String code, String client) throws Refused {
		if (_mailer == null) {
			throw new Refused(501, NOT_CONFIGURED, 0);
		}
		String key = key(scope, address);
		synchronized (this) {
			Instant now = _clock.instant();
			prune(now);
			Duration wait = _wrongPerClient.wait(client, now);
			if (!wait.isZero()) {
				LOG.warning("Refusing a code beyond the limit of wrong codes (client " + client + ").");
				throw new Refused(429, TOO_MANY_WRONG, seconds(wait));
			}
			Pending pending = _pending.get(key);
			if (pending == null || !pending._expires.isAfter(now) || pending._attempts >= ATTEMPTS) {
				_pending.remove(key);
				throw new Refused(400, CODE_VOID, 0);
			}
			pending._attempts++;
			String given = code == null ? "" : code.replaceAll("[\\s-]", "");
			if (!MessageDigest.isEqual(hash(pending._salt, given).getBytes(StandardCharsets.US_ASCII),
				pending._hash.getBytes(StandardCharsets.US_ASCII))) {
				_wrongPerClient.record(client, now);
				throw new Refused(400, CODE_WRONG, 0);
			}
			_pending.remove(key);
			return true;
		}
	}

	/** The text of a code mail, given the code and its lifetime in minutes. */
	public interface MailText {
		/** The mail carrying the given code. */
		CodeMail of(String code, long minutes);
	}

	private void prune(Instant now) {
		for (Iterator<Pending> it = _pending.values().iterator(); it.hasNext();) {
			if (!it.next()._expires.isAfter(now)) {
				it.remove();
			}
		}
		_perAddress.prune(now);
		_perLink.prune(now);
		_perClient.prune(now);
		_wrongPerClient.prune(now);
	}

	private static String key(String scope, String address) {
		return scope + "\n" + address;
	}

	private String code() {
		StringBuilder result = new StringBuilder(DIGITS);
		for (int n = 0; n < DIGITS; n++) {
			result.append((char) ('0' + _random.nextInt(10)));
		}
		return result.toString();
	}

	private String salt() {
		byte[] bytes = new byte[16];
		_random.nextBytes(bytes);
		return java.util.HexFormat.of().formatHex(bytes);
	}

	private static String hash(String salt, String code) {
		return UserStore.hash(salt + ":" + code);
	}

	private static Duration max(Duration a, Duration b) {
		return a.compareTo(b) >= 0 ? a : b;
	}

	private static long seconds(Duration wait) {
		return Math.max(1, (wait.toMillis() + 999) / 1000);
	}
}
