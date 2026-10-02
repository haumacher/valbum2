/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.heif.HeifDecoder;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.RefusedFile;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;

/**
 * Test case for HEIC/HEIF photographs, see issue #186.
 *
 * <p>
 * The fixtures are described in <code>src/test/fixtures/heic/README.md</code>: four quadrants
 * (red, green, blue, yellow from the top left) once upright, an EXIF block with a date, a camera
 * and a position.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestHeic extends ShareTestCase {

	private static final File FIXTURES = new File("src/test/fixtures/heic");

	private static final File JPEG =
		new File("src/test/fixtures/test-album/2005-08-24 Blumen und Fliegen/IMG_0417.JPG");

	private static final String ALBUM = "2024/Heic";

	private static final String PATH = "/" + ALBUM + "/";

	/** 2024:05:17 14:30:00 at +02:00, the fixtures' DateTimeOriginal. */
	private static final long TAKEN = Instant.parse("2024-05-17T12:30:00Z").toEpochMilli();

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		Files.createDirectories(_base.resolve(ALBUM));
	}

	@Override
	protected void tearDown() throws Exception {
		HeifDecoder.setProgramLocator(null);
		super.tearDown();
	}

	public void testABatchOfAJpegAndHeicsIsStoredAndListed() throws Exception {
		UploadResult result = uploadAll("IMG_0417.JPG", bytes(JPEG), "single.heic", fixture("single.heic"),
			"grid.HEIC", fixture("grid.heic"), "rotated.heif", fixture("rotated.heic"));
		assertEquals(Arrays.asList(ImageServlet.STORED, ImageServlet.STORED, ImageServlet.STORED, ImageServlet.STORED),
			result.getFiles().stream().map(UploadedFile::getStatus).collect(Collectors.toList()));
		assertTrue(result.getRefused().isEmpty());

		AlbumInfo album = album();
		assertNotNull(image(album, "IMG_0417.JPG"));
		assertDescribed(image(album, "single.heic"), 96, 64);
		assertDescribed(image(album, "grid.HEIC"), 180, 120);
		// Upright: the container's quarter turn applied once, the EXIF orientation 8 not on top.
		assertDescribed(image(album, "rotated.heif"), 120, 180);
	}

	public void testThePreviewIsAnUprightJpeg() throws Exception {
		for (String name : Arrays.asList("single.heic", "grid.heic", "rotated.heic", "mirrored.heic")) {
			copy(name);
		}
		assertPicture(get(PATH + "single.heic", "tn", SharingFixture.ALICE), 96, 64);
		assertPicture(get(PATH + "grid.heic", "tn", SharingFixture.ALICE), 180, 120);
		assertPicture(get(PATH + "rotated.heic", "tn", SharingFixture.ALICE), 120, 180);
		assertPicture(get(PATH + "mirrored.heic", "tn", SharingFixture.ALICE), 96, 64);

		File cache = _base.resolve(ALBUM).resolve(PreviewCache.CACHE_DIRECTORY_NAME).toFile();
		for (String file : cache.list()) {
			assertTrue("Everything generated is named so that a refresh finds it: " + file,
				CacheRefresh.isGenerated(file));
		}
	}

	public void testTheDisplayRenditionIsAnUprightJpegForWhoMayDownload() throws Exception {
		copy("rotated.heic");
		FakeResponse response = get(PATH + "rotated.heic", ImageServlet.DISPLAY_TYPE, SharingFixture.ALICE);
		assertPicture(response, 120, 180);
		File display = PreviewCache.displayFile(_base.resolve(ALBUM).resolve("rotated.heic").toFile());
		assertTrue(display.isFile());
		assertTrue(CacheRefresh.isGenerated(display.getName()));

		// Asked again, it is served from the cache.
		long made = display.lastModified();
		assertPicture(get(PATH + "rotated.heic", ImageServlet.DISPLAY_TYPE, SharingFixture.ALICE), 120, 180);
		assertEquals(made, display.lastModified());

		// A refresh throws it away.
		assertTrue(CacheRefresh.refresh(_base.resolve(ALBUM).toFile()) >= 1);
		assertFalse(display.exists());
	}

	public void testTheDisplayRenditionAsksTheDownloadRight() throws Exception {
		Path zoo = _base.resolve(SharingFixture.ZOO);
		Files.copy(new File(FIXTURES, "single.heic").toPath(), zoo.resolve("single.heic"));
		String viewer = zooToken(Rights.VIEW);
		FakeResponse refused = get("/single.heic", ImageServlet.DISPLAY_TYPE, viewer);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, refused.status());
		assertEquals(AuthService.DOWNLOAD_REFUSED, errorMessage(refused));
		// What the viewer is shown instead, as for a JPEG (issue #95).
		assertPicture(get("/single.heic", "tn", viewer), 96, 64);

		String downloader = zooToken(Rights.VIEW, Rights.DOWNLOAD);
		assertPicture(get("/single.heic", ImageServlet.DISPLAY_TYPE, downloader), 96, 64);
	}

	public void testAJpegHasNoDisplayRendition() throws Exception {
		FakeResponse response =
			get("/" + SharingFixture.ZOO + "/public.jpg", ImageServlet.DISPLAY_TYPE, SharingFixture.ALICE);
		assertEquals(HttpServletResponse.SC_NOT_FOUND, response.status());
		assertEquals(ImageServlet.DISPLAY_NOT_NEEDED, errorMessage(response));
	}

	public void testTheOriginalIsTheUploadedBytes() throws Exception {
		byte[] uploaded = fixture("grid.heic");
		uploadAll("grid.heic", uploaded);

		FakeResponse original = get(PATH + "grid.heic", null, SharingFixture.ALICE);
		assertEquals(HttpServletResponse.SC_OK, original.status());
		assertEquals("image/heic", original.contentType());
		assertTrue(Arrays.equals(uploaded, original.bodyBytes()));
		assertTrue("The original is never touched.",
			Arrays.equals(uploaded, Files.readAllBytes(_base.resolve(ALBUM).resolve("grid.heic"))));

		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", ImageServlet.ZIP_ACTION);
		FakeResponse zip = post(PATH, "{\"target\":\"\",\"names\":[{\"name\":\"grid.heic\"}]}", SharingFixture.ALICE,
			parameters);
		assertEquals(zip.body(), HttpServletResponse.SC_OK, zip.status());
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip.bodyBytes()))) {
			ZipEntry entry = in.getNextEntry();
			assertEquals("grid.heic", entry.getName());
			assertTrue(Arrays.equals(uploaded, in.readAllBytes()));
		}
	}

	public void testTheSidecarRoundTripsAnAlbumHoldingAHeic() throws Exception {
		uploadAll("single.heic", fixture("single.heic"), "rotated.heic", fixture("rotated.heic"));
		String before = get(PATH, "json", SharingFixture.ALICE).body();

		FakeResponse stored = put(PATH, before, SharingFixture.ALICE);
		assertEquals(stored.body(), HttpServletResponse.SC_OK, stored.status());
		restartServer();

		assertEquals(before, get(PATH, "json", SharingFixture.ALICE).body());
		AlbumInfo again = album();
		assertDescribed(image(again, "rotated.heic"), 120, 180);
	}

	public void testAnUnsupportedFileIsRefusedAloneAndTheRestStored() throws Exception {
		UploadResult result = uploadAll("a.jpg", bytes(JPEG), "x.bmp", "bmp".getBytes(), "single.heic",
			fixture("single.heic"), ".hidden.jpg", bytes(JPEG));
		assertEquals(Arrays.asList("a.jpg", "single.heic"),
			result.getFiles().stream().map(UploadedFile::getStoredAs).collect(Collectors.toList()));
		assertEquals(Arrays.asList("x.bmp", ".hidden.jpg"),
			result.getRefused().stream().map(RefusedFile::getName).collect(Collectors.toList()));
		assertEquals(ImageServlet.unsupportedFormat("x.bmp"), result.getRefused().get(0).getReason());
		assertTrue(result.getRefused().get(0).getReason().contains("x.bmp"));
		assertEquals(ImageServlet.unsupportedName(".hidden.jpg"), result.getRefused().get(1).getReason());
		assertFalse(Files.exists(_base.resolve(ALBUM).resolve("x.bmp")));
		assertFalse(Files.exists(_base.resolve(ALBUM).resolve(".hidden.jpg")));
		assertTrue(Files.exists(_base.resolve(ALBUM).resolve("a.jpg")));
		assertTrue(Files.exists(_base.resolve(ALBUM).resolve("single.heic")));
	}

	public void testABatchOfNothingSupportedIsRefusedWithAReason() throws Exception {
		LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
		files.put("x.bmp", "bmp".getBytes());
		FakeResponse response = upload(PATH, SharingFixture.ALICE, files);
		assertEquals(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, response.status());
		assertEquals(ImageServlet.unsupportedFormat("x.bmp"), errorMessage(response));
		try (java.util.stream.Stream<Path> stored = Files.list(_base.resolve(ALBUM))) {
			assertEquals(0, stored.filter(p -> !p.getFileName().toString().startsWith(".")).count());
		}
	}

	public void testASingleImagePutOfAnUnsupportedFileNamesIt() throws Exception {
		FakeResponse response = upload(PATH + "x.bmp", SharingFixture.ALICE, "x.bmp", "bmp".getBytes());
		assertEquals(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, response.status());
		assertEquals(ImageServlet.unsupportedFormat("x.bmp"), errorMessage(response));
	}

	public void testWithoutADecoderTheListingStandsAndThePreviewSaysWhy() throws Exception {
		HeifDecoder.setProgramLocator(() -> {
			throw new IOException("no FFmpeg on this machine");
		});
		copy("grid.heic");

		assertDescribed(image(album(), "grid.heic"), 180, 120);

		FakeResponse preview = get(PATH + "grid.heic", "tn", SharingFixture.ALICE);
		assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, preview.status());
		String reason = errorMessage(preview);
		assertTrue(reason, reason.contains("HEIC/HEIF photographs cannot be decoded"));
		assertTrue(reason, reason.contains("no FFmpeg on this machine"));

		FakeResponse display = get(PATH + "grid.heic", ImageServlet.DISPLAY_TYPE, SharingFixture.ALICE);
		assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, display.status());
		assertEquals(reason, errorMessage(display));

		// The original is still the original.
		FakeResponse original = get(PATH + "grid.heic", null, SharingFixture.ALICE);
		assertTrue(Arrays.equals(fixture("grid.heic"), original.bodyBytes()));
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

	private static void assertDescribed(ImagePart image, int width, int height) {
		assertEquals(image.getName(), width, image.getWidth());
		assertEquals(image.getName(), height, image.getHeight());
		assertEquals(image.getName(), TAKEN, image.getDate());
		assertEquals(image.getName(), "Fixture HEIC", image.getCamera());
		assertNotNull(image.getName(), image.getLocation());
		assertEquals(48.125, image.getLocation().getLatitude(), 1e-6);
		assertEquals(11.57, image.getLocation().getLongitude(), 1e-6);
		assertEquals(de.haumacher.imageServer.shared.model.ImageKind.IMAGE, image.getKind());
	}

	private static void assertPicture(FakeResponse response, int width, int height) throws IOException {
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		BufferedImage picture = ImageIO.read(new ByteArrayInputStream(response.bodyBytes()));
		assertNotNull("A JPEG", picture);
		assertEquals(width, picture.getWidth());
		assertEquals(height, picture.getHeight());
		de.haumacher.imageServer.heif.TestHeifDecoder.assertQuadrants(picture);
	}

	private static byte[] fixture(String name) throws IOException {
		return bytes(new File(FIXTURES, name));
	}

	private static byte[] bytes(File file) throws IOException {
		return Files.readAllBytes(file.toPath());
	}
}
