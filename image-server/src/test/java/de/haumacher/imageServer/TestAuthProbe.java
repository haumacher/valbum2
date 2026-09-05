/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.DeviceStore;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.PairResponse;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Review probe of the authentication (issue #28) composed with nested folders, several devices,
 * a header written by a different client and a device store damaged on disk.
 */
@SuppressWarnings("javadoc")
public class TestAuthProbe extends TestCase {

	private static final String ALBUM_JSON = "[\"AlbumInfo\",{\"title\":\"Nested\",\"parts\":[]}]";

	private static final String SECRET = "s3cret";

	private Path _base;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-auth-probe");
		Files.createDirectories(_base.resolve("2020 Trip"));
	}

	@Override
	protected void tearDown() throws Exception {
		try (Stream<Path> files = Files.walk(_base)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		super.tearDown();
	}

	public void testNestedFolderInModeAll() throws Exception {
		ImageServlet servlet = new ImageServlet(_base.toFile(), new AuthService(AuthMode.ALL, SECRET, _base));

		FakeResponse anonymous = get(servlet, "/2020 Trip/", null);
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, anonymous.status());
		assertEquals(AuthService.READ_REFUSED, errorMessage(anonymous));

		String token = pair(servlet, "Tablet").getToken();
		assertEquals(HttpServletResponse.SC_OK, get(servlet, "/2020 Trip/", "Bearer " + token).status());

		// A header written by another client: lower-case scheme, extra blanks.
		assertEquals(HttpServletResponse.SC_OK, get(servlet, "/2020 Trip/", "bearer   " + token + " ").status());

		FakeResponse stored = put(servlet, "/2020 Trip/", "Bearer " + token);
		assertEquals(HttpServletResponse.SC_OK, stored.status());
		assertTrue(new File(_base.toFile(), "2020 Trip/index.json").exists());
		assertFalse("The root must stay untouched.", new File(_base.toFile(), "index.json").exists());
	}

	public void testTwoDevicesWithTheSameName() throws Exception {
		ImageServlet servlet = new ImageServlet(_base.toFile(), new AuthService(AuthMode.WRITES, SECRET, _base));
		String first = pair(servlet, "Phone").getToken();
		String second = pair(servlet, "Phone").getToken();
		assertFalse(first.equals(second));

		assertEquals(HttpServletResponse.SC_OK, put(servlet, "/2020 Trip/", "Bearer " + first).status());
		assertEquals(HttpServletResponse.SC_OK, put(servlet, "/2020 Trip/", "Bearer " + second).status());
		assertEquals(2, new DeviceStore(_base).getDevices().size());
	}

	public void testDamagedStoreIsNotSilentlyDiscarded() throws Exception {
		ImageServlet before = new ImageServlet(_base.toFile(), new AuthService(AuthMode.WRITES, SECRET, _base));
		String token = pair(before, "Phone").getToken();

		Path file = _base.resolve(DeviceStore.DIRECTORY_NAME).resolve(DeviceStore.FILE_NAME);
		Files.write(file, "{\"version\":1,\"devices\":[{\"name\":\"Phone\",".getBytes(StandardCharsets.UTF_8));

		// A restart on the damaged store: the server comes up, the old token is refused with a reason.
		ImageServlet after = new ImageServlet(_base.toFile(), new AuthService(AuthMode.WRITES, SECRET, _base));
		FakeResponse refused = put(after, "/2020 Trip/", "Bearer " + token);
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, refused.status());
		assertEquals(AuthService.TOKEN_REFUSED, errorMessage(refused));

		// Pairing again works, and the damaged file is kept beside the new one, not overwritten.
		String fresh = pair(after, "Phone").getToken();
		assertEquals(HttpServletResponse.SC_OK, put(after, "/2020 Trip/", "Bearer " + fresh).status());
		String[] names = file.getParent().toFile().list();
		assertNotNull(names);
		assertTrue("Expected the damaged store to be kept, found: " + String.join(", ", names),
			Stream.of(names).anyMatch(name -> name.startsWith(DeviceStore.FILE_NAME + ".")));
	}

	// --- Helpers. ---

	private PairResponse pair(ImageServlet servlet, String deviceName) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "pair");
		FakeResponse response = new FakeResponse();
		byte[] body = ("{\"secret\":\"" + SECRET + "\",\"deviceName\":\"" + deviceName + "\"}")
			.getBytes(StandardCharsets.UTF_8);
		servlet.doPost(TestImageServletPut.request("/", "application/json", body, Collections.emptyMap(), parameters),
			response.response());
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		return PairResponse.readPairResponse(reader(response.body()));
	}

	private static FakeResponse get(ImageServlet servlet, String pathInfo, String authorization) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "json");
		FakeResponse response = new FakeResponse();
		servlet.doGet(TestImageServletPut.request(pathInfo, null, new byte[0], headers(authorization), parameters),
			response.response());
		return response;
	}

	private static FakeResponse put(ImageServlet servlet, String pathInfo, String authorization) throws Exception {
		FakeResponse response = new FakeResponse();
		servlet.doPut(TestImageServletPut.request(pathInfo, "application/json",
			ALBUM_JSON.getBytes(StandardCharsets.UTF_8), headers(authorization), Collections.emptyMap()),
			response.response());
		return response;
	}

	private static Map<String, String> headers(String authorization) {
		Map<String, String> headers = new HashMap<>();
		if (authorization != null) {
			headers.put("Authorization", authorization);
		}
		return headers;
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
