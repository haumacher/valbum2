/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.GrantStore;
import de.haumacher.imageServer.auth.GroupStore;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.auth.Subjects;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.shared.model.Grant;
import de.haumacher.imageServer.shared.model.GrantList;
import de.haumacher.imageServer.shared.model.Group;
import de.haumacher.imageServer.shared.model.GroupList;
import de.haumacher.imageServer.shared.model.MemberName;
import de.haumacher.imageServer.shared.model.RightName;
import de.haumacher.msgbuf.data.DataObject;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test case for the renaming of a group of issue #55, which is one operation over two stores: the
 * group and every grant made out to it.
 *
 * <p>
 * The library is the {@link SharingFixture} of issue #49: alice owns the group <code>family</code>
 * holding bob and carol, and the group may contribute to her zoo album.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestGroupRename extends LinkTestCase {

	// --- The rename itself. ---

	public void testTheOwnerRenamesHerGroup() throws Exception {
		FakeResponse response = regroup(SharingFixture.ALICE, "family", "clan");

		GroupList answer = GroupList.readGroupList(reader(body(response)));
		assertEquals(1, answer.getGroups().size());
		Group renamed = answer.getGroups().get(0);
		assertEquals("clan", renamed.getName());
		assertEquals("It is the same group under another name.", "alice", renamed.getOwner());
		assertEquals(Arrays.asList("bob", "carol"), members(renamed));

		GroupStore groups = new GroupStore(_base);
		assertNull("The old name is gone.", groups.getGroup("family"));
		assertNotNull("The new one is on disk.", groups.getGroup("clan"));
		assertEquals(Arrays.asList("bob", "carol"), new ArrayList<>(groups.getGroup("clan").getMembers()));
	}

	public void testEveryGrantMadeOutToTheGroupIsRewritten() throws Exception {
		regroup(SharingFixture.ALICE, "family", "clan");

		List<String> subjects = new ArrayList<>();
		for (GrantStore.Grant grant : new GrantStore(_base).getGrants()) {
			subjects.add(grant.getPath() + " -> " + grant.getSubject());
		}
		assertEquals("Only the grants naming the group changed.",
			Arrays.asList(SharingFixture.YEAR + " -> " + Subjects.user("bob"),
				SharingFixture.ZOO + " -> " + Subjects.group("clan"),
				SharingFixture.PUBLIC + " -> " + Subjects.ANONYMOUS),
			subjects);
	}

	public void testTheGrantListingShowsTheNewSubjectWithTheSameRights() throws Exception {
		regroup(SharingFixture.ALICE, "family", "clan");

		GrantList listed =
			GrantList.readGrantList(reader(body(get("/" + SharingFixture.ZOO + "/", "grants", SharingFixture.ALICE))));
		Grant onTheAlbum = null;
		for (Grant grant : listed.getGrants()) {
			if (grant.getPath().equals(SharingFixture.ZOO)) {
				onTheAlbum = grant;
			}
		}
		assertNotNull(listed.getGrants().toString(), onTheAlbum);
		assertEquals(Subjects.group("clan"), onTheAlbum.getSubject());
		assertEquals(Arrays.asList(Rights.CONTRIBUTE), rightNames(onTheAlbum));
	}

	public void testAMemberOfTheGroupKeepsExactlyTheRightsSheHad() throws Exception {
		List<String> before = rightsOf(folder(get("/~alice/" + SharingFixture.ZOO + "/", "json",
			SharingFixture.CAROL)));

		regroup(SharingFixture.ALICE, "family", "clan");

		List<String> after = rightsOf(folder(get("/~alice/" + SharingFixture.ZOO + "/", "json",
			SharingFixture.CAROL)));
		assertEquals("A better name takes nothing away.", before, after);
		assertTrue(before.toString(), before.contains(Rights.CONTRIBUTE));
	}

	public void testTheAdminRenamesSomebodyElsesGroup() throws Exception {
		assertEquals(HttpServletResponse.SC_OK, group(SharingFixture.CAROL, "friends", "bob").status());

		// The admin keeps the names of this server in order; a rename gives nobody a new right.
		FakeResponse response = regroup(SharingFixture.ALICE, "friends", "buddies");

		assertEquals(HttpServletResponse.SC_OK, response.status());
		GroupStore.Group renamed = new GroupStore(_base).getGroup("buddies");
		assertNotNull(renamed);
		assertEquals("It stays carol's group.", "carol", renamed.getOwner());
	}

	// --- What is refused. ---

	public void testAMemberMayNotRenameSomebodyElsesGroup() throws Exception {
		FakeResponse response = regroup(SharingFixture.BOB, "family", "clan");

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(AuthService.GROUP_REFUSED, errorMessage(response));
		assertNotNull("Nothing was renamed.", new GroupStore(_base).getGroup("family"));
		assertEquals(Subjects.group("family"), new GrantStore(_base).getGrants().get(1).getSubject());
	}

	public void testAnUnknownGroupIsRefused() throws Exception {
		FakeResponse response = regroup(SharingFixture.ALICE, "nobody", "somebody");

		assertEquals(HttpServletResponse.SC_NOT_FOUND, response.status());
		assertEquals(AuthService.GROUP_UNKNOWN, errorMessage(response));
	}

	public void testATakenNameIsRefused() throws Exception {
		assertEquals(HttpServletResponse.SC_OK, group(SharingFixture.ALICE, "friends", "dave").status());

		FakeResponse response = regroup(SharingFixture.ALICE, "family", "friends");

		assertEquals(HttpServletResponse.SC_CONFLICT, response.status());
		assertEquals(AuthService.groupNameTaken("friends"), errorMessage(response));
		assertNotNull("Neither group was touched.", new GroupStore(_base).getGroup("family"));
		assertEquals(Arrays.asList("dave"), new ArrayList<>(new GroupStore(_base).getGroup("friends").getMembers()));
	}

	public void testANameThatCannotBeAFolderIsRefused() throws Exception {
		FakeResponse response = regroup(SharingFixture.ALICE, "family", "../elsewhere");

		assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status());
		assertEquals(UserStore.NAME_REFUSED, errorMessage(response));

		assertEquals(HttpServletResponse.SC_BAD_REQUEST, regroup(SharingFixture.ALICE, "family", "").status());
		assertNotNull(new GroupStore(_base).getGroup("family"));
	}

	public void testAnAnonymousCallerRenamesNothing() throws Exception {
		FakeResponse response = regroup(null, "family", "clan");

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status());
		assertNotNull(new GroupStore(_base).getGroup("family"));
	}

	public void testAnUnreadableRequestIsRefusedWithTheReason() throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "regroup");
		FakeResponse response = post("/", "not json at all", SharingFixture.ALICE, parameters);

		assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status());
		assertEquals(ImageServlet.RENAME_UNREADABLE, errorMessage(response));
	}

	// --- Renaming a group to its own name, which is what a repair after a crash does. ---

	public void testRenamingToTheSameNameChangesNothing() throws Exception {
		FakeResponse response = regroup(SharingFixture.ALICE, "family", "family");

		assertEquals(HttpServletResponse.SC_OK, response.status());
		assertNotNull(new GroupStore(_base).getGroup("family"));
		assertEquals(Subjects.group("family"), new GrantStore(_base).getGrants().get(1).getSubject());
	}

	/**
	 * What a crash between the two writes leaves behind, and that renaming back repairs it.
	 *
	 * <p>
	 * The group is written first: a crash after that leaves the group under its new name while the
	 * grants still name the old one. Nothing leaks — a grant to a group nobody is in matches
	 * nobody — and renaming the group back makes the two agree again exactly as they did before.
	 * </p>
	 */
	public void testARenameThatOnlyReachedTheGroupStoreIsRepairedByRenamingBack() throws Exception {
		GroupStore groups = new GroupStore(_base);
		groups.rename(groups.getGroup("family"), "clan");
		restartServer();

		assertEquals("The grant matches nobody while the two disagree; nothing leaks, the album is "
			+ "simply not shared with the group any more.", HttpServletResponse.SC_FORBIDDEN,
			get("/~alice/" + SharingFixture.ZOO + "/", "json", SharingFixture.CAROL).status());

		assertEquals(HttpServletResponse.SC_OK, regroup(SharingFixture.ALICE, "clan", "family").status());

		assertNotNull(new GroupStore(_base).getGroup("family"));
		assertEquals(Subjects.group("family"), new GrantStore(_base).getGrants().get(1).getSubject());
		assertTrue("And the album is shared with the group again.",
			rightsOf(folder(get("/~alice/" + SharingFixture.ZOO + "/", "json", SharingFixture.CAROL)))
				.contains(Rights.CONTRIBUTE));
	}

	// --- Two grants that become one. ---

	public void testARenameOntoAGroupGrantedOnTheSameFolderMergesTheRights() throws Exception {
		GroupStore groups = new GroupStore(_base);
		groups.put("crew", "alice", Arrays.asList("dave"));
		new GrantStore(_base).grant("alice", SharingFixture.ZOO, Subjects.group("crew"),
			Arrays.asList(Rights.VIEW, Rights.DOWNLOAD));
		restartServer();

		// The group "family" is renamed onto "crew"'s name after "crew" is gone: one grant is left,
		// holding what both held.
		groups = new GroupStore(_base);
		groups.remove("crew");
		restartServer();
		assertEquals(HttpServletResponse.SC_OK, regroup(SharingFixture.ALICE, "family", "crew").status());

		List<GrantStore.Grant> onTheAlbum = new ArrayList<>();
		for (GrantStore.Grant grant : new GrantStore(_base).getGrants()) {
			if (grant.getPath().equals(SharingFixture.ZOO)) {
				onTheAlbum.add(grant);
			}
		}
		assertEquals("One grant per owner, path and subject, as always.", 1, onTheAlbum.size());
		assertEquals(Subjects.group("crew"), onTheAlbum.get(0).getSubject());
		assertEquals("Nobody lost a right.",
			new ArrayList<>(Arrays.asList(Rights.VIEW, Rights.DOWNLOAD, Rights.CONTRIBUTE)),
			new ArrayList<>(onTheAlbum.get(0).getRights()));
	}

	// --- Helpers. ---

	private FakeResponse regroup(String token, String name, String newName) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "regroup");
		return post("/", "{\"name\":\"" + name + "\",\"newName\":\"" + newName + "\"}", token, parameters);
	}

	private FakeResponse group(String token, String name, String... members) throws Exception {
		Group body = Group.create().setName(name);
		for (String member : members) {
			body.addMember(MemberName.create().setName(member));
		}
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "group");
		return post("/", json(body), token, parameters);
	}

	private static String json(DataObject object) throws IOException {
		StringWriter buffer = new StringWriter();
		try (JsonWriter out = new JsonWriter(new WriterAdapter(buffer))) {
			object.writeTo(out);
		}
		return buffer.toString();
	}

	private static List<String> members(Group group) {
		List<String> result = new ArrayList<>();
		for (MemberName member : group.getMembers()) {
			result.add(member.getName());
		}
		return result;
	}

	private static List<String> rightNames(Grant grant) {
		List<String> result = new ArrayList<>();
		for (RightName right : grant.getRights()) {
			result.add(right.getName());
		}
		return result;
	}
}
