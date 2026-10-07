/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.places;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import junit.framework.TestCase;

/**
 * The bound on the countries held in memory, see issue #234 and {@link Places#setBudget(long)}.
 */
@SuppressWarnings("javadoc")
public class TestPlacesMemory extends TestCase {

	private static final double[] KARLSRUHE = { 49.0134, 8.4044 };

	private static final double[] PARIS = { 48.8566, 2.3522 };

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

	/**
	 * With room for one country, Karlsruhe, then Paris, then Karlsruhe again: each lets the other go,
	 * and Germany comes back from its cache file with the same answer.
	 */
	public void testABudgetOfOneCountry() throws Exception {
		Places places = Places.offline(_dir);
		places.setBudget(1);

		PlaceResult first = places.lookup(KARLSRUHE[0], KARLSRUHE[1]);
		assertEquals("Karlsruhe", first.get(PlaceKind.PLACE).name());
		assertEquals(List.of("DE"), places.loadedCountries());
		assertEquals(1, places.reads());
		assertTrue("The index was cached.", Files.isRegularFile(_dir.resolve("DE" + Places.CACHE_SUFFIX)));

		assertEquals("Paris", places.lookup(PARIS[0], PARIS[1]).get(PlaceKind.PLACE).name());
		assertEquals(List.of("FR"), places.loadedCountries());
		assertEquals(2, places.reads());

		PlaceResult again = places.lookup(KARLSRUHE[0], KARLSRUHE[1]);
		assertEquals(first.getTags(), again.getTags());
		assertEquals(first.getVersion(), again.getVersion());
		assertEquals(List.of("DE"), places.loadedCountries());
		assertEquals(3, places.reads());
		assertTrue(places.loadedBytes() > 0);
	}

	/** Within the budget, a country is read once and kept, the least recently used first. */
	public void testWithinTheBudgetEverythingStays() throws Exception {
		Places places = Places.offline(_dir);
		places.lookup(KARLSRUHE[0], KARLSRUHE[1]);
		places.lookup(PARIS[0], PARIS[1]);
		places.lookup(KARLSRUHE[0], KARLSRUHE[1]);
		assertEquals(List.of("FR", "DE"), places.loadedCountries());
		assertEquals(2, places.reads());

		long both = places.loadedBytes();
		// Shrinking the budget lets the least recently used go at once.
		places.setBudget(both - 1);
		assertEquals(List.of("DE"), places.loadedCountries());
		assertTrue(places.loadedBytes() < both);
	}

	/** The default is an eighth of the heap. */
	public void testTheDefaultBudget() {
		assertEquals(Runtime.getRuntime().maxMemory() / 8, Places.offline(_dir).getBudget());
	}
}
