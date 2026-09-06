/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.auth;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The roles a {@link UserStore.User} can have, see issue #45.
 *
 * <p>
 * A role is persisted and reported over the protocol, so it is a stable string, not an enum
 * ordinal. Only {@link #ADMIN} is ever created by this server; {@link #MEMBER} and {@link #GUEST}
 * arrive with the invitation flow (issue #52), but the space mechanism is built for a member from
 * the start.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class Roles {

	/** The server owner: signs in against the pairing secret and owns the library. */
	public static final String ADMIN = "admin";

	/** A user owning exactly one space folder of their own. */
	public static final String MEMBER = "member";

	/** A user without a space of their own, see issue #52. */
	public static final String GUEST = "guest";

	private static final Set<String> ALL =
		Collections.unmodifiableSet(new HashSet<>(Arrays.asList(ADMIN, MEMBER, GUEST)));

	/**
	 * Whether the given string is one of the roles this build knows.
	 *
	 * <p>
	 * A stored user with an unknown role is never silently treated as anything: the sign-in is
	 * refused, see {@link AuthService#ROLE_REFUSED}.
	 * </p>
	 */
	public static boolean isKnown(String role) {
		return ALL.contains(role);
	}

	/** The roles this build knows, for a message naming them. */
	public static String names() {
		return ADMIN + ", " + MEMBER + ", " + GUEST;
	}
}
