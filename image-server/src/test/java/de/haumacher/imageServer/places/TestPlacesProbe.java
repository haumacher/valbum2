/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.places;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import junit.framework.TestCase;

/**
 * Probe of issue #234 at the date line: Fiji, Chukotka and the Aleutians straddle longitude 180, and
 * two villages a kilometre apart on either side of it are neighbours, not half a world apart.
 */
@SuppressWarnings("javadoc")
public class TestPlacesProbe extends TestCase {

	public void testNeighboursAcrossTheDateLine() throws Exception {
		Path dir = TestPlacesLookup.copyFixtures();
		try {
			String east = "9000001\tEastvillage\tEastvillage\t\t-16.5\t179.995\tP\tPPL\tFJ\t\t03\t\t\t\t500\t\t5\tPacific/Fiji\t2026-01-01";
			String west = "9000002\tWestvillage\tWestvillage\t\t-16.5\t-179.995\tP\tPPL\tFJ\t\t03\t\t\t\t500\t\t5\tPacific/Fiji\t2026-01-01";
			Path file = dir.resolve("FJ.txt");
			Files.write(file, (east + "\n" + west + "\n").getBytes(StandardCharsets.UTF_8));
			CountryIndex index = Places.parseCountry("FJ", file, dir.resolve(GeoNamesStore.ADMIN1),
				dir.resolve(GeoNamesStore.ADMIN2));
			for (double lon : new double[] { 179.999, -179.999, 180.0 }) {
				List<String> found = new ArrayList<>();
				index.near(-16.5, lon, 3, (entry, distance) -> found.add(index.name(entry)));
				assertEquals("At " + lon + ": " + found, 2, found.size());
			}
		} finally {
			TestPlacesLookup.delete(dir);
		}
	}
}
