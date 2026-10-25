package de.haumacher.imageServer;

import de.haumacher.imageServer.faces.Originals;
import de.haumacher.imageServer.faces.Originals.Region;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import junit.framework.TestCase;

/**
 * Probe of issue #312 beside the analysis and the previews: the face detector reads a rectangle of
 * the original's raster ({@link Originals}), and a mislabelled picture must give it the same pixels
 * as the same bytes under their right name &mdash; in both directions.
 */
@SuppressWarnings("javadoc")
public class TestMislabelledPicturesProbe extends TestCase {

	private static final File HEIC_FIXTURES = new File("src/test/fixtures/heic");

	private Path _dir;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_dir = Files.createTempDirectory("mislabelled-probe");
	}

	@Override
	protected void tearDown() throws Exception {
		try (var files = Files.walk(_dir)) {
			files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
		}
		super.tearDown();
	}

	public void testAJpegNamedHeicGivesTheFaceDetectorTheJpegsPixels() throws Exception {
		byte[] jpeg = RawFixtures.withExif(RawFixtures.stored(48, 32, 6), RawFixtures.exifTiff(6));
		assertSameRegions(write("right.jpg", jpeg), write("20220630_141544.heic", jpeg));
	}

	public void testAHeicNamedJpegGivesTheFaceDetectorTheHeicsPixels() throws Exception {
		byte[] heic = Files.readAllBytes(new File(HEIC_FIXTURES, "rotated.heic").toPath());
		assertSameRegions(write("right.heic", heic), write("wrong.jpg", heic));
	}

	private void assertSameRegions(File right, File wrong) throws IOException {
		for (double[] box : new double[][] { { 0, 0, 1, 1 }, { 0.25, 0.1, 0.75, 0.6 } }) {
			Region expected = Originals.decodeLongSide(right, box[0], box[1], box[2], box[3], 64);
			Region actual = Originals.decodeLongSide(wrong, box[0], box[1], box[2], box[3], 64);
			assertEquals(expected.getRawWidth(), actual.getRawWidth());
			assertEquals(expected.getRawHeight(), actual.getRawHeight());
			assertEquals(expected.getLeft(), actual.getLeft());
			assertEquals(expected.getTop(), actual.getTop());
			assertSamePixels(expected.getImage(), actual.getImage());
		}
	}

	private static void assertSamePixels(BufferedImage expected, BufferedImage actual) {
		assertEquals(expected.getWidth(), actual.getWidth());
		assertEquals(expected.getHeight(), actual.getHeight());
		for (int y = 0; y < expected.getHeight(); y++) {
			for (int x = 0; x < expected.getWidth(); x++) {
				assertEquals("pixel " + x + "," + y, expected.getRGB(x, y), actual.getRGB(x, y));
			}
		}
	}

	private File write(String name, byte[] content) throws IOException {
		return Files.write(_dir.resolve(name), content).toFile();
	}
}
