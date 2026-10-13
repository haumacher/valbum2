/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.content;

import java.io.IOException;

/**
 * An {@link Embedder} cannot answer now: the model is being downloaded, the service is starting or
 * cannot run here. The message says which, for the administrator; a background step waits and asks
 * again instead of giving up on a photograph, see issue #257.
 */
public class EmbedderUnavailableException extends IOException {

	/** Creates an {@link EmbedderUnavailableException} saying why. */
	public EmbedderUnavailableException(String reason) {
		super(reason);
	}

	/** Creates an {@link EmbedderUnavailableException} saying why, with what caused it. */
	public EmbedderUnavailableException(String reason, Throwable cause) {
		super(reason, cause);
	}
}
