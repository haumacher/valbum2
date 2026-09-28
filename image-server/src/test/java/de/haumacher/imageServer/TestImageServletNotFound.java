/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.PairResponse;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
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
 * Test case for how an address naming no folder or file is answered, see issue #176.
 *
 * <p>
 * Nothing is malformed about such an address: it is <code>404</code> with an {@link ErrorInfo}
 * naming the segment that is missing, whichever form of the address was asked for and whoever
 * asks. A request that really is malformed — an unknown <code>type</code> of a folder — is
 * <code>400</code>.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestImageServletNotFound extends TestCase {

	private static final String ALBUM = "2020-05-01 Trip";

	private Path _base;

	private AuthService _auth;

	private ImageServlet _servlet;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-not-found-test");
		Path album = Files.createDirectory(_base.resolve(ALBUM));
		Files.write(album.resolve("index.json"),
			"[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[]}]".getBytes(StandardCharsets.UTF_8));
		_auth = new AuthService(AuthMode.WRITES, _base);
		_servlet = new ImageServlet(_base.toFile(), _auth);
	}

	@Override
	protected void tearDown() throws Exception {
		if (_servlet != null) {
			_servlet.destroy();
		}
		if (_base != null) {
			try (Stream<Path> files = Files.walk(_base)) {
				files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
		super.tearDown();
	}

	public void testJsonOfNothingIsNotFound() throws Exception {
		assertNotFound(get("/nonexistent/", "json", null), "nonexistent");
	}

	public void testJsonWithoutTrailingSlashIsNotFound() throws Exception {
		assertNotFound(get("/nonexistent", "json", null), "nonexistent");
	}

	public void testFolderFormOfNothingIsNotFound() throws Exception {
		assertNotFound(get("/nonexistent/", null, null), "nonexistent");
	}

	public void testFileFormOfNothingIsNotFound() throws Exception {
		assertNotFound(get("/nonexistent", null, null), "nonexistent");
	}

	/** The reported case of issue #176: the context path read as an album. */
	public void testContextPathReadAsAnAlbumIsNotFound() throws Exception {
		assertNotFound(get("/valbum/", "json", null), "valbum");
	}

	/** The segment named is the first one that is missing, not the last one asked for. */
	public void testTheFirstMissingSegmentIsNamed() throws Exception {
		assertNotFound(get("/nonexistent/deeper/IMG_1.jpg", "json", null), "nonexistent");
		assertNotFound(get("/" + ALBUM + "/IMG_1.jpg", "json", null), "IMG_1.jpg");
	}

	/** A browser on the same origin carries a device token: it changes nothing about a 404. */
	public void testSignedInCallerIsAnsweredTheSame() throws Exception {
		String token = pair();
		assertNotFound(get("/valbum/", "json", token), "valbum");
		assertNotFound(get("/valbum", null, token), "valbum");
	}

	public void testAFileThatIsNoPhotographIsNotFound() throws Exception {
		Files.write(_base.resolve(ALBUM).resolve("notes.txt"), "x".getBytes(StandardCharsets.UTF_8));
		assertNotFound(get("/" + ALBUM + "/notes.txt", null, null), "notes.txt");
	}

	public void testARealAlbumIsAnswered() throws Exception {
		FakeResponse response = get("/" + ALBUM + "/", "json", null);

		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		assertTrue(response.body(), response.body().startsWith("[\"AlbumInfo\""));
	}

	public void testAnUnknownTypeOfARealAlbumIsMalformed() throws Exception {
		FakeResponse response = get("/" + ALBUM + "/", "bogus", null);

		assertEquals(response.body(), HttpServletResponse.SC_BAD_REQUEST, response.status());
		assertEquals(ImageServlet.unknownType("bogus"), errorMessage(response));
	}

	private void assertNotFound(FakeResponse response, String segment) throws Exception {
		assertEquals(response.body(), HttpServletResponse.SC_NOT_FOUND, response.status());
		assertEquals(ImageServlet.notFound(segment), errorMessage(response));
		assertEquals("no-store", response.header("Cache-Control"));
	}

	private String pair() throws Exception {
		String code = _auth.issueSeatCode(null).getCode();
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "pair");
		String body = "{\"deviceCode\":\"" + code + "\",\"userName\":\"haui\",\"deviceName\":\"Browser\"}";
		FakeResponse response = new FakeResponse();
		_servlet.doPost(TestImageServletPut.request("/", "application/json", body.getBytes(StandardCharsets.UTF_8),
			Collections.emptyMap(), parameters), response.response());
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		return PairResponse.readPairResponse(reader(response.body())).getToken();
	}

	private FakeResponse get(String pathInfo, String type, String token) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		if (type != null) {
			parameters.put("type", type);
		}
		Map<String, String> headers = new HashMap<>();
		if (token != null) {
			headers.put("Authorization", "Bearer " + token);
		}
		FakeResponse response = new FakeResponse();
		_servlet.doGet(TestImageServletPut.request(pathInfo, null, new byte[0], headers, parameters),
			response.response());
		return response;
	}

	private static String errorMessage(FakeResponse response) throws Exception {
		Resource resource = Resource.readResource(reader(response.body()));
		assertTrue("Expected an ErrorInfo body, got: " + response.body(), resource instanceof ErrorInfo);
		return ((ErrorInfo) resource).getMessage();
	}

	private static JsonReader reader(String contents) {
		return new JsonReader(new ReaderAdapter(new StringReader(contents)));
	}

}
