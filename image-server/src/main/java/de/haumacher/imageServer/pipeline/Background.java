/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.pipeline;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * How the background work of every space gives way to the people being served, see issue #236.
 *
 * <h2>Requests first</h2>
 *
 * <p>
 * The requests that make something a person is waiting for &mdash; a thumbnail, a display
 * rendition, a face crop, a video rendition &mdash; say when they begin and end
 * ({@link #requestStarted()}, {@link #requestEnded()}). The background makes nothing new while one
 * of them runs, nor within {@link #quietMillis() a short quiet period} after the last one ended:
 * an album being opened asks for its tiles in a burst with small gaps between them, and a background
 * preview started in such a gap would take a permit and a core from the very next tile. The
 * background only ever waits <em>before</em> a unit of work (one preview, one photograph's faces,
 * one rendition); a unit that has begun runs to its end, which is a fraction of a second for a
 * photograph.
 * </p>
 *
 * <p>
 * Rejected: a priority queue on the preview permits. Requests and the background would then still
 * share the CPU while a page loads, and the permits know nothing of face detection or of a
 * transcode. Rejected too: counting <em>every</em> request. The admin screen that shows the
 * progress of this work asks every few seconds, and so does an album whose faces are pending;
 * counting those would stop the background for as long as somebody watches it.
 * </p>
 *
 * <h2>One decode at a time</h2>
 *
 * <p>
 * Every unit of background work that decodes a picture runs under one lock for the whole process
 * ({@link #decode(Callable)}), whichever space it belongs to: the background never holds two large
 * rasters at once, see <code>faq/decode-memory.md</code>. A transcode is not a decode in this sense
 * (FFmpeg runs as a child process, outside the heap) and waits for requests only.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class Background {

	/** How long after the last request the background waits, see {@link Background}. */
	public static final long QUIET_MILLIS = 2000;

	private static final AtomicInteger ACTIVE = new AtomicInteger();

	private static final AtomicLong LAST_END = new AtomicLong();

	private static final AtomicLong YIELDS = new AtomicLong();

	private static final ReentrantLock DECODE = new ReentrantLock();

	private static volatile long _quietMillis = QUIET_MILLIS;

	private Background() {
		// Static use only.
	}

	/** A request that makes something for a person begins; always paired with {@link #requestEnded()}. */
	public static void requestStarted() {
		ACTIVE.incrementAndGet();
	}

	/** The request of {@link #requestStarted()} ended. */
	public static void requestEnded() {
		LAST_END.set(System.currentTimeMillis());
		ACTIVE.decrementAndGet();
	}

	/** How long after the last request the background waits. */
	public static long quietMillis() {
		return _quietMillis;
	}

	/** Sets {@link #quietMillis()}, for the tests. */
	public static void setQuietMillis(long millis) {
		_quietMillis = millis;
	}

	/** Whether a request is being served, or was a moment ago. */
	public static boolean busy() {
		return ACTIVE.get() > 0 || System.currentTimeMillis() - LAST_END.get() < _quietMillis;
	}

	/** How often the background waited for requests, for the tests and the status. */
	public static long yields() {
		return YIELDS.get();
	}

	/**
	 * Waits until no request has been served for the quiet period.
	 *
	 * @throws InterruptedException
	 *         When the server stops meanwhile.
	 */
	public static void awaitQuiet() throws InterruptedException {
		if (!busy()) {
			return;
		}
		YIELDS.incrementAndGet();
		while (busy()) {
			Thread.sleep(50);
		}
	}

	/**
	 * Runs a unit of background work that decodes pictures: after {@link #awaitQuiet()}, and never
	 * at the same time as another such unit of any space.
	 */
	public static <T> T decode(Callable<T> work) throws Exception {
		while (true) {
			awaitQuiet();
			DECODE.lockInterruptibly();
			if (!busy()) {
				break;
			}
			// A request came while the lock was being taken: it goes first.
			DECODE.unlock();
		}
		try {
			return work.call();
		} finally {
			DECODE.unlock();
		}
	}

	/**
	 * Runs a decode of a background thread that works for a person who asked (the faces of an
	 * album just opened): at once, but never at the same time as a unit of {@link #decode(Callable)}.
	 */
	public static <T> T exclusive(Callable<T> work) throws Exception {
		DECODE.lockInterruptibly();
		try {
			return work.call();
		} finally {
			DECODE.unlock();
		}
	}
}
