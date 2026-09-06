/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.auth;

import de.haumacher.imageServer.shared.model.ImagePart;

/**
 * The privacy levels of an {@link ImagePart} and the clearance a request is served with, see issue
 * #46.
 *
 * <p>
 * Both sides of the comparison use the same scale: an image is visible to a request whenever its
 * {@link ImagePart#getPrivacy() privacy} is not above the request's clearance, see
 * {@link #visible(int, int)}. The levels are stored numbers in <code>index.json</code> and have
 * been part of the model since the GWT days, so they are constants here, not an enum: an absent
 * field is {@link #PUBLIC}, which is what makes every album written before this change load
 * unchanged.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class Privacy {

	/** Visible to everyone who may see the album, share links included. */
	public static final int PUBLIC = 0;

	/** Visible to a signed-in user holding a grant on the album (issue #49). */
	public static final int MEMBERS = 1;

	/** Visible to the owner of the space the album lies in. */
	public static final int PRIVATE = 2;

	/** The name of the query parameter lowering one's own clearance, see {@link #viewAs(String)}. */
	public static final String VIEW_AS_PARAMETER = "viewAs";

	/** The <code>viewAs</code> value asking for the clearance of an anonymous caller. */
	public static final String VIEW_AS_PUBLIC = "public";

	/** The <code>viewAs</code> value asking for the clearance of a caller holding a grant. */
	public static final String VIEW_AS_MEMBERS = "members";

	/** Whether an image with the given privacy level is visible to a request with the given clearance. */
	public static boolean visible(int privacy, int clearance) {
		return privacy <= clearance;
	}

	/**
	 * The clearance the given <code>viewAs</code> parameter value asks for.
	 *
	 * <p>
	 * The value is a cap, never a grant: the caller's own clearance is lowered to it, see
	 * {@link AuthService#clearance(AuthService.Caller, de.haumacher.imageServer.PathInfo)}, so it
	 * is safe for anybody to send. That is what the app's "view as" preview switch uses.
	 * </p>
	 *
	 * @param value
	 *        The parameter value, <code>null</code> if the request carries none.
	 * @return The clearance to cap the request at; {@link #PRIVATE} (no cap at all) for
	 *         <code>null</code>.
	 * @throws IllegalArgumentException
	 *         If the value is none of the known ones.
	 */
	public static int viewAs(String value) {
		if (value == null) {
			return PRIVATE;
		}
		switch (value) {
			case VIEW_AS_PUBLIC:
				return PUBLIC;
			case VIEW_AS_MEMBERS:
				return MEMBERS;
			default:
				throw new IllegalArgumentException(value);
		}
	}
}
