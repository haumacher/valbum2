/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.shared.model.AuthInfo;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.PairResponse;
import de.haumacher.imageServer.shared.model.Resource;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Test case for the per-device token authentication of {@link ImageServlet} (issue #28).
 *
 * <p>
 * The servlet is driven headlessly on a temporary base folder, as in {@link TestImageServletPut},
 * whose request and response fakes are reused.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestImageServletAuth extends TestCase {

	private static final String ALBUM_JSON = "[\"AlbumInfo\",{\"title\":\"Root\",\"parts\":[]}]";

	private static final String SECRET = "let-me-in";

	private Path _base;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-auth-test");
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

	// --- The default mode: reads are open, writes need a paired device. ---

	public void testAnonymousWriteRefused() throws Exception {
		ImageServlet servlet = servlet(AuthMode.WRITES);

		FakeResponse response = put(servlet, "/", ALBUM_JSON, null);

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status());
		assertEquals("Bearer", response.header("WWW-Authenticate"));
		assertEquals(AuthService.WRITE_REFUSED, errorMessage(response));
		assertFalse("A refused write must not create the sidecar.",
			new File(_base.toFile(), "index.json").exists());
	}

	public void testAnonymousReadAllowed() throws Exception {
		ImageServlet servlet = servlet(AuthMode.WRITES);

		FakeResponse response = get(servlet, "/", "json", null);

		assertEquals(HttpServletResponse.SC_OK, response.status());
		assertTrue("Expected album data, got: " + response.body(), response.body().startsWith("["));
	}

	public void testWriteWithTokenAllowed() throws Exception {
		AuthService auth = auth(AuthMode.WRITES);
		ImageServlet servlet = servlet(auth);
		String token = pair(servlet, SECRET, "Phone").getToken();

		FakeResponse response = put(servlet, "/", ALBUM_JSON, token);

		assertEquals(HttpServletResponse.SC_OK, response.status());
		assertEquals(ALBUM_JSON, read(new File(_base.toFile(), "index.json")));
	}

	public void testGarbageTokenRefused() throws Exception {
		ImageServlet servlet = servlet(AuthMode.WRITES);

		FakeResponse response = put(servlet, "/", ALBUM_JSON, "not-a-token");

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status());
		assertEquals(AuthService.TOKEN_REFUSED, errorMessage(response));
	}

	// --- Mode "all": reads need a paired device as well. ---

	public void testAnonymousReadRefusedInModeAll() throws Exception {
		ImageServlet servlet = servlet(AuthMode.ALL);

		FakeResponse response = get(servlet, "/", "json", null);

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status());
		assertEquals("Bearer", response.header("WWW-Authenticate"));
		assertEquals(AuthService.READ_REFUSED, errorMessage(response));
	}

	public void testReadWithTokenInModeAll() throws Exception {
		ImageServlet servlet = servlet(AuthMode.ALL);
		String token = pair(servlet, SECRET, "Phone").getToken();

		FakeResponse response = get(servlet, "/", "json", token);

		assertEquals(HttpServletResponse.SC_OK, response.status());
		assertTrue("Expected album data, got: " + response.body(), response.body().startsWith("["));
	}

	// --- Mode "off": today's behaviour. ---

	public void testAnonymousWriteAllowedWhenAuthIsOff() throws Exception {
		ImageServlet servlet = servlet(AuthMode.OFF);

		FakeResponse response = put(servlet, "/", ALBUM_JSON, null);

		assertEquals(HttpServletResponse.SC_OK, response.status());
		assertEquals(ALBUM_JSON, read(new File(_base.toFile(), "index.json")));
	}

	public void testPairingRefusedWhenAuthIsOff() throws Exception {
		ImageServlet servlet = servlet(AuthMode.OFF);

		FakeResponse response = post(servlet, "pair", pairRequest(SECRET, "Phone"), null);

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(AuthService.PAIRING_DISABLED, errorMessage(response));
	}

	// --- Pairing. ---

	public void testWrongSecretRefused() throws Exception {
		ImageServlet servlet = servlet(AuthMode.WRITES);

		FakeResponse response = post(servlet, "pair", pairRequest("guess", "Phone"), null);

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(AuthService.SECRET_REFUSED, errorMessage(response));
		assertFalse("A refused pairing must not create the device store.",
			_base.resolve(UserStore.DIRECTORY_NAME).resolve(UserStore.FILE_NAME).toFile().exists());
	}

	public void testStoreHoldsTheHashNeverTheToken() throws Exception {
		ImageServlet servlet = servlet(AuthMode.WRITES);

		String token = pair(servlet, SECRET, "Phone").getToken();

		String stored = read(_base.resolve(UserStore.DIRECTORY_NAME).resolve(UserStore.FILE_NAME).toFile());
		assertFalse("The token itself must never be stored: " + stored, stored.contains(token));
		assertTrue("The store must hold the token's hash: " + stored,
			stored.contains(UserStore.hash(token)));
		assertTrue("The store must be versioned: " + stored, stored.contains("\"version\":1"));
		assertTrue("The store must name the device: " + stored, stored.contains("\"Phone\""));
		assertTrue("The store must record the role: " + stored, stored.contains("\"role\":\"admin\""));
	}

	public void testStoreWrittenByThisBuildLoadsBack() throws Exception {
		ImageServlet servlet = servlet(AuthMode.WRITES);
		String token = pair(servlet, SECRET, "Phone").getToken();

		UserStore reloaded = new UserStore(_base);

		assertEquals(1, reloaded.getUsers().size());
		assertEquals(1, reloaded.getOwner().getDevices().size());
		assertEquals("Phone", reloaded.getOwner().getDevices().get(0).getName());
		assertEquals("Phone", reloaded.lookup(token).getDevice().getName());
		assertNull(reloaded.lookup("some other token"));
		assertFalse("A device entry records when it was paired.",
			reloaded.getOwner().getDevices().get(0).getCreated().isEmpty());
	}

	public void testTokenSurvivesARestart() throws Exception {
		ImageServlet servlet = servlet(AuthMode.WRITES);
		String token = pair(servlet, SECRET, "Phone").getToken();

		// A new servlet on the same base path is what a restarted server looks like.
		ImageServlet restarted = servlet(AuthMode.WRITES);

		FakeResponse response = put(restarted, "/", ALBUM_JSON, token);
		assertEquals(HttpServletResponse.SC_OK, response.status());
	}

	public void testPairingWritesNothingButItsOwnDirectory() throws Exception {
		ImageServlet servlet = servlet(AuthMode.WRITES);

		pair(servlet, SECRET, "Phone");

		assertEquals("Pairing must write nothing but its own directory.",
			Collections.singletonList(UserStore.DIRECTORY_NAME), entries(_base));
		assertEquals(Collections.singletonList(UserStore.FILE_NAME),
			entries(_base.resolve(UserStore.DIRECTORY_NAME)));
	}

	public void testUnparsablePairRequestRefused() throws Exception {
		ImageServlet servlet = servlet(AuthMode.WRITES);

		FakeResponse response = post(servlet, "pair", "this is not JSON", null);

		assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status());
		assertFalse("Even a broken request is answered with a reason.", errorMessage(response).isEmpty());
	}

	public void testPostWithoutPairActionRefusedLikeAWrite() throws Exception {
		ImageServlet servlet = servlet(AuthMode.WRITES);

		FakeResponse response = post(servlet, null, "{}", null);

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status());
		assertEquals(AuthService.WRITE_REFUSED, errorMessage(response));
	}

	// --- The auth endpoint. ---

	public void testAuthInfoOfAnonymousCaller() throws Exception {
		ImageServlet servlet = servlet(AuthMode.WRITES);

		AuthInfo info = authInfo(servlet, null);

		assertEquals("writes", info.getMode());
		assertEquals("", info.getDeviceName());
		assertFalse(info.isWriteAllowed());
	}

	public void testAuthInfoOfPairedCaller() throws Exception {
		ImageServlet servlet = servlet(AuthMode.WRITES);
		String token = pair(servlet, SECRET, "Phone").getToken();

		AuthInfo info = authInfo(servlet, token);

		assertEquals("writes", info.getMode());
		assertEquals("Phone", info.getDeviceName());
		assertTrue(info.isWriteAllowed());
	}

	public void testAuthInfoIsAnsweredEvenWhenReadsNeedAuth() throws Exception {
		ImageServlet servlet = servlet(AuthMode.ALL);

		AuthInfo info = authInfo(servlet, null);

		assertEquals("The app must be able to learn that it has to pair.", "all", info.getMode());
		assertEquals("", info.getDeviceName());
		assertFalse(info.isWriteAllowed());
	}

	public void testAuthInfoWhenAuthIsOff() throws Exception {
		ImageServlet servlet = servlet(AuthMode.OFF);

		AuthInfo info = authInfo(servlet, null);

		assertEquals("off", info.getMode());
		assertTrue(info.isWriteAllowed());
	}

	// --- Helpers. ---

	private AuthService auth(AuthMode mode) {
		return new AuthService(mode, SECRET, _base);
	}

	private ImageServlet servlet(AuthMode mode) throws IOException {
		return servlet(auth(mode));
	}

	private ImageServlet servlet(AuthService auth) throws IOException {
		return new ImageServlet(_base.toFile(), auth);
	}

	private PairResponse pair(ImageServlet servlet, String secret, String deviceName) throws Exception {
		FakeResponse response = post(servlet, "pair", pairRequest(secret, deviceName), null);
		assertEquals("Pairing failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return PairResponse.readPairResponse(reader(response.body()));
	}

	private static String pairRequest(String secret, String deviceName) {
		return "{\"secret\":\"" + secret + "\",\"deviceName\":\"" + deviceName + "\"}";
	}

	private AuthInfo authInfo(ImageServlet servlet, String token) throws Exception {
		FakeResponse response = get(servlet, "/", "auth", token);
		assertEquals(HttpServletResponse.SC_OK, response.status());
		return AuthInfo.readAuthInfo(reader(response.body()));
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

	private static String read(File file) throws IOException {
		return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
	}

	private static List<String> entries(Path directory) {
		String[] names = directory.toFile().list();
		if (names == null) {
			return Collections.emptyList();
		}
		return Arrays.stream(names).sorted().collect(Collectors.toList());
	}
}
