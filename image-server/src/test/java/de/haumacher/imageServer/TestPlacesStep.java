/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.haumacher.imageServer.pipeline.FolderPipeline;
import de.haumacher.imageServer.places.GeoNamesStore;
import de.haumacher.imageServer.places.Places;
import de.haumacher.imageServer.places.PlacesStep;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import junit.framework.TestCase;

/**
 * The warm-up of the gazetteer in the background work of a space, see issue #234 and
 * {@link PlacesStep}: the countries of a folder's photographs are downloaded before anybody opens
 * the album, and a server without a network backs off instead of trying in a loop.
 */
@SuppressWarnings("javadoc")
public class TestPlacesStep extends TestCase {

	private Path _root;

	private File _folder;

	private Path _geonames;

	private HttpServer _server;

	private final Map<String, byte[]> _files = new ConcurrentHashMap<>();

	private final List<String> _requests = Collections.synchronizedList(new ArrayList<>());

	private GeoNamesStore _store;

	private FolderPipeline _pipeline;

	private PlacesStep _step;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_root = Files.createTempDirectory("valbum-step");
		_folder = new File(_root.toFile(), "2002 Karlsruhe");
		assertTrue(_folder.mkdirs());
		TestGeoLocation.writeJpeg(new File(_folder, "schloss.jpg"), TestPlaceTags.gps(49.0134, 8.4044));
		TestGeoLocation.writeJpeg(new File(_folder, "nowhere.jpg"), null);
		_geonames = Files.createTempDirectory("valbum-step-geonames");
	}

	@Override
	protected void tearDown() throws Exception {
		if (_pipeline != null) {
			_pipeline.shutdown();
		}
		if (_step != null) {
			_step.close();
		}
		if (_store != null) {
			_store.close();
		}
		if (_server != null) {
			_server.stop(0);
		}
		TestPlaceTags.delete(_root);
		TestPlaceTags.delete(_geonames);
		super.tearDown();
	}

	/**
	 * Nobody opens the album: the pipeline asks for the files its photographs need, they are
	 * downloaded (only Germany's, nothing but file names in the requests), and the step is done.
	 */
	public void testTheCountriesOfAFolderAreFetchedInTheBackground() throws Exception {
		Path fixtures = TestPlaceTags.FIXTURES;
		_files.put(GeoNamesStore.COUNTRY_INFO, Files.readAllBytes(fixtures.resolve(GeoNamesStore.COUNTRY_INFO)));
		_files.put(GeoNamesStore.ADMIN1, Files.readAllBytes(fixtures.resolve(GeoNamesStore.ADMIN1)));
		_files.put(GeoNamesStore.ADMIN2, Files.readAllBytes(fixtures.resolve(GeoNamesStore.ADMIN2)));
		_files.put(GeoNamesStore.SHAPES,
			zip("shapes_simplified_low.json", Files.readAllBytes(fixtures.resolve("shapes_simplified_low.json"))));
		_files.put("DE.zip", zip("DE.txt", Files.readAllBytes(fixtures.resolve("DE.txt"))));
		_files.put("FR.zip", zip("FR.txt", Files.readAllBytes(fixtures.resolve("FR.txt"))));
		_server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		_server.createContext("/export/dump/", this::handle);
		_server.start();

		start(URI.create("http://127.0.0.1:" + _server.getAddress().getPort() + "/export/dump/"));
		_pipeline.notice(_folder, false);

		long end = System.currentTimeMillis() + 30_000;
		while (!_pipeline.isDone(_folder, PlacesStep.NAME) && System.currentTimeMillis() < end) {
			assertTrue(_store.awaitIdle(Duration.ofSeconds(20)));
			assertTrue(_pipeline.awaitIdle(20_000));
		}
		assertTrue("The step is done: " + _requests, _pipeline.isDone(_folder, PlacesStep.NAME));
		assertTrue(Files.isRegularFile(_geonames.resolve("DE.zip")));
		assertFalse("Only the country of the photographs.", Files.exists(_geonames.resolve("FR.zip")));
		for (String request : _requests) {
			assertFalse(request, request.contains("?") || request.contains("49.") || request.contains("8.40"));
		}
		assertTrue(_step.waiting().isEmpty());
		assertFalse("Nothing was written beside the photographs.", new File(_folder, "index.json").exists());
	}

	/**
	 * No network: the downloads fail, the folder waits, and asking again within the back-off makes
	 * no further attempt. No loop.
	 */
	public void testNoNetworkBacksOff() throws Exception {
		int port;
		try (ServerSocket socket = new ServerSocket(0)) {
			port = socket.getLocalPort();
		}
		start(URI.create("http://127.0.0.1:" + port + "/export/dump/"));
		_pipeline.notice(_folder, false);

		long end = System.currentTimeMillis() + 30_000;
		while (_store.generation() < 2 && System.currentTimeMillis() < end) {
			assertTrue(_store.awaitIdle(Duration.ofSeconds(20)));
			Thread.sleep(20);
		}
		assertEquals("The two files every lookup needs were tried once each.", 2, _store.generation());
		// The end of each attempt noticed the folder again; it runs, finds the files backing off,
		// and waits once more.
		Thread.sleep(500);
		assertTrue(_pipeline.awaitIdle(20_000));
		assertTrue(_store.awaitIdle(Duration.ofSeconds(20)));
		Thread.sleep(300);
		assertTrue(_pipeline.awaitIdle(20_000));
		assertEquals("No further attempt within the back-off.", 2, _store.generation());
		assertEquals(List.of(_folder), _step.waiting());
		assertFalse(_pipeline.isDone(_folder, PlacesStep.NAME));
		assertTrue(_store.problem(GeoNamesStore.COUNTRY_INFO), _store.problem(GeoNamesStore.COUNTRY_INFO).contains("failed"));

		// Noticed again (the album opened, a restart): still no attempt before the back-off ends.
		_pipeline.notice(_folder, false);
		Thread.sleep(200);
		assertTrue(_pipeline.awaitIdle(20_000));
		assertTrue(_store.awaitIdle(Duration.ofSeconds(20)));
		assertEquals(2, _store.generation());
	}

	private void start(URI base) {
		_store = new GeoNamesStore(_geonames, new GeoNamesStore.Settings().setBase(base).setGap(Duration.ZERO)
			.setFirstBackoff(Duration.ofMinutes(10)));
		Places places = new Places(_store);
		_pipeline = new FolderPipeline(_root, "places-test");
		_pipeline.setQuietMillis(20);
		_step = new PlacesStep(places, _pipeline);
		_pipeline.addStep(_step);
		assertTrue(_pipeline.start());
	}

	private void handle(HttpExchange exchange) throws IOException {
		String name = exchange.getRequestURI().getPath().substring("/export/dump/".length());
		_requests.add(exchange.getRequestURI().toString());
		try (exchange) {
			byte[] content = _files.get(name);
			if (content == null) {
				exchange.sendResponseHeaders(404, -1);
				return;
			}
			exchange.sendResponseHeaders(200, content.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(content);
			}
		}
	}

	private static byte[] zip(String entry, byte[] content) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (ZipOutputStream out = new ZipOutputStream(buffer)) {
			out.putNextEntry(new ZipEntry(entry));
			out.write(content);
		}
		return buffer.toByteArray();
	}
}
