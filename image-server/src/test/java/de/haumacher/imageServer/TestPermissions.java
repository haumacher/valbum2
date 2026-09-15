/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.Clearances;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.AuthInfo;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.UserList;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The permission model of issue #83, role by role: what each may do, and what each may see.
 *
 * <p>
 * One space with five callers, from {@link SharingFixture}: alice administers it, bob may add,
 * carol may change, dave may only look, eve may only look and sees nothing restricted. The same
 * question is asked of every endpoint, because the answer is the same everywhere in a space —
 * there are no per-album permissions any more.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestPermissions extends ShareTestCase {

	/** The album every test works on. */
	private static final String ZOO = "/" + SharingFixture.ZOO + "/";

	// --- What a role may do. ---

	public void testTheRightsOfEachRole() throws Exception {
		assertEquals(Arrays.asList("view", "download", "contribute", "edit"), rights(SharingFixture.ALICE));
		assertEquals("An editor may change what is there.",
			Arrays.asList("view", "download", "contribute", "edit"), rights(SharingFixture.CAROL));
		assertEquals("A contributor adds and never changes.",
			Arrays.asList("view", "download", "contribute"), rights(SharingFixture.BOB));
		assertEquals("A viewer looks and keeps what they see.",
			Arrays.asList("view", "download"), rights(SharingFixture.DAVE));
		assertEquals(Arrays.asList("view", "download"), rights(SharingFixture.EVE));
	}

	public void testTheSameRightsEverywhereInTheSpace() throws Exception {
		for (String folder : new String[] { "/", ZOO, "/" + SharingFixture.PRIVATE + "/" }) {
			assertEquals("The role answers for '" + folder + "' as for every other folder.",
				Arrays.asList("view", "download", "contribute"),
				rightsOf(folder(get(folder, "json", SharingFixture.BOB))));
		}
	}

	public void testLooking() throws Exception {
		for (String token : everybody()) {
			assertEquals("Everybody in this space may look.", HttpServletResponse.SC_OK,
				get(ZOO, "json", token).status());
		}
	}

	public void testTheThumbnailAndTheOriginal() throws Exception {
		for (String token : everybody()) {
			assertEquals(HttpServletResponse.SC_OK, get(ZOO + "public.jpg", "tn", token).status());
			assertEquals("Whoever may look may keep what they see.", HttpServletResponse.SC_OK,
				get(ZOO + "public.jpg", null, token).status());
		}
	}

	public void testUploading() throws Exception {
		assertEquals(HttpServletResponse.SC_OK, upload(ZOO, SharingFixture.ALICE, "alices.jpg").status());
		assertEquals(HttpServletResponse.SC_OK, upload(ZOO, SharingFixture.CAROL, "carols-new.jpg").status());
		assertEquals("A contributor is what the role is for.", HttpServletResponse.SC_OK,
			upload(ZOO, SharingFixture.BOB, "bobs.jpg").status());
		assertEquals("A viewer adds nothing.", HttpServletResponse.SC_FORBIDDEN,
			upload(ZOO, SharingFixture.DAVE, "daves.jpg").status());
		assertEquals(HttpServletResponse.SC_FORBIDDEN, upload(ZOO, SharingFixture.EVE, "eves.jpg").status());
	}

	public void testStoringASidecar() throws Exception {
		String album = "[\"AlbumInfo\",{\"title\":\"Renamed\",\"parts\":[]}]";
		assertEquals(HttpServletResponse.SC_OK, put(ZOO, album, SharingFixture.ALICE).status());
		assertEquals(HttpServletResponse.SC_OK, put(ZOO, album, SharingFixture.CAROL).status());
		assertEquals("A contributor never changes what is there.", HttpServletResponse.SC_FORBIDDEN,
			put(ZOO, album, SharingFixture.BOB).status());
		assertEquals(HttpServletResponse.SC_FORBIDDEN, put(ZOO, album, SharingFixture.DAVE).status());
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, put(ZOO, album, null).status());
	}

	public void testMovingOutOfAnAlbum() throws Exception {
		assertEquals(HttpServletResponse.SC_OK,
			move(ZOO, SharingFixture.PUBLIC, SharingFixture.CAROL, "members.jpg").status());
		assertEquals("A contributor moves nothing that is not their own.", HttpServletResponse.SC_FORBIDDEN,
			move(ZOO, SharingFixture.PUBLIC, SharingFixture.BOB, "private.jpg").status());
		assertEquals(HttpServletResponse.SC_FORBIDDEN,
			move(ZOO, SharingFixture.PUBLIC, SharingFixture.DAVE, "public.jpg").status());
	}

	public void testCreatingAnAlbum() throws Exception {
		String album = "[\"AlbumInfo\",{\"title\":\"New\",\"parts\":[]}]";
		assertEquals(HttpServletResponse.SC_OK, put("/Fresh/", album, SharingFixture.ALICE).status());
		assertEquals(HttpServletResponse.SC_OK, put("/Fresh2/", album, SharingFixture.CAROL).status());
		assertEquals("Creating an album changes the folder it lands in.", HttpServletResponse.SC_FORBIDDEN,
			put("/Fresh3/", album, SharingFixture.BOB).status());
		assertEquals(HttpServletResponse.SC_FORBIDDEN, put("/Fresh4/", album, SharingFixture.DAVE).status());
	}

	public void testCreatingAShareLink() throws Exception {
		String body = shareBody("Grandma", "", 0, 0, "view");
		assertEquals(HttpServletResponse.SC_OK, share(ZOO, SharingFixture.ALICE, body).status());
		assertEquals("The share flag, not the role, decides.", HttpServletResponse.SC_OK,
			share(ZOO, SharingFixture.BOB, body).status());
		assertEquals("An editor without the flag hands out no link.", HttpServletResponse.SC_FORBIDDEN,
			share(ZOO, SharingFixture.CAROL, body).status());
		assertEquals(HttpServletResponse.SC_FORBIDDEN, share(ZOO, SharingFixture.DAVE, body).status());
	}

	// --- What a clearance may see. ---

	public void testWhatEachClearanceSeesInTheListing() throws Exception {
		assertEquals("The administrator sees everything in their space.",
			Arrays.asList("public.jpg", "members.jpg", "private.jpg", "rejected.jpg"),
			names(SharingFixture.ALICE));
		assertEquals("Clearance 'all' sees everything, whatever the role.",
			Arrays.asList("public.jpg", "members.jpg", "private.jpg", "rejected.jpg"),
			names(SharingFixture.BOB));
		assertEquals("Clearance 'nonPrivate' stops below the private ones.",
			Arrays.asList("public.jpg", "members.jpg", "rejected.jpg"), names(SharingFixture.CAROL));
		assertEquals(Arrays.asList("public.jpg", "members.jpg", "rejected.jpg"), names(SharingFixture.DAVE));
		assertEquals("Clearance 'public' sees what nobody restricted.",
			Arrays.asList("public.jpg", "rejected.jpg"), names(SharingFixture.EVE));
	}

	public void testAClearanceRefusesTheImageItself() throws Exception {
		assertEquals(HttpServletResponse.SC_OK, get(ZOO + "private.jpg", "tn", SharingFixture.BOB).status());
		assertEquals("What a listing hides, an image request refuses.", HttpServletResponse.SC_FORBIDDEN,
			get(ZOO + "private.jpg", "tn", SharingFixture.CAROL).status());
		assertEquals(HttpServletResponse.SC_FORBIDDEN,
			get(ZOO + "members.jpg", "tn", SharingFixture.EVE).status());
		assertEquals(HttpServletResponse.SC_FORBIDDEN,
			get(ZOO + "private.jpg", null, SharingFixture.EVE).status());
	}

	public void testViewAsLowersOnesOwnClearance() throws Exception {
		assertEquals("'view as public' shows what an anonymous caller would see.",
			Arrays.asList("public.jpg", "rejected.jpg"),
			names(album(get(ZOO, "json", SharingFixture.ALICE, "public"))));
		assertEquals(Arrays.asList("public.jpg", "members.jpg", "rejected.jpg"),
			names(album(get(ZOO, "json", SharingFixture.ALICE, "members"))));
	}

	// --- The anonymous caller, and the space they are in. ---

	public void testAnOpenSpaceLetsAnAnonymousCallerLook() throws Exception {
		_authMode = AuthMode.WRITES;
		restartServer();

		assertEquals(HttpServletResponse.SC_OK, get(ZOO, "json", null).status());
		assertEquals("An anonymous caller sees the public images and no others.",
			Arrays.asList("public.jpg", "rejected.jpg"), names((String) null));
		assertEquals(HttpServletResponse.SC_OK, get(ZOO + "public.jpg", null, null).status());
		assertEquals("Looking, never changing.", HttpServletResponse.SC_UNAUTHORIZED,
			upload(ZOO, null, "anon.jpg").status());
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED,
			put(ZOO, "[\"AlbumInfo\",{\"title\":\"Mine\",\"parts\":[]}]", null).status());
	}

	public void testAClosedSpaceLetsNobodyIn() throws Exception {
		_authMode = AuthMode.ALL;
		restartServer();

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, get(ZOO, "json", null).status());
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, get(ZOO + "public.jpg", "tn", null).status());
		assertEquals("The space's own users are unaffected.", HttpServletResponse.SC_OK,
			get(ZOO, "json", SharingFixture.DAVE).status());
	}

	// --- Managing the users of the space. ---

	public void testOnlyTheAdminInvites() throws Exception {
		assertEquals(HttpServletResponse.SC_OK, invite(SharingFixture.ALICE).status());
		for (String token : new String[] { SharingFixture.CAROL, SharingFixture.BOB, SharingFixture.DAVE }) {
			assertEquals("Only an administrator invites into a space.", HttpServletResponse.SC_FORBIDDEN,
				invite(token).status());
		}
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, invite(null).status());
	}

	public void testOnlyTheAdminSeesTheUsers() throws Exception {
		assertEquals(HttpServletResponse.SC_OK, get("/", "users", SharingFixture.ALICE).status());
		assertEquals(HttpServletResponse.SC_FORBIDDEN, get("/", "users", SharingFixture.CAROL).status());
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, get("/", "users", null).status());
	}

	public void testTheAdminSetsAPermission() throws Exception {
		FakeResponse response = setPermission(SharingFixture.ALICE, "dave", Roles.EDIT, Clearances.ALL, true);
		assertEquals(body(response), HttpServletResponse.SC_OK, response.status());

		UserList users = UserList.readUserList(reader(body(response)));
		assertEquals(Roles.EDIT, entryOf(users, "dave").getRole());
		assertEquals(Clearances.ALL, entryOf(users, "dave").getClearance());
		assertTrue(entryOf(users, "dave").isMayShare());

		restartServer();
		assertEquals("What dave may do changed, everywhere at once.", HttpServletResponse.SC_OK,
			put(ZOO, "[\"AlbumInfo\",{\"title\":\"Daves\",\"parts\":[]}]", SharingFixture.DAVE).status());
	}

	public void testNobodyButTheAdminSetsAPermission() throws Exception {
		for (String token : new String[] { SharingFixture.CAROL, SharingFixture.BOB, SharingFixture.DAVE }) {
			assertEquals(HttpServletResponse.SC_FORBIDDEN,
				setPermission(token, "dave", Roles.EDIT, Clearances.ALL, true).status());
		}
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED,
			setPermission(null, "dave", Roles.EDIT, Clearances.ALL, true).status());
		assertEquals("Nothing was changed.", Roles.VIEW, new UserStore(_base).getUser("dave").getRole());
	}

	public void testTheLastAdminIsNotDemoted() throws Exception {
		FakeResponse refused =
			setPermission(SharingFixture.ALICE, "alice", Roles.EDIT, Clearances.ALL, true);
		assertEquals(HttpServletResponse.SC_CONFLICT, refused.status());
		assertEquals(de.haumacher.imageServer.auth.AuthService.LAST_ADMIN, errorMessage(refused));
		assertEquals(Roles.ADMIN, new UserStore(_base).getUser("alice").getRole());

		// With a second administrator there is a way back in, so the first may step down.
		assertEquals(HttpServletResponse.SC_OK,
			setPermission(SharingFixture.ALICE, "carol", Roles.ADMIN, Clearances.ALL, true).status());
		assertEquals(HttpServletResponse.SC_OK,
			setPermission(SharingFixture.ALICE, "alice", Roles.EDIT, Clearances.ALL, true).status());
		assertEquals(Roles.EDIT, new UserStore(_base).getUser("alice").getRole());
	}

	public void testAnUnknownRoleOrClearanceIsRefused() throws Exception {
		assertEquals(HttpServletResponse.SC_BAD_REQUEST,
			setPermission(SharingFixture.ALICE, "dave", "superuser", Clearances.ALL, false).status());
		assertEquals(HttpServletResponse.SC_BAD_REQUEST,
			setPermission(SharingFixture.ALICE, "dave", Roles.VIEW, "everything", false).status());
		assertEquals(HttpServletResponse.SC_NOT_FOUND,
			setPermission(SharingFixture.ALICE, "nobody", Roles.VIEW, Clearances.ALL, false).status());
	}

	public void testTheAdminRemovesAUser() throws Exception {
		assertEquals(HttpServletResponse.SC_OK, get(ZOO, "json", SharingFixture.DAVE).status());

		FakeResponse response = removeUser(SharingFixture.ALICE, "dave");
		assertEquals(body(response), HttpServletResponse.SC_OK, response.status());
		assertNull(new UserStore(_base).getUser("dave"));

		restartServer();
		assertEquals("A removed user's token stops working.", HttpServletResponse.SC_UNAUTHORIZED,
			get(ZOO, "json", SharingFixture.DAVE).status());
		assertEquals("Their photos stay where they are.", HttpServletResponse.SC_OK,
			get(ZOO, "json", SharingFixture.ALICE).status());
	}

	public void testNobodyButTheAdminRemovesAUser() throws Exception {
		assertEquals(HttpServletResponse.SC_FORBIDDEN, removeUser(SharingFixture.CAROL, "dave").status());
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, removeUser(null, "dave").status());
		assertNotNull(new UserStore(_base).getUser("dave"));
	}

	public void testTheLastAdminIsNotRemoved() throws Exception {
		FakeResponse refused = removeUser(SharingFixture.ALICE, "alice");
		assertEquals(HttpServletResponse.SC_CONFLICT, refused.status());
		assertNotNull(new UserStore(_base).getUser("alice"));
	}

	// --- What ?type=auth says about the caller. ---

	public void testTheAnswerNamesTheWholePermission() throws Exception {
		AuthInfo bob = auth(get("/", "auth", SharingFixture.BOB));
		assertEquals("bob", bob.getUserName());
		assertEquals(Roles.CONTRIBUTE, bob.getRole());
		assertEquals(Clearances.ALL, bob.getClearance());
		assertTrue(bob.isMayShare());

		AuthInfo dave = auth(get("/", "auth", SharingFixture.DAVE));
		assertEquals(Roles.VIEW, dave.getRole());
		assertEquals(Clearances.NON_PRIVATE, dave.getClearance());
		assertFalse(dave.isMayShare());

		AuthInfo anonymous = auth(get("/", "auth", null));
		assertEquals("", anonymous.getRole());
		assertEquals("", anonymous.getClearance());
		assertFalse(anonymous.isMayShare());
	}

	// --- Helpers. ---

	private static List<String> everybody() {
		return Arrays.asList(SharingFixture.ALICE, SharingFixture.CAROL, SharingFixture.BOB,
			SharingFixture.DAVE, SharingFixture.EVE);
	}

	private List<String> rights(String token) throws Exception {
		return rightsOf(folder(get(ZOO, "json", token)));
	}

	private List<String> names(String token) throws Exception {
		return names(album(get(ZOO, "json", token)));
	}

	private static List<String> names(AlbumInfo album) {
		List<String> result = new ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				result.add(((ImagePart) part).getName());
			}
		}
		return result;
	}

	private static de.haumacher.imageServer.shared.model.UserEntry entryOf(UserList users, String name) {
		for (de.haumacher.imageServer.shared.model.UserEntry entry : users.getUsers()) {
			if (entry.getName().equals(name)) {
				return entry;
			}
		}
		fail("No user '" + name + "' in the answer.");
		return null;
	}

	private FakeResponse invite(String token) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "invite");
		return post("/", "{\"role\":\"view\",\"expires\":\"\",\"note\":\"\"}", token, parameters);
	}

	private FakeResponse setPermission(String token, String name, String role, String clearance, boolean share)
			throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "set-permission");
		return post("/", "{\"name\":\"" + name + "\",\"role\":\"" + role + "\",\"clearance\":\"" + clearance
			+ "\",\"mayShare\":" + share + "}", token, parameters);
	}

	private FakeResponse removeUser(String token, String name) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "remove-user");
		return post("/", "{\"name\":\"" + name + "\"}", token, parameters);
	}

	static {
		// Nothing; the fixture is built by the base class.
		Collections.emptyList();
	}

}
