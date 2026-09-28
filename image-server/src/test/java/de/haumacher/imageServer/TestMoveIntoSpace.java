/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.InviteMode;
import de.haumacher.imageServer.auth.SpaceAlias;
import de.haumacher.imageServer.auth.SpaceMode;
import de.haumacher.imageServer.auth.SpaceStore;
import de.haumacher.imageServer.auth.Spaces;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.shared.model.AuthInfo;
import de.haumacher.imageServer.shared.model.InvitationCreated;
import de.haumacher.imageServer.shared.model.PairResponse;
import de.haumacher.imageServer.shared.model.Person;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

/**
 * Test case for <code>--move-into-space</code> and the alias of the old addresses, see issue #177.
 *
 * <p>
 * The library is built by this build over HTTP - an administrator signed in with the seat code, a
 * member who joined through an invitation, two invitations still pending, a share link, a person
 * with a tag, faces on - then moved, and asked again at the old and at the new addresses.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestMoveIntoSpace extends TestCase {

	private static final String ALBUM = "2020-05-01 Trip";

	private static final String ALBUM_URL = "2020-05-01%20Trip";

	private static final String PHOTO = "photo.jpg";

	private Path _base;

	private Path _webRoot;

	private Server _server;

	private int _port;

	private final HttpClient _client = HttpClient.newHttpClient();

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-move-into-space");
		_webRoot = Files.createTempDirectory("valbum-move-into-space-web");
		Files.writeString(_webRoot.resolve("index.html"),
			"<html><head><base href=\"/\"></head><body>app</body></html>", StandardCharsets.UTF_8);
		Files.writeString(_webRoot.resolve("main.dart.js"), "// app", StandardCharsets.UTF_8);
	}

	@Override
	protected void tearDown() throws Exception {
		stop();
		delete(_base);
		delete(_webRoot);
		super.tearDown();
	}

	// --- The whole library, at both addresses. ---

	public void testTheWholeLibraryMovesAndAnswersAtBothAddresses() throws Exception {
		Files.createDirectories(_base.resolve(".valbum"));
		Files.writeString(_base.resolve(".valbum/space.json"), "{\"version\":1,\"faces\":\"on\"}",
			StandardCharsets.UTF_8);
		Files.createDirectories(_base.resolve(ALBUM));
		image(_base.resolve(ALBUM).resolve(PHOTO), 64, 48);
		image(_base.resolve("loose.jpg"), 8, 6);

		// The library, as the current build writes it.
		String seat = start();
		String admin = pair("/valbum/data/", seat, "Anna", "Laptop");
		assertTrue(get("/valbum/data/" + ALBUM_URL + "/?type=json", admin).body().contains(PHOTO));

		ShareLinkCreated link = share("/valbum/data/", admin);
		assertEquals("/valbum/s/" + link.getToken() + "/", link.getUrl());

		String joined = invite("/valbum/data/", admin).getToken();
		String bob = pair("/valbum/data/", joined, "Bob", "Phone");
		String pendingOld = invite("/valbum/data/", admin).getToken();
		String pendingNew = invite("/valbum/data/", admin).getToken();

		HttpResponse<String> created = post("/valbum/data/?action=create-person", admin, "{\"name\":\"Berta\"}");
		assertEquals(created.body(), 200, created.statusCode());
		String berta = Person.readPerson(reader(created.body())).getId();
		HttpResponse<String> tagged = post("/valbum/data/" + ALBUM_URL + "/?action=tag-faces", admin,
			"{\"faces\":[{\"image\":\"" + PHOTO + "\",\"x\":0.2,\"y\":0.2,\"w\":0.3,\"h\":0.4,\"person\":\"" + berta
				+ "\",\"state\":\"CONFIRMED\"}]}");
		assertEquals(tagged.body(), 200, tagged.statusCode());
		assertTrue(tagged.body(), tagged.body().contains(berta));
		stop();

		String originals = originals(_base);
		assertFalse(originals.isEmpty());

		// The move.
		assertEquals(0, Main.moveIntoSpace(_base, "family", "The Family", AuthMode.WRITES, null));

		Spaces spaces = Spaces.detect(_base, null, AuthMode.WRITES, InviteMode.MEMBERS);
		assertEquals(SpaceMode.MULTI, spaces.getMode());
		assertEquals(List.of("family"), spaces.segments());
		assertEquals("family", spaces.alias().getSegment());
		assertFalse("Nothing was retired.", Files.exists(_base.resolve(".valbum/retired")));
		assertFalse("Nothing was retired.", Files.exists(_base.resolve("family/.valbum/retired")));
		assertEquals("Not one byte of an original changed.", originals, originals(_base.resolve("family")));
		assertEquals("Only the marker and the upload staging area of the old address stay.", List.of(".valbum", "family"),
			names(_base).stream().filter(name -> !name.equals(".upload")).toList());
		assertEquals(List.of(SpaceAlias.FILE_NAME), names(_base.resolve(".valbum")));
		SpaceStore.Config config = SpaceStore.load(_base.resolve("family"), "family");
		assertEquals("The Family", config.getName());
		assertTrue("The faces stay on.", config.isFacesEnabled());
		assertTrue("--auth writes showed the public photos to everybody, and still does.",
			config.isAnonymousAllowed());

		// The server again.
		start(spaces);

		assertTrue("The context root keeps opening the library.",
			get("/valbum/", null).body().contains("<base href=\"/valbum/\">"));
		assertTrue("A deep link of the old address opens the application.",
			get("/valbum/" + ALBUM_URL + "/" + PHOTO, null).body().contains("<base href=\"/valbum/\">"));
		assertTrue(get("/valbum/family/", null).body().contains("<base href=\"/valbum/family/\">"));

		int user = 0;
		String[] pending = { pendingOld, pendingNew };
		for (String app : List.of("/valbum", "/valbum/family")) {
			String data = app + "/data/";

			AuthInfo anna = auth(data, admin);
			assertEquals(app, "Anna", anna.getUserName());
			assertEquals(app, "admin", anna.getRole());
			assertEquals(app, "family", anna.getSpace());
			AuthInfo bobInfo = auth(data, bob);
			assertEquals(app, "Bob", bobInfo.getUserName());
			assertEquals(app, "edit", bobInfo.getRole());

			String album = get(data + ALBUM_URL + "/?type=json", admin).body();
			assertTrue(app + ": " + album, album.contains(PHOTO));
			assertTrue(app + ": the tag resolves: " + album, album.contains("\"person\":\"" + berta + "\""));
			String people = get(data + "?type=people", admin).body();
			assertTrue(app + ": " + people, people.contains("Berta"));

			// The share link sent before the move.
			String session = app + "/s/" + link.getToken() + "/";
			HttpResponse<String> page = get(session, null);
			assertEquals(app, 200, page.statusCode());
			assertTrue(page.body(), page.body().contains("<base href=\"" + session + "\">"));
			assertTrue(page.body(), page.body().contains("og:image"));
			HttpResponse<String> cover = get(session + "cover.jpg", null);
			assertEquals(app + ": " + cover.body(), 200, cover.statusCode());
			String shared = get(data + "?type=json", link.getToken()).body();
			assertTrue(app + ": the link opens the album: " + shared, shared.contains(PHOTO));

			// An invitation sent before the move.
			String invitation = app + "/i/" + pending[user] + "/";
			assertEquals(app, 200, get(invitation, null).statusCode());
			String newcomer = pair(data, pending[user], "Carl" + user, "Tablet");
			assertEquals(app, "Carl" + user, auth(data, newcomer).getUserName());
			HttpResponse<String> used = get(invitation, null);
			assertEquals(app, 302, used.statusCode());
			assertTrue(used.headers().firstValue("Location").orElse(""),
				used.headers().firstValue("Location").orElse("").endsWith(app + "/?invitation=used"));
			user++;

			// What is made now is spelled with the new address.
			assertEquals(app, "/valbum/family/s/", prefix(share(data, admin).getUrl()));
			assertEquals(app, "/valbum/family/i/", prefix(invite(data, admin).getUrl()));
		}
	}

	// --- What moves and what stays. ---

	public void testTheServersOwnEntriesMoveAndForeignOnesStay() throws Exception {
		Files.createDirectories(_base.resolve(ALBUM));
		image(_base.resolve(ALBUM).resolve(PHOTO), 8, 6);
		for (String name : new String[] { ".vacache", ".upload", "@eaDir", "#recycle", ".stfolder" }) {
			Files.createDirectories(_base.resolve(name));
		}
		Files.writeString(_base.resolve("index.json"), "[\"ListingInfo\",{}]", StandardCharsets.UTF_8);
		Files.writeString(_base.resolve("index.json.20260101-000000"), "[\"ListingInfo\",{}]", StandardCharsets.UTF_8);
		Files.writeString(_base.resolve(".hashes.json"), "{}", StandardCharsets.UTF_8);
		Files.writeString(_base.resolve("Thumbs.db"), "x", StandardCharsets.UTF_8);

		SpaceCreation.Report report = SpaceMove.move(_base, "family", null, AuthMode.ALL, null);

		assertEquals(List.of(".hashes.json", ".vacache", ALBUM, "index.json", "index.json.20260101-000000"),
			names(_base.resolve("family")).stream().filter(name -> !name.equals(".valbum")).toList());
		assertEquals(List.of("#recycle", ".stfolder", ".upload", ".valbum", "@eaDir", "Thumbs.db", "family"),
			names(_base));
		assertTrue(report.toString(), report.toString().contains("as no part of the library: #recycle, .stfolder, @eaDir, Thumbs.db."));
		assertTrue(report.toString(), report.toString().contains("Left at the base folder: .upload, the upload staging"));
		SpaceStore.Config config = SpaceStore.load(_base.resolve("family"), "family");
		assertEquals("Without a name the folder names the space.", "family", config.getName());
		assertFalse("--auth all showed nothing to visitors, and still does.", config.isAnonymousAllowed());
		assertFalse(config.isFacesEnabled());
	}

	public void testWhatTheSpaceFileSaidIsKept() throws Exception {
		Files.createDirectories(_base.resolve(".valbum"));
		Files.writeString(_base.resolve(".valbum/space.json"), "{\"version\":1,\"name\":\"Müllers\",\"anonymous\":"
			+ "\"public\",\"mapUrl\":\"https://maps.example.org/?q={lat},{lon}\",\"faces\":\"on\"}",
			StandardCharsets.UTF_8);

		SpaceMove.move(_base, "family", "", AuthMode.ALL, null);

		SpaceStore.Config config = SpaceStore.load(_base.resolve("family"), "family");
		assertEquals("Müllers", config.getName());
		assertEquals("https://maps.example.org/?q={lat},{lon}", config.getMapUrl());
		assertTrue(config.isFacesEnabled());
		assertTrue(config.isAnonymousAllowed());
	}

	public void testAnExistingEmptyFolderIsTheTarget() throws Exception {
		Files.createDirectories(_base.resolve("family"));
		image(_base.resolve("loose.jpg"), 8, 6);

		SpaceMove.move(_base, "family", null, AuthMode.WRITES, null);

		assertTrue(Files.isRegularFile(_base.resolve("family/loose.jpg")));
	}

	public void testASecondSpaceFitsBesideTheMovedOne() throws Exception {
		Files.createDirectories(_base.resolve(ALBUM));
		image(_base.resolve(ALBUM).resolve(PHOTO), 8, 6);
		SpaceMove.move(_base, "family", null, AuthMode.WRITES, null);

		SpaceCreation.create(_base, "friends", "", SpaceStore.ANONYMOUS_NONE, SpaceStore.FACES_OFF, null);

		Spaces spaces = Spaces.detect(_base, null, AuthMode.WRITES, InviteMode.MEMBERS);
		assertEquals(List.of("family", "friends"), spaces.segments());
		assertEquals("family", spaces.alias().getSegment());
	}

	public void testWithoutTheMarkerTheOldAddressesEnd() throws Exception {
		Files.createDirectories(_base.resolve(ALBUM));
		image(_base.resolve(ALBUM).resolve(PHOTO), 8, 6);
		SpaceMove.move(_base, "family", null, AuthMode.WRITES, null);
		Files.delete(SpaceAlias.file(_base));

		start(Spaces.detect(_base, null, AuthMode.WRITES, InviteMode.MEMBERS));

		assertEquals(404, get("/valbum/data/?type=json", null).statusCode());
		assertEquals(404, get("/valbum/s/whatever/", null).statusCode());
		assertEquals(200, get("/valbum/family/data/" + ALBUM_URL + "/?type=json", null).statusCode());
	}

	public void testAMarkerNamingNoSpaceIsSaidAndIgnored() throws Exception {
		Files.createDirectories(_base.resolve("family/.valbum"));
		Files.writeString(_base.resolve("family/.valbum/space.json"), "{}", StandardCharsets.UTF_8);
		SpaceAlias.write(_base, "gone", java.time.Instant.now());

		Spaces spaces = Spaces.detect(_base, null, AuthMode.WRITES, InviteMode.MEMBERS);

		assertNull(spaces.alias());
		assertNull(spaces.bySegment(""));
		assertTrue(spaces.aliasProblem(), spaces.aliasProblem().contains("'gone'"));
	}

	// --- Refusals: each moves and writes nothing. ---

	public void testATargetThatIsNotEmptyIsRefused() throws Exception {
		library();
		Files.createDirectories(_base.resolve("family"));
		Files.writeString(_base.resolve("family/notes.txt"), "mine", StandardCharsets.UTF_8);
		refused("family", SpaceMove.targetNotEmpty("family"), null);

		refused(ALBUM, SpaceMove.targetNotEmpty(ALBUM), null);
	}

	public void testAFileOfThatNameIsRefused() throws Exception {
		library();
		Files.writeString(_base.resolve("family"), "a file", StandardCharsets.UTF_8);
		refused("family", SpaceCreation.notAFolder("family"), null);
	}

	public void testIllegalAndReservedNamesAreRefused() throws Exception {
		library();
		for (String name : new String[] { ".hidden", ".valbum", ".upload", "..", ".", "@eaDir", "#recycle",
			"Thumbs.db", "a/b", " family", "" }) {
			refused(name, SpaceCreation.illegalName(name), null);
		}
		for (String name : new String[] { "data", "s", "i", "assets", "canvaskit", "icons" }) {
			refused(name, SpaceCreation.reservedName(name), null);
		}
	}

	public void testAMultiSpaceServerIsRefused() throws Exception {
		library();
		Files.createDirectories(_base.resolve("friends/.valbum"));
		Files.writeString(_base.resolve("friends/.valbum/space.json"), "{}", StandardCharsets.UTF_8);
		refused("family", SpaceMove.alreadyMulti(List.of("friends")), null);
	}

	public void testAMovedLibraryIsRefused() throws Exception {
		library();
		SpaceMove.move(_base, "family", null, AuthMode.WRITES, null);
		refused("other", SpaceMove.alreadyMoved("family"), null);
	}

	public void testForcedSingleSpaceModeIsRefused() throws Exception {
		library();
		refused("family", SpaceMove.FORCED_SINGLE, SpaceMode.SINGLE);
	}

	public void testALibrarySplitPerUserIsRefused() throws Exception {
		library();
		de.haumacher.imageServer.auth.AuthService auth =
			new de.haumacher.imageServer.auth.AuthService(AuthMode.WRITES, _base);
		UserStore.User owner = auth.getUsers().getOwner();
		owner.setName("alice");
		owner.setSpace("alice");
		auth.getUsers().store();

		String message = refused("family", SpaceMove.perUser("alice", "alice"), null);
		assertTrue(message, message.contains("migrate-to-spaces"));
	}

	public void testTheCommandAnswersItsExitCode() throws Exception {
		library();
		assertEquals(1, Main.moveIntoSpace(_base, "data", null, AuthMode.WRITES, null));
		assertEquals(0, Main.moveIntoSpace(_base, "family", null, AuthMode.WRITES, null));
		assertEquals(1, Main.moveIntoSpace(_base, "family", null, AuthMode.WRITES, null));
	}

	// --- Helpers. ---

	/** A small single-space library with an album, a photo at the root and a signed-in seat. */
	private void library() throws Exception {
		Files.createDirectories(_base.resolve(ALBUM));
		image(_base.resolve(ALBUM).resolve(PHOTO), 8, 6);
		image(_base.resolve("loose.jpg"), 8, 6);
		de.haumacher.imageServer.auth.AuthService auth =
			new de.haumacher.imageServer.auth.AuthService(AuthMode.WRITES, _base);
		auth.getUsers().getOwner().setName("anna");
		auth.getUsers().store();
	}

	private String refused(String folder, String expected, SpaceMode mode) throws Exception {
		List<String> before = snapshot();
		try {
			SpaceMove.move(_base, folder, "Name", AuthMode.WRITES, mode);
			fail("Moving into '" + folder + "' was not refused.");
			return null;
		} catch (SpaceCreation.Refused ex) {
			assertEquals(expected, ex.getMessage());
			assertEquals("A refusal moves and writes nothing ('" + folder + "').", before, snapshot());
			return ex.getMessage();
		}
	}

	/** Starts the server on the base folder as it is, and answers the seat code it printed. */
	private String start() throws Exception {
		Spaces spaces = Spaces.detect(_base, null, AuthMode.WRITES, InviteMode.MEMBERS);
		assertEquals(SpaceMode.SINGLE, spaces.getMode());
		List<String> lines = Main.reportSpace(spaces, spaces.single(), null);
		Matcher matcher = Pattern.compile("sign the administrator in with the code ([A-Z0-9-]+) ")
			.matcher(String.join("\n", lines));
		assertTrue(lines.toString(), matcher.find());
		start(spaces);
		return matcher.group(1);
	}

	private void start(Spaces spaces) throws Exception {
		_server = Main.createServer(0, "/valbum", _base.toFile(), _webRoot.toFile(), spaces);
		_server.start();
		_port = ((ServerConnector) _server.getConnectors()[0]).getLocalPort();
	}

	private void stop() throws Exception {
		if (_server != null) {
			_server.stop();
			_server = null;
		}
	}

	private String pair(String data, String code, String userName, String deviceName) throws Exception {
		HttpResponse<String> paired = post(data + "?action=pair", null, Codes.pairRequest(code, deviceName, userName));
		assertEquals(paired.body(), 200, paired.statusCode());
		return PairResponse.readPairResponse(reader(paired.body())).getToken();
	}

	private ShareLinkCreated share(String data, String token) throws Exception {
		HttpResponse<String> created = post(data + ALBUM_URL + "/?action=share", token,
			"{\"label\":\"Grandma\",\"expires\":\"\",\"maxPrivacy\":0,\"minRating\":0,"
				+ "\"rights\":[{\"name\":\"view\"}]}");
		assertEquals(created.body(), 200, created.statusCode());
		return ShareLinkCreated.readShareLinkCreated(reader(created.body()));
	}

	private InvitationCreated invite(String data, String token) throws Exception {
		HttpResponse<String> invited = post(data + "?action=invite", token,
			"{\"role\":\"edit\",\"clearance\":\"all\",\"expires\":\"\",\"note\":\"\",\"recipient\":\"\"}");
		assertEquals(invited.body(), 200, invited.statusCode());
		return InvitationCreated.readInvitationCreated(reader(invited.body()));
	}

	private AuthInfo auth(String data, String token) throws Exception {
		HttpResponse<String> response = get(data + "?type=auth", token);
		assertEquals(response.body(), 200, response.statusCode());
		return AuthInfo.readAuthInfo(reader(response.body()));
	}

	private HttpResponse<String> get(String path, String token) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + _port + path));
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		return _client.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> post(String path, String token, String body) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + _port + path))
			.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		return _client.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	/** The path of a session URL up to its token. */
	private static String prefix(String url) {
		return url.substring(0, url.length() - 1).substring(0, url.substring(0, url.length() - 1).lastIndexOf('/') + 1);
	}

	/** Every photo below the given root with its digest, outside the server's own folders. */
	private static String originals(Path root) throws Exception {
		StringBuilder result = new StringBuilder();
		try (Stream<Path> walk = Files.walk(root)) {
			for (Path path : (Iterable<Path>) walk.sorted()::iterator) {
				String relative = root.relativize(path).toString();
				if (relative.startsWith(".valbum") || relative.contains(".vacache")
					|| !Files.isRegularFile(path) || !ResourceCache.isImage(path.toFile())) {
					continue;
				}
				result.append(relative).append(' ').append(digest(path)).append('\n');
			}
		}
		return result.toString();
	}

	/** Every path below the base folder, with the digest of every file. */
	private List<String> snapshot() throws Exception {
		List<String> result = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(_base)) {
			for (Path path : (Iterable<Path>) walk.sorted()::iterator) {
				result.add(_base.relativize(path) + (Files.isRegularFile(path) ? " " + digest(path) : "/"));
			}
		}
		return result;
	}

	private static List<String> names(Path folder) throws Exception {
		try (Stream<Path> list = Files.list(folder)) {
			return list.map(path -> path.getFileName().toString()).sorted().toList();
		}
	}

	private static String digest(Path file) throws Exception {
		StringBuilder hex = new StringBuilder();
		for (byte b : MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))) {
			hex.append(String.format("%02x", Byte.valueOf(b)));
		}
		return hex.toString();
	}

	private static void image(Path file, int width, int height) throws Exception {
		ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR), "jpg", file.toFile());
	}

	private static JsonReader reader(String contents) {
		return new JsonReader(new ReaderAdapter(new StringReader(contents)));
	}

	private static void delete(Path root) throws Exception {
		if (root == null || !Files.exists(root)) {
			return;
		}
		try (Stream<Path> files = Files.walk(root)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
	}
}
