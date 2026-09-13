/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.GrantStore;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.auth.Subjects;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.Grant;
import de.haumacher.imageServer.shared.model.GrantList;
import de.haumacher.imageServer.shared.model.Group;
import de.haumacher.imageServer.shared.model.GroupList;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.MemberName;
import de.haumacher.imageServer.shared.model.MoveResult;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.RightName;
import de.haumacher.imageServer.shared.model.UserEntry;
import de.haumacher.imageServer.shared.model.UserList;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Test case for the enforcement of the grants of issue #49 on every endpoint, driven headlessly
 * through {@link ImageServlet} with the request and response fakes of {@link TestImageServletPut}.
 *
 * <p>
 * The library under test is {@link SharingFixture}.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestImageServletGrants extends TestCase {

	private static final String BOUNDARY = "----valbumGrantsTestBoundary";

	private static final String ALBUM_JSON = "[\"AlbumInfo\",{\"title\":\"Changed\",\"parts\":[]}]";

	private Path _base;

	private final List<ImageServlet> _servlets = new ArrayList<>();

	private ImageServlet _servlet;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-grants-test");
		SharingFixture.create(_base);
	}

	@Override
	protected void tearDown() throws Exception {
		for (ImageServlet servlet : _servlets) {
			servlet.destroy();
		}
		_servlets.clear();
		_servlet = null;
		if (_base != null) {
			try (Stream<Path> files = Files.walk(_base)) {
				files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
		super.tearDown();
	}

	// --- Reading another user's album through the canonical form. ---

	public void testBobListsTheYearHeWasGranted() throws Exception {
		FakeResponse response = get("/~alice/" + SharingFixture.YEAR + "/", "json", SharingFixture.BOB);

		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		assertEquals("The answer says what the caller may do here.",
			Arrays.asList(Rights.VIEW, Rights.DOWNLOAD), rightsOf(folder(response)));
	}

	public void testTheOwnerIsAnsweredEveryRight() throws Exception {
		FakeResponse response = get("/" + SharingFixture.ZOO + "/", "json", SharingFixture.ALICE);

		assertEquals(new ArrayList<>(Rights.ALL), rightsOf(folder(response)));
	}

	public void testBobFetchesAThumbnailAndAnOriginal() throws Exception {
		assertEquals("Looking is the view right.", HttpServletResponse.SC_OK,
			get("/~alice/" + SharingFixture.ZOO + "/public.jpg", "tn", SharingFixture.BOB).status());
		assertEquals("Taking a copy is the download right.", HttpServletResponse.SC_OK,
			get("/~alice/" + SharingFixture.ZOO + "/public.jpg", null, SharingFixture.BOB).status());
	}

	public void testCarolMayLookButNotDownload() throws Exception {
		assertEquals(HttpServletResponse.SC_OK,
			get("/~alice/" + SharingFixture.ZOO + "/public.jpg", "tn", SharingFixture.CAROL).status());

		FakeResponse original = get("/~alice/" + SharingFixture.ZOO + "/public.jpg", null, SharingFixture.CAROL);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, original.status());
		assertEquals(AuthService.DOWNLOAD_REFUSED, errorMessage(original));
	}

	public void testBobSeesTheMembersImagesButNotThePrivateOnes() throws Exception {
		AlbumInfo album = album(get("/~alice/" + SharingFixture.ZOO + "/", "json", SharingFixture.BOB));

		assertEquals("A grant makes a member of the album, not its owner.",
			Arrays.asList("public.jpg", "members.jpg"), names(album));

		FakeResponse refused = get("/~alice/" + SharingFixture.ZOO + "/private.jpg", "tn", SharingFixture.BOB);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, refused.status());
		assertEquals(ImageServlet.IMAGE_REFUSED, errorMessage(refused));
	}

	public void testBobMayNotChangeAlicesAlbum() throws Exception {
		FakeResponse response = put("/~alice/" + SharingFixture.ZOO + "/", ALBUM_JSON, SharingFixture.BOB);

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(AuthService.EDIT_REFUSED, errorMessage(response));
		assertFalse("Nothing was written.", read(_base.resolve("alice/" + SharingFixture.ZOO + "/index.json"))
			.contains("Changed"));
	}

	public void testBobMayNotUploadIntoTheYearHeMayLookAt() throws Exception {
		FakeResponse response = upload("/~alice/" + SharingFixture.YEAR + "/", SharingFixture.BOB, "b.jpg");

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(AuthService.CONTRIBUTE_REFUSED, errorMessage(response));
		assertFalse(_base.resolve("alice/" + SharingFixture.YEAR + "/b.jpg").toFile().exists());
	}

	public void testDaveHoldsNothingAndIsToldSo() throws Exception {
		FakeResponse response = get("/~alice/" + SharingFixture.YEAR + "/", "json", SharingFixture.DAVE);

		assertEquals("Signing in again would not help him.", HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(AuthService.VIEW_REFUSED, errorMessage(response));
	}

	// --- The contributor of a shared album. ---

	public void testCarolContributesToTheSharedAlbum() throws Exception {
		FakeResponse response = upload("/~alice/" + SharingFixture.ZOO + "/", SharingFixture.CAROL, "c.jpg");

		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		assertTrue("The photo lands in alice's album.",
			_base.resolve("alice/" + SharingFixture.ZOO + "/c.jpg").toFile().exists());
	}

	public void testCarolMayNotRearrangeTheSharedAlbum() throws Exception {
		FakeResponse response = put("/~alice/" + SharingFixture.ZOO + "/", ALBUM_JSON, SharingFixture.CAROL);

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(AuthService.EDIT_REFUSED, errorMessage(response));
	}

	public void testCarolMovesOneOfHerPhotosIntoTheSharedAlbum() throws Exception {
		MoveResult result = moveResult(move("/" + SharingFixture.CAROLS_ALBUM + "/",
			"~alice/" + SharingFixture.ZOO, SharingFixture.CAROL, "carols.jpg"));

		assertEquals("", result.getOutcomes().get(0).getMessage());
		assertEquals("carols.jpg", result.getOutcomes().get(0).getNewName());
		assertTrue(_base.resolve("alice/" + SharingFixture.ZOO + "/carols.jpg").toFile().exists());
		assertFalse(_base.resolve("carol/" + SharingFixture.CAROLS_ALBUM + "/carols.jpg").toFile().exists());
	}

	public void testCarolMayNotMoveAnythingOutOfTheSharedAlbum() throws Exception {
		FakeResponse response =
			move("/~alice/" + SharingFixture.ZOO + "/", "", SharingFixture.CAROL, "public.jpg");

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
		// Since issue #53 a contributor may take their own contribution back, so the refusal
		// names what is actually missing: alice's photo is not carol's to move.
		assertEquals(MoveService.CONTRIBUTION_REFUSED, errorMessage(response));
		assertTrue("The photo stayed where it was.",
			_base.resolve("alice/" + SharingFixture.ZOO + "/public.jpg").toFile().exists());
	}

	public void testTheMoveTargetMustTakeContributions() throws Exception {
		FakeResponse response = move("/" + SharingFixture.CAROLS_ALBUM + "/", "~alice/" + SharingFixture.PRIVATE,
			SharingFixture.CAROL, "carols.jpg");

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(MoveService.CONTRIBUTE_REFUSED, errorMessage(response));
	}

	public void testApplyingAPlacementRuleNeedsTheEditRight() throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "place");
		FakeResponse response = post("/~alice/" + SharingFixture.YEAR + "/", "{}", SharingFixture.CAROL, parameters);

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(MoveService.EDIT_REFUSED, errorMessage(response));
	}

	// --- The anonymous caller of a migrated library. ---

	public void testAnonymousSeesWhatWasOpenedToEverybody() throws Exception {
		FakeResponse response = get("/~alice/" + SharingFixture.PUBLIC + "/", "json", null);

		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		assertEquals(Collections.singletonList(Rights.VIEW), rightsOf(folder(response)));
		assertEquals(HttpServletResponse.SC_OK,
			get("/~alice/" + SharingFixture.PUBLIC + "/open.jpg", "tn", null).status());
	}

	public void testAnonymousMayNotDownloadTheOriginalOfAnOpenAlbum() throws Exception {
		FakeResponse response = get("/~alice/" + SharingFixture.PUBLIC + "/open.jpg", null, null);

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status());
		assertEquals("Bearer", response.header("WWW-Authenticate"));
	}

	public void testAnonymousIsStillRefusedEverythingElse() throws Exception {
		FakeResponse year = get("/~alice/" + SharingFixture.YEAR + "/", "json", null);
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, year.status());
		assertEquals(AuthService.LIBRARY_REFUSED, errorMessage(year));

		FakeResponse root = get("/~alice/", "json", null);
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, root.status());
		assertEquals(AuthService.LIBRARY_REFUSED, errorMessage(root));

		FakeResponse own = get("/", "json", null);
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, own.status());
		assertEquals(AuthService.LIBRARY_REFUSED, errorMessage(own));
	}

	// --- Names that are not there and names that may not be. ---

	public void testAnUnknownSpaceIsNotFound() throws Exception {
		FakeResponse response = get("/~nobody/", "json", SharingFixture.BOB);

		assertEquals(HttpServletResponse.SC_NOT_FOUND, response.status());
		assertEquals(AuthService.unknownSpace("nobody"), errorMessage(response));
	}

	public void testCreatingAReservedNameAtTheTopOfASpaceIsRefused() throws Exception {
		FakeResponse response = put("/~x/", ALBUM_JSON, SharingFixture.BOB);

		assertEquals(HttpServletResponse.SC_NOT_FOUND, response.status());
		assertEquals(AuthService.unknownSpace("x"), errorMessage(response));
		assertFalse(_base.resolve("bob").resolve("~x").toFile().exists());
	}

	public void testUploadingAReservedNameToTheTopOfASpaceIsRefused() throws Exception {
		FakeResponse response = upload("/", SharingFixture.BOB, "~evil.jpg");

		assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status());
		assertEquals(AuthService.homeNameRefused("~evil.jpg"), errorMessage(response));
		assertFalse(_base.resolve("bob").resolve("~evil.jpg").toFile().exists());
	}

	public void testMovingAReservedNameToTheTopOfASpaceIsRefused() throws Exception {
		Files.createDirectories(_base.resolve("carol/" + SharingFixture.CAROLS_ALBUM + "/~odd"));

		MoveResult result =
			moveResult(move("/" + SharingFixture.CAROLS_ALBUM + "/", "", SharingFixture.CAROL, "~odd"));

		assertEquals(AuthService.homeNameRefused("~odd"), result.getOutcomes().get(0).getMessage());
		assertFalse(_base.resolve("carol/~odd").toFile().exists());
	}

	// --- Every path the server answers is in the coordinates of the request. ---

	public void testACreatedAlbumIsAnsweredInTheCoordinatesOfTheRequest() throws Exception {
		grantAliceSpaceTo(Subjects.user("bob"), Rights.EDIT);

		FakeResponse own = put("/2025-01-01 Trip/", ALBUM_JSON, SharingFixture.BOB);
		assertEquals("2025-01-01 Trip", createResult(own).getPath());

		FakeResponse canonical = put("/~alice/2025-01-01 Trip/", ALBUM_JSON, SharingFixture.BOB);
		assertEquals("~alice/2025-01-01 Trip", createResult(canonical).getPath());
	}

	public void testTheRedirectOfAFolderKeepsTheLibraryItNames() throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "json");
		FakeResponse response = new FakeResponse();
		servlet().doGet(request("/~alice/" + SharingFixture.YEAR, null, "", SharingFixture.BOB, parameters),
			response.response());

		assertEquals(HttpServletResponse.SC_FOUND, response.status());
		assertTrue("The redirect must not drop the library the request named: " + response.header("Location"),
			response.header("Location").contains("/~alice/" + SharingFixture.YEAR + "/"));
	}

	public void testAFolderFiledAwayIsReportedBelowTheTargetTheRequestNamed() throws Exception {
		filedByYear("alice/" + SharingFixture.YEAR);
		grant("/" + SharingFixture.YEAR + "/", SharingFixture.ALICE, "grant", Subjects.user("carol"),
			Rights.CONTRIBUTE);
		Files.createDirectories(_base.resolve("carol/2025-03-01 Hike"));

		MoveResult result =
			moveResult(move("/", "~alice/" + SharingFixture.YEAR, SharingFixture.CAROL, "2025-03-01 Hike"));

		assertEquals("The path a filed folder is reported at is below the folder the request named.",
			"2025/2025-03-01 Hike", result.getOutcomes().get(0).getNewName());
		assertTrue(Files.isDirectory(_base.resolve("alice/" + SharingFixture.YEAR + "/2025/2025-03-01 Hike")));
	}

	public void testAPlacedFolderIsReportedBelowTheFolderTheRequestNamed() throws Exception {
		grantAliceSpaceTo(Subjects.user("bob"), Rights.EDIT);
		filedByYear("alice");
		Files.createDirectories(_base.resolve("alice/2025-03-01 Hike"));

		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "place");
		MoveResult result = moveResult(post("/~alice/", "{}", SharingFixture.BOB, parameters));

		String filed = null;
		for (int n = 0; n < result.getOutcomes().size(); n++) {
			if ("2025-03-01 Hike".equals(result.getOutcomes().get(n).getName())) {
				filed = result.getOutcomes().get(n).getNewName();
			}
		}
		assertEquals("2025/2025-03-01 Hike", filed);
	}

	// --- Managing the grants. ---

	public void testAliceGrantsListsAndRevokes() throws Exception {
		FakeResponse granted = grant("/" + SharingFixture.PRIVATE + "/", SharingFixture.ALICE, "grant",
			Subjects.user("dave"), Rights.EDIT);
		assertEquals(granted.body(), HttpServletResponse.SC_OK, granted.status());

		FakeResponse listed = get("/" + SharingFixture.PRIVATE + "/", "grants", SharingFixture.ALICE);
		GrantList grants = GrantList.readGrantList(reader(body(listed)));
		assertEquals(1, grants.getGrants().size());
		assertEquals("alice", grants.getGrants().get(0).getOwner());
		assertEquals(SharingFixture.PRIVATE, grants.getGrants().get(0).getPath());
		assertEquals(Subjects.user("dave"), grants.getGrants().get(0).getSubject());
		assertEquals(HttpServletResponse.SC_OK,
			get("/~alice/" + SharingFixture.PRIVATE + "/", "json", SharingFixture.DAVE).status());

		FakeResponse revoked = grant("/" + SharingFixture.PRIVATE + "/", SharingFixture.ALICE, "revoke",
			Subjects.user("dave"), null);
		assertEquals(HttpServletResponse.SC_OK, revoked.status());
		assertEquals("The grant is gone the moment it is revoked.", HttpServletResponse.SC_FORBIDDEN,
			get("/~alice/" + SharingFixture.PRIVATE + "/", "json", SharingFixture.DAVE).status());
	}

	public void testTheGrantsOfAFolderIncludeTheOnesAboveIt() throws Exception {
		GrantList grants = GrantList.readGrantList(
			reader(body(get("/" + SharingFixture.ZOO + "/", "grants", SharingFixture.ALICE))));

		assertEquals("What decides here is the grant on this folder and the ones above it.", 2,
			grants.getGrants().size());
		assertEquals(SharingFixture.ZOO, grants.getGrants().get(0).getPath());
		assertEquals(SharingFixture.YEAR, grants.getGrants().get(1).getPath());
	}

	public void testGrantingAgainReplacesTheRights() throws Exception {
		grant("/" + SharingFixture.YEAR + "/", SharingFixture.ALICE, "grant", Subjects.user("bob"), Rights.VIEW);

		GrantStore store = new GrantStore(_base);
		assertEquals(3, store.getGrants().size());
		assertEquals(Collections.singleton(Rights.VIEW),
			store.covering("alice", SharingFixture.YEAR).get(0).getRights());
	}

	public void testBobMayNotSeeOrChangeAlicesGrants() throws Exception {
		FakeResponse listed = get("/~alice/" + SharingFixture.YEAR + "/", "grants", SharingFixture.BOB);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, listed.status());
		assertEquals(AuthService.GRANTS_REFUSED, errorMessage(listed));

		FakeResponse granted = grant("/~alice/" + SharingFixture.YEAR + "/", SharingFixture.BOB, "grant",
			Subjects.user("dave"), Rights.EDIT);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, granted.status());
		assertEquals(AuthService.GRANTS_REFUSED, errorMessage(granted));
		assertEquals("Nothing was recorded.", 3, new GrantStore(_base).getGrants().size());
	}

	public void testTheAdminManagesTheGrantsOfEverySpace() throws Exception {
		FakeResponse granted = grant("/~carol/" + SharingFixture.CAROLS_ALBUM + "/", SharingFixture.ALICE, "grant",
			Subjects.user("dave"), Rights.VIEW);

		assertEquals(granted.body(), HttpServletResponse.SC_OK, granted.status());
		assertEquals("carol", new GrantStore(_base).covering("carol", SharingFixture.CAROLS_ALBUM).get(0).getOwner());
		assertEquals(HttpServletResponse.SC_OK,
			get("/~carol/" + SharingFixture.CAROLS_ALBUM + "/", "grants", SharingFixture.ALICE).status());
	}

	public void testAGrantToSomebodyWhoIsNotThereIsRefused() throws Exception {
		FakeResponse response = grant("/" + SharingFixture.YEAR + "/", SharingFixture.ALICE, "grant",
			Subjects.user("nobody"), Rights.VIEW);

		assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status());
		assertEquals(AuthService.unknownSubject(Subjects.user("nobody")), errorMessage(response));
		assertEquals(3, new GrantStore(_base).getGrants().size());
	}

	public void testAGrantOfARightThisServerDoesNotKnowIsRefused() throws Exception {
		FakeResponse response = grant("/" + SharingFixture.YEAR + "/", SharingFixture.ALICE, "grant",
			Subjects.user("dave"), "teleport");

		assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status());
		assertEquals(AuthService.unknownRight("teleport"), errorMessage(response));
	}

	public void testAnAnonymousCallerManagesNothing() throws Exception {
		FakeResponse response = grant("/~alice/" + SharingFixture.YEAR + "/", null, "grant",
			Subjects.user("dave"), Rights.VIEW);

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status());
	}

	// --- Managing the groups. ---

	public void testCarolCreatesAGroupAndListsIt() throws Exception {
		FakeResponse created = group(SharingFixture.CAROL, "group", "friends", "bob");
		assertEquals(created.body(), HttpServletResponse.SC_OK, created.status());

		GroupList listed = GroupList.readGroupList(reader(body(get("/", "groups", SharingFixture.CAROL))));
		List<String> names = new ArrayList<>();
		for (Group entry : listed.getGroups()) {
			names.add(entry.getName());
		}
		assertEquals("The groups one owns, then the groups one is in.", Arrays.asList("friends", "family"), names);
		assertEquals("carol", listed.getGroups().get(0).getOwner());
	}

	public void testCarolMayNotChangeAlicesGroup() throws Exception {
		FakeResponse response = group(SharingFixture.CAROL, "group", "family", "carol");

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(AuthService.GROUP_REFUSED, errorMessage(response));
		assertEquals("The group is unchanged.", 2,
			new de.haumacher.imageServer.auth.GroupStore(_base).getGroup("family").getMembers().size());
	}

	public void testRemovingOnesOwnGroup() throws Exception {
		group(SharingFixture.CAROL, "group", "friends", "bob");

		assertEquals(HttpServletResponse.SC_OK, group(SharingFixture.CAROL, "ungroup", "friends").status());
		assertNull(new de.haumacher.imageServer.auth.GroupStore(_base).getGroup("friends"));

		FakeResponse foreign = group(SharingFixture.CAROL, "ungroup", "family");
		assertEquals(HttpServletResponse.SC_FORBIDDEN, foreign.status());
	}

	public void testAGroupMemberWhoIsNotThereIsRefused() throws Exception {
		FakeResponse response = group(SharingFixture.CAROL, "group", "friends", "nobody");

		assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status());
		assertEquals(AuthService.unknownSubject(Subjects.user("nobody")), errorMessage(response));
		assertNull(new de.haumacher.imageServer.auth.GroupStore(_base).getGroup("friends"));
	}

	public void testAGuestMayNotCreateGroupsButSeesTheOnesSheIsIn() throws Exception {
		FakeResponse response = group(SharingFixture.EVE, "group", "guests", "bob");

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(AuthService.GROUP_CREATE_REFUSED, errorMessage(response));

		GroupList listed = GroupList.readGroupList(reader(body(get("/", "groups", SharingFixture.EVE))));
		assertTrue("A guest in no group sees none.", listed.getGroups().isEmpty());
	}

	// --- Who uses this server. ---

	public void testAMemberSeesTheNamesToShareWith() throws Exception {
		UserList users = UserList.readUserList(reader(body(get("/", "users", SharingFixture.BOB))));

		List<String> names = new ArrayList<>();
		for (UserEntry user : users.getUsers()) {
			names.add(user.getName() + ":" + user.getRole());
		}
		assertEquals(Arrays.asList("alice:admin", "bob:member", "carol:member", "dave:member", "eve:guest"), names);
	}

	public void testAGuestAndAnAnonymousCallerSeeNoNames() throws Exception {
		FakeResponse guest = get("/", "users", SharingFixture.EVE);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, guest.status());
		assertEquals(AuthService.USERS_REFUSED, errorMessage(guest));

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, get("/", "users", null).status());
	}

	// --- What is derived is answered and never stored. ---

	public void testARoundTripNeverFreezesTheRightsIntoASidecar() throws Exception {
		String answered = body(get("/" + SharingFixture.ZOO + "/", "json", SharingFixture.ALICE));
		assertTrue("The answer carries the rights: " + answered, answered.contains("\"name\":\"edit\""));

		assertEquals(HttpServletResponse.SC_OK,
			put("/" + SharingFixture.ZOO + "/", answered, SharingFixture.ALICE).status());

		String sidecar = read(_base.resolve("alice/" + SharingFixture.ZOO + "/index.json"));
		assertFalse("A sidecar never names a right: " + sidecar, sidecar.contains("\"name\":\"edit\""));
		assertTrue("Everything else survives the round trip.", sidecar.contains("private.jpg"));
	}

	public void testAMoveWritesNoRightsEither() throws Exception {
		// Read first, so that the cache holds the album the move rewrites.
		assertTrue(body(get("/" + SharingFixture.ZOO + "/", "json", SharingFixture.ALICE)).contains("\"name\":\"edit\""));

		moveResult(move("/" + SharingFixture.CAROLS_ALBUM + "/", "~alice/" + SharingFixture.ZOO,
			SharingFixture.CAROL, "carols.jpg"));

		String sidecar = read(_base.resolve("alice/" + SharingFixture.ZOO + "/index.json"));
		assertTrue("The moved photo arrived.", sidecar.contains("carols.jpg"));
		assertFalse("The rewritten sidecar names no right: " + sidecar, sidecar.contains("\"name\":\"view\""));
	}

	/** Gives the given subject the given right on the whole of alice's library. */
	private void grantAliceSpaceTo(String subject, String right) throws Exception {
		assertEquals(HttpServletResponse.SC_OK, grant("/", SharingFixture.ALICE, "grant", subject, right).status());
	}

	/** Makes the folder at the given path below the base folder file what lands in it by year. */
	private void filedByYear(String path) throws IOException {
		Files.createDirectories(_base.resolve(path));
		Files.write(_base.resolve(path).resolve("index.json"),
			"[\"ListingInfo\",{\"title\":\"Filed\",\"placement\":\"BY_YEAR\"}]".getBytes(StandardCharsets.UTF_8));
	}

	// --- Helpers: the servlet. ---

	private ImageServlet servlet() throws Exception {
		if (_servlet == null) {
			_servlet = new ImageServlet(_base.toFile(), new AuthService(AuthMode.WRITES, SharingFixture.SECRET, _base));
			_servlet.init();
			_servlets.add(_servlet);
		}
		return _servlet;
	}

	private FakeResponse get(String pathInfo, String type, String token) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", type);
		FakeResponse response = new FakeResponse();
		servlet().doGet(request(pathInfo, null, "", token, parameters), response.response());
		return response;
	}

	private FakeResponse put(String pathInfo, String body, String token) throws Exception {
		FakeResponse response = new FakeResponse();
		servlet().doPut(request(pathInfo, "application/json", body, token, Collections.emptyMap()),
			response.response());
		return response;
	}

	private FakeResponse post(String pathInfo, String body, String token, Map<String, String> parameters)
			throws Exception {
		FakeResponse response = new FakeResponse();
		servlet().doPost(request(pathInfo, "application/json", body, token, parameters), response.response());
		return response;
	}

	private FakeResponse upload(String pathInfo, String token, String fileName) throws Exception {
		LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
		files.put(fileName, ("pixels of " + fileName).getBytes(StandardCharsets.UTF_8));
		Map<String, String> headers = new HashMap<>();
		headers.put("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
		if (token != null) {
			headers.put("Authorization", "Bearer " + token);
		}
		FakeResponse response = new FakeResponse();
		servlet().doPut(TestImageServletPut.request(pathInfo, "multipart/form-data; boundary=" + BOUNDARY,
			multipart(files), headers, Collections.emptyMap()), response.response());
		return response;
	}

	private FakeResponse move(String pathInfo, String target, String token, String... names) throws Exception {
		StringBuilder body = new StringBuilder("{\"target\":\"").append(target).append("\",\"names\":[");
		for (int n = 0; n < names.length; n++) {
			body.append(n == 0 ? "" : ",").append("{\"name\":\"").append(names[n]).append("\"}");
		}
		body.append("]}");
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "move");
		return post(pathInfo, body.toString(), token, parameters);
	}

	private FakeResponse grant(String pathInfo, String token, String action, String subject, String right)
			throws Exception {
		Grant body = Grant.create().setSubject(subject);
		if (right != null) {
			body.addRight(RightName.create().setName(right));
		}
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", action);
		return post(pathInfo, json(body), token, parameters);
	}

	private FakeResponse group(String token, String action, String name, String... members) throws Exception {
		Group body = Group.create().setName(name);
		for (String member : members) {
			body.addMember(MemberName.create().setName(member));
		}
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", action);
		return post("/", json(body), token, parameters);
	}

	private static String json(de.haumacher.msgbuf.data.DataObject object) throws IOException {
		java.io.StringWriter buffer = new java.io.StringWriter();
		try (de.haumacher.msgbuf.json.JsonWriter out =
			new de.haumacher.msgbuf.json.JsonWriter(new de.haumacher.msgbuf.server.io.WriterAdapter(buffer))) {
			object.writeTo(out);
		}
		return buffer.toString();
	}

	private static HttpServletRequest request(String pathInfo, String contentType, String body, String token,
			Map<String, String> parameters) {
		Map<String, String> headers = new HashMap<>();
		if (token != null) {
			headers.put("Authorization", "Bearer " + token);
		}
		return TestImageServletPut.request(pathInfo, contentType, body.getBytes(StandardCharsets.UTF_8), headers,
			parameters);
	}

	/** A multipart body carrying the given files, as the app would send it. */
	private static byte[] multipart(LinkedHashMap<String, byte[]> files) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (Map.Entry<String, byte[]> file : files.entrySet()) {
			out.write(("--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.UTF_8));
			out.write(("Content-Disposition: form-data; name=\"" + file.getKey() + "\"; filename=\""
				+ file.getKey() + "\"\r\n").getBytes(StandardCharsets.UTF_8));
			out.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
			out.write(file.getValue());
			out.write("\r\n".getBytes(StandardCharsets.UTF_8));
		}
		out.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
		return out.toByteArray();
	}

	// --- Helpers: reading the answers. ---

	private static List<String> rightsOf(FolderResource folder) {
		List<String> result = new ArrayList<>();
		for (RightName right : folder.getRights()) {
			result.add(right.getName());
		}
		return result;
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

	private static FolderResource folder(FakeResponse response) throws IOException {
		Resource resource = Resource.readResource(reader(body(response)));
		assertTrue("Expected a folder, got: " + resource, resource instanceof FolderResource);
		return (FolderResource) resource;
	}

	private static AlbumInfo album(FakeResponse response) throws IOException {
		FolderResource folder = folder(response);
		assertTrue("Expected an album, got: " + folder, folder instanceof AlbumInfo);
		return (AlbumInfo) folder;
	}

	private static de.haumacher.imageServer.shared.model.CreateResult createResult(FakeResponse response)
			throws IOException {
		return de.haumacher.imageServer.shared.model.CreateResult.readCreateResult(reader(body(response)));
	}

	private static MoveResult moveResult(FakeResponse response) throws IOException {
		return MoveResult.readMoveResult(reader(body(response)));
	}

	private static String body(FakeResponse response) {
		assertEquals("Expected a successful request, got: " + response.body(), HttpServletResponse.SC_OK,
			response.status());
		return response.body();
	}

	private static String errorMessage(FakeResponse response) throws IOException {
		Resource resource = Resource.readResource(reader(response.body()));
		assertTrue("Expected an ErrorInfo body, got: " + response.body(), resource instanceof ErrorInfo);
		return ((ErrorInfo) resource).getMessage();
	}

	private static JsonReader reader(String contents) {
		return new JsonReader(new ReaderAdapter(new StringReader(contents)));
	}

	private static String read(Path file) throws IOException {
		return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
	}
}
