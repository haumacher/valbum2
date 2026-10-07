/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.places;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import junit.framework.TestCase;

/**
 * The names of places in other languages, see issue #234 and {@link PlaceNames}, on real lines of
 * GeoNames' <code>alternatenames/DE.txt</code> in <code>src/test/fixtures/geonames</code>.
 */
@SuppressWarnings("javadoc")
public class TestPlaceNames extends TestCase {

	private static final int GERMANY = 2921044, MUNICH = 2867714, BAVARIA = 2951839, BADEN_WUERTTEMBERG = 2953481;

	private static final int[] KEPT = { MUNICH, 2892794, GERMANY, BAVARIA, BADEN_WUERTTEMBERG };

	static {
		java.util.Arrays.sort(KEPT);
	}

	private static PlaceNames parse(String language) throws Exception {
		try (InputStream in = Files.newInputStream(TestPlacesLookup.FIXTURES.resolve("alternatenames/DE.txt"))) {
			return PlaceNames.parse("DE", language, in, KEPT, GERMANY);
		}
	}

	/** The preferred name wins; a historic or colloquial one never; a country is its short name. */
	public void testWhichName() throws Exception {
		PlaceNames de = parse("de");
		assertEquals("München", de.name(MUNICH));
		assertEquals("Bayern", de.name(BAVARIA));
		assertEquals("Baden-Württemberg", de.name(BADEN_WUERTTEMBERG));
		assertEquals("The country is its short preferred name.", "Deutschland", de.name(GERMANY));

		PlaceNames en = parse("en");
		assertEquals("Munich", en.name(MUNICH));
		assertEquals("Preferred and short beats neither.", "Bavaria", en.name(BAVARIA));
		assertEquals("Baden-Wurttemberg", en.name(BADEN_WUERTTEMBERG));
		assertEquals("Germany", en.name(GERMANY));
	}

	/** Rules on lines made up for them: historic, colloquial, short and the order of equals. */
	public void testTheRules() throws Exception {
		String lines = String.join("\n",
			"1\t" + MUNICH + "\tit\tMonaco antico\t\t\t\t1",
			"2\t" + MUNICH + "\tit\tMonacolo\t\t\t1\t",
			"3\t" + MUNICH + "\tit\tMUC\t\t1\t\t",
			"4\t" + MUNICH + "\tit\tMonaco di Baviera\t\t\t\t",
			"5\t" + MUNICH + "\tit\tMonaco\t\t\t\t",
			"6\t" + BAVARIA + "\tit\tBav.\t\t1\t\t",
			"7\t" + BAVARIA + "\tita\tBaviera\t1\t\t\t",
			"8\t99\tit\tNot kept\t1\t\t\t");
		PlaceNames it = PlaceNames.parse("DE", "it", new ByteArrayInputStream(lines.getBytes(StandardCharsets.UTF_8)),
			KEPT, GERMANY);
		assertEquals("Neither historic, nor colloquial, nor short; the first of equals.", "Monaco di Baviera",
			it.name(MUNICH));
		assertEquals("A short name where there is nothing else.", "Bav.", it.name(BAVARIA));
		assertNull(it.name(99));
		assertNull(it.name(GERMANY));
		assertEquals(2, it.size());
	}

	/** The cache file answers the same names, and only for the same files. */
	public void testCacheFile() throws Exception {
		PlaceNames de = parse("de");
		Path file = Files.createTempFile("names", ".names");
		try {
			de.write(file, 42);
			PlaceNames read = PlaceNames.read(file, 42);
			assertNotNull(read);
			assertEquals(de.size(), read.size());
			assertEquals("München", read.name(MUNICH));
			assertEquals("de", read.getLanguage());
			assertNull("Stale.", PlaceNames.read(file, 43));
		} finally {
			Files.deleteIfExists(file);
		}
	}

	/** The language of an Accept-Language header: weights, primary tags, nothing for nonsense. */
	public void testLanguage() {
		assertEquals("de", PlaceNames.language("de-DE,de;q=0.9,en;q=0.8"));
		assertEquals("en", PlaceNames.language("en-GB"));
		assertEquals("de", PlaceNames.language("en;q=0.5, de"));
		assertEquals("fr", PlaceNames.language("*;q=1, fr;q=0.3"));
		assertEquals("zh", PlaceNames.language("zh-Hant-TW"));
		assertEquals("pt", PlaceNames.language("pt_BR"));
		assertNull(PlaceNames.language(null));
		assertNull(PlaceNames.language(""));
		assertNull(PlaceNames.language("*"));
		assertNull(PlaceNames.language("de;q=0"));
		assertNull(PlaceNames.language("x-klingon"));
	}
}
