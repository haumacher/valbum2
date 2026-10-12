/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.cache;

import de.haumacher.imageServer.PathInfo;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Resource;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Test case for how {@link ResourceCache} stays true to the disk, see issues #235 and #253.
 *
 * <p>
 * Before issue #253 the cache watched the folders a request had loaded. A request is a pure read of
 * the sidecars now and watches nothing (the watches are the background's, see
 * {@link de.haumacher.imageServer.pipeline.LibraryWatch}): what the cache holds is dropped when the
 * background records a folder ({@link ResourceCache#recorded(File)}), when the servlet writes a
 * sidecar, and when a sidecar changed behind its back (its stamp).
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestResourceCacheFreshness extends TestCase {

	private Path _root;

	private final List<ResourceCache> _caches = new ArrayList<>();

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_root = Files.createTempDirectory("valbum-freshness");
	}

	@Override
	protected void tearDown() throws Exception {
		for (ResourceCache cache : _caches) {
			cache.close();
		}
		try (Stream<Path> files = Files.walk(_root)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		super.tearDown();
	}

	/**
	 * With a background, a photograph copied in is answered once the background recorded it, and
	 * not before: the request itself looks at nothing but the sidecars.
	 */
	public void testARecordWrittenByTheBackgroundIsAnswered() throws Exception {
		ResourceCache cache = cache(true);
		File folder = album("a", 1);
		record(cache, folder);
		assertEquals(1, ((AlbumInfo) cache.lookup(path(folder))).getParts().size());
		long loads = cache.loadCount();

		photo(new File(folder, "added.jpg"), 7);
		assertEquals("Not recorded yet: not there.", 1, ((AlbumInfo) cache.lookup(path(folder))).getParts().size());

		record(cache, folder);
		assertEquals("Recorded: there.", 2, ((AlbumInfo) cache.lookup(path(folder))).getParts().size());
		assertTrue(cache.loadCount() > loads);
	}

	/** A sidecar edited by hand is read again: its stamp moved. */
	public void testASidecarEditedBehindTheCachesBackIsReadAgain() throws Exception {
		ResourceCache cache = cache(true);
		File folder = album("edited", 1);
		record(cache, folder);
		assertEquals("Edited", ((AlbumInfo) cache.lookup(path(folder))).getTitle());

		Files.write(new File(folder, "index.json").toPath(),
			"[\"AlbumInfo\",{\"title\":\"By hand\"}]".getBytes(StandardCharsets.UTF_8));
		assertEquals("By hand", ((AlbumInfo) cache.lookup(path(folder))).getTitle());
		assertEquals("The photograph the record holds is still there.", 1,
			((AlbumInfo) cache.lookup(path(folder))).getParts().size());
	}

	/**
	 * Without a background, the cache records the folder it is asked for itself: a file copied into
	 * a folder read while empty is answered, never a cast of the listing to an album (the CI
	 * failure of 48f317d).
	 */
	public void testAFileArrivingInAFolderCachedAsAListingIsAnswered() throws Exception {
		ResourceCache cache = cache(false);
		File folder = album("empty", 0);
		assertFalse(cache.lookup(path(folder)) instanceof AlbumInfo);

		File arrived = new File(folder, "arrived.jpg");
		photo(arrived, 3);

		Resource answer = cache.lookup(path(arrived));
		assertTrue("The file is answered: " + answer, answer instanceof ImagePart);
		assertEquals("arrived.jpg", ((ImagePart) answer).getName());
		assertTrue("The folder is an album now.", cache.lookup(path(folder)) instanceof AlbumInfo);

		// A file of no media kind in a folder that stays a listing is nothing, not a failure.
		File note = new File(album("notes", 0), "readme.txt");
		assertTrue(note.createNewFile());
		assertNull(cache.lookup(path(note)));
	}

	public void testConcurrentLoadsRecordsAndInvalidationsDoNotThrow() throws Exception {
		ResourceCache cache = cache(false);
		List<File> folders = new ArrayList<>();
		for (int n = 0; n < 24; n++) {
			folders.add(album("f" + n, 1));
		}
		List<Throwable> failures = new CopyOnWriteArrayList<>();
		AtomicBoolean stop = new AtomicBoolean();
		int threads = 8;
		CountDownLatch done = new CountDownLatch(threads);
		for (int t = 0; t < threads; t++) {
			int seed = t;
			Thread thread = new Thread(() -> {
				Random random = new Random(seed);
				int written = 0;
				try {
					while (!stop.get()) {
						File folder = folders.get(random.nextInt(folders.size()));
						switch (random.nextInt(4)) {
							case 0:
								photo(new File(folder, "t" + seed + "-" + (written++) + ".jpg"), written);
								break;
							case 1:
								ResourceCache.recorded(folder);
								break;
							case 2:
								cache.invalidate(path(folder));
								break;
							default:
								Resource resource = cache.lookup(path(folder));
								assertNotNull(resource);
								break;
						}
					}
				} catch (Throwable ex) {
					failures.add(ex);
				} finally {
					done.countDown();
				}
			});
			thread.start();
		}
		Thread.sleep(2000);
		stop.set(true);
		assertTrue(done.await(30, TimeUnit.SECONDS));
		if (!failures.isEmpty()) {
			AssertionError error = new AssertionError("Concurrent use failed: " + failures);
			error.initCause(failures.get(0));
			throw error;
		}
		for (File folder : folders) {
			String[] files = folder.list((dir, name) -> name.endsWith(".jpg"));
			assertEquals("Every photograph is answered after the storm.", files.length,
				((AlbumInfo) cache.lookup(path(folder))).getParts().size());
		}
	}

	// --- Helpers. ---

	private ResourceCache cache(boolean background) throws Exception {
		// The albums held are bounded by memory, see issue #245; here by nothing.
		ResourceCache cache = new ResourceCache(ImageData.Analysis.NONE, null, Long.MAX_VALUE / 2);
		cache.setBackground(background);
		_caches.add(cache);
		return cache;
	}

	/** What the background does for the given folder. */
	private static void record(ResourceCache cache, File folder) throws Exception {
		AnalysisRecord.update(folder, cache.analyst(), true);
	}

	private PathInfo path(File folder) {
		return new PathInfo(_root, _root.relativize(folder.toPath()));
	}

	private File album(String name, int photos) throws Exception {
		File folder = new File(_root.toFile(), name);
		assertTrue(folder.mkdirs());
		for (int n = 0; n < photos; n++) {
			photo(new File(folder, "p" + n + ".jpg"), n);
		}
		return folder;
	}

	static void photo(File file, int seed) throws Exception {
		Random random = new Random(seed * 31L + file.getName().hashCode());
		BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
		for (int x = 0; x < 8; x++) {
			for (int y = 0; y < 8; y++) {
				image.setRGB(x, y, random.nextInt(0xFFFFFF));
			}
		}
		assertTrue(ImageIO.write(image, "jpg", file));
	}
}
