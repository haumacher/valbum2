/*
 * Copyright (c) 2021 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer.io;

import java.io.IOException;

import de.haumacher.msgbuf.io.Reader;

/**
 * TODO
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public final class ReaderAdapter implements Reader {

	private java.io.Reader _in;

	/** 
	 * Creates a {@link ReaderAdapter}.
	 */
	public ReaderAdapter(java.io.Reader in) {
		_in = in;
	}

	@Override
	public int read() throws IOException {
		return _in.read();
	}

	@Override
	public int read(char[] cbuf, int off, int len) throws IOException {
		return _in.read(cbuf, off, len);
	}

	@Override
	public void close() throws IOException {
		_in.close();
	}

}
