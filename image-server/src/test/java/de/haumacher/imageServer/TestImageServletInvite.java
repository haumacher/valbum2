/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.InvitationStore;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.auth.UserStore.Device;
import de.haumacher.imageServer.auth.UserStore.User;
import de.haumacher.imageServer.shared.model.AuthInfo;
import de.haumacher.imageServer.shared.model.InvitationCreated;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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

	public void testTheAdminInvitesAViewer() throws Exception {
		FakeResponse response = invite(SharingFixture.ALICE, Roles.VIEW, "", "Uncle Bob");

		InvitationCreated created = created(response);
		assertEquals(Roles.VIEW, created.getInvitation().getRole());
		assertEquals("alice", created.getInvitation().getInvitedBy());
		assertEquals("Uncle Bob", created.getInvitation().getNote());
		assertFalse("An invitation always has a lifetime.", created.getInvitation().getExpires().isEmpty());
		assertEquals("/valbum/" + InvitationStore.URL_SEGMENT + "/" + created.getToken() + "/", created.getUrl());
		assertFalse("The token travels back exactly once.", created.getToken().isEmpty());

		AuthService.Invited stored = stored(created.getInvitation().getId());
		assertEquals("alice", stored.getUser().getInvitedBy());
		assertEquals(UserStore.hash(created.getToken()), stored.getCode().getCodeHash());
		assertTrue("An invitation is a pending user (issue #89).", stored.isPending());
	}

	public void testTheAdminInvitesAnEditor() throws Exception {
		InvitationCreated created = created(invite(SharingFixture.ALICE, Roles.EDIT));

		assertEquals(Roles.EDIT, created.getInvitation().getRole());
	}

	public void testAnInvitationWithoutARoleInvitesAViewer() throws Exception {
		InvitationCreated created = created(invite(SharingFixture.ALICE, ""));

		assertEquals(Roles.VIEW, created.getInvitation().getRole());
	}

	public void testNobodyIsInvitedAsAnAdmin() throws Exception {
		FakeResponse response = invite(SharingFixture.ALICE, Roles.ADMIN);

		assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status());
		assertEquals(AuthService.INVITATION_ADMIN_REFUSED, errorMessage(response));
		assertTrue("A refused invitation records nothing.", stored().isEmpty());
	}



	public void testAnAnonymousCallerSeesNoInvitations() throws Exception {
		FakeResponse response = invitations(null);

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status());
	}


	// --- The invitation as a bearer. ---

	public void testAnInvitationTokenSaysWhoInvitedAndAsWhat() throws Exception {
		InvitationCreated created = created(invite(SharingFixture.ALICE, Roles.EDIT, "", "Come and look"));

		AuthInfo info = auth(authOf(created.getToken()));

		assertEquals(Roles.EDIT, info.getInvitation().getRole());
		assertEquals("alice", info.getInvitation().getInvitedBy());
		assertEquals("Come and look", info.getInvitation().getNote());
		assertEquals(created.getInvitation().getExpires(), info.getInvitation().getExpires());
		assertEquals("An invitation is no login.", "", info.getUserName());
		assertEquals("", info.getRole());
		assertFalse(info.isWriteAllowed());
	}

	public void testAnInvitationTokenIsAnonymousEverywhereElse() throws Exception {
		InvitationCreated created = created(invite(SharingFixture.ALICE, Roles.EDIT));

		// An invitation is no login: on every endpoint but ?type=auth its holder is anonymous, so
		// they see exactly what an anonymous caller of this space sees and nothing more.
		AuthInfo info = AuthInfo.readAuthInfo(reader(body(get("/", "auth", created.getToken()))));
		assertEquals("", info.getUserName());
		assertEquals("", info.getRole());
		assertNotNull("The one thing the server says about the token it was handed.",
			info.getInvitation());
	}

	public void testAnExpiredInvitationIsGoneAtTheAuthEndpoint() throws Exception {
		String token = issue(Roles.EDIT, "alice",
			java.time.Instant.now().minus(java.time.Duration.ofDays(1)).toString());

		FakeResponse response = authOf(token);

		assertEquals(HttpServletResponse.SC_GONE, response.status());
		assertEquals(AuthService.INVITATION_EXPIRED, errorMessage(response));
	}


	public void testTheNewMemberCanUseTheirTokenAtOnce() throws Exception {
		InvitationCreated created = created(invite(SharingFixture.ALICE, Roles.EDIT));
		String token = paired(accept(created.getToken(), "frank")).getToken();

		assertEquals(HttpServletResponse.SC_OK, get("/", "json", token).status());
	}

	public void testASingleUseTokenIsUsedUp() throws Exception {
		InvitationCreated created = created(invite(SharingFixture.ALICE, Roles.EDIT));
		assertEquals(HttpServletResponse.SC_OK, accept(created.getToken(), "frank").status());

		FakeResponse second = accept(created.getToken(), "grace");

		assertEquals(HttpServletResponse.SC_GONE, second.status());
		assertEquals(AuthService.INVITATION_USED, errorMessage(second));
		assertNull("The second name must never be created.", new UserStore(_base).getUser("grace"));
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
			new AuthService(AuthMode.WRITES, base));
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
