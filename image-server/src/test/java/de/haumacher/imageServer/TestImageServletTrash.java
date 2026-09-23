/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.Clearances;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.auth.UserStore.Device;
import de.haumacher.imageServer.auth.UserStore.User;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.MoveOutcome;
import de.haumacher.imageServer.shared.model.MoveResult;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Test case for the trash of an album, see issue #152.
 *
 * <p>
 * Two things: a photograph rated &minus;2 is answered to editors alone, and
 * <code>?action=purge</code>, an administrator's act, deletes every such photograph — group members
 * one by one — from disk, with its sidecar part, its hash entry and its generated cache files. The servlet is driven headlessly with the fakes
 * of {@link TestImageServletPut}, on real (tiny) JPEGs, like {@link TestImageServletDelete}.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestImageServletTrash extends TestCase {

	private static final String ALICE_TOKEN = "alice-token";

	private static final String EVE_TOKEN = "eve-token";

	private static final String BOB_TOKEN = "bob-token";

	private static final String DAVE_TOKEN = "dave-token";

	private Path _base;

	private final List<ImageServlet> _servlets = new ArrayList<>();

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-trash-test");
	}

	@Override
	protected void tearDown() throws Exception {
		for (ImageServlet servlet : _servlets) {
			servlet.destroy();
		}
		_servlets.clear();
		if (_base != null) {
			delete(_base);
		}
		super.tearDown();
	}

	// --- A: the trash is the editor's. ---

	public void testAnEditorIsAnsweredTheTrash() throws Exception {
		trip();
		ImageServlet servlet = servlet(AuthMode.WRITES);

		assertEquals(Arrays.asList("a.jpg", "b.jpg"), names(album(servlet, "/Trip/", ALICE_TOKEN, null)));
		assertEquals("An editor who is no administrator sees it too: it is the edit right that counts.",
			Arrays.asList("a.jpg", "b.jpg"), names(album(servlet, "/Trip/", EVE_TOKEN, null)));
		assertEquals(HttpServletResponse.SC_OK, get(servlet, "/Trip/a.jpg", ALICE_TOKEN, "json", null).status());
		assertEquals(HttpServletResponse.SC_OK, get(servlet, "/Trip/a.jpg", ALICE_TOKEN, "tn", null).status());
	}

	public void testNobodyWithoutTheEditRightIsAnsweredTheTrash() throws Exception {
		trip();
		ImageServlet servlet = servlet(AuthMode.WRITES);
		String share = shareToken(servlet, "/Trip/", -2);

		Map<String, String> callers = new java.util.LinkedHashMap<>();
		callers.put("a viewer", DAVE_TOKEN);
		callers.put("a contributor", BOB_TOKEN);
		callers.put("a share link for every photo", share);
		callers.put("an anonymous visitor of an open space", null);
		for (Map.Entry<String, String> caller : callers.entrySet()) {
			String path = caller.getValue() == share ? "/" : "/Trip/";
			String image = caller.getValue() == share ? "/a.jpg" : "/Trip/a.jpg";
			assertEquals(caller.getKey() + " must not see the trash.", Arrays.asList("b.jpg"),
				names(album(servlet, path, caller.getValue(), null)));
			for (String type : Arrays.asList("json", "tn", "")) {
				FakeResponse response = get(servlet, image, caller.getValue(), type, null);
				assertTrue(caller.getKey() + " must not get the trashed photograph (type '" + type + "'): "
					+ response.status(), response.status() >= 400);
			}
		}
	}

	public void testTheAuthorsOwnPreviewShowsNoTrash() throws Exception {
		trip();
		ImageServlet servlet = servlet(AuthMode.WRITES);

		for (String viewAs : Arrays.asList("members", "public")) {
			assertEquals("'View as " + viewAs + "' shows what the others see.", Arrays.asList("b.jpg"),
				names(album(servlet, "/Trip/", ALICE_TOKEN, viewAs)));
			FakeResponse response = get(servlet, "/Trip/a.jpg", ALICE_TOKEN, "tn", viewAs);
			assertTrue(response.status() >= 400);
		}
	}

	public void testATrashedGroupMemberIsHiddenAndTheGroupStays() throws Exception {
		users();
		image("Trip/a.jpg", 8, 6, Color.RED);
		image("Trip/b.jpg", 8, 7, Color.GREEN);
		sidecar("Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":["
			+ group(part("a.jpg", "\"rating\":1"), part("b.jpg", "\"rating\":-2")) + "]}]");
		ImageServlet servlet = servlet(AuthMode.WRITES);

		assertEquals(Arrays.asList("a.jpg"), names(album(servlet, "/Trip/", DAVE_TOKEN, null)));
		assertEquals(Arrays.asList("a.jpg", "b.jpg"), names(album(servlet, "/Trip/", ALICE_TOKEN, null)));
	}

	public void testTheSidecarIsUntouchedByTheFilter() throws Exception {
		trip();
		String before = read(_base.resolve("Trip/index.json"));
		ImageServlet servlet = servlet(AuthMode.WRITES);

		album(servlet, "/Trip/", DAVE_TOKEN, null);

		assertEquals(before, read(_base.resolve("Trip/index.json")));
		assertEquals("The cache still holds the trashed part for the next editor.", Arrays.asList("a.jpg", "b.jpg"),
			names(album(servlet, "/Trip/", ALICE_TOKEN, null)));
	}

	// --- B: the purge deletes from disk. ---

	public void testAPurgeDeletesATrashedPhotographFromDisk() throws Exception {
		trip();
		ImageServlet servlet = servlet(AuthMode.OFF);
		// The hashes are filled, as an upload would, so that they can be seen to go.
		album(servlet, "/Trip/", null, null);
		de.haumacher.imageServer.upload.HashCache hashes =
			new de.haumacher.imageServer.upload.HashCache(_base.resolve("Trip").toFile());
		hashes.refresh();
		hashes.flush();
		// The previews of both, made the ordinary way.
		assertEquals(HttpServletResponse.SC_OK, get(servlet, "/Trip/a.jpg", null, "tn", null).status());
		assertEquals(HttpServletResponse.SC_OK, get(servlet, "/Trip/b.jpg", null, "tn", null).status());
		// Crops and renditions of a.jpg in either naming, and a crop of a photograph whose name
		// merely begins with a.jpg's.
		for (String name : Arrays.asList("face-a.jpg-f0123456789ab.jpg", "face-a.jpg-1.jpg",
			"face-a.jpg-2.jpg.tmp", "video-a.jpg.mp4", "teaser-a.jpg.mp4", "preview-a.jpg.tmp")) {
			write("Trip/.vacache/" + name, new byte[] { 1 });
		}
		write("Trip/.vacache/face-a.jpg-1.jpg-f0123456789ab.jpg", new byte[] { 2 });
		write("Trip/.vacache/face-b.jpg-f0123456789ab.jpg", new byte[] { 3 });
		write("Trip/.vacache/faces.json", "{}".getBytes(StandardCharsets.UTF_8));
		assertTrue(_base.resolve("Trip/.vacache/preview-a.jpg").toFile().isFile());

		MoveResult result = purge(servlet, "/Trip/", null);

		assertEquals(1, result.getOutcomes().size());
		MoveOutcome outcome = result.getOutcomes().get(0);
		assertEquals("a.jpg", outcome.getName());
		assertEquals("", outcome.getNewName());
		assertEquals(DeleteService.PURGED, outcome.getMessage());

		assertFalse("The original is gone from disk.", _base.resolve("Trip/a.jpg").toFile().exists());
		assertFalse("Nothing went to the trash folder.", trash().exists());
		assertEquals(Arrays.asList(".vacache/face-a.jpg-1.jpg-f0123456789ab.jpg",
			".vacache/face-b.jpg-f0123456789ab.jpg", ".vacache/faces.json", ".vacache/preview-b.jpg"),
			cacheFiles("Trip"));
		assertTrue(_base.resolve("Trip/b.jpg").toFile().isFile());
		assertFalse("The hash entry is gone.", read(_base.resolve("Trip/.hashes.json")).contains("\"a.jpg\""));
		assertTrue(read(_base.resolve("Trip/.hashes.json")).contains("\"b.jpg\""));
		assertEquals("The part left the album's sidecar.", Arrays.asList("b.jpg"), names(stored("Trip")));
		assertEquals(Arrays.asList("b.jpg"), names(album(servlet, "/Trip/", null, null)));
	}

	public void testNothingRatedHigherIsPurged() throws Exception {
		image("Trip/a.jpg", 8, 6, Color.RED);
		image("Trip/b.jpg", 8, 7, Color.GREEN);
		sidecar("Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[" + part("a.jpg", "\"rating\":-1") + ","
			+ part("b.jpg", "") + "]}]");
		String before = read(_base.resolve("Trip/index.json"));
		ImageServlet servlet = servlet(AuthMode.OFF);

		MoveResult result = purge(servlet, "/Trip/", null);

		assertEquals("Nothing to purge is an empty answer, not a refusal.", 0, result.getOutcomes().size());
		assertEquals("Nothing was written.", before, read(_base.resolve("Trip/index.json")));
		assertTrue(_base.resolve("Trip/a.jpg").toFile().isFile());
		assertTrue(_base.resolve("Trip/b.jpg").toFile().isFile());
	}

	public void testATrashedMemberLeavesItsGroupAlone() throws Exception {
		image("Trip/a.jpg", 8, 6, Color.RED);
		image("Trip/b.jpg", 8, 7, Color.GREEN);
		image("Trip/c.jpg", 8, 5, Color.BLUE);
		sidecar("Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":["
			+ group(part("a.jpg", ""), part("b.jpg", "\"rating\":-2"), part("c.jpg", "")) + "]}]");
		ImageServlet servlet = servlet(AuthMode.OFF);

		MoveResult result = purge(servlet, "/Trip/", null);

		assertEquals(Arrays.asList("b.jpg"), outcomeNames(result));
		AlbumInfo source = stored("Trip");
		assertEquals(1, source.getParts().size());
		ImageGroup group = (ImageGroup) source.getParts().get(0);
		assertEquals("The representative stays, with the member nobody trashed.", Arrays.asList("a.jpg", "c.jpg"),
			group.getImages().stream().map(ImagePart::getName).collect(Collectors.toList()));
		assertEquals("a.jpg", group.getImages().get(group.getRepresentative()).getName());
		assertFalse(_base.resolve("Trip/b.jpg").toFile().exists());
		assertTrue(_base.resolve("Trip/a.jpg").toFile().isFile());
		assertTrue(_base.resolve("Trip/c.jpg").toFile().isFile());
	}

	public void testATrashedRepresentativeGoesWithoutItsGroup() throws Exception {
		image("Trip/a.jpg", 8, 6, Color.RED);
		image("Trip/b.jpg", 8, 7, Color.GREEN);
		image("Trip/c.jpg", 8, 5, Color.BLUE);
		image("Trip/d.jpg", 8, 4, Color.YELLOW);
		sidecar("Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":["
			+ group(part("a.jpg", "\"rating\":-2"), part("b.jpg", "\"rating\":1"), part("c.jpg", "")) + ","
			+ group(part("d.jpg", "\"rating\":-2"), part("e.jpg", "")) + "]}]");
		image("Trip/e.jpg", 9, 4, Color.PINK);
		ImageServlet servlet = servlet(AuthMode.OFF);

		MoveResult result = purge(servlet, "/Trip/", null);

		assertEquals("Only the representatives go; they never take their groups along.",
			Arrays.asList("a.jpg", "d.jpg"), outcomeNames(result));
		AlbumInfo source = stored("Trip");
		assertEquals(2, source.getParts().size());
		ImageGroup kept = (ImageGroup) source.getParts().get(0);
		assertEquals("The members stay a group.", Arrays.asList("b.jpg", "c.jpg"),
			kept.getImages().stream().map(ImagePart::getName).collect(Collectors.toList()));
		assertTrue("A group left with one member is a plain part.", source.getParts().get(1) instanceof ImagePart);
		assertEquals("e.jpg", ((ImagePart) source.getParts().get(1)).getName());
		for (String name : Arrays.asList("b.jpg", "c.jpg", "e.jpg")) {
			assertTrue(name, _base.resolve("Trip").resolve(name).toFile().isFile());
		}
		assertFalse(_base.resolve("Trip/a.jpg").toFile().exists());
		assertFalse(_base.resolve("Trip/d.jpg").toFile().exists());
	}

	public void testAPurgedSidecarReadsBackAndWritesBackUnchanged() throws Exception {
		image("Trip/a.jpg", 8, 6, Color.RED);
		image("Trip/b.jpg", 8, 7, Color.GREEN);
		image("Trip/c.jpg", 8, 5, Color.BLUE);
		sidecar("Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"indexPicture\":{\"image\":\"a.jpg\"},\"parts\":["
			+ part("a.jpg", "\"rating\":-2") + "," + group(part("b.jpg", ""), part("c.jpg", "\"rating\":-2"))
			+ "]}]");
		ImageServlet servlet = servlet(AuthMode.OFF);
		purge(servlet, "/Trip/", null);

		String first = read(_base.resolve("Trip/index.json"));
		AlbumInfo read = (AlbumInfo) Resource.readResource(reader(first));
		assertEquals(Arrays.asList("b.jpg"), names(read));
		assertEquals("A purged index picture is repaired, never left dangling.", "b.jpg",
			read.getIndexPicture().getImage());
		java.io.StringWriter buffer = new java.io.StringWriter();
		try (de.haumacher.msgbuf.json.JsonWriter json = new de.haumacher.msgbuf.json.JsonWriter(
			new de.haumacher.msgbuf.server.io.WriterAdapter(buffer))) {
			read.writeTo(json);
		}
		assertEquals("read -> write -> read is stable.", first, buffer.toString());
	}

	public void testAnInboxIsPurgedToo() throws Exception {
		image("Inbox/a.jpg", 8, 6, Color.RED);
		image("Inbox/b.jpg", 8, 7, Color.GREEN);
		sidecar("Inbox", "[\"AlbumInfo\",{\"kind\":\"INBOX\",\"title\":\"Inbox\",\"parts\":["
			+ part("a.jpg", "") + "," + part("b.jpg", "\"rating\":-2") + "]}]");
		ImageServlet servlet = servlet(AuthMode.OFF);

		assertEquals(Arrays.asList("b.jpg"), outcomeNames(purge(servlet, "/Inbox/", null)));
		assertTrue(_base.resolve("Inbox/a.jpg").toFile().isFile());
		assertFalse(_base.resolve("Inbox/b.jpg").toFile().exists());
	}

	public void testAMissingFileRefusesTheWholePurge() throws Exception {
		image("Trip/a.jpg", 8, 6, Color.RED);
		image("Trip/b.jpg", 8, 7, Color.GREEN);
		sidecar("Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[" + part("a.jpg", "\"rating\":-2") + ","
			+ part("b.jpg", "\"rating\":-2") + "]}]");
		ImageServlet servlet = servlet(AuthMode.OFF);
		album(servlet, "/Trip/", null, null);
		// Gone behind the server's back, after the album was read: the sidecar still names it.
		Files.delete(_base.resolve("Trip/b.jpg"));
		String before = read(_base.resolve("Trip/index.json"));

		FakeResponse response = purgeResponse(servlet, "/Trip/", null);

		assertEquals(response.body(), HttpServletResponse.SC_CONFLICT, response.status());
		assertEquals(DeleteService.purgeUnresolved("b.jpg"), errorMessage(response));
		assertTrue("Nothing was deleted.", _base.resolve("Trip/a.jpg").toFile().isFile());
		assertEquals(before, read(_base.resolve("Trip/index.json")));
	}

	public void testWhoMayPurge() throws Exception {
		trip();
		ImageServlet servlet = servlet(AuthMode.WRITES);
		String share = shareToken(servlet, "/Trip/", -2);

		FakeResponse anonymous = purgeResponse(servlet, "/Trip/", null);
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, anonymous.status());
		assertEquals(AuthService.WRITE_REFUSED, errorMessage(anonymous));

		for (String token : Arrays.asList(EVE_TOKEN, BOB_TOKEN, DAVE_TOKEN, share)) {
			FakeResponse response = purgeResponse(servlet, token == share ? "/" : "/Trip/", token);
			assertEquals("Purging deletes from disk: an administrator's act alone.",
				HttpServletResponse.SC_FORBIDDEN, response.status());
			assertEquals(DeleteService.PURGE_REFUSED, errorMessage(response));
		}
		assertTrue("Nothing was deleted yet.", _base.resolve("Trip/a.jpg").toFile().isFile());

		FakeResponse admin = purgeResponse(servlet, "/Trip/", ALICE_TOKEN);
		assertEquals(admin.body(), HttpServletResponse.SC_OK, admin.status());
		assertFalse(_base.resolve("Trip/a.jpg").toFile().exists());
		assertTrue(_base.resolve("Trip/b.jpg").toFile().isFile());
	}

	public void testAPurgeOfAMissingFolderIsRefused() throws Exception {
		ImageServlet servlet = servlet(AuthMode.OFF);
		FakeResponse response = purgeResponse(servlet, "/nowhere/", null);
		assertEquals(response.body(), HttpServletResponse.SC_NOT_FOUND, response.status());
	}

	// --- Helpers. ---

	/** An album with a trashed <code>a.jpg</code> and a plain <code>b.jpg</code>, and the users. */
	private void trip() throws IOException {
		users();
		image("Trip/a.jpg", 8, 6, Color.RED);
		image("Trip/b.jpg", 8, 7, Color.GREEN);
		sidecar("Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[" + part("a.jpg", "\"rating\":-2") + ","
			+ part("b.jpg", "") + "]}]");
	}

	/** The files below the cache directory of the given folder, relative to it and sorted. */
	private List<String> cacheFiles(String folder) throws IOException {
		Path root = _base.resolve(folder);
		try (Stream<Path> walk = Files.walk(root.resolve(".vacache"))) {
			return walk.filter(Files::isRegularFile).map(p -> root.relativize(p).toString()).sorted()
				.collect(Collectors.toList());
		}
	}

	private File trash() {
		return _base.resolve(UserStore.DIRECTORY_NAME).resolve(DeleteService.TRASH_FOLDER).toFile();
	}

	private ImageServlet servlet(AuthMode mode) throws Exception {
		ImageServlet servlet = new ImageServlet(_base.toFile(), new AuthService(mode, _base));
		servlet.init();
		_servlets.add(servlet);
		return servlet;
	}

	/** An administrator, an editor, a contributor and a viewer of the one space. */
	private void users() throws IOException {
		UserStore store = new UserStore(_base);
		User alice = store.nameOwner("alice");
		alice.addDevice(new Device("Alice's phone", UserStore.hash(ALICE_TOKEN), Instant.now().toString()));
		store.addUser(user("eve", EVE_TOKEN, Roles.EDIT));
		store.addUser(user("bob", BOB_TOKEN, Roles.CONTRIBUTE));
		store.addUser(user("dave", DAVE_TOKEN, Roles.VIEW));
		store.store();
	}

	private static User user(String name, String token, String role) {
		User result = new User(name, role, "", Instant.now().toString(), Clearances.ALL, true);
		result.addDevice(new Device(name + "'s device", UserStore.hash(token), Instant.now().toString()));
		return result;
	}

	private static String shareToken(ImageServlet servlet, String pathInfo, int minRating) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "share");
		String body = "{\"label\":\"Grandma\",\"expires\":\"\",\"maxPrivacy\":1,\"minRating\":" + minRating + ","
			+ "\"rights\":[{\"name\":\"view\"},{\"name\":\"download\"}]}";
		FakeResponse response = new FakeResponse();
		servlet.doPost(request(pathInfo, "application/json", body, ALICE_TOKEN, parameters), response.response());
		assertEquals("Cannot create a share link: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return ShareLinkCreated.readShareLinkCreated(reader(response.body())).getToken();
	}

	private static AlbumInfo album(ImageServlet servlet, String pathInfo, String token, String viewAs)
			throws Exception {
		FakeResponse response = get(servlet, pathInfo, token, "json", viewAs);
		assertEquals("Reading failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return (AlbumInfo) Resource.readResource(reader(response.body()));
	}

	private static FakeResponse get(ImageServlet servlet, String pathInfo, String token, String type, String viewAs)
			throws Exception {
		Map<String, String> parameters = new HashMap<>();
		if (!type.isEmpty()) {
			parameters.put("type", type);
		}
		if (viewAs != null) {
			parameters.put("viewAs", viewAs);
		}
		FakeResponse response = new FakeResponse();
		servlet.doGet(request(pathInfo, null, "", token, parameters), response.response());
		return response;
	}

	private static MoveResult purge(ImageServlet servlet, String pathInfo, String token) throws Exception {
		FakeResponse response = purgeResponse(servlet, pathInfo, token);
		assertEquals("The purge failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return MoveResult.readMoveResult(reader(response.body()));
	}

	private static FakeResponse purgeResponse(ImageServlet servlet, String pathInfo, String token) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "purge");
		FakeResponse response = new FakeResponse();
		servlet.doPost(request(pathInfo, null, "", token, parameters), response.response());
		return response;
	}

	private AlbumInfo stored(String folder) throws IOException {
		return (AlbumInfo) Resource.readResource(reader(read(_base.resolve(folder).resolve("index.json"))));
	}

	private static HttpServletRequest request(String pathInfo, String contentType, String body, String token,
			Map<String, String> parameters) {
		Map<String, String> headers = new HashMap<>();
		if (contentType != null) {
			headers.put("Content-Type", contentType);
		}
		if (token != null) {
			headers.put("Authorization", "Bearer " + token);
		}
		return TestImageServletPut.request(pathInfo, contentType, body.getBytes(StandardCharsets.UTF_8), headers,
			parameters);
	}

	/** The names of every photograph of the album, group members one by one. */
	private static List<String> names(AlbumInfo album) {
		List<String> result = new ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				result.add(((ImagePart) part).getName());
			} else if (part instanceof ImageGroup) {
				for (ImagePart member : ((ImageGroup) part).getImages()) {
					result.add(member.getName());
				}
			}
		}
		return result;
	}

	private static List<String> outcomeNames(MoveResult result) {
		return result.getOutcomes().stream().map(MoveOutcome::getName).collect(Collectors.toList());
	}

	private static String errorMessage(FakeResponse response) throws IOException {
		Resource resource = Resource.readResource(reader(response.body()));
		assertTrue("Expected an ErrorInfo body, got: " + response.body(), resource instanceof ErrorInfo);
		return ((ErrorInfo) resource).getMessage();
	}

	private static JsonReader reader(String contents) {
		return new JsonReader(new ReaderAdapter(new StringReader(contents)));
	}

	private static String read(Path file) throws IOException {
		return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
	}

	private static String part(String name, String extra) {
		return "[\"ImagePart\",{\"name\":\"" + name + "\",\"kind\":\"IMAGE\",\"width\":8,\"height\":6"
			+ (extra.isEmpty() ? "" : "," + extra) + "}]";
	}

	/** A group of the given parts, each written as the plain member object a group holds. */
	private static String group(String... images) {
		List<String> members = new ArrayList<>();
		for (String image : images) {
			members.add(image.substring("[\"ImagePart\",".length(), image.length() - 1));
		}
		return "[\"ImageGroup\",{\"representative\":0,\"images\":[" + String.join(",", members) + "]}]";
	}

	private void sidecar(String folder, String contents) throws IOException {
		Path path = _base.resolve(folder);
		Files.createDirectories(path);
		Files.write(path.resolve("index.json"), contents.getBytes(StandardCharsets.UTF_8));
	}

	private void image(String relativePath, int width, int height, Color color) throws IOException {
		write(relativePath, jpeg(width, height, color));
	}

	private void write(String relativePath, byte[] contents) throws IOException {
		Path path = _base.resolve(relativePath);
		Files.createDirectories(path.getParent());
		Files.write(path, contents);
	}

	private static byte[] jpeg(int width, int height, Color color) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		graphics.setColor(color);
		graphics.fillRect(0, 0, width, height);
		graphics.dispose();
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		ImageIO.write(image, "jpg", buffer);
		return buffer.toByteArray();
	}

	private static void delete(Path root) throws IOException {
		if (!Files.exists(root)) {
			return;
		}
		try (Stream<Path> files = Files.walk(root)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
	}
}
