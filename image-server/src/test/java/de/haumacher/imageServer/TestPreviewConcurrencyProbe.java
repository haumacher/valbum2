/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Probe for the single generation of #69, composed with same-named originals in different
 * folders, stale previews and a crashed generation's leftovers.
 */
@SuppressWarnings("javadoc")
public class TestPreviewConcurrencyProbe extends TestCase {

	private Path _dir;

	private final AtomicInteger _started = new AtomicInteger();

	@Override
	protected void setUp() throws Exception {
		_dir = Files.createTempDirectory("valbum-concurrency-probe");
		PreviewCache.setHook(new PreviewCache.Hook() {
			@Override
			public void permitAcquired(File file) {
				// Not observed here.
			}

			@Override
			public void generationStarted(File file) {
				_started.incrementAndGet();
				try {
					Thread.sleep(150);
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}

			@Override
			public void generationFinished(File file) {
				// Not observed here.
			}
		});
	}

	@Override
	protected void tearDown() throws Exception {
		PreviewCache.setHook(null);
		try (Stream<Path> files = Files.walk(_dir)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
	}

	/** Two originals with the same file name in different folders are two previews, not one. */
	public void testSameNameInDifferentFolders() throws Exception {
		File a = write(new File(_dir.toFile(), "a"), "x.jpg", Color.RED);
		File b = write(new File(_dir.toFile(), "b"), "x.jpg", Color.BLUE);

		List<Throwable> failures = concurrently(8, i -> PreviewCache.createPreview(i % 2 == 0 ? a : b));

		assertTrue(failures.toString(), failures.isEmpty());
		assertEquals("One generation per original.", 2, _started.get());
		assertColor(a, Color.RED);
		assertColor(b, Color.BLUE);
	}

	/** A stale preview is regenerated once for every concurrent request, and shows the new content. */
	public void testStalePreviewRegeneratedOnce() throws Exception {
		File folder = new File(_dir.toFile(), "stale");
		File file = write(folder, "y.jpg", Color.RED);
		File preview = PreviewCache.createPreview(file);
		assertColor(file, Color.RED);
		assertEquals(1, _started.get());

		// The original changed after its preview was made: the preview is dated before it.
		write(folder, "y.jpg", Color.GREEN);
		assertTrue(preview.setLastModified(file.lastModified() - 5000));

		List<Throwable> failures = concurrently(8, i -> PreviewCache.createPreview(file));

		assertTrue(failures.toString(), failures.isEmpty());
		assertEquals("Exactly one regeneration.", 2, _started.get());
		assertColor(file, Color.GREEN);
		assertTrue("The new preview is not older than the original.",
			preview.lastModified() >= file.lastModified());
	}

	/** A temporary file left behind by a crashed generation is neither served nor left behind again. */
	public void testCrashLeftoverIsReplaced() throws Exception {
		File folder = new File(_dir.toFile(), "crash");
		File file = write(folder, "z.jpg", Color.BLUE);
		File cacheDir = new File(folder, PreviewCache.CACHE_DIRECTORY_NAME);
		assertTrue(cacheDir.mkdirs());
		File leftover = new File(cacheDir, "preview-z.jpg" + PreviewCache.TMP_SUFFIX);
		Files.write(leftover.toPath(), new byte[] { 1, 2, 3 });

		File preview = PreviewCache.createPreview(file);

		assertEquals("preview-z.jpg", preview.getName());
		assertFalse("The leftover must be gone.", leftover.exists());
		assertColor(file, Color.BLUE);
		assertEquals("Nothing but the preview in the cache directory.", 1, cacheDir.list().length);
	}

	/** A video and an image with the same base name are two previews with different names. */
	public void testVideoBesideImageOfSameName() throws Exception {
		File folder = new File(_dir.toFile(), "mixed");
		File image = write(folder, "MVI_0450.jpg", Color.RED);
		File video = new File(folder, "MVI_0450.mp4");
		Files.copy(new File("src/test/fixtures/test-album/2005-08-24 Blumen und Fliegen/MVI_0450.mp4").toPath(),
			video.toPath());

		List<Throwable> failures = concurrently(6, i -> PreviewCache.createPreview(i % 2 == 0 ? image : video));

		assertTrue(failures.toString(), failures.isEmpty());
		assertEquals(2, _started.get());
		assertEquals("preview-MVI_0450.jpg", PreviewCache.createPreview(image).getName());
		assertEquals("preview-MVI_0450.mp4.jpg", PreviewCache.createPreview(video).getName());
		assertEquals("Still two generations: both are cached now.", 2, _started.get());
	}

	interface Request {
		void run(int index) throws Exception;
	}

	private static List<Throwable> concurrently(int count, Request request) throws Exception {
		CountDownLatch go = new CountDownLatch(1);
		List<Throwable> failures = new ArrayList<>();
		List<Thread> threads = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			int index = i;
			Thread t = new Thread(() -> {
				try {
					go.await();
					request.run(index);
				} catch (Throwable ex) {
					synchronized (failures) {
						failures.add(ex);
					}
				}
			});
			t.start();
			threads.add(t);
		}
		go.countDown();
		for (Thread t : threads) {
			t.join(30000);
		}
		return failures;
	}

	private static File write(File folder, String name, Color color) throws Exception {
		folder.mkdirs();
		BufferedImage image = new BufferedImage(1600, 1200, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(color);
		g.fillRect(0, 0, 1600, 1200);
		g.dispose();
		File file = new File(folder, name);
		assertTrue(ImageIO.write(image, "jpg", file));
		return file;
	}

	private static void assertColor(File original, Color expected) throws Exception {
		BufferedImage preview = ImageIO.read(PreviewCache.createPreview(original));
		assertEquals(800, preview.getWidth());
		Color c = new Color(preview.getRGB(400, 300));
		assertTrue("Expected " + expected + " but got " + c,
			Math.abs(c.getRed() - expected.getRed()) < 40
				&& Math.abs(c.getGreen() - expected.getGreen()) < 40
				&& Math.abs(c.getBlue() - expected.getBlue()) < 40);
	}
}
