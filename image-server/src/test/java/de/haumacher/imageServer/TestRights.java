/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.AuthService.Caller;
import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.Subjects;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.auth.UserStore.Device;
import de.haumacher.imageServer.auth.UserStore.User;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Test case for {@link AuthService#rights(Caller, PathInfo)}, the one method that decides what a
 * caller may do where (issue #49).
 */
@SuppressWarnings("javadoc")
public class TestRights extends TestCase {

	private Path _base;

	private final java.util.List<Path> _extra = new java.util.ArrayList<>();

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-rights-test");
		SharingFixture.create(_base);
	}

	@Override
	protected void tearDown() throws Exception {
		_extra.add(_base);
		for (Path folder : _extra) {
			if (folder != null) {
				try (Stream<Path> files = Files.walk(folder)) {
					files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
				}
			}
		}
		_extra.clear();
		super.tearDown();
	}

	// --- The owner of a space holds everything in it. ---

	public void testTheOwnerHoldsEveryRightInHerSpace() throws Exception {
		AuthService auth = auth(AuthMode.WRITES);
		Caller alice = caller(auth, SharingFixture.ALICE);

		assertEquals(Rights.ALL, auth.rights(alice, in("alice", "")));
		assertEquals(Rights.ALL, auth.rights(alice, in("alice", SharingFixture.YEAR)));
		assertEquals(Rights.ALL, auth.rights(alice, in("alice", SharingFixture.ZOO)));
		assertEquals(Rights.ALL, auth.rights(alice, in("alice", SharingFixture.PRIVATE)));
		assertEquals("The owner sees their own private images.", Privacy.PRIVATE,
			auth.clearance(alice, in("alice", SharingFixture.ZOO)));
	}

	public void testTheAdminHoldsNothingInSomebodyElsesSpace() throws Exception {
		AuthService auth = auth(AuthMode.WRITES);
		Caller alice = caller(auth, SharingFixture.ALICE);

		assertEquals("The admin manages the server; they do not browse everybody's albums.",
			Rights.NONE, auth.rights(alice, in("carol", SharingFixture.CAROLS_ALBUM)));
		assertTrue("The admin does manage the grants on any space.",
			auth.mayManageGrants(alice, in("carol", "")));
	}

	// --- A grant is inherited downwards and reaches no further. ---

	public void testBobHoldsWhatHeWasGrantedAndInheritsItDownwards() throws Exception {
		AuthService auth = auth(AuthMode.WRITES);
		Caller bob = caller(auth, SharingFixture.BOB);

		assertEquals(rights(Rights.VIEW, Rights.DOWNLOAD), auth.rights(bob, in("alice", SharingFixture.YEAR)));
		assertEquals("A grant covers everything below its folder.", rights(Rights.VIEW, Rights.DOWNLOAD),
			auth.rights(bob, in("alice", SharingFixture.YEAR + "/2024-07 Lake")));
		assertTrue(auth.mayView(bob, in("alice", SharingFixture.ZOO)));
		assertTrue(auth.mayDownload(bob, in("alice", SharingFixture.ZOO)));
		assertFalse(auth.mayEdit(bob, in("alice", SharingFixture.ZOO)));
		assertFalse("Bob may not add to the year folder itself.",
			auth.mayContribute(bob, in("alice", SharingFixture.YEAR)));
		assertEquals("Two grants that name him are one union: his own and the one of his group.",
			rights(Rights.VIEW, Rights.DOWNLOAD, Rights.CONTRIBUTE), auth.rights(bob, in("alice", SharingFixture.ZOO)));
	}

	public void testBobHoldsNothingBesideTheGrantedFolder() throws Exception {
		AuthService auth = auth(AuthMode.WRITES);
		Caller bob = caller(auth, SharingFixture.BOB);

		assertEquals(Rights.NONE, auth.rights(bob, in("alice", SharingFixture.PRIVATE)));
		assertEquals("A grant on a folder says nothing about the folder above it.",
			Rights.NONE, auth.rights(bob, in("alice", "")));
	}

	public void testBobHoldsEverythingInHisOwnSpace() throws Exception {
		AuthService auth = auth(AuthMode.WRITES);
		assertEquals(Rights.ALL, auth.rights(caller(auth, SharingFixture.BOB), in("bob", "")));
	}

	public void testBobIsAMemberOfAlicesAlbumAndNotItsOwner() throws Exception {
		AuthService auth = auth(AuthMode.WRITES);

		assertEquals(Privacy.MEMBERS, auth.clearance(caller(auth, SharingFixture.BOB), in("alice", SharingFixture.ZOO)));
	}

	// --- A group names its members wherever a user can be named. ---

	public void testCarolContributesThroughTheGroup() throws Exception {
		AuthService auth = auth(AuthMode.WRITES);
		Caller carol = caller(auth, SharingFixture.CAROL);

		assertEquals("Contributing implies looking.", rights(Rights.VIEW, Rights.CONTRIBUTE),
			auth.rights(carol, in("alice", SharingFixture.ZOO)));
		assertTrue(auth.mayContribute(carol, in("alice", SharingFixture.ZOO)));
		assertFalse("She may add to the album, not rearrange it.",
			auth.mayEdit(carol, in("alice", SharingFixture.ZOO)));
		assertFalse("The group was granted the album, not the year it lies in.",
			auth.mayView(carol, in("alice", SharingFixture.YEAR)));
	}

	// --- Anonymous names everybody, and only where it was granted. ---

	public void testTheAnonymousCallerSeesWhatWasOpenedToEverybody() throws Exception {
		AuthService auth = auth(AuthMode.WRITES);
		Caller anonymous = caller(auth, null);

		assertEquals(Collections.singleton(Rights.VIEW), auth.rights(anonymous, in("alice", SharingFixture.PUBLIC)));
		assertEquals(Rights.NONE, auth.rights(anonymous, in("alice", SharingFixture.YEAR)));
		assertEquals(Rights.NONE, auth.rights(anonymous, in("alice", "")));
		assertEquals("The base folder of a migrated library belongs to nobody.",
			Rights.NONE, auth.rights(anonymous, in(null, "")));
		assertEquals("Anonymous is public, whatever it was granted.", Privacy.PUBLIC,
			auth.clearance(anonymous, in("alice", SharingFixture.PUBLIC)));
	}

	public void testASignedInCallerIsAnonymousToo() throws Exception {
		AuthService auth = auth(AuthMode.WRITES);

		assertTrue("'anonymous' names everybody, signed in or not.",
			auth.mayView(caller(auth, SharingFixture.DAVE), in("alice", SharingFixture.PUBLIC)));
	}

	// --- Somebody nothing was granted to holds nothing. ---

	public void testDaveHoldsNothingOfAlices() throws Exception {
		AuthService auth = auth(AuthMode.WRITES);
		Caller dave = caller(auth, SharingFixture.DAVE);

		assertEquals(Rights.NONE, auth.rights(dave, in("alice", SharingFixture.YEAR)));
		assertEquals(Rights.NONE, auth.rights(dave, in("alice", SharingFixture.ZOO)));
	}

	public void testTheGuestHoldsNothingAnywhere() throws Exception {
		AuthService auth = auth(AuthMode.WRITES);
		Caller eve = caller(auth, SharingFixture.EVE);

		assertEquals(Rights.NONE, auth.rights(eve, in("alice", SharingFixture.YEAR)));
		assertEquals(Rights.NONE, auth.rights(eve, in("alice", SharingFixture.ZOO)));
		assertEquals("A guest has no space of their own, and the base folder is nobody's.",
			Rights.NONE, auth.rights(eve, in(null, "")));
		assertTrue("What a guest was granted, a guest holds.",
			auth.mayView(eve, in("alice", SharingFixture.PUBLIC)));
	}

	public void testAnUnknownTokenHoldsNothing() throws Exception {
		AuthService auth = auth(AuthMode.WRITES);

		assertEquals(Rights.NONE, auth.rights(caller(auth, "no-such-token"), in("alice", SharingFixture.PUBLIC)));
	}

	// --- Without authentication there is nobody to refuse. ---

	public void testWithoutAuthenticationEverybodyHoldsEverything() throws Exception {
		AuthService auth = auth(AuthMode.OFF);
		Caller anonymous = caller(auth, null);

		assertEquals(Rights.ALL, auth.rights(anonymous, in("alice", SharingFixture.PRIVATE)));
		assertEquals(Rights.ALL, auth.rights(anonymous, in(null, "")));
		assertEquals(Privacy.PRIVATE, auth.clearance(anonymous, in("alice", SharingFixture.PRIVATE)));
	}

	// --- The implications are applied once, where the rights are computed. ---

	public void testTheImplicationsBetweenTheRights() {
		assertEquals(Rights.ALL, Rights.closure(Collections.singletonList(Rights.EDIT)));
		assertEquals(rights(Rights.VIEW, Rights.CONTRIBUTE), Rights.closure(Collections.singletonList(Rights.CONTRIBUTE)));
		assertEquals(rights(Rights.VIEW, Rights.DOWNLOAD), Rights.closure(Collections.singletonList(Rights.DOWNLOAD)));
		assertEquals(Collections.singleton(Rights.VIEW), Rights.closure(Collections.singletonList(Rights.VIEW)));
		assertEquals(Rights.NONE, Rights.closure(Collections.<String> emptyList()));
		assertEquals("A right this build does not know is dropped, never honoured.",
			Rights.NONE, Rights.closure(Collections.singletonList("teleport")));
	}

	// --- A resolved path is spelled back in the coordinates of the request. ---

	public void testALocationIsSpelledBackAsTheRequestSpelledIt() throws Exception {
		AuthService auth = auth(AuthMode.WRITES);
		Caller bob = caller(auth, SharingFixture.BOB);

		AuthService.Location own = auth.resolve(bob, _base, SharingFixture.YEAR);
		assertEquals("", own.getPrefix());
		assertEquals(SharingFixture.YEAR, own.spell());
		assertEquals("2025/2025-01-01 Trip", own.spell("2025/2025-01-01 Trip"));
		assertEquals("bob", own.getOwner());

		AuthService.Location canonical = auth.resolve(bob, _base, "~alice/" + SharingFixture.YEAR);
		assertEquals("~alice/", canonical.getPrefix());
		assertEquals("~alice/" + SharingFixture.YEAR, canonical.spell());
		assertEquals("A client following this answer must land in alice's library, not in its own.",
			"~alice/2025/2025-01-01 Trip", canonical.spell("2025/2025-01-01 Trip"));
		assertEquals("alice", canonical.getOwner());
		assertEquals(SharingFixture.YEAR, canonical.getOwnerPath());

		AuthService.Location root = auth.resolve(bob, _base, "~alice");
		assertEquals("~alice/", root.getPrefix());
		assertEquals("The space root of another library is spelled without a path.", "~alice/", root.spell());
		assertEquals("", root.getOwnerPath());
	}

	// --- Which library a path lies in is decided by where it lies. ---

	public void testTheSpaceOfAPathIsTheDeepestOneContainingIt() throws Exception {
		AuthService auth = auth(AuthMode.WRITES);

		assertEquals("alice", auth.spaceOf(in("alice", SharingFixture.ZOO)).getOwner());
		assertEquals(SharingFixture.ZOO, auth.spaceOf(in("alice", SharingFixture.ZOO)).getPath());
		assertEquals("A migrated library's base folder belongs to nobody.", null, auth.spaceOf(in(null, "")));
	}

	public void testAGrantOfTheUnmigratedOwnerStopsAtAMembersSpace() throws Exception {
		Path base = unmigrated();
		AuthService auth = new AuthService(AuthMode.WRITES, SharingFixture.SECRET, base);
		auth.getGrants().grant("alice", "", Subjects.user("carol"), Collections.singletonList(Rights.EDIT));

		PathInfo alices = new PathInfo(base, Paths.get("Public"));
		PathInfo bobs = new PathInfo(base, Paths.get("bob/Secret"));

		assertEquals("The deepest space wins: this folder is bob's, wherever it lies.",
			"bob", auth.spaceOf(bobs).getOwner());
		assertEquals("Secret", auth.spaceOf(bobs).getPath());
		assertEquals("alice", auth.spaceOf(alices).getOwner());

		Caller carol = caller(auth, SharingFixture.CAROL);
		assertEquals("What alice owns, alice may share.", Rights.ALL, auth.rights(carol, alices));
		assertEquals("What she does not own, she cannot share.", Rights.NONE, auth.rights(carol, bobs));
	}

	public void testTheUnmigratedOwnerKeepsReachingHerWholeBaseFolder() throws Exception {
		Path base = unmigrated();
		AuthService auth = new AuthService(AuthMode.WRITES, SharingFixture.SECRET, base);
		Caller alice = caller(auth, SharingFixture.ALICE);

		assertEquals("Until the library is migrated, the base folder is her tree, as before issue #45.",
			Rights.ALL, auth.rights(alice, new PathInfo(base, Paths.get("bob/Secret"))));
		assertEquals(Privacy.PRIVATE, auth.clearance(alice, new PathInfo(base, Paths.get("bob/Secret"))));

		Caller bob = caller(auth, SharingFixture.BOB);
		assertEquals("Bob reaches his own space as its owner.", Rights.ALL,
			auth.rights(bob, new PathInfo(base.resolve("bob"), Paths.get("Secret"))));
		assertEquals("And nothing of hers.", Rights.NONE,
			auth.rights(bob, new PathInfo(base, Paths.get("Public"))));
	}

	// --- Helpers. ---

	/**
	 * A library that was never migrated: alice owns the base folder, bob and carol have spaces
	 * inside it.
	 */
	private Path unmigrated() throws Exception {
		Path base = Files.createTempDirectory("valbum-rights-unmigrated");
		_extra.add(base);
		UserStore users = new UserStore(base);
		User alice = users.nameOwner("alice");
		alice.addDevice(new Device("Phone", UserStore.hash(SharingFixture.ALICE), Instant.now().toString()));
		users.addUser(member("bob", SharingFixture.BOB));
		users.addUser(member("carol", SharingFixture.CAROL));
		users.store();

		SharingFixture.album(base, "Public", "Public",
			"[\"ImagePart\",{\"name\":\"open.jpg\",\"width\":4,\"height\":3}]", "open.jpg");
		SharingFixture.album(base, "bob/Secret", "Secret",
			"[\"ImagePart\",{\"name\":\"secret.jpg\",\"width\":4,\"height\":3}]", "secret.jpg");
		Files.createDirectories(base.resolve("carol"));
		return base;
	}

	private static User member(String name, String token) {
		User result = new User(name, Roles.MEMBER, name, Instant.now().toString());
		result.addDevice(new Device(name + "'s device", UserStore.hash(token), Instant.now().toString()));
		return result;
	}


	private AuthService auth(AuthMode mode) {
		return new AuthService(mode, SharingFixture.SECRET, _base);
	}

	private Caller caller(AuthService auth, String token) {
		java.util.Map<String, String> headers = new java.util.HashMap<>();
		if (token != null) {
			headers.put("Authorization", "Bearer " + token);
		}
		return auth.caller(TestImageServletPut.request("/", null, new byte[0], headers, Collections.emptyMap()));
	}

	/** The path of the given folder in the given user's space; a <code>null</code> space is the base folder. */
	private PathInfo in(String space, String path) {
		Path root = space == null ? _base : _base.resolve(space);
		return path.isEmpty() ? new PathInfo(root) : new PathInfo(root, Paths.get(path));
	}

	private static Set<String> rights(String... names) {
		return new LinkedHashSet<>(Arrays.asList(names));
	}
}
