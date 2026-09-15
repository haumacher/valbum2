/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.Clearances;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.UserStore;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * What became of the sharing of Phase 3, see issue #83.
 *
 * <p>
 * Grants, groups, links into somebody's tree and guest promotion are gone. Their endpoints answer
 * <code>410 Gone</code> for one release with a message naming what does the job now, so that an app
 * that was not updated says something useful instead of failing silently. A library written by the
 * build before reads unchanged: its roles are translated, never rewritten.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestRetired extends ShareTestCase {

	// --- The endpoints that are gone. ---

	public void testTheGrantEndpointsAreGone() throws Exception {
		assertRetired(get("/", "grants", SharingFixture.ALICE), ImageServlet.RETIRED_GRANTS);
		assertRetired(action("grant", SharingFixture.ALICE), ImageServlet.RETIRED_GRANTS);
		assertRetired(action("revoke", SharingFixture.ALICE), ImageServlet.RETIRED_GRANTS);
	}

	public void testTheGroupEndpointsAreGone() throws Exception {
		assertRetired(get("/", "groups", SharingFixture.ALICE), ImageServlet.RETIRED_GROUPS);
		assertRetired(action("group", SharingFixture.ALICE), ImageServlet.RETIRED_GROUPS);
		assertRetired(action("ungroup", SharingFixture.ALICE), ImageServlet.RETIRED_GROUPS);
		assertRetired(action("regroup", SharingFixture.ALICE), ImageServlet.RETIRED_GROUPS);
	}

	public void testTheLinkEndpointsAreGone() throws Exception {
		assertRetired(get("/", "links", SharingFixture.ALICE), ImageServlet.RETIRED_LINKS);
		assertRetired(action("unlink", SharingFixture.ALICE), ImageServlet.RETIRED_LINKS);
	}

	public void testThePromotionEndpointIsGone() throws Exception {
		assertRetired(action("promote", SharingFixture.ALICE), ImageServlet.RETIRED_PROMOTE);
	}

	/** A retired endpoint says the same to everybody: it is gone, whoever asks. */
	public void testARetiredEndpointSaysTheSameToEverybody() throws Exception {
		for (String token : new String[] { SharingFixture.ALICE, SharingFixture.DAVE, null }) {
			assertRetired(get("/", "grants", token), ImageServlet.RETIRED_GRANTS);
		}
	}

	/** What is not retired keeps working. */
	public void testWhatIsKeptStillAnswers() throws Exception {
		assertEquals(HttpServletResponse.SC_OK, get("/", "users", SharingFixture.ALICE).status());
		assertEquals(HttpServletResponse.SC_OK, get("/", "devices", SharingFixture.ALICE).status());
		assertEquals(HttpServletResponse.SC_OK, get("/", "invitations", SharingFixture.ALICE).status());
		assertEquals(HttpServletResponse.SC_OK, shares("/", SharingFixture.ALICE).status());
		assertEquals("Applying a folder's placement rule stays.", HttpServletResponse.SC_OK,
			place("/", SharingFixture.ALICE).status());
	}

	// --- A library written before issue #83. ---

	public void testTheOldRoleNamesAreReadAsPermissions() throws Exception {
		Path file = _base.resolve(UserStore.DIRECTORY_NAME).resolve(UserStore.FILE_NAME);
		Files.write(file, ("{\"version\":1,\"users\":["
			+ "{\"name\":\"haui\",\"role\":\"admin\",\"space\":\"haui\",\"created\":\"2026-09-06T10:11:12Z\","
			+ "\"devices\":[{\"id\":\"aaaaaaaa\",\"name\":\"Phone\",\"tokenHash\":\""
			+ UserStore.hash("old-admin") + "\",\"created\":\"2026-09-06T10:11:12Z\"}]},"
			+ "{\"name\":\"mem\",\"role\":\"member\",\"space\":\"mem\",\"created\":\"2026-09-06T10:11:12Z\","
			+ "\"devices\":[{\"id\":\"bbbbbbbb\",\"name\":\"Phone\",\"tokenHash\":\""
			+ UserStore.hash("old-member") + "\",\"created\":\"2026-09-06T10:11:12Z\"}]},"
			+ "{\"name\":\"gst\",\"role\":\"guest\",\"space\":\"\",\"created\":\"2026-09-06T10:11:12Z\","
			+ "\"devices\":[{\"id\":\"cccccccc\",\"name\":\"Phone\",\"tokenHash\":\""
			+ UserStore.hash("old-guest") + "\",\"created\":\"2026-09-06T10:11:12Z\"}]}]}")
				.getBytes(StandardCharsets.UTF_8));
		restartServer();

		UserStore users = new UserStore(_base);
		assertEquals("A member did everything with their albums.", Roles.EDIT,
			Roles.of(users.getUser("mem").getRole()));
		assertEquals(Clearances.ALL, users.getUser("mem").getClearance());
		assertTrue(users.getUser("mem").isShare());

		assertEquals("A guest looked at what was shared with them.", Roles.VIEW,
			Roles.of(users.getUser("gst").getRole()));
		assertEquals("What a guest saw was served at the members' level.", Clearances.NON_PRIVATE,
			users.getUser("gst").getClearance());
		assertFalse(users.getUser("gst").isShare());

		// And the tokens they were issued keep working, with exactly that permission.
		assertEquals(Roles.EDIT, auth(get("/", "auth", "old-member")).getRole());
		assertEquals(Clearances.ALL, auth(get("/", "auth", "old-member")).getClearance());
		assertEquals(Roles.VIEW, auth(get("/", "auth", "old-guest")).getRole());
		assertEquals(HttpServletResponse.SC_OK,
			put("/" + SharingFixture.ZOO + "/", "[\"AlbumInfo\",{\"title\":\"Mem\",\"parts\":[]}]",
				"old-member").status());
		assertEquals("A guest never changed anything.", HttpServletResponse.SC_FORBIDDEN,
			put("/" + SharingFixture.ZOO + "/", "[\"AlbumInfo\",{\"title\":\"Gst\",\"parts\":[]}]",
				"old-guest").status());
	}

	public void testTheStoredFileIsNotRewrittenBehindTheUsersBack() throws Exception {
		Path file = _base.resolve(UserStore.DIRECTORY_NAME).resolve(UserStore.FILE_NAME);
		String before = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
		assertTrue(before, before.contains("\"role\":\"admin\""));

		restartServer();
		assertEquals(HttpServletResponse.SC_OK, get("/", "json", SharingFixture.ALICE).status());

		assertEquals("Reading a library must not rewrite its user store.", before,
			new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
	}

	private void assertRetired(FakeResponse response, String message) throws Exception {
		assertEquals("Expected a 410 naming the replacement, got: " + response.body(),
			HttpServletResponse.SC_GONE, response.status());
		assertEquals(message, errorMessage(response));
		assertEquals("no-store", response.header("Cache-Control"));
	}

	private FakeResponse action(String action, String token) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", action);
		return post("/", "{}", token, parameters);
	}

}
