/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.cache.ImageData;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ImageKind;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.MediaUrl;
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
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;

/**
 * Test case for the QuickTime, iTunes and 3GPP videos of issue #189.
 *
 * <p>
 * The fixtures are described in <code>src/test/fixtures/video/README.md</code>: one second of four
 * quadrants (red, green, blue, yellow from the top left, upright), written by the FFmpeg program the
 * server bundles.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestVideoContainers extends ShareTestCase {

	private static final File FIXTURES = new File("src/test/fixtures/video");

	private static final File JPEG =
		new File("src/test/fixtures/test-album/2005-08-24 Blumen und Fliegen/IMG_0417.JPG");

	private static final String ALBUM = "2024/Videos";

	private static final String PATH = "/" + ALBUM + "/";

	private static final long TRANSCODE_TIMEOUT_MS = 120000;

	private static final long CLIP_MOV = Instant.parse("2024-05-17T12:30:00Z").toEpochMilli();

	private static final long CLIP_M4V = Instant.parse("2024-05-17T12:31:00Z").toEpochMilli();

	private static final long CLIP_3GP = Instant.parse("2024-05-17T12:32:00Z").toEpochMilli();

	/** 2024-05-17T14:30:00+0200, the Apple creation date of <code>apple.mov</code>. */
	private static final long APPLE = Instant.parse("2024-05-17T12:30:00Z").toEpochMilli();

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		Files.createDirectories(_base.resolve(ALBUM));
	}

	public void testABatchOfAJpegAndTheThreeContainersIsStoredAndListed() throws Exception {
		UploadResult result = uploadAll("a.jpg", bytes(JPEG), "clip.mov", fixture("clip.mov"), "clip.m4v",
			fixture("clip.m4v"), "clip.3gp", fixture("clip.3gp"), "IMG_0001.MOV", fixture("apple.mov"));
		assertEquals(Collections.nCopies(5, ImageServlet.STORED),
			result.getFiles().stream().map(UploadedFile::getStatus).collect(Collectors.toList()));
		assertTrue(result.getRefused().isEmpty());

		AlbumInfo album = album();
		assertEquals(ImageKind.IMAGE, image(album, "a.jpg").getKind());
		assertVideo(image(album, "clip.mov"), ImageKind.QUICKTIME, 96, 64, CLIP_MOV);
		assertVideo(image(album, "clip.m4v"), ImageKind.VIDEO, 96, 64, CLIP_M4V);
		assertVideo(image(album, "clip.3gp"), ImageKind.VIDEO, 128, 96, CLIP_3GP);

		ImagePart apple = image(album, "IMG_0001.MOV");
		assertVideo(apple, ImageKind.QUICKTIME, 96, 64, APPLE);
		assertNotNull("The ISO 6709 position of the meta box.", apple.getLocation());
		assertEquals(48.125, apple.getLocation().getLatitude(), 1e-6);
		assertEquals(11.57, apple.getLocation().getLongitude(), 1e-6);
		assertEquals("Apple iPhone 15", apple.getCamera());
		assertNull("The other clips say nowhere.", image(album, "clip.mov").getLocation());
	}

	public void testAPortraitMovieIsListedAndPostedUpright() throws Exception {
		copy("rotated.mov");
		assertVideo(image(album(), "rotated.mov"), ImageKind.QUICKTIME, 64, 96,
			Instant.parse("2024-05-17T12:33:00Z").toEpochMilli());

		BufferedImage poster = picture(get(PATH + "rotated.mov", "tn", SharingFixture.ALICE));
		assertEquals(64, poster.getWidth());
		assertEquals(96, poster.getHeight());
		// Turned as FFmpeg turns it when it plays the movie: a quarter anti-clockwise.
		assertColor("top left", poster, 8, 8, 0x00FF00);
		assertColor("top right", poster, 56, 8, 0xFFFF00);
		assertColor("bottom left", poster, 8, 88, 0xFF0000);
		assertColor("bottom right", poster, 56, 88, 0x0000FF);
	}

	public void testEveryContainerHasAnUprightPoster() throws Exception {
		for (String name : Arrays.asList("clip.mov", "clip.m4v", "clip.3gp", "apple.mov")) {
			copy(name);
		}
		for (String name : Arrays.asList("clip.mov", "clip.m4v", "apple.mov")) {
			assertQuadrants(name, picture(get(PATH + name, "tn", SharingFixture.ALICE)), 96, 64);
		}
		assertQuadrants("clip.3gp", picture(get(PATH + "clip.3gp", "tn", SharingFixture.ALICE)), 128, 96);
	}

	public void testEveryContainerHasItsRenditions() throws Exception {
		for (String name : Arrays.asList("clip.mov", "clip.m4v", "clip.3gp")) {
			copy(name);
			assertEquals(name, HttpServletResponse.SC_ACCEPTED, get(PATH + name, "video", SharingFixture.ALICE).status());
			assertEquals(name, HttpServletResponse.SC_ACCEPTED,
				get(PATH + name, "teaser", SharingFixture.ALICE).status());
		}
		assertTrue("The transcoder did not finish in time.", servlet().videos().awaitQueue(TRANSCODE_TIMEOUT_MS));

		for (String name : Arrays.asList("clip.mov", "clip.m4v", "clip.3gp")) {
			for (String type : Arrays.asList("video", "teaser")) {
				FakeResponse rendition = get(PATH + name, type, SharingFixture.ALICE);
				assertEquals(name + " " + type + ": " + rendition.body(), HttpServletResponse.SC_OK, rendition.status());
				assertEquals("video/mp4", rendition.contentType());
				assertTrue(rendition.bodyBytes().length > 0);
			}
		}
		File cache = _base.resolve(ALBUM).resolve(PreviewCache.CACHE_DIRECTORY_NAME).toFile();
		assertTrue(new File(cache, "video-clip.mov.mp4").isFile());
		assertTrue(new File(cache, "teaser-clip.3gp.mp4").isFile());
		for (String file : cache.list()) {
			assertTrue("Everything generated is named so that a refresh finds it: " + file,
				CacheRefresh.isGenerated(file));
		}
		for (String name : Arrays.asList("clip.mov", "clip.m4v", "clip.3gp")) {
			assertTrue("The original is never touched: " + name,
				Arrays.equals(fixture(name), Files.readAllBytes(_base.resolve(ALBUM).resolve(name))));
		}
	}

	public void testTheOriginalIsServedWithItsTypeAndInRanges() throws Exception {
		Map<String, String> types = new LinkedHashMap<>();
		types.put("clip.mov", "video/quicktime");
		types.put("clip.m4v", "video/mp4");
		types.put("clip.3gp", "video/3gpp");
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

	public void testAMovieHasASignedMediaAddress() throws Exception {
		copy("clip.mov");
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", ImageServlet.MEDIA_URL_TYPE);
		parameters.put(ImageServlet.MEDIA_FOR_PARAMETER, "video");
		Map<String, String> headers = new HashMap<>();
		headers.put("Authorization", "Bearer " + SharingFixture.ALICE);
		FakeResponse response = new FakeResponse();
		servlet().doGet(TestImageServletPut.request(PATH + "clip.mov", null, new byte[0], headers, parameters),
			response.response());
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		MediaUrl url = MediaUrl.readMediaUrl(reader(body(response)));
		assertTrue(url.getUrl(), url.getUrl().contains("clip.mov?type=video&media="));
	}

	public void testAnIPhoneMovieKeepsItsAppleDateThroughTheSidecar() throws Exception {
		uploadAll("IMG_0001.MOV", fixture("apple.mov"));
		String before = get(PATH, "json", SharingFixture.ALICE).body();
		assertEquals(APPLE, image(album(), "IMG_0001.MOV").getDate());

		FakeResponse stored = put(PATH, before, SharingFixture.ALICE);
		assertEquals(stored.body(), HttpServletResponse.SC_OK, stored.status());
		restartServer();

		assertEquals(before, get(PATH, "json", SharingFixture.ALICE).body());
		ImagePart again = image(album(), "IMG_0001.MOV");
		assertVideo(again, ImageKind.QUICKTIME, 96, 64, APPLE);
		assertEquals(48.125, again.getLocation().getLatitude(), 1e-6);
	}

	public void testTheMovieHeaderOfTheIPhoneFixtureSaysNothing() throws Exception {
		// The point of the fixture: without the Apple key the movie would be dated by its file.
		ImageData data = ImageData.analyze(null, new File(FIXTURES, "apple.mov"), ImageData.Analysis.NONE,
			ZoneId.of("UTC"));
		assertEquals(APPLE, data.getDate());
		com.drew.metadata.mov.QuickTimeDirectory header = ImageData.readMetadata(new File(FIXTURES, "apple.mov"))
			.getFirstDirectoryOfType(com.drew.metadata.mov.QuickTimeDirectory.class);
		assertTrue("mvhd says 1904: " + header.getDate(com.drew.metadata.mov.QuickTimeDirectory.TAG_CREATION_TIME),
			header.getDate(com.drew.metadata.mov.QuickTimeDirectory.TAG_CREATION_TIME).getTime()
				< ImageData.EARLIEST_RECORDING);
	}

	public void testTheAppleCreationDate() {
		ZoneId berlin = ZoneId.of("Europe/Berlin");
		long expected = Instant.parse("2024-05-17T12:30:00Z").toEpochMilli();
		assertEquals(expected, ImageData.appleCreationDate("2024-05-17T14:30:00+0200", berlin).getTime());
		assertEquals(expected, ImageData.appleCreationDate("2024-05-17T14:30:00+02:00", null).getTime());
		assertEquals(expected, ImageData.appleCreationDate("2024-05-17T09:30:00-0300", null).getTime());
		assertEquals(expected, ImageData.appleCreationDate("2024-05-17T12:30:00Z", null).getTime());
		assertEquals(expected + 250, ImageData.appleCreationDate("2024-05-17T14:30:00.25+0200", null).getTime());
		// A wall clock without its offset is read in the zone of the space, as a photo's (issue #183).
		assertEquals(expected, ImageData.appleCreationDate("2024-05-17T14:30:00", berlin).getTime());
		assertEquals(expected, ImageData.appleCreationDate(" 2024-05-17T14:30:00+0200 ", null).getTime());
		assertNull(ImageData.appleCreationDate(null, berlin));
		assertNull(ImageData.appleCreationDate("", berlin));
		assertNull(ImageData.appleCreationDate("yesterday", berlin));
		assertNull(ImageData.appleCreationDate("2024-13-17T14:30:00+0200", berlin));
	}

	public void testTheContentTypeOfAVideo() {
		assertEquals("video/quicktime", ImageServlet.videoType("IMG_0001.MOV", ImageKind.QUICKTIME));
		assertEquals("video/quicktime", ImageServlet.videoType("odd.mp4", ImageKind.QUICKTIME));
		assertEquals("video/mp4", ImageServlet.videoType("clip.mp4", ImageKind.VIDEO));
		assertEquals("video/mp4", ImageServlet.videoType("clip.M4V", ImageKind.VIDEO));
		assertEquals("video/3gpp", ImageServlet.videoType("clip.3GP", ImageKind.VIDEO));
	}

	public void testTheExtensionsInAnyCase() {
		for (String name : Arrays.asList("a.mov", "a.MOV", "a.m4v", "a.M4V", "a.3gp", "a.3GP", "a.mp4")) {
			assertTrue(name, PreviewCache.isVideoName(name));
			assertTrue(name, PreviewCache.SUPPORTED_EXTENSIONS.contains(name.substring(2).toLowerCase()));
			assertNull(name, ImageServlet.uploadRefusal(name));
		}
		assertFalse(PreviewCache.isVideoName("a.jpg"));
		assertFalse(PreviewCache.isVideoName("mov"));
		assertNotNull(ImageServlet.uploadRefusal("a.wmv"));
	}

	// --- Helpers. ---

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

	private static void assertVideo(ImagePart image, ImageKind kind, int width, int height, long date) {
		assertEquals(image.getName(), kind, image.getKind());
		assertEquals(image.getName(), width, image.getWidth());
		assertEquals(image.getName(), height, image.getHeight());
		assertEquals(image.getName(), date, image.getDate());
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

	/** Each channel within a generous tolerance: H.263, H.264 and JPEG all move colours a little. */
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
