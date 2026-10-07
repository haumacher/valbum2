/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import static de.haumacher.imageServer.TestImageServletPut.request;

import com.sun.net.httpserver.HttpServer;
import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.places.GeoNamesStore;
import de.haumacher.imageServer.places.Places;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.GeoLocation;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.PlaceInfo;
import de.haumacher.imageServer.shared.model.PlaceKind;
import de.haumacher.imageServer.shared.model.PlaceTag;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * The place tags of issue #234 on the wire: derived from a photograph's position whenever its album
 * is read, never stored in its sidecar, and named in the caller's language.
 *
 * <p>
 * The gazetteer is a scratch copy of the excerpts in <code>src/test/fixtures/geonames</code>, with
 * Munich and Bavaria added for the names in other languages.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestPlaceTags extends TestCase {

	private static final String ALBUM = "2002-03-03 Schlosspark";

	/** The fixtures of the gazetteer. */
	static final Path FIXTURES = Path.of("src/test/fixtures/geonames");

	/** Munich, as GeoNames' DE.txt has it, without its alternate names column. */
	private static final String MUNICH = "2867714\tMunich\tMunich\t\t48.13743\t11.57549\tP\tPPLA\tDE\t\t02\t091\t09162\t"
		+ "09162000\t1505005\t\t524\tEurope/Berlin\t2026-05-06\n";

	/** The Karlsruhe palace, the Schlossgarten. */
	private static final double[] SCHLOSS = { 49.0134, 8.4044 };

	/** The cathedral of Strasbourg. */
	private static final double[] STRASBOURG = { 48.5734, 7.7521 };

	/** The Marienplatz of Munich. */
	private static final double[] MARIENPLATZ = { 48.1374, 11.5755 };

	private Path _base;

	private File _album;

	private Path _geonames;

	private Places _places;

	private ImageServlet _servlet;

	private HttpServer _server;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-places");
		_album = new File(_base.toFile(), ALBUM);
		assertTrue(_album.mkdirs());
		_geonames = Files.createTempDirectory("valbum-geonames");
		copyGazetteer(_geonames);
	}

	@Override
	protected void tearDown() throws Exception {
		if (_servlet != null) {
			_servlet.destroy();
		}
		if (_places != null) {
			_places.getStore().close();
		}
		if (_server != null) {
			_server.stop(0);
		}
		delete(_base);
		delete(_geonames);
		super.tearDown();
	}

	/** The fixtures of the gazetteer, with Munich and Bavaria, into the given directory. */
	static void copyGazetteer(Path dir) throws IOException {
		for (String name : List.of(GeoNamesStore.COUNTRY_INFO, GeoNamesStore.ADMIN1, GeoNamesStore.ADMIN2,
			"shapes_simplified_low.json", "DE.txt", "FR.txt")) {
			Files.copy(FIXTURES.resolve(name), dir.resolve(name));
		}
		Files.writeString(dir.resolve("DE.txt"), MUNICH, StandardCharsets.UTF_8,
			java.nio.file.StandardOpenOption.APPEND);
		Files.writeString(dir.resolve(GeoNamesStore.ADMIN1), "DE.02\tBavaria\tBavaria\t2951839\n",
			StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
		Files.createDirectories(dir.resolve(GeoNamesStore.ALTERNATE_NAMES));
		Files.copy(FIXTURES.resolve("alternatenames/DE.txt"), dir.resolve("alternatenames/DE.txt"));
	}

	static void delete(Path dir) throws IOException {
		if (dir == null || !Files.exists(dir)) {
			return;
		}
		try (Stream<Path> files = Files.walk(dir)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
	}

	// --- The tags. ---

	/** A photograph whose file says where it was taken is answered the places there. */
	public void testTheGpsPositionAnswersThePlaces() throws Exception {
		photo("schloss.jpg", SCHLOSS);
		String body = albumJson(null);
		assertTrue(body, body.contains("\"places\""));
		ImagePart image = image(parse(body), "schloss.jpg");
		assertEquals(List.of("COUNTRY:Germany", "ADM1:Baden-Württemberg", "ADM2:Karlsruhe Region",
			"ADM3:Stadtkreis Karlsruhe", "ADM4:Karlsruhe", "PLACE:Karlsruhe", "DISTRICT:Innenstadt",
			"FEATURE:Karlsruhe Schloss"), names(image));
		assertEquals("", image.getPlaces().getPending());
		PlaceTag feature = image.getPlaces().getTags().get(7);
		assertEquals(6955705, feature.getGeonameId());
		assertEquals("PAL", feature.getFeatureCode());
		assertEquals("DE", feature.getCountry());
	}

	/** Without a position nothing is said: no tags, nothing pending. */
	public void testNoPositionNoPlaces() throws Exception {
		TestGeoLocation.writeJpeg(new File(_album, "nowhere.jpg"), null);
		String body = albumJson(null);
		assertFalse(body, body.contains("\"places\""));
		assertNull(image(parse(body), "nowhere.jpg").getPlaces());
	}

	/** The open sea is no place either: an honest nothing, not "pending". */
	public void testTheOpenSeaNoPlaces() throws Exception {
		photo("sea.jpg", new double[] { 54.5, 6.0 });
		assertNull(image(parse(albumJson(null)), "sea.jpg").getPlaces());
	}

	/**
	 * The tags never reach the sidecar: an album read with its tags and written back (a rating, the
	 * app's ordinary PUT) stores none, and reading it again answers them again.
	 */
	public void testTheTagsAreNeverStored() throws Exception {
		photo("schloss.jpg", SCHLOSS);
		AlbumInfo album = parse(albumJson(null));
		assertNotNull(image(album, "schloss.jpg").getPlaces());
		image(album, "schloss.jpg").setRating(2);
		FakeResponse put = put(json(album));
		assertEquals(put.body(), HttpServletResponse.SC_OK, put.status());

		String stored = Files.readString(_album.toPath().resolve("index.json"), StandardCharsets.UTF_8);
		assertTrue(stored, stored.contains("\"rating\":2"));
		assertFalse(stored, stored.contains("places"));
		assertFalse(stored, stored.contains("Karlsruhe Schloss"));
		assertFalse(stored, stored.contains("geonameId"));

		AlbumInfo again = parse(albumJson(null));
		assertEquals(2, image(again, "schloss.jpg").getRating());
		assertEquals("Karlsruhe Schloss", last(image(again, "schloss.jpg")));
	}

	/** The position the sidecar stores is the photograph's position, for its places too. */
	public void testAStoredLocationOverridesTheFile() throws Exception {
		photo("moved.jpg", STRASBOURG);
		AlbumInfo album = parse(albumJson(null));
		assertTrue(names(image(album, "moved.jpg")).contains("COUNTRY:France"));

		image(album, "moved.jpg").setLocation(
			GeoLocation.create().setLatitude(SCHLOSS[0]).setLongitude(SCHLOSS[1]));
		assertEquals(HttpServletResponse.SC_OK, put(json(album)).status());

		ImagePart moved = image(parse(albumJson(null)), "moved.jpg");
		assertEquals(SCHLOSS[0], moved.getLocation().getLatitude(), 1e-9);
		assertTrue(names(moved).toString(), names(moved).contains("COUNTRY:Germany"));
		assertEquals("Karlsruhe Schloss", last(moved));
	}

	/**
	 * A country whose file is missing on a server that downloads nothing: the photograph says why,
	 * and once the file is placed by hand the next request answers the tags, without a restart.
	 */
	public void testAFilePlacedByHandIsSeenAtTheNextRequest() throws Exception {
		Path de = Files.move(_geonames.resolve("DE.txt"), _base.resolve("DE.txt.aside"));
		photo("schloss.jpg", SCHLOSS);
		PlaceInfo pending = image(parse(albumJson(null)), "schloss.jpg").getPlaces();
		assertNotNull(pending);
		assertTrue(pending.getTags().isEmpty());
		assertTrue(pending.getPending(), pending.getPending().startsWith("Place names for Germany cannot be loaded: "));
		assertTrue(pending.getPending(), pending.getPending().contains("DE.zip is missing"));

		// The cached album is answered again while nothing changed.
		assertEquals(pending.getPending(), image(parse(albumJson(null)), "schloss.jpg").getPlaces().getPending());

		Thread.sleep(20);
		Files.move(de, _geonames.resolve("DE.txt"));
		assertEquals("Karlsruhe Schloss", last(image(parse(albumJson(null)), "schloss.jpg")));
	}

	/**
	 * A country being downloaded: the photograph says so, the answer never waits, and the next
	 * request after the download answers the tags (the cached album is read again).
	 */
	public void testADownloadArrivingIsSeenAtTheNextRequest() throws Exception {
		Files.delete(_geonames.resolve("DE.txt"));
		CountDownLatch release = new CountDownLatch(1);
		byte[] zip = zip("DE.txt", Files.readAllBytes(FIXTURES.resolve("DE.txt")));
		_server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		_server.createContext("/export/dump/", exchange -> {
			try (exchange) {
				String name = exchange.getRequestURI().getPath().substring("/export/dump/".length());
				if (!name.equals("DE.zip")) {
					exchange.sendResponseHeaders(404, -1);
					return;
				}
				try {
					release.await(20, TimeUnit.SECONDS);
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
				exchange.sendResponseHeaders(200, zip.length);
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(zip);
				}
			}
		});
		_server.start();
		URI base = URI.create("http://127.0.0.1:" + _server.getAddress().getPort() + "/export/dump/");
		_places = new Places(new GeoNamesStore(_geonames,
			new GeoNamesStore.Settings().setBase(base).setGap(Duration.ZERO)));

		photo("schloss.jpg", SCHLOSS);
		long start = System.nanoTime();
		PlaceInfo pending = image(parse(albumJson(null)), "schloss.jpg").getPlaces();
		assertTrue("The answer does not wait for the download.",
			System.nanoTime() - start < TimeUnit.SECONDS.toNanos(10));
		assertEquals("Place names for Germany are being loaded.", pending.getPending());
		assertTrue(_places.getStore().isLoading("DE.zip"));

		release.countDown();
		assertTrue(_places.getStore().awaitIdle(Duration.ofSeconds(20)));
		assertTrue(Files.isRegularFile(_geonames.resolve("DE.zip")));
		ImagePart tagged = image(parse(albumJson(null)), "schloss.jpg");
		assertEquals("", tagged.getPlaces().getPending());
		assertEquals("Karlsruhe Schloss", last(tagged));
	}

	// --- The caller's language. ---

	/** The same album answers the names of the caller's language: München in German, Munich in English. */
	public void testTheNamesInTheCallersLanguage() throws Exception {
		photo("munich.jpg", MARIENPLATZ);
		assertEquals(List.of("COUNTRY:Deutschland", "ADM1:Bayern", "PLACE:München"),
			names(image(parse(albumJson("de-DE,de;q=0.9,en;q=0.8")), "munich.jpg")));
		assertEquals(List.of("COUNTRY:Germany", "ADM1:Bavaria", "PLACE:Munich"),
			names(image(parse(albumJson("en-GB,en;q=0.9")), "munich.jpg")));
		assertEquals("The weights decide, not the order.", List.of("COUNTRY:Deutschland", "ADM1:Bayern", "PLACE:München"),
			names(image(parse(albumJson("en;q=0.5, de")), "munich.jpg")));
	}

	/** A language GeoNames has no names in, or none asked for: the main names. */
	public void testAnUnknownLanguageAnswersTheMainNames() throws Exception {
		photo("munich.jpg", MARIENPLATZ);
		List<String> main = List.of("COUNTRY:Germany", "ADM1:Bavaria", "PLACE:Munich");
		assertEquals(main, names(image(parse(albumJson("tlh")), "munich.jpg")));
		assertEquals(main, names(image(parse(albumJson("*")), "munich.jpg")));
		assertEquals(main, names(image(parse(albumJson(null)), "munich.jpg")));
	}

	/** A single photograph is answered in the caller's language too. */
	public void testASinglePhotographInTheCallersLanguage() throws Exception {
		photo("munich.jpg", MARIENPLATZ);
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "json");
		FakeResponse response = new FakeResponse();
		servlet().doGet(request("/" + ALBUM + "/munich.jpg", null, new byte[0], Map.of("Accept-Language", "de"),
			parameters), response.response());
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		ImagePart image = (ImagePart) Resource.readResource(new JsonReader(new ReaderAdapter(new StringReader(response.body()))));
		assertEquals("München", last(image));
	}

	/**
	 * The cached album is never changed by a localized answer: requests in German and in English
	 * one after the other each get their own names, and the cache keeps the main names.
	 */
	public void testTheCachedAlbumKeepsTheMainNames() throws Exception {
		photo("munich.jpg", MARIENPLATZ);
		photo("schloss.jpg", SCHLOSS);
		for (int n = 0; n < 3; n++) {
			assertEquals("München", last(image(parse(albumJson("de")), "munich.jpg")));
			assertEquals("Munich", last(image(parse(albumJson("en")), "munich.jpg")));
			assertEquals("Munich", last(image(parse(albumJson(null)), "munich.jpg")));
		}
		AlbumInfo cached = (AlbumInfo) _servlet.cache().lookup(new PathInfo(_base, Path.of(ALBUM)));
		ImagePart munich = cached.getImageByName().get("munich.jpg");
		assertEquals(List.of("COUNTRY:Germany", "ADM1:Bavaria", "PLACE:Munich"), names(munich));
		ImagePart schloss = cached.getImageByName().get("schloss.jpg");
		assertEquals("COUNTRY:Germany", names(schloss).get(0));
	}

	// --- Helpers. ---

	/**
	 * Probe: a virtual collection (#221) shows a photograph of another album by reference; its places
	 * are those of the photograph, derived just the same.
	 */
	public void testProbeACollectionShowsThePlacesOfItsPhotos() throws Exception {
		photo("schloss.jpg", SCHLOSS);
		File photo = new File(_album, "schloss.jpg");
		String hash = de.haumacher.imageServer.upload.HashCache.sha256(photo);
		de.haumacher.imageServer.upload.HashCache hashes = new de.haumacher.imageServer.upload.HashCache(_album);
		hashes.put(photo, hash, null);
		hashes.flush();
		File best = new File(_base.toFile(), "Best of");
		assertTrue(best.mkdirs());
		Files.writeString(best.toPath().resolve("index.json"),
			"[\"AlbumInfo\",{\"kind\":\"COLLECTION\",\"title\":\"Best of\",\"parts\":[[\"ImagePart\",{\"name\":\"schloss.jpg\","
				+ "\"ref\":{\"hash\":\"" + hash + "\",\"path\":\"" + ALBUM + "/schloss.jpg\"}}]]}]",
			StandardCharsets.UTF_8);
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "json");
		FakeResponse response = new FakeResponse();
		servlet().doGet(request("/Best of/", null, new byte[0], Map.of(), parameters), response.response());
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		ImagePart shown = image(parse(response.body()), "schloss.jpg");
		assertNotNull("The collection shows the photograph's places: " + response.body(), shown.getPlaces());
		assertEquals("Karlsruhe Schloss", last(shown));
		assertFalse("Nothing is stored in the collection.",
			Files.readString(best.toPath().resolve("index.json")).contains("places"));
	}

	private void photo(String name, double[] position) throws Exception {
		TestGeoLocation.writeJpeg(new File(_album, name), gps(position[0], position[1]));
	}

	/** The GPS block of a position, in degrees, minutes and seconds. */
	static byte[] gps(double lat, double lon) throws IOException {
		double[] a = dms(Math.abs(lat));
		double[] o = dms(Math.abs(lon));
		return TestGeoLocation.gps(lat >= 0 ? "N" : "S", (int) a[0], (int) a[1], a[2], lon >= 0 ? "E" : "W", (int) o[0],
			(int) o[1], o[2]);
	}

	private static double[] dms(double value) {
		double degrees = Math.floor(value);
		double minutes = Math.floor((value - degrees) * 60);
		double seconds = ((value - degrees) * 60 - minutes) * 60;
		return new double[] { degrees, minutes, seconds };
	}

	private ImageServlet servlet() throws IOException {
		if (_servlet == null) {
			_servlet = new ImageServlet(_base.toFile(), new AuthService(AuthMode.OFF, _base));
			if (_places == null) {
				_places = Places.offline(_geonames);
			}
			_servlet.setPlaces(_places);
		}
		return _servlet;
	}

	private String albumJson(String acceptLanguage) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "json");
		Map<String, String> headers = acceptLanguage == null ? Map.of() : Map.of("Accept-Language", acceptLanguage);
		FakeResponse response = new FakeResponse();
		servlet().doGet(request("/" + ALBUM + "/", null, new byte[0], headers, parameters), response.response());
		assertEquals("Unexpected answer: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return response.body();
	}

	private FakeResponse put(String body) throws Exception {
		FakeResponse response = new FakeResponse();
		servlet().doPut(request("/" + ALBUM + "/", "application/json", body.getBytes(StandardCharsets.UTF_8),
			Map.of(), Collections.emptyMap()), response.response());
		return response;
	}

	static AlbumInfo parse(String body) throws IOException {
		Resource resource = Resource.readResource(new JsonReader(new ReaderAdapter(new StringReader(body))));
		assertTrue("Expected an album, got: " + resource, resource instanceof AlbumInfo);
		return (AlbumInfo) resource;
	}

	private static String json(AlbumInfo album) throws IOException {
		StringWriter out = new StringWriter();
		try (JsonWriter json = new JsonWriter(new WriterAdapter(out))) {
			album.writeTo(json);
		}
		return out.toString();
	}

	static ImagePart image(AlbumInfo album, String name) {
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart && ((ImagePart) part).getName().equals(name)) {
				return (ImagePart) part;
			}
		}
		fail("No image " + name + " in " + album);
		return null;
	}

	/** "KIND:name" of every tag. */
	static List<String> names(ImagePart image) {
		List<String> result = new ArrayList<>();
		if (image.getPlaces() != null) {
			for (PlaceTag tag : image.getPlaces().getTags()) {
				result.add(tag.getKind().protocolName() + ":" + tag.getName());
			}
		}
		return result;
	}

	/** The name of the most specific tag. */
	private static String last(ImagePart image) {
		assertNotNull(image.getName() + " has no places.", image.getPlaces());
		List<PlaceTag> tags = image.getPlaces().getTags();
		assertFalse(image.getPlaces().getPending(), tags.isEmpty());
		PlaceTag result = tags.get(tags.size() - 1);
		assertTrue(result.getKind() == PlaceKind.FEATURE || result.getKind() == PlaceKind.PLACE
			|| result.getKind() == PlaceKind.DISTRICT);
		return result.getName();
	}

	private static byte[] zip(String entry, byte[] content) throws IOException {
		java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
		try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(buffer)) {
			out.putNextEntry(new java.util.zip.ZipEntry(entry));
			out.write(content);
		}
		return buffer.toByteArray();
	}
}
