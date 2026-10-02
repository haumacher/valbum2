/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.drew.metadata.exif.ExifIFD0Directory;
import de.haumacher.imageServer.cache.ImageData;
import de.haumacher.imageServer.cache.WebpMetadata;
import de.haumacher.imageServer.faces.Originals;
import de.haumacher.imageServer.heif.TestHeifDecoder;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import junit.framework.TestCase;

/**
 * Test case for the memory rule and the GIF canvas of {@link PictureReader}, see issue #207.
 *
 * <p>
 * <code>lossless-4mp.webp</code> is a 2400 &times; 1600 lossless WebP of 50&nbsp;KB whose decoder
 * holds the whole raster: {@value PictureReader#LOSSLESS_BYTES_PER_PIXEL} bytes a pixel are
 * reserved for it, 22&nbsp;MB.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestPictureReader extends TestCase {

	private static final File FIXTURES = TestWebpGif.FIXTURES;

	private static final File LARGE = new File(FIXTURES, "lossless-4mp.webp");

	private static final long LARGE_BYTES = PictureReader.LOSSLESS_BYTES_PER_PIXEL * 2400L * 1600L;

	private static final File JPEG =
		new File("src/test/fixtures/test-album/2005-08-24 Blumen und Fliegen/IMG_0417.JPG");

	private static final String OK = "OK";

	private static final String REFUSED = "REFUSED ";

	private static final String ESCAPED = "ESCAPED ";

	private Path _dir;

	private int _permits;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_permits = PreviewCache.permitCount();
		_dir = Files.createTempDirectory("valbum-picture-reader");
		PictureReader.resetStatistics();
	}

	@Override
	protected void tearDown() throws Exception {
		PictureReader.reservedHook = null;
		PictureReader.resetBudget();
		PreviewCache.setPermitCount(_permits);
		try (java.util.stream.Stream<Path> files = Files.walk(_dir)) {
			files.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		super.tearDown();
	}

	public void testTheCostOfAWebpDecodeIsReadFromItsChunks() throws Exception {
		assertEquals(PictureReader.LOSSY_BYTES_PER_PIXEL, PictureReader.webpBytesPerPixel(fixture("lossy.webp")));
		assertEquals(PictureReader.LOSSY_BYTES_PER_PIXEL, PictureReader.webpBytesPerPixel(fixture("exif.webp")));
		assertEquals(PictureReader.LOSSY_BYTES_PER_PIXEL, PictureReader.webpBytesPerPixel(fixture("portrait.webp")));
		assertEquals(PictureReader.LOSSY_BYTES_PER_PIXEL + PictureReader.ALPHA_BYTES_PER_PIXEL,
			PictureReader.webpBytesPerPixel(fixture("lossy-alpha.webp")));
		assertEquals(PictureReader.LOSSLESS_BYTES_PER_PIXEL, PictureReader.webpBytesPerPixel(fixture("alpha.webp")));
		// The first frame of a lossless animation.
		assertEquals(PictureReader.LOSSLESS_BYTES_PER_PIXEL,
			PictureReader.webpBytesPerPixel(fixture("animated.webp")));
		assertEquals(PictureReader.LOSSLESS_BYTES_PER_PIXEL, PictureReader.webpBytesPerPixel(LARGE));

		// Where an animation's first frame lies on its canvas.
		assertTrue(java.util.Arrays.equals(new int[] { 96, 64, 32, 16 },
			PictureReader.webpAnimation(fixture("offset.webp"))));
		assertTrue(java.util.Arrays.equals(new int[] { 96, 64, 0, 0 },
			PictureReader.webpAnimation(fixture("animated.webp"))));
		assertNull(PictureReader.webpAnimation(fixture("lossy.webp")));
		assertNull(PictureReader.webpAnimation(fixture("lossy-alpha.webp")));

		try (PictureReader picture = PictureReader.open(LARGE)) {
			assertEquals(2400, picture.getWidth());
			assertEquals(1600, picture.getHeight());
			assertEquals(LARGE_BYTES, picture.getWholeRasterBytes());
		}
		// A reader that subsamples while it decodes reserves nothing.
		for (File file : new File[] { JPEG, fixture("still.gif"), fixture("offset.gif") }) {
			try (PictureReader picture = PictureReader.open(file)) {
				assertEquals(file.getName(), 0, picture.getWholeRasterBytes());
			}
		}
	}

	public void testAPictureLargerThanTheBudgetIsRefusedWithAReason() throws Exception {
		PictureReader.setBudget(LARGE_BYTES / 2);
		File file = copy(LARGE);
		try {
			PreviewCache.createPreview(file);
			fail("Refused: it needs more than the budget.");
		} catch (PreviewException ex) {
			assertTrue(ex.getCause() instanceof PictureTooLargeException);
			String message = ex.getMessage();
			assertTrue(message, message.contains("'lossless-4mp.webp'"));
			assertTrue(message, message.contains("2400 × 1600"));
			assertTrue(message, message.contains("about " + (LARGE_BYTES + (1 << 20) - 1) / (1 << 20) + " MB"));
			assertTrue(message, message.contains("-Xmx"));
		}
		File cache = new File(file.getParentFile(), PreviewCache.CACHE_DIRECTORY_NAME);
		String[] left = cache.list();
		assertTrue("Nothing is left behind: " + java.util.Arrays.toString(left), left == null || left.length == 0);
		assertEquals("Nothing stays reserved.", 0, PictureReader.peakReserved());

		// The face index's look at the original obeys the same rule.
		try {
			Originals.decodeLongSide(file, 0, 0, 2400, 1600, 600);
			fail("Refused: it needs more than the budget.");
		} catch (PictureTooLargeException ex) {
			assertTrue(ex.getMessage(), ex.getMessage().contains("2400 × 1600"));
		}

		// Whatever is bounded by the preview needs no budget.
		File jpeg = copy(JPEG);
		assertNotNull(ImageIO.read(PreviewCache.createPreview(jpeg)));

		// With the budget the picture is shown.
		PictureReader.setBudget(LARGE_BYTES);
		BufferedImage preview = ImageIO.read(PreviewCache.createPreview(file));
		assertEquals(900, preview.getWidth());
		assertEquals(600, preview.getHeight());
		TestHeifDecoder.assertQuadrants(preview);
	}

	public void testDecodesThatDoNotFitTogetherWaitForEachOther() throws Exception {
		// Room for one at a time, permits for both.
		PictureReader.setBudget(LARGE_BYTES + LARGE_BYTES / 2);
		PreviewCache.setPermitCount(4);
		File first = copy(LARGE, "a.webp");
		File second = copy(LARGE, "b.webp");

		CountDownLatch holding = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		PictureReader.reservedHook = () -> {
			if (holding.getCount() > 0) {
				holding.countDown();
				try {
					release.await(1, TimeUnit.MINUTES);
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}
		};

		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread one = start(first, failure);
		assertTrue(holding.await(1, TimeUnit.MINUTES));
		Thread two = start(second, failure);

		long deadline = System.currentTimeMillis() + 60_000;
		while (PictureReader.waits() == 0 && System.currentTimeMillis() < deadline) {
			Thread.sleep(5);
		}
		assertEquals("The second waits for the memory instead of failing.", 1, PictureReader.waits());
		assertTrue(two.isAlive());
		release.countDown();
		one.join(60_000);
		two.join(60_000);
		assertNull(String.valueOf(failure.get()), failure.get());
		assertEquals("Never more than one at a time.", LARGE_BYTES, PictureReader.peakReserved(), 1024);
		TestHeifDecoder.assertQuadrants(ImageIO.read(PreviewCache.createPreview(first)));
		TestHeifDecoder.assertQuadrants(ImageIO.read(PreviewCache.createPreview(second)));
	}

	/**
	 * The default budget is half the heap, and a decode that runs out of memory after all is a
	 * refusal, never an {@link OutOfMemoryError}: each in a JVM of its own heap size.
	 */
	public void testTheDefaultBudgetIsHalfTheHeapAndNoOutOfMemoryErrorEscapes() throws Exception {
		File file = copy(LARGE);
		// 22 MB reserved: a heap of 48 MB lends about 23 (the JVM counts one survivor space out of
		// its -Xmx), one of 40 MB about 19.
		assertEquals(OK, child(file, "-Xmx48m"));
		String refused = child(file, "-Xmx40m");
		assertTrue(refused, refused.startsWith(REFUSED) && refused.contains("whose decoder needs memory")
			&& refused.contains("this server lets one picture take (half of its"));
		// A budget larger than the heap: the decode runs out of memory, and that is answered too.
		String exhausted = child(file, "-Xmx18m", "-D" + PictureReader.BUDGET_PROPERTY + "=1000");
		assertTrue(exhausted, exhausted.startsWith(REFUSED) && exhausted.contains("could not be decoded"));
	}

	public void testWebpMetadataAreThoseOfMetadataExtractorWithoutThePicture() throws Exception {
		for (String name : new String[] { "lossy.webp", "alpha.webp", "lossy-alpha.webp", "exif.webp",
			"animated.webp", "offset.webp", "portrait.webp", "lossless-4mp.webp" }) {
			File file = fixture(name);
			assertTrue(name, WebpMetadata.isWebp(file));
			assertEquals(name, tags(ImageMetadataReader.readMetadata(file)), tags(ImageData.readMetadata(file)));
		}
		Metadata exif = WebpMetadata.read(fixture("exif.webp"));
		assertEquals(6, exif.getFirstDirectoryOfType(ExifIFD0Directory.class).getInt(ExifIFD0Directory.TAG_ORIENTATION));
		assertFalse(WebpMetadata.isWebp(JPEG));
		assertFalse(WebpMetadata.isWebp(fixture("still.gif")));
	}

	/**
	 * TwelveMonkeys' buffered stream serves every file, JPEG included: measured no slower for a
	 * JPEG or PNG preview and five times faster for a WebP than ImageIO's own, so it stays.
	 */
	public void testFilesAreReadThroughTheBufferedStream() throws Exception {
		try (ImageInputStream in = ImageIO.createImageInputStream(JPEG)) {
			assertTrue(in.getClass().getName(), in.getClass().getName().startsWith("com.twelvemonkeys."));
		}
	}

	// --- Helpers. ---

	private static Thread start(File file, AtomicReference<Throwable> failure) {
		Thread thread = new Thread(() -> {
			try {
				PreviewCache.createPreview(file);
			} catch (Throwable ex) {
				failure.compareAndSet(null, ex);
			}
		});
		thread.start();
		return thread;
	}

	/** Every directory's name and tags, without those only metadata-extractor's file reader adds. */
	private static List<String> tags(Metadata metadata) {
		List<String> result = new ArrayList<>();
		for (Directory directory : metadata.getDirectories()) {
			String name = directory.getName();
			if (name.equals("File") || name.equals("File Type")) {
				continue;
			}
			for (Tag tag : directory.getTags()) {
				result.add(name + ": " + tag.getTagName() + " = " + tag.getDescription());
			}
			directory.getErrors().forEach(result::add);
		}
		return result;
	}

	private String child(File file, String... options) throws Exception {
		String program = ProcessHandle.current().info().command().orElse(
			System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
		List<String> command = new ArrayList<>();
		command.add(program);
		command.add("-XX:+UseSerialGC");
		command.addAll(java.util.Arrays.asList(options));
		command.add("-cp");
		command.add(System.getProperty("java.class.path"));
		command.add(getClass().getName());
		File copy = copy(file, "child-" + System.nanoTime() + ".webp");
		command.add(copy.getAbsolutePath());
		ProcessBuilder builder = new ProcessBuilder(command);
		builder.redirectErrorStream(true);
		Process process = builder.start();
		byte[] output = process.getInputStream().readAllBytes();
		assertTrue("The child JVM did not finish.", process.waitFor(5, TimeUnit.MINUTES));
		String text = new String(output, StandardCharsets.UTF_8);
		for (String line : text.split("\n")) {
			if (line.equals(OK) || line.startsWith(REFUSED) || line.startsWith(ESCAPED)) {
				return line;
			}
		}
		fail("No answer from the child JVM: " + text);
		return null;
	}

	/** The child: makes the preview of the given file and says how it went. */
	public static void main(String[] args) {
		try {
			PreviewCache.createPreview(new File(args[0]));
			System.out.println(OK);
		} catch (PreviewException ex) {
			System.out.println(REFUSED + ex.getMessage());
		} catch (Throwable ex) {
			System.out.println(ESCAPED + ex);
		}
	}

	private static File fixture(String name) {
		return new File(FIXTURES, name);
	}

	private File copy(File source) throws Exception {
		return copy(source, source.getName());
	}

	private File copy(File source, String name) throws Exception {
		File file = _dir.resolve(name).toFile();
		Files.copy(source.toPath(), file.toPath());
		return file;
	}
}
