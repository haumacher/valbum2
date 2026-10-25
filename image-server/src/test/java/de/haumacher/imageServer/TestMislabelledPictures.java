/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.cache.ImageData;
import de.haumacher.imageServer.coded.CodedPictures;
import de.haumacher.imageServer.coded.PictureFormat;
import de.haumacher.imageServer.faces.FaceIndex;
import de.haumacher.imageServer.raw.RawFile;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ImageKind;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Resource;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import javax.imageio.ImageIO;

/**
 * Test case for a picture whose name says another format than its bytes, see issue #312.
 *
 * <p>
 * The report: &quot;'20220630_141544.heic' is no readable HEIF file: the box '␐JF' is cut
 * off.&quot; The file was a JPEG named <code>.heic</code>, as phones and export tools write them;
 * the server picked its reader by the name, so it got no metadata, no preview and no display
 * rendition. A picture's format is what its first bytes say, the name only the fallback where they
 * say nothing recognisable.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestMislabelledPictures extends ShareTestCase {

	/** The author's file name, verbatim. */
	private static final String REPORTED = "20220630_141544.heic";

	private static final File HEIC_FIXTURES = new File("src/test/fixtures/heic");

	private static final String ALBUM = "2022/Mislabelled";

	private static final String PATH = "/" + ALBUM + "/";

	/** Upright size of the JPEG fixture, stored turned by EXIF orientation 6. */
	private static final int WIDTH = 48;

	private static final int HEIGHT = 32;

	private Path _album;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_album = Files.createDirectories(_base.resolve(ALBUM));
	}

	// --- The sniffer. ---

	public void testTheFirstBytesSayTheFormat() throws Exception {
		assertEquals(PictureFormat.JPEG, format(jpeg()));
		assertEquals(PictureFormat.PNG, format(png()));
		assertEquals(PictureFormat.HEIF, format(fixture("single.heic")));
		assertEquals(PictureFormat.AVIF, format(Files.readAllBytes(new File("src/test/fixtures/avif/single.avif").toPath())));
		assertEquals(PictureFormat.JXL, format(Files.readAllBytes(new File("src/test/fixtures/jxl/lossy.jxl").toPath())));
		assertEquals(PictureFormat.RAW, format(RawFixtures.dng(48, 32, 1, 0)));
		assertEquals(PictureFormat.RAW, format(RawFixtures.cr3(48, 32)));
		assertEquals(PictureFormat.RAW, format(RawFixtures.raf(48, 32, 1)));
		assertNull("A video is no picture format.",
			format(Files.readAllBytes(firstFile(new File("src/test/fixtures/video"), ".m4v").toPath())));
		assertNull(format(noise(64)));
		assertNull(format(new byte[0]));
	}

	// --- The reported file: a JPEG named .heic. ---

	public void testTheReportedFileIsDescribedAsTheSameJpegNamedRightly() throws Exception {
		byte[] jpeg = jpeg();
		File heic = write(REPORTED, jpeg);
		File named = write("20220630_141544.jpg", jpeg);

		ImageData mislabelled = ImageData.analyze(AlbumInfo.create(), heic);
		ImageData rightly = ImageData.analyze(AlbumInfo.create(), named);
		assertEquals(ImageKind.IMAGE, mislabelled.getKind());
		assertEquals("Turned by its EXIF orientation 6.", WIDTH, mislabelled.getWidth());
		assertEquals(HEIGHT, mislabelled.getHeight());
		assertEquals(RawFixtures.TAKEN, mislabelled.getDate());
		assertDescribedAlike(rightly, mislabelled);
		assertEquals(FaceIndex.exifOrientation(named), FaceIndex.exifOrientation(heic));

		assertFalse(CodedPictures.handles(heic));
		assertFalse(PreviewCache.needsDisplay(heic));
	}

	public void testTheReportedFileHasAPreviewADisplayRenditionAndItsOwnType() throws Exception {
		byte[] jpeg = jpeg();
		write(REPORTED, jpeg);

		ImagePart part = image(album(), REPORTED);
		assertEquals("", part.getBroken());
		assertEquals(WIDTH, part.getWidth());
		assertEquals(HEIGHT, part.getHeight());
		assertEquals(RawFixtures.TAKEN, part.getDate());

		assertPicture(get(PATH + REPORTED, "tn", SharingFixture.ALICE), WIDTH, HEIGHT);

		// The app asks for the display rendition by the name: answered with the picture itself, as
		// the same bytes named .jpg would be shown.
		FakeResponse display = get(PATH + REPORTED, ImageServlet.DISPLAY_TYPE, SharingFixture.ALICE);
		assertEquals(display.body(), HttpServletResponse.SC_OK, display.status());
		assertEquals("image/jpeg", display.contentType());
		assertTrue(Arrays.equals(jpeg, display.bodyBytes()));
		assertNotNull(ImageIO.read(new ByteArrayInputStream(display.bodyBytes())));

		FakeResponse original = get(PATH + REPORTED, null, SharingFixture.ALICE);
		assertEquals("image/jpeg", original.contentType());
		assertTrue("The original is never touched.",
			Arrays.equals(jpeg, Files.readAllBytes(_album.resolve(REPORTED))));
	}

	/** An earlier build recorded the reported file as broken: this one reads it again. */
	public void testTheReportedFileRecordedBrokenBeforeIsReadAgain() throws Exception {
		File heic = write(REPORTED, jpeg());
		Path cache = Files.createDirectories(_album.resolve(PreviewCache.CACHE_DIRECTORY_NAME));
		Files.writeString(cache.resolve(de.haumacher.imageServer.cache.AnalysisRecord.FILE_NAME),
			"{\"version\":1,\"files\":{\"" + REPORTED + "\":{\"size\":" + heic.length() + ",\"modified\":"
				+ heic.lastModified() + ",\"failure\":\"'" + REPORTED
				+ "' is no readable HEIF file: the box '\\u0010JF' is cut off.\",\"analysis\":1}}}");

		ImagePart part = image(album(), REPORTED);
		assertEquals("", part.getBroken());
		assertEquals(WIDTH, part.getWidth());
	}

	public void testAJpegNamedRawIsAJpeg() throws Exception {
		File dng = write("IMG_1.dng", jpeg());
		assertFalse(RawFile.isRaw(dng));
		assertEquals(WIDTH, ImageData.analyze(AlbumInfo.create(), dng).getWidth());
		assertPicture(get(PATH + "IMG_1.dng", "tn", SharingFixture.ALICE), WIDTH, HEIGHT);
		FakeResponse display = get(PATH + "IMG_1.dng", ImageServlet.DISPLAY_TYPE, SharingFixture.ALICE);
		assertEquals(display.body(), HttpServletResponse.SC_OK, display.status());
		assertEquals("image/jpeg", display.contentType());
		assertEquals("image/jpeg", get(PATH + "IMG_1.dng", null, SharingFixture.ALICE).contentType());

		// A real raw stays a raw by its name.
		assertTrue(RawFile.isRaw(write("IMG_2.dng", RawFixtures.dng(48, 32, 1, 0))));
	}

	// --- The other direction: a HEIC named .jpg. ---

	public void testAHeicNamedJpegIsDescribedAndPreviewedAsTheHeic() throws Exception {
		copyAs("single.heic", "single.jpg");
		copyAs("rotated.heic", "rotated.JPG");
		copyAs("single.heic", "single.heic");

		AlbumInfo album = album();
		assertDescribedAlike(image(album, "single.heic"), image(album, "single.jpg"));
		assertEquals(96, image(album, "single.jpg").getWidth());
		assertEquals(64, image(album, "single.jpg").getHeight());
		assertEquals("Upright, the container's quarter turn applied.", 120, image(album, "rotated.JPG").getWidth());
		assertEquals(180, image(album, "rotated.JPG").getHeight());

		assertQuadrants(get(PATH + "single.jpg", "tn", SharingFixture.ALICE), 96, 64);
		assertQuadrants(get(PATH + "rotated.JPG", "tn", SharingFixture.ALICE), 120, 180);

		// Asked for, its display rendition is made as for the HEIC it is.
		assertQuadrants(get(PATH + "rotated.JPG", ImageServlet.DISPLAY_TYPE, SharingFixture.ALICE), 120, 180);

		FakeResponse original = get(PATH + "single.jpg", null, SharingFixture.ALICE);
		assertEquals("image/heic", original.contentType());
		assertTrue(Arrays.equals(fixture("single.heic"), original.bodyBytes()));
	}

	// --- A PNG named .jpg. ---

	public void testAPngNamedJpegHasItsPreview() throws Exception {
		write("pic.jpg", png());
		ImagePart part = image(album(), "pic.jpg");
		assertEquals("", part.getBroken());
		assertEquals(40, part.getWidth());
		assertEquals(30, part.getHeight());
		assertPicture(get(PATH + "pic.jpg", "tn", SharingFixture.ALICE), 40, 30);
		assertEquals("image/png", get(PATH + "pic.jpg", null, SharingFixture.ALICE).contentType());
	}

	// --- No picture at all. ---

	public void testNoiseNamedHeicSaysWhatItIsNotAndFailsNothingElse() throws Exception {
		write("a.jpg", jpeg());
		File noise = write("x.heic", noise(4096));
		try {
			ImageData.analyze(AlbumInfo.create(), noise);
			fail("Noise is no picture.");
		} catch (IOException | com.drew.imaging.ImageProcessingException ex) {
			assertRefusal(ex.getMessage());
		}

		AlbumInfo album = album();
		assertEquals("", image(album, "a.jpg").getBroken());
		String broken = image(album, "x.heic").getBroken();
		assertRefusal(broken);
		assertPicture(get(PATH + "a.jpg", "tn", SharingFixture.ALICE), WIDTH, HEIGHT);
	}

	// --- The sidecar. ---

	public void testTheSidecarRoundTripsAnAlbumOfMislabelledPictures() throws Exception {
		write(REPORTED, jpeg());
		copyAs("rotated.heic", "rotated.jpg");
		write("pic.jpg", png());
		write("x.heic", noise(512));
		String before = get(PATH, "json", SharingFixture.ALICE).body();

		FakeResponse stored = put(PATH, before, SharingFixture.ALICE);
		assertEquals(stored.body(), HttpServletResponse.SC_OK, stored.status());
		restartServer();

		assertEquals(before, get(PATH, "json", SharingFixture.ALICE).body());
		AlbumInfo again = album();
		assertEquals(WIDTH, image(again, REPORTED).getWidth());
		assertEquals(120, image(again, "rotated.jpg").getWidth());
	}

	// --- Composing with raw pairs. ---

	/**
	 * A pair is found by the names, as it always was: the JPEG named <code>.heic</code> beside the
	 * raw of its base name is the photograph, the raw its companion &mdash; exactly as a real HEIC of
	 * that name. What the companion's bytes are does not decide the pair, only how it is read.
	 */
	public void testAMislabelledHeicBesideItsRawIsThePhotographOfThePair() throws Exception {
		write(REPORTED, jpeg());
		write("20220630_141544.dng", RawFixtures.dng(48, 32, 1, 0));
		copyAs("single.heic", "real.heic");
		write("real.dng", RawFixtures.dng(48, 32, 1, 0));

		AlbumInfo album = album();
		assertEquals(Arrays.asList(REPORTED, "real.heic"), names(album));
		assertEquals("20220630_141544.dng", image(album, REPORTED).getRaw());
		assertEquals("real.dng", image(album, "real.heic").getRaw());
		assertEquals(WIDTH, image(album, REPORTED).getWidth());
		assertPicture(get(PATH + REPORTED, "tn", SharingFixture.ALICE), WIDTH, HEIGHT);
	}

	// --- Helpers. ---

	private static void assertRefusal(String message) {
		assertTrue(message, message.contains("'x.heic' is neither HEIF nor any other picture format: it starts with "));
	}

	private static void assertDescribedAlike(ImagePart expected, ImagePart actual) {
		assertEquals(expected.getKind(), actual.getKind());
		assertEquals(expected.getWidth(), actual.getWidth());
		assertEquals(expected.getHeight(), actual.getHeight());
		assertEquals(expected.getDate(), actual.getDate());
		assertEquals(expected.getOrientation(), actual.getOrientation());
		assertEquals(expected.getCamera(), actual.getCamera());
		assertNotNull(actual.getLocation());
		assertEquals(expected.getLocation().getLatitude(), actual.getLocation().getLatitude(), 1e-9);
		assertEquals(expected.getLocation().getLongitude(), actual.getLocation().getLongitude(), 1e-9);
		assertEquals(expected.getComment(), actual.getComment());
	}

	/** A JPEG of the upright size {@link #WIDTH} x {@link #HEIGHT} stored turned, with EXIF orientation 6, a date, a camera and a position. */
	private static byte[] jpeg() throws IOException {
		return RawFixtures.withExif(RawFixtures.stored(WIDTH, HEIGHT, 6), RawFixtures.exifTiff(6));
	}

	private static byte[] png() throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_ARGB), "png", out);
		return out.toByteArray();
	}

	private static byte[] noise(int length) {
		byte[] result = new byte[length];
		new Random(312).nextBytes(result);
		// Never by chance a format's signature.
		result[0] = 0x3F;
		result[4] = 0x11;
		return result;
	}

	private static PictureFormat format(byte[] bytes) {
		return PictureFormat.of(bytes, Math.min(bytes.length, PictureFormat.HEAD));
	}

	private static File firstFile(File dir, String suffix) {
		for (File file : dir.listFiles()) {
			if (file.getName().endsWith(suffix)) {
				return file;
			}
		}
		fail("No " + suffix + " in " + dir);
		return null;
	}

	private File write(String name, byte[] content) throws IOException {
		return Files.write(_album.resolve(name), content).toFile();
	}

	private void copyAs(String fixture, String name) throws IOException {
		Files.copy(new File(HEIC_FIXTURES, fixture).toPath(), _album.resolve(name));
	}

	private static byte[] fixture(String name) throws IOException {
		return Files.readAllBytes(new File(HEIC_FIXTURES, name).toPath());
	}

	private AlbumInfo album() throws Exception {
		FakeResponse response = get(PATH, "json", SharingFixture.ALICE);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		return (AlbumInfo) Resource.readResource(reader(response.body()));
	}

	private static java.util.List<String> names(AlbumInfo album) {
		java.util.List<String> result = new java.util.ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				result.add(((ImagePart) part).getName());
			}
		}
		java.util.Collections.sort(result);
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

	private static BufferedImage assertPicture(FakeResponse response, int width, int height) throws IOException {
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		BufferedImage picture = ImageIO.read(new ByteArrayInputStream(response.bodyBytes()));
		assertNotNull("A picture", picture);
		assertEquals(width, picture.getWidth());
		assertEquals(height, picture.getHeight());
		return picture;
	}

	private static void assertQuadrants(FakeResponse response, int width, int height) throws IOException {
		de.haumacher.imageServer.heif.TestHeifDecoder.assertQuadrants(assertPicture(response, width, height));
	}
}
