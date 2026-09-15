/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeInputStream;
import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.Clearances;
import de.haumacher.imageServer.auth.InviteMode;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.SpaceMode;
import de.haumacher.imageServer.auth.SpaceStore;
import de.haumacher.imageServer.auth.Spaces;
import de.haumacher.imageServer.shared.model.AuthInfo;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.InvitationCreated;
import de.haumacher.imageServer.shared.model.PairResponse;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import de.haumacher.util.servlet.ResourceServlet;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.StringReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Test case for the space model of issue #82: the two modes, the addressing, the users of a space
 * and the bootstrap of its admin.
 *
 * <p>
 * The whole front door is driven headlessly: a {@link SpaceServlet} built on a temporary base
 * folder, with the request and response fakes of {@link TestImageServletPut}.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestSpaces extends TestCase {

	private static final String SECRET = "let-me-in";

	private Path _base;

	private Path _webRoot;

	private SpaceServlet _servlet;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-spaces");
		_webRoot = Files.createTempDirectory("valbum-spaces-web");
		Files.write(_webRoot.resolve("index.html"),
			"<html><head><base href=\"/\"></head><body>app</body></html>".getBytes(StandardCharsets.UTF_8));
		Files.write(_webRoot.resolve("main.dart.js"), "// app".getBytes(StandardCharsets.UTF_8));
	}

	@Override
	protected void tearDown() throws Exception {
		if (_servlet != null) {
			_servlet.destroy();
			_servlet = null;
		}
		delete(_base);
		delete(_webRoot);
		super.tearDown();
	}

	// --- How the mode is decided. ---

	/** A library that carries no space marker is the one space it always was. */
	public void testSingleModeWithoutMarkers() throws Exception {
		album("2024 Trip");

		Spaces spaces = detect(null, AuthMode.WRITES);
		assertEquals(SpaceMode.SINGLE, spaces.getMode());
		assertEquals(1, spaces.getSpaces().size());
		assertEquals("", spaces.single().getSegment());
		assertEquals(_base, spaces.single().getRoot());
		assertTrue(spaces.segments().isEmpty());
	}

	/** One marked folder is enough to make a multi-space server. */
	public void testMultiModeFromOneMarker() throws Exception {
		space("alice", "{\"name\":\"Alice\",\"anonymous\":\"public\"}");
		Files.createDirectories(_base.resolve("not-a-space"));

		Spaces spaces = detect(null, AuthMode.WRITES);
		assertEquals(SpaceMode.MULTI, spaces.getMode());
		assertEquals(java.util.Arrays.asList("alice"), spaces.segments());
		assertEquals("A folder without the marker is no space.", null, spaces.bySegment("not-a-space"));
		assertEquals("Alice", spaces.bySegment("alice").getConfig().getName());
		assertTrue(spaces.bySegment("alice").getConfig().isAnonymousAllowed());
	}

	/** The command line overrides the rule in both directions. */
	public void testForcedModes() throws Exception {
		space("alice", "{}");

		Spaces forcedSingle = detect(SpaceMode.SINGLE, AuthMode.WRITES);
		assertEquals(SpaceMode.SINGLE, forcedSingle.getMode());
		assertEquals("The marker is ignored: the base folder is the space.", _base,
			forcedSingle.single().getRoot());

		delete(_base.resolve("alice"));
		Spaces forcedMulti = detect(SpaceMode.MULTI, AuthMode.WRITES);
		assertEquals(SpaceMode.MULTI, forcedMulti.getMode());
		assertTrue("A multi-space server without markers hosts no space at all.",
			forcedMulti.getSpaces().isEmpty());
	}

	/** What a space says about itself, and what it says when it says nothing. */
	public void testSpaceConfig() throws Exception {
		space("plain", "{}");
		space("named", "{\"name\":\"The Family\",\"anonymous\":\"public\"}");
		space("odd", "{\"anonymous\":\"everyone\"}");

		Spaces spaces = detect(null, AuthMode.WRITES);
		assertEquals("An unnamed space is named by its folder.", "plain",
			spaces.bySegment("plain").getConfig().getName());
		assertFalse("A space is closed unless it says otherwise.",
			spaces.bySegment("plain").getConfig().isAnonymousAllowed());
		assertEquals("The Family", spaces.bySegment("named").getConfig().getName());
		assertTrue(spaces.bySegment("named").getConfig().isAnonymousAllowed());
		assertFalse("A space is never opened by a typo.",
			spaces.bySegment("odd").getConfig().isAnonymousAllowed());
		assertEquals(SpaceStore.ANONYMOUS_NONE, spaces.bySegment("odd").getConfig().getAnonymous());
	}

	/** Anonymous access is a property of the space, and the server-wide mode only ever closes. */
	public void testAnonymousIsAPropertyOfTheSpace() throws Exception {
		space("open", "{\"anonymous\":\"public\"}");
		space("closed", "{}");

		Spaces spaces = detect(null, AuthMode.WRITES);
		assertEquals(AuthMode.WRITES, spaces.bySegment("open").getAuth().getMode());
		assertEquals(AuthMode.ALL, spaces.bySegment("closed").getAuth().getMode());

		Spaces strict = detect(null, AuthMode.ALL);
		assertEquals("A server-wide 'all' is never loosened by a space.",
			AuthMode.ALL, strict.bySegment("open").getAuth().getMode());

		Spaces off = detect(null, AuthMode.OFF);
		assertEquals("'--auth off' stays off.", AuthMode.OFF, off.bySegment("open").getAuth().getMode());
	}

	// --- How a space is addressed. ---

	/** Each space answers for its own folder, below its own first path segment. */
	public void testEachSpaceAnswersForItsOwnFolder() throws Exception {
		space("alice", "{\"anonymous\":\"public\"}");
		space("bob", "{\"anonymous\":\"public\"}");
		album("alice/2024 Alice");
		album("bob/2024 Bob");

		String alice = body(get("/alice/data/", "json"));
		assertTrue(alice, alice.contains("2024 Alice"));
		assertFalse("Nothing of another space is visible here.", alice.contains("2024 Bob"));

		String bob = body(get("/bob/data/", "json"));
		assertTrue(bob, bob.contains("2024 Bob"));
		assertFalse(bob, bob.contains("2024 Alice"));
	}

	/** An album inside a space is addressed below the space, and answered in those coordinates. */
	public void testAlbumInsideASpace() throws Exception {
		space("alice", "{\"anonymous\":\"public\"}");
		album("alice/2024 Alice");

		FakeResponse response = get("/alice/data/2024 Alice/", "json");
		assertEquals(HttpServletResponse.SC_OK, response.status());
		Resource resource = Resource.readResource(reader(response.body()));
		assertTrue("Expected an album, got: " + resource, resource instanceof de.haumacher.imageServer.shared.model.AlbumInfo);
	}

	/** Every path the answer spells is spelled in the coordinates the caller used. */
	public void testAnswersAreSpelledInTheSpaceOfTheRequest() throws Exception {
		space("alice", "{\"anonymous\":\"public\"}");
		album("alice/2024 Alice");

		FakeResponse response = get("/alice/data/2024 Alice", "json");
		assertEquals(HttpServletResponse.SC_FOUND, response.status());
		assertEquals("The redirect must carry the space, or the app would leave it.",
			"/valbum/alice/data/2024 Alice/?type=json", response.header("Location"));
	}

	/** A name this server hosts no space for is refused, and says so. */
	public void testUnknownSpaceIsRefused() throws Exception {
		space("alice", "{\"anonymous\":\"public\"}");

		FakeResponse data = get("/nosuch/data/", "json");
		assertEquals(HttpServletResponse.SC_NOT_FOUND, data.status());
		assertEquals(SpaceServlet.noSuchSpace("nosuch"), error(data).getMessage());

		FakeResponse app = get("/nosuch/", null);
		assertEquals("A person who mistyped a space is told so, not shown an empty browser.",
			HttpServletResponse.SC_NOT_FOUND, app.status());
		assertEquals(SpaceServlet.noSuchSpace("nosuch"), error(app).getMessage());
	}

	/** The application is served below the space with its base rewritten onto it. */
	public void testTheApplicationIsRebasedOntoTheSpace() throws Exception {
		space("alice", "{\"anonymous\":\"public\"}");

		String page = body(get("/alice/", null));
		assertTrue("Expected the base on the space: " + page, page.contains("<base href=\"/valbum/alice/\">"));

		assertEquals("The application's own files keep working.", HttpServletResponse.SC_OK,
			get("/main.dart.js", null).status());
		String root = body(get("/", null));
		assertTrue("The context root keeps serving the application: " + root,
			root.contains("<base href=\"/valbum/\">"));
	}

	/** A session of a space lives under it: the address a share link and an invitation are opened at. */
	public void testSessionsOfASpaceLiveUnderIt() throws Exception {
		space("alice", "{\"anonymous\":\"public\"}");
		album("alice/2024 Alice");
		String token = pair("/alice/data/", "Alice", "Phone");

		FakeResponse created = post("/alice/data/2024 Alice/", "share", token,
			"{\"label\":\"Grandma\",\"expires\":\"\",\"maxPrivacy\":0,\"minRating\":0,"
				+ "\"rights\":[{\"name\":\"view\"}]}");
		assertEquals(created.body(), HttpServletResponse.SC_OK, created.status());
		ShareLinkCreated link = ShareLinkCreated.readShareLinkCreated(reader(created.body()));
		assertEquals("A share link of a space is opened under that space.",
			"/valbum/alice/s/" + link.getToken() + "/", link.getUrl());

		FakeResponse invited = post("/alice/data/", "invite", token,
			"{\"role\":\"member\",\"expires\":\"\",\"note\":\"\"}");
		assertEquals(invited.body(), HttpServletResponse.SC_OK, invited.status());
		InvitationCreated invitation = InvitationCreated.readInvitationCreated(reader(invited.body()));
		assertEquals("An invitation of a space is opened under that space.",
			"/valbum/alice/i/" + invitation.getToken() + "/", invitation.getUrl());

		// The application served there is rebased onto the session, so it asks the space's data root.
		String page = body(get("/alice/s/" + link.getToken() + "/", null));
		assertTrue("Expected the base on the session: " + page,
			page.contains("<base href=\"/valbum/alice/s/" + link.getToken() + "/\">"));
		String invitationPage = body(get("/alice/i/" + invitation.getToken() + "/", null));
		assertTrue("Expected the base on the session: " + invitationPage,
			invitationPage.contains("<base href=\"/valbum/alice/i/" + invitation.getToken() + "/\">"));
	}

	/** At the context root of a multi-space server a session address leads nowhere, and says why. */
	public void testRootLevelSessionIsRefused() throws Exception {
		space("alice", "{\"anonymous\":\"public\"}");

		FakeResponse share = get("/s/whatever/", null);
		assertEquals("The app shell served there could never load anything.",
			HttpServletResponse.SC_NOT_FOUND, share.status());
		assertEquals(SpaceServlet.sessionNeedsSpace("s"), error(share).getMessage());

		FakeResponse invitation = get("/i/whatever/", null);
		assertEquals(HttpServletResponse.SC_NOT_FOUND, invitation.status());
		assertEquals(SpaceServlet.sessionNeedsSpace("i"), error(invitation).getMessage());
	}

	// --- The users of a space. ---

	/** The answer names the space, the role, the clearance and the share flag. */
	public void testAuthNamesTheSpace() throws Exception {
		space("alice", "{\"anonymous\":\"public\"}");

		AuthInfo anonymous = auth("/alice/data/", null);
		assertEquals("alice", anonymous.getSpace());
		assertEquals("", anonymous.getUserName());
		assertEquals("An anonymous caller holds no clearance of their own.", "", anonymous.getClearance());
		assertFalse(anonymous.isMayShare());

		String token = pair("/alice/data/", "Alice", "Phone");
		AuthInfo signedIn = auth("/alice/data/", token);
		assertEquals("alice", signedIn.getSpace());
		assertEquals("Alice", signedIn.getUserName());
		assertEquals(Roles.ADMIN, signedIn.getRole());
		assertEquals("The admin of a space sees everything in it.", Clearances.ALL, signedIn.getClearance());
		assertTrue("The admin of a space may share.", signedIn.isMayShare());
	}

	/** The pairing secret makes the first admin of a space, and only further devices afterwards. */
	public void testTheSecretMakesTheFirstAdminOfASpace() throws Exception {
		space("alice", "{\"anonymous\":\"public\"}");

		String first = pair("/alice/data/", "Alice", "Phone");
		assertNotNull(first);

		// The same person adds a device: told apart from a stranger by the user name.
		FakeResponse second = post("/alice/data/", "pair",
			"{\"secret\":\"" + SECRET + "\",\"userName\":\"Alice\",\"deviceName\":\"Tablet\"}");
		assertEquals(HttpServletResponse.SC_OK, second.status());

		FakeResponse stranger = post("/alice/data/", "pair",
			"{\"secret\":\"" + SECRET + "\",\"userName\":\"Mallory\",\"deviceName\":\"Laptop\"}");
		assertEquals("A second person cannot become admin through the secret.",
			HttpServletResponse.SC_UNAUTHORIZED, stranger.status());

		assertEquals("Two devices of one admin.", 2, devices("alice"));
	}

	/** Each space bootstraps its own admin; the secret is the server's, the admin is the space's. */
	public void testEachSpaceHasItsOwnAdmin() throws Exception {
		space("alice", "{\"anonymous\":\"public\"}");
		space("bob", "{\"anonymous\":\"public\"}");

		pair("/alice/data/", "Alice", "Phone");
		pair("/bob/data/", "Bob", "Phone");

		assertEquals("Alice", ownerName("alice"));
		assertEquals("Bob", ownerName("bob"));
		assertTrue("Each space keeps its own user store.",
			Files.isRegularFile(_base.resolve("alice").resolve(".valbum").resolve("users.json")));
		assertTrue(Files.isRegularFile(_base.resolve("bob").resolve(".valbum").resolve("users.json")));
		assertFalse("Nothing of the users is written at the base folder.",
			Files.exists(_base.resolve(".valbum").resolve("users.json")));
	}

	/** A token of one space means nothing in another. */
	public void testTokenOfOneSpaceIsAnonymousInAnother() throws Exception {
		space("alice", "{\"anonymous\":\"public\"}");
		space("bob", "{\"anonymous\":\"public\"}");
		album("bob/2024 Bob");

		String aliceToken = pair("/alice/data/", "Alice", "Phone");

		AuthInfo inBob = auth("/bob/data/", aliceToken);
		assertEquals("A device of another space is nobody here.", "", inBob.getUserName());
		assertEquals("", inBob.getRole());
	}

	/** A closed space refuses a token of another space as it refuses everybody. */
	public void testClosedSpaceRefusesAForeignToken() throws Exception {
		space("alice", "{\"anonymous\":\"public\"}");
		space("bob", "{}");
		album("bob/2024 Bob");

		String aliceToken = pair("/alice/data/", "Alice", "Phone");

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, get("/bob/data/", "json", aliceToken).status());
		assertEquals("The space's own admin gets in.", HttpServletResponse.SC_OK,
			get("/bob/data/", "json", pair("/bob/data/", "Bob", "Phone")).status());
	}

	/** A single-space server keeps today's addresses and says it has no space of its own. */
	public void testSingleModeKeepsTodaysAnswers() throws Exception {
		album("2024 Trip");
		Spaces spaces = detect(null, AuthMode.WRITES);
		ImageServlet servlet = new ImageServlet(_base.toFile(), spaces.single().getAuth());
		try {
			servlet.init(config());
			FakeResponse response = new FakeResponse();
			Map<String, String> parameters = new HashMap<>();
			parameters.put("type", "auth");
			servlet.doGet(request("GET", "/", parameters, Collections.emptyMap(), new byte[0], null),
				response.response());
			AuthInfo info = AuthInfo.readAuthInfo(reader(response.body()));
			assertEquals("A single-space server names no space.", "", info.getSpace());
		} finally {
			servlet.destroy();
		}
	}

	// --- Helpers. ---

	private String ownerName(String space) throws Exception {
		return new de.haumacher.imageServer.auth.UserStore(_base.resolve(space)).getOwner().getName();
	}

	private int devices(String space) throws Exception {
		return new de.haumacher.imageServer.auth.UserStore(_base.resolve(space)).getOwner().getDevices().size();
	}

	private Spaces detect(SpaceMode forced, AuthMode mode) throws Exception {
		return Spaces.detect(_base, forced, mode, SECRET, InviteMode.MEMBERS);
	}

	/** A folder below the base that is a space, with the given <code>space.json</code>. */
	private void space(String name, String config) throws Exception {
		Path root = _base.resolve(name);
		Files.createDirectories(root.resolve(".valbum"));
		Files.write(root.resolve(".valbum").resolve("space.json"), config.getBytes(StandardCharsets.UTF_8));
	}

	/** An album folder with one tiny image. */
	private void album(String path) throws Exception {
		Path folder = _base.resolve(path);
		Files.createDirectories(folder);
		ImageIO.write(new BufferedImage(4, 3, BufferedImage.TYPE_3BYTE_BGR), "jpg",
			folder.resolve("image.jpg").toFile());
	}

	private SpaceServlet servlet() throws Exception {
		if (_servlet == null) {
			Spaces spaces = detect(null, AuthMode.WRITES);
			ResourceServlet app = new ResourceServlet(_webRoot, "/data", "s", "i");
			app.setBaseSegments(spaces.segments());
			_servlet = new SpaceServlet(spaces, app);
			_servlet.init(config());
		}
		return _servlet;
	}

	private FakeResponse get(String pathInfo, String type) throws Exception {
		return get(pathInfo, type, null);
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
		servlet().service(request("GET", pathInfo, parameters, headers, new byte[0], null), response.response());
		return response;
	}

	private FakeResponse post(String pathInfo, String action, String body) throws Exception {
		return post(pathInfo, action, null, body);
	}

	private FakeResponse post(String pathInfo, String action, String token, String body) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", action);
		Map<String, String> headers = new HashMap<>();
		if (token != null) {
			headers.put("Authorization", "Bearer " + token);
		}
		FakeResponse response = new FakeResponse();
		servlet().service(request("POST", pathInfo, parameters, headers,
			body.getBytes(StandardCharsets.UTF_8), "application/json"), response.response());
		return response;
	}

	/** Pairs a device against the server-wide secret and answers its token. */
	private String pair(String pathInfo, String userName, String deviceName) throws Exception {
		FakeResponse response = post(pathInfo, "pair", "{\"secret\":\"" + SECRET
			+ "\",\"userName\":\"" + userName + "\",\"deviceName\":\"" + deviceName + "\"}");
		assertEquals("Pairing failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return PairResponse.readPairResponse(reader(response.body())).getToken();
	}

	private AuthInfo auth(String pathInfo, String token) throws Exception {
		FakeResponse response = get(pathInfo, "auth", token);
		assertEquals("Unexpected answer: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return AuthInfo.readAuthInfo(reader(response.body()));
	}

	private static ErrorInfo error(FakeResponse response) throws Exception {
		Resource resource = Resource.readResource(reader(response.body()));
		assertTrue("Expected an error, got: " + resource, resource instanceof ErrorInfo);
		return (ErrorInfo) resource;
	}

	private static String body(FakeResponse response) {
		assertEquals("Unexpected answer: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return response.body();
	}

	private static JsonReader reader(String contents) {
		return new JsonReader(new ReaderAdapter(new StringReader(contents)));
	}

	private static void delete(Path dir) throws Exception {
		if (dir == null || !Files.exists(dir)) {
			return;
		}
		try (Stream<Path> files = Files.walk(dir)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
	}

	/** A request the whole front door can be driven with, method and all. */
	static HttpServletRequest request(String method, String pathInfo, Map<String, String> parameters,
			Map<String, String> headers, byte[] body, String contentType) {
		InvocationHandler handler = (proxy, m, args) -> {
			switch (m.getName()) {
				case "getMethod":
					return method;
				case "getPathInfo":
					return pathInfo;
				case "getContentType":
					return contentType;
				case "getCharacterEncoding":
					return "utf-8";
				case "getContentLength":
					return Integer.valueOf(body.length);
				case "getContentLengthLong":
					return Long.valueOf(body.length);
				case "getInputStream":
					return new FakeInputStream(body);
				case "getHeader":
					return headers.get(args[0]);
				case "getParameter":
					return parameters.get(args[0]);
				case "getContextPath":
					return "/valbum";
				case "getServletPath":
					return "";
				case "getProtocol":
					return "HTTP/1.1";
				case "getServletContext":
					return context();
				case "toString":
					return "FakeRequest[" + method + " " + pathInfo + "]";
				default:
					throw new UnsupportedOperationException("Unexpected request method in test: " + m.getName());
			}
		};
		return (HttpServletRequest) Proxy.newProxyInstance(TestSpaces.class.getClassLoader(),
			new Class<?>[] { HttpServletRequest.class }, handler);
	}

	private static ServletContext context() {
		return (ServletContext) Proxy.newProxyInstance(TestSpaces.class.getClassLoader(),
			new Class<?>[] { ServletContext.class }, (proxy, m, args) -> {
				switch (m.getName()) {
					case "getMimeType":
						return "image/jpeg";
					case "toString":
						return "FakeServletContext";
					default:
						throw new UnsupportedOperationException(
							"Unexpected servlet context method in test: " + m.getName());
				}
			});
	}

	/** A servlet configuration the servlets below the front door can be initialised with. */
	static ServletConfig config() {
		return (ServletConfig) Proxy.newProxyInstance(TestSpaces.class.getClassLoader(),
			new Class<?>[] { ServletConfig.class }, (proxy, m, args) -> {
				switch (m.getName()) {
					case "getServletName":
						return "test";
					case "getServletContext":
						return context();
					case "getInitParameter":
						return null;
					case "getInitParameterNames":
						return Collections.enumeration(Collections.<String> emptyList());
					case "toString":
						return "FakeServletConfig";
					default:
						throw new UnsupportedOperationException(
							"Unexpected servlet config method in test: " + m.getName());
				}
			});
	}

}
