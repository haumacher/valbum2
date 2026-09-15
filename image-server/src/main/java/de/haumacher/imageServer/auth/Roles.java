/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.auth;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * What a user of a space may do, one of the two axes of the permission model of Phase 6 (#83).
 *
 * <p>
 * A role is persisted and reported over the protocol, so it is a stable string, not an enum
 * ordinal. It says what a user may <em>do</em>; {@link Clearances} says what they may <em>see</em>;
 * the share flag says whether they may hand out share links. A user holds the same role in every
 * album of their space — there are no per-album permissions any more, which is the whole point of
 * the model: one question, one answer, the same everywhere.
 * </p>
 *
 * <p>
 * <b>The roles before #83.</b> A library written by an earlier build calls its users
 * {@value #LEGACY_MEMBER} and {@value #LEGACY_GUEST}. They are read as what they could do:
 * </p>
 * <ul>
 * <li>{@value #LEGACY_MEMBER} → {@link #EDIT}: a member owned a space and did everything in it.</li>
 * <li>{@value #LEGACY_GUEST} → {@link #VIEW}: a guest owned nothing and looked at what was shared
 * with them.</li>
 * </ul>
 * <p>
 * The stored file is not rewritten for that: it is read through {@link #of(String)} on every load,
 * so a library can still be opened by the build before this one.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class Roles {

	/** Everything, plus the users of the space: invite, set a permission, remove a user. */
	public static final String ADMIN = "admin";

	/** Everything with the albums: look, download, add, and change what is there. */
	public static final String EDIT = "edit";

	/** Look, download and add photos, but never change or remove what is already there. */
	public static final String CONTRIBUTE = "contribute";

	/** Look and download, and nothing else. */
	public static final String VIEW = "view";

	/** What a user owning a space was called before #83, read as {@link #EDIT}. */
	public static final String LEGACY_MEMBER = "member";

	/** What a user without a space was called before #83, read as {@link #VIEW}. */
	public static final String LEGACY_GUEST = "guest";

	private static final Set<String> ALL =
		Collections.unmodifiableSet(new HashSet<>(Arrays.asList(ADMIN, EDIT, CONTRIBUTE, VIEW)));

	/**
	 * The role of the given stored string, translating the names of an older library.
	 *
	 * @return <code>null</code> if this build knows no such role; such a user is never silently
	 *         treated as anything, see {@link AuthService#ROLE_REFUSED}.
	 */
	public static String of(String role) {
		if (role == null) {
			return null;
		}
		switch (role) {
			case LEGACY_MEMBER:
				return EDIT;
			case LEGACY_GUEST:
				return VIEW;
			default:
				return ALL.contains(role) ? role : null;
		}
	}

	/** Whether the given string is a role this build knows, its older names included. */
	public static boolean isKnown(String role) {
		return of(role) != null;
	}

	/** The roles this build knows, for a message naming them. */
	public static String names() {
		return ADMIN + ", " + EDIT + ", " + CONTRIBUTE + ", " + VIEW;
	}

	/**
	 * What the given role may do, everywhere in its space.
	 *
	 * <p>
	 * {@link #ADMIN} and {@link #EDIT} hold every right; the difference between them is the users
	 * of the space, which is not a right on a folder and is asked as {@link #isAdmin(String)}.
	 * {@link #VIEW} holds {@link Rights#VIEW} and {@link Rights#DOWNLOAD} together: somebody who
	 * may look at a photo may keep it — a server cannot prevent a screenshot, and pretending
	 * otherwise would only be a worse experience for the honest.
	 * </p>
	 */
	public static Set<String> rightsOf(String role) {
		String known = of(role);
		if (known == null) {
			return Rights.NONE;
		}
		switch (known) {
			case ADMIN:
			case EDIT:
				return Rights.ALL;
			case CONTRIBUTE:
				return Rights.CONTRIBUTE_ONLY;
			default:
				return Rights.READ_ONLY;
		}
	}

	/** Whether the given role administers its space: its users, invitations and permissions. */
	public static boolean isAdmin(String role) {
		return ADMIN.equals(of(role));
	}
}
