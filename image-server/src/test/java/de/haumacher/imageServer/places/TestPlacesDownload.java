/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.places;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import junit.framework.TestCase;

/**
 * The download of the GeoNames files, see issue #234, against a local HTTP server that serves the
 * fixtures: fetched once and on demand, a failure backed off, a refresh by conditional request
 * that keeps the old data until the new has been read completely, and a file placed by hand that
 * is never replaced.
 */
@SuppressWarnings("javadoc")
public class TestPlacesDownload extends TestCase {

	private static final double LAT = 49.0134;

	private static final double LON = 8.4044;

	/** A clock the test moves. */
	static final class TestClock extends Clock {

		volatile Instant _now = Instant.parse("2026-10-07T12:00:00Z");

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return _now;
		}

		void advance(Duration duration) {
			_now = _now.plus(duration);
		}
	}

	/** A file the server answers with. */
	record Served(byte[] content, String etag) {
		// A pair.
	}

	private HttpServer _server;

	private final Map<String, Served> _files = new ConcurrentHashMap<>();

	/** Status codes to answer the next requests of a file with instead of the file. */
	private final Map<String, Integer> _failures = new ConcurrentHashMap<>();

	/** Every request: "<path> <If-None-Match or ->". */
	private final List<String> _requests = Collections.synchronizedList(new ArrayList<>());

	private Path _dir;

	private TestClock _clock;

	private GeoNamesStore _store;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		Path fixtures = TestPlacesLookup.FIXTURES;
		serve(GeoNamesStore.COUNTRY_INFO, Files.readAllBytes(fixtures.resolve(GeoNamesStore.COUNTRY_INFO)));
		serve(GeoNamesStore.ADMIN1, Files.readAllBytes(fixtures.resolve(GeoNamesStore.ADMIN1)));
		serve(GeoNamesStore.ADMIN2, Files.readAllBytes(fixtures.resolve(GeoNamesStore.ADMIN2)));
		serve(GeoNamesStore.SHAPES,
			zip("shapes_simplified_low.json", Files.readAllBytes(fixtures.resolve("shapes_simplified_low.json"))));
		serve("DE.zip", zip("DE.txt", Files.readAllBytes(fixtures.resolve("DE.txt"))));
		serve("FR.zip", zip("FR.txt", Files.readAllBytes(fixtures.resolve("FR.txt"))));

		_server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		_server.createContext("/export/dump/", this::handle);
		_server.start();

		_dir = Files.createTempDirectory("geonames");
		_clock = new TestClock();
		_store = newStore();
	}

	@Override
	protected void tearDown() throws Exception {
		_store.close();
		_server.stop(0);
		TestPlacesLookup.delete(_dir);
		super.tearDown();
	}

	private GeoNamesStore newStore() {
		URI base = URI.create("http://127.0.0.1:" + _server.getAddress().getPort() + "/export/dump/");
		return new GeoNamesStore(_dir, new GeoNamesStore.Settings().setBase(base).setGap(Duration.ZERO)
			.setFirstBackoff(Duration.ofMinutes(10)).setMaxBackoff(Duration.ofHours(4)).setClock(_clock));
	}

	private int etags;

	private void serve(String name, byte[] content) {
		_files.put(name, new Served(content, "\"v" + (++etags) + "\""));
	}

	private void handle(HttpExchange exchange) throws IOException {
		String name = exchange.getRequestURI().getPath().substring("/export/dump/".length());
		String condition = exchange.getRequestHeaders().getFirst("If-None-Match");
		_requests.add(exchange.getRequestURI() + " " + (condition == null ? "-" : condition));
		try (exchange) {
			Integer failure = _failures.remove(name);
			if (failure != null) {
				exchange.sendResponseHeaders(failure.intValue(), -1);
				return;
			}
			Served served = _files.get(name);
			if (served == null) {
				exchange.sendResponseHeaders(404, -1);
				return;
			}
			if (served.etag().equals(condition)) {
				exchange.sendResponseHeaders(304, -1);
				return;
			}
			exchange.getResponseHeaders().add("ETag", served.etag());
			exchange.getResponseHeaders().add("Last-Modified", "Wed, 07 Oct 2026 02:03:35 GMT");
			exchange.sendResponseHeaders(200, served.content().length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(served.content());
			}
		}
	}

	static byte[] zip(String entry, byte[] content) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (ZipOutputStream out = new ZipOutputStream(buffer)) {
			out.putNextEntry(new ZipEntry("readme.txt"));
			out.write("GeoNames, CC BY 4.0".getBytes(StandardCharsets.UTF_8));
			out.putNextEntry(new ZipEntry(entry));
			out.write(content);
		}
		return buffer.toByteArray();
	}

	private int requests(String name) {
		int result = 0;
		synchronized (_requests) {
			for (String request : _requests) {
				if (request.startsWith("/export/dump/" + name + " ")) {
					result++;
				}
			}
		}
		return result;
	}

	/** Looks up until the result is there, waiting for the downloads each lookup asks for. */
	private PlaceResult settle(Places places) throws InterruptedException {
		PlaceResult result = null;
		for (int n = 0; n < 5; n++) {
			result = places.lookup(LAT, LON);
			if (result.isAvailable()) {
				return result;
			}
			assertTrue(_store.awaitIdle(Duration.ofSeconds(20)));
		}
		return result;
	}

	private Places fetched() throws InterruptedException {
		Places places = new Places(_store);
		PlaceResult first = places.lookup(LAT, LON);
		assertFalse(first.isAvailable());
		assertTrue(first.getReason(), first.getReason().contains("being downloaded"));
		PlaceResult result = settle(places);
		assertTrue(result.toString(), result.isAvailable());
		assertEquals("Karlsruhe", result.get(PlaceKind.PLACE).name());
		return places;
	}

	/**
	 * The files needed are fetched once each, only the country the position lies in, nothing but
	 * file names in the requests; then they are used from the directory, by a restarted server as
	 * well.
	 */
	public void testFetchedOnceAndCached() throws Exception {
		Places places = fetched();
		for (String name : List.of(GeoNamesStore.COUNTRY_INFO, GeoNamesStore.SHAPES, GeoNamesStore.ADMIN1,
			GeoNamesStore.ADMIN2, "DE.zip")) {
			assertEquals(name, 1, requests(name));
			assertTrue(name, Files.isRegularFile(_dir.resolve(name)));
			assertTrue(name, Files.isRegularFile(_dir.resolve(name + GeoNamesStore.RECORD_SUFFIX)));
		}
		assertEquals(0, requests("FR.zip"));
		for (String request : _requests) {
			assertFalse(request, request.contains("?") || request.contains("49.") || request.contains("8.40"));
		}
		int before = _requests.size();
		for (int n = 0; n < 20; n++) {
			assertTrue(places.lookup(LAT + n * 0.001, LON).isAvailable());
		}
		assertTrue(_store.awaitIdle(Duration.ofSeconds(5)));
		assertEquals(before, _requests.size());

		_store.close();
		_store = newStore();
		assertTrue(new Places(_store).lookup(LAT, LON).isAvailable());
		assertTrue(_store.awaitIdle(Duration.ofSeconds(5)));
		assertEquals(before, _requests.size());
		assertTrue(_store.status().toString(), _store.status().stream()
			.anyMatch(s -> s.name().equals("DE.zip") && s.present() && s.state().startsWith("downloaded")));
	}

	/** A failed download is reported, not retried before its back-off, and then retried. */
	public void testFailureBackoff() throws Exception {
		_failures.put("DE.zip", Integer.valueOf(503));
		Places places = new Places(_store);
		PlaceResult result = settle(places);
		assertFalse(result.isAvailable());
		assertTrue(result.getReason(), result.getReason().contains("failed"));
		assertTrue(result.getReason(), result.getReason().contains("HTTP 503"));
		assertTrue(result.getReason(), result.getReason().contains("Germany (DE)"));
		assertEquals(1, requests("DE.zip"));

		// Asked for again and again within the back-off: no request.
		for (int n = 0; n < 10; n++) {
			assertFalse(places.lookup(LAT, LON).isAvailable());
		}
		assertTrue(_store.awaitIdle(Duration.ofSeconds(5)));
		assertEquals(1, requests("DE.zip"));
		assertTrue(_store.status().toString(),
			_store.status().stream().anyMatch(s -> s.name().equals("DE.zip") && s.state().startsWith("failed")));

		// A second failure doubles the back-off.
		_clock.advance(Duration.ofMinutes(11));
		_failures.put("DE.zip", Integer.valueOf(500));
		assertFalse(settle(places).isAvailable());
		assertEquals(2, requests("DE.zip"));
		_clock.advance(Duration.ofMinutes(11));
		assertFalse(places.lookup(LAT, LON).isAvailable());
		assertTrue(_store.awaitIdle(Duration.ofSeconds(5)));
		assertEquals(2, requests("DE.zip"));

		_clock.advance(Duration.ofMinutes(10));
		PlaceResult later = settle(places);
		assertTrue(later.toString(), later.isAvailable());
		assertEquals(3, requests("DE.zip"));
	}

	/** An unchanged file costs a 304 at the refresh, and the old file stays as it is. */
	public void testRefreshNotModified() throws Exception {
		Places places = fetched();
		String version = places.lookup(LAT, LON).getVersion();
		byte[] before = Files.readAllBytes(_dir.resolve("DE.zip"));

		_clock.advance(Duration.ofDays(30));
		places.lookup(LAT, LON);
		assertTrue(_store.awaitIdle(Duration.ofSeconds(5)));
		assertEquals("not due before 90 days", 1, requests("DE.zip"));

		_clock.advance(Duration.ofDays(61));
		assertTrue(places.lookup(LAT, LON).isAvailable());
		assertTrue(_store.awaitIdle(Duration.ofSeconds(20)));
		assertEquals(2, requests("DE.zip"));
		assertTrue(_requests.toString(), _requests.contains("/export/dump/DE.zip " + _files.get("DE.zip").etag()));
		assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(_dir.resolve("DE.zip"))));
		assertEquals(version, places.lookup(LAT, LON).getVersion());
		assertTrue(places.isCurrent(version));

		// Checked now: not again before another 90 days.
		places.lookup(LAT, LON);
		assertTrue(_store.awaitIdle(Duration.ofSeconds(5)));
		assertEquals(2, requests("DE.zip"));
	}

	/** New content at the refresh is a new version; the old one is outdated. */
	public void testRefreshNewVersion() throws Exception {
		Places places = fetched();
		String version = places.lookup(LAT, LON).getVersion();

		String added = "13600000\tSchlossplatz\tSchlossplatz\t\t49.0128\t8.4046\tS\tSQR\tDE\t\t01\t082\t08212\t08212000\t0\t\t115\t"
			+ "Europe/Berlin\t2026-10-07\n";
		serve("DE.zip", zip("DE.txt", (Files.readString(TestPlacesLookup.FIXTURES.resolve("DE.txt")) + added)
			.getBytes(StandardCharsets.UTF_8)));
		_clock.advance(Duration.ofDays(91));
		// The old data answers while the new is fetched: no gap.
		PlaceResult during = places.lookup(LAT, LON);
		assertTrue(during.isAvailable());
		assertTrue(_store.awaitIdle(Duration.ofSeconds(20)));

		PlaceResult after = places.lookup(LAT, LON);
		assertTrue(after.isAvailable());
		assertFalse(version.equals(after.getVersion()));
		assertTrue(after.getVersion(), after.getVersion().contains("/DE/2026-10-07-"));
		assertFalse(places.isCurrent(version));
		assertTrue(places.isCurrent(after.getVersion()));
		// The cache file was written for the new data, and a restarted server reads it.
		_store.close();
		_store = newStore();
		assertEquals(after.getVersion(), new Places(_store).lookup(LAT, LON).getVersion());
	}

	/** A broken new download is dropped: the old data stays in use, and the failure is reported. */
	public void testRefreshCorrupt() throws Exception {
		Places places = fetched();
		String version = places.lookup(LAT, LON).getVersion();
		byte[] before = Files.readAllBytes(_dir.resolve("DE.zip"));

		serve("DE.zip", "<html>Service temporarily unavailable</html>".getBytes(StandardCharsets.UTF_8));
		_clock.advance(Duration.ofDays(91));
		assertTrue(places.lookup(LAT, LON).isAvailable());
		assertTrue(_store.awaitIdle(Duration.ofSeconds(20)));
		assertEquals(2, requests("DE.zip"));

		PlaceResult after = places.lookup(LAT, LON);
		assertTrue(after.isAvailable());
		assertEquals(version, after.getVersion());
		assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(_dir.resolve("DE.zip"))));
		assertFalse(Files.exists(_dir.resolve("DE.zip" + GeoNamesStore.PART_SUFFIX)));
		assertTrue(_store.status().toString(), _store.status().stream()
			.anyMatch(s -> s.name().equals("DE.zip") && s.present() && s.state().startsWith("refresh failed")));

		// A zip that holds a broken country file is dropped just the same.
		serve("DE.zip", zip("DE.txt", "no\tgeonames\n".getBytes(StandardCharsets.UTF_8)));
		_clock.advance(Duration.ofDays(1));
		assertTrue(places.lookup(LAT, LON).isAvailable());
		assertTrue(_store.awaitIdle(Duration.ofSeconds(20)));
		assertEquals(3, requests("DE.zip"));
		assertEquals(version, places.lookup(LAT, LON).getVersion());
	}

	/** A file placed by hand has no record of a download and is never replaced. */
	public void testHandPlacedNeverReplaced() throws Exception {
		for (String name : TestPlacesLookup.FILES) {
			Files.copy(TestPlacesLookup.FIXTURES.resolve(name), _dir.resolve(name));
		}
		Places places = new Places(_store);
		assertTrue(places.lookup(LAT, LON).isAvailable());
		_clock.advance(Duration.ofDays(400));
		assertTrue(places.lookup(LAT, LON).isAvailable());
		assertTrue(_store.awaitIdle(Duration.ofSeconds(5)));
		assertEquals(_requests.toString(), 0, _requests.size());
		assertTrue(_store.status().toString(),
			_store.status().stream().anyMatch(s -> s.name().equals("DE.txt") && s.state().equals("placed by hand")));
	}
}
