/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.places;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import junit.framework.TestCase;

/**
 * The rules of {@link Places#lookup(double, double)}, see issue #234, on excerpts of the real
 * GeoNames files in <code>src/test/fixtures/geonames</code>: <code>DE.txt</code> around Karlsruhe
 * and Kehl, <code>FR.txt</code> around Strasbourg and Paris, the outlines of Germany, France and
 * Switzerland, and no file of Switzerland.
 */
@SuppressWarnings("javadoc")
public class TestPlacesLookup extends TestCase {

	/** The fixture files. */
	static final Path FIXTURES = Path.of("src/test/fixtures/geonames");

	/** The files a test copies into its own directory. */
	static final List<String> FILES = List.of(GeoNamesStore.COUNTRY_INFO, GeoNamesStore.ADMIN1,
		GeoNamesStore.ADMIN2, "shapes_simplified_low.json", "DE.txt", "FR.txt");

	private Path _dir;

	private Places _places;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_dir = copyFixtures();
		_places = Places.offline(_dir);
	}

	@Override
	protected void tearDown() throws Exception {
		delete(_dir);
		super.tearDown();
	}

	/** A scratch copy of the fixtures, so that a cache file is never written into the source tree. */
	static Path copyFixtures() throws IOException {
		Path dir = Files.createTempDirectory("geonames");
		for (String name : FILES) {
			Files.copy(FIXTURES.resolve(name), dir.resolve(name));
		}
		return dir;
	}

	static void delete(Path dir) throws IOException {
		if (dir == null || !Files.exists(dir)) {
			return;
		}
		try (var files = Files.walk(dir)) {
			List<Path> all = new ArrayList<>(files.toList());
			java.util.Collections.reverse(all);
			for (Path file : all) {
				Files.delete(file);
			}
		}
	}

	/** "KIND:name" of every tag, for an assertion that reads like the hierarchy. */
	static List<String> names(PlaceResult result) {
		List<String> names = new ArrayList<>();
		for (PlaceTag tag : result.getTags()) {
			names.add(tag.kind() + ":" + tag.name());
		}
		return names;
	}

	/**
	 * The Schlosspark of the fixture album ("2002-03-03 Schlosspark Karlsruhe", whose photos carry
	 * no position): the whole hierarchy. GeoNames has no entry for the Schlossgarten itself; the
	 * palace is the feature there, and the museum in it, at the same spot, gives way to it.
	 */
	public void testKarlsruheSchlosspark() {
		PlaceResult result = _places.lookup(49.0134, 8.4044);
		assertTrue(result.toString(), result.isAvailable());
		assertEquals(List.of("COUNTRY:Germany", "ADM1:Baden-Württemberg", "ADM2:Karlsruhe Region",
			"ADM3:Stadtkreis Karlsruhe", "ADM4:Karlsruhe", "PLACE:Karlsruhe", "DISTRICT:Innenstadt",
			"FEATURE:Karlsruhe Schloss"), names(result));
		assertEquals(2921044, result.get(PlaceKind.COUNTRY).geonameId());
		assertEquals("DE", result.get(PlaceKind.COUNTRY).countryCode());
		assertEquals(2953481, result.get(PlaceKind.ADM1).geonameId());
		assertEquals(3220721, result.get(PlaceKind.ADM3).geonameId());
		assertEquals(6555606, result.get(PlaceKind.ADM4).geonameId());
		PlaceTag place = result.get(PlaceKind.PLACE);
		assertEquals(2892794, place.geonameId());
		assertEquals("PPLA2", place.featureCode());
		assertEquals(8643496, result.get(PlaceKind.DISTRICT).geonameId());
		PlaceTag feature = result.get(PlaceKind.FEATURE);
		assertEquals(6955705, feature.geonameId());
		assertEquals("PAL", feature.featureCode());
	}

	/**
	 * The fixture's DE.txt lacks the ADM2 entry of the Regierungsbezirk Karlsruhe: its name comes
	 * from admin2Codes.txt, the same id.
	 */
	public void testAdminLevelFromTable() {
		PlaceTag adm2 = _places.lookup(49.0134, 8.4044).get(PlaceKind.ADM2);
		assertEquals(3214104, adm2.geonameId());
		assertEquals("Karlsruhe Region", adm2.name());
		assertEquals("ADM2", adm2.featureCode());
	}

	/** In Durlach, east of the centre: still Karlsruhe, the part of town Durlach, no feature. */
	public void testPartOfTown() {
		PlaceResult result = _places.lookup(48.9995, 8.4710);
		assertEquals("Durlach", result.get(PlaceKind.DISTRICT).name());
		assertEquals("Karlsruhe", result.get(PlaceKind.PLACE).name());
		assertNull(result.get(PlaceKind.FEATURE));
	}

	/** The hotel and the station beside the zoo are noise; the zoo is the feature. */
	public void testNoiseIsNoFeature() {
		PlaceResult result = _places.lookup(48.9965, 8.4015);
		assertEquals("Karlsruhe Zoo", result.get(PlaceKind.FEATURE).name());
		for (PlaceTag tag : _places.lookup(48.99368, 8.40113).getTags()) {
			assertFalse(tag.toString(), tag.featureCode().equals("RSTN") || tag.featureCode().equals("HTL"));
		}
	}

	/** The open North Sea, 100 km from any coast: honestly nothing, and no file is needed. */
	public void testOpenSea() {
		PlaceResult result = _places.lookup(54.5, 6.0);
		assertTrue(result.isAvailable());
		assertEquals(List.of(), result.getTags());
		assertTrue(result.getVersion(), result.getVersion().startsWith("geonames/" + Places.RULES + "/-/"));
	}

	/**
	 * Off the German coast within the margin of the outline, but with no place nearby: no country
	 * either, never the nearest town (Karlsruhe, 500 km away).
	 */
	public void testOffTheCoast() {
		PlaceResult result = _places.lookup(53.95, 8.3);
		assertTrue(result.isAvailable());
		assertEquals(List.of(), result.getTags());
	}

	/** Inside the country but far from any entry of the fixture: the country and nothing else. */
	public void testNothingNearby() {
		PlaceResult result = _places.lookup(52.52, 13.405);
		assertEquals(List.of("COUNTRY:Germany"), names(result));
	}

	/** Kehl and Strasbourg, a few kilometres apart on either side of the Rhine. */
	public void testBorder() {
		PlaceResult kehl = _places.lookup(48.5725, 7.8155);
		assertEquals(List.of("COUNTRY:Germany", "ADM1:Baden-Württemberg", "ADM2:Freiburg Region",
			"ADM3:Ortenaukreis", "ADM4:Kehl", "PLACE:Kehl"), names(kehl));
		PlaceResult strasbourg = _places.lookup(48.5734, 7.7521);
		assertEquals(List.of("COUNTRY:France", "ADM1:Grand Est", "ADM2:Bas-Rhin", "ADM3:Arrondissement de Strasbourg",
			"ADM4:Strasbourg", "PLACE:Strasbourg", "DISTRICT:La Petite France"), names(strasbourg));
		assertTrue(strasbourg.getVersion(), strasbourg.getVersion().startsWith("geonames/" + Places.RULES + "/FR/"));
	}

	/**
	 * A point in Kehl's harbour the simplified outline puts into France, 0.9 km from Kehl and 4 km
	 * from Strasbourg's nearest entry: the village is the better witness.
	 */
	public void testOutlineOverruled() throws IOException {
		Countries countries;
		try (var in = Files.newInputStream(_dir.resolve(GeoNamesStore.COUNTRY_INFO))) {
			countries = Countries.parse(in);
		}
		CountryShapes shapes;
		try (var in = Files.newInputStream(_dir.resolve("shapes_simplified_low.json"))) {
			shapes = CountryShapes.parse(in, countries.isoById());
		}
		List<CountryShapes.Candidate> outlines = shapes.candidates(48.585, 7.811, 0);
		assertEquals(1, outlines.size());
		assertEquals("FR", outlines.get(0).country());

		PlaceResult result = _places.lookup(48.585, 7.811);
		assertEquals("DE", result.get(PlaceKind.COUNTRY).countryCode());
		assertEquals("Kehl", result.get(PlaceKind.PLACE).name());
	}

	/** Paris: the arrondissement GeoNames records as a town is part of Paris. */
	public void testSameMunicipality() {
		PlaceResult result = _places.lookup(48.856, 2.355);
		assertEquals(2988507, result.get(PlaceKind.PLACE).geonameId());
		assertEquals("PPLC", result.get(PlaceKind.PLACE).featureCode());
	}

	/** Zürich: Switzerland has an outline but no file here, which the result says. */
	public void testCountryFileMissing() {
		PlaceResult result = _places.lookup(47.3769, 8.5417);
		assertFalse(result.isAvailable());
		assertEquals(List.of(), result.getTags());
		assertNull(result.getVersion());
		assertTrue(result.getReason(), result.getReason().contains("Switzerland (CH)"));
		assertTrue(result.getReason(), result.getReason().contains("CH.zip"));
	}

	/** Without the outlines nothing can be told at all, and the result says why. */
	public void testBaseFileMissing() throws IOException {
		Files.delete(_dir.resolve("shapes_simplified_low.json"));
		PlaceResult result = Places.offline(_dir).lookup(49.0134, 8.4044);
		assertFalse(result.isAvailable());
		assertTrue(result.getReason(), result.getReason().contains(GeoNamesStore.SHAPES));
	}

	/** A broken country file is reported, not thrown. */
	public void testBrokenCountryFile() throws IOException {
		Files.writeString(_dir.resolve("DE.txt"), "this is\tno\tgeonames file\n");
		PlaceResult result = Places.offline(_dir).lookup(49.0134, 8.4044);
		assertFalse(result.isAvailable());
		assertTrue(result.getReason(), result.getReason().contains("Germany (DE)"));
	}

	public void testNoPosition() {
		assertFalse(_places.lookup(Double.NaN, 8).isAvailable());
		assertFalse(_places.lookup(91, 8).isAvailable());
		assertFalse(_places.lookup(49, 181).isAvailable());
	}

	/** The version names rules, country and data, and stays what it is while the data does. */
	public void testVersion() throws IOException {
		String version = _places.lookup(49.0134, 8.4044).getVersion();
		assertTrue(version, version.matches("geonames/" + Places.RULES + "/DE/2026-09-30-[0-9a-f]{8}"));
		assertEquals(version, _places.currentVersion("DE"));
		assertTrue(_places.isCurrent(version));
		assertTrue(_places.isCurrent(_places.lookup(54.5, 6.0).getVersion()));
		assertFalse(_places.isCurrent("geonames/0/DE/2026-09-30-00000000"));
		assertFalse(_places.isCurrent("geonames/" + Places.RULES + "/DE/2020-01-01-00000000"));
		assertFalse(_places.isCurrent(null));
		// A country whose data cannot be read now: nothing better to retag with.
		assertTrue(_places.isCurrent("geonames/" + Places.RULES + "/CH/2020-01-01-00000000"));

		// New data, a new version.
		Files.writeString(_dir.resolve("DE.txt"), Files.readString(_dir.resolve("DE.txt"))
			+ "13600000\tNeuer Platz\tNeuer Platz\t\t49.0\t8.4\tS\tSQR\tDE\t\t01\t082\t08212\t08212000\t0\t\t115\t"
			+ "Europe/Berlin\t2026-10-07\n");
		String fresh = _places.lookup(49.0134, 8.4044).getVersion();
		assertTrue(fresh, fresh.matches("geonames/" + Places.RULES + "/DE/2026-10-07-[0-9a-f]{8}"));
		assertFalse(_places.isCurrent(version));
		assertTrue(_places.isCurrent(fresh));
	}
}
