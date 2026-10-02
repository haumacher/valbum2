/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.raw;

import java.io.IOException;

/**
 * A raw file that carries no JPEG preview this server can show, see {@link RawFile}.
 *
 * <p>
 * Its message is a sentence for the reader &mdash; which file, and what to do &mdash; and is what a
 * request for its preview or its display rendition is answered with.
 * </p>
 */
public class NoEmbeddedPreviewException extends IOException {

	private static final long serialVersionUID = 1L;

	/** Creates a {@link NoEmbeddedPreviewException}. */
	public NoEmbeddedPreviewException(String message) {
		super(message);
	}

	/** Creates a {@link NoEmbeddedPreviewException} for a file that could not be read. */
	public NoEmbeddedPreviewException(String message, Throwable cause) {
		super(message, cause);
	}
}
