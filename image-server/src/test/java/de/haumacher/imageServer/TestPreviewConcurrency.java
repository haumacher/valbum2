/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Test case for the bounded parallelism and the once-only generation of previews, see issue #69.
 *
 * <p>
 * The generation is observed through {@link PreviewCache#setHook(PreviewCache.Hook)}, so what is
 * asserted is counted, not timed: how often a preview was generated, and how many generations ran
 * at the same time.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestPreviewConcurrency extends TestCase {

	/** How long a generation is held up, so that the requests really overlap. */
	private static final long HOLD_MS = 200;

	private Path _dir;

	private int _permits;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_dir = Files.createTempDirectory("valbum-preview-concurrency");
		_permits = PreviewCache.permitCount();
	}

	@Override
	protected void tearDown() throws Exception {
		PreviewCache.setHook(null);
		PreviewCache.setPermitCount(_permits);
		try (Stream<Path> files = Files.walk(_dir)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		super.tearDown();
	}

	/** Sixteen requests for the same uncached preview generate it exactly once. */
	public void testGeneratedOnce() throws Exception {
		File image = image("once.jpg");
		CountingHook hook = new CountingHook(HOLD_MS);
		PreviewCache.setHook(hook);

		List<Throwable> failures = request(16, image);

		assertEquals("Nobody may be refused: " + failures, 0, failures.size());
		assertEquals("The preview must be generated exactly once.", 1, hook.started());
		assertTrue("Every request must see the preview.", preview(image).exists());
		assertNoTempFiles();
	}

	/**
	 * With two permits, eight requests for eight different files never run more than two
	 * generations at the same time — and do reach two, or the limit would not be the reason.
	 */
	public void testPermitsBoundParallelGenerations() throws Exception {
		PreviewCache.setPermitCount(2);
		CountingHook hook = new CountingHook(HOLD_MS);
		PreviewCache.setHook(hook);

		File[] images = new File[8];
		for (int n = 0; n < images.length; n++) {
			images[n] = image("bounded-" + n + ".jpg");
		}

		List<Throwable> failures = request(images.length, images);

		assertEquals("Nobody may be refused: " + failures, 0, failures.size());
		assertEquals("Every file must be generated.", images.length, hook.started());
		assertTrue("More than two generations ran at once: " + hook.maxParallel(),
			hook.maxParallel() <= 2);
		assertEquals("The two permits must be used, or nothing is proven.", 2, hook.maxParallel());
		for (File image : images) {
			assertTrue("Missing preview of " + image.getName(), preview(image).exists());
		}
		assertNoTempFiles();
	}

	/** Serving a preview that is already cached takes no permit and generates nothing. */
	public void testCachedPreviewTakesNoPermit() throws Exception {
		File image = image("cached.jpg");
		assertTrue(PreviewCache.createPreview(image).exists());

		CountingHook hook = new CountingHook(0);
		PreviewCache.setHook(hook);

		List<Throwable> failures = request(4, image);

		assertEquals("Nobody may be refused: " + failures, 0, failures.size());
		assertEquals("A cached preview must not take a permit.", 0, hook.acquired());
		assertEquals("A cached preview must not be generated again.", 0, hook.started());
	}

	/**
	 * A failing generation fails every waiter with the same {@link PreviewException}, and the next
	 * request tries again rather than remembering the failure.
	 */
	public void testFailureReachesEveryWaiterAndIsRetried() throws Exception {
		File broken = new File(_dir.toFile(), "broken.jpg");
		Files.write(broken.toPath(), "This is not a JPEG.".getBytes(StandardCharsets.UTF_8));

		CountingHook hook = new CountingHook(HOLD_MS);
		PreviewCache.setHook(hook);

		List<Throwable> failures = request(16, broken);

		assertEquals("Every request must be refused.", 16, failures.size());
		for (Throwable failure : failures) {
			assertTrue("Expected a PreviewException, got: " + failure, failure instanceof PreviewException);
		}
		assertEquals("The failing generation must have run once.", 1, hook.started());
		assertFalse("A failure must not leave a preview behind.", preview(broken).exists());
		assertNoTempFiles();

		// A failure is not remembered: the next request tries again.
		try {
			PreviewCache.createPreview(broken);
			fail("Expected a PreviewException.");
		} catch (PreviewException expected) {
			// Expected.
		}
		assertEquals("The next request must generate again.", 2, hook.started());
		assertNoTempFiles();
	}

	/**
	 * A video goes through the same permit and the same lock, and leaves nothing behind either.
	 */
	public void testVideoThroughPermit() throws Exception {
		File fixture = new File(
			"src/test/fixtures/test-album/2005-08-24 Blumen und Fliegen/MVI_0450.mp4");
		assertTrue("Missing fixture: " + fixture.getAbsolutePath(), fixture.exists());
		File video = new File(_dir.toFile(), fixture.getName());
		Files.copy(fixture.toPath(), video.toPath());

		CountingHook hook = new CountingHook(HOLD_MS);
		PreviewCache.setHook(hook);

		List<Throwable> failures = request(4, video);

		assertEquals("Nobody may be refused: " + failures, 0, failures.size());
		assertEquals("The video preview must be generated exactly once.", 1, hook.started());
		assertEquals("Only the generating request may take a permit.", 1, hook.acquired());
		// A video preview is a JPEG, so its name carries the image type, see PreviewCache.
		assertTrue("Missing video preview.",
			new File(cacheDir(), "preview-" + video.getName() + ".jpg").exists());
		assertNoTempFiles();
		assertEquals("The original must not be modified.", fixture.length(), video.length());
	}

	/** Runs one request per thread, all starting at the same time. */
	private static List<Throwable> request(int threads, File... files) throws Exception {
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch go = new CountDownLatch(1);
		List<Throwable> failures = new CopyOnWriteArrayList<>();
		List<Thread> workers = new ArrayList<>();
		for (int n = 0; n < threads; n++) {
			File file = files[n % files.length];
			Thread worker = new Thread(() -> {
				ready.countDown();
				try {
					go.await();
					PreviewCache.createPreview(file);
				} catch (Throwable ex) {
					failures.add(ex);
				}
			}, "preview-request-" + n);
			workers.add(worker);
			worker.start();
		}
		assertTrue("Threads did not start.", ready.await(30, java.util.concurrent.TimeUnit.SECONDS));
		go.countDown();
		for (Thread worker : workers) {
			worker.join(60000);
			assertFalse("A request did not finish: " + worker.getName(), worker.isAlive());
		}
		return failures;
	}

	private File preview(File image) {
		return new File(cacheDir(), "preview-" + image.getName());
	}

	private File cacheDir() {
		return new File(_dir.toFile(), PreviewCache.CACHE_DIRECTORY_NAME);
	}

	/** Nothing half-written may be left in the cache directory. */
	private void assertNoTempFiles() {
		String[] leftOver = cacheDir().list((dir, name) -> name.endsWith(PreviewCache.TMP_SUFFIX));
		if (leftOver == null) {
			return;
		}
		assertEquals("Temporary files left behind: " + String.join(", ", leftOver), 0, leftOver.length);
	}

	private File image(String name) throws Exception {
		BufferedImage image = new BufferedImage(1200, 900, BufferedImage.TYPE_3BYTE_BGR);
		Graphics2D g = image.createGraphics();
		g.setColor(new Color(200, 40, 40));
		g.fillRect(0, 0, 1200, 900);
		g.dispose();
		File file = new File(_dir.toFile(), name);
		assertTrue("Cannot write a JPEG.", ImageIO.write(image, "jpg", file));
		return file;
	}

	/**
	 * Counts permits and generations, and holds each generation up for a while so that the
	 * requests really overlap.
	 */
	static final class CountingHook implements PreviewCache.Hook {

		private final long _holdMs;

		private final AtomicInteger _acquired = new AtomicInteger();

		private final AtomicInteger _started = new AtomicInteger();

		private final AtomicInteger _running = new AtomicInteger();

		private final AtomicInteger _maxParallel = new AtomicInteger();

		CountingHook(long holdMs) {
			_holdMs = holdMs;
		}

		int acquired() {
			return _acquired.get();
		}

		int started() {
			return _started.get();
		}

		int maxParallel() {
			return _maxParallel.get();
		}

		@Override
		public void permitAcquired(File file) {
			_acquired.incrementAndGet();
		}

		@Override
		public void generationStarted(File file) {
			_started.incrementAndGet();
			int running = _running.incrementAndGet();
			_maxParallel.accumulateAndGet(running, Math::max);
			if (_holdMs > 0) {
				try {
					Thread.sleep(_holdMs);
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}
		}

		@Override
		public void generationFinished(File file) {
			_running.decrementAndGet();
		}

	}

}
