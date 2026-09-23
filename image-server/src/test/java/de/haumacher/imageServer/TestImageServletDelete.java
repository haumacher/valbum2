/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.Clearances;
import de.haumacher.imageServer.auth.InviteMode;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.SpaceMode;
import de.haumacher.imageServer.auth.Spaces;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.auth.UserStore.Device;
import de.haumacher.imageServer.auth.UserStore.User;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.ListingInfo;
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
import java.security.MessageDigest;
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
 * Test case for deleting albums and folders, see issue #109.
 *
 * <p>
 * The servlet is driven headlessly on a temporary base folder with the request and response fakes
 * of {@link TestImageServletPut}, like the move tests of issue #47. The images are real (tiny)
 * JPEGs drawn here, so that what the server calls a picture really is one.
 * </p>
 *
 * <p>
 * The test that matters most is {@link #testNothingButWhatTheServerWroteIsEverDeleted()}: a
 * picture is planted at every depth of a tree and every byte of that tree is fingerprinted before
 * the delete and after it. The server never deletes an original, and a delete is where that
 * sentence is easiest to break.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestImageServletDelete extends TestCase {

	private static final String ALICE_TOKEN = "alice-token";

	private static final String BOB_TOKEN = "bob-token";

	private static final String DAVE_TOKEN = "dave-token";

	private Path _base;

	private ImageServlet _servlet;

	private final List<ImageServlet> _servlets = new ArrayList<>();

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-delete-test");
		_servlet = servlet(_base, AuthMode.OFF);
	}

	@Override
	protected void tearDown() throws Exception {
		for (ImageServlet servlet : _servlets) {
			servlet.destroy();
		}
		_servlets.clear();
		_servlet = null;
		if (_base != null) {
			delete(_base);
		}
		super.tearDown();
	}

	// --- The empty album: really gone. ---

	public void testAnEmptyAlbumIsRemoved() throws Exception {
		sidecar("A/Empty", "[\"AlbumInfo\",{\"title\":\"Empty\",\"parts\":[]}]");
		// Everything the server itself writes beside an album.
		write("A/Empty/index.json.1234567", "[\"AlbumInfo\",{\"title\":\"older\"}]".getBytes(StandardCharsets.UTF_8));
		write("A/Empty/.hashes.json", "{}".getBytes(StandardCharsets.UTF_8));
		write("A/Empty/.vacache/preview-a.jpg", jpeg(4, 3, Color.RED));
		write("A/Empty/.vacache/video-a.mp4", new byte[] { 1, 2, 3 });
		write("A/Empty/.vacache/preview-b.jpg.tmp", jpeg(4, 3, Color.BLUE));
		// A listing above it, so that the answer can be read back.
		sidecar("A", "[\"ListingInfo\",{\"title\":\"A\"}]");

		MoveResult result = delete("/A/", "Empty");

		MoveOutcome outcome = result.getOutcomes().get(0);
		assertEquals("An album that held no picture is gone, not set aside.", "", outcome.getNewName());
		assertEquals(DeleteService.REMOVED, outcome.getMessage());
		assertFalse("The folder must be gone.", _base.resolve("A/Empty").toFile().exists());
		assertFalse("Nothing may have gone to the trash.", trash().exists());

		ListingInfo listing = (ListingInfo) Resource.readResource(reader(getJson("/A/")));
		assertEquals("The listing no longer shows it.", java.util.Collections.emptyList(), listing.getFolders());
	}

	public void testAFolderOfEmptyFoldersAndSidecarsIsRemoved() throws Exception {
		sidecar("A/Tree", "[\"ListingInfo\",{\"title\":\"Tree\"}]");
		sidecar("A/Tree/2020", "[\"ListingInfo\",{\"title\":\"2020\"}]");
		sidecar("A/Tree/2020/Empty", "[\"AlbumInfo\",{\"title\":\"Empty\",\"parts\":[]}]");
		Files.createDirectories(_base.resolve("A/Tree/2021"));

		MoveResult result = delete("/A/", "Tree");

		assertEquals(DeleteService.REMOVED, result.getOutcomes().get(0).getMessage());
		assertFalse(_base.resolve("A/Tree").toFile().exists());
		assertTrue("The folder above it stays.", _base.resolve("A").toFile().isDirectory());
	}

	// --- The album that holds photographs: into the trash of its space. ---

	public void testAnAlbumWithAnImageGoesToTheTrash() throws Exception {
		image("A/Trip/a.jpg", 8, 6, Color.RED);
		sidecar("A/Trip", "[\"AlbumInfo\",{\"title\":\"The trip\",\"parts\":[" + part("a.jpg", "") + "]}]");
		write("A/Trip/.hashes.json", "{}".getBytes(StandardCharsets.UTF_8));
		sidecar("A", "[\"ListingInfo\",{\"title\":\"A\"}]");

		MoveResult result = delete("/A/", "Trip");

		MoveOutcome outcome = result.getOutcomes().get(0);
		assertEquals("Trip", outcome.getNewName());
		assertEquals(DeleteService.trashed("Trip"), outcome.getMessage());

		assertFalse("The album has left the tree.", _base.resolve("A/Trip").toFile().exists());
		File trashed = new File(trash(), "Trip");
		assertTrue("The album is in the trash of the space.", trashed.isDirectory());
		assertTrue("The photograph is untouched.", new File(trashed, "a.jpg").isFile());
		assertTrue("The sidecar rides along.", new File(trashed, "index.json").isFile());
		assertTrue("The hashes ride along.", new File(trashed, ".hashes.json").isFile());

		ListingInfo listing = (ListingInfo) Resource.readResource(reader(getJson("/A/")));
		assertEquals(java.util.Collections.emptyList(), listing.getFolders());
	}

	public void testTheTrashIsNeverServed() throws Exception {
		image("Trip/a.jpg", 8, 6, Color.RED);
		sidecar("Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[" + part("a.jpg", "") + "]}]");

		delete("/", "Trip");

		ListingInfo root = (ListingInfo) Resource.readResource(reader(getJson("/")));
		assertEquals("Nothing below '.valbum' is ever an entry of a listing.",
			java.util.Collections.emptyList(), names(root));

		FakeResponse response = new FakeResponse();
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "json");
		_servlet.doGet(request("/" + UserStore.DIRECTORY_NAME + "/" + DeleteService.TRASH_FOLDER + "/Trip/", null, "",
			null, parameters), response.response());
		assertTrue("The trash is not addressable: " + response.status(), response.status() >= 400);
	}

	public void testASecondDeleteOfTheSameNameIsStamped() throws Exception {
		image("Trip/a.jpg", 8, 6, Color.RED);
		sidecar("Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[" + part("a.jpg", "") + "]}]");
		assertEquals("Trip", delete("/", "Trip").getOutcomes().get(0).getNewName());

		image("Trip/b.jpg", 8, 6, Color.GREEN);
		sidecar("Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[" + part("b.jpg", "") + "]}]");
		String newName = delete("/", "Trip").getOutcomes().get(0).getNewName();

		assertTrue("Expected a timestamp prefix, got '" + newName + "'.",
			newName.matches("\\d{8}-\\d{6}-Trip"));
		assertTrue("The first one is still there, untouched.", new File(trash(), "Trip/a.jpg").isFile());
		assertTrue("The second one is beside it.", new File(trash(), newName + "/b.jpg").isFile());
	}

	public void testAPhotoInThePaperBasketIsStillAPhoto() throws Exception {
		image("A/Rejected/a.jpg", 8, 6, Color.RED);
		sidecar("A/Rejected",
			"[\"AlbumInfo\",{\"title\":\"Rejected\",\"parts\":[" + part("a.jpg", "\"rating\":-2") + "]}]");

		MoveResult result = delete("/A/", "Rejected");

		assertEquals("A rating says what one thinks of a photograph, not whether it exists.",
			DeleteService.trashed("Rejected"), result.getOutcomes().get(0).getMessage());
		assertTrue(new File(trash(), "Rejected/a.jpg").isFile());
	}

	public void testAnImageThreeLevelsDownIsFoundAndTheFolderMovesOnce() throws Exception {
		sidecar("A/Tree", "[\"ListingInfo\",{\"title\":\"Tree\"}]");
		sidecar("A/Tree/2020", "[\"ListingInfo\",{\"title\":\"2020\"}]");
		sidecar("A/Tree/2020/Empty", "[\"AlbumInfo\",{\"title\":\"Empty\",\"parts\":[]}]");
		image("A/Tree/2020/Deep/deep.jpg", 8, 6, Color.BLUE);

		MoveResult result = delete("/A/", "Tree");

		assertEquals(DeleteService.trashed("Tree"), result.getOutcomes().get(0).getMessage());
		assertTrue("The whole tree moved by one rename, structure and all.",
			new File(trash(), "Tree/2020/Deep/deep.jpg").isFile());
		assertTrue("The empty folders came along; nothing was deleted on the way.",
			new File(trash(), "Tree/2020/Empty/index.json").isFile());
	}

	public void testAStrayImageInTheCacheDirectorySavesTheFolder() throws Exception {
		sidecar("A/Empty", "[\"AlbumInfo\",{\"title\":\"Empty\",\"parts\":[]}]");
		write("A/Empty/.vacache/preview-gone.jpg", jpeg(4, 3, Color.RED));
		// A name the server never writes: it is somebody's picture, wherever it lies.
		write("A/Empty/.vacache/stray.jpg", jpeg(8, 6, Color.GREEN));

		MoveResult result = delete("/A/", "Empty");

		assertEquals("A picture the server did not generate stops the removal.",
			DeleteService.trashed("Empty"), result.getOutcomes().get(0).getMessage());
		assertTrue("The stray picture is still there, in the trash.",
			new File(trash(), "Empty/.vacache/stray.jpg").isFile());
		assertTrue("Even the generated preview came along, rather than being deleted.",
			new File(trash(), "Empty/.vacache/preview-gone.jpg").isFile());
	}

	public void testAForeignFileStopsTheRemoval() throws Exception {
		sidecar("A/Empty", "[\"AlbumInfo\",{\"title\":\"Empty\",\"parts\":[]}]");
		write("A/Empty/notes.txt", "my notes".getBytes(StandardCharsets.UTF_8));

		MoveResult result = delete("/A/", "Empty");

		assertEquals("The server deletes what it wrote and nothing else.",
			DeleteService.trashed("Empty"), result.getOutcomes().get(0).getMessage());
		assertEquals("my notes", read(new File(trash(), "Empty/notes.txt").toPath()));
	}

	// --- The p0: nothing but what the server wrote is ever deleted. ---

	public void testNothingButWhatTheServerWroteIsEverDeleted() throws Exception {
		// A picture at every depth, and a file of somebody else's beside each of them.
		image("A/Tree/top.jpg", 8, 6, Color.RED);
		image("A/Tree/2020/mid.jpg", 8, 6, Color.GREEN);
		image("A/Tree/2020/Trip/deep.jpg", 8, 6, Color.BLUE);
		image("A/Tree/2020/Trip/Deeper/deepest.jpg", 8, 6, Color.YELLOW);
		write("A/Tree/2020/Trip/Deeper/.vacache/stray.png", jpeg(4, 3, Color.PINK));
		write("A/Tree/notes.txt", "notes".getBytes(StandardCharsets.UTF_8));
		write("A/Tree/2020/Trip/notes.txt", "more notes".getBytes(StandardCharsets.UTF_8));
		sidecar("A/Tree", "[\"ListingInfo\",{\"title\":\"Tree\"}]");
		sidecar("A/Tree/2020/Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":["
			+ part("deep.jpg", "\"rating\":-2") + "]}]");

		String before = fingerprint(_base.resolve("A/Tree"));

		MoveResult result = delete("/A/", "Tree");

		assertEquals(DeleteService.trashed("Tree"), result.getOutcomes().get(0).getMessage());
		assertFalse(_base.resolve("A/Tree").toFile().exists());
		assertEquals("Every byte of the tree survived the delete, name for name.",
			before, fingerprint(new File(trash(), "Tree").toPath()));
	}

	/**
	 * The one amendment of the doctrine above, see issue #152: an administrator's purge deletes the
	 * originals rated &minus;2 and the cache files generated from them — and not one byte of
	 * anything else. The sidecar and the hash cache are rewritten (they lose the purged entries)
	 * and are therefore the only other files allowed to change.
	 */
	public void testAPurgeDeletesOnlyTheTrashedOriginalsAndTheirCacheFiles() throws Exception {
		image("A/Trip/gone.jpg", 8, 6, Color.RED);
		image("A/Trip/also-gone.jpg", 8, 7, Color.GREEN);
		image("A/Trip/kept.jpg", 8, 5, Color.BLUE);
		image("A/Trip/rejected.jpg", 8, 4, Color.YELLOW);
		image("A/Trip/unlisted.jpg", 9, 4, Color.PINK);
		write("A/Trip/notes.txt", "notes".getBytes(StandardCharsets.UTF_8));
		write("A/Trip/.vacache/stray.jpg", jpeg(4, 3, Color.CYAN));
		write("A/Trip/.vacache/preview-gone.jpg", jpeg(4, 3, Color.RED));
		write("A/Trip/.vacache/face-gone.jpg-f0123456789ab.jpg", jpeg(4, 3, Color.RED));
		write("A/Trip/.vacache/preview-kept.jpg", jpeg(4, 3, Color.BLUE));
		write("A/Trip/.vacache/face-kept.jpg-1.jpg", jpeg(4, 3, Color.BLUE));
		image("A/Other/other.jpg", 8, 6, Color.RED);
		sidecar("A/Other", "[\"AlbumInfo\",{\"title\":\"Other\",\"parts\":[" + part("other.jpg", "\"rating\":-2")
			+ "]}]");
		sidecar("A/Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[" + part("gone.jpg", "\"rating\":-2")
			+ ",[\"ImageGroup\",{\"representative\":0,\"images\":[{\"name\":\"kept.jpg\",\"kind\":\"IMAGE\","
			+ "\"width\":8,\"height\":6},{\"name\":\"also-gone.jpg\",\"kind\":\"IMAGE\",\"width\":8,"
			+ "\"height\":6,\"rating\":-2}]}]," + part("rejected.jpg", "\"rating\":-1") + "]}]");
		List<String> purged = Arrays.asList("Trip/gone.jpg", "Trip/also-gone.jpg", "Trip/.vacache/preview-gone.jpg",
			"Trip/.vacache/face-gone.jpg-f0123456789ab.jpg");
		String before = fingerprintExcept(_base.resolve("A"), purged);

		FakeResponse response = new FakeResponse();
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "purge");
		_servlet.doPost(request("/A/Trip/", null, "", null, parameters), response.response());
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());

		for (String path : purged) {
			assertFalse("'" + path + "' must be gone.", _base.resolve("A").resolve(path).toFile().exists());
		}
		assertEquals("Every other byte of the library survived the purge, name for name.", before,
			fingerprintExcept(_base.resolve("A"), purged));
		assertFalse("A purge sets nothing aside.", trash().exists());
	}

	public void testTheRemovalBranchIsRefusedAtEveryDepth() throws Exception {
		// The same tree, once per depth: one picture anywhere below is enough to save it all.
		for (String where : Arrays.asList("A/Deep/a.jpg", "A/Deep/1/a.jpg", "A/Deep/1/2/a.jpg",
			"A/Deep/1/2/3/a.jpg", "A/Deep/1/2/3/.vacache/a.jpg")) {
			delete(_base.resolve("A"));
			sidecar("A/Deep", "[\"AlbumInfo\",{\"title\":\"Deep\",\"parts\":[]}]");
			Files.createDirectories(_base.resolve("A/Deep/1/2/3"));
			image(where, 8, 6, Color.RED);
			String content = read(_base.resolve(where));

			MoveResult result = delete("/A/", "Deep");

			assertEquals("A picture at '" + where + "' must save the folder.",
				DeleteService.trashed("Deep"), result.getOutcomes().get(0).getMessage());
			String moved = where.replace("A/Deep", "Deep");
			assertEquals("The picture at '" + where + "' must be untouched.",
				content, read(new File(trash(), moved).toPath()));
			delete(trash().toPath());
		}
	}

	// --- Names that are not entries. ---

	public void testWhatCannotBeDeleted() throws Exception {
		image("A/a.jpg", 8, 6, Color.RED);
		sidecar("A", "[\"AlbumInfo\",{\"title\":\"A\",\"parts\":[" + part("a.jpg", "") + "]}]");
		Files.createDirectories(_base.resolve("A/Sub"));

		MoveResult result = delete("/A/", "a.jpg", "index.json", "../A", ".vacache", "nowhere", "Sub", "Sub");

		assertEquals("A photograph is thrown away into the album this one has in the trash.",
			DeleteService.trashed("a.jpg"), result.getOutcomes().get(0).getMessage());
		assertEquals(DeleteService.notAnEntry("index.json"), result.getOutcomes().get(1).getMessage());
		assertEquals("A name is a name, never a path.", DeleteService.notAnEntry("../A"),
			result.getOutcomes().get(2).getMessage());
		assertEquals("The server's own folders are no album entries.", DeleteService.notAnEntry(".vacache"),
			result.getOutcomes().get(3).getMessage());
		assertEquals(DeleteService.notFound("nowhere"), result.getOutcomes().get(4).getMessage());
		assertEquals(DeleteService.REMOVED, result.getOutcomes().get(5).getMessage());
		assertEquals(DeleteService.namedTwice("Sub"), result.getOutcomes().get(6).getMessage());

		assertTrue("The photograph is in the trash, not deleted.", new File(trash(), "A/a.jpg").isFile());
		assertTrue("The sidecar must still be there.", _base.resolve("A/index.json").toFile().exists());
	}

	public void testAMissingFolderRefusesTheWholeRequest() throws Exception {
		FakeResponse response = deleteResponse(_servlet, "/nowhere/", null, "A");

		assertEquals(HttpServletResponse.SC_NOT_FOUND, response.status());
		assertEquals(DeleteService.FOLDER_MISSING, errorMessage(response));
	}

	public void testAnUnreadableRequestIsRefused() throws Exception {
		FakeResponse response = new FakeResponse();
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "delete");
		_servlet.doPost(request("/", "application/json", "not JSON at all", null, parameters), response.response());

		assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status());
		assertEquals(DeleteService.DELETE_UNREADABLE, errorMessage(response));
	}

	// --- Who may delete. ---

	public void testAnAnonymousDeleteIsRefused() throws Exception {
		users();
		image("A/Trip/a.jpg", 8, 6, Color.RED);

		ImageServlet servlet = servlet(_base, AuthMode.WRITES);
		FakeResponse response = deleteResponse(servlet, "/A/", null, "Trip");

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status());
		assertEquals(AuthService.WRITE_REFUSED, errorMessage(response));
		assertTrue(_base.resolve("A/Trip/a.jpg").toFile().exists());
	}

	public void testViewAndContributeAreRefused() throws Exception {
		users();
		image("A/Trip/a.jpg", 8, 6, Color.RED);

		ImageServlet servlet = servlet(_base, AuthMode.WRITES);
		for (String token : Arrays.asList(DAVE_TOKEN, BOB_TOKEN)) {
			FakeResponse response = deleteResponse(servlet, "/A/", token, "Trip");

			assertEquals("Deleting an album is an edit of the listing it is an entry of.",
				HttpServletResponse.SC_FORBIDDEN, response.status());
			assertEquals(DeleteService.EDIT_REFUSED, errorMessage(response));
		}
		assertTrue("Nothing may have moved.", _base.resolve("A/Trip/a.jpg").toFile().exists());
		assertFalse(trash().exists());
	}

	public void testTheEditRoleMayDelete() throws Exception {
		users();
		image("A/Trip/a.jpg", 8, 6, Color.RED);

		ImageServlet servlet = servlet(_base, AuthMode.WRITES);
		FakeResponse response = deleteResponse(servlet, "/A/", ALICE_TOKEN, "Trip");

		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		assertTrue(new File(trash(), "Trip/a.jpg").isFile());
	}

	public void testAShareLinkIsRefused() throws Exception {
		users();
		image("A/Trip/a.jpg", 8, 6, Color.RED);
		sidecar("A", "[\"ListingInfo\",{\"title\":\"A\"}]");

		ImageServlet servlet = servlet(_base, AuthMode.WRITES);
		String token = shareToken(servlet, "/A/");

		FakeResponse response = deleteResponse(servlet, "/", token, "Trip");

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals("A link may well allow adding photos; deleting an album is still none of its "
			+ "business.", DeleteService.SHARE_DELETE_REFUSED, errorMessage(response));
		assertTrue(_base.resolve("A/Trip/a.jpg").toFile().exists());
	}

	// --- One server, several spaces. ---

	public void testTheTrashIsTheTrashOfTheCallersOwnSpace() throws Exception {
		for (String segment : Arrays.asList("one", "two")) {
			Files.createDirectories(_base.resolve(segment).resolve(UserStore.DIRECTORY_NAME));
			Files.write(_base.resolve(segment).resolve(UserStore.DIRECTORY_NAME).resolve("space.json"),
				("{\"name\":\"" + segment + "\"}").getBytes(StandardCharsets.UTF_8));
			image(segment + "/Trip/a.jpg", 8, 6, Color.RED);
			sidecar(segment + "/Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[" + part("a.jpg", "") + "]}]");
		}

		Spaces spaces = Spaces.detect(_base, SpaceMode.MULTI, AuthMode.OFF, InviteMode.MEMBERS);
		assertEquals(2, spaces.getSpaces().size());
		for (Spaces.Space space : spaces.getSpaces()) {
			ImageServlet servlet = new ImageServlet(space.getRoot().toFile(), space.getAuth(), space.getSegment());
			servlet.init();
			_servlets.add(servlet);

			FakeResponse response = deleteResponse(servlet, "/", null, "Trip");
			assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		}

		for (String segment : Arrays.asList("one", "two")) {
			assertTrue("Each space throws away into its own trash.",
				_base.resolve(segment).resolve(UserStore.DIRECTORY_NAME).resolve(DeleteService.TRASH_FOLDER)
					.resolve("Trip").resolve("a.jpg").toFile().isFile());
		}
		assertFalse("Nothing lands below the base folder of the server.",
			_base.resolve(UserStore.DIRECTORY_NAME).resolve(DeleteService.TRASH_FOLDER).toFile().exists());
	}

	// --- Helpers. ---

	/** The trash folder of the one space of the test library. */
	private File trash() {
		return _base.resolve(UserStore.DIRECTORY_NAME).resolve(DeleteService.TRASH_FOLDER).toFile();
	}

	private ImageServlet servlet(Path base, AuthMode mode) throws Exception {
		ImageServlet servlet = new ImageServlet(base.toFile(), new AuthService(mode, base));
		servlet.init();
		_servlets.add(servlet);
		return servlet;
	}

	/** An administrator, a contributor and a viewer of the one space. */
	private void users() throws IOException {
		UserStore store = new UserStore(_base);
		User alice = store.nameOwner("alice");
		alice.addDevice(new Device("Alice's phone", UserStore.hash(ALICE_TOKEN), Instant.now().toString()));
		store.addUser(user("bob", BOB_TOKEN, Roles.CONTRIBUTE));
		store.addUser(user("dave", DAVE_TOKEN, Roles.VIEW));
		store.store();
	}

	private static User user(String name, String token, String role) {
		User result = new User(name, role, "", Instant.now().toString(), Clearances.ALL, true);
		result.addDevice(new Device(name + "'s device", UserStore.hash(token), Instant.now().toString()));
		return result;
	}

	/** A share link on the given folder that even allows contributing. */
	private static String shareToken(ImageServlet servlet, String pathInfo) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "share");
		String body = "{\"label\":\"Grandma\",\"expires\":\"\",\"maxPrivacy\":0,\"minRating\":0,"
			+ "\"rights\":[{\"name\":\"view\"},{\"name\":\"download\"},{\"name\":\"contribute\"}]}";
		FakeResponse response = new FakeResponse();
		servlet.doPost(request(pathInfo, "application/json", body, ALICE_TOKEN, parameters), response.response());
		assertEquals("Cannot create a share link: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return ShareLinkCreated.readShareLinkCreated(reader(response.body())).getToken();
	}

	private MoveResult delete(String pathInfo, String... names) throws Exception {
		FakeResponse response = deleteResponse(_servlet, pathInfo, null, names);
		assertEquals("The delete failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return MoveResult.readMoveResult(reader(response.body()));
	}

	private static FakeResponse deleteResponse(ImageServlet servlet, String pathInfo, String token, String... names)
			throws Exception {
		String body = "{\"target\":\"\",\"names\":["
			+ Arrays.stream(names).map(n -> "{\"name\":\"" + n + "\"}").collect(Collectors.joining(",")) + "]}";
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "delete");
		FakeResponse response = new FakeResponse();
		servlet.doPost(request(pathInfo, "application/json", body, token, parameters), response.response());
		return response;
	}

	private String getJson(String pathInfo) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "json");
		FakeResponse response = new FakeResponse();
		_servlet.doGet(request(pathInfo, null, "", null, parameters), response.response());
		assertEquals("Reading failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return response.body();
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

	private static List<String> names(ListingInfo listing) {
		return listing.getFolders().stream().map(f -> f.getName()).collect(Collectors.toList());
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
		return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
	}

	/**
	 * A fingerprint of every file below the given path but the purged ones and the sidecars a purge
	 * rewrites (<code>index.json</code>, its backups, <code>.hashes.json</code>).
	 */
	private static String fingerprintExcept(Path root, List<String> purged) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		List<Path> files = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(root)) {
			walk.filter(Files::isRegularFile).forEach(files::add);
		}
		files.sort(Comparator.comparing(Path::toString));
		for (Path file : files) {
			String relative = root.relativize(file).toString();
			String name = file.getFileName().toString();
			if (purged.contains(relative) || name.startsWith("index.json") || name.equals(".hashes.json")) {
				continue;
			}
			digest.update(relative.getBytes(StandardCharsets.UTF_8));
			digest.update(Files.readAllBytes(file));
		}
		StringBuilder result = new StringBuilder();
		for (byte b : digest.digest()) {
			result.append(String.format("%02x", Byte.valueOf(b)));
		}
		return result.toString();
	}

	/** A fingerprint of every file below the given path: its name, and its bytes. */
	private static String fingerprint(Path root) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		List<Path> files = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(root)) {
			walk.filter(Files::isRegularFile).forEach(files::add);
		}
		files.sort(Comparator.comparing(Path::toString));
		for (Path file : files) {
			digest.update(root.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
			digest.update(Files.readAllBytes(file));
		}
		StringBuilder result = new StringBuilder();
		for (byte b : digest.digest()) {
			result.append(String.format("%02x", Byte.valueOf(b)));
		}
		return result.toString();
	}

	/** An <code>ImagePart</code> for a hand-written sidecar. */
	private static String part(String name, String extra) {
		return "[\"ImagePart\",{\"name\":\"" + name + "\",\"kind\":\"IMAGE\",\"width\":8,\"height\":6"
			+ (extra.isEmpty() ? "" : "," + extra) + "}]";
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

	/** A real (tiny) JPEG; different sizes and colours give different content hashes. */
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

	/** Deletes the given tree, if it is there. */
	private static void delete(Path root) throws IOException {
		if (!Files.exists(root)) {
			return;
		}
		try (Stream<Path> files = Files.walk(root)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
	}

	/** Probe: a folder of folders holding an empty album and one with an image goes to the trash whole, by one rename. */
	public void testProbeAFolderWithAnEmptyAndAFullAlbumGoesToTheTrashWhole() throws Exception {
		sidecar("Year", "[\"ListingInfo\",{\"title\":\"Year\"}]");
		sidecar("Year/Empty", "[\"AlbumInfo\",{\"title\":\"Empty\",\"parts\":[]}]");
		image("Year/Full/a.jpg", 8, 6, Color.RED);
		sidecar("Year/Full", "[\"AlbumInfo\",{\"title\":\"Full\",\"parts\":[" + part("a.jpg", "") + "]}]");

		MoveResult result = delete("/", "Year");

		assertEquals("Year", result.getOutcomes().get(0).getNewName());
		File trashed = new File(trash(), "Year");
		assertTrue(new File(trashed, "Full/a.jpg").isFile());
		assertTrue("The empty album rides along; nothing inside a trashed folder is removed.",
			new File(trashed, "Empty/index.json").isFile());
		assertFalse(_base.resolve("Year").toFile().exists());
	}

	/** Probe: deleting, re-creating and deleting the same name keeps both in the trash. */
	public void testProbeASecondDeleteOfTheSameNameKeepsBoth() throws Exception {
		image("Trip/a.jpg", 8, 6, Color.RED);
		sidecar("Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[" + part("a.jpg", "") + "]}]");
		delete("/", "Trip");
		image("Trip/b.jpg", 8, 6, Color.BLUE);
		sidecar("Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[" + part("b.jpg", "") + "]}]");

		MoveResult result = delete("/", "Trip");

		String second = result.getOutcomes().get(0).getNewName();
		assertTrue("Expected a timestamp prefix, got '" + second + "'.", second.matches("\\d{8}-\\d{6}-Trip"));
		assertTrue(new File(trash(), "Trip/a.jpg").isFile());
		assertTrue(new File(trash(), second + "/b.jpg").isFile());
	}

	/** Probe: one request naming a folder and an unknown name answers per name and deletes the folder. */
	public void testProbeAMixedRequestAnswersPerName() throws Exception {
		image("Trip/a.jpg", 8, 6, Color.RED);
		sidecar("Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[" + part("a.jpg", "") + "]}]");

		MoveResult result = delete("/", "Nowhere", "Trip");

		assertEquals(2, result.getOutcomes().size());
		assertEquals("", result.getOutcomes().get(0).getNewName());
		assertFalse(result.getOutcomes().get(0).getMessage().isEmpty());
		assertEquals("Trip", result.getOutcomes().get(1).getNewName());
		assertTrue(new File(trash(), "Trip/a.jpg").isFile());
	}

}
