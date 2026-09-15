/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.LibraryMigration;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.auth.UserStore.Device;
import de.haumacher.imageServer.auth.UserStore.User;
import de.haumacher.imageServer.shared.model.AuthInfo;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.PairResponse;
import de.haumacher.imageServer.shared.model.Resource;
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
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Test case for the user principal and the user spaces of issue #45, driven through
 * {@link ImageServlet} with the request and response fakes of {@link TestImageServletPut}.
 */
@SuppressWarnings("javadoc")
public class TestImageServletUsers extends TestCase {

	private static final String ALBUM_JSON = "[\"AlbumInfo\",{\"title\":\"Root\",\"parts\":[]}]";

	private static final String SECRET = "let-me-in";

	private static final String ALICE_TOKEN = "alice-token";

	private static final String BOUNDARY = "----valbumUsersTestBoundary";

	private Path _base;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-users-test");
	}

	@Override
	protected void tearDown() throws Exception {
		if (_base != null) {
			try (Stream<Path> files = Files.walk(_base)) {
				files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
		super.tearDown();
	}

	// --- Signing in names the library owner exactly once. ---

	public void testFirstSignInCreatesTheOwner() throws Exception {
		ImageServlet servlet = servlet(AuthMode.WRITES);

		PairResponse response = signIn(servlet, "haui", "Phone");

		assertEquals("haui", response.getUserName());
		assertEquals(Roles.ADMIN, response.getRole());
		assertEquals("A fresh library is rooted at the base folder.", "", response.getSpace());
		assertEquals("Phone", response.getDeviceName());

		User owner = new UserStore(_base).getOwner();
		assertEquals("haui", owner.getName());
		assertEquals(Roles.ADMIN, owner.getRole());
	}

	/**
	 * The first sign-in with the secret creates the administrator of the space, and an
	 * administrator without a name is nobody the space can talk about, see issue #86.
	 */
	public void testAFirstSignInWithoutANameIsRefused() throws Exception {
		ImageServlet servlet = servlet(AuthMode.WRITES);

		FakeResponse response = post(servlet, "pair", pairRequest(SECRET, "Phone", ""), null);

		assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status());
		assertEquals(AuthService.ADMIN_NAME_REQUIRED, errorMessage(response));
		assertNull("Nobody was created.", new UserStore(_base).getOwner());

		// Blanks are no name either.
		assertEquals(HttpServletResponse.SC_BAD_REQUEST,
			post(servlet, "pair", pairRequest(SECRET, "Phone", "   "), null).status());
		assertNull(new UserStore(_base).getOwner());
	}

	/**
	 * A library written before Phase 6 can have a nameless owner; adding a device to them keeps
	 * working with no name, and a name given then is theirs, see issues #82 and #86.
	 */
	public void testANamelessOwnerOfAnOlderLibraryStillAddsDevices() throws Exception {
		namelessOwner();
		ImageServlet servlet = servlet(AuthMode.WRITES);

		PairResponse nameless = signIn(servlet, "", "Phone");
		assertEquals("A device of the nameless owner, as before.", "", nameless.getUserName());
		assertEquals(Roles.ADMIN, nameless.getRole());

		PairResponse named = signIn(servlet, "haui", "Tablet");
		assertEquals("haui", named.getUserName());
		UserStore store = new UserStore(_base);
		assertEquals(1, store.getUsers().size());
		assertEquals("Every device belongs to the one administrator.", 3, store.getOwner().getDevices().size());
	}

	/** A user store as an older build wrote it: one administrator, without a name. */
	private void namelessOwner() throws Exception {
		java.nio.file.Path file = _base.resolve(UserStore.DIRECTORY_NAME).resolve(UserStore.FILE_NAME);
		java.nio.file.Files.createDirectories(file.getParent());
		java.nio.file.Files.write(file, ("{\"version\":1,\"users\":[{\"name\":\"\",\"role\":\"admin\","
			+ "\"space\":\"\",\"created\":\"2026-09-06T10:11:12Z\",\"devices\":[{\"id\":\"aaaaaaaa\","
			+ "\"name\":\"Old\",\"tokenHash\":\"" + UserStore.hash("old-token")
			+ "\",\"created\":\"2026-09-06T10:11:12Z\"}]}]}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}


	public void testAUserWithAnUnknownRoleIsRefused() throws Exception {
		UserStore store = new UserStore(_base);
		store.nameOwner("haui");
		User stranger = new User("stranger", "sorcerer", "stranger", Instant.now().toString());
		stranger.addDevice(new Device("Broom", UserStore.hash("broom-token"), Instant.now().toString()));
		store.addUser(stranger);
		store.store();

		FakeResponse response = put(servlet(AuthMode.WRITES), "/", ALBUM_JSON, "broom-token");

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status());
		assertEquals(AuthService.ROLE_REFUSED, errorMessage(response));
	}

	// --- Anonymous callers. ---



	public void testAuthOffIgnoresTheUsersEntirely() throws Exception {
		signIn(servlet(AuthMode.WRITES), "haui", "Phone");
		LibraryMigration.migrate(_base, "haui");

		// No store is read at all: the base folder is served as it was before issue #28.
		FakeResponse response = put(servlet(AuthMode.OFF), "/", ALBUM_JSON, null);

		assertEquals(HttpServletResponse.SC_OK, response.status());
		assertEquals(ALBUM_JSON, read(_base.resolve("index.json")));
	}

	// --- The auth endpoint reports the user. ---

	public void testAuthInfoOfTheOwner() throws Exception {
		String token = signIn(servlet(AuthMode.WRITES), "haui", "Phone").getToken();
		LibraryMigration.migrate(_base, "haui");

		AuthInfo info = authInfo(servlet(AuthMode.WRITES), token);

		assertEquals("writes", info.getMode());
		assertEquals("Phone", info.getDeviceName());
		assertEquals("haui", info.getUserName());
		assertEquals(Roles.ADMIN, info.getRole());
		assertEquals("haui", info.getSpace());
		assertTrue(info.isWriteAllowed());
	}

	public void testAuthInfoOfAMember() throws Exception {
		member();

		AuthInfo info = authInfo(servlet(AuthMode.WRITES), ALICE_TOKEN);

		assertEquals("Alice's tablet", info.getDeviceName());
		assertEquals("alice", info.getUserName());
		assertEquals(Roles.EDIT, info.getRole());
		assertEquals("alice", info.getSpace());
		assertTrue(info.isWriteAllowed());
	}

	public void testAuthInfoOfAnAnonymousCallerAfterTheMigration() throws Exception {
		signIn(servlet(AuthMode.WRITES), "haui", "Phone");
		LibraryMigration.migrate(_base, "haui");

		AuthInfo info = authInfo(servlet(AuthMode.WRITES), null);

		assertEquals("The app must always be able to learn where it stands.", "writes", info.getMode());
		assertEquals("", info.getUserName());
		assertEquals("An anonymous caller has no role.", "", info.getRole());
		assertEquals("", info.getSpace());
		assertFalse(info.isWriteAllowed());
	}

	// --- What the user list says about everybody, see issue #55. ---

	public void testTheUserListSaysSpaceCreatedAndHowManyDevices() throws Exception {
		member();
		ImageServlet servlet = servlet(AuthMode.WRITES);
		PairResponse owner = signIn(servlet, "haui", "Phone");

		UserList listed = UserList.readUserList(reader(body(get(servlet, "/", "users", owner.getToken()))));

		UserStore store = new UserStore(_base);
		assertEquals(store.getUsers().size(), listed.getUsers().size());
		for (UserEntry entry : listed.getUsers()) {
			User user = store.getUser(entry.getName());
			assertNotNull(entry.getName(), user);
			assertEquals(user.getRole(), entry.getRole());
			assertEquals(user.getSpace(), entry.getSpace());
			assertEquals(user.getCreated(), entry.getCreated());
			assertEquals(user.getDevices().size(), entry.getDevices());
		}
		assertEquals("The owner's own library is his space.", "haui", entry(listed, "haui").getSpace());
		assertEquals(1, entry(listed, "haui").getDevices());
		assertEquals("alice", entry(listed, "alice").getSpace());
	}

	public void testTheDeviceCountFollowsTheDevices() throws Exception {
		member();
		ImageServlet servlet = servlet(AuthMode.WRITES);
		PairResponse owner = signIn(servlet, "haui", "Phone");
		signIn(servlet, "haui", "Tablet");

		UserList listed = UserList.readUserList(reader(body(get(servlet, "/", "users", owner.getToken()))));

		assertEquals(2, entry(listed, "haui").getDevices());
		assertEquals("Nobody else grew a device.", 1, entry(listed, "alice").getDevices());
	}

	public void testTheAdminSeesTheSameShapeAndNoTokens() throws Exception {
		member();
		ImageServlet servlet = servlet(AuthMode.WRITES);
		String owner = signIn(servlet, "haui", "Phone").getToken();

		// Who else is in the space is the administrator's business, see issue #83.
		String listed = body(get(servlet, "/", "users", owner));

		assertTrue(listed, listed.contains("\"clearance\""));
		assertTrue(listed, listed.contains("\"devices\":1"));
		assertFalse("A user list never carries a token or its hash.", listed.contains("tokenHash"));
		assertFalse(listed, listed.contains(UserStore.hash(ALICE_TOKEN)));
	}

	/** The listed user of the given name. */
	private static UserEntry entry(UserList users, String name) {
		for (UserEntry user : users.getUsers()) {
			if (user.getName().equals(name)) {
				return user;
			}
		}
		fail("No user '" + name + "' in the listing.");
		return null;
	}

	// --- Helpers. ---

	/** A library with a named owner and a member "alice" whose space is not created yet. */
	private void member() throws IOException {
		Files.createDirectories(_base.resolve("haui"));
		UserStore store = new UserStore(_base);
		User owner = store.nameOwner("haui");
		owner.setSpace("haui");
		User alice = new User("alice", Roles.EDIT, "alice", Instant.now().toString());
		alice.addDevice(new Device("Alice's tablet", UserStore.hash(ALICE_TOKEN), Instant.now().toString()));
		store.addUser(alice);
		store.store();
	}

	private ImageServlet servlet(AuthMode mode) throws IOException {
		return new ImageServlet(_base.toFile(), new AuthService(mode, SECRET, _base));
	}

	private PairResponse signIn(ImageServlet servlet, String userName, String deviceName) throws Exception {
		FakeResponse response = post(servlet, "pair", pairRequest(SECRET, deviceName, userName), null);
		assertEquals("Sign-in failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return PairResponse.readPairResponse(reader(response.body()));
	}

	private static String pairRequest(String secret, String deviceName, String userName) {
		return "{\"secret\":\"" + secret + "\",\"deviceName\":\"" + deviceName + "\",\"userName\":\"" + userName
			+ "\"}";
	}

	private AuthInfo authInfo(ImageServlet servlet, String token) throws Exception {
		FakeResponse response = get(servlet, "/", "auth", token);
		assertEquals(HttpServletResponse.SC_OK, response.status());
		return AuthInfo.readAuthInfo(reader(response.body()));
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

	private static FakeResponse put(ImageServlet servlet, String pathInfo, String body, String token)
			throws Exception {
		FakeResponse response = new FakeResponse();
		servlet.doPut(request(pathInfo, "application/json", body, token, Collections.emptyMap()),
			response.response());
		return response;
	}

	private static FakeResponse post(ImageServlet servlet, String action, String body, String token)
			throws Exception {
		Map<String, String> parameters = new HashMap<>();
		if (action != null) {
			parameters.put("action", action);
		}
		FakeResponse response = new FakeResponse();
		servlet.doPost(request("/", "application/json", body, token, parameters), response.response());
		return response;
	}

	private static FakeResponse get(ImageServlet servlet, String pathInfo, String type, String token)
			throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", type);
		FakeResponse response = new FakeResponse();
		servlet.doGet(request(pathInfo, null, "", token, parameters), response.response());
		return response;
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

	private static String read(Path file) throws IOException {
		return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
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
}
