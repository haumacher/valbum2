/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.heif.TestHeifDecoder;
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
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;

/**
 * Test case for WebP and GIF pictures, see issue #190.
 *
 * <p>
 * The fixtures are described in <code>src/test/fixtures/webp-gif/README.md</code>: four quadrants
 * (red, green, blue, yellow from the top left) once upright; <code>exif.webp</code> stored on its
 * side with an EXIF chunk saying orientation 6, a date, a camera and a position.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestWebpGif extends ShareTestCase {

	static final File FIXTURES = new File("src/test/fixtures/webp-gif");

	private static final File JPEG =
		new File("src/test/fixtures/test-album/2005-08-24 Blumen und Fliegen/IMG_0417.JPG");

	private static final String ALBUM = "2024/Pictures";

	private static final String PATH = "/" + ALBUM + "/";

	/** 2024:05:17 14:30:00 at +02:00, the DateTimeOriginal of <code>exif.webp</code>. */
	private static final long TAKEN = Instant.parse("2024-05-17T12:30:00Z").toEpochMilli();

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		Files.createDirectories(_base.resolve(ALBUM));
	}

	public void testABatchOfAJpegAWebpAndAGifIsStoredAndListed() throws Exception {
		UploadResult result = uploadAll("a.jpg", bytes(JPEG), "x.webp", fixture("exif.webp"), "y.gif",
			fixture("still.gif"), "Z.WEBP", fixture("alpha.webp"), "IMG_20230102_101112.GIF", fixture("animated.gif"));
		assertEquals(Collections.nCopies(5, ImageServlet.STORED),
			result.getFiles().stream().map(UploadedFile::getStatus).collect(Collectors.toList()));
		assertTrue(result.getRefused().isEmpty());

		AlbumInfo album = album();
		assertNotNull(image(album, "a.jpg"));

		// Upright: the EXIF orientation 6 of the WebP applied, its date, camera and position read.
		ImagePart webp = image(album, "x.webp");
		assertSize(webp, 96, 64);
		assertEquals(TAKEN, webp.getDate());
		assertEquals("Fixture WebP", webp.getCamera());
		assertNotNull(webp.getLocation());
		assertEquals(48.125, webp.getLocation().getLatitude(), 1e-6);
		assertEquals(11.57, webp.getLocation().getLongitude(), 1e-6);

		assertSize(image(album, "Z.WEBP"), 96, 64);
		assertEquals("", nonNull(image(album, "Z.WEBP").getCamera()));

		// A GIF says nothing about itself: no camera, no position, dated by its name or its file.
		ImagePart gif = image(album, "y.gif");
		assertSize(gif, 96, 64);
		assertEquals("", nonNull(gif.getCamera()));
		assertNull(gif.getLocation());
		assertEquals(_base.resolve(ALBUM).resolve("y.gif").toFile().lastModified(), gif.getDate());

		ImagePart named = image(album, "IMG_20230102_101112.GIF");
		assertSize(named, 96, 64);
		assertEquals(LocalDateTime.of(2023, 1, 2, 10, 11, 12).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
			named.getDate());
	}

	public void testThePreviewIsAnUprightJpegOfTheFirstFrame() throws Exception {
		for (String name : Arrays.asList("lossy.webp", "exif.webp", "animated.webp", "still.gif", "animated.gif")) {
			copy(name);
		}
		assertPicture(get(PATH + "lossy.webp", "tn", SharingFixture.ALICE), 96, 64);
		// Stored on its side, shown upright.
		assertPicture(get(PATH + "exif.webp", "tn", SharingFixture.ALICE), 96, 64);
		// The first frame, never the magenta second one.
		assertPicture(get(PATH + "animated.webp", "tn", SharingFixture.ALICE), 96, 64);
		assertPicture(get(PATH + "still.gif", "tn", SharingFixture.ALICE), 96, 64);
		assertPicture(get(PATH + "animated.gif", "tn", SharingFixture.ALICE), 96, 64);

		File cache = _base.resolve(ALBUM).resolve(PreviewCache.CACHE_DIRECTORY_NAME).toFile();
		for (String file : cache.list()) {
			assertTrue("Everything generated is named so that a refresh finds it: " + file,
				CacheRefresh.isGenerated(file));
			assertTrue("A JPEG preview: " + file, file.endsWith(".jpg"));
		}
	}

	/**
	 * A GIF whose first frame is smaller than its logical screen is shown on that screen, the frame
	 * at its offset and the rest white, so that the listed size and the preview agree (issue #207);
	 * an animated WebP whose first frame is smaller than its canvas likewise.
	 */
	public void testAFirstFrameSmallerThanItsCanvasIsShownOnTheCanvas() throws Exception {
		uploadAll("offset.gif", fixture("offset.gif"), "offset.webp", fixture("offset.webp"));
		for (String name : Arrays.asList("offset.gif", "offset.webp")) {
			assertSize(image(album(), name), 96, 64);

			FakeResponse response = get(PATH + name, "tn", SharingFixture.ALICE);
			assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
			assertJpeg(response.bodyBytes());
			BufferedImage picture = ImageIO.read(new ByteArrayInputStream(response.bodyBytes()));
			assertEquals(name, 96, picture.getWidth());
			assertEquals(name, 64, picture.getHeight());
			assertOffsetGif(picture, 1);
		}
	}

	/**
	 * The pixels of the canvas of <code>offset.gif</code> and <code>offset.webp</code>, drawn at every <code>sampling</code>-th pixel:
	 * the frame (32, 16, 48 × 32) with its red, green and blue quadrants, white around it — not the
	 * magenta of its background colour — and in its transparent corner.
	 */
	public static void assertOffsetGif(BufferedImage picture, int sampling) {
		assertWhite(picture, 5 / sampling, 5 / sampling);
		assertWhite(picture, 90 / sampling, 60 / sampling);
		assertWhite(picture, 20 / sampling, 40 / sampling);
		assertWhite(picture, 88 / sampling, 24 / sampling);
		TestHeifDecoder.assertColour(TestHeifDecoder.RED, picture, 40 / sampling, 20 / sampling);
		TestHeifDecoder.assertColour(0x00FF00, picture, 72 / sampling, 20 / sampling);
		TestHeifDecoder.assertColour(0x0000FF, picture, 40 / sampling, 44 / sampling);
		// The transparent quadrant of the frame.
		assertWhite(picture, 72 / sampling, 44 / sampling);
	}

	public static void assertWhite(BufferedImage picture, int x, int y) {
		int rgb = picture.getRGB(x, y) & 0xFFFFFF;
		assertTrue("White at " + x + "," + y + ", not " + Integer.toHexString(rgb),
			((rgb >> 16) & 0xFF) > 230 && ((rgb >> 8) & 0xFF) > 230 && (rgb & 0xFF) > 230);
	}

	public void testAPictureTooLargeForTheServerIsRefusedWithAReason() throws Exception {
		copy("lossless-4mp.webp");
		PictureReader.setBudget(8 << 20);
		try {
			FakeResponse response = get(PATH + "lossless-4mp.webp", "tn", SharingFixture.ALICE);
			assertEquals(response.body(), HttpServletResponse.SC_INTERNAL_SERVER_ERROR, response.status());
			de.haumacher.imageServer.shared.model.ErrorInfo error =
				(de.haumacher.imageServer.shared.model.ErrorInfo) Resource.readResource(reader(response.body()));
			assertTrue(error.getMessage(), error.getMessage().contains("2400 × 1600"));
			assertTrue(error.getMessage(), error.getMessage().contains("-Xmx"));
		} finally {
			PictureReader.resetBudget();
		}
		// With the memory it needs, it is shown.
		assertEquals(HttpServletResponse.SC_OK, get(PATH + "lossless-4mp.webp", "tn", SharingFixture.ALICE).status());
	}

	public void testATransparentPixelIsShownOnWhite() throws Exception {
		copy("alpha.webp");
		FakeResponse response = get(PATH + "alpha.webp", "tn", SharingFixture.ALICE);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		assertJpeg(response.bodyBytes());
		BufferedImage picture = ImageIO.read(new ByteArrayInputStream(response.bodyBytes()));
		assertEquals(96, picture.getWidth());
		assertEquals(64, picture.getHeight());
		TestHeifDecoder.assertColour(TestHeifDecoder.RED, picture, 2, 2);
		TestHeifDecoder.assertColour(0x00FF00, picture, 93, 2);
		TestHeifDecoder.assertColour(0x0000FF, picture, 2, 61);
		// The transparent quadrant, whose colour channels say yellow, is white.
		int rgb = picture.getRGB(93, 61) & 0xFFFFFF;
		assertTrue("White, not " + Integer.toHexString(rgb),
			((rgb >> 16) & 0xFF) > 240 && ((rgb >> 8) & 0xFF) > 240 && (rgb & 0xFF) > 240);
	}

	public void testTheOriginalIsTheUploadedBytesWithItsType() throws Exception {
		byte[] webp = fixture("animated.webp");
		byte[] gif = fixture("animated.gif");
		uploadAll("anim.WebP", webp, "anim.gif", gif);

		assertOriginal("anim.WebP", webp, "image/webp");
		assertOriginal("anim.gif", gif, "image/gif");

		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", ImageServlet.ZIP_ACTION);
		FakeResponse zip = post(PATH, "{\"target\":\"\",\"names\":[{\"name\":\"anim.WebP\"},{\"name\":\"anim.gif\"}]}",
			SharingFixture.ALICE, parameters);
		assertEquals(zip.body(), HttpServletResponse.SC_OK, zip.status());
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip.bodyBytes()))) {
			ZipEntry entry = in.getNextEntry();
			assertEquals("anim.WebP", entry.getName());
			assertTrue("The animation is kept.", Arrays.equals(webp, in.readAllBytes()));
			entry = in.getNextEntry();
			assertEquals("anim.gif", entry.getName());
			assertTrue("The animation is kept.", Arrays.equals(gif, in.readAllBytes()));
		}
	}

	public void testTheSidecarRoundTripsAnAlbumHoldingAWebpAndAGif() throws Exception {
		uploadAll("x.webp", fixture("exif.webp"), "y.gif", fixture("still.gif"));
		String before = get(PATH, "json", SharingFixture.ALICE).body();

		FakeResponse stored = put(PATH, before, SharingFixture.ALICE);
		assertEquals(stored.body(), HttpServletResponse.SC_OK, stored.status());
		restartServer();

		assertEquals(before, get(PATH, "json", SharingFixture.ALICE).body());
		assertSize(image(album(), "x.webp"), 96, 64);
		assertSize(image(album(), "y.gif"), 96, 64);
	}

	public void testAReanalysisFillsTheCameraOfAWebp() throws Exception {
		uploadAll("x.webp", fixture("exif.webp"));
		String json = get(PATH, "json", SharingFixture.ALICE).body();
		String bare = json.replaceFirst(",?\\s*\"camera\":\\s*\"Fixture WebP\"", "");
		assertFalse(bare.equals(json));
		FakeResponse put = put(PATH, bare, SharingFixture.ALICE);
		assertEquals(put.body(), HttpServletResponse.SC_OK, put.status());
		assertEquals("", nonNull(image(album(), "x.webp").getCamera()));

		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "reanalyze");
		FakeResponse reanalyzed = post(PATH, "", SharingFixture.ALICE, parameters);
		assertEquals(reanalyzed.body(), HttpServletResponse.SC_OK, reanalyzed.status());
		assertEquals("Fixture WebP", image(album(), "x.webp").getCamera());
	}

	public void testAMovedWebpAndAPurgedGif() throws Exception {
		Files.createDirectories(_base.resolve("2024/Other"));
		copy("exif.webp");
		copy("still.gif");
		assertPicture(get(PATH + "exif.webp", "tn", SharingFixture.ALICE), 96, 64);
		assertPicture(get(PATH + "still.gif", "tn", SharingFixture.ALICE), 96, 64);

		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "move");
		FakeResponse moved = post(PATH, "{\"target\":\"2024/Other\",\"names\":[{\"name\":\"exif.webp\"}]}",
			SharingFixture.ALICE, parameters);
		assertEquals(moved.body(), HttpServletResponse.SC_OK, moved.status());
		assertTrue(moved.body(), Files.exists(_base.resolve("2024/Other/exif.webp")));
		assertPicture(get("/2024/Other/exif.webp", "tn", SharingFixture.ALICE), 96, 64);

		String json = get(PATH, "json", SharingFixture.ALICE).body();
		String trashed = json.replaceFirst("(\"name\":\\s*\"still.gif\"[^}]*\"rating\":\\s*)-?\\d+", "$1-2");
		if (trashed.equals(json)) {
			trashed = json.replaceFirst("\"name\":\\s*\"still.gif\"", "\"name\": \"still.gif\", \"rating\": -2");
		}
		FakeResponse put = put(PATH, trashed, SharingFixture.ALICE);
		assertEquals(put.body(), HttpServletResponse.SC_OK, put.status());

		parameters.put("action", "purge");
		FakeResponse purge = post(PATH, "", SharingFixture.ALICE, parameters);
		assertEquals(purge.body(), HttpServletResponse.SC_OK, purge.status());
		assertFalse(Files.exists(_base.resolve(ALBUM).resolve("still.gif")));
		Path cache = _base.resolve(ALBUM).resolve(PreviewCache.CACHE_DIRECTORY_NAME);
		try (var listing = Files.list(cache)) {
			listing.forEach(file -> assertFalse("Left behind: " + file,
				file.getFileName().toString().contains("still.gif")));
		}
	}

	// --- Helpers. ---

	private void assertOriginal(String name, byte[] uploaded, String type) throws Exception {
		FakeResponse original = get(PATH + name, null, SharingFixture.ALICE);
		assertEquals(HttpServletResponse.SC_OK, original.status());
		assertEquals(type, original.contentType());
		assertTrue(Arrays.equals(uploaded, original.bodyBytes()));
		assertTrue("The original is never touched.",
			Arrays.equals(uploaded, Files.readAllBytes(_base.resolve(ALBUM).resolve(name))));
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

	private static void assertSize(ImagePart image, int width, int height) {
		assertEquals(image.getName(), width, image.getWidth());
		assertEquals(image.getName(), height, image.getHeight());
		assertEquals(image.getName(), ImageKind.IMAGE, image.getKind());
	}

	private static String nonNull(String value) {
		return value == null ? "" : value;
	}

	private static void assertJpeg(byte[] data) {
		assertTrue("A JPEG", data.length > 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8);
	}

	private static void assertPicture(FakeResponse response, int width, int height) throws IOException {
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		assertJpeg(response.bodyBytes());
		BufferedImage picture = ImageIO.read(new ByteArrayInputStream(response.bodyBytes()));
		assertNotNull("A JPEG", picture);
		assertEquals(width, picture.getWidth());
		assertEquals(height, picture.getHeight());
		TestHeifDecoder.assertQuadrants(picture);
	}

	private static byte[] fixture(String name) throws IOException {
		return bytes(new File(FIXTURES, name));
	}

	private static byte[] bytes(File file) throws IOException {
		return Files.readAllBytes(file.toPath());
	}
}
