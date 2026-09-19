/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.Clearances;
import de.haumacher.imageServer.auth.DeviceCodeStore;
import de.haumacher.imageServer.auth.InvitationStore;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.shared.model.AuthInfo;
import de.haumacher.imageServer.shared.model.Invitation;
import de.haumacher.imageServer.shared.model.InvitationCreated;
import de.haumacher.imageServer.shared.model.UserEntry;
import de.haumacher.imageServer.shared.model.UserList;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * An invitation is a pending user carrying a code, see issue #89 (step two).
 *
 * <p>
 * The one mechanism, asserted from both ends: what issuing an invitation writes (a nameless user
 * and one code of {@link DeviceCodeStore#KIND_INVITATION}), and that redeeming it is the ordinary
 * pairing every other code goes through. What used to be a store, a token and a redemption path of
 * its own is now a row in {@code users.json} and a row in {@code device-codes.json}.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestInvitationAsCode extends InviteTestCase {

	// --- Issuing: a pending user and one code. ---

	public void testAnInvitationIsAPendingUserAndACode() throws Exception {
		InvitationCreated created =
			created(invite(SharingFixture.ALICE, Roles.EDIT, "", "Come and look", "Grandma"));

		AuthService.Invited invitation = stored(created.getInvitation().getId());
		UserStore.User pending = invitation.getUser();
		assertTrue("Nobody has accepted it yet.", pending.isPending());
		assertEquals("A pending user has no name until somebody chooses one.", "", pending.getName());
		assertTrue("...and no device, so no token signs anybody in as them.",
			pending.getDevices().isEmpty());
		assertEquals(Roles.EDIT, pending.getRole());
		assertEquals(Clearances.ofRole(Roles.EDIT), pending.getClearance());
		assertEquals("alice", pending.getInvitedBy());
		assertEquals("Come and look", pending.getNote());
		assertEquals("The inviter's own memento.", "Grandma", pending.getRecipient());

		DeviceCodeStore.Code code = invitation.getCode();
		assertEquals(DeviceCodeStore.KIND_INVITATION, code.getKind());
		assertEquals("The code's id names the invitation.", code.getId(), pending.getInvitation());
		assertEquals(UserStore.hash(created.getToken()), code.getCodeHash());
		assertEquals("The invitation's expiry, not ten minutes.", created.getInvitation().getExpires(),
			code.getExpires());
		assertTrue("Days, not minutes.",
			Instant.parse(code.getExpires()).isAfter(Instant.now().plus(Duration.ofDays(6))));
	}

	public void testTheListingShowsWhatWasIssued() throws Exception {
		InvitationCreated created =
			created(invite(SharingFixture.ALICE, Roles.VIEW, "", "A note", "Grandma"));

		Invitation listed = invitation(list(invitations(SharingFixture.ALICE)),
			created.getInvitation().getId());
		assertNotNull("Derived from the pending user and their code.", listed);
		assertEquals(Roles.VIEW, listed.getRole());
		assertEquals("alice", listed.getInvitedBy());
		assertEquals("A note", listed.getNote());
		assertEquals("Grandma", listed.getRecipient());
		assertEquals("", listed.getUsed());
		assertEquals("", listed.getRevoked());
		assertFalse(listed.getExpires().isEmpty());
	}

	public void testNothingIsWrittenToAnInvitationStore() throws Exception {
		created(invite(SharingFixture.ALICE, Roles.VIEW));

		assertFalse("The invitation store of issue #52 is retired (issue #89).",
			Files.exists(_base.resolve(UserStore.DIRECTORY_NAME).resolve(InvitationStore.FILE_NAME)));
	}

	// --- The token as a bearer. ---

	public void testTheTokenSaysWhoInvitedAndForWhom() throws Exception {
		InvitationCreated created =
			created(invite(SharingFixture.ALICE, Roles.EDIT, "", "Come and look", "Grandma"));

		AuthInfo info = auth(authOf(created.getToken()));

		assertEquals(Roles.EDIT, info.getInvitation().getRole());
		assertEquals("alice", info.getInvitation().getInvitedBy());
		assertEquals("Come and look", info.getInvitation().getNote());
		assertEquals("Grandma", info.getInvitation().getRecipient());
		assertEquals("An invitation is no login.", "", info.getUserName());
	}

	public void testATypedDeviceCodeIsNoBearer() throws Exception {
		// The one thing that must not follow from "an invitation is a code": eight characters that
		// add a device to somebody's own account must never open a session by being put in a
		// header, see DeviceCodeStore.
		String code = servlet().auth().getDeviceCodes()
			.create("alice", servlet().auth().getUsers().getUser("alice").getDevices().get(0).getId())
			.getCode();

		FakeResponse response = authOf(code);

		AuthInfo info = auth(response);
		assertNull("A device code opens nothing.", info.getInvitation());
		assertEquals("", info.getUserName());
	}

	// --- Redeeming: the ordinary pairing. ---

	public void testJoiningIsPairingWithTheCode() throws Exception {
		InvitationCreated created = created(invite(SharingFixture.ALICE, Roles.EDIT, "", "", "Grandma"));
		String id = created.getInvitation().getId();

		FakeResponse joined = accept(created.getToken(), "frank");
		assertEquals(joined.body(), HttpServletResponse.SC_OK, joined.status());
		assertEquals("frank", paired(joined).getUserName());
		assertEquals(Roles.EDIT, paired(joined).getRole());

		UserStore.User user = servlet().auth().getUsers().getUser("frank");
		assertNotNull(user);
		assertFalse("Named, so no longer pending.", user.isPending());
		assertEquals("One device, the one that joined.", 1, user.getDevices().size());
		assertEquals("The history of how they got here stays.", "alice", user.getInvitedBy());
		assertEquals("Grandma", user.getRecipient());
		assertEquals(id, user.getInvitation());
		assertTrue("The code is used up.", stored(id).getCode().isUsed());
		assertEquals("frank", invitation(list(invitations(SharingFixture.ALICE)), id).getUsedBy());
	}

	public void testTheRetiredInvitationFieldStillJoins() throws Exception {
		InvitationCreated created = created(invite(SharingFixture.ALICE, Roles.VIEW));

		FakeResponse joined = acceptAsBefore(created.getToken(), "gina");

		assertEquals(joined.body(), HttpServletResponse.SC_OK, joined.status());
		assertEquals("gina", paired(joined).getUserName());
	}

	public void testTheSameTokenTwiceIsRefused() throws Exception {
		InvitationCreated created = created(invite(SharingFixture.ALICE, Roles.VIEW));
		assertEquals(HttpServletResponse.SC_OK, accept(created.getToken(), "frank").status());

		FakeResponse again = accept(created.getToken(), "gina");

		assertEquals(HttpServletResponse.SC_GONE, again.status());
		assertEquals(AuthService.INVITATION_USED, errorMessage(again));
		assertNull("Nobody was created twice.", servlet().auth().getUsers().getUser("gina"));
	}

	public void testAJoinWithoutANameIsRefused() throws Exception {
		InvitationCreated created = created(invite(SharingFixture.ALICE, Roles.VIEW));

		FakeResponse response = accept(created.getToken(), "");

		assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status());
		assertEquals(AuthService.NAME_REQUIRED, errorMessage(response));
		assertTrue("The code is still there to be used.",
			stored(created.getInvitation().getId()).isPending());
	}

	public void testATakenNameKeepsTheInvitationAlive() throws Exception {
		InvitationCreated created = created(invite(SharingFixture.ALICE, Roles.VIEW));

		FakeResponse response = accept(created.getToken(), "bob");

		assertEquals(HttpServletResponse.SC_CONFLICT, response.status());
		assertFalse("The refusal speaks.", errorMessage(response).isEmpty());
		assertFalse("The code lives; the next name signs in.",
			stored(created.getInvitation().getId()).getCode().isUsed());
		assertEquals(HttpServletResponse.SC_OK, accept(created.getToken(), "frank").status());
	}

	public void testAnExpiredInvitationIsGoneEverywhere() throws Exception {
		String token = issue(Roles.VIEW, "alice", Instant.now().minus(Duration.ofDays(1)).toString());

		assertEquals(HttpServletResponse.SC_GONE, authOf(token).status());
		assertEquals(AuthService.INVITATION_EXPIRED, errorMessage(authOf(token)));
		FakeResponse joined = accept(token, "frank");
		assertEquals(HttpServletResponse.SC_GONE, joined.status());
		assertEquals(AuthService.INVITATION_EXPIRED, errorMessage(joined));
	}

	// --- Withdrawing. ---

	public void testWithdrawingBeforeJoiningRemovesThePendingUser() throws Exception {
		InvitationCreated created = created(invite(SharingFixture.ALICE, Roles.VIEW, "", "", "Grandma"));
		int before = servlet().auth().getUsers().getUsers().size();

		FakeResponse response = uninvite(SharingFixture.ALICE, created.getInvitation().getId());

		assertEquals(HttpServletResponse.SC_OK, response.status());
		assertEquals("The pending user goes with the invitation.", before - 1,
			servlet().auth().getUsers().getUsers().size());
		assertEquals(HttpServletResponse.SC_GONE, authOf(created.getToken()).status());
		assertEquals(AuthService.INVITATION_REVOKED, errorMessage(authOf(created.getToken())));
	}

	public void testWithdrawingAfterJoiningKeepsTheUser() throws Exception {
		InvitationCreated created = created(invite(SharingFixture.ALICE, Roles.VIEW));
		accept(created.getToken(), "frank");

		FakeResponse response = uninvite(SharingFixture.ALICE, created.getInvitation().getId());

		assertEquals(HttpServletResponse.SC_OK, response.status());
		assertNotNull("Withdrawing an invitation is not removing somebody.",
			servlet().auth().getUsers().getUser("frank"));
	}

	// --- What the invitation survives, and what it does not. ---

	public void testSigningTheInvitersDeviceOutKeepsTheInvitation() throws Exception {
		InvitationCreated created = created(invite(SharingFixture.ALICE, Roles.VIEW));
		String device = servlet().auth().getUsers().getUser("alice").getDevices().get(0).getId();

		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "unpair");
		FakeResponse out = post("/", "{\"id\":\"" + device + "\"}", SharingFixture.ALICE, parameters);
		assertEquals(out.body(), HttpServletResponse.SC_OK, out.status());

		assertEquals("An inviter signing a laptop out has not taken the letter back.",
			HttpServletResponse.SC_OK, authOf(created.getToken()).status());
		assertEquals(HttpServletResponse.SC_OK, accept(created.getToken(), "frank").status());
	}

	public void testRemovingTheInviterWithdrawsWhatTheyPutInThePost() throws Exception {
		// bob invites nobody; alice is the administrator and the one who may invite, so the
		// inviter that is removed has to be a second administrator.
		InvitationCreated open = created(invite(SharingFixture.ALICE, Roles.VIEW));
		InvitationCreated taken = created(invite(SharingFixture.ALICE, Roles.VIEW));
		accept(taken.getToken(), "frank");

		UserStore users = servlet().auth().getUsers();
		users.getUser("bob").setRole(Roles.ADMIN);
		users.store();
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "remove-user");
		FakeResponse removed = post("/", "{\"name\":\"alice\"}", SharingFixture.BOB, parameters);
		assertEquals(removed.body(), HttpServletResponse.SC_OK, removed.status());

		assertEquals("The open invitation dies with the account that vouched for it.",
			HttpServletResponse.SC_GONE, authOf(open.getToken()).status());
		assertNotNull("Somebody who already joined is a user of this space now.",
			servlet().auth().getUsers().getUser("frank"));
	}

	public void testAPendingAdministratorIsNoAdministrator() throws Exception {
		// Nobody is ever invited as an administrator, so this is built by hand: even then, a user
		// without a device can never be the last administrator that keeps a space administrable.
		UserStore users = servlet().auth().getUsers();
		UserStore.User pending = users.addUser(new UserStore.User("", Roles.ADMIN, "",
			Instant.now().toString(), Clearances.ALL, true));
		pending.setInvitation("made-up");
		pending.setInvitedBy("alice");
		users.store();

		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "set-permission");
		FakeResponse response = post("/", "{\"name\":\"alice\",\"role\":\"" + Roles.VIEW + "\"}",
			SharingFixture.ALICE, parameters);

		assertEquals("A seat nobody sits in does not make alice dispensable.",
			HttpServletResponse.SC_CONFLICT, response.status());
		assertEquals(AuthService.LAST_ADMIN, errorMessage(response));
	}

	public void testAPendingUserIsNotNamedAtStartUp() throws Exception {
		created(invite(SharingFixture.ALICE, Roles.VIEW, "", "", "Grandma"));
		restartServer();

		assertNull("#86's naming is for the administrator's seat, not for an invitation.",
			servlet().auth().nameNamelessOwner("owner"));
		assertEquals("Still waiting for somebody.", 1, stored().size());
		assertTrue(stored().get(0).isPending());
	}

	// --- The users list. ---

	public void testThePendingUserIsListedAsSuch() throws Exception {
		InvitationCreated created = created(invite(SharingFixture.ALICE, Roles.VIEW, "", "", "Grandma"));

		UserEntry entry = pending(users(SharingFixture.ALICE), created.getInvitation().getId());
		assertNotNull("The administrator sees whom they are waiting for.", entry);
		assertTrue(entry.isPending());
		assertEquals("", entry.getName());
		assertEquals("Grandma", entry.getRecipient());
		assertEquals("alice", entry.getInvitedBy());
		assertEquals(0, entry.getDevices());

		accept(created.getToken(), "frank");
		UserEntry joined = named(users(SharingFixture.ALICE), "frank");
		assertNotNull(joined);
		assertFalse("They are here now.", joined.isPending());
		assertEquals("The memento stays beside the name.", "Grandma", joined.getRecipient());
	}

	// --- The library of the build before. ---

	public void testAnInvitationStoreOfTheBuildBeforeIsCarriedOver() throws Exception {
		// Written by the store of issue #52, exactly as the current master writes it.
		InvitationStore old = new InvitationStore(_base);
		String live = old.create(Roles.EDIT, Clearances.ALL, true, "alice", "Uncle Bob",
			Instant.now().plus(Duration.ofDays(3)).toString()).getToken();
		String stale = old.create(Roles.VIEW, "alice", "Gone", Instant.now().minus(Duration.ofDays(2))
			.toString()).getToken();
		InvitationStore.Issued withdrawn = old.create(Roles.VIEW, "alice", "Sorry", "");
		old.revoke(withdrawn.getInvitation().getId());
		restartServer();

		// Reading the server is what migrates; the file is set aside, never deleted.
		assertEquals(HttpServletResponse.SC_OK, authOf(live).status());
		assertEquals("The link that was sent last week still works.", Roles.EDIT,
			auth(authOf(live)).getInvitation().getRole());
		assertEquals("Uncle Bob", auth(authOf(live)).getInvitation().getNote());
		assertEquals(HttpServletResponse.SC_OK, accept(live, "frank").status());

		assertEquals("A dead invitation is a record of something that is over.",
			HttpServletResponse.SC_UNAUTHORIZED, accept(stale, "gina").status());
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, accept(withdrawn.getToken(), "gina").status());

		Path file = _base.resolve(UserStore.DIRECTORY_NAME).resolve(InvitationStore.FILE_NAME);
		assertFalse("The store is gone from where it was.", Files.exists(file));
		assertTrue("...and set aside, never deleted.", retired().contains("invitations.json"));
	}

	// --- Helpers. ---

	private String retired() throws Exception {
		Path retired = _base.resolve(UserStore.DIRECTORY_NAME).resolve("retired");
		StringBuilder result = new StringBuilder();
		if (Files.isDirectory(retired)) {
			Files.walk(retired).forEach(path -> result.append(path.getFileName()).append('\n'));
		}
		return result.toString();
	}

	private UserList users(String token) throws Exception {
		FakeResponse response = get("/", "users", token);
		assertEquals(HttpServletResponse.SC_OK, response.status());
		return UserList.readUserList(reader(body(response)));
	}

	private static UserEntry pending(UserList users, String invitation) {
		for (UserEntry entry : users.getUsers()) {
			if (invitation.equals(entry.getInvitation()) && entry.isPending()) {
				return entry;
			}
		}
		return null;
	}

	private static UserEntry named(UserList users, String name) {
		for (UserEntry entry : users.getUsers()) {
			if (name.equals(entry.getName())) {
				return entry;
			}
		}
		return null;
	}
}
