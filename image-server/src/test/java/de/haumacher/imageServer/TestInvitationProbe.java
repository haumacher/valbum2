/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.UserStore;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.file.Files;

/**
 * Review probe of the server half of issue #52: invitations and guests composed with the
 * features that were there before them — placement rules (#48), declined shares (#50), the
 * canonical <code>~owner</code> form (#49), and the byte-identity of the owner's library.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestInvitationProbe extends InviteTestCase {

	private static final String GUEST_ROOT = UserStore.DIRECTORY_NAME + "/guests/eve";


	public void testAnInvitationExpiryIsNormalisedOrRefusedWithAMessage() throws Exception {
		// Instant.parse accepts an offset since Java 12; the server stores the instant in UTC.
		FakeResponse withOffset = invite(SharingFixture.ALICE, Roles.VIEW, "2026-12-24T18:00:00+01:00", "");
		assertEquals(HttpServletResponse.SC_OK, withOffset.status());
		assertEquals("2026-12-24T17:00:00Z", created(withOffset).getInvitation().getExpires());

		FakeResponse unreadable = invite(SharingFixture.ALICE, Roles.VIEW, "next week", "");
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, unreadable.status());
		assertFalse("The refusal speaks.", errorMessage(unreadable).isEmpty());
		assertEquals("Only the readable one was recorded.", 1, store().getInvitations().size());
	}

	private static String tomorrow() {
		return java.time.Instant.now().plus(1, java.time.temporal.ChronoUnit.DAYS).toString();
	}

	// --- What a browser may keep. ---

	public void testNoAnswerAboutACallerIsCachedByTheBrowser() throws Exception {
		String token = issue(Roles.VIEW, "alice", tomorrow());
		FakeResponse live = authOf(token);
		assertEquals(HttpServletResponse.SC_OK, live.status());
		assertEquals("A 200 depends on the bearer that asked.", "no-store", live.header("Cache-Control"));
		assertEquals("Authorization", live.header("Vary"));

		String id = store().lookup(token).getId();
		store().revoke(id);
		restartServer();
		FakeResponse gone = authOf(token);
		assertEquals(HttpServletResponse.SC_GONE, gone.status());
		// Found in the browser check of #52: Chrome caches a 410 by default, and replayed the
		// withdrawn share link's answer to the invitation opened afterwards at the same origin.
		assertEquals("A 410 is cacheable by default; this one must not be.", "no-store",
			gone.header("Cache-Control"));

		FakeResponse listing = get("/", "json", SharingFixture.ALICE);
		assertEquals("no-store", listing.header("Cache-Control"));
	}

	// --- Names and canonical paths. ---

	public void testANameSpelledLikeACanonicalPathIsRefused() throws Exception {
		String token = issue(Roles.EDIT, "alice", tomorrow());

		FakeResponse response = accept(token, "~alice");

		assertTrue("A user named like the canonical form of another space is refused: " + response.status(),
			response.status() >= 400 && response.status() < 500);
		assertFalse("The refusal speaks.", errorMessage(response).isEmpty());
		assertFalse("No space folder was created.", Files.exists(_base.resolve("~alice")));
		assertNull(new UserStore(_base).getUser("~alice"));
		assertFalse("The invitation is still open.", store().lookup(token).isUsed());
	}


	// --- The owner's library through the whole life of a guest. ---

}
