/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.GroupStore;
import de.haumacher.imageServer.auth.InvitationStore;
import de.haumacher.imageServer.auth.InviteMode;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.auth.UserStore.Device;
import de.haumacher.imageServer.auth.UserStore.User;
import de.haumacher.imageServer.shared.model.AuthInfo;
import de.haumacher.imageServer.shared.model.InvitationCreated;
import de.haumacher.imageServer.shared.model.InvitationList;
import de.haumacher.imageServer.shared.model.PairResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Test case for the invitations of issue #52: who may issue one, what it looks like, and what
 * accepting it creates.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestImageServletInvite extends InviteTestCase {

	// --- Issuing. ---

	public void testAMemberInvitesAGuest() throws Exception {
		FakeResponse response = invite(SharingFixture.BOB, Roles.GUEST, "", "Uncle Bob");

		InvitationCreated created = created(response);
		assertEquals(Roles.GUEST, created.getInvitation().getRole());
		assertEquals("bob", created.getInvitation().getInvitedBy());
		assertEquals("Uncle Bob", created.getInvitation().getNote());
		assertFalse("An invitation always has a lifetime.", created.getInvitation().getExpires().isEmpty());
		assertEquals("/valbum/" + InvitationStore.URL_SEGMENT + "/" + created.getToken() + "/", created.getUrl());
		assertFalse("The token travels back exactly once.", created.getToken().isEmpty());

		InvitationStore.Link stored = store().get(created.getInvitation().getId());
		assertEquals("bob", stored.getInvitedBy());
		assertEquals(UserStore.hash(created.getToken()), stored.getTokenHash());
	}

	public void testAMemberInvitesAMember() throws Exception {
		InvitationCreated created = created(invite(SharingFixture.BOB, Roles.MEMBER));

		assertEquals(Roles.MEMBER, created.getInvitation().getRole());
	}

	public void testAnInvitationWithoutARoleInvitesAMember() throws Exception {
		InvitationCreated created = created(invite(SharingFixture.BOB, ""));

		assertEquals(Roles.MEMBER, created.getInvitation().getRole());
	}

	public void testNobodyIsInvitedAsAnAdmin() throws Exception {
		FakeResponse response = invite(SharingFixture.ALICE, Roles.ADMIN);

		assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status());
		assertEquals(AuthService.INVITATION_ADMIN_REFUSED, errorMessage(response));
		assertTrue("A refused invitation records nothing.", store().getInvitations().isEmpty());
	}

	public void testAnUnknownRoleIsRefusedWithAMessage() throws Exception {
		FakeResponse response = invite(SharingFixture.ALICE, "sovereign");

		assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status());
		assertEquals(AuthService.invitationRoleRefused("sovereign"), errorMessage(response));
	}

	public void testAnUnreadableExpiryIsRefusedWithAMessage() throws Exception {
		FakeResponse response = invite(SharingFixture.ALICE, Roles.GUEST, "whenever", "");

		assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status());
		assertEquals(AuthService.INVITATION_EXPIRY_REFUSED, errorMessage(response));
	}

	public void testAGuestInvitesNobody() throws Exception {
		FakeResponse response = invite(SharingFixture.EVE, Roles.GUEST);

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(AuthService.INVITE_GUEST_REFUSED, errorMessage(response));
		assertTrue(store().getInvitations().isEmpty());
	}

	public void testAnAnonymousCallerInvitesNobody() throws Exception {
		FakeResponse response = invite(null, Roles.GUEST);

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status());
		assertFalse(errorMessage(response).isEmpty());
		assertTrue(store().getInvitations().isEmpty());
	}

	// --- The flag. ---

	public void testUnderInviteAdminOnlyTheAdminInvites() throws Exception {
		_inviteMode = InviteMode.ADMIN;

		FakeResponse refused = invite(SharingFixture.BOB, Roles.GUEST);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, refused.status());
		assertEquals(AuthService.INVITE_ADMIN_ONLY, errorMessage(refused));

		InvitationCreated created = created(invite(SharingFixture.ALICE, Roles.GUEST));
		assertEquals("alice", created.getInvitation().getInvitedBy());
	}

	// --- Listing and withdrawing. ---

	public void testTheAdminSeesEveryInvitationAndAMemberOnlyTheirOwn() throws Exception {
		String byAlice = created(invite(SharingFixture.ALICE, Roles.GUEST)).getInvitation().getId();
		String byBob = created(invite(SharingFixture.BOB, Roles.MEMBER)).getInvitation().getId();

		InvitationList all = list(invitations(SharingFixture.ALICE));
		assertEquals(2, all.getInvitations().size());
		assertNotNull(invitation(all, byAlice));
		assertNotNull(invitation(all, byBob));

		InvitationList bobs = list(invitations(SharingFixture.BOB));
		assertEquals("A member sees the invitations they issued, and no others.", 1, bobs.getInvitations().size());
		assertNotNull(invitation(bobs, byBob));

		assertTrue("A member who invited nobody sees nothing.",
			list(invitations(SharingFixture.DAVE)).getInvitations().isEmpty());
	}

	public void testNoListingEverCarriesAToken() throws Exception {
		InvitationCreated created = created(invite(SharingFixture.ALICE, Roles.GUEST));

		String listing = body(invitations(SharingFixture.ALICE));

		assertFalse("A listing must never carry the token: " + listing, listing.contains(created.getToken()));
		assertFalse("Nor its hash: " + listing, listing.contains(UserStore.hash(created.getToken())));
	}

	public void testAGuestSeesNoInvitations() throws Exception {
		FakeResponse response = invitations(SharingFixture.EVE);

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(AuthService.INVITATIONS_REFUSED, errorMessage(response));
	}

	public void testAnAnonymousCallerSeesNoInvitations() throws Exception {
		FakeResponse response = invitations(null);

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status());
	}

	public void testAWithdrawnInvitationIsGoneAtTheAuthEndpoint() throws Exception {
		InvitationCreated created = created(invite(SharingFixture.BOB, Roles.GUEST));

		InvitationList answer = list(uninvite(SharingFixture.BOB, created.getInvitation().getId()));

		assertFalse(answer.getInvitations().get(0).getRevoked().isEmpty());
		assertTrue(store().get(created.getInvitation().getId()).isRevoked());

		FakeResponse gone = authOf(created.getToken());
		assertEquals(HttpServletResponse.SC_GONE, gone.status());
		assertEquals(AuthService.INVITATION_REVOKED, errorMessage(gone));
	}

	public void testOnlyTheIssuerAndTheAdminWithdraw() throws Exception {
		String id = created(invite(SharingFixture.BOB, Roles.GUEST)).getInvitation().getId();

		FakeResponse refused = uninvite(SharingFixture.DAVE, id);
		assertEquals("Somebody else's invitation is one this caller never saw.",
			HttpServletResponse.SC_NOT_FOUND, refused.status());
		assertEquals(AuthService.INVITATION_UNKNOWN, errorMessage(refused));
		assertFalse(store().get(id).isRevoked());

		assertEquals(HttpServletResponse.SC_OK, uninvite(SharingFixture.ALICE, id).status());
		assertTrue(store().get(id).isRevoked());
	}

	// --- The invitation as a bearer. ---

	public void testAnInvitationTokenSaysWhoInvitedAndAsWhat() throws Exception {
		InvitationCreated created = created(invite(SharingFixture.BOB, Roles.MEMBER, "", "Come and look"));

		AuthInfo info = auth(authOf(created.getToken()));

		assertEquals(Roles.MEMBER, info.getInvitation().getRole());
		assertEquals("bob", info.getInvitation().getInvitedBy());
		assertEquals("Come and look", info.getInvitation().getNote());
		assertEquals(created.getInvitation().getExpires(), info.getInvitation().getExpires());
		assertEquals("An invitation is no login.", "", info.getUserName());
		assertEquals("", info.getRole());
		assertFalse(info.isWriteAllowed());
	}

	public void testAnInvitationTokenIsAnonymousEverywhereElse() throws Exception {
		InvitationCreated created = created(invite(SharingFixture.BOB, Roles.MEMBER));

		FakeResponse response = get("/", "json", created.getToken());

		assertEquals("A migrated library refuses an anonymous caller, and this caller is one.",
			HttpServletResponse.SC_UNAUTHORIZED, response.status());
		assertEquals(AuthService.LIBRARY_REFUSED, errorMessage(response));
	}

	public void testAnExpiredInvitationIsGoneAtTheAuthEndpoint() throws Exception {
		String token = issue(Roles.MEMBER, "alice", "2026-01-01T00:00:00Z");

		FakeResponse response = authOf(token);

		assertEquals(HttpServletResponse.SC_GONE, response.status());
		assertEquals(AuthService.INVITATION_EXPIRED, errorMessage(response));
	}

	public void testATokenInNeitherStoreIsSimplyUnknown() throws Exception {
		// Share tokens and invitation tokens live in two stores and are looked up in that order;
		// a token in neither must not be mistaken for either of them, see issues #51 and #52.
		FakeResponse response = get("/", "json", "a-token-this-server-never-issued");

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status());
		assertEquals(AuthService.TOKEN_REFUSED, errorMessage(response));
		assertNull(auth(authOf("a-token-this-server-never-issued")).getInvitation());
	}

	// --- Accepting. ---

	public void testAcceptingCreatesTheMemberWithTheirOwnSpace() throws Exception {
		InvitationCreated created = created(invite(SharingFixture.BOB, Roles.MEMBER));

		PairResponse paired = paired(accept(created.getToken(), "frank"));

		assertEquals(Roles.MEMBER, paired.getRole());
		assertEquals("frank", paired.getUserName());
		assertEquals("frank", paired.getSpace());
		assertFalse(paired.getToken().isEmpty());
		assertEquals("Phone", paired.getDeviceName());
		assertTrue("A member's space folder is made on the spot.", Files.isDirectory(_base.resolve("frank")));

		UserStore users = new UserStore(_base);
		User frank = users.getUser("frank");
		assertEquals(Roles.MEMBER, frank.getRole());
		assertEquals("frank", frank.getSpace());
		assertEquals(1, frank.getDevices().size());
		assertEquals("frank", users.lookup(paired.getToken()).getUser().getName());

		InvitationStore.Link used = store().get(created.getInvitation().getId());
		assertTrue(used.isUsed());
		assertEquals("frank", used.getUsedBy());
	}

	public void testTheNewMemberCanUseTheirTokenAtOnce() throws Exception {
		InvitationCreated created = created(invite(SharingFixture.BOB, Roles.MEMBER));
		String token = paired(accept(created.getToken(), "frank")).getToken();

		assertEquals(HttpServletResponse.SC_OK, get("/", "json", token).status());
	}

	public void testASingleUseTokenIsUsedUp() throws Exception {
		InvitationCreated created = created(invite(SharingFixture.BOB, Roles.MEMBER));
		assertEquals(HttpServletResponse.SC_OK, accept(created.getToken(), "frank").status());

		FakeResponse second = accept(created.getToken(), "grace");

		assertEquals(HttpServletResponse.SC_GONE, second.status());
		assertEquals(AuthService.INVITATION_USED, errorMessage(second));
		assertNull("The second name must never be created.", new UserStore(_base).getUser("grace"));
	}

	public void testATokenNobodyIssuedIsRefusedWithAMessage() throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "pair");
		FakeResponse response = post("/",
			"{\"invitation\":\"nonsense\",\"deviceName\":\"Phone\",\"userName\":\"frank\"}", null, parameters);

		assertEquals(HttpServletResponse.SC_GONE, response.status());
		assertEquals(AuthService.INVITATION_UNKNOWN_TOKEN, errorMessage(response));
	}

	public void testATakenNameIsRefused() throws Exception {
		new GroupStore(_base).put("club", "alice", Collections.<String> emptyList());
		// A folder at the top of the base folder that belongs to nobody: a space of that name
		// would land on top of it, so the name is taken just as a user's or a group's is.
		Files.createDirectories(_base.resolve("archive"));
		restartServer();
		InvitationCreated created = created(invite(SharingFixture.BOB, Roles.MEMBER));

		for (String taken : new String[] { "bob", "club", "archive" }) {
			FakeResponse response = accept(created.getToken(), taken);
			assertEquals("'" + taken + "' is taken.", HttpServletResponse.SC_CONFLICT, response.status());
			assertEquals(AuthService.nameTaken(taken), errorMessage(response));
		}
		assertFalse("A refused acceptance must not use the invitation up.",
			store().get(created.getInvitation().getId()).isUsed());
	}

	public void testABadNameIsRefusedWithTheServersOwnRule() throws Exception {
		InvitationCreated created = created(invite(SharingFixture.BOB, Roles.MEMBER));

		FakeResponse response = accept(created.getToken(), "../elsewhere");

		assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status());
		assertEquals(UserStore.NAME_REFUSED, errorMessage(response));
	}

	public void testAcceptingAsAGuestCreatesNoFolderInTheLibrary() throws Exception {
		InvitationCreated created = created(invite(SharingFixture.BOB, Roles.GUEST));

		PairResponse paired = paired(accept(created.getToken(), "grace"));

		assertEquals(Roles.GUEST, paired.getRole());
		assertEquals("A guest has no space of their own.", "", paired.getSpace());
		assertFalse("Nothing of a guest's ever lies in the album tree.",
			Files.exists(_base.resolve("grace")));
		assertEquals(Roles.GUEST, new UserStore(_base).getUser("grace").getRole());
		assertFalse("The guest's root is made when something is first put in it, not before.",
			Files.exists(AuthService.guestRoot(_base, "grace")));
	}

	// --- A library that was never migrated. ---

	public void testAMemberCannotBeCreatedInAnUnmigratedLibrary() throws Exception {
		Path base = unmigrated();
		try {
			String token = new InvitationStore(base).create(Roles.MEMBER, "alice", "", "").getToken();
			ImageServlet servlet = servletOn(base);

			FakeResponse response = acceptOn(servlet, token, "frank");

			assertEquals(HttpServletResponse.SC_CONFLICT, response.status());
			assertEquals(AuthService.SPACE_REFUSED, errorMessage(response));
			assertNull("Nothing is created.", new UserStore(base).getUser("frank"));
			assertFalse(Files.exists(base.resolve("frank")));
			assertFalse("The invitation is not used up either.",
				new InvitationStore(base).getInvitations().get(0).isUsed());
			servlet.destroy();
		} finally {
			delete(base);
		}
	}

	public void testAGuestCanBeCreatedInAnUnmigratedLibrary() throws Exception {
		Path base = unmigrated();
		try {
			String token = new InvitationStore(base).create(Roles.GUEST, "alice", "", "").getToken();
			ImageServlet servlet = servletOn(base);

			FakeResponse response = acceptOn(servlet, token, "grace");

			PairResponse paired = paired(response);
			assertEquals(Roles.GUEST, paired.getRole());
			assertEquals("", paired.getSpace());
			assertEquals(Roles.GUEST, new UserStore(base).getUser("grace").getRole());
			servlet.destroy();
		} finally {
			delete(base);
		}
	}

	// --- Helpers for the second, un-migrated library. ---

	/** A base folder whose owner still has the base folder itself as her library, see issue #45. */
	private Path unmigrated() throws Exception {
		Path base = Files.createTempDirectory("valbum-unmigrated-invite-test");
		UserStore users = new UserStore(base);
		User alice = users.nameOwner("alice");
		alice.addDevice(new Device("Alice's phone", UserStore.hash(SharingFixture.ALICE), Instant.now().toString()));
		users.store();
		Files.write(base.resolve("index.json"),
			"[\"ListingInfo\",{\"title\":\"Root\"}]".getBytes(StandardCharsets.UTF_8));
		return base;
	}

	private static ImageServlet servletOn(Path base) throws Exception {
		ImageServlet servlet = new ImageServlet(base.toFile(),
			new AuthService(AuthMode.WRITES, SharingFixture.SECRET, base));
		servlet.init();
		return servlet;
	}

	private static FakeResponse acceptOn(ImageServlet servlet, String invitation, String userName) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "pair");
		Map<String, String> headers = new HashMap<>();
		FakeResponse response = new FakeResponse();
		servlet.doPost(TestImageServletPut.request("/", "application/json",
			("{\"invitation\":\"" + invitation + "\",\"deviceName\":\"Phone\",\"userName\":\"" + userName + "\"}")
				.getBytes(StandardCharsets.UTF_8),
			headers, parameters), response.response());
		return response;
	}

	private static void delete(Path root) throws Exception {
		try (Stream<Path> files = Files.walk(root)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
	}
}
