/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.InvitationStore;
import de.haumacher.imageServer.auth.Spaces;
import de.haumacher.util.servlet.ResourceServlet;
import java.time.Instant;

/**
 * A dead invitation address is the start page of its space, with a word why (issue #88).
 *
 * <p>
 * Somebody who was invited by a link rarely bookmarks the album; the next time they click the very
 * same link from the very same message. That link is used up by then, and what they were shown was
 * the application asking <code>?type=auth</code>, being answered <code>410</code> and standing
 * there with a sentence and nothing to go on with — although this browser holds a device token for
 * this server and is signed in. So a <em>page load</em> below
 * <code>&lt;context&gt;[/&lt;space&gt;]/i/&lt;token&gt;/</code> whose token is no <b>live</b>
 * invitation of that space is answered <code>302</code> to the ordinary application base of the
 * same space, with the reason in the query string:
 * <code>?invitation=used|expired|withdrawn|unknown</code>. The application reads it once, says it,
 * and shows whatever an ordinary start shows — the album for a device that is signed in, the
 * sign-in screen for one that is not.
 * </p>
 *
 * <p>
 * The rule is general and not a special case for "used": <em>a session address whose token is no
 * live invitation of that space</em>. A token nobody ever issued and a token of another space are
 * the same thing here — unknown — because a space knows only its own.
 * </p>
 *
 * <p>
 * Share links (<code>/s/&lt;token&gt;/</code>) are untouched and deliberately so: a share visitor
 * has no account of this server to fall back on, so the page of {@code 410} with its reason is the
 * whole answer they can be given.
 * </p>
 *
 * <p>
 * The check is read-only: looking at an address never uses up, expires or revokes anything, and the
 * store is not written.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
public class InvitationRedirect implements ResourceServlet.SessionGuard {

	/** The query parameter the reason travels in; no protocol change, see issue #88. */
	public static final String REASON_PARAMETER = "invitation";

	/** The invitation was accepted already. */
	public static final String USED = "used";

	/** The invitation's lifetime has run out. */
	public static final String EXPIRED = "expired";

	/** The invitation was withdrawn by the person who issued it. */
	public static final String WITHDRAWN = "withdrawn";

	/** This space never issued such an invitation, or the token is of another space. */
	public static final String UNKNOWN = "unknown";

	private final Spaces _spaces;

	/**
	 * Creates an {@link InvitationRedirect} answering for the given spaces.
	 *
	 * <p>
	 * Each space is asked its own {@link InvitationStore}, which is what makes a token of one space
	 * simply unknown in another.
	 * </p>
	 */
	public InvitationRedirect(Spaces spaces) {
		_spaces = spaces;
	}

	@Override
	public String redirect(String appBase, String prefix, String token) {
		if (!InvitationStore.URL_SEGMENT.equals(prefix)) {
			// A share link is none of this, see the class comment.
			return null;
		}
		Spaces.Space space = _spaces.bySegment(appBase.isEmpty() ? "" : appBase.substring(1));
		if (space == null) {
			// Not a space of this server; the address is answered as it was before.
			return null;
		}
		String reason = reason(space, token);
		if (reason == null) {
			return null;
		}
		return appBase + "/?" + REASON_PARAMETER + "=" + reason;
	}

	/**
	 * Why the given token is no live invitation of the given space, <code>null</code> if it is one.
	 */
	private static String reason(Spaces.Space space, String token) {
		InvitationStore invitations = space.getAuth() == null ? null : space.getAuth().getInvitations();
		if (invitations == null) {
			// A server started with '--auth off' issues no invitation and honours none.
			return UNKNOWN;
		}
		InvitationStore.Link invitation = invitations.lookup(token);
		if (invitation == null) {
			return UNKNOWN;
		}
		if (invitation.isRevoked()) {
			return WITHDRAWN;
		}
		if (invitation.isUsed()) {
			return USED;
		}
		if (invitation.isExpired(Instant.now())) {
			return EXPIRED;
		}
		return null;
	}

}
