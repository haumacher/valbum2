/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import static de.haumacher.imageServer.TestImageServletPut.request;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.mp4.media.Mp4SoundDirectory;
import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Test case for the transcoded video renditions of issue #74.
 *
 * <p>
 * The servlet is driven headlessly on a temporary base folder with the request and response fakes
 * of {@link TestImageServletPut}. A transcode is never waited for by a request, so every test that
 * wants the result asks the queue to run empty first, see
 * {@link VideoRenditions#awaitQueue(long)}.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestVideoRenditions extends TestCase {

	/** How long a test waits for the single-thread transcoder. */
	private static final long TRANSCODE_TIMEOUT_MS = 120000;

	private static final File VIDEO_FIXTURE =
		new File("src/test/fixtures/test-album/2005-08-24 Blumen und Fliegen/MVI_0450.mp4");

	private static final String ALBUM = "2005-08-24 Trip";

	private Path _base;

	private File _album;

	private final List<ImageServlet> _servlets = new ArrayList<>();

	private ImageServlet _servlet;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-video-rendition");
		_album = new File(_base.toFile(), ALBUM);
		assertTrue(_album.mkdirs());
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

	/**
	 * The first request queues the transcode and says so; the second one plays it.
	 */
	public void testPendingThenPlayable() throws Exception {
		File video = copyFixture("MVI_0450.mp4");
		byte[] before = Files.readAllBytes(video.toPath());

		FakeResponse pending = get(video, "video");
		assertEquals(HttpServletResponse.SC_ACCEPTED, pending.status());
		assertTrue(pending.body(), pending.body().contains(ImageServlet.RENDITION_PENDING));
		assertEquals("A caller must be told when to come back.", "10", pending.header("Retry-After"));
		assertEquals("no-store", pending.header("Cache-Control"));

		awaitTranscodes();

		File rendition = VideoRenditions.file(video, VideoRenditions.Kind.PLAYBACK);
		assertEquals("The rendition belongs beside the poster frame.",
			"video-MVI_0450.mp4", rendition.getName());
		assertTrue("No rendition was made.", rendition.exists());
		assertTrue("Empty rendition.", rendition.length() > 0);

		FakeResponse served = get(video, "video");
		assertEquals(HttpServletResponse.SC_OK, served.status());
		assertEquals("video/mp4", served.contentType());
		assertEquals("bytes", served.header("Accept-Ranges"));
		assertEquals(rendition.length(), served.bodyBytes().length);

		// Playing must be able to start with the first bytes.
		List<String> boxes = topLevelBoxes(rendition);
		assertTrue("Expected the index in front, got: " + boxes,
			boxes.indexOf("moov") >= 0 && boxes.indexOf("moov") < boxes.indexOf("mdat"));

		assertTrue("The original must not be modified.",
			Arrays.equals(before, Files.readAllBytes(video.toPath())));
		assertNoTempFiles();
	}

	/** A rendition is served over the same byte-range path as every other file. */
	public void testRangeRequest() throws Exception {
		File video = copyFixture("MVI_0450.mp4");
		get(video, "video");
		awaitTranscodes();

		Map<String, String> headers = new HashMap<>();
		headers.put("Range", "bytes=0-99");
		FakeResponse response = get(video, "video", headers);

		assertEquals(HttpServletResponse.SC_PARTIAL_CONTENT, response.status());
		File rendition = VideoRenditions.file(video, VideoRenditions.Kind.PLAYBACK);
		assertEquals("bytes 0-99/" + rendition.length(), response.header("Content-Range"));
		assertEquals(100, response.bodyBytes().length);
	}

	/**
	 * The teaser is a few silent seconds, while the playback rendition keeps the sound.
	 */
	public void testTeaserIsShortAndSilent() throws Exception {
		File video = new File(_album, "loud.mp4");
		recordVideoWithAudio(video, 8);

		get(video, "video");
		get(video, "teaser");
		awaitTranscodes();

		File playback = VideoRenditions.file(video, VideoRenditions.Kind.PLAYBACK);
		File teaser = VideoRenditions.file(video, VideoRenditions.Kind.TEASER);
		assertEquals("teaser-loud.mp4", teaser.getName());
		assertTrue("No teaser was made.", teaser.exists());

		assertTrue("The playback rendition must keep the sound.", hasAudio(playback));
		assertFalse("The teaser must be silent.", hasAudio(teaser));
		assertTrue("The teaser must be short, was " + duration(teaser) + "s.",
			duration(teaser) <= 3.5);
		assertTrue("The teaser must be small, was " + teaser.length() + " bytes.",
			teaser.length() < 500 * 1024);

		List<String> boxes = topLevelBoxes(teaser);
		assertTrue("Expected the index in front, got: " + boxes,
			boxes.indexOf("moov") >= 0 && boxes.indexOf("moov") < boxes.indexOf("mdat"));

		FakeResponse served = get(video, "teaser");
		assertEquals(HttpServletResponse.SC_OK, served.status());
		assertEquals("video/mp4", served.contentType());
		assertNoTempFiles();
	}

	/**
	 * A file FFmpeg cannot read is answered <code>202</code> once and <code>500</code> afterwards:
	 * the failure is remembered, so the machine does not try it again for every request.
	 */
	public void testUnreadableVideoFails() throws Exception {
		File broken = new File(_album, "broken.mp4");
		Files.write(broken.toPath(), "This is not a video.".getBytes(StandardCharsets.UTF_8));

		FakeResponse pending = get(broken, "video");
		assertEquals(HttpServletResponse.SC_ACCEPTED, pending.status());

		awaitTranscodes();

		FakeResponse failed = get(broken, "video");
		assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, failed.status());
		assertTrue(failed.body(), failed.body().contains(ImageServlet.RENDITION_FAILED));
		assertEquals("no-store", failed.header("Cache-Control"));

		assertFalse("A failure must not leave a rendition behind.",
			VideoRenditions.file(broken, VideoRenditions.Kind.PLAYBACK).exists());
		assertNoTempFiles();
	}

	/** A rendition is looking, so it is refused exactly as the thumbnail is. */
	public void testAnonymousCallerRefused() throws Exception {
		File video = copyFixture("MVI_0450.mp4");

		_servlet = new ImageServlet(_base.toFile(), new AuthService(AuthMode.ALL, _base));
		_servlets.add(_servlet);

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, get(video, "video").status());
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, get(video, "teaser").status());
		assertEquals("Nothing may be transcoded for a caller that is refused.",
			0, renditionCount());
	}

	/** Only a video has renditions. */
	public void testPhotoHasNoRendition() throws Exception {
		File photo = new File(_album, "photo.jpg");
		javax.imageio.ImageIO.write(
			new java.awt.image.BufferedImage(40, 30, java.awt.image.BufferedImage.TYPE_3BYTE_BGR), "jpg", photo);

		assertEquals(HttpServletResponse.SC_NOT_FOUND, get(photo, "video").status());
		assertEquals(HttpServletResponse.SC_NOT_FOUND, get(photo, "teaser").status());
	}

	private int renditionCount() {
		File cacheDir = new File(_album, PreviewCache.CACHE_DIRECTORY_NAME);
		String[] names = cacheDir.list((dir, name) -> name.startsWith("video-") || name.startsWith("teaser-"));
		return names == null ? 0 : names.length;
	}

	private void assertNoTempFiles() {
		File cacheDir = new File(_album, PreviewCache.CACHE_DIRECTORY_NAME);
		String[] leftOver = cacheDir.list((dir, name) -> name.endsWith(PreviewCache.TMP_SUFFIX));
		if (leftOver == null) {
			return;
		}
		assertEquals("Temporary files left behind: " + String.join(", ", leftOver), 0, leftOver.length);
	}

	private void awaitTranscodes() throws Exception {
		assertTrue("The transcoder did not finish in time.",
			servlet().videos().awaitQueue(TRANSCODE_TIMEOUT_MS));
	}

	private File copyFixture(String name) throws IOException {
		assertTrue("Missing fixture: " + VIDEO_FIXTURE.getAbsolutePath(), VIDEO_FIXTURE.exists());
		File copy = new File(_album, name);
		Files.copy(VIDEO_FIXTURE.toPath(), copy.toPath());
		return copy;
	}

	/**
	 * Records a tiny video with an audio track, using the same bundled FFmpeg the server uses.
	 */
	private static void recordVideoWithAudio(File target, int seconds) throws Exception {
		List<String> command = new ArrayList<>(Arrays.asList(VideoRenditions.executable(),
			"-hide_banner", "-nostdin", "-y", "-loglevel", "error",
			"-f", "lavfi", "-i", "testsrc=size=320x240:rate=10:duration=" + seconds,
			"-f", "lavfi", "-i", "sine=frequency=440:duration=" + seconds,
			"-c:v", VideoRenditions.encoder(), "-b:v", "300k", "-pix_fmt", "yuv420p",
			"-c:a", "aac", "-b:a", "64k", "-shortest", target.getAbsolutePath()));
		ProcessBuilder builder = new ProcessBuilder(command);
		builder.redirectErrorStream(true);
		Process process = builder.start();
		process.getInputStream().readAllBytes();
		assertEquals("Cannot record a test video.", 0, process.waitFor());
		assertTrue("No test video was recorded.", target.length() > 0);
	}

	private static boolean hasAudio(File video) throws Exception {
		Metadata metadata = ImageMetadataReader.readMetadata(video);
		return metadata.getFirstDirectoryOfType(Mp4SoundDirectory.class) != null;
	}

	private static double duration(File video) throws Exception {
		try (org.bytedeco.javacv.FFmpegFrameGrabber grabber =
			new org.bytedeco.javacv.FFmpegFrameGrabber(video)) {
			grabber.start();
			return grabber.getLengthInTime() / 1000000.0;
		}
	}

	/** The names of the top-level boxes of the given MP4, in file order. */
	private static List<String> topLevelBoxes(File file) throws IOException {
		byte[] contents = Files.readAllBytes(file.toPath());
		ByteBuffer buffer = ByteBuffer.wrap(contents);
		List<String> boxes = new ArrayList<>();
		int offset = 0;
		while (offset + 8 <= contents.length) {
			buffer.position(offset);
			long size = buffer.getInt() & 0xFFFFFFFFL;
			byte[] type = new byte[4];
			buffer.get(type);
			boxes.add(new String(type, StandardCharsets.US_ASCII));
			if (size == 1) {
				size = buffer.getLong();
			} else if (size == 0) {
				break;
			}
			if (size < 8) {
				break;
			}
			offset += size;
		}
		return boxes;
	}

	private ImageServlet servlet() throws IOException {
		if (_servlet == null) {
			_servlet = new ImageServlet(_base.toFile(), new AuthService(AuthMode.OFF, _base));
			_servlets.add(_servlet);
		}
		return _servlet;
	}

	private FakeResponse get(File file, String type) throws Exception {
		return get(file, type, Map.of());
	}

	private FakeResponse get(File file, String type, Map<String, String> headers) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", type);
		FakeResponse response = new FakeResponse();
		String pathInfo = "/" + ALBUM + "/" + file.getName();
		servlet().doGet(request(pathInfo, null, new byte[0], headers, parameters), response.response());
		return response;
	}

}
