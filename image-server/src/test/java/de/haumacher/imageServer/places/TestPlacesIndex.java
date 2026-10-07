/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.places;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import junit.framework.TestCase;

/**
 * The {@link CountryIndex} of a country file, its cache file, and lookups from many threads, see
 * issue #234.
 */
@SuppressWarnings("javadoc")
public class TestPlacesIndex extends TestCase {

	private Path _dir;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_dir = TestPlacesLookup.copyFixtures();
	}

	@Override
	protected void tearDown() throws Exception {
		TestPlacesLookup.delete(_dir);
		super.tearDown();
	}

	private CountryIndex parse(String iso) throws IOException {
		return Places.parseCountry(iso, _dir.resolve(iso + ".txt"), _dir.resolve(GeoNamesStore.ADMIN1),
			_dir.resolve(GeoNamesStore.ADMIN2));
	}

	/** What the index finds around a position: id, distance, name, code, population, codes. */
	private static Map<Integer, String> around(CountryIndex index, double lat, double lon) {
		Map<Integer, String> result = new TreeMap<>();
		index.near(lat, lon, 30, (entry, distance) -> result.put(Integer.valueOf(index.id(entry)),
			Math.round(distance * 1000) + "m " + index.name(entry) + " " + FeatureCodes.code(index.code(entry)) + " "
				+ index.population(entry) + " " + String.join(".", index.adminCodes(entry))));
		return result;
	}

	/** Only the kept codes are read: the hotel, the station and the stream are not in the index. */
	public void testSelection() throws IOException {
		CountryIndex index = parse("DE");
		Map<Integer, String> all = around(index, 49.0, 8.4);
		assertTrue(all.containsKey(Integer.valueOf(6955705)));
		assertFalse(all.containsKey(Integer.valueOf(6488551)));
		assertFalse(all.containsKey(Integer.valueOf(6955704)));
		assertFalse(all.containsKey(Integer.valueOf(2874043)));
		// Nor the country's own entry: the country comes from countryInfo.txt.
		assertFalse(all.containsKey(Integer.valueOf(2921044)));
		assertEquals(18, index.size());
	}

	/** The k-d tree finds exactly what a scan of all entries finds. */
	public void testTreeAgainstScan() throws IOException {
		CountryIndex index = parse("DE");
		for (double lat = 48.5; lat <= 49.1; lat += 0.05) {
			for (double lon = 7.7; lon <= 8.6; lon += 0.05) {
				for (double radius : new double[] { 0.5, 2, 10, 40 }) {
					List<Integer> found = new ArrayList<>();
					index.near(lat, lon, radius, (entry, distance) -> found.add(Integer.valueOf(entry)));
					List<Integer> expected = new ArrayList<>();
					for (int entry = 0; entry < index.size(); entry++) {
						if (Geo.near(lat, lon, Math.cos(Math.toRadians(lat)), index.lat(entry), index.lon(entry)) <= radius) {
							expected.add(Integer.valueOf(entry));
						}
					}
					found.sort(null);
					assertEquals(lat + "," + lon + " r=" + radius, expected, found);
				}
			}
		}
	}

	/** The index read from its cache file answers exactly as the one built from the dump. */
	public void testCacheFileSameAnswers() throws IOException {
		for (String iso : List.of("DE", "FR")) {
			CountryIndex parsed = parse(iso);
			Path cache = _dir.resolve(iso + Places.CACHE_SUFFIX);
			parsed.write(cache, 4711);
			CountryIndex read = CountryIndex.read(cache, 4711);
			assertNotNull(read);
			assertEquals(parsed.getCountry(), read.getCountry());
			assertEquals(parsed.getVersion(), read.getVersion());
			assertEquals(parsed.size(), read.size());
			for (double lat = 48.4; lat <= 49.2; lat += 0.1) {
				for (double lon = 2.2; lon <= 8.6; lon += 0.1) {
					assertEquals(around(parsed, lat, lon), around(read, lat, lon));
					for (int level = 1; level <= 4; level++) {
						for (int entry = 0; entry < parsed.size(); entry++) {
							assertEquals(parsed.admin(parsed.adminCodes(entry), level),
								read.admin(read.adminCodes(entry), level));
						}
					}
				}
			}
		}
	}

	/** And so does a {@link Places} that reads the cache file a first one wrote. */
	public void testPlacesFromCacheFile() throws IOException {
		Places first = Places.offline(_dir);
		List<PlaceResult> expected = grid(first);
		assertTrue(Files.isRegularFile(_dir.resolve("DE" + Places.CACHE_SUFFIX)));
		assertTrue(Files.isRegularFile(_dir.resolve("FR" + Places.CACHE_SUFFIX)));

		// The dump unreadable: only the cache file can answer now (its stamp names size and time).
		Path dump = _dir.resolve("DE.txt");
		var modified = Files.getLastModifiedTime(dump);
		byte[] content = Files.readAllBytes(dump);
		byte[] garbage = new byte[content.length];
		java.util.Arrays.fill(garbage, (byte) 'x');
		Files.write(dump, garbage);
		Files.setLastModifiedTime(dump, modified);

		List<PlaceResult> second = grid(Places.offline(_dir));
		assertEquals(expected.size(), second.size());
		for (int n = 0; n < expected.size(); n++) {
			assertEquals(expected.get(n).toString(), second.get(n).toString());
		}
	}

	/** A stale or broken cache file is ignored, and the index built from the dump again. */
	public void testStaleOrBrokenCacheFile() throws IOException {
		CountryIndex parsed = parse("DE");
		Path cache = _dir.resolve("DE" + Places.CACHE_SUFFIX);
		parsed.write(cache, 1);
		assertNull(CountryIndex.read(cache, 2));
		byte[] bytes = Files.readAllBytes(cache);
		Files.write(cache, java.util.Arrays.copyOf(bytes, bytes.length / 2));
		assertNull(CountryIndex.read(cache, 1));
		Files.write(cache, new byte[0]);
		assertNull(CountryIndex.read(cache, 1));

		// The lookup does not care.
		assertEquals("Karlsruhe", Places.offline(_dir).lookup(49.0134, 8.4044).get(PlaceKind.PLACE).name());
	}

	private static List<PlaceResult> grid(Places places) {
		List<PlaceResult> result = new ArrayList<>();
		for (double lat = 48.5; lat <= 49.1; lat += 0.01) {
			for (double lon = 7.7; lon <= 8.5; lon += 0.02) {
				result.add(places.lookup(lat, lon));
			}
		}
		result.add(places.lookup(48.856, 2.355));
		return result;
	}

	/**
	 * Many threads looking up at once, the first of them while the countries are still being
	 * loaded: every one gets what a single thread gets.
	 */
	public void testConcurrentLookups() throws Exception {
		Path other = TestPlacesLookup.copyFixtures();
		List<PlaceResult> expected;
		try {
			expected = grid(Places.offline(other));
		} finally {
			TestPlacesLookup.delete(other);
		}
		Places places = Places.offline(_dir);
		int threads = 8;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<List<PlaceResult>>> futures = new ArrayList<>();
			for (int n = 0; n < threads; n++) {
				Callable<List<PlaceResult>> task = () -> {
					start.await();
					List<PlaceResult> all = new ArrayList<>();
					for (int round = 0; round < 5; round++) {
						all = grid(places);
					}
					return all;
				};
				futures.add(pool.submit(task));
			}
			start.countDown();
			for (Future<List<PlaceResult>> future : futures) {
				List<PlaceResult> got = future.get();
				assertEquals(expected.size(), got.size());
				for (int n = 0; n < got.size(); n++) {
					assertEquals(expected.get(n).toString(), got.get(n).toString());
				}
			}
		} finally {
			pool.shutdownNow();
		}
	}

	/** The outlines of a real shapes file: every country of the fixture, and the even-odd rule. */
	public void testShapes() throws IOException {
		Countries countries;
		try (InputStream in = Files.newInputStream(_dir.resolve(GeoNamesStore.COUNTRY_INFO))) {
			countries = Countries.parse(in);
		}
		CountryShapes shapes;
		try (InputStream in = Files.newInputStream(_dir.resolve("shapes_simplified_low.json"))) {
			shapes = CountryShapes.parse(in, countries.isoById());
		}
		assertEquals(3, shapes.size());
		assertEquals("CH", shapes.candidates(46.95, 7.45, 0).get(0).country());
		assertEquals("DE", shapes.candidates(52.52, 13.405, 0).get(0).country());
		assertTrue(shapes.candidates(40.0, -30.0, 10).isEmpty());
		// Basel, at the corner of three countries: all of them within 10 km.
		List<String> near = new ArrayList<>();
		for (CountryShapes.Candidate candidate : shapes.candidates(47.5596, 7.5886, 10)) {
			near.add(candidate.country());
		}
		near.sort(null);
		assertEquals(List.of("CH", "DE", "FR"), near);
	}
}
