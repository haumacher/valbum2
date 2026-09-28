/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.MoveOutcome;
import de.haumacher.imageServer.shared.model.MoveResult;
import de.haumacher.imageServer.shared.model.ReanalyzeResult;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.UploadCheckResult;
import de.haumacher.imageServer.upload.HashCache;
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
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Test case for the litter other systems write into a library, see issue #173.
 *
 * <p>
 * A temporary copy of the fixture album (never the committed one) is littered the way a Synology
 * share and a Windows or Mac desktop litter it: DSM's <code>@eaDir</code> with a real JPEG
 * thumbnail below an album, the recycle bin of the share at the space root, a
 * <code>Thumbs.db</code>, a <code>.DS_Store</code> and a Mac's <code>._</code> companion of a
 * photograph beside the photographs. None of it is ever an entry, a photograph, a hash of the
 * index, an address, something a walk goes into or something a move carries as an image.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestLibraryLitter extends TestCase {

	private static final File FIXTURE = new File("src/test/fixtures/test-album");

	/** An album of the fixture holding photographs only, and no sidecar. */
	private static final String ALBUM = "2002-03-03 Schlosspark Karlsruhe";

	private static final String THUMB = ALBUM + "/@eaDir/SYNOPHOTO_THUMB_M.jpg";

	private static final String STREAM = ALBUM + "/@eaDir/SYNOPHOTO_THUMB_M.jpg@SynoEAStream";

	private static final String RECYCLED = "#recycle/old.jpg";

	private static final String BOUNDARY = "litter-boundary";

	private Path _base;

	private ImageServlet _servlet;

	/** The photographs the fixture album holds, by name, before anything was littered. */
	private List<String> _photos;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-litter-test");
		copy(FIXTURE.toPath(), _base);
		_photos = photosOf(_base.resolve(ALBUM));

		write(THUMB, jpeg(12, 9, Color.MAGENTA));
		write(STREAM, "DSM extended attributes".getBytes(StandardCharsets.UTF_8));
		write(RECYCLED, jpeg(14, 9, Color.ORANGE));
		write(ALBUM + "/Thumbs.db", new byte[] { 0x0d, 0x0e, 0x0a, 0x0d });
		write(ALBUM + "/.DS_Store", new byte[] { 0, 0, 0, 1, 'B', 'u', 'd', '1' });
		write(ALBUM + "/._P3031375.JPG", jpeg(10, 9, Color.CYAN));

		_servlet = new ImageServlet(_base.toFile(), new AuthService(AuthMode.OFF, _base));
		_servlet.init();
	}

	@Override
	protected void tearDown() throws Exception {
		if (_servlet != null) {
			_servlet.destroy();
			_servlet = null;
		}
		if (_base != null) {
			delete(_base);
		}
		super.tearDown();
	}

	// --- The predicate. ---

	public void testThePredicate() {
		for (String name : LibraryFiles.GENERATED) {
			assertTrue(name, LibraryFiles.isIgnored(name));
			assertTrue(name, LibraryFiles.isGenerated(name));
			assertFalse(name, LibraryFiles.isHeld(name));
			assertTrue("Compared ignoring case: " + name, LibraryFiles.isIgnored(name.toUpperCase(Locale.ROOT)));
			assertTrue("Compared ignoring case: " + name, LibraryFiles.isGenerated(name.toLowerCase(Locale.ROOT)));
		}
		for (String name : LibraryFiles.HELD) {
			assertTrue("Ignored like the generated names: " + name, LibraryFiles.isIgnored(name));
			assertTrue(name, LibraryFiles.isHeld(name));
			assertFalse("Held, never generated: " + name, LibraryFiles.isGenerated(name));
			assertTrue("Compared ignoring case: " + name, LibraryFiles.isIgnored(name.toUpperCase(Locale.ROOT)));
			assertTrue("Compared ignoring case: " + name, LibraryFiles.isHeld(name.toLowerCase(Locale.ROOT)));
		}
		assertTrue(LibraryFiles.isIgnored(".vacache"));
		assertTrue(LibraryFiles.isIgnored("._IMG_1.jpg"));
		assertFalse("A hidden file is not generated: nobody knows who wrote it.", LibraryFiles.isGenerated(".notes"));
		assertFalse(LibraryFiles.isHeld(".notes"));
		for (String name : Arrays.asList("eaDir", "@eaDir2", "recycle", "my #recycle", "Thumbs.db.bak", "Thumbs",
			"2020-01-01 Trip", "IMG_1.jpg", "@home")) {
			assertFalse(name, LibraryFiles.isIgnored(name));
			assertFalse(name, LibraryFiles.isGenerated(name));
			assertFalse(name, LibraryFiles.isHeld(name));
		}
		assertFalse(LibraryFiles.isIgnored((String) null));
	}

	// --- Not shown. ---

	public void testTheListingShowsNoLitterFolder() throws Exception {
		ListingInfo root = (ListingInfo) resource(getJson("/"));

		List<String> names = root.getFolders().stream().map(FolderInfo::getName).collect(Collectors.toList());
		assertFalse("The recycle bin of the share is no folder of the library: " + names,
			names.contains("#recycle"));
		assertTrue(names.contains(ALBUM));
	}

	public void testTheAlbumShowsNoLitter() throws Exception {
		AlbumInfo album = (AlbumInfo) resource(getJson("/" + ALBUM + "/"));

		assertEquals("Exactly the photographs of the album, the litter beside them not among them.", _photos,
			sorted(partNames(album)));
	}

	public void testALitterFolderHoldingNoAlbumIsNoFolderOfFolders() throws Exception {
		// DSM writes @eaDir into a folder of folders too.
		write("Year/@eaDir/SYNOPHOTO_THUMB_S.jpg", jpeg(6, 5, Color.PINK));
		Files.createDirectories(_base.resolve("Year/Trip"));
		write("Year/Trip/a.jpg", jpeg(8, 6, Color.RED));

		Resource year = resource(getJson("/Year/"));

		assertTrue("A folder whose only picture is DSM's thumbnail is a folder of folders: " + year,
			year instanceof ListingInfo);
		assertEquals(Collections.singletonList("Trip"),
			((ListingInfo) year).getFolders().stream().map(FolderInfo::getName).collect(Collectors.toList()));
	}

	// --- Not hashed. ---

	public void testTheIndexHashesNoLitter() throws Exception {
		_servlet.index().indexNow();

		assertTrue("The index works: a photograph of the album is found.",
			check("/generated/", sha(ALBUM + "/" + _photos.get(0))).getPresent().size() == 1);
		assertEquals("DSM's thumbnail is no photograph of the space.", Collections.emptyList(),
			check("/generated/", sha(THUMB)).getPresent());
		assertEquals("Neither is a photograph in the recycle bin of the share.", Collections.emptyList(),
			check("/generated/", sha(RECYCLED)).getPresent());
		assertEquals("Nor a Mac's companion file.", Collections.emptyList(),
			check("/generated/", sha(ALBUM + "/._P3031375.JPG")).getPresent());

		assertFalse("No hash cache is written into a litter folder.",
			_base.resolve(ALBUM + "/@eaDir/" + HashCache.FILE_NAME).toFile().exists());
		assertFalse(_base.resolve("#recycle/" + HashCache.FILE_NAME).toFile().exists());
		String hashes = new String(Files.readAllBytes(_base.resolve(ALBUM).resolve(HashCache.FILE_NAME)),
			StandardCharsets.UTF_8);
		for (String litter : Arrays.asList("Thumbs.db", ".DS_Store", "._P3031375.JPG", "SYNOPHOTO")) {
			assertFalse("The album's hashes name no '" + litter + "': " + hashes, hashes.contains(litter));
		}
	}

	/**
	 * An index file an earlier build wrote names litter folders and litter files: both are dropped
	 * on load, and the next write leaves them out.
	 */
	public void testAnOlderIndexFileIsCleanedOnLoad() throws Exception {
		String thumb = "cd".repeat(32);
		String companion = "ef".repeat(32);
		String photo = sha(ALBUM + "/" + _photos.get(0));
		Path file = indexFile();
		Files.createDirectories(file.getParent());
		Files.writeString(file, "{\"version\":1,\"folders\":{"
			+ "\"" + ALBUM + "/@eaDir\":{\"stamp\":1,\"hashes\":{\"" + thumb + "\":\"SYNOPHOTO_THUMB_M.jpg\"}},"
			+ "\"" + ALBUM + "\":{\"stamp\":1,\"hashes\":{\"" + companion + "\":\"._P3031375.JPG\",\"" + photo
			+ "\":\"" + _photos.get(0) + "\"}}}}", StandardCharsets.UTF_8);

		de.haumacher.imageServer.upload.HashIndex index = new de.haumacher.imageServer.upload.HashIndex(_base);
		try {
			assertNull("DSM's thumbnail is still on disk, and still no answer.", index.pathOf(thumb));
			assertNull("Neither is the Mac's companion file.", index.pathOf(companion));
			assertEquals("What is the library's stays.", ALBUM + "/" + _photos.get(0), index.pathOf(photo));

			index.flush();
			String written = Files.readString(file, StandardCharsets.UTF_8);
			assertFalse("The next write leaves the litter out: " + written, written.contains("@eaDir"));
			assertFalse(written, written.contains("._P3031375.JPG"));
			assertTrue(written, written.contains(photo));
		} finally {
			index.shutdown();
		}
	}

	/** A sidecar that somehow lies in a litter folder is never taken into the index. */
	public void testASidecarInALitterFolderIsNeverRecorded() throws Exception {
		File litter = _base.resolve(ALBUM + "/@eaDir").toFile();
		// What an earlier build's lazy fill could have written there.
		HashCache cache = new HashCache(litter);
		cache.refresh();
		cache.flush();
		assertTrue(new File(litter, HashCache.FILE_NAME).isFile());

		de.haumacher.imageServer.upload.HashIndex index = new de.haumacher.imageServer.upload.HashIndex(_base);
		try {
			index.folderChanged(litter);
			index.treeChanged(_base.resolve(ALBUM).toFile());
			assertNull(index.pathOf(sha(THUMB)));
		} finally {
			index.shutdown();
		}
	}

	/** An interrupted pass drops nothing it did not reach: resuming is what the file is for (#118). */
	public void testAnInterruptedPassDropsNothing() throws Exception {
		String kept = "12".repeat(32);
		Path file = indexFile();
		Files.createDirectories(file.getParent());
		Files.writeString(file, "{\"version\":1,\"folders\":{\"" + ALBUM + "\":{\"stamp\":1,\"hashes\":{\"" + kept
			+ "\":\"" + _photos.get(0) + "\"}}}}", StandardCharsets.UTF_8);

		de.haumacher.imageServer.upload.HashIndex index = new de.haumacher.imageServer.upload.HashIndex(_base);
		try {
			Thread.currentThread().interrupt();
			try {
				index.indexNow();
			} finally {
				Thread.interrupted();
			}
			assertFalse(index.isComplete());
			assertEquals("The entry of a folder the pass never reached is still there.", ALBUM + "/" + _photos.get(0),
				index.pathOf(kept));
		} finally {
			index.shutdown();
		}
	}

	private Path indexFile() {
		return _base.resolve(de.haumacher.imageServer.auth.UserStore.DIRECTORY_NAME)
			.resolve(de.haumacher.imageServer.upload.HashIndex.FILE_NAME);
	}

	// --- Not addressable. ---

	public void testLitterIsNotAddressable() throws Exception {
		for (String path : Arrays.asList("/" + THUMB, "/" + RECYCLED, "/#recycle/", "/" + ALBUM + "/@eaDir/",
			"/" + ALBUM + "/@EADIR/SYNOPHOTO_THUMB_M.jpg", "/" + ALBUM + "/Thumbs.db", "/" + ALBUM + "/.DS_Store",
			"/" + ALBUM + "/._P3031375.JPG")) {
			for (String type : Arrays.asList("tn", "json")) {
				FakeResponse response = get(path, "type", type);
				assertEquals(path + "?type=" + type, HttpServletResponse.SC_NOT_FOUND, response.status());
				assertEquals(path + "?type=" + type, AuthService.PATH_ESCAPED, errorMessage(response));
			}
		}
	}

	// --- Not walked. ---

	public void testAReanalysisReadsNoLitter() throws Exception {
		String before = fingerprint(_base.resolve(ALBUM + "/@eaDir"));

		FakeResponse response = post("/" + ALBUM + "/", "{}", "action", "reanalyze");

		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		ReanalyzeResult result = ReanalyzeResult.readReanalyzeResult(reader(response.body()));
		assertEquals("The photographs of the album and nothing below it.", _photos.size(), result.getExamined());
		assertFalse("DSM's folder was never taken for an album and written a sidecar.",
			_base.resolve(ALBUM + "/@eaDir/index.json").toFile().exists());
		assertEquals("Nothing in DSM's folder changed.", before, fingerprint(_base.resolve(ALBUM + "/@eaDir")));
	}

	public void testAMoveOfTheAlbumTakesItsLitterAlongAsFiles() throws Exception {
		Files.createDirectories(_base.resolve("Target"));
		write("Target/index.json", "[\"ListingInfo\",{\"title\":\"Target\"}]".getBytes(StandardCharsets.UTF_8));
		String before = fingerprint(_base.resolve(ALBUM));

		MoveResult result = move("/", "Target", ALBUM);

		assertEquals(ALBUM, result.getOutcomes().get(0).getNewName());
		assertEquals("The folder moved by one rename, its litter inside it untouched.", before,
			fingerprint(_base.resolve("Target").resolve(ALBUM)));
	}

	public void testLitterIsNoEntryAMoveCarries() throws Exception {
		Files.createDirectories(_base.resolve("Other"));
		List<String> litter = Arrays.asList("@eaDir", "Thumbs.db", ".DS_Store", "._P3031375.JPG");
		String before = fingerprint(_base.resolve(ALBUM));

		MoveResult result = move("/" + ALBUM + "/", "Other", litter.toArray(new String[0]));

		for (MoveOutcome outcome : result.getOutcomes()) {
			assertEquals(MoveService.notAnEntry(outcome.getName()), outcome.getMessage());
		}
		assertEquals("Nothing moved.", before, fingerprint(_base.resolve(ALBUM)));
		assertEquals(Collections.emptyList(), Arrays.asList(_base.resolve("Other").toFile().list()));
	}

	public void testAMoveOfPhotosLeavesTheLitterWhereItIs() throws Exception {
		Files.createDirectories(_base.resolve("Other"));

		move("/" + ALBUM + "/", "Other", _photos.toArray(new String[0]));

		AlbumInfo other = (AlbumInfo) resource(getJson("/Other/"));
		assertEquals(_photos, sorted(partNames(other)));
		assertTrue(_base.resolve(THUMB).toFile().isFile());
		assertTrue(_base.resolve(ALBUM + "/Thumbs.db").toFile().isFile());
		assertFalse(_base.resolve("Other/@eaDir").toFile().exists());
	}

	// --- Not a name a user can give. ---

	public void testAnUploadOfAHiddenNameIsRefused() throws Exception {
		Map<String, byte[]> files = new java.util.LinkedHashMap<>();
		files.put("IMG_9999.jpg", jpeg(9, 8, Color.GREEN));
		files.put("._IMG_9999.jpg", jpeg(9, 7, Color.GREEN));
		FakeResponse response = new FakeResponse();
		_servlet.doPut(request("/" + ALBUM + "/", "multipart/form-data; boundary=" + BOUNDARY, multipart(files),
			Collections.emptyMap()), response.response());

		assertEquals("A file the library would never show is not stored.",
			HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, response.status());
		assertFalse("An upload is accepted as a whole or not at all.",
			_base.resolve(ALBUM + "/IMG_9999.jpg").toFile().exists());
		assertFalse(_base.resolve(ALBUM + "/._IMG_9999.jpg").toFile().exists());
	}

	public void testNoAlbumIsCreatedUnderALitterName() throws Exception {
		String body = "[\"AlbumInfo\",{\"title\":\"x\",\"parts\":[]}]";
		for (String name : Arrays.asList("@eaDir", "#recycle", "@EADIR", "Thumbs.db", ".@__thumb", ".hidden")) {
			for (String parent : Arrays.asList("", "/" + ALBUM)) {
				String path = parent + "/" + name + "/";
				FakeResponse response = put(path, body);

				assertEquals(path + ": " + response.body(), HttpServletResponse.SC_BAD_REQUEST, response.status());
				assertEquals(FolderNames.illegalName(name), errorMessage(response));
			}
		}
		assertFalse("Nothing was written.", _base.resolve("@eaDir").toFile().exists());
		assertFalse(_base.resolve("#recycle/index.json").toFile().exists());
		assertFalse(_base.resolve(ALBUM + "/@eaDir/index.json").toFile().exists());
		assertFalse(_base.resolve(ALBUM + "/.hidden").toFile().exists());
	}

	public void testNoAlbumIsRenamedToALitterName() throws Exception {
		assertEquals(HttpServletResponse.SC_OK,
			put("/Trip/", "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[]}]").status());

		for (String title : Arrays.asList("@eaDir", "#recycle", "desktop.ini")) {
			FakeResponse response = put("/Trip/", "[\"AlbumInfo\",{\"title\":\"" + title + "\",\"parts\":[]}]");

			assertEquals(title + ": " + response.body(), HttpServletResponse.SC_BAD_REQUEST, response.status());
			assertEquals(FolderNames.illegalName(title), errorMessage(response));
		}
		assertTrue("The album keeps its name and its sidecar.",
			new String(Files.readAllBytes(_base.resolve("Trip/index.json")), StandardCharsets.UTF_8)
				.contains("\"Trip\""));
	}

	public void testALitterNameIsNoFolderPicture() {
		assertNull(FolderCover.chosen(ListingInfo.create().setIndex("@eaDir")));
		assertNull(FolderCover.chosen(ListingInfo.create().setIndex("#Recycle")));
		assertEquals("Trip", FolderCover.chosen(ListingInfo.create().setIndex("Trip")));
	}

	/**
	 * A sidecar an earlier build wrote lists the Mac's companion file it showed as a photograph,
	 * even inside a group and as the album picture: the album answers none of it, and its next write
	 * leaves it out.
	 */
	public void testAnOlderSidecarNamingLitterIsReadWithoutIt() throws Exception {
		String first = _photos.get(0);
		String second = _photos.get(1);
		write(ALBUM + "/index.json", ("[\"AlbumInfo\",{\"title\":\"Schlosspark Karlsruhe\","
			+ "\"indexPicture\":{\"image\":\"._P3031375.JPG\"},\"parts\":["
			+ "[\"ImageGroup\",{\"representative\":1,\"images\":["
			+ "{\"name\":\"" + first + "\",\"kind\":\"IMAGE\",\"width\":8,\"height\":6},"
			+ "{\"name\":\"._P3031375.JPG\",\"kind\":\"IMAGE\",\"width\":8,\"height\":6}]}],"
			+ "[\"ImagePart\",{\"name\":\".DS_Store\",\"kind\":\"IMAGE\",\"width\":8,\"height\":6}],"
			+ "[\"ImagePart\",{\"name\":\"" + second + "\",\"kind\":\"IMAGE\",\"width\":8,\"height\":6,"
			+ "\"comment\":\"kept\"}]]}]").getBytes(StandardCharsets.UTF_8));

		String json = getJson("/" + ALBUM + "/");
		AlbumInfo album = (AlbumInfo) resource(json);

		assertEquals(_photos, sorted(partNames(album)));
		assertFalse("A group of one is no group: " + json, album.getParts().stream().anyMatch(p -> p instanceof ImageGroup));
		assertNull("The album picture named what is no photograph.", album.getIndexPicture());
		assertEquals("Everything else stands.", "kept", album.getParts().stream().filter(p -> p instanceof ImagePart && second.equals(((ImagePart) p).getName()))
				.map(p -> ((ImagePart) p).getComment()).findFirst().orElse(null));

		assertEquals(HttpServletResponse.SC_OK, put("/" + ALBUM + "/", json).status());
		String stored = new String(Files.readAllBytes(_base.resolve(ALBUM + "/index.json")), StandardCharsets.UTF_8);
		assertFalse("The next write leaves it out: " + stored, stored.contains("._P3031375.JPG"));
		assertFalse(stored, stored.contains(".DS_Store"));
		assertTrue("The companion file itself is untouched.", _base.resolve(ALBUM + "/._P3031375.JPG").toFile().isFile());
	}

	// --- The round trip. ---

	public void testAnAlbumWithLitterReadsBackAsItWasWritten() throws Exception {
		String first = getJson("/" + ALBUM + "/");
		assertEquals(HttpServletResponse.SC_OK, put("/" + ALBUM + "/", first).status());
		String second = getJson("/" + ALBUM + "/");

		assertEquals(sorted(partNames((AlbumInfo) resource(first))), sorted(partNames((AlbumInfo) resource(second))));
		assertEquals(_photos, sorted(partNames((AlbumInfo) resource(second))));
		assertEquals(HttpServletResponse.SC_OK, put("/" + ALBUM + "/", second).status());
		assertEquals("Read, written and read again, the album is the album.", second, getJson("/" + ALBUM + "/"));
		assertTrue("The litter is still there, untouched.", _base.resolve(THUMB).toFile().isFile());
	}

	// --- Helpers. ---

	private static List<String> photosOf(Path album) {
		List<String> result = new ArrayList<>();
		for (String name : album.toFile().list()) {
			if (name.toUpperCase(Locale.ROOT).endsWith(".JPG")) {
				result.add(name);
			}
		}
		assertFalse(result.isEmpty());
		return sorted(result);
	}

	private static List<String> sorted(List<String> names) {
		return new ArrayList<>(new TreeSet<>(names));
	}

	private static List<String> partNames(AlbumInfo album) {
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

	private UploadCheckResult check(String pathInfo, String hash) throws Exception {
		FakeResponse response = post(pathInfo, "{\"hashes\":[{\"hash\":\"" + hash + "\"}]}", "action", "check");
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		return UploadCheckResult.readUploadCheckResult(reader(response.body()));
	}

	private MoveResult move(String pathInfo, String target, String... names) throws Exception {
		String body = "{\"target\":\"" + target + "\",\"names\":["
			+ Arrays.stream(names).map(n -> "{\"name\":\"" + n + "\"}").collect(Collectors.joining(",")) + "]}";
		FakeResponse response = post(pathInfo, body, "action", "move");
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		return MoveResult.readMoveResult(reader(response.body()));
	}

	private String sha(String relativePath) throws Exception {
		return HashCache.sha256(_base.resolve(relativePath).toFile());
	}

	private String getJson(String pathInfo) throws Exception {
		FakeResponse response = get(pathInfo, "type", "json");
		assertEquals("Reading failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return response.body();
	}

	private FakeResponse get(String pathInfo, String name, String value) throws Exception {
		FakeResponse response = new FakeResponse();
		_servlet.doGet(request(pathInfo, null, new byte[0], parameters(name, value)), response.response());
		return response;
	}

	private FakeResponse post(String pathInfo, String body, String name, String value) throws Exception {
		FakeResponse response = new FakeResponse();
		_servlet.doPost(request(pathInfo, "application/json", body.getBytes(StandardCharsets.UTF_8),
			parameters(name, value)), response.response());
		return response;
	}

	private FakeResponse put(String pathInfo, String body) throws Exception {
		FakeResponse response = new FakeResponse();
		_servlet.doPut(request(pathInfo, "application/json", body.getBytes(StandardCharsets.UTF_8),
			Collections.emptyMap()), response.response());
		return response;
	}

	private static Map<String, String> parameters(String name, String value) {
		Map<String, String> result = new HashMap<>();
		result.put(name, value);
		return result;
	}

	private static HttpServletRequest request(String pathInfo, String contentType, byte[] body,
			Map<String, String> parameters) {
		Map<String, String> headers = new HashMap<>();
		if (contentType != null) {
			headers.put("Content-Type", contentType);
		}
		return TestImageServletPut.request(pathInfo, contentType, body, headers, parameters);
	}

	/** A multipart body carrying the given files, as a browser or the app would send it. */
	private static byte[] multipart(Map<String, byte[]> files) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (Map.Entry<String, byte[]> file : files.entrySet()) {
			out.write(("--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.UTF_8));
			out.write(("Content-Disposition: form-data; name=\"" + file.getKey() + "\"; filename=\""
				+ file.getKey() + "\"\r\n").getBytes(StandardCharsets.UTF_8));
			out.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
			out.write(file.getValue());
			out.write("\r\n".getBytes(StandardCharsets.UTF_8));
		}
		out.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
		return out.toByteArray();
	}

	private static Resource resource(String json) throws IOException {
		return Resource.readResource(reader(json));
	}

	private static String errorMessage(FakeResponse response) throws IOException {
		Resource resource = resource(response.body());
		assertTrue("Expected an ErrorInfo body, got: " + response.body(), resource instanceof ErrorInfo);
		return ((ErrorInfo) resource).getMessage();
	}

	private static JsonReader reader(String contents) {
		return new JsonReader(new ReaderAdapter(new StringReader(contents)));
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

	/**
	 * Copies the fixture without the server's own state and caches, which a running demo server
	 * may have written into it.
	 */
	private static void copy(Path from, Path to) throws Exception {
		try (Stream<Path> files = Files.walk(from)) {
			for (Path source : (Iterable<Path>) files::iterator) {
				String relative = from.relativize(source).toString();
				if (relative.contains(PreviewCache.CACHE_DIRECTORY_NAME) || relative.startsWith(".valbum")
					|| relative.startsWith(".upload")) {
					continue;
				}
				Path target = to.resolve(relative);
				if (Files.isDirectory(source)) {
					Files.createDirectories(target);
				} else {
					Files.createDirectories(target.getParent());
					Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
				}
			}
		}
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
