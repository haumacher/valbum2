/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.heif.HeifDecoder;
import de.haumacher.imageServer.heif.TestAvifDecoder;
import de.haumacher.imageServer.heif.TestHeifDecoder;
import de.haumacher.imageServer.jxl.TestJxlFile;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;

/**
 * Test case for AVIF and JPEG XL pictures, see issue #193.
 *
 * <p>
 * The fixtures are described in <code>src/test/fixtures/avif/README.md</code> and
 * <code>src/test/fixtures/jxl/README.md</code>: four quadrants (red, green, blue, yellow from the
 * top left) once upright, an EXIF block with a date, a camera and a position. A JPEG XL picture is
 * decoded everywhere (the decoder is Java); an AVIF needs a software AV1 decoder in the FFmpeg
 * program, which the bundled one has since the presets 1.5.9 (issue #210), see
 * {@link TestAvifDecoder#assertAv1Decoder()}.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestAvifJxl extends ShareTestCase {

	private static final File AVIF = TestAvifDecoder.FIXTURES;

	private static final File JXL = TestJxlFile.FIXTURES;

	private static final File JPEG =
		new File("src/test/fixtures/test-album/2005-08-24 Blumen und Fliegen/IMG_0417.JPG");

	private static final String ALBUM = "2024/Modern";

	private static final String PATH = "/" + ALBUM + "/";

	/** 2024:05:17 14:30:00 at +02:00, the fixtures' DateTimeOriginal. */
	private static final long TAKEN = Instant.parse("2024-05-17T12:30:00Z").toEpochMilli();

	private static final List<String> AVIFS =
		Arrays.asList("single.avif", "grid.avif", "rotated.avif", "alpha.avif", "ten-bit.avif");

	private static final List<String> JXLS = Arrays.asList("lossy.jxl", "lossless.jxl", "rotated.jxl", "alpha.jxl");

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		Files.createDirectories(_base.resolve(ALBUM));
	}

	@Override
	protected void tearDown() throws Exception {
		HeifDecoder.setProgramLocator(null);
		PictureReader.resetBudget();
		super.tearDown();
	}

	public void testABatchOfAJpegAnAvifAndAJxlIsStoredAndListed() throws Exception {
		UploadResult result = uploadAll("a.jpg", bytes(JPEG), "x.avif", fixture(AVIF, "rotated.avif"), "y.jxl",
			fixture(JXL, "lossy.jxl"), "Z.JXL", fixture(JXL, "rotated.jxl"), "w.AVIF", fixture(AVIF, "grid.avif"));
		assertEquals(Arrays.asList(ImageServlet.STORED, ImageServlet.STORED, ImageServlet.STORED, ImageServlet.STORED,
			ImageServlet.STORED), result.getFiles().stream().map(UploadedFile::getStatus).collect(Collectors.toList()));
		assertTrue(result.getRefused().isEmpty());

		AlbumInfo album = album();
		assertNotNull(image(album, "a.jpg"));
		// Upright: the container's quarter turn applied once, the EXIF orientation 8 not on top.
		assertDescribed(image(album, "x.avif"), "Fixture AVIF", 120, 180);
		assertDescribed(image(album, "w.AVIF"), "Fixture AVIF", 180, 120);
		assertDescribed(image(album, "y.jxl"), "Fixture JXL", 96, 64);
		// The codestream's orientation 6 applied once, the EXIF orientation 6 not on top.
		assertDescribed(image(album, "Z.JXL"), "Fixture JXL", 96, 64);
	}

	public void testEveryJxlPreviewIsAnUprightJpeg() throws Exception {
		for (String name : JXLS) {
			copy(JXL, name);
		}
		assertPicture(get(PATH + "lossy.jxl", "tn", SharingFixture.ALICE), 96, 64);
		assertPicture(get(PATH + "lossless.jxl", "tn", SharingFixture.ALICE), 96, 64);
		assertPicture(get(PATH + "rotated.jxl", "tn", SharingFixture.ALICE), 96, 64);
		assertTransparentCornerIsWhite(get(PATH + "alpha.jxl", "tn", SharingFixture.ALICE));
		assertEverythingGeneratedIsKnown();
	}

	public void testEveryAvifPreviewIsAnUprightJpeg() throws Exception {
		TestAvifDecoder.assertAv1Decoder();
		for (String name : AVIFS) {
			copy(AVIF, name);
		}
		assertPicture(get(PATH + "single.avif", "tn", SharingFixture.ALICE), 96, 64);
		assertPicture(get(PATH + "grid.avif", "tn", SharingFixture.ALICE), 180, 120);
		assertPicture(get(PATH + "rotated.avif", "tn", SharingFixture.ALICE), 120, 180);
		assertPicture(get(PATH + "ten-bit.avif", "tn", SharingFixture.ALICE), 96, 64);
		assertTransparentCornerIsWhite(get(PATH + "alpha.avif", "tn", SharingFixture.ALICE));
		assertEverythingGeneratedIsKnown();
	}

	public void testTheDisplayRenditionIsAnUprightJpeg() throws Exception {
		copy(JXL, "rotated.jxl");
		FakeResponse response = get(PATH + "rotated.jxl", ImageServlet.DISPLAY_TYPE, SharingFixture.ALICE);
		assertPicture(response, 96, 64);
		File display = PreviewCache.displayFile(_base.resolve(ALBUM).resolve("rotated.jxl").toFile());
		assertTrue(display.isFile());
		assertTrue(CacheRefresh.isGenerated(display.getName()));

		TestAvifDecoder.assertAv1Decoder();
		copy(AVIF, "rotated.avif");
		copy(AVIF, "alpha.avif");
		assertPicture(get(PATH + "rotated.avif", ImageServlet.DISPLAY_TYPE, SharingFixture.ALICE), 120, 180);
		assertTransparentCornerIsWhite(get(PATH + "alpha.avif", ImageServlet.DISPLAY_TYPE, SharingFixture.ALICE));
	}

	public void testTheOriginalIsTheUploadedBytes() throws Exception {
		byte[] avif = fixture(AVIF, "grid.avif");
		byte[] jxl = fixture(JXL, "lossless.jxl");
		uploadAll("grid.avif", avif, "lossless.jxl", jxl);

		assertOriginal("grid.avif", avif, "image/avif");
		assertOriginal("lossless.jxl", jxl, "image/jxl");

		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", ImageServlet.ZIP_ACTION);
		FakeResponse zip = post(PATH,
			"{\"target\":\"\",\"names\":[{\"name\":\"grid.avif\"},{\"name\":\"lossless.jxl\"}]}",
			SharingFixture.ALICE, parameters);
		assertEquals(zip.body(), HttpServletResponse.SC_OK, zip.status());
		Map<String, byte[]> entries = new HashMap<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip.bodyBytes()))) {
			ZipEntry entry;
			while ((entry = in.getNextEntry()) != null) {
				entries.put(entry.getName(), in.readAllBytes());
			}
		}
		assertTrue(Arrays.equals(avif, entries.get("grid.avif")));
		assertTrue(Arrays.equals(jxl, entries.get("lossless.jxl")));
	}

	public void testTheSidecarRoundTripsAnAlbumHoldingThem() throws Exception {
		uploadAll("rotated.avif", fixture(AVIF, "rotated.avif"), "rotated.jxl", fixture(JXL, "rotated.jxl"),
			"alpha.avif", fixture(AVIF, "alpha.avif"));
		String before = get(PATH, "json", SharingFixture.ALICE).body();

		FakeResponse stored = put(PATH, before, SharingFixture.ALICE);
		assertEquals(stored.body(), HttpServletResponse.SC_OK, stored.status());
		restartServer();

		assertEquals(before, get(PATH, "json", SharingFixture.ALICE).body());
		AlbumInfo again = album();
		assertDescribed(image(again, "rotated.avif"), "Fixture AVIF", 120, 180);
		assertDescribed(image(again, "rotated.jxl"), "Fixture JXL", 96, 64);
	}

	public void testWithoutAnAv1DecoderTheListingStandsAndThePreviewSaysWhy() throws Exception {
		HeifDecoder.setProgramLocator(() -> {
			throw new IOException("no FFmpeg on this machine");
		});
		copy(AVIF, "grid.avif");

		assertDescribed(image(album(), "grid.avif"), "Fixture AVIF", 180, 120);

		FakeResponse preview = get(PATH + "grid.avif", "tn", SharingFixture.ALICE);
		assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, preview.status());
		String reason = errorMessage(preview);
		assertTrue(reason, reason.contains("AVIF pictures cannot be decoded"));
		assertTrue(reason, reason.contains("no FFmpeg on this machine"));

		FakeResponse display = get(PATH + "grid.avif", ImageServlet.DISPLAY_TYPE, SharingFixture.ALICE);
		assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, display.status());
		assertEquals(reason, errorMessage(display));

		FakeResponse original = get(PATH + "grid.avif", null, SharingFixture.ALICE);
		assertTrue(Arrays.equals(fixture(AVIF, "grid.avif"), original.bodyBytes()));
	}

	public void testAProgramWithoutAnAv1DecoderSaysSoOnThePreview() throws Exception {
		TestAvifDecoder.installProgramWithoutAv1(_base);
		String unavailable = HeifDecoder.av1Unavailability();
		assertNotNull(unavailable);
		copy(AVIF, "single.avif");
		FakeResponse preview = get(PATH + "single.avif", "tn", SharingFixture.ALICE);
		assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, preview.status());
		assertEquals(unavailable, errorMessage(preview));
		assertTrue(unavailable, unavailable.contains("no software AV1 decoder"));
		// HEIC is untouched.
		assertNull(HeifDecoder.unavailability());
	}

	public void testAJxlTooLargeForTheBudgetIsListedAndThePreviewSaysWhatToDo() throws Exception {
		copy(JXL, "large.jxl");
		PictureReader.setBudget(1024 * 1024);
		ImagePart image = image(album(), "large.jxl");
		assertEquals(512, image.getWidth());

		FakeResponse preview = get(PATH + "large.jxl", "tn", SharingFixture.ALICE);
		assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, preview.status());
		String reason = errorMessage(preview);
		assertTrue(reason, reason.startsWith("'large.jxl' is a lossless JPEG XL picture of 512 × 512 pixels"));

		FakeResponse display = get(PATH + "large.jxl", ImageServlet.DISPLAY_TYPE, SharingFixture.ALICE);
		assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, display.status());
		assertEquals(reason, errorMessage(display));
	}

	// --- Helpers. ---

	private void assertOriginal(String name, byte[] uploaded, String contentType) throws Exception {
		FakeResponse original = get(PATH + name, null, SharingFixture.ALICE);
		assertEquals(HttpServletResponse.SC_OK, original.status());
		assertEquals(contentType, original.contentType());
		assertTrue(Arrays.equals(uploaded, original.bodyBytes()));
		assertTrue("The original is never touched.",
			Arrays.equals(uploaded, Files.readAllBytes(_base.resolve(ALBUM).resolve(name))));
	}

	private void assertEverythingGeneratedIsKnown() {
		File cache = _base.resolve(ALBUM).resolve(PreviewCache.CACHE_DIRECTORY_NAME).toFile();
		for (String file : cache.list()) {
			assertTrue("Everything generated is named so that a refresh finds it: " + file,
				CacheRefresh.isGenerated(file));
		}
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

	private void copy(File fixtures, String name) throws IOException {
		Files.copy(new File(fixtures, name).toPath(), _base.resolve(ALBUM).resolve(name));
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

	private static void assertDescribed(ImagePart image, String camera, int width, int height) {
		assertEquals(image.getName(), width, image.getWidth());
		assertEquals(image.getName(), height, image.getHeight());
		assertEquals(image.getName(), TAKEN, image.getDate());
		assertEquals(image.getName(), camera, image.getCamera());
		assertNotNull(image.getName(), image.getLocation());
		assertEquals(48.125, image.getLocation().getLatitude(), 1e-6);
		assertEquals(11.57, image.getLocation().getLongitude(), 1e-6);
		assertEquals(ImageKind.IMAGE, image.getKind());
	}

	private static BufferedImage picture(FakeResponse response) throws IOException {
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		BufferedImage picture = ImageIO.read(new ByteArrayInputStream(response.bodyBytes()));
		assertNotNull("A JPEG", picture);
		return picture;
	}

	private static void assertPicture(FakeResponse response, int width, int height) throws IOException {
		BufferedImage picture = picture(response);
		assertEquals(width, picture.getWidth());
		assertEquals(height, picture.getHeight());
		TestHeifDecoder.assertQuadrants(picture);
	}

	private static void assertTransparentCornerIsWhite(FakeResponse response) throws IOException {
		BufferedImage picture = picture(response);
		assertEquals(96, picture.getWidth());
		assertEquals(64, picture.getHeight());
		TestHeifDecoder.assertColour(TestHeifDecoder.RED, picture, 2, 2);
		TestAvifDecoder.assertWhite(picture, 93, 61);
	}

	private static byte[] fixture(File fixtures, String name) throws IOException {
		return bytes(new File(fixtures, name));
	}

	private static byte[] bytes(File file) throws IOException {
		return Files.readAllBytes(file.toPath());
	}
}
