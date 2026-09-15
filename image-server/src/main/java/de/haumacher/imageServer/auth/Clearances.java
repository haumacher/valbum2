/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.auth;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Which privacy levels a user may see, one of the two axes of the permission model of Phase 6
 * (issue #82).
 *
 * <p>
 * A clearance is persisted with the user and reported over the protocol, so it is a stable string,
 * not an enum ordinal — exactly like {@link Roles}. It is compared against the privacy level of an
 * image, which does not change: the levels of {@link Privacy} stay what they are, and this says
 * how far up them a user may look.
 * </p>
 *
 * <p>
 * This package stores the clearance and answers it; issue #83 makes it the one thing the endpoints
 * consult. Until then the clearance of the space's own users is what it always was, so the server
 * keeps working between the two packages.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class Clearances {

	/** Only images nobody restricted, {@link Privacy#PUBLIC}. */
	public static final String PUBLIC = "public";

	/** Everything but what is kept private, up to {@link Privacy#MEMBERS}. */
	public static final String NON_PRIVATE = "nonPrivate";

	/** Everything in the space, up to {@link Privacy#PRIVATE}. */
	public static final String ALL = "all";

	private static final Set<String> KNOWN =
		Collections.unmodifiableSet(new HashSet<>(Arrays.asList(PUBLIC, NON_PRIVATE, ALL)));

	/** Whether the given string is one of the clearances this build knows. */
	public static boolean isKnown(String clearance) {
		return KNOWN.contains(clearance);
	}

	/** The clearances this build knows, for a message naming them. */
	public static String names() {
		return PUBLIC + ", " + NON_PRIVATE + ", " + ALL;
	}

	/** The {@link Privacy} level the given clearance reaches up to. */
	public static int level(String clearance) {
		switch (clearance == null ? "" : clearance) {
			case ALL:
				return Privacy.PRIVATE;
			case NON_PRIVATE:
				return Privacy.MEMBERS;
			default:
				return Privacy.PUBLIC;
		}
	}

	/**
	 * What a user of the given role holds when nothing was recorded, which is the case for every
	 * user written before issue #82.
	 *
	 * <p>
	 * The rule keeps such a user exactly where they were: the admin and a member owned their space
	 * and saw everything in it, a guest saw what was shown to them.
	 * </p>
	 */
	public static String ofRole(String role) {
		String known = Roles.of(role);
		if (Roles.ADMIN.equals(known) || Roles.EDIT.equals(known)) {
			return ALL;
		}
		// What a guest of the build before #83 effectively saw: the albums shared with them, which
		// were served at the members' level, never the owner's private ones.
		return NON_PRIVATE;
	}

	/** Whether a user of the given role may create share links when nothing was recorded. */
	public static boolean mayShareByRole(String role) {
		String known = Roles.of(role);
		return Roles.ADMIN.equals(known) || Roles.EDIT.equals(known);
	}
}
