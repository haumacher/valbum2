/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.io.IOException;

/**
 * A picture whose decoder needs more memory than this server lets one picture take, see issue #207
 * and {@link PictureReader}.
 *
 * <p>
 * Its message is a sentence for the reader — which picture, how much it needs, how much there is
 * and what to do — and is answered as the {@code ErrorInfo} of the preview it refuses.
 * </p>
 */
public class PictureTooLargeException extends IOException {

	private static final long serialVersionUID = 1L;

	/**
	 * Creates a {@link PictureTooLargeException}.
	 */
	public PictureTooLargeException(String message) {
		super(message);
	}

	/**
	 * Creates a {@link PictureTooLargeException} for a decode that ran out of memory after all.
	 */
	public PictureTooLargeException(String message, Throwable cause) {
		super(message, cause);
	}
}
