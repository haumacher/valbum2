/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.GrantStore;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.auth.Subjects;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.links.LinkService;
import de.haumacher.imageServer.links.LinkStore;
import de.haumacher.imageServer.links.ShareRegistry;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.ListingInfo;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

/**
 * Test case for the guest root of issue #52: a place that holds the albums shared with a guest and
 * nothing else.
 *
 * <p>
 * Eve of the {@link SharingFixture} is the guest; the zoo album of alice is shared with her here,
 * exactly as it is with a member.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestGuestSpace extends InviteTestCase {

	/** Where the links made for eve are kept, see {@link UserStore#GUESTS_DIRECTORY_NAME}. */
	private Path _eve;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_eve = AuthService.guestRoot(_base, "eve");
	}

	// --- Looking. ---

	public void testAGuestsRootIsExactlyTheAlbumsSharedWithThem() throws Exception {
		shareZooWithEve(Rights.VIEW);

		ListingInfo listing = listing(get("/", "json", SharingFixture.EVE));

		assertEquals("The guest sees the shared album and nothing else.",
			Collections.singletonList("2024-05-01 Zoo"), entryNames(listing));
		FolderInfo tile = entry(listing, "2024-05-01 Zoo");
		assertEquals("The tile is built from the target, and says whose it is.",
			"~alice/" + SharingFixture.ZOO, tile.getLink());
		assertEquals("Zoo", tile.getTitle());
		assertTrue("The links of a guest live beside the server's own state, never in the album tree.",
			Files.isDirectory(_eve));
		assertTrue(Files.exists(_eve.resolve(LinkStore.FILE_NAME)));
		assertTrue("The share registry lies where a member's does, below the root's own .valbum.",
			Files.exists(_eve.resolve(UserStore.DIRECTORY_NAME).resolve(ShareRegistry.FILE_NAME)));
	}

	public void testAGuestFollowsTheLinkIntoTheOwnersAlbum() throws Exception {
		shareZooWithEve(Rights.VIEW);
		listing(get("/", "json", SharingFixture.EVE));

		FakeResponse response = get("/2024-05-01 Zoo/", "json", SharingFixture.EVE);

		assertEquals(HttpServletResponse.SC_OK, response.status());
		assertEquals("Zoo", album(response).getTitle());
	}

	public void testBrowsingAsAGuestLeavesTheOwnersLibraryByteIdentical() throws Exception {
		shareZooWithEve(Rights.VIEW);
		String before = fingerprint(_base.resolve("alice"));

		listing(get("/", "json", SharingFixture.EVE));
		get("/2024-05-01 Zoo/", "json", SharingFixture.EVE);

		assertEquals("Nothing of the owner's is touched because a guest looked.", before,
			fingerprint(_base.resolve("alice")));
	}

	// --- Writing. ---

	public void testAGuestCreatesNoAlbumInTheirRoot() throws Exception {
		FakeResponse response = put("/My holidays/", "[\"AlbumInfo\",{\"title\":\"Mine\"}]", SharingFixture.EVE);

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(AuthService.GUEST_SPACE_REFUSED, errorMessage(response));
		assertFalse(Files.exists(_eve.resolve("My holidays")));
	}

	public void testAGuestUploadsNothingIntoTheirRoot() throws Exception {
		FakeResponse response = upload("/", SharingFixture.EVE, "holiday.jpg");

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(AuthService.GUEST_SPACE_REFUSED, errorMessage(response));
		assertFalse(Files.exists(_eve.resolve("holiday.jpg")));
	}

	public void testNothingIsMovedIntoAGuestsRoot() throws Exception {
		// The edit right, so that the source folder is not what refuses the move: what is asked
		// here is the target, and the target is the one folder a guest may put nothing into.
		shareZooWithEve(Rights.EDIT);

		FakeResponse response = move("/~alice/" + SharingFixture.ZOO + "/", "~eve", SharingFixture.EVE,
			"public.jpg");

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(AuthService.GUEST_SPACE_REFUSED, errorMessage(response));
		assertTrue("The photo stays where it is.",
			Files.exists(_base.resolve("alice").resolve(SharingFixture.ZOO).resolve("public.jpg")));
	}

	public void testAGuestsRootIsSharedWithNobody() throws Exception {
		FakeResponse response = grant("/~eve/", SharingFixture.ALICE, "grant", Subjects.user("bob"), Rights.VIEW);

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(AuthService.GUEST_GRANT_REFUSED, errorMessage(response));
		assertTrue(new GrantStore(_base).ofOwner("eve").isEmpty());
	}

	public void testAGuestContributesWhereAGrantSaysSo() throws Exception {
		shareZooWithEve(Rights.CONTRIBUTE);

		FakeResponse response = upload("/~alice/" + SharingFixture.ZOO + "/", SharingFixture.EVE, "eves.jpg");

		assertEquals(HttpServletResponse.SC_OK, response.status());
		assertTrue("What a grant allows, a guest does.",
			Files.exists(_base.resolve("alice").resolve(SharingFixture.ZOO).resolve("eves.jpg")));
	}

	public void testAGuestDeclinesAShareLikeAMember() throws Exception {
		shareZooWithEve(Rights.VIEW);
		listing(get("/", "json", SharingFixture.EVE));

		FakeResponse response = unlink("/", SharingFixture.EVE, "2024-05-01 Zoo");

		assertEquals(LinkService.UNLINKED, outcome(moveResult(response), "2024-05-01 Zoo").getMessage());
		assertTrue("The declined share does not come back on the next listing.",
			listing(get("/", "json", SharingFixture.EVE)).getFolders().isEmpty());
	}

	/** Shares alice's zoo album with eve and restarts the server, so that the grant is read. */
	private void shareZooWithEve(String right) throws Exception {
		new GrantStore(_base).grant("alice", SharingFixture.ZOO, Subjects.user("eve"),
			Collections.singletonList(right));
		restartServer();
	}
}
