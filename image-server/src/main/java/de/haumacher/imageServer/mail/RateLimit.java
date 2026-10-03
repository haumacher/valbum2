/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.mail;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * At most so many events per key within a sliding window, held in memory, see issue #199.
 *
 * <p>
 * A restart forgets everything, which is acceptable for what it guards: the mail a server sends
 * and the guesses at a code. Not thread-safe; {@link EmailProofs} holds its lock around it.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
final class RateLimit {

	private final int _max;

	private final Duration _window;

	private final Map<String, Deque<Instant>> _events = new HashMap<>();

	/** Creates a {@link RateLimit} of the given number of events per window. */
	RateLimit(int max, Duration window) {
		_max = max;
		_window = window;
	}

	/**
	 * How long the given key must wait before one more event is allowed, {@link Duration#ZERO} if
	 * it may go ahead now.
	 */
	Duration wait(String key, Instant now) {
		Deque<Instant> events = _events.get(key);
		if (events == null) {
			return Duration.ZERO;
		}
		drop(events, now);
		if (events.size() < _max) {
			return Duration.ZERO;
		}
		return Duration.between(now, events.peekFirst().plus(_window));
	}

	/** Records one event of the given key. */
	void record(String key, Instant now) {
		_events.computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(now);
	}

	/** Forgets the keys whose events have all left the window, so that the map does not grow. */
	void prune(Instant now) {
		for (Iterator<Deque<Instant>> it = _events.values().iterator(); it.hasNext();) {
			Deque<Instant> events = it.next();
			drop(events, now);
			if (events.isEmpty()) {
				it.remove();
			}
		}
	}

	private void drop(Deque<Instant> events, Instant now) {
		Instant oldest = now.minus(_window);
		while (!events.isEmpty() && !events.peekFirst().isAfter(oldest)) {
			events.removeFirst();
		}
	}
}
