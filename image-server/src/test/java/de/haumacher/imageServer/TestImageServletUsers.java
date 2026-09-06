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
import de.haumacher.imageServer.shared.model.UploadCheckResult;
import de.haumacher.imageServer.upload.HashCache;
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

	public void testASignInWithoutANameKeepsTheOwnerUnnamed() throws Exception {
		ImageServlet servlet = servlet(AuthMode.WRITES);

		PairResponse response = signIn(servlet, "", "Phone");

		assertEquals("A pre-#45 app sends no name and is still the owner.", "", response.getUserName());
		assertEquals(Roles.ADMIN, response.getRole());
		assertEquals("", new UserStore(_base).getOwner().getName());
	}

	public void testTheSecondSignInNamesTheUnnamedOwner() throws Exception {
		ImageServlet servlet = servlet(AuthMode.WRITES);
		signIn(servlet, "", "Phone");

		PairResponse response = signIn(servlet, "haui", "Tablet");

		assertEquals("haui", response.getUserName());
		UserStore store = new UserStore(_base);
		assertEquals(1, store.getUsers().size());
		assertEquals("Both devices belong to the one owner.", 2, store.getOwner().getDevices().size());
	}

	public void testSigningInUnderTheOwnersNameAgain() throws Exception {
		ImageServlet servlet = servlet(AuthMode.WRITES);
		signIn(servlet, "haui", "Phone");

		assertEquals("haui", signIn(servlet, "haui", "Tablet").getUserName());
		assertEquals("An empty name still means 'the library owner'.", "haui",
			signIn(servlet, "", "Laptop").getUserName());
	}

	public void testSigningInUnderAnotherNameRefused() throws Exception {
		ImageServlet servlet = servlet(AuthMode.WRITES);
		signIn(servlet, "haui", "Phone");

		FakeResponse response = post(servlet, "pair", pairRequest(SECRET, "Intruder", "somebody-else"), null);

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status());
		assertEquals(UserStore.ownerMismatch("haui"), errorMessage(response));
		assertEquals("A refused sign-in must not add a device.", 1,
			new UserStore(_base).getOwner().getDevices().size());
	}

	public void testSigningInWithAnInvalidNameRefused() throws Exception {
		ImageServlet servlet = servlet(AuthMode.WRITES);

		FakeResponse response = post(servlet, "pair", pairRequest(SECRET, "Phone", "a\\/b"), null);

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status());
		assertEquals(UserStore.NAME_REFUSED, errorMessage(response));
		assertFalse("A refused sign-in must not create the store.",
			_base.resolve(UserStore.DIRECTORY_NAME).resolve(UserStore.FILE_NAME).toFile().exists());
	}

	// --- The owner of a migrated library is re-rooted at their space. ---

	public void testTheOwnerSeesTheirSpaceAsTheRoot() throws Exception {
		Files.createDirectories(_base.resolve("2020 Trip"));
		String token = signIn(servlet(AuthMode.WRITES), "haui", "Phone").getToken();
		LibraryMigration.migrate(_base, "haui");

		ImageServlet servlet = servlet(AuthMode.WRITES);
		String listing = body(get(servlet, "/", "json", token));

		assertTrue("The owner's root is their space folder: " + listing, listing.contains("2020 Trip"));
		assertFalse("The space folder must not appear inside itself: " + listing, listing.contains("haui"));
	}

	public void testTheOwnerWritesIntoTheirSpace() throws Exception {
		String token = signIn(servlet(AuthMode.WRITES), "haui", "Phone").getToken();
		LibraryMigration.migrate(_base, "haui");

		FakeResponse response = put(servlet(AuthMode.WRITES), "/", ALBUM_JSON, token);

		assertEquals(HttpServletResponse.SC_OK, response.status());
		assertEquals(ALBUM_JSON, read(_base.resolve("haui").resolve("index.json")));
		assertFalse("Nothing may be written at the base folder any more.",
			_base.resolve("index.json").toFile().exists());
	}

	public void testASignInAfterTheMigrationReportsTheSpace() throws Exception {
		signIn(servlet(AuthMode.WRITES), "haui", "Phone");
		LibraryMigration.migrate(_base, "haui");

		PairResponse response = signIn(servlet(AuthMode.WRITES), "haui", "Tablet");

		assertEquals("haui", response.getUserName());
		assertEquals("haui", response.getSpace());
		assertEquals(Roles.ADMIN, response.getRole());
	}

	// --- A member owns a space of their own (the way in arrives with issue #52). ---

	public void testAMemberSeesOnlyTheirOwnSpace() throws Exception {
		member();
		Files.createDirectories(_base.resolve("alice").resolve("Alice's album"));
		Files.createDirectories(_base.resolve("haui").resolve("The owner's album"));

		ImageServlet servlet = servlet(AuthMode.WRITES);
		String listing = body(get(servlet, "/", "json", ALICE_TOKEN));

		assertTrue(listing, listing.contains("Alice&apos;s album") || listing.contains("Alice's album"));
		assertFalse("A member must not see another user's space: " + listing,
			listing.contains("The owner&apos;s album") || listing.contains("The owner's album"));
	}

	public void testAMemberCannotEscapeTheirSpace() throws Exception {
		member();
		ImageServlet servlet = servlet(AuthMode.WRITES);

		assertEquals("A path leaving the space is a 404, exactly like one leaving the base folder.",
			HttpServletResponse.SC_NOT_FOUND, get(servlet, "/../", "json", ALICE_TOKEN).status());
		assertEquals(HttpServletResponse.SC_NOT_FOUND,
			get(servlet, "/haui/", "json", ALICE_TOKEN).status());
		assertEquals(HttpServletResponse.SC_NOT_FOUND,
			put(servlet, "/../escape/", ALBUM_JSON, ALICE_TOKEN).status());
	}

	public void testAMemberWritesIntoTheirSpace() throws Exception {
		member();

		FakeResponse response = put(servlet(AuthMode.WRITES), "/", ALBUM_JSON, ALICE_TOKEN);

		assertEquals(HttpServletResponse.SC_OK, response.status());
		assertEquals(ALBUM_JSON, read(_base.resolve("alice").resolve("index.json")));
	}

	public void testAMembersSpaceIsCreatedWhenItIsFirstNeeded() throws Exception {
		member();
		assertFalse("The space of a member who never signed in does not exist yet.",
			_base.resolve("alice").toFile().exists());

		assertEquals(HttpServletResponse.SC_OK, get(servlet(AuthMode.WRITES), "/", "json", ALICE_TOKEN).status());

		assertTrue("A member's space is created when it is first needed.",
			_base.resolve("alice").toFile().isDirectory());
	}

	public void testAMemberUploadsIntoTheirSpace() throws Exception {
		member();
		ImageServlet servlet = servlet(AuthMode.WRITES);
		servlet.init();

		FakeResponse response = new FakeResponse();
		LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
		files.put("a.jpg", "red pixels".getBytes(StandardCharsets.UTF_8));
		Map<String, String> headers = new HashMap<>();
		headers.put("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
		headers.put("Authorization", "Bearer " + ALICE_TOKEN);
		servlet.doPut(TestImageServletPut.request("/", "multipart/form-data; boundary=" + BOUNDARY, multipart(files),
			headers, Collections.emptyMap()), response.response());

		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		assertTrue("The upload lands in the member's space.", _base.resolve("alice").resolve("a.jpg").toFile().exists());
		assertFalse("Nothing may be written at the base folder.", _base.resolve("a.jpg").toFile().exists());
		assertTrue("The staging area belongs to the server, not to a space.",
			_base.resolve(UserStore.UPLOAD_DIRECTORY_NAME).toFile().isDirectory());
		assertFalse(_base.resolve("alice").resolve(UserStore.UPLOAD_DIRECTORY_NAME).toFile().exists());
	}

	public void testTheUploadPreCheckAsksTheMembersSpace() throws Exception {
		member();
		Files.createDirectories(_base.resolve("alice"));
		Files.write(_base.resolve("alice").resolve("a.jpg"), "red pixels".getBytes(StandardCharsets.UTF_8));
		Files.write(_base.resolve("haui").resolve("b.jpg"), "blue pixels".getBytes(StandardCharsets.UTF_8));

		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "check");
		FakeResponse response = new FakeResponse();
		String body = "{\"hashes\":[{\"hash\":\"" + HashCache.sha256("red pixels".getBytes(StandardCharsets.UTF_8))
			+ "\"},{\"hash\":\"" + HashCache.sha256("blue pixels".getBytes(StandardCharsets.UTF_8)) + "\"}]}";
		servlet(AuthMode.WRITES).doPost(request("/", "application/json", body, ALICE_TOKEN, parameters),
			response.response());

		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		UploadCheckResult result = UploadCheckResult.readUploadCheckResult(reader(response.body()));
		assertEquals("Only what the member's own space holds is present.", 1, result.getPresent().size());
		assertEquals("a.jpg", result.getPresent().get(0).getName());
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

	public void testAnonymousReadsStayOpenBeforeTheMigration() throws Exception {
		signIn(servlet(AuthMode.WRITES), "haui", "Phone");

		FakeResponse response = get(servlet(AuthMode.WRITES), "/", "json", null);

		assertEquals("A single-user library looks exactly as it did before issue #45.",
			HttpServletResponse.SC_OK, response.status());
	}

	public void testAnonymousReadsAreRefusedAfterTheMigration() throws Exception {
		signIn(servlet(AuthMode.WRITES), "haui", "Phone");
		LibraryMigration.migrate(_base, "haui");

		FakeResponse response = get(servlet(AuthMode.WRITES), "/", "json", null);

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status());
		assertEquals("Bearer", response.header("WWW-Authenticate"));
		assertEquals(AuthService.LIBRARY_REFUSED, errorMessage(response));
	}

	public void testAnonymousWritesAreRefusedAfterTheMigration() throws Exception {
		signIn(servlet(AuthMode.WRITES), "haui", "Phone");
		LibraryMigration.migrate(_base, "haui");

		FakeResponse response = put(servlet(AuthMode.WRITES), "/", ALBUM_JSON, null);

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status());
		assertEquals(AuthService.LIBRARY_REFUSED, errorMessage(response));
	}

	public void testTheMigratedLibraryIsClosedInEveryMode() throws Exception {
		signIn(servlet(AuthMode.WRITES), "haui", "Phone");
		LibraryMigration.migrate(_base, "haui");

		assertEquals(AuthService.LIBRARY_REFUSED, errorMessage(get(servlet(AuthMode.ALL), "/", "json", null)));
		assertEquals(AuthService.LIBRARY_REFUSED, errorMessage(get(servlet(AuthMode.WRITES), "/", "json", null)));
	}

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
		assertEquals(Roles.MEMBER, info.getRole());
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

	// --- Helpers. ---

	/** A library with a named owner and a member "alice" whose space is not created yet. */
	private void member() throws IOException {
		Files.createDirectories(_base.resolve("haui"));
		UserStore store = new UserStore(_base);
		User owner = store.nameOwner("haui");
		owner.setSpace("haui");
		User alice = new User("alice", Roles.MEMBER, "alice", Instant.now().toString());
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
