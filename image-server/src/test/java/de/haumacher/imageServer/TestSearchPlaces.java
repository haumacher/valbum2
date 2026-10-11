/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import static de.haumacher.imageServer.TestImageServletPut.request;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.places.Places;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.PlaceKind;
import de.haumacher.imageServer.shared.model.PlaceTag;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.SearchAnd;
import de.haumacher.imageServer.shared.model.SearchOptions;
import de.haumacher.imageServer.shared.model.SearchPlace;
import de.haumacher.imageServer.shared.model.SearchQuery;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import junit.framework.TestCase;

/**
 * The place criterion of the search of issue #227: a photograph matches a GeoNames entry at any
 * level of its place tags (issue #234), so a state finds the photographs of its towns.
 *
 * <p>
 * The gazetteer is the scratch copy of {@link TestPlaceTags}: Karlsruhe and Kehl lie in
 * Baden-Württemberg, Strasbourg in France.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestSearchPlaces extends TestCase {

	/** The GeoNames id of Baden-Württemberg (ADM1). */
	private static final int BADEN_WUERTTEMBERG = 2953481;

	/** The GeoNames id of the town of Karlsruhe (PPLA2). */
	private static final int KARLSRUHE = 2892794;

	private Path _base;

	private Path _geonames;

	private Places _places;

	private ImageServlet _servlet;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-search-places");
		_geonames = Files.createTempDirectory("valbum-geonames");
		TestPlaceTags.copyGazetteer(_geonames);
		File trip = new File(_base.toFile(), "Trip");
		assertTrue(trip.mkdirs());
		TestGeoLocation.writeJpeg(new File(trip, "schloss.jpg"), TestPlaceTags.gps(49.0134, 8.4044));
		TestGeoLocation.writeJpeg(new File(trip, "kehl.jpg"), TestPlaceTags.gps(48.5728, 7.8156));
		TestGeoLocation.writeJpeg(new File(trip, "strasbourg.jpg"), TestPlaceTags.gps(48.5734, 7.7521));
		TestGeoLocation.writeJpeg(new File(trip, "nowhere.jpg"), null);
	}

	@Override
	protected void tearDown() throws Exception {
		if (_servlet != null) {
			_servlet.destroy();
		}
		if (_places != null) {
			_places.getStore().close();
		}
		TestPlaceTags.delete(_base);
		TestPlaceTags.delete(_geonames);
		super.tearDown();
	}

	public void testAStateFindsThePhotosOfItsTowns() throws Exception {
		assertEquals(Arrays.asList("Trip/kehl.jpg", "Trip/schloss.jpg"), sorted(search(BADEN_WUERTTEMBERG)));
	}

	public void testATownFindsItsOwnPhotos() throws Exception {
		assertEquals(Arrays.asList("Trip/schloss.jpg"), search(KARLSRUHE));
	}

	public void testTheOptionsOfferThePlacesPresent() throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", ImageServlet.SEARCH_OPTIONS_TYPE);
		FakeResponse response = new FakeResponse();
		servlet().doGet(request("/", null, new byte[0], Map.of(), parameters), response.response());
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		SearchOptions options = SearchOptions.readSearchOptions(json(response.body()));
		List<String> states = new ArrayList<>();
		List<String> countries = new ArrayList<>();
		for (PlaceTag tag : options.getPlaces()) {
			if (tag.getKind() == PlaceKind.ADM_1) {
				states.add(tag.getName());
			} else if (tag.getKind() == PlaceKind.COUNTRY) {
				countries.add(tag.getName());
			}
		}
		assertEquals("Each place once, by name.", Arrays.asList("Baden-Württemberg", "Grand Est"), states);
		assertEquals("The largest first.", PlaceKind.COUNTRY, options.getPlaces().get(0).getKind());
		assertEquals(Arrays.asList("France", "Germany"), countries);
	}

	public void testASearchAnswersThePlaceLineAndThePhotographEverything() throws Exception {
		ImagePart found = (ImagePart) answer(KARLSRUHE).getParts().get(0);
		assertEquals("Trip/schloss.jpg", found.getName());
		assertSlim(found);

		ImagePart own = (ImagePart) Resource.readResource(json(get("/Trip/schloss.jpg", "json")));
		assertFull(own);
	}

	public void testASavedSearchAnswersThePlaceLineAndThePhotographEverything() throws Exception {
		File saved = new File(_base.toFile(), "Saved");
		assertTrue(saved.mkdirs());
		Files.write(new File(saved, "index.json").toPath(), ("[\"AlbumInfo\",{\"kind\":\"SEARCH\",\"query\":"
			+ TestSavedSearch.write(query(KARLSRUHE)) + "}]").getBytes(StandardCharsets.UTF_8));

		AlbumInfo album = (AlbumInfo) Resource.readResource(json(get("/Saved/", "json")));
		ImagePart found = (ImagePart) album.getParts().get(0);
		assertEquals("Trip/schloss.jpg", found.getName());
		assertSlim(found);

		assertFull((ImagePart) Resource.readResource(json(get("/Saved/Trip/schloss.jpg", "json"))));
	}

	private static void assertSlim(ImagePart found) {
		assertTrue(found.isPartial());
		assertFalse(found.getPlaces().getTags().isEmpty());
		for (PlaceTag tag : found.getPlaces().getTags()) {
			assertTrue(tag.getKind() + " is no kind the place line reads.",
				PhotoSearch.PLACE_LINE_KINDS.contains(tag.getKind())
					|| PhotoSearch.PLACE_LINE_TOWN.contains(tag.getKind()));
			assertFalse(tag.getName().isEmpty());
			assertEquals("Name and kind alone.", 0, tag.getGeonameId());
			assertEquals("", tag.getCountry());
		}
	}

	private static void assertFull(ImagePart own) {
		assertFalse(own.isPartial());
		boolean town = false;
		for (PlaceTag tag : own.getPlaces().getTags()) {
			assertTrue("Every tag with its id: " + tag.getName(), tag.getGeonameId() != 0);
			assertFalse(tag.getCountry().isEmpty());
			town |= tag.getGeonameId() == KARLSRUHE;
		}
		assertTrue(town);
	}

	private String get(String path, String type) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", type);
		FakeResponse response = new FakeResponse();
		servlet().doGet(request(path, null, new byte[0], Map.of(), parameters), response.response());
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		return response.body();
	}

	private static SearchQuery query(int geonameId) {
		return SearchQuery.create().setVersion(1).setRoot(SearchAnd.create()
			.setCriteria(new ArrayList<>(Arrays.asList(SearchPlace.create().setGeonameId(geonameId)))));
	}

	private AlbumInfo answer(int geonameId) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", ImageServlet.SEARCH_ACTION);
		FakeResponse response = new FakeResponse();
		servlet().doPost(request("/", "application/json",
			TestSavedSearch.write(query(geonameId)).getBytes(StandardCharsets.UTF_8), Map.of(), parameters),
			response.response());
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		return (AlbumInfo) Resource.readResource(json(response.body()));
	}

	private List<String> search(int geonameId) throws Exception {
		SearchQuery query = SearchQuery.create().setVersion(1).setRoot(SearchAnd.create()
			.setCriteria(new ArrayList<>(Arrays.asList(SearchPlace.create().setGeonameId(geonameId)))));
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", ImageServlet.SEARCH_ACTION);
		FakeResponse response = new FakeResponse();
		servlet().doPost(request("/", "application/json",
			TestSavedSearch.write(query).getBytes(StandardCharsets.UTF_8), Map.of(), parameters), response.response());
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		AlbumInfo album = (AlbumInfo) Resource.readResource(json(response.body()));
		List<String> result = new ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			result.add(((ImagePart) part).getName());
		}
		return result;
	}

	private static List<String> sorted(List<String> names) {
		List<String> result = new ArrayList<>(names);
		result.sort(null);
		return result;
	}

	private static JsonReader json(String body) {
		return new JsonReader(new ReaderAdapter(new StringReader(body)));
	}

	private ImageServlet servlet() throws Exception {
		if (_servlet == null) {
			_servlet = new ImageServlet(_base.toFile(), new AuthService(AuthMode.OFF, _base));
			_places = Places.offline(_geonames);
			_servlet.setPlaces(_places);
		}
		return _servlet;
	}
}
