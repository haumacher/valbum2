/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.PresentFile;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.UploadCheckResult;
import de.haumacher.imageServer.shared.model.UploadResult;
import de.haumacher.imageServer.shared.model.UploadedFile;
import de.haumacher.imageServer.upload.HashCache;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Test case for idempotent uploads keyed by the content hash (issue #29).
 *
 * <p>
 * The servlet is driven headlessly on a temporary base folder, as in {@link TestImageServletPut},
 * whose request and response fakes are reused; the multipart bodies are built by hand.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestImageServletUpload extends TestCase {

	private static final File JPEG_FIXTURE =
		new File("src/test/fixtures/test-album/2005-08-24 Blumen und Fliegen/IMG_0417.JPG");

	private static final byte[] RED = "red pixels".getBytes(StandardCharsets.UTF_8);

	private static final byte[] BLUE = "blue pixels".getBytes(StandardCharsets.UTF_8);

	private static final String BOUNDARY = "----valbumTestBoundary";

	private Path _base;

	private ImageServlet _servlet;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-upload-test");
		_servlet = servlet(AuthMode.OFF);
	}

	@Override
	protected void tearDown() throws Exception {
		if (_base != null) {
			try (Stream<Path> files = Files.walk(_base)) {
				files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
		super.tearDown();
	}

	// --- The folder upload. ---

	public void testUploadStoresTheFiles() throws Exception {
		UploadResult result = upload(_servlet, "/", files("a.jpg", RED, "b.jpg", BLUE));

		assertEquals(Arrays.asList("a.jpg", "b.jpg"), storedNames(result));
		assertEquals(Arrays.asList(ImageServlet.STORED, ImageServlet.STORED), states(result));
		assertEquals(HashCache.sha256(RED), result.getFiles().get(0).getHash());
		assertTrue(Arrays.equals(RED, contents("a.jpg")));
		assertTrue(Arrays.equals(BLUE, contents("b.jpg")));
	}

	public void testTheSameUploadAgainIsANoOp() throws Exception {
		upload(_servlet, "/", files("a.jpg", RED, "b.jpg", BLUE));

		List<String> before = entries(_base);
		String cacheBefore = read(new File(_base.toFile(), HashCache.FILE_NAME));

		UploadResult result = upload(_servlet, "/", files("a.jpg", RED, "b.jpg", BLUE));

		assertEquals("A retried upload must store nothing.",
			Arrays.asList(ImageServlet.PRESENT, ImageServlet.PRESENT), states(result));
		assertEquals("The answer names the files that already hold the contents.",
			Arrays.asList("a.jpg", "b.jpg"), storedNames(result));
		assertEquals("A retried upload must not add a file.", before, entries(_base));
		assertEquals("Nothing changed, so the cache must not change either.", cacheBefore,
			read(new File(_base.toFile(), HashCache.FILE_NAME)));
		assertTrue(Arrays.equals(RED, contents("a.jpg")));
		assertTrue(Arrays.equals(BLUE, contents("b.jpg")));
	}

	public void testRetryUnderADifferentNameIsAlsoANoOp() throws Exception {
		upload(_servlet, "/", files("a.jpg", RED));

		UploadResult result = upload(_servlet, "/", files("copy-of-a.jpg", RED));

		assertEquals(Collections.singletonList(ImageServlet.PRESENT), states(result));
		assertEquals("The contents are known under their original name.",
			Collections.singletonList("a.jpg"), storedNames(result));
		assertEquals("The upload must not have created a second copy.",
			Collections.singletonList("a.jpg"), imageNames());
	}

	public void testSameNameDifferentContentKeepsBoth() throws Exception {
		upload(_servlet, "/", files("a.jpg", RED));

		UploadResult result = upload(_servlet, "/", files("a.jpg", BLUE));

		assertEquals(Collections.singletonList(ImageServlet.STORED), states(result));
		assertEquals("a.jpg", result.getFiles().get(0).getName());
		assertEquals("a-2.jpg", result.getFiles().get(0).getStoredAs());
		assertEquals(Arrays.asList("a-2.jpg", "a.jpg"), imageNames());
		assertTrue("The original must survive untouched.", Arrays.equals(RED, contents("a.jpg")));
		assertTrue(Arrays.equals(BLUE, contents("a-2.jpg")));
	}

	public void testTwoIdenticalFilesInOneRequest() throws Exception {
		UploadResult result = upload(_servlet, "/", files("a.jpg", RED, "b.jpg", RED));

		assertEquals(Arrays.asList(ImageServlet.STORED, ImageServlet.PRESENT), states(result));
		assertEquals(Arrays.asList("a.jpg", "a.jpg"), storedNames(result));
		assertEquals(Collections.singletonList("a.jpg"), imageNames());
	}

	public void testUploadToASubFolder() throws Exception {
		File folder = new File(_base.toFile(), "album");
		assertTrue(folder.mkdir());

		upload(_servlet, "/album/", files("a.jpg", RED));
		UploadResult again = upload(_servlet, "/album/", files("a.jpg", RED));

		assertEquals(Collections.singletonList(ImageServlet.PRESENT), states(again));
		assertEquals("The same contents in another folder are a different album's business.",
			Collections.singletonList(ImageServlet.STORED), states(upload(_servlet, "/", files("a.jpg", RED))));
	}

	public void testUnsupportedExtensionStoresNothing() throws Exception {
		FakeResponse response = uploadResponse(_servlet, "/", files("a.jpg", RED, "notes.txt", BLUE));

		assertEquals(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, response.status());
		assertEquals("A refused upload must store nothing at all.",
			Collections.emptyList(), imageNames());
	}

	public void testTheFolderHoldsNothingButTheImagesAndTheSidecars() throws Exception {
		upload(_servlet, "/", files("a.jpg", RED, "b.jpg", BLUE));

		assertEquals(Arrays.asList(".hashes.json", ".upload", "a.jpg", "b.jpg"), entries(_base));
		assertEquals("The upload repository must be emptied.", Collections.emptyList(),
			entries(_base.resolve(".upload")));
	}

	// --- The single-image PUT. ---

	public void testSingleImagePutStores() throws Exception {
		UploadResult result = upload(_servlet, "/a.jpg", files("a.jpg", RED));

		assertEquals(Collections.singletonList(ImageServlet.STORED), states(result));
		assertEquals("a.jpg", result.getFiles().get(0).getStoredAs());
		assertTrue(Arrays.equals(RED, contents("a.jpg")));
	}

	public void testSingleImagePutOfTheSameContentsIsPresent() throws Exception {
		upload(_servlet, "/a.jpg", files("a.jpg", RED));

		UploadResult result = upload(_servlet, "/a.jpg", files("a.jpg", RED));

		assertEquals(Collections.singletonList(ImageServlet.PRESENT), states(result));
		assertEquals("a.jpg", result.getFiles().get(0).getStoredAs());
		assertEquals(Collections.singletonList("a.jpg"), imageNames());
	}

	public void testSingleImagePutNeverReplacesAnOriginal() throws Exception {
		upload(_servlet, "/a.jpg", files("a.jpg", RED));

		FakeResponse response = uploadResponse(_servlet, "/a.jpg", files("a.jpg", BLUE));

		assertEquals(HttpServletResponse.SC_CONFLICT, response.status());
		assertEquals(ImageServlet.REPLACE_REFUSED, errorMessage(response));
		assertTrue("The original must survive byte for byte.", Arrays.equals(RED, contents("a.jpg")));
		assertEquals(Collections.singletonList("a.jpg"), imageNames());
	}

	public void testSingleImagePutOfContentsPresentUnderAnotherName() throws Exception {
		upload(_servlet, "/", files("a.jpg", RED));

		UploadResult result = upload(_servlet, "/b.jpg", files("b.jpg", RED));

		assertEquals(Collections.singletonList(ImageServlet.PRESENT), states(result));
		assertEquals("a.jpg", result.getFiles().get(0).getStoredAs());
		assertEquals(Collections.singletonList("a.jpg"), imageNames());
	}

	// --- The pre-check. ---

	public void testCheckReportsPresentAndAbsentHashes() throws Exception {
		upload(_servlet, "/", files("a.jpg", RED));

		UploadCheckResult result = check(_servlet, "/", null, HashCache.sha256(RED), HashCache.sha256(BLUE));

		assertEquals(1, result.getPresent().size());
		PresentFile present = result.getPresent().get(0);
		assertEquals(HashCache.sha256(RED), present.getHash());
		assertEquals("a.jpg", present.getName());
	}

	public void testCheckOfAnEmptyFolder() throws Exception {
		UploadCheckResult result = check(_servlet, "/", null, HashCache.sha256(RED));

		assertEquals(Collections.emptyList(), result.getPresent());
	}

	public void testCheckSeesFilesThatWereNeverUploaded() throws Exception {
		Files.write(_base.resolve("dropped-in.jpg"), BLUE);

		UploadCheckResult result = check(_servlet, "/", null, HashCache.sha256(BLUE));

		assertEquals("The cache is only a cache: the folder decides.", "dropped-in.jpg",
			result.getPresent().get(0).getName());
	}

	public void testCheckIsAReadAndServedAnonymously() throws Exception {
		ImageServlet servlet = servlet(AuthMode.WRITES);
		upload(servlet, "/", files("a.jpg", RED), token(servlet));

		UploadCheckResult result = check(servlet, "/", null, HashCache.sha256(RED));

		assertEquals("a.jpg", result.getPresent().get(0).getName());
	}

	public void testCheckIsRefusedWhereReadsAre() throws Exception {
		ImageServlet servlet = servlet(AuthMode.ALL);

		FakeResponse response = checkResponse(servlet, "/", null, HashCache.sha256(RED));

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status());
		assertEquals(AuthService.READ_REFUSED, errorMessage(response));
	}

	public void testCheckOfAnUnknownFolder() throws Exception {
		FakeResponse response = checkResponse(_servlet, "/nowhere/", null, HashCache.sha256(RED));

		assertEquals(HttpServletResponse.SC_NOT_FOUND, response.status());
	}

	public void testUnparsableCheckRefusedWithAReason() throws Exception {
		FakeResponse response = new FakeResponse();
		_servlet.doPost(request("/", "application/json", "not JSON at all".getBytes(StandardCharsets.UTF_8),
			null, parameters("action", "check")), response.response());

		assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status());
		assertEquals(ImageServlet.CHECK_UNREADABLE, errorMessage(response));
	}

	// --- The hash cache sidecar. ---

	public void testTheCacheWrittenByThisBuildLoadsBack() throws Exception {
		upload(_servlet, "/", files("a.jpg", RED, "b.jpg", BLUE));

		String stored = read(new File(_base.toFile(), HashCache.FILE_NAME));
		assertTrue("The cache must be versioned: " + stored, stored.contains("\"version\":1"));

		HashCache reloaded = new HashCache(_base.toFile());
		Map<String, String> hashes = reloaded.hashByName();
		assertEquals(HashCache.sha256(RED), hashes.get("a.jpg"));
		assertEquals(HashCache.sha256(BLUE), hashes.get("b.jpg"));
		reloaded.flush();
		assertEquals("Reading an up-to-date cache must not rewrite it.", stored,
			read(new File(_base.toFile(), HashCache.FILE_NAME)));
	}

	public void testAHandWrittenCacheWithAnUnknownKeyIsUsed() throws Exception {
		Files.write(_base.resolve("a.jpg"), RED);
		File file = new File(_base.toFile(), "a.jpg");

		// A cache written by a future build: it carries entries this build does not know, and a
		// deliberately wrong hash proves that the recorded value is trusted rather than recomputed.
		String handWritten = "{\"version\":1,\"comment\":\"hand written\",\"files\":{\"a.jpg\":{"
			+ "\"size\":" + file.length() + ",\"modified\":" + file.lastModified()
			+ ",\"sha256\":\"" + "f".repeat(64) + "\",\"colorProfile\":\"sRGB\"}}}";
		Files.write(_base.resolve(HashCache.FILE_NAME), handWritten.getBytes(StandardCharsets.UTF_8));

		UploadCheckResult result = check(_servlet, "/", null, "f".repeat(64));

		assertEquals("a.jpg", result.getPresent().get(0).getName());
	}

	public void testAStaleEntryIsRecomputed() throws Exception {
		upload(_servlet, "/", files("a.jpg", RED));

		// The file changes behind the server's back, as a file manager would change it.
		File file = new File(_base.toFile(), "a.jpg");
		Files.write(file.toPath(), BLUE);
		assertTrue(file.setLastModified(file.lastModified() + 10_000));

		UploadCheckResult result = check(_servlet, "/", null, HashCache.sha256(RED), HashCache.sha256(BLUE));

		assertEquals(1, result.getPresent().size());
		assertEquals("The stale entry must be gone.", HashCache.sha256(BLUE), result.getPresent().get(0).getHash());
		assertEquals("a.jpg", result.getPresent().get(0).getName());
	}

	public void testACorruptCacheIsRebuilt() throws Exception {
		upload(_servlet, "/", files("a.jpg", RED));
		Files.write(_base.resolve(HashCache.FILE_NAME), "}{ this is not JSON".getBytes(StandardCharsets.UTF_8));

		UploadResult result = upload(_servlet, "/", files("a.jpg", RED));

		assertEquals("A broken cache must not cause a duplicate.",
			Collections.singletonList(ImageServlet.PRESENT), states(result));
		assertEquals(Collections.singletonList("a.jpg"), imageNames());
		assertTrue(read(new File(_base.toFile(), HashCache.FILE_NAME)).contains("\"version\":1"));
	}

	public void testAVanishedFileIsForgotten() throws Exception {
		upload(_servlet, "/", files("a.jpg", RED, "b.jpg", BLUE));
		assertTrue(new File(_base.toFile(), "b.jpg").delete());

		UploadCheckResult result = check(_servlet, "/", null, HashCache.sha256(RED), HashCache.sha256(BLUE));

		assertEquals(1, result.getPresent().size());
		assertEquals("a.jpg", result.getPresent().get(0).getName());
		assertFalse("The forgotten entry must be gone from the sidecar.",
			read(new File(_base.toFile(), HashCache.FILE_NAME)).contains("b.jpg"));
	}

	public void testTheSidecarIsNoAlbumPart() throws Exception {
		byte[] jpeg = Files.readAllBytes(JPEG_FIXTURE.toPath());

		upload(_servlet, "/", files("IMG_0417.JPG", jpeg));

		String album = albumJson(_servlet, "/");
		assertTrue("Expected the uploaded image in the album: " + album, album.contains("IMG_0417.JPG"));
		assertFalse("The hash cache must never be listed as an album part: " + album,
			album.contains(HashCache.FILE_NAME));
	}

	// --- Helpers. ---

	private AuthService auth(AuthMode mode) {
		return new AuthService(mode, "let-me-in", _base);
	}

	private ImageServlet servlet(AuthMode mode) throws Exception {
		ImageServlet servlet = new ImageServlet(_base.toFile(), auth(mode));
		servlet.init();
		return servlet;
	}

	private String token(ImageServlet servlet) throws Exception {
		FakeResponse response = new FakeResponse();
		servlet.doPost(request("/", "application/json",
			"{\"secret\":\"let-me-in\",\"deviceName\":\"Phone\"}".getBytes(StandardCharsets.UTF_8), null,
			parameters("action", "pair")), response.response());
		assertEquals("Pairing failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return de.haumacher.imageServer.shared.model.PairResponse.readPairResponse(reader(response.body())).getToken();
	}

	private static LinkedHashMap<String, byte[]> files(Object... nameAndContents) {
		LinkedHashMap<String, byte[]> result = new LinkedHashMap<>();
		for (int n = 0; n < nameAndContents.length; n += 2) {
			result.put((String) nameAndContents[n], (byte[]) nameAndContents[n + 1]);
		}
		return result;
	}

	private UploadResult upload(ImageServlet servlet, String pathInfo, LinkedHashMap<String, byte[]> files)
			throws Exception {
		return upload(servlet, pathInfo, files, null);
	}

	private UploadResult upload(ImageServlet servlet, String pathInfo, LinkedHashMap<String, byte[]> files,
			String token) throws Exception {
		FakeResponse response = uploadResponse(servlet, pathInfo, files, token);
		assertEquals("Upload failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return UploadResult.readUploadResult(reader(response.body()));
	}

	private FakeResponse uploadResponse(ImageServlet servlet, String pathInfo, LinkedHashMap<String, byte[]> files)
			throws Exception {
		return uploadResponse(servlet, pathInfo, files, null);
	}

	private FakeResponse uploadResponse(ImageServlet servlet, String pathInfo, LinkedHashMap<String, byte[]> files,
			String token) throws Exception {
		FakeResponse response = new FakeResponse();
		servlet.doPut(request(pathInfo, "multipart/form-data; boundary=" + BOUNDARY, multipart(files), token,
			Collections.emptyMap()), response.response());
		return response;
	}

	private UploadCheckResult check(ImageServlet servlet, String pathInfo, String token, String... hashes)
			throws Exception {
		FakeResponse response = checkResponse(servlet, pathInfo, token, hashes);
		assertEquals("Check failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return UploadCheckResult.readUploadCheckResult(reader(response.body()));
	}

	private FakeResponse checkResponse(ImageServlet servlet, String pathInfo, String token, String... hashes)
			throws Exception {
		String body = "{\"hashes\":["
			+ Arrays.stream(hashes).map(h -> "{\"hash\":\"" + h + "\"}").collect(Collectors.joining(","))
			+ "]}";
		FakeResponse response = new FakeResponse();
		servlet.doPost(request(pathInfo, "application/json", body.getBytes(StandardCharsets.UTF_8), token,
			parameters("action", "check")), response.response());
		return response;
	}

	private String albumJson(ImageServlet servlet, String pathInfo) throws Exception {
		FakeResponse response = new FakeResponse();
		servlet.doGet(request(pathInfo, null, new byte[0], null, parameters("type", "json")), response.response());
		assertEquals(HttpServletResponse.SC_OK, response.status());
		return response.body();
	}

	/** A multipart body carrying the given files, as a browser or the app would send it. */
	private static byte[] multipart(LinkedHashMap<String, byte[]> files) throws IOException {
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

	private static Map<String, String> parameters(String name, String value) {
		Map<String, String> result = new HashMap<>();
		result.put(name, value);
		return result;
	}

	private static HttpServletRequest request(String pathInfo, String contentType, byte[] body, String token,
			Map<String, String> parameters) {
		Map<String, String> headers = new HashMap<>();
		if (contentType != null) {
			headers.put("Content-Type", contentType);
		}
		if (token != null) {
			headers.put("Authorization", "Bearer " + token);
		}
		return TestImageServletPut.request(pathInfo, contentType, body, headers, parameters);
	}

	private static List<String> storedNames(UploadResult result) {
		return result.getFiles().stream().map(UploadedFile::getStoredAs).collect(Collectors.toList());
	}

	private static List<String> states(UploadResult result) {
		return result.getFiles().stream().map(UploadedFile::getStatus).collect(Collectors.toList());
	}

	private byte[] contents(String name) throws IOException {
		return Files.readAllBytes(_base.resolve(name));
	}

	/** The image files of the base folder, sorted. */
	private List<String> imageNames() {
		File[] images = _base.toFile().listFiles(f -> f.isFile() && ResourceCache.isImage(f));
		if (images == null) {
			return Collections.emptyList();
		}
		List<String> result = new ArrayList<>();
		for (File image : images) {
			result.add(image.getName());
		}
		Collections.sort(result);
		return result;
	}

	private static String errorMessage(FakeResponse response) throws IOException {
		Resource resource = Resource.readResource(reader(response.body()));
		assertTrue("Expected an ErrorInfo body, got: " + response.body(), resource instanceof ErrorInfo);
		return ((ErrorInfo) resource).getMessage();
	}

	private static JsonReader reader(String contents) {
		return new JsonReader(new ReaderAdapter(new StringReader(contents)));
	}

	private static String read(File file) throws IOException {
		return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
	}

	private static List<String> entries(Path directory) {
		String[] names = directory.toFile().list();
		if (names == null) {
			return Collections.emptyList();
		}
		return Arrays.stream(names).sorted().collect(Collectors.toList());
	}

}
