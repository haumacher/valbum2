/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.GrantStore;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.Subjects;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.auth.UserStore.Device;
import de.haumacher.imageServer.auth.UserStore.User;
import de.haumacher.imageServer.links.LinkStore;
import de.haumacher.imageServer.links.ShareRegistry;
import de.haumacher.imageServer.shared.model.UserEntry;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Test case for the promotion of a guest to a member, see issue #52.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestPromotion extends InviteTestCase {

	public void testTheAdminPromotesAGuestAndTheirLinksTravelWithThem() throws Exception {
		linkZooForEve();
		Path guestRoot = AuthService.guestRoot(_base, "eve");
		assertTrue("The guest holds a link before the promotion.",
			Files.exists(guestRoot.resolve(LinkStore.FILE_NAME)));

		UserEntry promoted = user(promote(SharingFixture.ALICE, "eve"));

		assertEquals("eve", promoted.getName());
		assertEquals(Roles.MEMBER, promoted.getRole());

		User eve = new UserStore(_base).getUser("eve");
		assertEquals(Roles.MEMBER, eve.getRole());
		assertEquals("eve", eve.getSpace());

		Path space = _base.resolve("eve");
		assertTrue("The guest's root became the member's space, by one rename.", Files.isDirectory(space));
		assertTrue(Files.exists(space.resolve(LinkStore.FILE_NAME)));
		assertTrue(Files.exists(space.resolve(UserStore.DIRECTORY_NAME).resolve(ShareRegistry.FILE_NAME)));
		assertFalse("Nothing is left behind where the guest's root was.", Files.exists(guestRoot));

		restartServer();
		assertEquals("The same link tile, now in the member's own space.",
			Collections.singletonList("2024-05-01 Zoo"),
			entryNames(listing(get("/", "json", SharingFixture.EVE))));
	}

	public void testAPromotedGuestMayThenHaveAlbumsOfTheirOwn() throws Exception {
		promote(SharingFixture.ALICE, "eve");
		restartServer();

		FakeResponse response = put("/My holidays/", "[\"AlbumInfo\",{\"title\":\"Mine\"}]", SharingFixture.EVE);

		assertEquals(HttpServletResponse.SC_OK, response.status());
		assertTrue(Files.isDirectory(_base.resolve("eve").resolve("My holidays")));
	}

	public void testAGuestWithoutASingleLinkIsPromotedToAnEmptySpace() throws Exception {
		assertFalse(Files.exists(AuthService.guestRoot(_base, "eve")));

		assertEquals(HttpServletResponse.SC_OK, promote(SharingFixture.ALICE, "eve").status());

		assertTrue(Files.isDirectory(_base.resolve("eve")));
		assertEquals("eve", new UserStore(_base).getUser("eve").getSpace());
	}

	public void testAMemberPromotesNobody() throws Exception {
		FakeResponse response = promote(SharingFixture.BOB, "eve");

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(AuthService.PROMOTE_REFUSED, errorMessage(response));
		assertEquals(Roles.GUEST, new UserStore(_base).getUser("eve").getRole());
	}

	public void testAnAnonymousCallerPromotesNobody() throws Exception {
		FakeResponse response = promote(null, "eve");

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status());
		assertEquals(Roles.GUEST, new UserStore(_base).getUser("eve").getRole());
	}

	public void testOnlyAGuestBecomesAMember() throws Exception {
		FakeResponse member = promote(SharingFixture.ALICE, "bob");
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, member.status());
		assertEquals(AuthService.notAGuest("bob"), errorMessage(member));

		FakeResponse admin = promote(SharingFixture.ALICE, "alice");
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, admin.status());
		assertEquals(AuthService.notAGuest("alice"), errorMessage(admin));
	}

	public void testSomebodyThisServerDoesNotKnowIsNotPromoted() throws Exception {
		FakeResponse response = promote(SharingFixture.ALICE, "nobody");

		assertEquals(HttpServletResponse.SC_NOT_FOUND, response.status());
		assertEquals(AuthService.unknownUser("nobody"), errorMessage(response));
	}

	public void testAnOccupiedFolderBlocksThePromotion() throws Exception {
		linkZooForEve();
		Files.createDirectories(_base.resolve("eve"));

		FakeResponse response = promote(SharingFixture.ALICE, "eve");

		assertEquals(HttpServletResponse.SC_CONFLICT, response.status());
		assertEquals(AuthService.promotionBlocked("eve"), errorMessage(response));
		assertTrue("Nothing was moved.",
			Files.exists(AuthService.guestRoot(_base, "eve").resolve(LinkStore.FILE_NAME)));
		assertEquals(Roles.GUEST, new UserStore(_base).getUser("eve").getRole());
	}

	public void testNoPromotionInALibraryThatWasNeverMigrated() throws Exception {
		Path base = Files.createTempDirectory("valbum-unmigrated-promotion-test");
		try {
			UserStore users = new UserStore(base);
			User alice = users.nameOwner("alice");
			alice.addDevice(
				new Device("Alice's phone", UserStore.hash(SharingFixture.ALICE), Instant.now().toString()));
			User eve = new User("eve", Roles.GUEST, "", Instant.now().toString());
			eve.addDevice(new Device("Eve's phone", UserStore.hash(SharingFixture.EVE), Instant.now().toString()));
			users.addUser(eve);
			users.store();

			ImageServlet servlet = new ImageServlet(base.toFile(),
				new AuthService(AuthMode.WRITES, SharingFixture.SECRET, base));
			servlet.init();
			try {
				FakeResponse response = new FakeResponse();
				java.util.Map<String, String> parameters = new java.util.HashMap<>();
				parameters.put("action", "promote");
				java.util.Map<String, String> headers = new java.util.HashMap<>();
				headers.put("Authorization", "Bearer " + SharingFixture.ALICE);
				servlet.doPost(TestImageServletPut.request("/", "application/json",
					"{\"name\":\"eve\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8), headers, parameters),
					response.response());

				assertEquals(HttpServletResponse.SC_CONFLICT, response.status());
				assertEquals(AuthService.SPACE_REFUSED, errorMessage(response));
				assertFalse("Nothing is created.", Files.exists(base.resolve("eve")));
				assertEquals(Roles.GUEST, new UserStore(base).getUser("eve").getRole());
			} finally {
				servlet.destroy();
			}
		} finally {
			try (Stream<Path> files = Files.walk(base)) {
				files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
	}

	/** Shares alice's zoo album with eve and lets her list her root, so that the link is made. */
	private void linkZooForEve() throws Exception {
		new GrantStore(_base).grant("alice", SharingFixture.ZOO, Subjects.user("eve"),
			Collections.singletonList(Rights.VIEW));
		restartServer();
		listing(get("/", "json", SharingFixture.EVE));
	}
}
