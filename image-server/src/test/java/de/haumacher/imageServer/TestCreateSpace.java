/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.InviteMode;
import de.haumacher.imageServer.auth.LibraryMigration;
import de.haumacher.imageServer.auth.SpaceMode;
import de.haumacher.imageServer.auth.SpaceStore;
import de.haumacher.imageServer.auth.Spaces;
import de.haumacher.imageServer.auth.SpacesMigration;
import de.haumacher.imageServer.shared.model.AuthInfo;
import de.haumacher.imageServer.shared.model.PairResponse;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
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
 * Test case for <code>--create-space</code>, see issue #175.
 */
@SuppressWarnings("javadoc")
public class TestCreateSpace extends TestCase {

	private Path _base;

	private Server _server;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-create-space");
	}

	@Override
	protected void tearDown() throws Exception {
		if (_server != null) {
			_server.stop();
			_server = null;
		}
		if (_base != null) {
			try (Stream<Path> files = Files.walk(_base)) {
				files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
		super.tearDown();
	}

	// --- Creating. ---

	public void testAFirstSpaceOnAnEmptyBaseFolder() throws Exception {
		SpaceCreation.Report report = create("family", "Family Müller", SpaceStore.ANONYMOUS_PUBLIC,
			SpaceStore.FACES_ON, null);

		Spaces spaces = Spaces.detect(_base, null, AuthMode.WRITES, InviteMode.MEMBERS);
		assertEquals("The first space switches 'auto' to multi-space mode.", SpaceMode.MULTI, spaces.getMode());
		assertEquals(List.of("family"), spaces.segments());

		SpaceStore.Config config = spaces.bySegment("family").getConfig();
		assertEquals("Family Müller", config.getName());
		assertEquals(SpaceStore.ANONYMOUS_PUBLIC, config.getAnonymous());
		assertTrue(config.isFacesEnabled());
		assertEquals(SpaceStore.DEFAULT_MAP_URL, config.getMapUrl());

		String file = Files.readString(SpaceStore.file(_base.resolve("family")), StandardCharsets.UTF_8);
		assertFalse("No map template is written: " + file, file.contains("mapUrl"));

		assertTrue(report.toString(), report.toString().contains("Created the folder 'family'"));
		assertTrue(report.toString(), report.toString().contains("restart the server"));
	}

	public void testTheDefaultsAreClosedAndWithoutFaces() throws Exception {
		create("family", null, SpaceStore.ANONYMOUS_NONE, SpaceStore.FACES_OFF, null);

		SpaceStore.Config config = SpaceStore.load(_base.resolve("family"), "family");
		assertEquals("Without a name the folder names the space.", "family", config.getName());
		assertEquals(SpaceStore.ANONYMOUS_NONE, config.getAnonymous());
		assertFalse(config.isFacesEnabled());
		String file = Files.readString(SpaceStore.file(_base.resolve("family")), StandardCharsets.UTF_8);
		assertFalse("No name is frozen into the file: " + file, file.contains("\"name\""));
	}

	public void testASecondSpaceBesideAFirst() throws Exception {
		create("family", "", SpaceStore.ANONYMOUS_NONE, SpaceStore.FACES_OFF, null);
		SpaceCreation.Report report = create("friends", "", SpaceStore.ANONYMOUS_NONE, SpaceStore.FACES_OFF, null);

		assertTrue(report.toString(), report.toString().contains("family"));
		assertEquals(List.of("family", "friends"),
			Spaces.detect(_base, null, AuthMode.WRITES, InviteMode.MEMBERS).segments());
	}

	public void testAnExistingEmptyFolderBecomesTheSpace() throws Exception {
		Files.createDirectory(_base.resolve("family"));

		SpaceCreation.Report report = create("family", "", SpaceStore.ANONYMOUS_NONE, SpaceStore.FACES_OFF, null);

		assertTrue(report.toString(), report.toString().contains("exists and is empty"));
		assertTrue(SpaceStore.isSpace(_base.resolve("family")));
	}

	public void testAnExistingFolderWithAlbumsBecomesTheSpaceUnchanged() throws Exception {
		album("family/2020-05-01 Trip");
		album("family/Garden");
		Files.writeString(_base.resolve("family/Garden/index.json"),
			"[\"AlbumInfo\",{\"title\":\"Garden\"}]", StandardCharsets.UTF_8);
		String before = fingerprint(_base.resolve("family"));

		SpaceCreation.Report report = create("family", "", SpaceStore.ANONYMOUS_NONE, SpaceStore.FACES_OFF, null);

		assertTrue(report.toString(), report.toString().contains("holds 2 folder(s)"));
		assertEquals("Not one byte of an album changed.", before, fingerprint(_base.resolve("family")));
		Spaces spaces = Spaces.detect(_base, null, AuthMode.WRITES, InviteMode.MEMBERS);
		assertEquals(List.of("family"), spaces.segments());
		assertEquals(_base.resolve("family"), spaces.bySegment("family").getRoot());
	}

	public void testAForcedMultiSpaceServerMayHaveContentOutsideItsSpaces() throws Exception {
		album("Old");

		SpaceCreation.Report report = create("family", "", SpaceStore.ANONYMOUS_NONE, SpaceStore.FACES_OFF,
			SpaceMode.MULTI);

		assertTrue(report.toString(), report.toString().contains("Old"));
		assertTrue(SpaceStore.isSpace(_base.resolve("family")));
	}

	// --- Refusals: each writes nothing. ---

	public void testAFolderThatIsASpaceAlreadyIsRefused() throws Exception {
		create("family", "First", SpaceStore.ANONYMOUS_NONE, SpaceStore.FACES_OFF, null);
		refused("family", SpaceCreation.alreadyASpace("family"), null);
		assertEquals("First", SpaceStore.load(_base.resolve("family"), "family").getName());
	}

	public void testIllegalNamesAreRefused() throws Exception {
		for (String name : new String[] { ".hidden", ".valbum", "..", ".", "@eaDir", "#recycle", "Thumbs.db",
			"a/b", "a\\b", " family", "" }) {
			refused(name, SpaceCreation.illegalName(name), null);
		}
	}

	public void testReservedNamesAreRefused() throws Exception {
		for (String name : new String[] { "data", "s", "i", "assets" }) {
			refused(name, SpaceCreation.reservedName(name), null);
		}
	}

	public void testAFileOfThatNameIsRefused() throws Exception {
		Files.writeString(_base.resolve("family"), "a file", StandardCharsets.UTF_8);
		refused("family", SpaceCreation.notAFolder("family"), null);
	}

	public void testForcedSingleSpaceModeIsRefused() throws Exception {
		refused("family", SpaceCreation.FORCED_SINGLE, SpaceMode.SINGLE);
	}

	public void testASingleSpaceLibraryWithAlbumsAtItsRootIsRefused() throws Exception {
		album("2020-05-01 Trip");
		Files.createDirectory(_base.resolve("family"));

		String message = refused("family", null, null);

		assertTrue(message, message.contains("--migrate-to-spaces"));
		assertTrue(message, message.contains("--migrate-to-user"));
		assertTrue(message, message.contains("2020-05-01 Trip"));
	}

	public void testASingleSpaceLibraryWithAPhotoAtItsRootIsRefused() throws Exception {
		image(_base.resolve("loose.jpg"));
		String message = refused("family", null, null);
		assertTrue(message, message.contains("loose.jpg"));
	}

	public void testASingleSpaceLibraryWithUsersIsRefused() throws Exception {
		// An empty library somebody signed into: the seat of issue #89 lives at the base folder.
		new de.haumacher.imageServer.auth.AuthService(AuthMode.WRITES, _base);
		assertTrue(Files.exists(_base.resolve(".valbum/users.json")));

		String message = refused("family", null, null);

		assertTrue(message, message.contains("user store"));
		assertTrue(message, message.contains("--migrate-to-spaces"));
	}

	public void testWhatNoSystemCountsDoesNotMakeALibrary() throws Exception {
		Files.createDirectories(_base.resolve("@eaDir"));
		Files.createDirectories(_base.resolve(".upload"));
		Files.writeString(_base.resolve("README.txt"), "notes", StandardCharsets.UTF_8);

		create("family", "", SpaceStore.ANONYMOUS_NONE, SpaceStore.FACES_OFF, null);

		assertTrue(SpaceStore.isSpace(_base.resolve("family")));
	}

	/** The way the refusal names works: the library becomes a space, and a second one fits beside it. */
	public void testTheAdviceOfTheRefusalLeadsToSpaces() throws Exception {
		album("2020-05-01 Trip");
		// The administrator of issue #89 signed in and named herself.
		de.haumacher.imageServer.auth.AuthService auth =
			new de.haumacher.imageServer.auth.AuthService(AuthMode.WRITES, _base);
		auth.getUsers().getOwner().setName("alice");
		auth.getUsers().store();
		refused("family", null, null);

		LibraryMigration.migrate(_base, "alice");
		SpacesMigration.migrate(_base);
		create("family", "", SpaceStore.ANONYMOUS_NONE, SpaceStore.FACES_OFF, null);

		assertEquals(List.of("alice", "family"),
			Spaces.detect(_base, null, AuthMode.WRITES, InviteMode.MEMBERS).segments());
		assertTrue(Files.isDirectory(_base.resolve("alice/2020-05-01 Trip")));
		assertEquals("The administrator is the admin of the space her albums are in.", "alice",
			Spaces.detect(_base, null, AuthMode.WRITES, InviteMode.MEMBERS).bySegment("alice").getAuth().getUsers()
				.getOwner().getName());
	}

	// --- The command line. ---

	public void testTheCommandAnswersItsExitCode() throws Exception {
		assertEquals(0, Main.createSpace(_base, "family", "", SpaceStore.ANONYMOUS_NONE, SpaceStore.FACES_OFF, null));

		PrintStream err = System.err;
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
		int code;
		try {
			code = Main.createSpace(_base, "family", "", SpaceStore.ANONYMOUS_NONE, SpaceStore.FACES_OFF, null);
		} finally {
			System.setErr(err);
		}
		assertEquals(1, code);
		String printed = captured.toString(StandardCharsets.UTF_8);
		assertEquals("One line: " + printed, 1, printed.strip().lines().count());
		assertTrue(printed, printed.contains(SpaceCreation.alreadyASpace("family")));
	}

	// --- The server afterwards. ---

	public void testTheNextStartSignsTheNewSpaceIn() throws Exception {
		album("family/Garden");
		create("family", "Family", SpaceStore.ANONYMOUS_NONE, SpaceStore.FACES_OFF, null);

		Spaces spaces = Spaces.detect(_base, null, AuthMode.WRITES, InviteMode.MEMBERS);
		List<String> lines = Main.reportSpace(spaces, spaces.bySegment("family"), null);
		String seatLine = lines.stream().filter(line -> line.contains("sign the administrator in")).findFirst()
			.orElseThrow(() -> new AssertionError("No seat code in " + lines));
		assertTrue(seatLine, seatLine.startsWith("Space 'family'"));
		Matcher matcher = Pattern.compile("code ([A-Z0-9-]+) ").matcher(seatLine);
		assertTrue(seatLine, matcher.find());
		String code = matcher.group(1);

		_server = Main.createServer(0, "/valbum", _base.toFile(), null, spaces);
		_server.start();
		int port = ((ServerConnector) _server.getConnectors()[0]).getLocalPort();
		HttpClient client = HttpClient.newHttpClient();
		String data = "http://localhost:" + port + "/valbum/family/data/";

		HttpResponse<String> anonymous = client.send(HttpRequest.newBuilder(URI.create(data + "?type=json")).build(),
			HttpResponse.BodyHandlers.ofString());
		assertEquals("A closed space refuses an anonymous caller: " + anonymous.body(), 401, anonymous.statusCode());

		HttpResponse<String> paired = client.send(HttpRequest.newBuilder(URI.create(data + "?action=pair"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(Codes.pairRequest(code, "Laptop", "Anna"))).build(),
			HttpResponse.BodyHandlers.ofString());
		assertEquals(paired.body(), 200, paired.statusCode());
		String token = PairResponse.readPairResponse(reader(paired.body())).getToken();

		HttpResponse<String> auth = client.send(HttpRequest.newBuilder(URI.create(data + "?type=auth"))
			.header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(auth.body(), 200, auth.statusCode());
		AuthInfo info = AuthInfo.readAuthInfo(reader(auth.body()));
		assertEquals("Anna", info.getUserName());
		assertEquals("admin", info.getRole());

		HttpResponse<String> listing = client.send(HttpRequest.newBuilder(URI.create(data + "?type=json"))
			.header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(listing.body(), 200, listing.statusCode());
		assertTrue("The album of the folder is the space's: " + listing.body(), listing.body().contains("Garden"));
	}

	// --- Helpers. ---

	private SpaceCreation.Report create(String folder, String name, String anonymous, String faces, SpaceMode mode)
			throws Exception {
		return SpaceCreation.create(_base, folder, name, anonymous, faces, mode);
	}

	/**
	 * Asserts that creating the given space is refused and that nothing below the base folder
	 * changed, and answers the refusal.
	 *
	 * @param expected
	 *        The message expected, <code>null</code> for any.
	 */
	private String refused(String folder, String expected, SpaceMode mode) throws Exception {
		List<String> before = snapshot();
		try {
			create(folder, "Name", SpaceStore.ANONYMOUS_PUBLIC, SpaceStore.FACES_ON, mode);
			fail("Creating '" + folder + "' was not refused.");
			return null;
		} catch (SpaceCreation.Refused ex) {
			if (expected != null) {
				assertEquals(expected, ex.getMessage());
			}
			assertEquals("A refusal writes nothing ('" + folder + "').", before, snapshot());
			return ex.getMessage();
		}
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

	private static String fingerprint(Path root) throws Exception {
		StringBuilder result = new StringBuilder();
		try (Stream<Path> walk = Files.walk(root)) {
			for (Path path : (Iterable<Path>) walk.sorted()::iterator) {
				String relative = root.relativize(path).toString();
				if (relative.startsWith(".valbum")) {
					continue;
				}
				result.append(relative).append(Files.isRegularFile(path) ? " " + digest(path) : "/").append('\n');
			}
		}
		return result.toString();
	}

	private static String digest(Path file) throws Exception {
		StringBuilder hex = new StringBuilder();
		for (byte b : MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))) {
			hex.append(String.format("%02x", Byte.valueOf(b)));
		}
		return hex.toString();
	}

	private void album(String path) throws Exception {
		Path folder = _base.resolve(path);
		Files.createDirectories(folder);
		image(folder.resolve("image.jpg"));
	}

	private static void image(Path file) throws Exception {
		ImageIO.write(new BufferedImage(4, 3, BufferedImage.TYPE_3BYTE_BGR), "jpg", file.toFile());
	}

	private static JsonReader reader(String contents) {
		return new JsonReader(new ReaderAdapter(new StringReader(contents)));
	}
}
