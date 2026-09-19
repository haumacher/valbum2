/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.Clearances;
import de.haumacher.imageServer.auth.DeviceCodeStore;
import de.haumacher.imageServer.auth.InviteMode;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.SpaceMode;
import de.haumacher.imageServer.auth.Spaces;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.ui.Settings;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import de.haumacher.util.servlet.ResourceServlet;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.StringReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Test case for issue #88: a page load of a dead invitation address is the start page of its
 * space, with a word why.
 *
 * <p>
 * The whole front door is driven headlessly, as {@link TestSpaces} drives it: the real
 * {@link ResourceServlet} with the {@link InvitationRedirect} the server wires into it, and the
 * real {@link ImageServlet} of the space beside it, so that an invitation is accepted the way a
 * browser accepts it.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestInvitationRedirect extends TestCase {

	private static final String SECRET = "let-me-in";

	private Path _base;

	private Path _webRoot;

	private Spaces _spaces;

	private ResourceServlet _app;

	private final Map<String, ImageServlet> _data = new HashMap<>();

	private SpaceServlet _front;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-invitation-redirect");
		_webRoot = Files.createTempDirectory("valbum-invitation-redirect-web");
		Files.write(_webRoot.resolve("index.html"),
			"<html><head><base href=\"/\"></head><body>app</body></html>".getBytes(StandardCharsets.UTF_8));
		Files.write(_webRoot.resolve("main.dart.js"), "// app".getBytes(StandardCharsets.UTF_8));
	}

	@Override
	protected void tearDown() throws Exception {
		if (_front != null) {
			_front.destroy();
			_front = null;
		} else {
			for (ImageServlet servlet : _data.values()) {
				servlet.destroy();
			}
			if (_app != null) {
				_app.destroy();
			}
		}
		_data.clear();
		_app = null;
		_spaces = null;
		delete(_base);
		delete(_webRoot);
		super.tearDown();
	}

	// --- A single-space server. ---

	/** A live invitation is served exactly as before: the app, rebased onto the session. */
	public void testALiveInvitationIsServedAsBefore() throws Exception {
		single();
		String token = issue("", Roles.VIEW, tomorrow());

		FakeResponse page = get("/i/" + token + "/");
		assertEquals(HttpServletResponse.SC_OK, page.status());
		assertTrue("Expected the base on the session: " + page.body(),
			page.body().contains("<base href=\"/valbum/i/" + token + "/\">"));
	}

	/** The second click on the link somebody joined with: the start page, and a word why. */
	public void testAnAcceptedInvitationRedirectsToTheStartPage() throws Exception {
		single();
		String token = issue("", Roles.VIEW, tomorrow());
		String store = storeContents("");

		FakeResponse joined = post("/data/", "pair",
			"{\"invitation\":\"" + token + "\",\"userName\":\"carol\",\"deviceName\":\"Phone\"}");
		assertEquals(joined.body(), HttpServletResponse.SC_OK, joined.status());

		FakeResponse again = get("/i/" + token + "/");
		assertEquals(HttpServletResponse.SC_FOUND, again.status());
		assertEquals("/valbum/?invitation=used", again.header("Location"));
		assertFalse("The store was written by the joining, not by the page load.",
			store.equals(storeContents("")));
	}

	/** Each ending of an invitation is named by the reason it redirects with. */
	public void testEveryDeadInvitationNamesItsReason() throws Exception {
		single();

		String expired = issue("", Roles.VIEW, Instant.now().minus(Duration.ofDays(1)).toString());
		assertEquals("/valbum/?invitation=expired", location(get("/i/" + expired + "/")));

		String withdrawn = issue("", Roles.VIEW, tomorrow());
		auth("").uninvite(code("", withdrawn).getId());
		assertEquals("/valbum/?invitation=withdrawn", location(get("/i/" + withdrawn + "/")));

		assertEquals("A token nobody issued is not an invitation of this server.",
			"/valbum/?invitation=unknown", location(get("/i/nobody-issued-this/")));
	}

	/** Looking at an address changes nothing: the check is read-only. */
	public void testThePageLoadLeavesTheInvitationAlone() throws Exception {
		single();
		String live = issue("", Roles.VIEW, tomorrow());
		String dead = issue("", Roles.VIEW, Instant.now().minus(Duration.ofDays(1)).toString());
		String before = storeContents("");

		assertEquals(HttpServletResponse.SC_OK, get("/i/" + live + "/").status());
		assertEquals(HttpServletResponse.SC_FOUND, get("/i/" + dead + "/").status());
		assertEquals(HttpServletResponse.SC_FOUND, get("/i/" + dead + "/some/album/").status());

		assertEquals("A page load never uses up, expires or withdraws anything.", before,
			storeContents(""));
		assertFalse("The live invitation is still live.", code("", live).isUsed());
	}

	/** A deep link below a dead base is a page load, too, and lands on the same start page. */
	public void testADeepLinkBelowADeadBaseRedirectsAsWell() throws Exception {
		single();
		String token = issue("", Roles.VIEW, Instant.now().minus(Duration.ofDays(1)).toString());

		assertEquals("/valbum/?invitation=expired", location(get("/i/" + token + "/some/album/")));
		assertEquals("Without a trailing slash, too.", "/valbum/?invitation=expired",
			location(get("/i/" + token + "/some/album")));
	}

	/** The application's own files are served below a dead base as they always were. */
	public void testAnAssetBelowADeadBaseIsServed() throws Exception {
		single();
		String token = issue("", Roles.VIEW, Instant.now().minus(Duration.ofDays(1)).toString());

		FakeResponse asset = get("/i/" + token + "/main.dart.js");
		assertEquals("A page being served needs its files, whatever the token says.",
			HttpServletResponse.SC_OK, asset.status());
		assertEquals("// app", asset.body());
	}

	/** A share link is none of this: it is served exactly as it was. */
	public void testAShareLinkIsUntouched() throws Exception {
		single();
		String token = issue("", Roles.VIEW, Instant.now().minus(Duration.ofDays(1)).toString());

		FakeResponse page = get("/s/" + token + "/");
		assertEquals("A share visitor has no account to fall back on; the 410 page stays.",
			HttpServletResponse.SC_OK, page.status());
		assertTrue(page.body(), page.body().contains("<base href=\"/valbum/s/" + token + "/\">"));
	}

	// --- A multi-space server. ---

	/** The start page a space's dead invitation leads to is that space's own. */
	public void testTheStartPageOfASpace() throws Exception {
		multi("alice", "bob");
		String token = issue("alice", Roles.VIEW, tomorrow());

		FakeResponse live = get("/alice/i/" + token + "/");
		assertEquals(HttpServletResponse.SC_OK, live.status());
		assertTrue(live.body(), live.body().contains("<base href=\"/valbum/alice/i/" + token + "/\">"));

		auth("alice").getDeviceCodes().markUsed(code("alice", token).getId(), "some-device");
		assertEquals("/valbum/alice/?invitation=used", location(get("/alice/i/" + token + "/")));
	}

	/** A space knows only its own invitations: another space's token is simply unknown there. */
	public void testATokenOfAnotherSpaceIsUnknown() throws Exception {
		multi("alice", "bob");
		String token = issue("alice", Roles.VIEW, tomorrow());

		assertEquals("/valbum/bob/?invitation=unknown", location(get("/bob/i/" + token + "/")));
		assertEquals("And it is still live where it belongs.", HttpServletResponse.SC_OK,
			get("/alice/i/" + token + "/").status());
	}

	/** At the context root of a multi-space server a session address still says where it belongs. */
	public void testARootLevelSessionStillNeedsItsSpace() throws Exception {
		multi("alice", "bob");
		String token = issue("alice", Roles.VIEW, tomorrow());

		FakeResponse response = get("/i/" + token + "/");
		assertEquals(HttpServletResponse.SC_NOT_FOUND, response.status());
		assertEquals(SpaceServlet.sessionNeedsSpace("i"), error(response).getMessage());
	}

	// --- The harness. ---

	/** A single-space server on the base folder. */
	void single() throws Exception {
		_spaces = Spaces.detect(_base, SpaceMode.SINGLE, AuthMode.WRITES, InviteMode.MEMBERS);
		_app = new ResourceServlet(_webRoot, Settings.DATA_PREFIX, "s", "i");
		_app.setSessionGuard(new InvitationRedirect(_spaces));
		_app.init(config());
		ImageServlet data = new ImageServlet(_base.toFile(), _spaces.single().getAuth());
		data.init(config());
		_data.put("", data);
	}

	/** A multi-space server hosting the given spaces. */
	void multi(String... segments) throws Exception {
		for (String segment : segments) {
			Path root = _base.resolve(segment);
			Files.createDirectories(root.resolve(".valbum"));
			Files.write(root.resolve(".valbum").resolve("space.json"),
				"{\"anonymous\":\"public\"}".getBytes(StandardCharsets.UTF_8));
		}
		_spaces = Spaces.detect(_base, null, AuthMode.WRITES, InviteMode.MEMBERS);
		assertEquals(SpaceMode.MULTI, _spaces.getMode());
		_app = new ResourceServlet(_webRoot, Settings.DATA_PREFIX, "s", "i");
		_app.setBaseSegments(_spaces.segments());
		_app.setSessionGuard(new InvitationRedirect(_spaces));
		_front = new SpaceServlet(_spaces, _app);
		_front.init(config());
	}

	/** The authentication of the given space. */
	AuthService auth(String segment) {
		return _spaces.bySegment(segment).getAuth();
	}

	/** The code of the given invitation token, whatever became of it (issue #89). */
	DeviceCodeStore.Code code(String segment, String token) {
		return auth(segment).getDeviceCodes().lookup(token);
	}

	/** Issues an invitation of the given space directly, and answers its token. */
	String issue(String segment, String role, String expires) throws Exception {
		return auth(segment)
			.createInvitation("alice", "", role, Clearances.ofRole(role), false, "Welcome", expires, "")
			.getToken();
	}

	/** The bytes of the given space's code store, to tell a write from a read. */
	private String storeContents(String segment) throws Exception {
		Path file = auth(segment).getDeviceCodes().getFile();
		return Files.exists(file) ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8) : "";
	}

	private static String tomorrow() {
		return Instant.now().plus(Duration.ofDays(1)).toString();
	}

	static String location(FakeResponse response) {
		assertEquals("Expected a redirect, got " + response.status() + ": " + response.body(),
			HttpServletResponse.SC_FOUND, response.status());
		return response.header("Location");
	}

	FakeResponse get(String pathInfo) throws Exception {
		FakeResponse response = new FakeResponse();
		service("GET", pathInfo, new HashMap<>(), new byte[0], null, response);
		return response;
	}

	FakeResponse post(String pathInfo, String action, String body) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", action);
		FakeResponse response = new FakeResponse();
		service("POST", pathInfo, parameters, body.getBytes(StandardCharsets.UTF_8), "application/json",
			response);
		return response;
	}

	/**
	 * Hands the request to the servlet the server's own wiring would hand it to.
	 *
	 * <p>
	 * On a multi-space server that is the {@link SpaceServlet}; on a single-space one the
	 * {@link ImageServlet} is mapped below <code>/data</code> and the {@link ResourceServlet} takes
	 * everything else, which is what is spelled out here.
	 * </p>
	 */
	private void service(String method, String pathInfo, Map<String, String> parameters, byte[] body,
			String contentType, FakeResponse response) throws Exception {
		HttpServletRequest request =
			TestSpaces.request(method, pathInfo, parameters, new HashMap<>(), body, contentType);
		if (_front != null) {
			_front.service(request, response.response());
			return;
		}
		if (pathInfo.equals(Settings.DATA_PREFIX) || pathInfo.startsWith(Settings.DATA_PREFIX + "/")) {
			_data.get("").service(
				new SpaceServlet.InSpace(request, Settings.DATA_PREFIX,
					pathInfo.substring(Settings.DATA_PREFIX.length())),
				response.response());
			return;
		}
		_app.service(request, response.response());
	}

	private static ErrorInfo error(FakeResponse response) throws Exception {
		Resource resource = Resource.readResource(
			new JsonReader(new ReaderAdapter(new StringReader(response.body()))));
		assertTrue("Expected an error, got: " + resource, resource instanceof ErrorInfo);
		return (ErrorInfo) resource;
	}

	private static ServletConfig config() {
		return (ServletConfig) Proxy.newProxyInstance(TestInvitationRedirect.class.getClassLoader(),
			new Class<?>[] { ServletConfig.class }, (proxy, m, args) -> {
				switch (m.getName()) {
					case "getServletContext":
						return context();
					case "getInitParameterNames":
						return java.util.Collections.emptyEnumeration();
					case "getServletName":
						return "test";
					case "toString":
						return "FakeServletConfig";
					default:
						return null;
				}
			});
	}

	private static ServletContext context() {
		return (ServletContext) Proxy.newProxyInstance(TestInvitationRedirect.class.getClassLoader(),
			new Class<?>[] { ServletContext.class }, (proxy, m, args) -> {
				switch (m.getName()) {
					case "getMimeType":
						return "text/javascript";
					case "toString":
						return "FakeServletContext";
					default:
						return null;
				}
			});
	}

	private static void delete(Path dir) throws Exception {
		if (dir == null || !Files.exists(dir)) {
			return;
		}
		try (Stream<Path> files = Files.walk(dir)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
	}

}
