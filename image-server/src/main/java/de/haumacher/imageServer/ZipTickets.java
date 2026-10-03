/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The download tickets of one space, see issue #209.
 *
 * <p>
 * On the web the browser has to fetch an archive itself — the app's own transport collects the
 * whole answer before it can hand it on, which an album of videos does not survive — and a
 * browser's download carries no <code>Authorization</code> header. So the app asks, with its
 * bearer, <code>POST &lt;album&gt;/?action=zip-ticket</code> with the names, the server checks them
 * exactly as <code>?action=zip</code> would and remembers them here under a random id, and the
 * browser fetches <code>GET &lt;album&gt;/?action=zip&amp;ticket=&lt;id&gt;&amp;media=&lt;signature&gt;</code>.
 * </p>
 *
 * <p>
 * A ticket is held in memory only (a restart forgets it, and the app simply asks again), lives
 * as long as a media signature ({@link de.haumacher.imageServer.auth.MediaSignatures#LIFETIME}),
 * opens its archive <em>once</em> and only to the subject it was made for — the device, the share
 * link or the session of a personal link whose signature it carries, or {@link #ANONYMOUS} for a
 * caller without a token, who could have asked the plain address as well.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
final class ZipTickets {

	/** The subject of a ticket made for a caller without a token. */
	static final String ANONYMOUS = "anonymous";

	/** The most tickets held at once; the oldest goes first beyond that. */
	static final int MAX_TICKETS = 1000;

	/** One remembered download. */
	static final class Ticket {

		private final String _path;

		private final List<String> _names;

		private final int _viewAs;

		private final String _subject;

		private final long _expires;

		Ticket(String path, Collection<String> names, int viewAs, String subject, long expires) {
			_path = path;
			_names = Collections.unmodifiableList(new ArrayList<>(names));
			_viewAs = viewAs;
			_subject = subject;
			_expires = expires;
		}

		/** The path of the folder, as the servlet saw it (its path info). */
		String getPath() {
			return _path;
		}

		/** The names asked for, each once, in the order asked. */
		List<String> getNames() {
			return _names;
		}

		/** The "view as" of the request that made the ticket. */
		int getViewAs() {
			return _viewAs;
		}

		/** Who may use it. */
		String getSubject() {
			return _subject;
		}

		/** Seconds since the epoch. */
		long getExpires() {
			return _expires;
		}
	}

	private final Map<String, Ticket> _tickets = new ConcurrentHashMap<>();

	private final SecureRandom _random = new SecureRandom();

	private final LongSupplier _now;

	/** A store on the system clock. */
	ZipTickets() {
		this(() -> System.currentTimeMillis() / 1000);
	}

	/**
	 * A store on the given clock.
	 *
	 * @param now
	 *        Seconds since the epoch.
	 */
	ZipTickets(LongSupplier now) {
		_now = now;
	}

	/** A new random ticket id: sixteen bytes, base64url without padding. */
	String newId() {
		byte[] bytes = new byte[16];
		_random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/** Remembers the given ticket under the given id. */
	void put(String id, Ticket ticket) {
		prune();
		_tickets.put(id, ticket);
	}

	/**
	 * Takes the ticket of the given id out of the store, where it is still good and was made for
	 * the given folder and subject; <code>null</code> otherwise.
	 *
	 * <p>
	 * A ticket presented by another subject or for another folder stays where it is: whoever made
	 * it can still use it.
	 * </p>
	 */
	Ticket take(String id, String path, String subject) {
		if (id == null) {
			return null;
		}
		Ticket[] taken = new Ticket[1];
		_tickets.computeIfPresent(id, (key, ticket) -> {
			if (!ticket.getSubject().equals(subject) || !ticket.getPath().equals(path)) {
				return ticket;
			}
			taken[0] = ticket;
			return null;
		});
		Ticket result = taken[0];
		if (result == null || _now.getAsLong() >= result.getExpires()) {
			return null;
		}
		return result;
	}

	/** How many tickets are held. */
	int size() {
		return _tickets.size();
	}

	private void prune() {
		long now = _now.getAsLong();
		_tickets.values().removeIf(ticket -> now >= ticket.getExpires());
		if (_tickets.size() >= MAX_TICKETS) {
			// Nobody asks for a thousand downloads in ten minutes; whoever does loses the oldest.
			Iterator<Map.Entry<String, Ticket>> oldest = _tickets.entrySet().stream()
				.sorted((a, b) -> Long.compare(a.getValue().getExpires(), b.getValue().getExpires()))
				.iterator();
			while (_tickets.size() >= MAX_TICKETS && oldest.hasNext()) {
				_tickets.remove(oldest.next().getKey());
			}
		}
	}
}
