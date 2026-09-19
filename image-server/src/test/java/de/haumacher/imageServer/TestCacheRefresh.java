/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.auth.UserStore.Device;
import de.haumacher.imageServer.auth.UserStore.User;
import de.haumacher.imageServer.shared.model.CacheRefreshed;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Test case for throwing an album's generated cache away, see issue #98.
 *
 * <p>
 * The dangerous part of this feature is what it must <em>not</em> delete, so every test that
 * deletes anything compares a hash listing of the whole tree outside the cache directory before
 * and after: an original or a sidecar that changed is the failure this test exists for.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestCacheRefresh extends TestCase {

	private static final File FIXTURE = new File("src/test/fixtures/test-album");

	/** The album of the fixture that holds the video as well as photos. */
	static final String ALBUM = "2005-08-24 Blumen und Fliegen";

	private static final String ALICE_TOKEN = "alice-token";

	Path _base;

	private ImageServlet _servlet;

	private AuthService _auth;

	String _adminToken;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-cache-refresh");
		copy(FIXTURE.toPath(), _base);
	}

	@Override
	protected void tearDown() throws Exception {
		if (_servlet != null) {
			_servlet.destroy();
			_servlet = null;
		}
		if (_base != null) {
			try (Stream<Path> files = Files.walk(_base)) {
				files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
		super.tearDown();
	}

	// --- What goes, and what stays. ---

	/**
	 * The generated files go, everything else stays — down to the last byte of every original and
	 * every sidecar.
	 */
	public void testTheAdminThrowsTheGeneratedFilesAway() throws Exception {
		signIn();
		File cacheDir = seedCache(ALBUM);
		Map<String, String> before = hashes();

		CacheRefreshed result = refresh("/" + ALBUM + "/", _adminToken);

		assertEquals("The four generated files, and only those.", 4, result.getRemoved());
		assertFalse(new File(cacheDir, "preview-x.jpg").exists());
		assertFalse(new File(cacheDir, "video-y.mp4").exists());
		assertFalse(new File(cacheDir, "teaser-y.mp4").exists());
		assertFalse(new File(cacheDir, "preview-z.jpg.tmp").exists());
		assertTrue("A file the server did not write stays.", new File(cacheDir, "notes.txt").exists());
		assertTrue("The cache directory itself may stay.", cacheDir.isDirectory());
		assertEquals("Nothing outside the cache directory may be touched.", before, hashes());
	}

	/** A folder that never had a cache is refreshed just the same, and nothing happens. */
	public void testAFolderWithoutACache() throws Exception {
		signIn();
		Map<String, String> before = hashes();

		CacheRefreshed result = refresh("/" + ALBUM + "/", _adminToken);

		assertEquals(0, result.getRemoved());
		assertEquals(before, hashes());
		assertFalse("No cache directory is created by refreshing one.",
			new File(new File(_base.toFile(), ALBUM), PreviewCache.CACHE_DIRECTORY_NAME).exists());
	}

	/** Only the folder that was addressed: the cache of the folder below it is untouched. */
	public void testItIsNotRecursive() throws Exception {
		signIn();
		File album = seedCache(ALBUM);
		File other = seedCache("2002-03-03 Schlosspark Karlsruhe");

		assertEquals(0, refresh("/", _adminToken).getRemoved());
		assertTrue(new File(album, "preview-x.jpg").exists());
		assertTrue(new File(other, "preview-x.jpg").exists());

		assertEquals(4, refresh("/" + ALBUM + "/", _adminToken).getRemoved());
		assertFalse(new File(album, "preview-x.jpg").exists());
		assertTrue("The album next door keeps its previews.", new File(other, "preview-x.jpg").exists());
	}

	/** A preview really is made again afterwards, exactly as for an album that never had one. */
	public void testTheNextThumbnailIsMadeAfresh() throws Exception {
		signIn();
		String image = "IMG_0415.JPG";
		FakeResponse first = thumbnail("/" + ALBUM + "/" + image, _adminToken);
		assertEquals(first.body(), HttpServletResponse.SC_OK, first.status());
		File preview = onlyPreview(ALBUM);
		long size = preview.length();
		assertTrue("A preview must have been written.", size > 0);

		// As an early build left it behind: the right name, the right date, broken contents.
		Files.write(preview.toPath(), new byte[] { (byte) 0xFF, (byte) 0xD8 });
		assertTrue(preview.setLastModified(System.currentTimeMillis()));

		assertTrue("The broken preview must be thrown away.", refresh("/" + ALBUM + "/", _adminToken)
			.getRemoved() >= 1);
		assertFalse(preview.exists());

		FakeResponse again = thumbnail("/" + ALBUM + "/" + image, _adminToken);
		assertEquals(again.body(), HttpServletResponse.SC_OK, again.status());
		File made = onlyPreview(ALBUM);
		assertEquals("The preview is made again under its own name.", preview.getName(), made.getName());
		assertEquals("And it is the whole preview again, not the broken stump.", size, made.length());
	}

	// --- Who may. ---

	/** Editing the album is not administering the server. */
	public void testAnEditUserIsRefused() throws Exception {
		signIn();
		File cacheDir = seedCache(ALBUM);

		FakeResponse response = refreshResponse("/" + ALBUM + "/", ALICE_TOKEN);

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(ImageServlet.CACHE_REFRESH_REFUSED, errorMessage(response));
		assertTrue("Nothing may have been deleted.", new File(cacheDir, "preview-x.jpg").exists());
	}

	/** An anonymous caller of an open space is told how to become somebody. */
	public void testAnAnonymousCallerIsRefused() throws Exception {
		signIn();
		File cacheDir = seedCache(ALBUM);

		FakeResponse response = refreshResponse("/" + ALBUM + "/", null);

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status());
		assertEquals("Bearer", response.header("WWW-Authenticate"));
		assertTrue(new File(cacheDir, "preview-x.jpg").exists());
	}

	/** A folder that is not there is not there, even for the administrator. */
	public void testAMissingFolder() throws Exception {
		signIn();

		FakeResponse response = refreshResponse("/nowhere/", _adminToken);

		assertEquals(HttpServletResponse.SC_NOT_FOUND, response.status());
	}

	/**
	 * A server without any authentication has nobody to refuse: whoever reaches it runs it.
	 */
	public void testWithoutAuthenticationEverybodyMay() throws Exception {
		_auth = new AuthService(AuthMode.OFF, _base);
		_servlet = new ImageServlet(_base.toFile(), _auth);
		_servlet.init();
		File cacheDir = seedCache(ALBUM);
		Map<String, String> before = hashes();

		assertEquals(4, refresh("/" + ALBUM + "/", null).getRemoved());
		assertTrue(new File(cacheDir, "notes.txt").exists());
		assertEquals(before, hashes());
	}

	// --- The name rule, on its own. ---

	public void testWhichNamesAreGenerated() throws Exception {
		assertTrue(CacheRefresh.isGenerated("preview-IMG_0415.JPG.jpg"));
		assertTrue(CacheRefresh.isGenerated("preview-MVI_0450.mp4.jpg"));
		assertTrue(CacheRefresh.isGenerated("preview-IMG_0415.JPG.jpg.tmp"));
		assertTrue(CacheRefresh.isGenerated("video-MVI_0450.mp4"));
		assertTrue(CacheRefresh.isGenerated("teaser-MVI_0450.mp4"));
		assertTrue(CacheRefresh.isGenerated("video-MVI_0450.mp4.tmp"));

		assertFalse("A sidecar is never generated.", CacheRefresh.isGenerated("index.json"));
		assertFalse(CacheRefresh.isGenerated(".hashes.json"));
		assertFalse(CacheRefresh.isGenerated("index.json.bak"));
		assertFalse("An original that happens to sit there stays.", CacheRefresh.isGenerated("IMG_0415.JPG"));
		assertFalse("A note beside the previews is not the server's.", CacheRefresh.isGenerated("notes.txt"));
		assertFalse("A temporary file of somebody else's is not the server's either.",
			CacheRefresh.isGenerated("notes.txt.tmp"));
		assertFalse("A rendition is an mp4 or it is not a rendition.",
			CacheRefresh.isGenerated("video-notes.txt"));
		assertFalse("The prefix alone is not a name.", CacheRefresh.isGenerated("preview-"));
	}

	/** A sidecar lying inside the cache directory is not deleted either. */
	public void testASidecarInsideTheCacheSurvives() throws Exception {
		signIn();
		File cacheDir = seedCache(ALBUM);
		Files.write(new File(cacheDir, "index.json").toPath(),
			"[\"AlbumInfo\",{}]".getBytes(StandardCharsets.UTF_8));
		Files.createDirectories(new File(cacheDir, "sub").toPath());

		assertEquals(4, refresh("/" + ALBUM + "/", _adminToken).getRemoved());

		assertTrue(new File(cacheDir, "index.json").exists());
		assertTrue("A directory is never deleted.", new File(cacheDir, "sub").isDirectory());
	}

	// --- The failed-transcode memory of issue #74. ---

	/**
	 * A rendition that failed once is tried again after a refresh; the memory of another folder is
	 * untouched.
	 */
	public void testTheFailedTranscodeMemoryIsForgotten() throws Exception {
		VideoRenditions renditions = new VideoRenditions();
		try {
			File album = new File(_base.toFile(), ALBUM);
			File other = new File(_base.toFile(), "2002-03-03 Schlosspark Karlsruhe");
			File mine = VideoRenditions.file(new File(album, "MVI_0450.mp4"), VideoRenditions.Kind.PLAYBACK);
			File theirs = VideoRenditions.file(new File(other, "MVI_0450.mp4"), VideoRenditions.Kind.TEASER);
			renditions.remember(mine, "broken");
			renditions.remember(theirs, "broken");
			assertEquals("broken", renditions.failure(mine));

			int forgotten = renditions.forget(CacheRefresh.cacheDir(album));

			assertEquals(1, forgotten);
			assertNull("The failure of this folder must be forgotten.", renditions.failure(mine));
			assertEquals("The failure of another folder must stand.", "broken", renditions.failure(theirs));
		} finally {
			renditions.shutdown();
		}
	}

	/** The servlet's own renditions forget the folder that was refreshed. */
	public void testTheServletForgetsTheFolder() throws Exception {
		signIn();
		File album = new File(_base.toFile(), ALBUM);
		File rendition = VideoRenditions.file(new File(album, "MVI_0450.mp4"), VideoRenditions.Kind.PLAYBACK);
		_servlet.videos().remember(rendition, "broken");

		refresh("/" + ALBUM + "/", _adminToken);

		assertNull("A refreshed folder starts over, transcodes included.",
			_servlet.videos().failure(rendition));
	}

	// --- Helpers. ---

	/**
	 * A space with an administrator "haui" and an "alice" who may edit, and a servlet serving it.
	 */
	/** The servlet under test, for the probe. */
	ImageServlet _servletForProbe() {
		return _servlet;
	}

	void signIn() throws Exception {
		UserStore store = new UserStore(_base);
		store.nameOwner("haui");
		User alice = new User("alice", Roles.EDIT, "", Instant.now().toString());
		alice.addDevice(new Device("Alice's tablet", UserStore.hash(ALICE_TOKEN), Instant.now().toString()));
		store.addUser(alice);
		store.store();

		_auth = new AuthService(AuthMode.WRITES, _base);
		_servlet = new ImageServlet(_base.toFile(), _auth);
		_servlet.init();
		_adminToken = Codes.signInAdmin(_auth, "Phone", "haui").getToken();
	}

	/**
	 * Seeds the cache directory of the given folder with the four files the server writes and one
	 * it does not.
	 */
	File seedCache(String folder) throws Exception {
		File cacheDir = new File(new File(_base.toFile(), folder), PreviewCache.CACHE_DIRECTORY_NAME);
		Files.createDirectories(cacheDir.toPath());
		write(cacheDir, "preview-x.jpg", "a preview");
		write(cacheDir, "video-y.mp4", "a playback rendition");
		write(cacheDir, "teaser-y.mp4", "a teaser");
		write(cacheDir, "preview-z.jpg.tmp", "a leftover");
		write(cacheDir, "notes.txt", "not the server's");
		return cacheDir;
	}

	private static void write(File dir, String name, String contents) throws Exception {
		Files.write(new File(dir, name).toPath(), contents.getBytes(StandardCharsets.UTF_8));
	}

	/** The one preview of the given album's cache directory. */
	private File onlyPreview(String folder) {
		File cacheDir = new File(new File(_base.toFile(), folder), PreviewCache.CACHE_DIRECTORY_NAME);
		File[] previews = cacheDir.listFiles((dir, name) -> name.startsWith(PreviewCache.PREVIEW_PREFIX));
		assertNotNull("No cache directory at " + cacheDir, previews);
		assertEquals("Expected exactly one preview.", 1, previews.length);
		return previews[0];
	}

	CacheRefreshed refresh(String pathInfo, String token) throws Exception {
		FakeResponse response = refreshResponse(pathInfo, token);
		assertEquals("The refresh failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return CacheRefreshed.readCacheRefreshed(reader(response.body()));
	}

	FakeResponse refreshResponse(String pathInfo, String token) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "refresh-cache");
		Map<String, String> headers = new HashMap<>();
		if (token != null) {
			headers.put("Authorization", "Bearer " + token);
		}
		FakeResponse response = new FakeResponse();
		_servlet.doPost(TestImageServletPut.request(pathInfo, null, new byte[0], headers, parameters),
			response.response());
		return response;
	}

	private FakeResponse thumbnail(String pathInfo, String token) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "tn");
		Map<String, String> headers = new HashMap<>();
		if (token != null) {
			headers.put("Authorization", "Bearer " + token);
		}
		FakeResponse response = new FakeResponse();
		_servlet.doGet(TestImageServletPut.request(pathInfo, null, new byte[0], headers, parameters),
			response.response());
		return response;
	}

	private static String errorMessage(FakeResponse response) throws Exception {
		Resource resource = Resource.readResource(reader(response.body()));
		assertTrue("Expected an error, got: " + resource, resource instanceof ErrorInfo);
		return ((ErrorInfo) resource).getMessage();
	}

	private static JsonReader reader(String json) {
		return new JsonReader(new ReaderAdapter(new StringReader(json)));
	}

	/**
	 * The SHA-256 of every file of the tree that is not inside a cache directory, by relative
	 * path.
	 *
	 * <p>
	 * The guard of this whole test: the originals are sacred, and the only way to say so is to
	 * read them.
	 * </p>
	 */
	Map<String, String> hashes() throws Exception {
		Map<String, String> result = new LinkedHashMap<>();
		try (Stream<Path> files = Files.walk(_base)) {
			for (Path path : (Iterable<Path>) files.sorted()::iterator) {
				String relative = _base.relativize(path).toString();
				if (relative.contains(PreviewCache.CACHE_DIRECTORY_NAME)) {
					continue;
				}
				if (Files.isDirectory(path)) {
					result.put(relative + "/", "");
				} else {
					result.put(relative, hash(path));
				}
			}
		}
		return result;
	}

	private static String hash(Path file) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		StringBuilder buffer = new StringBuilder();
		for (byte b : digest.digest(Files.readAllBytes(file))) {
			buffer.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
		}
		return buffer.toString();
	}

	/** Copies the fixture tree, because a test never writes into the fixtures. */
	private static void copy(Path from, Path to) throws Exception {
		try (Stream<Path> files = Files.walk(from)) {
			for (Path source : (Iterable<Path>) files::iterator) {
				Path target = to.resolve(from.relativize(source).toString());
				if (Files.isDirectory(source)) {
					Files.createDirectories(target);
				} else {
					Files.createDirectories(target.getParent());
					Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
				}
			}
		}
	}

}
