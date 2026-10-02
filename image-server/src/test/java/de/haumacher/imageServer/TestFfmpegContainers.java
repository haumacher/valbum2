/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.cache.ImageData;
import de.haumacher.imageServer.cache.VideoProbe;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ImageKind;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.UploadResult;
import de.haumacher.imageServer.shared.model.UploadedFile;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;

/**
 * Test case for the videos only FFmpeg reads, issue #192: AVCHD transport streams, AVI, Matroska
 * and WebM.
 *
 * <p>
 * The fixtures are described in <code>src/test/fixtures/video/README.md</code>: one second of four
 * quadrants (red, green, blue, yellow from the top left, upright), written by the FFmpeg program the
 * server bundles.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestFfmpegContainers extends ShareTestCase {

	private static final File FIXTURES = new File("src/test/fixtures/video");

	private static final File JPEG =
		new File("src/test/fixtures/test-album/2005-08-24 Blumen und Fliegen/IMG_0417.JPG");

	private static final String ALBUM = "2024/Camcorder";

	private static final String PATH = "/" + ALBUM + "/";

	private static final long TRANSCODE_TIMEOUT_MS = 120000;

	private static final String WEBM = "screen-2024-05-17_12-37-00.webm";

	private static final List<String> CLIPS = Arrays.asList("clip.mts", "interlaced.m2ts", "clip.avi", "clip.mkv", WEBM);

	/** The MDPM recording time of <code>clip.mts</code>: 2024-05-17 12:34:56 at +02:00. */
	private static final long CLIP_MTS = Instant.parse("2024-05-17T10:34:56Z").toEpochMilli();

	/** The <code>DateUTC</code> of <code>clip.mkv</code>. */
	private static final long CLIP_MKV = Instant.parse("2024-05-17T12:36:00Z").toEpochMilli();

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		Files.createDirectories(_base.resolve(ALBUM));
	}

	/** The zone the test space reads a wall clock in: it names none, so the server's. */
	private static ZoneId zone() {
		return ZoneId.systemDefault();
	}

	public void testABatchOfAJpegAndEachContainerIsStoredAndListed() throws Exception {
		long before = System.currentTimeMillis() - 2000;
		Object[] upload = new Object[2 + 2 * CLIPS.size()];
		upload[0] = "a.jpg";
		upload[1] = bytes(JPEG);
		for (int n = 0; n < CLIPS.size(); n++) {
			upload[2 + 2 * n] = CLIPS.get(n);
			upload[3 + 2 * n] = fixture(CLIPS.get(n));
		}
		UploadResult result = uploadAll(upload);
		assertEquals(Collections.nCopies(1 + CLIPS.size(), ImageServlet.STORED),
			result.getFiles().stream().map(UploadedFile::getStatus).collect(Collectors.toList()));
		assertTrue(result.getRefused().isEmpty());
		long after = System.currentTimeMillis() + 2000;

		AlbumInfo album = album();
		assertEquals(ImageKind.IMAGE, image(album, "a.jpg").getKind());
		assertVideo(image(album, "clip.mts"), CLIP_MTS);
		assertVideo(image(album, "clip.mkv"), CLIP_MKV);
		assertVideo(image(album, "clip.avi"),
			LocalDate.of(2005, 6, 18).atStartOfDay(zone()).toInstant().toEpochMilli());
		assertVideo(image(album, WEBM),
			LocalDateTime.of(2024, 5, 17, 12, 37, 0).atZone(zone()).toInstant().toEpochMilli());

		ImagePart undated = image(album, "interlaced.m2ts");
		assertEquals(ImageKind.VIDEO, undated.getKind());
		assertEquals(96, undated.getWidth());
		assertEquals(64, undated.getHeight());
		assertTrue("Dated by its file: " + undated.getDate(), before <= undated.getDate() && undated.getDate() <= after);
		for (String name : CLIPS) {
			assertEquals(name, "", image(album, name).getCamera());
			assertNull(name, image(album, name).getLocation());
		}
	}

	public void testEveryContainerHasAnUprightPoster() throws Exception {
		for (String name : CLIPS) {
			copy(name);
		}
		for (String name : CLIPS) {
			assertQuadrants(name, picture(get(PATH + name, "tn", SharingFixture.ALICE)), 96, 64);
		}
	}

	public void testEveryContainerHasItsRenditionsAndAnInterlacedOneIsDeinterlaced() throws Exception {
		for (String name : CLIPS) {
			copy(name);
			assertEquals(name, HttpServletResponse.SC_ACCEPTED, get(PATH + name, "video", SharingFixture.ALICE).status());
			assertEquals(name, HttpServletResponse.SC_ACCEPTED,
				get(PATH + name, "teaser", SharingFixture.ALICE).status());
		}
		assertTrue("The transcoder did not finish in time.", servlet().videos().awaitQueue(TRANSCODE_TIMEOUT_MS));

		for (String name : CLIPS) {
			for (String type : Arrays.asList("video", "teaser")) {
				FakeResponse rendition = get(PATH + name, type, SharingFixture.ALICE);
				assertEquals(name + " " + type + ": " + rendition.body(), HttpServletResponse.SC_OK, rendition.status());
				assertEquals("video/mp4", rendition.contentType());
				assertTrue(rendition.bodyBytes().length > 0);
			}
		}
		File folder = _base.resolve(ALBUM).toFile();
		File cache = new File(folder, PreviewCache.CACHE_DIRECTORY_NAME);
		for (String file : cache.list()) {
			assertTrue("Everything generated is named so that a refresh finds it: " + file,
				CacheRefresh.isGenerated(file));
		}

		File interlaced = new File(folder, "interlaced.m2ts");
		assertTrue(VideoProbe.probe(interlaced).isInterlaced());
		assertTrue(VideoRenditions.videoFilter(interlaced, VideoRenditions.PLAYBACK_HEIGHT)
			.startsWith(VideoRenditions.DEINTERLACE + ","));
		VideoProbe played = VideoProbe.probe(VideoRenditions.file(interlaced, VideoRenditions.Kind.PLAYBACK));
		assertFalse("The rendition is progressive.", played.isInterlaced());
		assertEquals(96, played.getWidth());
		assertEquals(64, played.getHeight());

		for (String name : CLIPS) {
			if (!name.equals("interlaced.m2ts")) {
				File clip = new File(folder, name);
				assertFalse(name, VideoProbe.probe(clip).isInterlaced());
				assertEquals(name, VideoRenditions.scaleFilter(VideoRenditions.PLAYBACK_HEIGHT),
					VideoRenditions.videoFilter(clip, VideoRenditions.PLAYBACK_HEIGHT));
			}
			assertTrue("The original is never touched: " + name,
				Arrays.equals(fixture(name), Files.readAllBytes(_base.resolve(ALBUM).resolve(name))));
		}
	}

	public void testTheOriginalIsServedWithItsTypeAndInRanges() throws Exception {
		Map<String, String> types = new LinkedHashMap<>();
		types.put("clip.mts", "video/mp2t");
		types.put("interlaced.m2ts", "video/mp2t");
		types.put("clip.avi", "video/x-msvideo");
		types.put("clip.mkv", "video/x-matroska");
		types.put(WEBM, "video/webm");
		for (Map.Entry<String, String> entry : types.entrySet()) {
			String name = entry.getKey();
			copy(name);
			FakeResponse whole = get(PATH + name, null, SharingFixture.ALICE);
			assertEquals(name, HttpServletResponse.SC_OK, whole.status());
			assertEquals(name, entry.getValue(), whole.contentType());
			assertTrue(name, Arrays.equals(fixture(name), whole.bodyBytes()));

			FakeResponse range = ranged(PATH + name, "bytes=0-99");
			assertEquals(name, HttpServletResponse.SC_PARTIAL_CONTENT, range.status());
			assertEquals(name, entry.getValue(), range.contentType());
			assertEquals(name, "bytes 0-99/" + new File(FIXTURES, name).length(), range.header("Content-Range"));
			assertTrue(name, Arrays.equals(Arrays.copyOf(fixture(name), 100), range.bodyBytes()));
		}
	}

	public void testTheDatesSurviveTheSidecar() throws Exception {
		for (String name : CLIPS) {
			copy(name);
		}
		String before = get(PATH, "json", SharingFixture.ALICE).body();
		FakeResponse stored = put(PATH, before, SharingFixture.ALICE);
		assertEquals(stored.body(), HttpServletResponse.SC_OK, stored.status());
		restartServer();

		assertEquals(before, get(PATH, "json", SharingFixture.ALICE).body());
		assertVideo(image(album(), "clip.mts"), CLIP_MTS);
		assertVideo(image(album(), "clip.mkv"), CLIP_MKV);
	}

	public void testTheProbeReadsWhatEachContainerSays() throws Exception {
		VideoProbe mts = VideoProbe.probe(new File(FIXTURES, "clip.mts"));
		assertEquals(CLIP_MTS, mts.recordingTime(ZoneId.of("UTC")).getTime());
		assertNull(mts.getCreationTime());

		VideoProbe mkv = VideoProbe.probe(new File(FIXTURES, "clip.mkv"));
		assertEquals(CLIP_MKV, mkv.recordingTime(ZoneId.of("Asia/Tokyo")).getTime());

		ZoneId berlin = ZoneId.of("Europe/Berlin");
		VideoProbe avi = VideoProbe.probe(new File(FIXTURES, "clip.avi"));
		assertEquals(Instant.parse("2005-06-17T22:00:00Z").toEpochMilli(), avi.recordingTime(berlin).getTime());

		assertNull(VideoProbe.probe(new File(FIXTURES, WEBM)).recordingTime(berlin));
		assertNull(VideoProbe.probe(new File(FIXTURES, "interlaced.m2ts")).recordingTime(berlin));
		ImageData webm = ImageData.analyze(null, new File(FIXTURES, WEBM), ImageData.Analysis.NONE, berlin);
		assertEquals(Instant.parse("2024-05-17T10:37:00Z").toEpochMilli(), webm.getDate());

		for (String name : CLIPS) {
			VideoProbe probe = VideoProbe.probe(new File(FIXTURES, name));
			assertEquals(name, 0, probe.getRotation());
			assertEquals(name, 96, probe.getWidth());
			assertEquals(name, 64, probe.getHeight());
		}
	}

	public void testTheProbeTurnsAsTheMovieReaderDoes() throws Exception {
		// The display matrix read by FFmpeg and the track matrix read by metadata-extractor name the
		// same turn the same way, so a container of either reader is posted alike.
		File rotated = new File(FIXTURES, "rotated.mov");
		VideoProbe probe = VideoProbe.probe(rotated);
		assertEquals(ImageData.rotation(ImageData.readMetadata(rotated)), probe.getRotation());
		assertEquals(64, probe.getWidth());
		assertEquals(96, probe.getHeight());
	}

	public void testTheMdpmRecordingTime() {
		byte[] uuid = { 0x17, (byte) 0xee, (byte) 0x8c, 0x60, (byte) 0xf8, 0x4d, 0x11, (byte) 0xd9, (byte) 0x8c,
			(byte) 0xd6, 0x08, 0x00, 0x20, 0x0c, (byte) 0x9a, 0x66 };
		// 2001-02-03 00:00:00 at -05:30: zero bytes, so the encoder put emulation prevention in.
		byte[] payload = { 'M', 'D', 'P', 'M', 3, 0x70, 1, 2, 3, 4, 0x18, 0x2b, 0x20, 0x01, 0x02, 0x19, 0x03, 0x00,
			0x00, 0x03, 0x00 };
		byte[] data = concat(new byte[] { 0, 0, 1, 6, 5, 0x20 }, uuid, payload);
		assertEquals(Instant.parse("2001-02-03T05:30:00Z").toEpochMilli(), VideoProbe.mdpm(data).getTime());

		// No time entry, a bad BCD digit, another UUID: nothing.
		assertNull(VideoProbe.mdpm(concat(uuid, new byte[] { 'M', 'D', 'P', 'M', 1, 0x70, 1, 2, 3, 4 })));
		assertNull(VideoProbe.mdpm(concat(uuid, new byte[] { 'M', 'D', 'P', 'M', 2, 0x18, 0, 0x20, 0x24, 0x1A, 0x19,
			0x17, 0x12, 0x34, 0x56 })));
		byte[] other = uuid.clone();
		other[0] = 0x18;
		assertNull(VideoProbe.mdpm(concat(other, new byte[] { 'M', 'D', 'P', 'M', 2, 0x18, 0, 0x20, 0x24, 0x05, 0x19,
			0x17, 0x12, 0x34, 0x56 })));
	}

	public void testTheExtensionsInAnyCase() {
		for (String name : Arrays.asList("a.mts", "a.MTS", "a.m2ts", "a.M2TS", "a.avi", "a.AVI", "a.mkv", "a.MKV",
			"a.webm", "a.WebM")) {
			assertTrue(name, PreviewCache.isVideoName(name));
			assertTrue(name, VideoProbe.handles(name));
			assertTrue(name, PreviewCache.SUPPORTED_EXTENSIONS.contains(name.substring(2).toLowerCase()));
			assertNull(name, ImageServlet.uploadRefusal(name));
		}
		assertFalse(VideoProbe.handles("a.mp4"));
		assertFalse(VideoProbe.handles("a.mov"));
		assertNotNull(ImageServlet.uploadRefusal("a.wmv"));
		assertTrue(ImageServlet.unsupportedFormat("a.wmv").contains("MTS/M2TS, AVI, MKV and WebM"));
		assertEquals("video/mp2t", ImageServlet.videoType("00001.MTS", ImageKind.VIDEO));
		assertEquals("video/webm", ImageServlet.videoType("a.WEBM", ImageKind.VIDEO));
	}

	// --- Helpers. ---

	private static byte[] concat(byte[]... parts) {
		int length = 0;
		for (byte[] part : parts) {
			length += part.length;
		}
		byte[] result = new byte[length];
		int at = 0;
		for (byte[] part : parts) {
			System.arraycopy(part, 0, result, at, part.length);
			at += part.length;
		}
		return result;
	}

	private UploadResult uploadAll(Object... nameAndContents) throws Exception {
		LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
		for (int n = 0; n < nameAndContents.length; n += 2) {
			files.put((String) nameAndContents[n], (byte[]) nameAndContents[n + 1]);
		}
		FakeResponse response = upload(PATH, SharingFixture.ALICE, files);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		return UploadResult.readUploadResult(reader(response.body()));
	}

	private FakeResponse ranged(String pathInfo, String range) throws Exception {
		Map<String, String> headers = new HashMap<>();
		headers.put("Authorization", "Bearer " + SharingFixture.ALICE);
		headers.put("Range", range);
		FakeResponse response = new FakeResponse();
		servlet().doGet(TestImageServletPut.request(pathInfo, null, new byte[0], headers, new HashMap<>()),
			response.response());
		return response;
	}

	private void copy(String name) throws IOException {
		Files.copy(new File(FIXTURES, name).toPath(), _base.resolve(ALBUM).resolve(name));
	}

	private AlbumInfo album() throws Exception {
		FakeResponse response = get(PATH, "json", SharingFixture.ALICE);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		return (AlbumInfo) Resource.readResource(reader(response.body()));
	}

	private static ImagePart image(AlbumInfo album, String name) {
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart && name.equals(((ImagePart) part).getName())) {
				return (ImagePart) part;
			}
		}
		fail("No image '" + name + "' in " + album);
		return null;
	}

	private static void assertVideo(ImagePart image, long date) {
		assertEquals(image.getName(), ImageKind.VIDEO, image.getKind());
		assertEquals(image.getName(), 96, image.getWidth());
		assertEquals(image.getName(), 64, image.getHeight());
		assertEquals(image.getName(), Instant.ofEpochMilli(date), Instant.ofEpochMilli(image.getDate()));
	}

	private static BufferedImage picture(FakeResponse response) throws IOException {
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		BufferedImage picture = ImageIO.read(new ByteArrayInputStream(response.bodyBytes()));
		assertNotNull("A JPEG", picture);
		return picture;
	}

	private static void assertQuadrants(String name, BufferedImage picture, int width, int height) {
		assertEquals(name, width, picture.getWidth());
		assertEquals(name, height, picture.getHeight());
		int w = picture.getWidth();
		int h = picture.getHeight();
		assertColor(name + " top left", picture, 8, 8, 0xFF0000);
		assertColor(name + " top right", picture, w - 8, 8, 0x00FF00);
		assertColor(name + " bottom left", picture, 8, h - 8, 0x0000FF);
		assertColor(name + " bottom right", picture, w - 8, h - 8, 0xFFFF00);
	}

	/** Each channel within a generous tolerance: every codec moves colours a little. */
	private static void assertColor(String what, BufferedImage picture, int x, int y, int expected) {
		int actual = picture.getRGB(x, y) & 0xFFFFFF;
		for (int shift = 0; shift <= 16; shift += 8) {
			int a = (actual >> shift) & 0xFF;
			int e = (expected >> shift) & 0xFF;
			assertTrue(what + ": expected " + Integer.toHexString(expected) + ", got " + Integer.toHexString(actual),
				Math.abs(a - e) < 80);
		}
	}

	private static byte[] fixture(String name) throws IOException {
		return bytes(new File(FIXTURES, name));
	}

	private static byte[] bytes(File file) throws IOException {
		return Files.readAllBytes(file.toPath());
	}
}
