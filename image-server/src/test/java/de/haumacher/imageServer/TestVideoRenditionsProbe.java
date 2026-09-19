/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import static de.haumacher.imageServer.TestImageServletPut.request;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Probe for the video renditions of #74, composed with the invalidation rule of the previews and
 * the privacy levels of #46.
 */
@SuppressWarnings("javadoc")
public class TestVideoRenditionsProbe extends TestCase {

	private static final File VIDEO_FIXTURE =
		new File("src/test/fixtures/test-album/2005-08-24 Blumen und Fliegen/MVI_0450.mp4");

	private static final String SECRET = "probe-secret";

	private Path _base;

	private File _album;

	private ImageServlet _servlet;

	@Override
	protected void setUp() throws Exception {
		_base = Files.createTempDirectory("valbum-rendition-probe");
		_album = new File(_base.toFile(), "Trip");
		assertTrue(_album.mkdirs());
		Files.copy(VIDEO_FIXTURE.toPath(), new File(_album, "clip.mp4").toPath());
	}

	@Override
	protected void tearDown() throws Exception {
		if (_servlet != null) {
			_servlet.destroy();
		}
		try (Stream<Path> files = Files.walk(_base)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
	}

	/** A rendition older than its original is made again, once, on the next request. */
	public void testNewerOriginalInvalidatesRendition() throws Exception {
		servlet(AuthMode.OFF);
		File video = new File(_album, "clip.mp4");
		assertEquals(HttpServletResponse.SC_ACCEPTED, get("clip.mp4", "teaser", null).status());
		assertTrue(_servlet.videos().awaitQueue(60000));
		File rendition = VideoRenditions.file(video, VideoRenditions.Kind.TEASER);
		assertTrue(rendition.exists());
		long firstMade = rendition.lastModified();

		// The original is replaced by a newer file (a re-upload, an edit outside the server): the
		// rendition made earlier is older than it.
		assertTrue(rendition.setLastModified(firstMade - 120000));
		assertTrue(video.setLastModified(firstMade - 60000));

		assertEquals("A stale rendition is not served.", HttpServletResponse.SC_ACCEPTED,
			get("clip.mp4", "teaser", null).status());
		assertTrue(_servlet.videos().awaitQueue(60000));
		assertTrue("The rendition must be fresh again.", rendition.lastModified() >= video.lastModified());
		assertEquals(HttpServletResponse.SC_OK, get("clip.mp4", "teaser", null).status());
		assertEquals("Nothing but the rendition and the poster may be in the cache.", 0,
			new File(_album, PreviewCache.CACHE_DIRECTORY_NAME).list((d, n) -> n.endsWith(".tmp")).length);
	}

	/** A private video's renditions are refused to anonymous callers exactly like its thumbnail. */
	public void testPrivateVideoRefusedAnonymously() throws Exception {
		String index = "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":["
			+ "[\"ImagePart\",{\"name\":\"clip.mp4\",\"kind\":\"VIDEO\",\"width\":4,\"height\":3,\"privacy\":2}]]}]";
		Files.write(_album.toPath().resolve("index.json"), index.getBytes(StandardCharsets.UTF_8));
		// Paired before the servlet exists, so that the servlet's own store knows the device.
		String token = Codes.signInAdmin(new AuthService(AuthMode.WRITES, _base), "Phone", "haui").getToken();
		servlet(AuthMode.WRITES);

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, get("clip.mp4", "tn", null).status());
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, get("clip.mp4", "video", null).status());
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, get("clip.mp4", "teaser", null).status());
		assertTrue(_servlet.videos().awaitQueue(60000));
		assertFalse("A refused request must not start a transcode.",
			VideoRenditions.file(new File(_album, "clip.mp4"), VideoRenditions.Kind.PLAYBACK).exists());

		assertEquals(HttpServletResponse.SC_ACCEPTED, get("clip.mp4", "video", token).status());
		assertTrue(_servlet.videos().awaitQueue(60000));
		FakeResponse served = get("clip.mp4", "video", token);
		assertEquals(HttpServletResponse.SC_OK, served.status());
		assertEquals("video/mp4", served.contentType());
		// Still refused to the anonymous caller although the file now exists.
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, get("clip.mp4", "video", null).status());
	}

	/** Both kinds asked for in the same breath are two files, and the poster keeps working. */
	public void testBothKindsBesideThePoster() throws Exception {
		servlet(AuthMode.OFF);
		assertEquals(HttpServletResponse.SC_OK, get("clip.mp4", "tn", null).status());
		assertEquals(HttpServletResponse.SC_ACCEPTED, get("clip.mp4", "video", null).status());
		assertEquals(HttpServletResponse.SC_ACCEPTED, get("clip.mp4", "teaser", null).status());
		assertTrue(_servlet.videos().awaitQueue(120000));
		File cache = new File(_album, PreviewCache.CACHE_DIRECTORY_NAME);
		assertTrue(new File(cache, "preview-clip.mp4.jpg").exists());
		assertTrue(new File(cache, "video-clip.mp4").exists());
		assertTrue(new File(cache, "teaser-clip.mp4").exists());
		assertEquals(HttpServletResponse.SC_OK, get("clip.mp4", "video", null).status());
		assertEquals(HttpServletResponse.SC_OK, get("clip.mp4", "teaser", null).status());
		assertEquals("image/jpeg", get("clip.mp4", "tn", null).contentType());
	}

	private void servlet(AuthMode mode) throws Exception {
		_servlet = new ImageServlet(_base.toFile(), new AuthService(mode, _base));
	}

	private FakeResponse get(String name, String type, String token) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", type);
		Map<String, String> headers = new HashMap<>();
		if (token != null) {
			headers.put("Authorization", "Bearer " + token);
		}
		FakeResponse response = new FakeResponse();
		_servlet.doGet(request("/Trip/" + name, null, new byte[0], headers, parameters), response.response());
		return response;
	}
}
