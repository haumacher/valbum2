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
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.CreateResult;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.Grant;
import de.haumacher.imageServer.shared.model.Group;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.MemberName;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.RightName;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Review probe for the grants of issue #49, composing them with what the server already had: the
 * placement rule and the {@link CreateResult} of issue #48, the "view as" of issue #46, group
 * membership changing under a running server, and the library that was never migrated, in which
 * the owner's space is the base folder and the other spaces lie inside it.
 */
@SuppressWarnings("javadoc")
public class TestGrantsProbe extends TestCase {

	private static final String NEW_ALBUM = "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[]}]";

	private Path _base;

	private final List<ImageServlet> _servlets = new ArrayList<>();

	private ImageServlet _servlet;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-grants-probe");
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

	// --- The shared library, with the placement rule and the create result of issue #48. ---

	public void testAnAlbumCreatedThroughTheCanonicalFormIsAnsweredInIt() throws Exception {
		SharingFixture.create(_base);
		Files.write(_base.resolve("alice/index.json"),
			"[\"ListingInfo\",{\"title\":\"Alice\",\"placement\":\"BY_YEAR\"}]".getBytes(StandardCharsets.UTF_8));
		assertEquals(HttpServletResponse.SC_OK,
			grant("/", SharingFixture.ALICE, Subjects.user("bob"), Rights.EDIT).status());

		FakeResponse created = put("/~alice/2025-01-01 Trip/", NEW_ALBUM, SharingFixture.BOB);
		assertEquals(created.body(), HttpServletResponse.SC_OK, created.status());
		assertTrue("The rule of alice's root filed the album into her year folder.",
			Files.isDirectory(_base.resolve("alice/2025/2025-01-01 Trip")));

		CreateResult result = CreateResult.readCreateResult(reader(created.body()));
		assertEquals("The answer is in the coordinates of the request, or bob's app would look for the album in his own space.",
			"~alice/2025/2025-01-01 Trip", result.getPath());

		FakeResponse shown = get("/" + result.getPath() + "/", "json", SharingFixture.BOB);
		assertEquals(shown.body(), HttpServletResponse.SC_OK, shown.status());
		assertEquals(new ArrayList<>(Rights.ALL), rightsOf(folder(shown)));
	}

	public void testTheOwnerReachesHerOwnSpaceThroughTheCanonicalFormAsOwner() throws Exception {
		SharingFixture.create(_base);

		FakeResponse response = get("/~alice/" + SharingFixture.PRIVATE + "/", "json", SharingFixture.ALICE);

		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		assertEquals(new ArrayList<>(Rights.ALL), rightsOf(folder(response)));
	}

	public void testViewAsLowersTheClearanceOfAGrantHolder() throws Exception {
		SharingFixture.create(_base);
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "json");
		parameters.put("viewAs", "public");

		AlbumInfo album = album(get("/~alice/" + SharingFixture.ZOO + "/", parameters, SharingFixture.BOB));

