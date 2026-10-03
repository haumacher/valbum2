/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.raw;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import javax.imageio.stream.ImageInputStreamImpl;

/**
 * A piece of a file as an {@link javax.imageio.stream.ImageInputStream} of its own, see issue #191.
 *
 * <p>
 * What ImageIO's JPEG reader decodes the preview of a raw file through: the bytes are read in place
 * and never copied out, so a 10&nbsp;MB preview of a 60&nbsp;MB raw costs no more heap than a JPEG
 * of that size, and the region decodes and subsampling of issues #68 and #140 work as for a JPEG.
 * The file is opened for reading only.
 * </p>
 */
final class RangeImageInputStream extends ImageInputStreamImpl {

	private final RandomAccessFile _file;

	private final long _offset;

	private final long _length;

	RangeImageInputStream(File file, long offset, long length) throws IOException {
		_file = new RandomAccessFile(file, "r");
		_offset = offset;
		_length = length;
	}

	@Override
	public int read() throws IOException {
		checkClosed();
		bitOffset = 0;
		if (streamPos >= _length) {
			return -1;
		}
		_file.seek(_offset + streamPos);
		int result = _file.read();
		if (result >= 0) {
			streamPos++;
		}
		return result;
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		checkClosed();
		bitOffset = 0;
		if (len == 0) {
			return 0;
		}
		long left = _length - streamPos;
		if (left <= 0) {
			return -1;
		}
		_file.seek(_offset + streamPos);
		int result = _file.read(b, off, (int) Math.min(len, left));
		if (result > 0) {
			streamPos += result;
		}
		return result;
	}

	@Override
	public long length() {
		return _length;
	}

	@Override
	public void close() throws IOException {
		super.close();
		_file.close();
	}
}
