/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.InviteMode;
import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.auth.ShareStore;
import de.haumacher.imageServer.auth.SpaceStore;
import de.haumacher.imageServer.auth.Spaces;
import de.haumacher.imageServer.shared.model.AnonymousAccess;
import de.haumacher.imageServer.shared.model.AuthInfo;
import de.haumacher.imageServer.shared.model.ContentSwitch;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.SpaceSettings;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import de.haumacher.util.servlet.ResourceServlet;
import java.io.File;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Test case for the settings of a space made by its administrator in the app, see issue #297 and
 * {@link SpaceConfiguration}: read, written, refused, and in effect without a restart.
 *
 * <p>
 * Driven through the front door of a multi-space server, so that what a save changes is seen
 * where every request sees it: the space <code>fam</code> holds the users of the
 * {@link SharingFixture}, alice its administrator.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestSpaceSettings extends TestCase {

	private static final String SPACE = "fam";

	private static final String DATA = "/" + SPACE + "/data/";

	private Path _base;

	private Path _root;

	private Path _webRoot;

	private Spaces _spaces;

	private SpaceServlet _servlet;

	private ImageServlet _single;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-space-settings");
		_root = _base.resolve(SPACE);
		_webRoot = Files.createTempDirectory("valbum-space-settings-web");
		Files.write(_webRoot.resolve("index.html"),
			"<html><head><base href=\"/\"></head><body>app</body></html>".getBytes(StandardCharsets.UTF_8));
		SharingFixture.create(_root);
		spaceJson("{\"version\":1,\"name\":\"The Family\",\"anonymous\":\"none\",\"faces\":\"off\"}");
	}

	@Override
	protected void tearDown() throws Exception {
		if (_servlet != null) {
			_servlet.destroy();
			_servlet = null;
		}
		if (_single != null) {
			_single.destroy();
			_single = null;
		}
		delete(_base);
		delete(_webRoot);
		super.tearDown();
	}

	// --- Reading and writing. ---

	public void testTheAdministratorReadsTheSettings() throws Exception {
		SpaceSettings settings = read(SharingFixture.ALICE);
		assertEquals("The Family", settings.getName());
		assertEquals(AnonymousAccess.NONE, settings.getAnonymous());
		assertEquals("A multi-space server started with --auth writes lets the space decide.", "",
			settings.getAnonymousFixed());
		assertFalse(settings.isFaces());
		assertEquals("The default map, where the file names none.", SpaceStore.DEFAULT_MAP_URL, settings.getMapUrl());
		assertEquals(SpaceStore.DEFAULT_MAP_URL, settings.getDefaultMapUrl());
		assertEquals("", settings.getTimeZone());
		assertEquals(ZoneId.systemDefault().getId(), settings.getServerTimeZone());
		assertEquals(SpaceStore.DEFAULT_INBOX, settings.getInbox());
		assertEquals("An inbox nobody uploaded into yet can move.", "", settings.getInboxFixed());
	}

	public void testASaveIsStoredAndAnsweredAsStored() throws Exception {
		SpaceSettings wanted = read(SharingFixture.ALICE)
			.setName("Haumachers")
			.setMapUrl("https://www.openstreetmap.org/?mlat={lat}&mlon={lon}")
			.setTimeZone("Europe/Berlin")
			.setInbox("Neu/Eingang");
		FakeResponse saved = save(SharingFixture.ALICE, wanted);
		assertEquals(saved.body(), 200, saved.status());
		SpaceSettings answered = settings(saved);
		assertEquals("Haumachers", answered.getName());
		assertEquals("https://www.openstreetmap.org/?mlat={lat}&mlon={lon}", answered.getMapUrl());
		assertEquals("Europe/Berlin", answered.getTimeZone());
		assertEquals("Neu/Eingang", answered.getInbox());
		assertEquals("What the save answered is what is read afterwards.", write(answered),
			write(read(SharingFixture.ALICE)));

		SpaceStore.Config onDisk = SpaceStore.load(_root, SPACE);
		assertEquals("Haumachers", onDisk.getName());
		assertEquals("Europe/Berlin", onDisk.getTimeZone());
		assertEquals("Neu/Eingang", onDisk.getInbox());
		assertEquals("The space everybody else asks reads the same.", "Haumachers",
			_spaces.bySegment(SPACE).getConfig().getName());

		AuthInfo auth = auth(SharingFixture.ALICE);
		assertEquals("The map is answered from the next request on.",
			"https://www.openstreetmap.org/?mlat={lat}&mlon={lon}", auth.getMapUrl());
		assertEquals("The inbox moved; the app reads it from ?type=auth.", "Neu/Eingang", auth.getInbox());
		assertTrue(Inboxes.isInbox(_root.resolve("Neu").resolve("Eingang").toFile()));
		assertFalse(Inboxes.isInbox(_root.resolve(SpaceStore.DEFAULT_INBOX).toFile()));
		assertEquals("The zone dates what is read from now on.", ZoneId.of("Europe/Berlin"),
			_servlet.dataOf(SPACE).cache().zone());
	}

	public void testReadWriteReadIsTheSame() throws Exception {
		SpaceSettings first = read(SharingFixture.ALICE);
		byte[] before = Files.readAllBytes(SpaceStore.file(_root));
		FakeResponse saved = save(SharingFixture.ALICE, first);
		assertEquals(saved.body(), 200, saved.status());
		assertEquals(write(first), write(settings(saved)));
		assertEquals(write(first), write(read(SharingFixture.ALICE)));
		assertEquals("Saving what was read leaves the default map and inbox unwritten.",
			new String(before, StandardCharsets.UTF_8),
			new String(Files.readAllBytes(SpaceStore.file(_root)), StandardCharsets.UTF_8));
	}

	public void testAKeyThisBuildDoesNotKnowSurvivesASave() throws Exception {
		spaceJson("{\"version\":1,\"name\":\"The Family\",\"anonymous\":\"none\",\"faces\":\"off\","
			+ "\"content\":\"on\",\"later\":{\"list\":[1,2.50,true,null,\"x\"],\"nested\":{}}}");
		FakeResponse saved = save(SharingFixture.ALICE, read(SharingFixture.ALICE).setName("Renamed"));
		assertEquals(saved.body(), 200, saved.status());
		String written = new String(Files.readAllBytes(SpaceStore.file(_root)), StandardCharsets.UTF_8);
		assertTrue(written, written.contains("\"name\":\"Renamed\""));
		assertTrue(written, written.contains("\"content\":\"on\""));
		assertEquals("Known now, and kept.", ContentSwitch.ON, read(SharingFixture.ALICE).getContent());
		assertTrue(written, written.contains("\"later\":{\"list\":[1,2.50,true,null,\"x\"],\"nested\":{}}"));

		// And the inbox the starting server writes keeps them as well.
		SpaceStore.storeInbox(_root, "Inbox");
		written = new String(Files.readAllBytes(SpaceStore.file(_root)), StandardCharsets.UTF_8);
		assertTrue(written, written.contains("\"content\":\"on\""));
	}

	// --- Who may. ---

	public void testAMemberIsRefused() throws Exception {
		byte[] before = Files.readAllBytes(SpaceStore.file(_root));
		for (String token : new String[] { SharingFixture.CAROL, SharingFixture.DAVE }) {
			FakeResponse read = get("space-settings", token);
			assertEquals(403, read.status());
			assertEquals(SpaceConfiguration.ADMIN_ONLY, error(read));
			FakeResponse write = save(token, SpaceSettings.create().setName("Mine"));
			assertEquals(403, write.status());
			assertEquals(SpaceConfiguration.ADMIN_ONLY, error(write));
		}
		assertTrue(Arrays.equals(before, Files.readAllBytes(SpaceStore.file(_root))));
	}

	public void testALinkAndAVisitorAreRefused() throws Exception {
		String link = new ShareStore(_root)
			.create("alice", SharingFixture.ZOO, "Grandma", "", Privacy.PUBLIC, 0, Arrays.asList(Rights.VIEW))
			.getToken();
		FakeResponse linked = get("space-settings", link);
		assertEquals(403, linked.status());
		assertEquals(SpaceConfiguration.SHARE_LINK_REFUSED, error(linked));
		FakeResponse linkedWrite = save(link, SpaceSettings.create().setName("Grandma's"));
		assertEquals(403, linkedWrite.status());
		assertEquals(SpaceConfiguration.SHARE_LINK_REFUSED, error(linkedWrite));

		FakeResponse anonymous = get("space-settings", null);
		assertEquals("A visitor is asked to sign in.", 401, anonymous.status());
		assertFalse(error(anonymous).isEmpty());
		FakeResponse anonymousWrite = save(null, SpaceSettings.create().setName("Anybody's"));
		assertEquals(401, anonymousWrite.status());
		assertFalse(error(anonymousWrite).isEmpty());
		assertEquals("The Family", SpaceStore.load(_root, SPACE).getName());
	}

	// --- What is refused. ---

	public void testEveryWrongValueIsRefusedWithItsReason() throws Exception {
		Files.createDirectories(_root.resolve("Taken"));
		byte[] before = Files.readAllBytes(SpaceStore.file(_root));
		SpaceSettings valid = read(SharingFixture.ALICE);

		assertRefused(copy(valid).setName("  "), SpaceConfiguration.NAME_EMPTY, before);
		assertRefused(copy(valid).setMapUrl("ftp://maps.example.org/{lat}/{lon}"), SpaceConfiguration.MAP_NOT_WEB,
			before);
		assertRefused(copy(valid).setMapUrl("javascript:alert({lat},{lon})"), SpaceConfiguration.MAP_NOT_WEB, before);
		assertRefused(copy(valid).setMapUrl("https://maps.example.org/?q={lat}"),
			SpaceConfiguration.MAP_WITHOUT_POSITION, before);
		assertRefused(copy(valid).setTimeZone("Mars/Olympus"), SpaceStore.unknownTimeZone("Mars/Olympus"), before);
		assertRefused(copy(valid).setInbox("../Outside"), SpaceStore.checkInbox("../Outside"), before);
		assertRefused(copy(valid).setInbox(".valbum"), SpaceStore.checkInbox(".valbum"), before);
		assertRefused(copy(valid).setInbox("Taken"), "There is a folder 'Taken' in the space already", before);
		assertRefused(copy(valid).setInbox(SharingFixture.PUBLIC), "There is a folder 'Public'", before);

		// Two wrong values: both are named.
		FakeResponse both = save(SharingFixture.ALICE, copy(valid).setName("").setTimeZone("Nowhere"));
		assertEquals(400, both.status());
		assertTrue(error(both), error(both).contains(SpaceConfiguration.NAME_EMPTY));
		assertTrue(error(both), error(both).contains(SpaceStore.unknownTimeZone("Nowhere")));

		FakeResponse garbage = post(SharingFixture.ALICE, "not json at all");
		assertEquals(400, garbage.status());
		assertEquals(SpaceConfiguration.UNREADABLE, error(garbage));
		assertTrue(Arrays.equals(before, Files.readAllBytes(SpaceStore.file(_root))));
	}

	public void testAnInboxThatHoldsPhotosStaysWhereItIs() throws Exception {
		SharingFixture.album(_root, SpaceStore.DEFAULT_INBOX, "Inbox",
			"[\"ImagePart\",{\"name\":\"new.jpg\",\"width\":4,\"height\":3}]", "new.jpg");
		SpaceSettings settings = read(SharingFixture.ALICE);
		assertEquals(SpaceConfiguration.INBOX_HOLDS_PHOTOS, settings.getInboxFixed());
		byte[] before = Files.readAllBytes(SpaceStore.file(_root));
		assertRefused(copy(settings).setInbox("Elsewhere"), SpaceConfiguration.INBOX_HOLDS_PHOTOS, before);

		FakeResponse unchanged = save(SharingFixture.ALICE, copy(settings).setName("Still the Family"));
		assertEquals("Everything else can still be saved.", 200, unchanged.status());
		assertEquals(SpaceStore.DEFAULT_INBOX, settings(unchanged).getInbox());
	}

	// --- In effect without a restart. ---

	public void testAnonymousAccessDecidesTheNextRequest() throws Exception {
		assertEquals("Closed: a visitor is asked to sign in.", 401, album(null, SharingFixture.PUBLIC).status());

		FakeResponse opened =
			save(SharingFixture.ALICE, read(SharingFixture.ALICE).setAnonymous(AnonymousAccess.PUBLIC));
		assertEquals(opened.body(), 200, opened.status());
		assertEquals(AnonymousAccess.PUBLIC, settings(opened).getAnonymous());
		assertEquals("Open: the next visitor sees the public album.", 200, album(null, SharingFixture.PUBLIC).status());
		assertEquals(AuthMode.WRITES, _spaces.bySegment(SPACE).getAuth().getMode());
		assertTrue(_spaces.bySegment(SPACE).getConfig().isAnonymousAllowed());
		assertEquals(SpaceStore.ANONYMOUS_PUBLIC, SpaceStore.load(_root, SPACE).getAnonymous());

		save(SharingFixture.ALICE, read(SharingFixture.ALICE).setAnonymous(AnonymousAccess.NONE));
		assertEquals("Closed again, at once.", 401, album(null, SharingFixture.PUBLIC).status());
	}

	public void testFacesSwitchWithoutARestartAndKeepWhatWasFound() throws Exception {
		Path stored = _root.resolve(SharingFixture.ZOO).resolve(".vacache").resolve("faces.json");
		Files.createDirectories(stored.getParent());
		Files.write(stored, "{\"what\":\"was found\"}".getBytes(StandardCharsets.UTF_8));
		ImageServlet data = servlet().dataOf(SPACE);
		assertFalse(data.faces().isRequested());

		FakeResponse on = save(SharingFixture.ALICE, read(SharingFixture.ALICE).setFaces(true));
		assertEquals(on.body(), 200, on.status());
		assertTrue(settings(on).isFaces());
		assertTrue("The index of the space follows the switch.", data.faces().isRequested());
		assertEquals("The app is told what the machine can do.",
			de.haumacher.imageServer.faces.FaceDetection.unavailability() == null, auth(SharingFixture.ALICE).isFaces());
		assertEquals(SpaceStore.FACES_ON, SpaceStore.load(_root, SPACE).getFaces());

		FakeResponse off = save(SharingFixture.ALICE, read(SharingFixture.ALICE).setFaces(false));
		assertEquals(off.body(), 200, off.status());
		assertFalse(data.faces().isRequested());
		assertFalse("Faces are not offered any more.", auth(SharingFixture.ALICE).isFaces());
		assertEquals("What was found is never deleted by the switch.", "{\"what\":\"was found\"}",
			new String(Files.readAllBytes(stored), StandardCharsets.UTF_8));
	}

	public void testTheContentSearchIsSwitchedAndAnAppThatDoesNotKnowItKeepsIt() throws Exception {
		ImageServlet data = servlet().dataOf(SPACE);
		SpaceSettings settings = read(SharingFixture.ALICE);
		assertEquals("Absent is off.", ContentSwitch.OFF, settings.getContent());
		assertEquals("A server without an embedder says so.", de.haumacher.imageServer.content.ContentIndex.NO_EMBEDDER,
			settings.getContentUnavailable());
		assertFalse(data.content().isEnabled());

		FakeResponse on = save(SharingFixture.ALICE, copy(settings).setContent(ContentSwitch.ON));
		assertEquals(ContentSwitch.ON, settings(on).getContent());
		assertTrue("The space follows the switch at once.", data.content().isEnabled());
		String written = new String(Files.readAllBytes(SpaceStore.file(_root)), StandardCharsets.UTF_8);
		assertTrue(written, written.contains("\"content\":\"on\""));
		assertTrue(SpaceStore.load(_root, SPACE).isContentEnabled());
		assertEquals("Read, written, read: the same.", write(settings(on)), write(read(SharingFixture.ALICE)));

		// An app of an earlier release sends no switch at all: the space keeps it.
		String older = write(read(SharingFixture.ALICE).setName("Renamed")).replace("\"content\":\"ON\",", "")
			.replace(",\"content\":\"ON\"", "");
		assertFalse(older, older.contains("\"content\""));
		FakeResponse kept = post(SharingFixture.ALICE, older);
		assertEquals(kept.body(), 200, kept.status());
		assertEquals(ContentSwitch.ON, settings(kept).getContent());
		assertEquals("Renamed", settings(kept).getName());
		assertTrue(data.content().isEnabled());

		FakeResponse off = save(SharingFixture.ALICE, read(SharingFixture.ALICE).setContent(ContentSwitch.OFF));
		assertEquals(ContentSwitch.OFF, settings(off).getContent());
		assertFalse(data.content().isEnabled());
		written = new String(Files.readAllBytes(SpaceStore.file(_root)), StandardCharsets.UTF_8);
		assertTrue(written, written.contains("\"content\":\"off\""));
	}

	public void testAMemberCannotSwitchTheContentSearch() throws Exception {
		byte[] before = Files.readAllBytes(SpaceStore.file(_root));
		for (String token : new String[] { SharingFixture.CAROL, SharingFixture.DAVE }) {
			FakeResponse write = save(token, SpaceSettings.create().setName("The Family").setContent(ContentSwitch.ON));
			assertEquals(write.body(), 403, write.status());
			assertEquals(SpaceConfiguration.ADMIN_ONLY, error(write));
		}
		assertTrue(Arrays.equals(before, Files.readAllBytes(SpaceStore.file(_root))));
		assertFalse(servlet().dataOf(SPACE).content().isEnabled());
	}

	// --- Where the server decides. ---

	public void testOneLibraryDecidesItsVisitorsAtTheStart() throws Exception {
		_single = new ImageServlet(_root.toFile(), new AuthService(AuthMode.WRITES, _root, InviteMode.MEMBERS), "",
			SpaceStore.load(_root, ""));
		_single.init(TestSpaces.config());
		SpaceSettings settings = settings(single("GET", null));
		assertEquals(AuthService.ANONYMOUS_FIXED_SINGLE, settings.getAnonymousFixed());
		assertEquals("The space.json name is shown.", "The Family", settings.getName());

		byte[] before = Files.readAllBytes(SpaceStore.file(_root));
		FakeResponse opened = single("POST", copy(settings).setAnonymous(AnonymousAccess.PUBLIC));
		assertEquals(400, opened.status());
		assertEquals(AuthService.ANONYMOUS_FIXED_SINGLE, error(opened));
		assertTrue(Arrays.equals(before, Files.readAllBytes(SpaceStore.file(_root))));

		FakeResponse renamed = single("POST", copy(settings).setName("Our Library"));
		assertEquals(renamed.body(), 200, renamed.status());
		assertEquals("Our Library", settings(renamed).getName());
	}

	public void testAServerStartedClosedStaysClosed() throws Exception {
		_spaces = Spaces.detect(_base, null, AuthMode.ALL, InviteMode.MEMBERS);
		ResourceServlet app = Main.application(_webRoot);
		app.setBaseSegments(_spaces.segments());
		_servlet = new SpaceServlet(_spaces, app);
		_servlet.init(TestSpaces.config());
		SpaceSettings settings = read(SharingFixture.ALICE);
		assertEquals(AuthService.ANONYMOUS_FIXED_ALL, settings.getAnonymousFixed());
		FakeResponse opened = save(SharingFixture.ALICE, copy(settings).setAnonymous(AnonymousAccess.PUBLIC));
		assertEquals(400, opened.status());
		assertEquals(AuthService.ANONYMOUS_FIXED_ALL, error(opened));
		assertEquals(AuthMode.ALL, _spaces.bySegment(SPACE).getAuth().getMode());
	}

	// --- Helpers. ---

	private void assertRefused(SpaceSettings request, String reason, byte[] before) throws Exception {
		FakeResponse response = save(SharingFixture.ALICE, request);
		assertEquals(response.body(), 400, response.status());
		assertTrue("Expected '" + reason + "' in: " + error(response), error(response).contains(reason));
		assertTrue("Nothing is written: " + reason, Arrays.equals(before, Files.readAllBytes(SpaceStore.file(_root))));
	}

	private void spaceJson(String json) throws Exception {
		Files.createDirectories(SpaceStore.file(_root).getParent());
		Files.write(SpaceStore.file(_root), json.getBytes(StandardCharsets.UTF_8));
	}

	private SpaceServlet servlet() throws Exception {
		if (_servlet == null) {
			_spaces = Spaces.detect(_base, null, AuthMode.WRITES, InviteMode.MEMBERS);
			ResourceServlet app = Main.application(_webRoot);
			app.setBaseSegments(_spaces.segments());
			_servlet = new SpaceServlet(_spaces, app);
			_servlet.init(TestSpaces.config());
		}
		return _servlet;
	}

	private SpaceSettings read(String token) throws Exception {
		FakeResponse response = get("space-settings", token);
		assertEquals(response.body(), 200, response.status());
		return settings(response);
	}

	private AuthInfo auth(String token) throws Exception {
		FakeResponse response = get("auth", token);
		assertEquals(response.body(), 200, response.status());
		return AuthInfo.readAuthInfo(reader(response.body()));
	}

	private FakeResponse get(String type, String token) throws Exception {
		return request("GET", DATA, type == null ? null : Map.of("type", type), token, new byte[0]);
	}

	/** A visitor's or member's look at the album at the given path. */
	private FakeResponse album(String token, String album) throws Exception {
		return request("GET", DATA + album + "/", Map.of("type", "json"), token, new byte[0]);
	}

	private FakeResponse save(String token, SpaceSettings settings) throws Exception {
		return post(token, write(settings));
	}

	private FakeResponse post(String token, String body) throws Exception {
		return request("POST", DATA, Map.of("action", "space-settings"), token,
			body.getBytes(StandardCharsets.UTF_8));
	}

	private FakeResponse request(String method, String pathInfo, Map<String, String> parameters, String token,
			byte[] body) throws Exception {
		Map<String, String> headers = new HashMap<>();
		if (token != null) {
			headers.put("Authorization", "Bearer " + token);
		}
		FakeResponse response = new FakeResponse();
		servlet().service(TestSpaces.request(method, pathInfo, parameters == null ? Map.of() : parameters, headers,
			body, "application/json"), response.response());
		return response;
	}

	/** Asks the servlet of a server of one library, as alice. */
	private FakeResponse single(String method, SpaceSettings settings) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put(settings == null ? "type" : "action", "space-settings");
		Map<String, String> headers = new HashMap<>();
		headers.put("Authorization", "Bearer " + SharingFixture.ALICE);
		byte[] body = settings == null ? new byte[0] : write(settings).getBytes(StandardCharsets.UTF_8);
		FakeResponse response = new FakeResponse();
		if ("GET".equals(method)) {
			_single.doGet(TestSpaces.request(method, "/", parameters, headers, body, "application/json"),
				response.response());
		} else {
			_single.doPost(TestSpaces.request(method, "/", parameters, headers, body, "application/json"),
				response.response());
		}
		return response;
	}

	private static SpaceSettings copy(SpaceSettings settings) throws Exception {
		return SpaceSettings.readSpaceSettings(reader(write(settings)));
	}

	private static SpaceSettings settings(FakeResponse response) throws Exception {
		assertEquals(response.body(), 200, response.status());
		return SpaceSettings.readSpaceSettings(reader(response.body()));
	}

	private static String error(FakeResponse response) throws Exception {
		Resource resource = Resource.readResource(reader(response.body()));
		assertTrue("Expected an error, got: " + response.body(), resource instanceof ErrorInfo);
		return ((ErrorInfo) resource).getMessage();
	}

	private static String write(SpaceSettings settings) throws Exception {
		StringWriter buffer = new StringWriter();
		try (JsonWriter json = new JsonWriter(new WriterAdapter(buffer))) {
			settings.writeTo(json);
		}
		return buffer.toString();
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
}
