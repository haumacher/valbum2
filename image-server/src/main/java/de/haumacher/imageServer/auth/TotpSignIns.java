/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.auth;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Signing a contact in with a code from their authenticator app, see issue #208.
 *
 * <p>
 * The algorithm is {@link Totp}; the secret and the last step used lie on the contact in the
 * {@link ContactStore}. This decides what a code may do:
 * </p>
 * <ul>
 * <li><b>Setting up</b>: a fresh secret is pending, and a pending secret signs nobody in; the first
 * right code of it {@link #confirm confirms} it.</li>
 * <li><b>Replay</b>: a code accepted once is refused for the rest of its window, and so is any code
 * of an earlier step.</li>
 * <li><b>Guessing</b>: {@link #FAILURES} wrong codes within {@link #LOCK} lock the authenticator of
 * that contact for {@link #LOCK}. Where a code names its contact by a typed address (an open link,
 * the group link of issue #211), the lock is kept per address whether or not it is a contact's, so
 * that the answers are the same for a stranger's address; {@link #WRONG_PER_CLIENT} wrong codes per
 * client within an hour stop trying address after address.</li>
 * </ul>
 * <p>
 * Every refusal of a code is the one sentence {@link #CODE_WRONG}, whatever was wrong. The locks
 * live in memory: a restart forgets them, as it forgets the limits of the mailed codes.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class TotpSignIns {

	private static final Logger LOG = Logger.getLogger(TotpSignIns.class.getName());

	/** The name of the method, as <code>IdentifyRequired.methods</code> carries it. */
	public static final String METHOD = "totp";

	/** Wrong codes that lock an authenticator. */
	public static final int FAILURES = 5;

	/** How long a lock lasts, and how long a wrong code counts towards one. */
	public static final Duration LOCK = Duration.ofMinutes(15);

	/** Wrong codes per client address within an hour, across every contact and address. */
	public static final int WRONG_PER_CLIENT = 30;

	private static final Duration CLIENT_WINDOW = Duration.ofHours(1);

	/** What a code is answered that signs nobody in, whatever the reason. */
	public static final String CODE_WRONG =
		"This code is not right. Enter the code your authenticator app shows now.";

	/** What a code is answered while the authenticator is locked. */
	public static final String LOCKED = "Too many wrong codes. Try again in 15 minutes.";

	/** What a confirmation is answered where no authenticator app is being set up. */
	public static final String NOTHING_TO_CONFIRM = "Start setting up the authenticator app again.";

	/** Why a code was not accepted. */
	public static final class Refused extends Exception {

		private final int _status;

		private final long _retryAfter;

		Refused(int status, String message, long retryAfter) {
			super(message);
			_status = status;
			_retryAfter = retryAfter;
		}

		/** The HTTP status to answer. */
		public int getStatus() {
			return _status;
		}

		/** Seconds to wait, <code>0</code> where waiting does not help. */
		public long getRetryAfter() {
			return _retryAfter;
		}
	}

	private final ContactStore _contacts;

	private final SecureRandom _random = new SecureRandom();

	private Clock _clock;

	private final Map<String, Deque<Instant>> _failures = new HashMap<>();

	private final Map<String, Instant> _lockedUntil = new HashMap<>();

	private final Map<String, Deque<Instant>> _wrongPerClient = new HashMap<>();

	/** Creates the {@link TotpSignIns} of the given register. */
	public TotpSignIns(ContactStore contacts, Clock clock) {
		_contacts = contacts;
		_clock = clock;
	}

	/** Replaces the clock, for a test that moves time. */
	public void setClock(Clock clock) {
		_clock = clock;
	}

	/** The clock codes are checked against. */
	public Clock getClock() {
		return _clock;
	}

	/**
	 * Starts setting up an authenticator app for the given contact: a fresh secret, pending until
	 * {@link #confirm}.
	 *
	 * @return The secret in Base32, <code>null</code> for a contact that is gone.
	 */
	public String setUp(String contactId) throws IOException {
		String secret = Totp.newSecret(_random);
		return _contacts.startTotp(contactId, secret, _clock.instant()) == null ? null : secret;
	}

	/**
	 * Confirms the authenticator app being set up for the given contact by one of its codes: from
	 * then on its codes sign the contact in.
	 *
	 * @throws Refused
	 *         <code>409</code> {@link #NOTHING_TO_CONFIRM} where nothing is being set up,
	 *         {@link #CODE_WRONG} and {@link #LOCKED} as for a sign-in.
	 */
	public void confirm(String contactId, String code, String client) throws Refused, IOException {
		ContactStore.Contact contact = _contacts.get(contactId);
		ContactStore.Authenticator pending = contact == null ? null : contact.getPendingAuthenticator();
		if (pending == null) {
			throw new Refused(409, NOTHING_TO_CONFIRM, 0);
		}
		String key = contactKey(contactId);
		long step;
		synchronized (this) {
			Instant now = _clock.instant();
			checkLocks(key, client, now);
			step = Totp.match(pending.getSecret(), code, Totp.step(now));
			if (step < 0) {
				fail(key, client, now);
			}
		}
		if (!_contacts.confirmTotp(contactId, pending.getSecret(), step, _clock.instant())) {
			throw new Refused(409, NOTHING_TO_CONFIRM, 0);
		}
		cleared(key);
		LOG.info("An authenticator app signs in " + contact + " from now on.");
	}

	/**
	 * Checks a code of the given contact's authenticator app and uses it up.
	 *
	 * @param contact
	 *        The contact the code is to sign in, <code>null</code> where the request names nobody this
	 *        space knows (a stranger's typed address): refused exactly as a wrong code.
	 * @param lockKey
	 *        What the lock is kept under where the request named the contact by a typed address,
	 *        see {@link #addressKey(String, String)}; <code>null</code> for the contact itself.
	 * @param client
	 *        The client address, for {@link #WRONG_PER_CLIENT}.
	 * @throws Refused
	 *         <code>400</code> {@link #CODE_WRONG} for a code that signs nobody in,
	 *         <code>429</code> {@link #LOCKED} while locked.
	 */
	public void verify(ContactStore.Contact contact, String lockKey, String code, String client)
			throws Refused, IOException {
		String key = lockKey != null ? lockKey : contactKey(contact.getId());
		ContactStore.Authenticator active = contact == null ? null : contact.getAuthenticator();
		long step;
		synchronized (this) {
			Instant now = _clock.instant();
			checkLocks(key, client, now);
			if (contact != null && lockKey != null) {
				// A contact named by an address is locked by their own guesses too.
				checkLock(contactKey(contact.getId()), now);
			}
			step = active == null ? -1 : Totp.match(active.getSecret(), code, Totp.step(now));
			if (step < 0) {
				if (contact != null && lockKey != null) {
					record(_failures, contactKey(contact.getId()), now);
				}
				fail(key, client, now);
			}
		}
		if (!_contacts.useTotpStep(contact.getId(), active.getSecret(), step)) {
			// Replayed: the code is right but was used already. Not counted as a guess.
			LOG.info("Refusing a code of " + contact + " that was used already.");
			throw new Refused(400, CODE_WRONG, 0);
		}
		cleared(key);
		if (lockKey != null) {
			cleared(contactKey(contact.getId()));
		}
	}

	/** Removes the authenticator app of the given contact. */
	public boolean remove(String contactId) throws IOException {
		cleared(contactKey(contactId));
		return _contacts.removeTotp(contactId);
	}

	/** The lock key of a contact named by a typed address on the given link. */
	public static String addressKey(String linkId, String address) {
		return "address:" + linkId + ":" + address;
	}

	private static String contactKey(String contactId) {
		return "contact:" + contactId;
	}

	private void checkLocks(String key, String client, Instant now) throws Refused {
		Deque<Instant> wrong = window(_wrongPerClient, client, now, CLIENT_WINDOW);
		if (wrong != null && wrong.size() >= WRONG_PER_CLIENT) {
			LOG.warning("Refusing a code beyond the limit of wrong codes (client " + client + ").");
			throw new Refused(429, LOCKED, seconds(Duration.between(now, wrong.peekFirst().plus(CLIENT_WINDOW))));
		}
		checkLock(key, now);
	}

	private void checkLock(String key, Instant now) throws Refused {
		_lockedUntil.values().removeIf(until -> !until.isAfter(now));
		Instant until = _lockedUntil.get(key);
		if (until != null) {
			throw new Refused(429, LOCKED, seconds(Duration.between(now, until)));
		}
	}

	private void fail(String key, String client, Instant now) throws Refused {
		record(_failures, key, now);
		record(_wrongPerClient, client == null ? "" : client, now);
		throw new Refused(400, CODE_WRONG, 0);
	}

	private synchronized void cleared(String key) {
		_failures.remove(key);
	}

	/**
	 * Records one wrong code of the given key; the {@link #FAILURES}th within {@link #LOCK} locks it
	 * for {@link #LOCK} from now.
	 */
	private void record(Map<String, Deque<Instant>> events, String key, Instant now) {
		Deque<Instant> deque = window(events, key, now, events == _failures ? LOCK : CLIENT_WINDOW);
		if (deque == null) {
			deque = new ArrayDeque<>();
			events.put(key, deque);
		}
		deque.addLast(now);
		if (events == _failures && deque.size() >= FAILURES) {
			LOG.warning("Locking an authenticator app for " + LOCK.toMinutes() + " minutes after " + FAILURES
				+ " wrong codes (" + key + ").");
			_lockedUntil.put(key, now.plus(LOCK));
			events.remove(key);
		}
	}

	/** The events of the given key within the window before now, <code>null</code> for none. */
	private Deque<Instant> window(Map<String, Deque<Instant>> events, String key, Instant now, Duration length) {
		prune(events, now, length);
		return events.get(key);
	}

	private static void prune(Map<String, Deque<Instant>> events, Instant now, Duration length) {
		Instant oldest = now.minus(length);
		for (Iterator<Deque<Instant>> it = events.values().iterator(); it.hasNext();) {
			Deque<Instant> deque = it.next();
			while (!deque.isEmpty() && !deque.peekFirst().isAfter(oldest)) {
				deque.removeFirst();
			}
			if (deque.isEmpty()) {
				it.remove();
			}
		}
	}

	private static long seconds(Duration wait) {
		return Math.max(1, (wait.toMillis() + 999) / 1000);
	}
}