		assertEquals(Collections.singletonList("public.jpg"), names(album));
	}

	public void testLeavingTheGroupTakesTheRightAwayAtOnce() throws Exception {
		SharingFixture.create(_base);
		assertEquals(HttpServletResponse.SC_OK,
			get("/~alice/" + SharingFixture.ZOO + "/", "json", SharingFixture.CAROL).status());

		assertEquals(HttpServletResponse.SC_OK, group(SharingFixture.ALICE, "family", "bob").status());

		FakeResponse refused = get("/~alice/" + SharingFixture.ZOO + "/", "json", SharingFixture.CAROL);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, refused.status());
		assertFalse("A refusal speaks.", errorMessage(refused).isEmpty());

		assertEquals("Back in the group, back in the album.", HttpServletResponse.SC_OK,
			group(SharingFixture.ALICE, "family", "bob", "carol").status());
		assertEquals(HttpServletResponse.SC_OK,
			get("/~alice/" + SharingFixture.ZOO + "/", "json", SharingFixture.CAROL).status());
	}

	public void testRevokingWhileBrowsingRefusesTheNextRequest() throws Exception {
		SharingFixture.create(_base);
		assertEquals(HttpServletResponse.SC_OK,
			get("/~alice/" + SharingFixture.YEAR + "/", "json", SharingFixture.BOB).status());

		FakeResponse revoked = post("/" + SharingFixture.YEAR + "/", grantJson(Subjects.user("bob"), null),
			SharingFixture.ALICE, "revoke");
		assertEquals(revoked.body(), HttpServletResponse.SC_OK, revoked.status());

		FakeResponse refused = get("/~alice/" + SharingFixture.YEAR + "/", "json", SharingFixture.BOB);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, refused.status());
		assertFalse(errorMessage(refused).isEmpty());
	}

	public void testAnonymousLooksAtTheOpenAlbumAndAtNothingBesideIt() throws Exception {
		SharingFixture.create(_base);

		assertEquals(HttpServletResponse.SC_OK,
			get("/~alice/" + SharingFixture.PUBLIC + "/open.jpg", "tn", null).status());

		FakeResponse root = get("/~alice/", "json", null);
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, root.status());
		assertEquals(AuthService.LIBRARY_REFUSED, errorMessage(root));

		FakeResponse grants = get("/~alice/" + SharingFixture.PUBLIC + "/", "grants", null);
		assertEquals("Seeing who else may look is management, not looking.", HttpServletResponse.SC_UNAUTHORIZED,
			grants.status());
		assertFalse(errorMessage(grants).isEmpty());
	}

	// --- The library that was never migrated: the owner's space is the base folder. ---

	public void testAGrantOnTheBaseSpaceNeverCoversAnotherUsersSpace() throws Exception {
		createUnmigratedLibrary();
		assertEquals(HttpServletResponse.SC_OK,
			grant("/", SharingFixture.ALICE, Subjects.user("carol"), Rights.EDIT).status());

		assertEquals("Alice's own album is shared.", HttpServletResponse.SC_OK,
			get("/~alice/" + SharingFixture.PUBLIC + "/", "json", SharingFixture.CAROL).status());

		FakeResponse bobs = get("/~alice/bob/Secret/", "json", SharingFixture.CAROL);
		assertEquals("Bob's space lies inside the base folder, but it is not alice's to share: " + bobs.body(),
			HttpServletResponse.SC_FORBIDDEN, bobs.status());
		assertFalse(errorMessage(bobs).isEmpty());

		FakeResponse bobsImage = get("/~alice/bob/Secret/secret.jpg", "tn", SharingFixture.CAROL);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, bobsImage.status());

		assertEquals("Bob himself is untouched by alice's grant.", HttpServletResponse.SC_OK,
			get("/Secret/", "json", SharingFixture.BOB).status());
	}

	/**
	 * alice owns the base folder itself (no migration), bob has the space <code>bob</code> below
	 * it, carol has <code>carol</code>.
	 */
	private void createUnmigratedLibrary() throws IOException {
		UserStore users = new UserStore(_base);
		User alice = users.nameOwner("alice");
		alice.addDevice(new Device("Alice's phone", UserStore.hash(SharingFixture.ALICE), Instant.now().toString()));
		users.addUser(member("bob", SharingFixture.BOB));
		users.addUser(member("carol", SharingFixture.CAROL));
		users.store();

		SharingFixture.album(_base, SharingFixture.PUBLIC, "Public",
			"[\"ImagePart\",{\"name\":\"open.jpg\",\"width\":4,\"height\":3}]", "open.jpg");
		SharingFixture.album(_base, "bob/Secret", "Secret",
			"[\"ImagePart\",{\"name\":\"secret.jpg\",\"width\":4,\"height\":3}]", "secret.jpg");
		Files.createDirectories(_base.resolve("carol"));
		// The store exists, so that the servlet finds an empty one rather than none.
		new GrantStore(_base);
	}

	private static User member(String name, String token) {
		User result = new User(name, Roles.MEMBER, name, Instant.now().toString());
		result.addDevice(new Device(name + "'s device", UserStore.hash(token), Instant.now().toString()));
		return result;
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
		return get(pathInfo, parameters, token);
	}

	private FakeResponse get(String pathInfo, Map<String, String> parameters, String token) throws Exception {
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

	private FakeResponse post(String pathInfo, String body, String token, String action) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", action);
		FakeResponse response = new FakeResponse();
		servlet().doPost(request(pathInfo, "application/json", body, token, parameters), response.response());
		return response;
	}

	private FakeResponse grant(String pathInfo, String token, String subject, String right) throws Exception {
		return post(pathInfo, grantJson(subject, right), token, "grant");
	}

	private static String grantJson(String subject, String right) throws IOException {
		Grant body = Grant.create().setSubject(subject);
		if (right != null) {
			body.addRight(RightName.create().setName(right));
		}
		return json(body);
	}

	private FakeResponse group(String token, String name, String... members) throws Exception {
		Group body = Group.create().setName(name);
		for (String member : members) {
			body.addMember(MemberName.create().setName(member));
		}
		return post("/", json(body), token, "group");
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
		assertEquals("Expected a successful request, got: " + response.body(), HttpServletResponse.SC_OK,
			response.status());
		Resource resource = Resource.readResource(reader(response.body()));
		assertTrue("Expected a folder, got: " + resource, resource instanceof FolderResource);
		return (FolderResource) resource;
	}

	private static AlbumInfo album(FakeResponse response) throws IOException {
		FolderResource folder = folder(response);
		assertTrue("Expected an album, got: " + folder, folder instanceof AlbumInfo);
		return (AlbumInfo) folder;
	}

	private static String errorMessage(FakeResponse response) throws IOException {
		Resource resource = Resource.readResource(reader(response.body()));
		assertTrue("Expected an ErrorInfo body, got: " + response.body(), resource instanceof ErrorInfo);
		return ((ErrorInfo) resource).getMessage();
	}

	private static JsonReader reader(String contents) {
		return new JsonReader(new ReaderAdapter(new StringReader(contents)));
	}
}
