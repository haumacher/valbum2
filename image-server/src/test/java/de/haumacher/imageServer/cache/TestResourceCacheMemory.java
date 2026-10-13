/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.cache;

import de.haumacher.imageServer.PathInfo;
import de.haumacher.imageServer.PhotoSearch;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import java.io.File;
import java.lang.ref.Reference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Test case for the memory bound of {@link ResourceCache}, see issue #245.
 *
 * <p>
 * The albums held are weighed by their parts against a budget in bytes: a search over a large
 * library reads each album from disk once, as long as the budget holds them all. (The cache watches
 * nothing since issue #253; the watches are the background's, see
 * {@link de.haumacher.imageServer.pipeline.LibraryWatch}.)
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestResourceCacheMemory extends TestCase {

	private Path _root;

	private final List<ResourceCache> _caches = new ArrayList<>();

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_root = Files.createTempDirectory("valbum-cache-memory");
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
	 * The heap one part of a cached album takes: what {@link ResourceCache#BYTES_PER_PART} is
	 * taken from. 200 albums of 50 richly described photographs, the used heap after collecting
	 * before and after they are in the cache.
	 */
	public void testHeapPerPart() throws Exception {
		int albums = 200;
		int photos = 50;
		List<PathInfo> paths = new ArrayList<>();
		for (int a = 0; a < albums; a++) {
			paths.add(path(album("Album " + a, photos, true)));
		}
		ResourceCache cache = cache(Long.MAX_VALUE / 2);
		// Everything the first read touches (classes, interned names) is there before measuring.
		cache.lookup(path(album("Warm-up", photos, true)));

		long before = heap();
		for (PathInfo path : paths) {
			assertEquals(photos, ((AlbumInfo) cache.lookup(path)).getParts().size());
		}
		long after = heap();
		Reference.reachabilityFence(cache);
		assertEquals(albums + 1, cache.size());

		long perPart = (after - before) / (albums * photos);
		System.out.println("Heap per cached part: " + perPart + " bytes (" + albums + " albums of " + photos
			+ " photographs; the cache counts " + ResourceCache.BYTES_PER_PART + ")");
		assertTrue("A part takes some heap: " + perPart, perPart > 100);
		assertTrue("A part takes no more heap than the cache counts: " + perPart,
			perPart <= ResourceCache.BYTES_PER_PART);
	}

	/**
	 * A library all of whose albums the budget holds: a search reads
	 * each album from disk once, and the second search reads none.
	 */
	public void testALibraryTheBudgetHoldsIsReadOnce() throws Exception {
		int albums = 1500;
		library(albums);
		ResourceCache cache = cache(albums * 3L * ResourceCache.BYTES_PER_PART * 2);

		List<String> first = search(cache);
		long loads = cache.loadCount();
		assertEquals(albums * 3, first.size());
		assertEquals("Each album read once.", albums, loads);
		assertEquals("All albums held.", albums, cache.size());

		List<String> second = search(cache);
		assertEquals(first, second);
		assertEquals("Nothing read again.", loads, cache.loadCount());

		// A photograph added to a held album is answered: the cache has no background here and
		// records the folder it is asked for, see issue #253.
		File oldest = new File(_root.toFile(), "Year 0/Album 0");
		TestResourceCacheFreshness.photo(new File(oldest, "added.jpg"), 3);
		assertEquals(4, ((AlbumInfo) cache.lookup(path(oldest))).getParts().size());
		assertEquals(albums * 3 + 1, search(cache).size());
	}

	/** A budget too small for the library: some albums are read again, and every answer is right. */
	public void testALibraryBeyondTheBudgetIsReadAgainAndAnsweredRight() throws Exception {
		int albums = 1500;
		library(albums);
		long budget = 300 * 3L * ResourceCache.BYTES_PER_PART;
		ResourceCache cache = cache(budget);

		List<String> first = search(cache);
		long loads = cache.loadCount();
		assertEquals(albums * 3, first.size());
		assertTrue("Within the budget: " + cache.weight(), cache.weight() * ResourceCache.BYTES_PER_PART <= budget);
		assertTrue("Not all held: " + cache.size(), cache.size() < albums);

		List<String> second = search(cache);
		assertEquals(first, second);
		assertTrue("Some read again.", cache.loadCount() > loads);
	}

	/**
	 * Review probe: an album heavier than the whole budget is answered right on every lookup, read
	 * again each time rather than refused or held beyond the budget, and the small album beside it
	 * stays cached.
	 */
	public void testProbeAnAlbumHeavierThanTheBudgetIsStillAnswered() throws Exception {
		ResourceCache cache = cache(300L * ResourceCache.BYTES_PER_PART);
		File big = album("big", 800, false);
		File small = album("small", 5, false);
		for (int round = 0; round < 3; round++) {
			AlbumInfo bigAlbum = (AlbumInfo) cache.lookup(path(big));
			assertEquals("Round " + round, 800, bigAlbum.getParts().size());
			AlbumInfo smallAlbum = (AlbumInfo) cache.lookup(path(small));
			assertEquals(5, smallAlbum.getParts().size());
		}
		assertTrue("The big album is read on every lookup: " + cache.loadCount(), cache.loadCount() >= 3);
		assertTrue("The small one stays: " + cache.loadCount(), cache.loadCount() <= 4);
		assertTrue(cache.weight() <= 300);
	}

	public void testTheWeightOfAFolder() throws Exception {
		ResourceCache cache = cache(Long.MAX_VALUE / 2);
		AlbumInfo album = (AlbumInfo) cache.lookup(path(album("Seven", 7, false)));
		assertEquals(7, ResourceCache.weight(album));
		assertEquals(1, ResourceCache.weight(AlbumInfo.create()));
		assertEquals("A listing weighs its folders.", 1, ResourceCache.weight(cache.lookup(new PathInfo(_root))));
		album("Eight", 1, false);
		cache.invalidate(new PathInfo(_root, Path.of("Eight")));
		assertEquals(2, ResourceCache.weight(cache.lookup(new PathInfo(_root))));
	}

	public void testTheBudgetCanBeSetAfterwards() throws Exception {
		ResourceCache cache = cache(Long.MAX_VALUE / 2);
		for (int n = 0; n < 4; n++) {
			cache.lookup(path(album("A" + n, 2, false)));
		}
		long loads = cache.loadCount();
		cache.setBudget(100L * ResourceCache.BYTES_PER_PART);
		assertEquals(100L * ResourceCache.BYTES_PER_PART, cache.getBudget());
		assertEquals("What was held is carried over.", 4, cache.size());
		for (int n = 0; n < 4; n++) {
			assertEquals(2, ((AlbumInfo) cache.lookup(path(new File(_root.toFile(), "A" + n)))).getParts().size());
		}
		assertEquals(loads, cache.loadCount());
	}

	// --- Helpers. ---

	/**
	 * Every photograph of the library as the given cache answers its album: what a search once
	 * read, album by album; the search asks the index of the space now (issue #264), so the walk
	 * is the test's own.
	 */
	private List<String> search(ResourceCache cache) {
		List<String> result = new ArrayList<>();
		walk(cache, _root.toFile(), "", result);
		result.sort(null);
		return result;
	}

	private void walk(ResourceCache cache, File folder, String prefix, List<String> result) {
		File[] children = folder.listFiles();
		if (children == null) {
			return;
		}
		boolean holdsFiles = false;
		for (File child : children) {
			if (child.getName().startsWith(".")) {
				continue;
			}
			if (child.isDirectory()) {
				walk(cache, child, prefix + child.getName() + "/", result);
			} else {
				holdsFiles = true;
			}
		}
		if (holdsFiles && cache.lookup(path(folder)) instanceof AlbumInfo album) {
			for (de.haumacher.imageServer.shared.model.ImagePart image : PhotoSearch.images(album)) {
				result.add(prefix + image.getName());
			}
		}
	}

	/** A library of the given number of albums of three photographs, in ten year folders. */
	private void library(int albums) throws Exception {
		for (int a = 0; a < albums; a++) {
			File year = new File(_root.toFile(), "Year " + a % 10);
			album(year, "Album " + a, 3, false);
		}
	}

	private ResourceCache cache(long budget) throws Exception {
		ResourceCache cache = new ResourceCache(ImageData.Analysis.NONE, null, budget);
		_caches.add(cache);
		return cache;
	}

	private PathInfo path(File folder) {
		return new PathInfo(_root, _root.relativize(folder.toPath()));
	}

	private File album(String name, int photos, boolean rich) throws Exception {
		return album(_root.toFile(), name, photos, rich);
	}

	/**
	 * An album described by its sidecar, as every album is once the server has looked at it: its
	 * files are empty, they are never opened while the sidecar names them.
	 */
	private static File album(File parent, String name, int photos, boolean rich) throws Exception {
		File folder = new File(parent, name);
		assertTrue(folder.mkdirs());
		StringBuilder parts = new StringBuilder();
		for (int p = 0; p < photos; p++) {
			String file = "IMG_" + (1000 + p) + ".jpg";
			assertTrue(new File(folder, file).createNewFile());
			parts.append(p == 0 ? "" : ",").append("[\"ImagePart\",{\"name\":\"").append(file)
				.append("\",\"width\":4000,\"height\":3000,\"date\":").append(1_500_000_000_000L + p * 60_000L);
			if (rich) {
				parts.append(",\"orientation\":\"IDENTITY\",\"rating\":").append(p % 5)
					.append(",\"camera\":\"Canon EOS ").append(p % 3).append("D\"")
					.append(",\"location\":{\"latitude\":").append(48.1 + p * 0.001).append(",\"longitude\":")
					.append(11.5 + p * 0.001).append("}")
					.append(",\"comment\":\"Photo ").append(p).append(" of ").append(name).append("\"")
					.append(",\"labels\":[{\"name\":\"Label ").append(p % 5).append("\"}]")
					.append(",\"places\":{\"tags\":[")
					.append("{\"geonameId\":2867714,\"name\":\"Munich\",\"kind\":\"CITY\",\"featureCode\":\"PPLA\",\"country\":\"DE\"},")
					.append("{\"geonameId\":2861322,\"name\":\"Bavaria\",\"kind\":\"REGION\",\"featureCode\":\"ADM1\",\"country\":\"DE\"},")
					.append("{\"geonameId\":2921044,\"name\":\"Germany\",\"kind\":\"COUNTRY\",\"featureCode\":\"PCLI\",\"country\":\"DE\"}")
					.append("]}");
				if (p % 4 == 0) {
					parts.append(",\"tags\":[{\"x\":0.1,\"y\":0.1,\"w\":0.2,\"h\":0.2,\"person\":\"anne\",\"state\":\"CONFIRMED\"}]");
				}
			}
			parts.append("}]");
		}
		Files.write(new File(folder, "index.json").toPath(),
			("[\"AlbumInfo\",{\"title\":\"" + name + "\",\"parts\":[" + parts + "]}]").getBytes(StandardCharsets.UTF_8));
		return folder;
	}

	private static long heap() throws InterruptedException {
		Runtime runtime = Runtime.getRuntime();
		for (int n = 0; n < 3; n++) {
			System.gc();
			Thread.sleep(50);
		}
		return runtime.totalMemory() - runtime.freeMemory();
	}

}
