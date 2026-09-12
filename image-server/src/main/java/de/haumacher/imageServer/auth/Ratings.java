/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.auth;

import de.haumacher.imageServer.shared.model.ImagePart;

/**
 * The rating scale of an {@link ImagePart} and the lowest rating a request is served with, see
 * issue #51.
 *
 * <p>
 * The counterpart of {@link Privacy} for the album filter's scale: an image is shown to a request
 * whenever its {@link ImagePart#getRating() rating} is not below the request's limit, see
 * {@link #visible(int, int)}. Every caller but a share link has the limit {@link #MIN}, which hides
 * nothing at all — the rating is a filter the viewer sets, and only a share link turns it into
 * something the server enforces.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class Ratings {

	/** The lowest rating on the scale: the limit that hides nothing. */
	public static final int MIN = -2;

	/** The highest rating on the scale. */
	public static final int MAX = 2;

	/** Whether an image with the given rating is shown to a request with the given limit. */
	public static boolean visible(int rating, int minRating) {
		return rating >= minRating;
	}

	/** Whether the given limit hides nothing at all. */
	public static boolean unlimited(int minRating) {
		return minRating <= MIN;
	}

	/** Whether the given value is a rating this server knows. */
	public static boolean isKnown(int rating) {
		return rating >= MIN && rating <= MAX;
	}
}
