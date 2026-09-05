/*
 * Copyright (c) 2026 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.util.servlet;

/**
 * Parsed representation of an HTTP <code>Range</code> request header for a resource of a known
 * size.
 *
 * <p>
 * The contract is deliberately narrow: a request either asks for the whole entity (no range, an
 * unsupported or syntactically invalid range, or a multi-range request), or for exactly one
 * satisfiable byte slice, or for a slice that cannot be satisfied at all.
 * </p>
 *
 * <ul>
 * <li>{@link #isWhole()} &rarr; answer <code>200 OK</code> with the complete entity.</li>
 * <li>{@link #isPartial()} &rarr; answer <code>206 Partial Content</code> with
 * <code>Content-Range: bytes {@link #getStart() start}-{@link #getEnd() end}/total</code> and a
 * body of {@link #getLength()} bytes.</li>
 * <li>{@link #isUnsatisfiable()} &rarr; answer <code>416 Range Not Satisfiable</code> with
 * <code>Content-Range: bytes *&#47;total</code>.</li>
 * </ul>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class ByteRange {

	/** The request asks for the complete entity. */
	public static final ByteRange WHOLE = new ByteRange(-1, -1, false);

	/** The requested range cannot be satisfied for the given entity size. */
	public static final ByteRange UNSATISFIABLE = new ByteRange(-1, -1, true);

	private static final String BYTES_UNIT = "bytes=";

	private final long _start;

	private final long _end;

	private final boolean _unsatisfiable;

	private ByteRange(long start, long end, boolean unsatisfiable) {
		_start = start;
		_end = end;
		_unsatisfiable = unsatisfiable;
	}

	/**
	 * Parses the value of a <code>Range</code> request header against an entity of the given size.
	 *
	 * <p>
	 * Only the <code>bytes</code> unit is understood. A missing, empty, malformed or
	 * non-<code>bytes</code> header yields {@link #WHOLE}: a range a server does not understand
	 * must be ignored, not rejected. A multi-range request (a header with more than one range
	 * specification) also yields {@link #WHOLE}, since answering with the complete entity and
	 * <code>200 OK</code> is an allowed response to any range request and spares us assembling a
	 * <code>multipart/byteranges</code> body.
	 * </p>
	 *
	 * @param header
	 *        The raw header value, may be <code>null</code>.
	 * @param total
	 *        The size of the entity in bytes, must not be negative.
	 * @return The parsed range, never <code>null</code>.
	 */
	public static ByteRange parse(String header, long total) {
		if (header == null) {
			return WHOLE;
		}
		String spec = header.trim();
		if (spec.length() <= BYTES_UNIT.length()) {
			return WHOLE;
		}
		if (!spec.regionMatches(true, 0, BYTES_UNIT, 0, BYTES_UNIT.length())) {
			return WHOLE;
		}
		spec = spec.substring(BYTES_UNIT.length()).trim();
		if (spec.indexOf(',') >= 0) {
			// Multi-range request: answered with the complete entity.
			return WHOLE;
		}

		int dash = spec.indexOf('-');
		if (dash < 0) {
			return WHOLE;
		}
		String from = spec.substring(0, dash).trim();
		String to = spec.substring(dash + 1).trim();

		long start;
		long end;
		try {
			if (from.isEmpty()) {
				// Suffix range: the last <to> bytes.
				if (to.isEmpty()) {
					return WHOLE;
				}
				long suffix = Long.parseLong(to);
				if (suffix < 0) {
					return WHOLE;
				}
				if (suffix == 0 || total == 0) {
					return UNSATISFIABLE;
				}
				start = suffix >= total ? 0 : total - suffix;
				end = total - 1;
			} else {
				start = Long.parseLong(from);
				if (start < 0) {
					return WHOLE;
				}
				if (start >= total) {
					return UNSATISFIABLE;
				}
				if (to.isEmpty()) {
					end = total - 1;
				} else {
					end = Long.parseLong(to);
					if (end < start) {
						// Invalid specification, ignore the header.
						return WHOLE;
					}
					// Clamp an end beyond the entity to its last byte.
					end = Math.min(end, total - 1);
				}
			}
		} catch (NumberFormatException ex) {
			return WHOLE;
		}

		return new ByteRange(start, end, false);
	}

	/** Whether the complete entity should be delivered with status <code>200</code>. */
	public boolean isWhole() {
		return this == WHOLE;
	}

	/** Whether a slice should be delivered with status <code>206</code>. */
	public boolean isPartial() {
		return !_unsatisfiable && _start >= 0;
	}

	/** Whether the request must be answered with status <code>416</code>. */
	public boolean isUnsatisfiable() {
		return _unsatisfiable;
	}

	/** The index of the first byte to deliver, only defined if {@link #isPartial()}. */
	public long getStart() {
		return _start;
	}

	/** The index of the last byte to deliver (inclusive), only defined if {@link #isPartial()}. */
	public long getEnd() {
		return _end;
	}

	/** The number of bytes to deliver, only defined if {@link #isPartial()}. */
	public long getLength() {
		return _end - _start + 1;
	}

	@Override
	public String toString() {
		if (_unsatisfiable) {
			return "ByteRange[unsatisfiable]";
		}
		if (isPartial()) {
			return "ByteRange[" + _start + "-" + _end + "]";
		}
		return "ByteRange[whole]";
	}

}
