/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.cache;

import de.haumacher.imageServer.PathInfo;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.Resource;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
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
 * Test case for the directory watches of {@link ResourceCache}, see issue #235.
 *
 * <p>
 * A watch is an operating-system resource with a system-wide limit; a large library must not
 * spend one per folder ever opened. And the cache is used from request threads and from the
 * watcher's own thread at once.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestResourceCacheWatches extends TestCase {

	private Path _root;

	private final List<ResourceCache> _caches = new ArrayList<>();

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_root = Files.createTempDirectory("valbum-watches");
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
	 * The watches are bounded on their own, not by the albums held (issue #245): a held album that
	 * gave up its watch for room stays held, and is read again when its stamp moves.
	 */
	public void testWatchesAreBoundedNotTheAlbumsHeld() throws Exception {
		ResourceCache cache = cache(5);
		List<File> folders = new ArrayList<>();
		for (int n = 0; n < 20; n++) {
			File folder = album("album" + n, 1);
			folders.add(folder);
			assertTrue(cache.lookup(path(folder)) instanceof AlbumInfo);
			assertTrue("Never more watches than allowed: " + cache.watchCount(), cache.watchCount() <= 5);
		}
		assertEquals("Every album is held.", 20, cache.size());
		long loads = cache.loadCount();
		for (File folder : folders) {
			assertEquals(1, ((AlbumInfo) cache.lookup(path(folder))).getParts().size());
		}
		assertEquals("An unwatched album whose stamp stands is not read again.", loads, cache.loadCount());

		File oldest = folders.get(0);
		photo(new File(oldest, "added.jpg"), 7);
		assertTrue(oldest.setLastModified(oldest.lastModified() + 5000));
		assertEquals("The unwatched album's stamp moved: it is read again.", 2,
			((AlbumInfo) cache.lookup(path(oldest))).getParts().size());
		assertTrue(cache.watchCount() <= 5);
	}

	/** An album evicted for the budget gives up its watch, see issue #235. */
	public void testEvictingAnAlbumDropsItsWatch() throws Exception {
		ResourceCache cache = new ResourceCache(ImageData.Analysis.NONE, null, 10, 3 * ResourceCache.BYTES_PER_PART);
		_caches.add(cache);
		for (int n = 0; n < 20; n++) {
			File folder = album("album" + n, 1);
			assertTrue(cache.lookup(path(folder)) instanceof AlbumInfo);
			assertTrue("Within the budget: " + cache.size(), cache.size() <= 3);
			assertTrue("No watch beyond the albums held: " + cache.watchCount(), cache.watchCount() <= cache.size());
		}
	}

	public void testAFolderThatChangedKeepsBeingWatchedWithinTheBound() throws Exception {
		ResourceCache cache = cache(3);
		List<File> folders = new ArrayList<>();
		for (int n = 0; n < 6; n++) {
			folders.add(album("a" + n, 1));
		}
		for (File folder : folders) {
			cache.lookup(path(folder));
			// Every folder reports a change and is dropped; its watch stays a while.
			photo(new File(folder, "more.jpg"), n(folder));
			cache.processEvents();
			assertTrue("Within the bound: " + cache.watchCount(), cache.watchCount() <= 3);
		}
	}

	public void testConcurrentLoadsAndEventsDoNotThrow() throws Exception {
		ResourceCache cache = cache(8);
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
								cache.processEvents();
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
		cache.processEvents();
		assertTrue("Within the bound after the storm: " + cache.watchCount(), cache.watchCount() <= 8);
	}

	public void testAFolderThatCannotBeWatchedIsReadAgainWhenItsStampMoves() throws Exception {
		ResourceCache cache = cache(10);
		File folder = album("unwatched", 1);
		// A closed watcher refuses every watch, as a watcher at the system's limit does.
		cache.close();
		assertEquals(1, ((AlbumInfo) cache.lookup(path(folder))).getParts().size());
		assertEquals(0, cache.watchCount());

		photo(new File(folder, "added.jpg"), 7);
		assertTrue(folder.setLastModified(folder.lastModified() + 5000));

		assertEquals("The folder's stamp moved: it is read again.", 2,
			((AlbumInfo) cache.lookup(path(folder))).getParts().size());
	}

	/**
	 * A folder read while empty is a listing; the first video or photograph copied into it is asked
	 * for before the watcher reports it (the CI failure of 48f317d): the file is answered, never a
	 * cast of the listing to an album.
	 */
	public void testAFileArrivingInAFolderCachedAsAListingIsAnswered() throws Exception {
		ResourceCache cache = cache(10);
		File folder = album("empty", 0);
		// No watch, and a stamp that does not move: the listing stays cached, as it does while a
		// watcher has not reported the arrival yet.
		cache.close();
		long stamp = folder.lastModified();
		assertFalse(cache.lookup(path(folder)) instanceof AlbumInfo);

		File arrived = new File(folder, "arrived.jpg");
		photo(arrived, 3);
		assertTrue(folder.setLastModified(stamp));

		Resource answer = cache.lookup(path(arrived));
		assertTrue("The file is answered: " + answer, answer instanceof ImagePart);
		assertEquals("arrived.jpg", ((ImagePart) answer).getName());
		assertTrue("The folder is an album now.", cache.lookup(path(folder)) instanceof AlbumInfo);

		// A file of no media kind in a folder that stays a listing is nothing, not a failure.
		File note = new File(album("notes", 0), "readme.txt");
		assertTrue(note.createNewFile());
		assertNull(cache.lookup(path(note)));
	}

	public void testANewFolderInAWatchedOneIsNoticedWithEverythingInIt() throws Exception {
		ResourceCache cache = cache(10);
		List<String> noticed = Collections.synchronizedList(new ArrayList<>());
		CountDownLatch seen = new CountDownLatch(1);
		ResourceCache.FolderObserver observer = (folder, tree) -> {
			if (folder.getName().equals("copied") && tree) {
				noticed.add(folder.getName());
				seen.countDown();
			}
		};
		ResourceCache.addObserver(observer);
		try {
			assertTrue(cache.lookup(new PathInfo(_root)) instanceof ListingInfo);
			File copied = new File(_root.toFile(), "copied");
			assertTrue(copied.mkdir());
			// Nobody requests anything: the watcher's own thread notices.
			assertTrue("A new folder must be noticed without a request.", seen.await(20, TimeUnit.SECONDS));
		} finally {
			ResourceCache.removeObserver(observer);
		}
	}

	public void testLoadingAnAlbumIsNoticed() throws Exception {
		ResourceCache cache = cache(10);
		File folder = album("looked-at", 2);
		List<File> noticed = new CopyOnWriteArrayList<>();
		ResourceCache.FolderObserver observer = (dir, tree) -> noticed.add(dir);
		ResourceCache.addObserver(observer);
		try {
			cache.lookup(path(folder));
		} finally {
			ResourceCache.removeObserver(observer);
		}
		assertTrue("A loaded album is noticed: " + noticed, noticed.contains(folder));
	}

	/**
	 * A permission fixed with <code>chmod</code> is a change a watch reports (issue #251): the
	 * background work retries a folder that failed for it at once.
	 */
	public void testAChangeOfPermissionsIsReportedAsAChange() throws Exception {
		ResourceCache cache = cache(10);
		File folder = album("locked", 1);
		File photo = new File(folder, "p0.jpg");
		List<File> changed = new CopyOnWriteArrayList<>();
		CountDownLatch seen = new CountDownLatch(1);
		ResourceCache.FolderObserver observer = new ResourceCache.FolderObserver() {
			@Override
			public void folderNoticed(File dir, boolean tree) {
				// Loading is no change.
			}

			@Override
			public void folderChanged(File dir) {
				changed.add(dir);
				seen.countDown();
			}
		};
		ResourceCache.addObserver(observer);
		try {
			assertTrue(cache.lookup(path(folder)) instanceof AlbumInfo);
			assertTrue("Loading is no change: " + changed, changed.isEmpty());
			assertTrue(photo.setReadable(false, false) && photo.setReadable(true, false));
			assertTrue("A change of permissions is reported.", seen.await(20, TimeUnit.SECONDS));
			assertEquals(folder, changed.get(0));
		} finally {
			ResourceCache.removeObserver(observer);
		}
	}

	// --- Helpers. ---

	private ResourceCache cache(int watches) throws Exception {
		// The albums held are bounded by memory, see issue #245; here by nothing.
		ResourceCache cache = new ResourceCache(ImageData.Analysis.NONE, null, watches, Long.MAX_VALUE / 2);
		_caches.add(cache);
		return cache;
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

	private static int n(File folder) {
		return folder.getName().hashCode();
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
