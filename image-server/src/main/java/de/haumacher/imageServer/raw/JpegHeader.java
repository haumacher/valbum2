/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.raw;

import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Reading the header of a JPEG that lies somewhere inside another file, see issue #191.
 *
 * <p>
 * A raw file carries its previews as JPEG streams at offsets its container names (or does not name,
 * see {@link RawFile}); before one is taken for a picture its frame header is read: a baseline,
 * extended or progressive Huffman frame (<code>SOF0</code>, <code>SOF1</code>, <code>SOF2</code>) is
 * a picture ImageIO decodes, and its size is what the frame says. Everything else &mdash; the
 * lossless <code>SOF3</code> stream a Canon CR2 or a DNG stores its sensor data in, an arithmetic or
 * hierarchical frame, bytes that only happen to begin like a JPEG &mdash; is no preview.
 * </p>
 *
 * <p>
 * Nothing is decoded and nothing is held: the header is read through a small window of the file.
 * </p>
 */
final class JpegHeader {

	private JpegHeader() {
		// Static utility.
	}

	/**
	 * The size of the picture of the JPEG at the given offset, <code>{width, height}</code>, or
	 * <code>null</code> where no decodable JPEG frame begins there.
	 *
	 * @param limit
	 *        The offset nothing of the JPEG lies at or beyond.
	 */
	static int[] frameSize(RandomAccessFile in, long offset, long limit) throws IOException {
		long end = Math.min(limit, in.length());
		if (offset < 0 || offset + 4 > end) {
			return null;
		}
		in.seek(offset);
		if (in.read() != 0xFF || in.read() != 0xD8) {
			return null;
		}
		long pos = offset + 2;
		for (int segments = 0; segments < 1000 && pos + 4 <= end; segments++) {
			in.seek(pos);
			if (in.read() != 0xFF) {
				return null;
			}
			int marker = in.read();
			pos++;
			while (marker == 0xFF && pos + 2 <= end) {
				// Fill bytes.
				marker = in.read();
				pos++;
			}
			pos++;
			if (marker < 0) {
				return null;
			}
			if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
				continue;
			}
			if (marker == 0xD8 || marker == 0xD9 || marker == 0xDA || marker == 0x00) {
				// A second start, the end or a scan before any frame: no picture here.
				return null;
			}
			int length = (in.read() << 8) | in.read();
			if (length < 2 || pos + length > end) {
				return null;
			}
			if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
				if (marker > 0xC2) {
					// Lossless, hierarchical or arithmetic: the sensor data of a raw, never a preview.
					return null;
				}
				in.read();
				int height = (in.read() << 8) | in.read();
				int width = (in.read() << 8) | in.read();
				if (width <= 0 || height <= 0) {
					return null;
				}
				return new int[] { width, height };
			}
			pos += length;
		}
		return null;
	}

	/**
	 * Where the JPEG that begins at the given offset ends (exclusive), found by walking its segments
	 * and the entropy-coded data of every scan up to its <code>EOI</code>; <code>-1</code> where no
	 * end is found before the given limit.
	 */
	static long end(RandomAccessFile in, long offset, long limit) throws IOException {
		long end = Math.min(limit, in.length());
		Window window = new Window(in, end);
		long pos = offset;
		if (window.at(pos) != 0xFF || window.at(pos + 1) != 0xD8) {
			return -1;
		}
		pos += 2;
		while (pos + 2 <= end) {
			if (window.at(pos) != 0xFF) {
				return -1;
			}
			int marker = window.at(pos + 1);
			pos += 2;
			while (marker == 0xFF && pos < end) {
				marker = window.at(pos);
				pos++;
			}
			if (marker < 0) {
				return -1;
			}
			if (marker == 0xD9) {
				return pos;
			}
			if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
				continue;
			}
			if (pos + 2 > end) {
				return -1;
			}
			int length = (window.at(pos) << 8) | window.at(pos + 1);
			if (length < 2) {
				return -1;
			}
			pos += length;
			if (marker == 0xDA) {
				// The entropy-coded data of the scan: up to the first marker that is no stuffed byte
				// and no restart marker.
				while (pos + 1 < end) {
					int b = window.at(pos);
					if (b < 0) {
						return -1;
					}
					if (b != 0xFF) {
						pos++;
						continue;
					}
					int next = window.at(pos + 1);
					if (next == 0x00 || (next >= 0xD0 && next <= 0xD7) || next == 0xFF) {
						pos += next == 0xFF ? 1 : 2;
						continue;
					}
					break;
				}
			}
		}
		return -1;
	}

	/** A buffered view of a file, read in blocks; for the sequential walk of {@link #end}. */
	static final class Window {

		private static final int SIZE = 64 * 1024;

		private final RandomAccessFile _in;

		private final long _end;

		private final byte[] _buffer = new byte[SIZE];

		private long _start = -1;

		private int _filled;

		Window(RandomAccessFile in, long end) {
			_in = in;
			_end = end;
		}

		/** The byte at the given offset, <code>-1</code> at or beyond the end. */
		int at(long pos) throws IOException {
			if (pos < 0 || pos >= _end) {
				return -1;
			}
			if (_start < 0 || pos < _start || pos >= _start + _filled) {
				_start = pos;
				_in.seek(pos);
				int want = (int) Math.min(SIZE, _end - pos);
				_filled = 0;
				while (_filled < want) {
					int read = _in.read(_buffer, _filled, want - _filled);
					if (read < 0) {
						break;
					}
					_filled += read;
				}
				if (_filled == 0) {
					return -1;
				}
			}
			return _buffer[(int) (pos - _start)] & 0xFF;
		}
	}
}
