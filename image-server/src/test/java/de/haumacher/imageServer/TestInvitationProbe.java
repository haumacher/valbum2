/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.GrantStore;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.Subjects;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.shared.model.AuthInfo;
import de.haumacher.imageServer.shared.model.PairResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;

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

	private static String tomorrow() {
		return Instant.now().plus(1, ChronoUnit.DAYS).toString();
	}

	private void shareZooWithEve() throws Exception {
		new GrantStore(_base).grant("alice", SharingFixture.ZOO, Subjects.user("eve"),
			Collections.singletonList(Rights.VIEW));
		restartServer();
	}

	// --- Composition with placement and promotion. ---

	public void testAGuestRootFiledByYearPlacesTheLinkAndThePromotionKeepsIt() throws Exception {
		assertEquals("The probe's spelling of the guest root matches the server's.",
			AuthService.guestRoot(_base, "eve"), _base.resolve(GUEST_ROOT));
		filedByYear(GUEST_ROOT);
		shareZooWithEve();

		assertEquals("The link is filed by the root's rule, as a member's is.",
			Collections.singletonList("2024"), entryNames(listing(get("/", "json", SharingFixture.EVE))));
		assertEquals(Collections.singletonList("2024-05-01 Zoo"),
			entryNames(listing(get("/2024/", "json", SharingFixture.EVE))));

		assertEquals(HttpServletResponse.SC_OK, promote(SharingFixture.ALICE, "eve").status());
		restartServer();

		assertEquals("The filed link travels with the promotion.", Collections.singletonList("2024"),
			entryNames(listing(get("/", "json", SharingFixture.EVE))));
		FakeResponse album = get("/2024/2024-05-01 Zoo/", "json", SharingFixture.EVE);
		assertEquals(HttpServletResponse.SC_OK, album.status());
		assertEquals("Zoo", album(album).getTitle());
		assertEquals("The member may now create albums beside the filed link.", HttpServletResponse.SC_OK,
			put("/2024/Mine/", "[\"AlbumInfo\",{\"title\":\"Mine\"}]", SharingFixture.EVE).status());
	}

	public void testADeclinedShareStaysDeclinedAfterThePromotion() throws Exception {
		shareZooWithEve();
		listing(get("/", "json", SharingFixture.EVE));
		unlink("/", SharingFixture.EVE, "2024-05-01 Zoo");

		assertEquals(HttpServletResponse.SC_OK, promote(SharingFixture.ALICE, "eve").status());
		restartServer();

		assertTrue("What the guest declined does not come back to the member.",
			listing(get("/", "json", SharingFixture.EVE)).getFolders().isEmpty());
	}

	// --- The invitation token as a bearer. ---

	public void testAnInvitationTokenIsAnonymousEverywhereButAuth() throws Exception {
		String token = issue(Roles.GUEST, "alice", tomorrow());

		AuthInfo auth = auth(authOf(token));
		assertNotNull(auth.getInvitation());
		assertEquals(Roles.GUEST, auth.getInvitation().getRole());
		assertEquals("alice", auth.getInvitation().getInvitedBy());
		assertEquals("", auth.getUserName());

		FakeResponse listing = get("/", "json", token);
		assertEquals("A migrated library refuses an anonymous caller, and an invitation is not a login.",
			HttpServletResponse.SC_UNAUTHORIZED, listing.status());
		assertEquals(AuthService.LIBRARY_REFUSED, errorMessage(listing));
	}

	public void testAnInvitationExpiryIsNormalisedOrRefusedWithAMessage() throws Exception {
		// Instant.parse accepts an offset since Java 12; the server stores the instant in UTC.
		FakeResponse withOffset = invite(SharingFixture.ALICE, Roles.GUEST, "2026-12-24T18:00:00+01:00", "");
		assertEquals(HttpServletResponse.SC_OK, withOffset.status());
		assertEquals("2026-12-24T17:00:00Z", created(withOffset).getInvitation().getExpires());

		FakeResponse unreadable = invite(SharingFixture.ALICE, Roles.GUEST, "next week", "");
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, unreadable.status());
		assertFalse("The refusal speaks.", errorMessage(unreadable).isEmpty());
		assertEquals("Only the readable one was recorded.", 1, store().getInvitations().size());
	}

	// --- Names and canonical paths. ---

	public void testANameSpelledLikeACanonicalPathIsRefused() throws Exception {
		String token = issue(Roles.MEMBER, "alice", tomorrow());

		FakeResponse response = accept(token, "~alice");

		assertTrue("A user named like the canonical form of another space is refused: " + response.status(),
			response.status() >= 400 && response.status() < 500);
		assertFalse("The refusal speaks.", errorMessage(response).isEmpty());
		assertFalse("No space folder was created.", Files.exists(_base.resolve("~alice")));
		assertNull(new UserStore(_base).getUser("~alice"));
		assertFalse("The invitation is still open.", store().lookup(token).isUsed());
	}

	public void testAGuestsRootIsReachedByNobodyElse() throws Exception {
		shareZooWithEve();
		listing(get("/", "json", SharingFixture.EVE));

		FakeResponse asBob = get("/~eve/", "json", SharingFixture.BOB);
		assertTrue("A guest has no space anybody could be pointed at: " + asBob.status(),
			asBob.status() == HttpServletResponse.SC_NOT_FOUND || asBob.status() == HttpServletResponse.SC_FORBIDDEN);
		assertFalse(errorMessage(asBob).isEmpty());
	}

	// --- The owner's library through the whole life of a guest. ---

	public void testTheOwnersLibraryIsByteIdenticalAcrossAcceptBrowseAndPromote() throws Exception {
		String before = fingerprint(_base.resolve("alice"));

		String token = created(invite(SharingFixture.ALICE, Roles.GUEST)).getToken();
		PairResponse fred = paired(accept(token, "fred"));
		assertEquals(Roles.GUEST, fred.getRole());
		new GrantStore(_base).grant("alice", SharingFixture.ZOO, Subjects.user("fred"),
			Collections.singletonList(Rights.CONTRIBUTE));
		restartServer();
		assertEquals(Collections.singletonList("2024-05-01 Zoo"),
			entryNames(listing(get("/", "json", fred.getToken()))));
		assertEquals(HttpServletResponse.SC_OK, get("/2024-05-01 Zoo/", "json", fred.getToken()).status());
		assertEquals(HttpServletResponse.SC_OK, promote(SharingFixture.ALICE, "fred").status());
		restartServer();
		assertEquals(HttpServletResponse.SC_OK, get("/2024-05-01 Zoo/", "json", fred.getToken()).status());

		assertEquals("Accepting, browsing and promoting a guest touch nothing of the owner's.", before,
			fingerprint(_base.resolve("alice")));
		Path space = _base.resolve("fred");
		assertTrue(Files.isDirectory(space));
	}
}
