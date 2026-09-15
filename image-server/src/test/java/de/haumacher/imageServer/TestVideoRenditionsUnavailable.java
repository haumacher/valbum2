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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Test case for what a rendition request answers when this server cannot transcode, see issue #81.
 *
 * <p>
 * On a machine without the system libraries the bundled FFmpeg links, loading the program throws
 * an {@link UnsatisfiedLinkError}. That used to escape the worker thread, which left the request
 * answered <code>202</code> for ever: the queue said the transcode was in flight, nothing was
 * recorded, and the app waited for a file that was never going to appear.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestVideoRenditionsUnavailable extends TestCase {

	private static final File VIDEO_FIXTURE =
		new File("src/test/fixtures/test-album/2005-08-24 Blumen und Fliegen/MVI_0450.mp4");

	private static final String ALBUM = "2005-08-24 Trip";

	private Path _base;

	private File _album;

	private File _video;

	private final List<ImageServlet> _servlets = new ArrayList<>();

	private ImageServlet _servlet;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-rendition-unavailable");
		_album = new File(_base.toFile(), ALBUM);
		assertTrue(_album.mkdirs());
		assertTrue("Missing fixture: " + VIDEO_FIXTURE.getAbsolutePath(), VIDEO_FIXTURE.exists());
		_video = new File(_album, "MVI_0450.mp4");
		Files.copy(VIDEO_FIXTURE.toPath(), _video.toPath());
	}

	@Override
	protected void tearDown() throws Exception {
		// Whatever a test injected, the next one meets the bundled program again.
		VideoRenditions.setProgramLocator(null);
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

	/**
	 * A program that cannot be loaded is said so at once, on the very first request, and nothing
	 * is queued for it.
	 */
	public void testUnloadableProgramIsRefusedAtOnce() throws Exception {
		VideoRenditions.setProgramLocator(() -> {
			throw new UnsatisfiedLinkError("no jniavdevice in java.library.path");
		});

		FakeResponse first = get("video");
		assertEquals("A server that cannot transcode must not answer 202.",
			HttpServletResponse.SC_INTERNAL_SERVER_ERROR, first.status());
		assertTrue(first.body(), first.body().contains(ImageServlet.RENDITIONS_UNAVAILABLE));
		assertTrue("The reason must be named: " + first.body(),
			first.body().contains("no jniavdevice in java.library.path"));
		assertEquals("no-store", first.header("Cache-Control"));
		assertNull("A refusal is not a retry.", first.header("Retry-After"));

		assertEquals("Nothing may be queued that cannot be done.", 0, servlet().videos().queued());
		assertFalse("No rendition may appear.",
			VideoRenditions.file(_video, VideoRenditions.Kind.PLAYBACK).exists());
		assertNoTempFiles();

		// The same answer for the teaser, and for every later request.
		assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, get("teaser").status());
		assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, get("video").status());
		assertEquals(0, servlet().videos().queued());
	}

	/**
	 * An {@link Error} thrown while the transcode runs is recorded like any other failure, so the
	 * next request is answered <code>500</code> and not <code>202</code> again.
	 */
	public void testErrorInTheWorkerBecomesAFailure() throws Exception {
		// The program answers while the availability is checked and throws afterwards, as a native
		// library that fails to load on the worker thread does.
		AtomicBoolean broken = new AtomicBoolean();
		String bundled = VideoRenditions.executable();
		VideoRenditions.setProgramLocator(() -> {
			if (broken.get()) {
				throw new NoClassDefFoundError("Could not initialize class org.bytedeco.ffmpeg.global.avdevice");
			}
			return bundled;
		});
		assertNull("The check must pass before the program breaks.", VideoRenditions.unavailability());
		broken.set(true);

		FakeResponse pending = get("video");
		assertEquals("The server believes it can transcode, so it queues and says so.",
			HttpServletResponse.SC_ACCEPTED, pending.status());

		assertTrue("The transcoder did not finish.", servlet().videos().awaitQueue(60000));
		assertEquals("A failed transcode must leave nothing in flight.", 0, servlet().videos().queued());

		FakeResponse failed = get("video");
		assertEquals("An Error in the worker must not answer 202 for ever.",
			HttpServletResponse.SC_INTERNAL_SERVER_ERROR, failed.status());
		assertTrue(failed.body(), failed.body().contains(ImageServlet.RENDITION_FAILED));
		assertFalse("No rendition may appear.",
			VideoRenditions.file(_video, VideoRenditions.Kind.PLAYBACK).exists());
		assertNoTempFiles();
	}

	/** On a machine that has the program, renditions are available and nothing is refused. */
	public void testAvailableOnThisMachine() throws Exception {
		assertNull("The bundled FFmpeg must load here: " + VideoRenditions.unavailability(),
			VideoRenditions.unavailability());
		assertNotNull("An H.264 encoder must have been found.", VideoRenditions.encoderName());
		assertEquals(HttpServletResponse.SC_ACCEPTED, get("video").status());
		assertTrue(servlet().videos().awaitQueue(120000));
	}

	private void assertNoTempFiles() {
		File cacheDir = new File(_album, PreviewCache.CACHE_DIRECTORY_NAME);
		String[] leftOver = cacheDir.list((dir, name) -> name.endsWith(PreviewCache.TMP_SUFFIX));
		if (leftOver == null) {
			return;
		}
		assertEquals("Temporary files left behind: " + String.join(", ", leftOver), 0, leftOver.length);
	}

	private ImageServlet servlet() throws IOException {
		if (_servlet == null) {
			_servlet = new ImageServlet(_base.toFile(), new AuthService(AuthMode.OFF, "", _base));
			_servlets.add(_servlet);
		}
		return _servlet;
	}

	private FakeResponse get(String type) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", type);
		FakeResponse response = new FakeResponse();
		servlet().doGet(request("/" + ALBUM + "/" + _video.getName(), null, new byte[0], Map.of(), parameters),
			response.response());
		return response;
	}

}
