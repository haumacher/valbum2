/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.Clearances;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.auth.UserStore.Device;
import de.haumacher.imageServer.auth.UserStore.User;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumKind;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.FolderKind;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Test case for the starred entries of a folder, see issue #239.
 *
 * <p>
 * A star is the entry's own statement: {@link AlbumInfo#isStarred()} for an album or a collection,
 * {@link ListingInfo#isStarred()} for a folder of folders, stored in the entry's own
 * <code>index.json</code> and written by the ordinary sidecar <code>PUT</code>. The listing above
 * answers it on the entry's tile ({@link FolderInfo#isStarred()}), read from the sidecar at listing
 * time like the title.
 * </p>
 *
 * <p>
 * The servlet is driven headlessly on a temporary base folder with the request and response fakes
 * of {@link TestImageServletPut}, exactly as {@link TestFolderPicture} does.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestStarred extends TestCase {

	private static final String ALICE_TOKEN = "alice-token";

	private static final String DAVE_TOKEN = "dave-token";

	private Path _base;

	private ImageServlet _servlet;

	private final List<ImageServlet> _servlets = new ArrayList<>();

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-starred-test");
		_servlet = new ImageServlet(_base.toFile());
		_servlets.add(_servlet);
	}

	@Override
	protected void tearDown() throws Exception {
		for (ImageServlet servlet : _servlets) {
			servlet.destroy();
		}
		_servlets.clear();
		_servlet = null;
		if (_base != null) {
			try (Stream<Path> files = Files.walk(_base)) {
				files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
		super.tearDown();
	}

	// --- Starring through the property save. ---

	/** An album starred by its own properties is answered starred by the listing above. */
	public void testStarringAnAlbumMarksItsTile() throws Exception {
		image("F/A/a.jpg");
		sidecar("F/A", album("A", "a.jpg"));
		image("F/B/b.jpg");
		sidecar("F/B", album("B", "b.jpg"));

		// Read (and cached) before the write: the write must make the listing read again.
		assertFalse(byName(listing("/F/"), "A").isStarred());

		AlbumInfo read = (AlbumInfo) get("/F/A/");
		assertFalse(read.isStarred());
		read.setStarred(true);
		FakeResponse stored = put("/F/A/", write(read));
		assertEquals(stored.body(), HttpServletResponse.SC_OK, stored.status());

		ListingInfo after = listing("/F/");
		assertTrue("The tile of the starred album says so.", byName(after, "A").isStarred());
		assertFalse("Its neighbour is untouched.", byName(after, "B").isStarred());
		assertEquals("The tile is still the album's tile.", FolderKind.ALBUM, byName(after, "A").getKind());
		assertTrue("The album answers its own star.", ((AlbumInfo) get("/F/A/")).isStarred());
		assertTrue("A star is no secret: whoever sees the folder sees it.",
			byName(listing("/F/", "public"), "A").isStarred());

		AlbumInfo again = (AlbumInfo) get("/F/A/");
		again.setStarred(false);
		FakeResponse cleared = put("/F/A/", write(again));
		assertEquals(cleared.body(), HttpServletResponse.SC_OK, cleared.status());
		assertFalse("Unstarring takes it away again.", byName(listing("/F/"), "A").isStarred());
	}

	/**
	 * Probe: the star is the album's own and goes wherever the album goes &mdash; through a retitle
	 * that renames its folder (#130) and a move into another folder.
	 */
	public void testProbeTheStarFollowsARenameAndAMove() throws Exception {
		image("F/A/a.jpg");
		sidecar("F/A", album("A", "a.jpg"));
		image("G/X/x.jpg");
		sidecar("G/X", album("X", "x.jpg"));
		AlbumInfo read = (AlbumInfo) get("/F/A/");
		read.setStarred(true);
		assertEquals(200, put("/F/A/", write(read)).status());

		AlbumInfo retitled = (AlbumInfo) get("/F/A/");
		retitled.setTitle("Holiday");
		FakeResponse renamed = put("/F/A/", write(retitled));
		assertEquals(renamed.body(), HttpServletResponse.SC_OK, renamed.status());
		ListingInfo f = listing("/F/");
		assertEquals("One child, renamed: " + names(f), 1, f.getFolders().size());
		FolderInfo child = f.getFolders().get(0);
		assertTrue("The renamed album is still starred: " + child.getName(), child.isStarred());

		Files.move(_base.resolve("F").resolve(child.getName()), _base.resolve("G").resolve(child.getName()));
		ListingInfo g = listing("/G/");
		assertTrue("Moved, it is starred in its new folder: " + names(g), byName(g, child.getName()).isStarred());
		assertFalse("Its new neighbour is not.", byName(g, "X").isStarred());
	}

	/** A folder of folders is starred by its own sidecar, which the star writes where there is none. */
	public void testStarringAFolder() throws Exception {
		image("F/G/H/h.jpg");
		assertFalse("A folder without a sidecar is not starred.", byName(listing("/F/"), "G").isStarred());
		assertFalse(Files.exists(_base.resolve("F/G/index.json")));

		ListingInfo read = (ListingInfo) get("/F/G/");
		assertFalse(read.isStarred());
		read.setStarred(true);
		FakeResponse stored = put("/F/G/", write(read));
		assertEquals(stored.body(), HttpServletResponse.SC_OK, stored.status());

		String sidecar = read("F/G/index.json");
		assertTrue("Starring a folder writes its sidecar: " + sidecar, sidecar.contains("\"starred\":true"));
		assertTrue("Without the derived entries: " + sidecar, sidecar.contains("\"folders\":[]"));

		FolderInfo tile = byName(listing("/F/"), "G");
		assertTrue("The folder's tile is starred.", tile.isStarred());
		assertEquals("And still a folder.", FolderKind.FOLDER, tile.getKind());
		assertTrue("The folder answers its own star.", ((ListingInfo) get("/F/G/")).isStarred());
	}

	/** A collection is an album kind and is starred like an album, through its own write path. */
	public void testStarringACollection() throws Exception {
		Files.createDirectories(_base.resolve("F"));
		sidecar("F/Best", "[\"AlbumInfo\",{\"kind\":\"COLLECTION\",\"title\":\"Best\",\"parts\":[]}]");
		assertFalse(byName(listing("/F/"), "Best").isStarred());

		AlbumInfo read = (AlbumInfo) get("/F/Best/");
		assertEquals(AlbumKind.COLLECTION, read.getKind());
		read.setStarred(true);
		FakeResponse stored = put("/F/Best/", write(read));
		assertEquals(stored.body(), HttpServletResponse.SC_OK, stored.status());

		FolderInfo tile = byName(listing("/F/"), "Best");
		assertTrue("The collection's tile is starred.", tile.isStarred());
		assertEquals(FolderKind.COLLECTION, tile.getKind());
		AlbumInfo answered = (AlbumInfo) get("/F/Best/");
		assertTrue("The collection answers its own star.", answered.isStarred());
		assertEquals("It stays a collection.", AlbumKind.COLLECTION, answered.getKind());
	}

	// --- The stored form. ---

	/** A sidecar written before the field reads as not starred, in the listing and in the album. */
	public void testAnOlderSidecarIsNotStarred() throws Exception {
		image("F/A/a.jpg");
		sidecar("F/A", album("A", "a.jpg"));
		sidecar("F/G", "[\"ListingInfo\",{\"title\":\"G\"}]");

		ListingInfo listing = listing("/F/");
		assertFalse(byName(listing, "A").isStarred());
		assertFalse(byName(listing, "G").isStarred());
		assertFalse(((AlbumInfo) get("/F/A/")).isStarred());
		assertFalse(((ListingInfo) get("/F/G/")).isStarred());
	}

	/** Read, written and read again says the same thing, the star included. */
	public void testReadWriteRead() throws Exception {
		image("F/A/a.jpg");
		sidecar("F/A", "[\"AlbumInfo\",{\"title\":\"A\",\"starred\":true,"
			+ "\"parts\":[[\"ImagePart\",{\"name\":\"a.jpg\",\"width\":4,\"height\":3}]]}]");
		image("F/G/H/h.jpg");
		sidecar("F/G", "[\"ListingInfo\",{\"title\":\"G\",\"starred\":true}]");

		AlbumInfo album = (AlbumInfo) get("/F/A/");
		assertTrue(album.isStarred());
		ListingInfo folder = (ListingInfo) get("/F/G/");
		assertTrue(folder.isStarred());

		FakeResponse storedAlbum = put("/F/A/", write(album));
		assertEquals(storedAlbum.body(), HttpServletResponse.SC_OK, storedAlbum.status());
		FakeResponse storedFolder = put("/F/G/", write(folder));
		assertEquals(storedFolder.body(), HttpServletResponse.SC_OK, storedFolder.status());

		assertTrue(((AlbumInfo) get("/F/A/")).isStarred());
		assertTrue(((ListingInfo) get("/F/G/")).isStarred());
		ListingInfo listing = listing("/F/");
		assertTrue(byName(listing, "A").isStarred());
		assertTrue(byName(listing, "G").isStarred());

		// The model itself: what is written is what is read.
		AlbumInfo parsed = (AlbumInfo) Resource.readResource(reader(write(AlbumInfo.create().setStarred(true))));
		assertTrue(parsed.isStarred());
		ListingInfo parsedListing =
			(ListingInfo) Resource.readResource(reader(write(ListingInfo.create().setStarred(true))));
		assertTrue(parsedListing.isStarred());
	}

	/** A listing's sidecar never keeps the stars of its entries: they are its children's own. */
	public void testAListingKeepsNoStarOfItsEntries() throws Exception {
		image("F/A/a.jpg");
		sidecar("F/A", "[\"AlbumInfo\",{\"title\":\"A\",\"starred\":true,"
			+ "\"parts\":[[\"ImagePart\",{\"name\":\"a.jpg\",\"width\":4,\"height\":3}]]}]");
		sidecar("F", "[\"ListingInfo\",{\"title\":\"F\"}]");

		ListingInfo read = listing("/F/");
		assertTrue(byName(read, "A").isStarred());
		FakeResponse stored = put("/F/", write(read));
		assertEquals(stored.body(), HttpServletResponse.SC_OK, stored.status());

		String sidecar = read("F/index.json");
		assertTrue("The entries are not stored: " + sidecar, sidecar.contains("\"folders\":[]"));
		assertTrue("Nor is a star the folder never had: " + sidecar, sidecar.contains("\"starred\":false"));
	}

	/** Reading a listing writes no sidecar: neither for an entry without one, nor for an older one. */
	public void testTheListingWritesNothing() throws Exception {
		image("F/A/a.jpg");
		sidecar("F/A", album("A", "a.jpg"));
		image("F/Bare/b.jpg");
		image("F/G/H/h.jpg");
		sidecar("F/S", "[\"AlbumInfo\",{\"title\":\"S\",\"starred\":true,\"parts\":[]}]");

		Map<String, String> before = sidecars();
		ListingInfo listing = listing("/F/");
		assertEquals(4, listing.getFolders().size());
		assertTrue(byName(listing, "S").isStarred());
		assertFalse(byName(listing, "Bare").isStarred());
		assertEquals("Not one sidecar was written or rewritten.", before, sidecars());
	}

	// --- Who may. ---

	/** A member who may not edit the album is refused with the usual sentence, and nothing changes. */
	public void testAMemberWithoutEditIsRefused() throws Exception {
		users();
		image("F/A/a.jpg");
		sidecar("F/A", album("A", "a.jpg"));
		String before = read("F/A/index.json");

		ImageServlet servlet = servlet(AuthMode.WRITES);
		AlbumInfo read = (AlbumInfo) Resource.readResource(reader(get(servlet, "/F/A/", DAVE_TOKEN).body()));
		read.setStarred(true);

		FakeResponse refused = put(servlet, "/F/A/", write(read), DAVE_TOKEN);
		assertEquals(refused.body(), HttpServletResponse.SC_FORBIDDEN, refused.status());
		assertEquals(AuthService.rightRefused(Rights.EDIT),
			((ErrorInfo) Resource.readResource(reader(refused.body()))).getMessage());
		assertEquals("Nothing was written.", before, read("F/A/index.json"));
		assertFalse(byName(listing(servlet, "/F/", DAVE_TOKEN), "A").isStarred());

		// The one who may edit the album may star it.
		FakeResponse stored = put(servlet, "/F/A/", write(read), ALICE_TOKEN);
		assertEquals(stored.body(), HttpServletResponse.SC_OK, stored.status());
		assertTrue(byName(listing(servlet, "/F/", DAVE_TOKEN), "A").isStarred());
	}

	// --- Helpers. ---

	private static List<String> names(ListingInfo listing) {
		return listing.getFolders().stream().map(FolderInfo::getName).collect(Collectors.toList());
	}

	private static FolderInfo byName(ListingInfo listing, String name) {
		for (FolderInfo folder : listing.getFolders()) {
			if (name.equals(folder.getName())) {
				return folder;
			}
		}
		fail("No folder '" + name + "' in " + names(listing));
		return null;
	}

	/** An {@link AlbumInfo} sidecar of the given title, written before issue #239. */
	private static String album(String title, String... images) {
		StringBuilder parts = new StringBuilder();
		for (String image : images) {
			if (parts.length() > 0) {
				parts.append(",");
			}
			parts.append("[\"ImagePart\",{\"name\":\"").append(image).append("\",\"width\":4,\"height\":3}]");
		}
		return "[\"AlbumInfo\",{\"title\":\"" + title + "\",\"parts\":[" + parts + "]}]";
	}

	/** Every <code>index.json</code> below the base folder, by path, with its contents. */
	private Map<String, String> sidecars() throws IOException {
		Map<String, String> result = new TreeMap<>();
		try (Stream<Path> files = Files.walk(_base)) {
			for (Path file : files.collect(Collectors.toList())) {
				if (file.getFileName().toString().startsWith("index.json")) {
					result.put(_base.relativize(file).toString(),
						new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
				}
			}
		}
		return result;
	}

	/** An administrator and a viewer of the one space. */
	private void users() throws IOException {
		UserStore store = new UserStore(_base);
		User alice = store.nameOwner("alice");
		alice.addDevice(new Device("Alice's phone", UserStore.hash(ALICE_TOKEN), Instant.now().toString()));
		User dave = new User("dave", Roles.VIEW, "", Instant.now().toString(), Clearances.ALL, true);
		dave.addDevice(new Device("Dave's phone", UserStore.hash(DAVE_TOKEN), Instant.now().toString()));
		store.addUser(dave);
		store.store();
	}

	private ImageServlet servlet(AuthMode mode) throws Exception {
		ImageServlet servlet = new ImageServlet(_base.toFile(), new AuthService(mode, _base));
		servlet.init();
		_servlets.add(servlet);
		return servlet;
	}

	/** A tiny JPEG at the given (possibly nested) path below the base folder. */
	private void image(String path) throws IOException {
		Path file = _base.resolve(path);
		Files.createDirectories(file.getParent());
		ImageIO.write(new BufferedImage(4, 3, BufferedImage.TYPE_3BYTE_BGR), "jpg", file.toFile());
	}

	private void sidecar(String folder, String json) throws IOException {
		Path dir = _base.resolve(folder);
		Files.createDirectories(dir);
		Files.write(dir.resolve("index.json"), json.getBytes(StandardCharsets.UTF_8));
	}

	private String read(String path) throws IOException {
		return new String(Files.readAllBytes(_base.resolve(path)), StandardCharsets.UTF_8);
	}

	/** The folder resource at the given path, as the app reads it. */
	private FolderResource get(String pathInfo) throws Exception {
		FakeResponse response = get(_servlet, pathInfo, null);
		assertEquals("Reading failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return (FolderResource) Resource.readResource(reader(response.body()));
	}

	private ListingInfo listing(String pathInfo) throws Exception {
		return listing(pathInfo, null);
	}

	private ListingInfo listing(String pathInfo, String viewAs) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "json");
		if (viewAs != null) {
			parameters.put("viewAs", viewAs);
		}
		FakeResponse response = new FakeResponse();
		_servlet.doGet(request(pathInfo, null, "", null, parameters), response.response());
		assertEquals("Reading failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return (ListingInfo) Resource.readResource(reader(response.body()));
	}

	private static ListingInfo listing(ImageServlet servlet, String pathInfo, String token) throws Exception {
		FakeResponse response = get(servlet, pathInfo, token);
		assertEquals("Reading failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return (ListingInfo) Resource.readResource(reader(response.body()));
	}

	private static FakeResponse get(ImageServlet servlet, String pathInfo, String token) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "json");
		FakeResponse response = new FakeResponse();
		servlet.doGet(request(pathInfo, null, "", token, parameters), response.response());
		return response;
	}

	private FakeResponse put(String pathInfo, String body) throws Exception {
		return put(_servlet, pathInfo, body, null);
	}

	private static FakeResponse put(ImageServlet servlet, String pathInfo, String body, String token)
			throws Exception {
		FakeResponse response = new FakeResponse();
		servlet.doPut(request(pathInfo, "application/json", body, token, new HashMap<>()), response.response());
		return response;
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

	private static JsonReader reader(String contents) {
		return new JsonReader(new ReaderAdapter(new StringReader(contents)));
	}

	private static String write(Resource resource) throws IOException {
		StringWriter buffer = new StringWriter();
		try (JsonWriter json = new JsonWriter(new WriterAdapter(buffer))) {
			resource.writeTo(json);
		}
		return buffer.toString();
	}
}
