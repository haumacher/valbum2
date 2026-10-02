/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.heif.TestHeifDecoder;
import de.haumacher.imageServer.raw.RawFile;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ImageKind;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.MoveResult;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.UploadResult;
import de.haumacher.imageServer.shared.model.UploadedFile;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;

/**
 * Test case for raw photographs, see issue #191 and {@link RawFixtures}.
 *
 * <p>
 * A raw is shown through the JPEG it carries and downloaded as it came, and a raw and the JPEG of
 * its name are one photograph: one entry, whichever arrived first, moved, trashed, purged and zipped
 * together.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestRawPhotos extends ShareTestCase {

	private static final String ALBUM = "2024/Raw";

	private static final String PATH = "/" + ALBUM + "/";

	private static final String TARGET = "2024/Target";

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		Files.createDirectories(_base.resolve(ALBUM));
		Files.createDirectories(_base.resolve(TARGET));
	}

	// --- A batch, and what a raw is shown as. ---

	public void testABatchOfAJpegAPairAndASoloRawIsThreeEntries() throws Exception {
		byte[] solo = RawFixtures.dng(128, 192, 6, 3);
		UploadResult result = uploadAll("a.jpg", RawFixtures.quadrants(60, 40), "IMG_1.JPG", jpeg(),
			"IMG_1.CR2", RawFixtures.cr2(120, 80, 1), "solo.DNG", solo);
		assertEquals(Arrays.asList("a.jpg", "IMG_1.JPG", "IMG_1.CR2", "solo.DNG"),
			result.getFiles().stream().map(UploadedFile::getStoredAs).collect(Collectors.toList()));

		AlbumInfo album = album();
		assertEquals(Arrays.asList("IMG_1.JPG", "solo.DNG", "a.jpg").stream().sorted().collect(Collectors.toList()),
			names(album).stream().sorted().collect(Collectors.toList()));
		assertEquals("IMG_1.CR2", image(album, "IMG_1.JPG").getRaw());
		assertEquals("", image(album, "a.jpg").getRaw());

		ImagePart raw = image(album, "solo.DNG");
		assertEquals("", raw.getRaw());
		assertEquals(ImageKind.IMAGE, raw.getKind());
		// Upright: the raw's orientation 6 applied once, the preview's own orientation 3 never.
		assertEquals(128, raw.getWidth());
		assertEquals(192, raw.getHeight());
		assertEquals(RawFixtures.TAKEN, raw.getDate());
		assertEquals(RawFixtures.CAMERA, raw.getCamera());
		assertEquals(48.125, raw.getLocation().getLatitude(), 1e-6);
		assertEquals(11.57, raw.getLocation().getLongitude(), 1e-6);

		assertPicture(get(PATH + "solo.DNG", "tn", SharingFixture.ALICE), 128, 192);
		assertPicture(get(PATH + "solo.DNG", ImageServlet.DISPLAY_TYPE, SharingFixture.ALICE), 128, 192);

		FakeResponse original = get(PATH + "solo.DNG", null, SharingFixture.ALICE);
		assertEquals(HttpServletResponse.SC_OK, original.status());
		assertEquals("image/x-adobe-dng", original.contentType());
		assertTrue(Arrays.equals(solo, original.bodyBytes()));
		assertTrue("The original is never touched.",
			Arrays.equals(solo, Files.readAllBytes(_base.resolve(ALBUM).resolve("solo.DNG"))));

		File cache = _base.resolve(ALBUM).resolve(PreviewCache.CACHE_DIRECTORY_NAME).toFile();
		for (String file : cache.list()) {
			assertTrue("Everything generated is named so that a refresh finds it: " + file,
				CacheRefresh.isGenerated(file));
		}
	}

	public void testTheCompanionIsAddressedByItsNameAndAnsweredAsItsPhoto() throws Exception {
		byte[] cr2 = RawFixtures.cr2(120, 80, 1);
		uploadAll("IMG_1.JPG", jpeg(), "IMG_1.CR2", cr2);

		FakeResponse original = get(PATH + "IMG_1.CR2", null, SharingFixture.ALICE);
		assertEquals(HttpServletResponse.SC_OK, original.status());
		assertEquals("image/x-canon-cr2", original.contentType());
		assertTrue(Arrays.equals(cr2, original.bodyBytes()));

		FakeResponse description = get(PATH + "IMG_1.CR2", "json", SharingFixture.ALICE);
		ImagePart part = (ImagePart) Resource.readResource(reader(description.body()));
		assertEquals("IMG_1.JPG", part.getName());

		// The JPEG of the pair shows the JPEG, the companion shows its own embedded preview.
		assertPicture(get(PATH + "IMG_1.JPG", "tn", SharingFixture.ALICE), 120, 80);
		assertPicture(get(PATH + "IMG_1.CR2", "tn", SharingFixture.ALICE), 120, 80);
	}

	public void testAPrivatePhotographHidesItsRawToo() throws Exception {
		uploadAll("IMG_1.JPG", jpeg(), "IMG_1.CR2", RawFixtures.cr2(120, 80, 1));
		AlbumInfo album = album();
		image(album, "IMG_1.JPG").setPrivacy(2);
		assertEquals(HttpServletResponse.SC_OK, put(PATH, json(album), SharingFixture.ALICE).status());

		// Anonymous callers of this open space see public photographs only.
		FakeResponse raw = get(PATH + "IMG_1.CR2", null, null);
		assertFalse(raw.body(), raw.status() == HttpServletResponse.SC_OK);
		FakeResponse photo = get(PATH + "IMG_1.JPG", null, null);
		assertEquals(photo.status(), raw.status());
	}

	public void testACr3IsShownThroughItsLargestJpeg() throws Exception {
		Files.write(_base.resolve(ALBUM).resolve("canon.CR3"), RawFixtures.cr3(240, 160));
		ImagePart image = image(album(), "canon.CR3");
		assertEquals(240, image.getWidth());
		assertEquals(160, image.getHeight());
		assertEquals(RawFixtures.TAKEN, image.getDate());
		assertEquals(RawFixtures.CAMERA, image.getCamera());
		assertEquals(48.125, image.getLocation().getLatitude(), 1e-6);
		assertPicture(get(PATH + "canon.CR3", "tn", SharingFixture.ALICE), 240, 160);
	}

	public void testARafIsShownThroughItsJpeg() throws Exception {
		Files.write(_base.resolve(ALBUM).resolve("fuji.raf"), RawFixtures.raf(160, 120, 6));
		ImagePart image = image(album(), "fuji.raf");
		assertEquals(160, image.getWidth());
		assertEquals(120, image.getHeight());
		assertEquals(RawFixtures.TAKEN, image.getDate());
		assertEquals(RawFixtures.CAMERA, image.getCamera());
		assertEquals(11.57, image.getLocation().getLongitude(), 1e-6);
		assertPicture(get(PATH + "fuji.raf", "tn", SharingFixture.ALICE), 160, 120);
	}

	public void testAnAndroidDngIsShownThroughItsUncompressedThumbnail() throws Exception {
		// DngCreator writes no JPEG, only an RGB thumbnail of at most 256 px: shown at what it has.
		Files.write(_base.resolve(ALBUM).resolve("PXL_1.dng"), RawFixtures.dngWithRgbThumbnail(64, 96, 6));
		ImagePart image = image(album(), "PXL_1.dng");
		assertEquals(64, image.getWidth());
		assertEquals(96, image.getHeight());
		assertEquals(RawFixtures.TAKEN, image.getDate());
		assertEquals(RawFixtures.CAMERA, image.getCamera());
		assertPicture(get(PATH + "PXL_1.dng", "tn", SharingFixture.ALICE), 64, 96);
		assertPicture(get(PATH + "PXL_1.dng", ImageServlet.DISPLAY_TYPE, SharingFixture.ALICE), 64, 96);
	}

	public void testARawWithoutAPreviewIsListedAndSaysWhy() throws Exception {
		Files.write(_base.resolve(ALBUM).resolve("bare.dng"), RawFixtures.dngWithoutPreview());
		ImagePart image = image(album(), "bare.dng");
		assertEquals(300, image.getWidth());
		assertEquals(200, image.getHeight());

		FakeResponse preview = get(PATH + "bare.dng", "tn", SharingFixture.ALICE);
		assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, preview.status());
		assertEquals(RawFile.noPreview("bare.dng"), errorMessage(preview));
		FakeResponse display = get(PATH + "bare.dng", ImageServlet.DISPLAY_TYPE, SharingFixture.ALICE);
		assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, display.status());
		assertEquals(RawFile.noPreview("bare.dng"), errorMessage(display));
	}

	// --- Arrival in either order. ---

	public void testAJpegArrivingAfterItsRawKeepsTheRawsEdits() throws Exception {
		uploadAll("IMG_1.CR2", RawFixtures.cr2(120, 80, 1));
		AlbumInfo album = album();
		assertEquals(Arrays.asList("IMG_1.CR2"), names(album));
		ImagePart raw = image(album, "IMG_1.CR2");
		raw.setRating(2).setPrivacy(1).setComment("Kept").setOrientation(
			de.haumacher.imageServer.shared.model.Orientation.ROT_180);
		assertEquals(HttpServletResponse.SC_OK, put(PATH, json(album), SharingFixture.ALICE).status());

		uploadAll("IMG_1.JPG", RawFixtures.withExif(RawFixtures.stored(150, 100, 1), RawFixtures.exifTiff(1)));
		AlbumInfo after = album();
		assertEquals(Arrays.asList("IMG_1.JPG"), names(after));
		ImagePart photo = image(after, "IMG_1.JPG");
		assertEquals("IMG_1.CR2", photo.getRaw());
		assertEquals(2, photo.getRating());
		assertEquals(1, photo.getPrivacy());
		assertEquals("Kept", photo.getComment());
		assertEquals(de.haumacher.imageServer.shared.model.Orientation.ROT_180, photo.getOrientation());
		assertEquals("The JPEG's own size.", 150, photo.getWidth());
		assertEquals(100, photo.getHeight());

		// Stored by the next write, and read back the same.
		assertEquals(HttpServletResponse.SC_OK, put(PATH, json(after), SharingFixture.ALICE).status());
		restartServer();
		assertEquals(json(after), json(album()));
		assertTrue(read(_base.resolve(ALBUM).resolve("index.json")).contains("\"raw\":\"IMG_1.CR2\""));
	}

	public void testARawArrivingAfterItsJpegBecomesItsCompanion() throws Exception {
		uploadAll("IMG_1.JPG", jpeg());
		AlbumInfo album = album();
		image(album, "IMG_1.JPG").setRating(1).setComment("Mine");
		assertEquals(HttpServletResponse.SC_OK, put(PATH, json(album), SharingFixture.ALICE).status());

		uploadAll("img_1.cr2", RawFixtures.cr2(120, 80, 1));
		AlbumInfo after = album();
		assertEquals(Arrays.asList("IMG_1.JPG"), names(after));
		ImagePart photo = image(after, "IMG_1.JPG");
		assertEquals("The base name is compared ignoring case.", "img_1.cr2", photo.getRaw());
		assertEquals(1, photo.getRating());
		assertEquals("Mine", photo.getComment());
	}

	public void testTwoStoredPartsMergeTheJpegWinningFieldByField() throws Exception {
		Path dir = _base.resolve(ALBUM);
		Files.write(dir.resolve("IMG_1.JPG"), jpeg());
		Files.write(dir.resolve("IMG_1.CR2"), RawFixtures.cr2(120, 80, 1));
		// As a move of the raw alone into an album holding the JPEG leaves it.
		Files.write(dir.resolve("index.json"), ("[\"AlbumInfo\",{\"title\":\"Raw\",\"parts\":["
			+ "[\"ImagePart\",{\"kind\":\"IMAGE\",\"name\":\"IMG_1.JPG\",\"date\":1,\"width\":120,\"height\":80,"
			+ "\"rating\":0,\"privacy\":0,\"comment\":\"\"}],"
			+ "[\"ImagePart\",{\"kind\":\"IMAGE\",\"name\":\"IMG_1.CR2\",\"date\":2,\"width\":120,\"height\":80,"
			+ "\"rating\":1,\"privacy\":2,\"comment\":\"From the raw\"}]"
			+ "],\"indexPicture\":{\"image\":\"IMG_1.CR2\",\"scale\":1.0}}]").getBytes(StandardCharsets.UTF_8));

		AlbumInfo album = album();
		assertEquals(Arrays.asList("IMG_1.JPG"), names(album));
		ImagePart photo = image(album, "IMG_1.JPG");
		assertEquals("IMG_1.CR2", photo.getRaw());
		assertEquals("A rating the JPEG leaves empty is the raw's.", 1, photo.getRating());
		assertEquals("The stricter privacy.", 2, photo.getPrivacy());
		assertEquals("From the raw", photo.getComment());
		assertEquals("The date stays the JPEG's.", 1, photo.getDate());
		assertEquals("The cover follows.", "IMG_1.JPG", album.getIndexPicture().getImage());
	}

	public void testAPairInOneBatchKeepsOneBaseWhereTheNameIsTaken() throws Exception {
		uploadAll("IMG_1.JPG", RawFixtures.quadrants(60, 40));
		UploadResult result = uploadAll("IMG_1.JPG", jpeg(), "IMG_1.CR2", RawFixtures.cr2(120, 80, 1));
		assertEquals(Arrays.asList("IMG_1-2.JPG", "IMG_1-2.CR2"),
			result.getFiles().stream().map(UploadedFile::getStoredAs).collect(Collectors.toList()));
		AlbumInfo album = album();
		assertEquals("", image(album, "IMG_1.JPG").getRaw());
		assertEquals("IMG_1-2.CR2", image(album, "IMG_1-2.JPG").getRaw());
	}

	public void testAPhotographWhoseJpegIsGoneIsItsRaw() throws Exception {
		uploadAll("IMG_1.JPG", jpeg(), "IMG_1.CR2", RawFixtures.cr2(120, 80, 1));
		AlbumInfo album = album();
		image(album, "IMG_1.JPG").setRating(2);
		assertEquals(HttpServletResponse.SC_OK, put(PATH, json(album), SharingFixture.ALICE).status());

		Files.delete(_base.resolve(ALBUM).resolve("IMG_1.JPG"));
		restartServer();
		AlbumInfo after = album();
		assertEquals(Arrays.asList("IMG_1.CR2"), names(after));
		assertEquals(2, image(after, "IMG_1.CR2").getRating());
		assertEquals("", image(after, "IMG_1.CR2").getRaw());
	}

	// --- Acting on the pair. ---

	public void testAMoveTakesBothFiles() throws Exception {
		uploadAll("IMG_1.JPG", jpeg(), "IMG_1.CR2", RawFixtures.cr2(120, 80, 1));
		album();
		MoveResult result = movePair(PATH, TARGET, "IMG_1.JPG");
		assertEquals("", result.getOutcomes().get(0).getMessage());

		assertFalse(Files.exists(_base.resolve(ALBUM).resolve("IMG_1.JPG")));
		assertFalse(Files.exists(_base.resolve(ALBUM).resolve("IMG_1.CR2")));
		assertTrue(Files.exists(_base.resolve(TARGET).resolve("IMG_1.JPG")));
		assertTrue(Files.exists(_base.resolve(TARGET).resolve("IMG_1.CR2")));
		AlbumInfo target = album("/" + TARGET + "/");
		assertEquals(Arrays.asList("IMG_1.JPG"), names(target));
		assertEquals("IMG_1.CR2", image(target, "IMG_1.JPG").getRaw());
		assertTrue(names(album()).isEmpty());
	}

	public void testAMoveIntoATakenNameKeepsThePairOnOneBase() throws Exception {
		Files.write(_base.resolve(TARGET).resolve("IMG_1.JPG"), RawFixtures.quadrants(60, 40));
		uploadAll("IMG_1.JPG", jpeg(), "IMG_1.CR2", RawFixtures.cr2(120, 80, 1));
		album();
		movePair(PATH, TARGET, "IMG_1.JPG");
		AlbumInfo target = album("/" + TARGET + "/");
		// freeName writes the extension it numbers in lower case; the raw asks for the base and keeps its own.
		assertEquals("IMG_1-2.CR2", image(target, "IMG_1-2.jpg").getRaw());
		assertEquals("", image(target, "IMG_1.JPG").getRaw());
	}

	public void testADeleteTrashesBothFiles() throws Exception {
		uploadAll("IMG_1.JPG", jpeg(), "IMG_1.CR2", RawFixtures.cr2(120, 80, 1));
		album();
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "delete");
		FakeResponse response = post(PATH, names("", "IMG_1.JPG"), SharingFixture.ALICE, parameters);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());

		Path trash = _base.resolve(".valbum").resolve(DeleteService.TRASH_FOLDER).resolve("Raw");
		assertTrue(Files.exists(trash.resolve("IMG_1.JPG")));
		assertTrue(Files.exists(trash.resolve("IMG_1.CR2")));
		assertFalse(Files.exists(_base.resolve(ALBUM).resolve("IMG_1.CR2")));
	}

	public void testAPurgeDeletesBothFilesAndTheirCache() throws Exception {
		uploadAll("IMG_1.JPG", jpeg(), "IMG_1.CR2", RawFixtures.cr2(120, 80, 1), "a.jpg",
			RawFixtures.quadrants(60, 40));
		assertPicture(get(PATH + "IMG_1.JPG", "tn", SharingFixture.ALICE), 120, 80);
		assertPicture(get(PATH + "IMG_1.CR2", "tn", SharingFixture.ALICE), 120, 80);
		AlbumInfo album = album();
		image(album, "IMG_1.JPG").setRating(-2);
		assertEquals(HttpServletResponse.SC_OK, put(PATH, json(album), SharingFixture.ALICE).status());

		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "purge");
		FakeResponse response = post(PATH, "", SharingFixture.ALICE, parameters);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		MoveResult result = MoveResult.readMoveResult(reader(response.body()));
		assertEquals(1, result.getOutcomes().size());
		assertEquals(DeleteService.PURGED, result.getOutcomes().get(0).getMessage());

		Path dir = _base.resolve(ALBUM);
		assertFalse(Files.exists(dir.resolve("IMG_1.JPG")));
		assertFalse(Files.exists(dir.resolve("IMG_1.CR2")));
		assertTrue(Files.exists(dir.resolve("a.jpg")));
		try (Stream<Path> cache = Files.list(dir.resolve(PreviewCache.CACHE_DIRECTORY_NAME))) {
			List<String> left = cache.map(p -> p.getFileName().toString()).filter(n -> n.contains("IMG_1"))
				.collect(Collectors.toList());
			assertTrue("Nothing generated of the pair is left: " + left, left.isEmpty());
		}
		assertFalse(read(dir.resolve(".hashes.json")).contains("IMG_1"));
	}

	public void testAZipCarriesBothOriginals() throws Exception {
		byte[] jpeg = jpeg();
		byte[] cr2 = RawFixtures.cr2(120, 80, 1);
		uploadAll("IMG_1.JPG", jpeg, "IMG_1.CR2", cr2);
		album();
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", ImageServlet.ZIP_ACTION);
		FakeResponse zip = post(PATH, names("", "IMG_1.JPG", "IMG_1.CR2"), SharingFixture.ALICE, parameters);
		assertEquals(zip.body(), HttpServletResponse.SC_OK, zip.status());
		Map<String, byte[]> entries = new LinkedHashMap<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip.bodyBytes()))) {
			for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
				entries.put(entry.getName(), in.readAllBytes());
			}
		}
		assertEquals("Each file once.", Arrays.asList("IMG_1.JPG", "IMG_1.CR2"), new ArrayList<>(entries.keySet()));
		assertTrue(Arrays.equals(jpeg, entries.get("IMG_1.JPG")));
		assertTrue(Arrays.equals(cr2, entries.get("IMG_1.CR2")));
	}

	public void testTheDuplicateSweepTakesEachFileOfAPairOnItsOwn() throws Exception {
		byte[] jpeg = jpeg();
		uploadAll("IMG_1.JPG", jpeg, "IMG_1.CR2", RawFixtures.cr2(120, 80, 1));
		AlbumInfo album = album();
		image(album, "IMG_1.JPG").setRating(2);
		assertEquals(HttpServletResponse.SC_OK, put(PATH, json(album), SharingFixture.ALICE).status());
		// The JPEG, and only the JPEG, lies elsewhere in the space too.
		Files.createDirectories(_base.resolve("2024/Other"));
		Files.write(_base.resolve("2024/Other/copy.jpg"), jpeg);
		servlet().index().indexNow();

		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "find-duplicates");
		FakeResponse response = post(PATH, "", SharingFixture.ALICE, parameters);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		MoveResult result = MoveResult.readMoveResult(reader(response.body()));
		assertEquals(1, result.getOutcomes().size());
		assertEquals("IMG_1.JPG", result.getOutcomes().get(0).getName());

		// The photograph stays as its raw, with what was said about it.
		AlbumInfo after = album();
		assertEquals(Arrays.asList("IMG_1.CR2"), names(after));
		assertEquals(2, image(after, "IMG_1.CR2").getRating());
		assertEquals("", image(after, "IMG_1.CR2").getRaw());
	}

	// --- The stored form. ---

	public void testTheStoredFormOfAPair() throws Exception {
		Path dir = _base.resolve(ALBUM);
		Files.write(dir.resolve("IMG_1.JPG"), jpeg());
		Files.write(dir.resolve("IMG_1.CR2"), RawFixtures.cr2(120, 80, 1));
		Files.copy(new File("src/test/fixtures/raw-pair/index.json").toPath(), dir.resolve("index.json"));

		AlbumInfo album = album();
		assertEquals(Arrays.asList("IMG_1.JPG"), names(album));
		ImagePart photo = image(album, "IMG_1.JPG");
		assertEquals("IMG_1.CR2", photo.getRaw());
		assertEquals(1, photo.getRating());
		assertEquals("A pair", photo.getComment());

		String before = get(PATH, "json", SharingFixture.ALICE).body();
		assertEquals(HttpServletResponse.SC_OK, put(PATH, before, SharingFixture.ALICE).status());
		restartServer();
		assertEquals(before, get(PATH, "json", SharingFixture.ALICE).body());
	}

	public void testAnOlderSidecarWithoutTheFieldReadsUnchanged() throws Exception {
		Path dir = _base.resolve(ALBUM);
		Files.write(dir.resolve("a.jpg"), RawFixtures.quadrants(60, 40));
		Files.write(dir.resolve("index.json"), ("[\"AlbumInfo\",{\"title\":\"Raw\",\"parts\":["
			+ "[\"ImagePart\",{\"kind\":\"IMAGE\",\"name\":\"a.jpg\",\"date\":7,\"width\":60,\"height\":40,"
			+ "\"rating\":1,\"privacy\":0,\"comment\":\"Old\",\"camera\":\"\"}]]}]")
			.getBytes(StandardCharsets.UTF_8));
		ImagePart image = image(album(), "a.jpg");
		assertEquals("", image.getRaw());
		assertEquals(7, image.getDate());
		assertEquals(1, image.getRating());
		assertEquals("Old", image.getComment());
	}

	// --- Helpers. ---

	/** The JPEG of the pair: the quadrants with the fixtures' EXIF. */
	private static byte[] jpeg() throws IOException {
		return RawFixtures.withExif(RawFixtures.stored(120, 80, 1), RawFixtures.exifTiff(1));
	}

	private MoveResult movePair(String pathInfo, String target, String... names) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "move");
		FakeResponse response = post(pathInfo, names(target, names), SharingFixture.ALICE, parameters);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		return MoveResult.readMoveResult(reader(response.body()));
	}

	private static String names(String target, String... names) {
		return "{\"target\":\"" + target + "\",\"names\":["
			+ Arrays.stream(names).map(n -> "{\"name\":\"" + n + "\"}").collect(Collectors.joining(",")) + "]}";
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

	private AlbumInfo album() throws Exception {
		return album(PATH);
	}

	private AlbumInfo album(String path) throws Exception {
		FakeResponse response = get(path, "json", SharingFixture.ALICE);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		return (AlbumInfo) Resource.readResource(reader(response.body()));
	}

	private static String json(AlbumInfo album) throws IOException {
		java.io.StringWriter out = new java.io.StringWriter();
		try (de.haumacher.msgbuf.json.JsonWriter json =
			new de.haumacher.msgbuf.json.JsonWriter(new de.haumacher.msgbuf.server.io.WriterAdapter(out))) {
			album.writeTo(json);
		}
		return out.toString();
	}

	private static List<String> names(AlbumInfo album) {
		List<String> result = new ArrayList<>();
		for (ImagePart image : RawPairs.images(album)) {
			result.add(image.getName());
		}
		return result;
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

	private static String read(Path file) throws IOException {
		return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
	}

	private static void assertPicture(FakeResponse response, int width, int height) throws IOException {
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		BufferedImage picture = ImageIO.read(new ByteArrayInputStream(response.bodyBytes()));
		assertNotNull("A JPEG", picture);
		assertEquals(width, picture.getWidth());
		assertEquals(height, picture.getHeight());
		TestHeifDecoder.assertQuadrants(picture);
	}
}
