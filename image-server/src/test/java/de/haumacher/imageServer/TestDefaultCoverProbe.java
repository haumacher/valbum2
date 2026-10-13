/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.pipeline.Background;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Probe of issue #260: a year folder of albums, some with a sidecar that chose no cover, some
 * without; every tile has a picture whose preview is served, before and after an album is retitled.
 */
public class TestDefaultCoverProbe extends TestCase {

	private static final File FIXTURE = new File("src/test/fixtures/test-album/2005-08-24 Blumen und Fliegen");

	private Path _base;

	private ImageServlet _servlet;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-default-cover-probe");
	}

	@Override
	protected void tearDown() throws Exception {
		Background.setQuietMillis(Background.QUIET_MILLIS);
		if (_servlet != null) {
			_servlet.destroy();
		}
		try (Stream<Path> files = Files.walk(_base)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		super.tearDown();
	}

	public void testYearOfAlbumsAllShowAServedPicture() throws Exception {
		copy("2026/2026-01-02 Plain");
		copy("2026/2026-02-03 Titled");
		Files.write(_base.resolve("2026/2026-02-03 Titled/index.json"),
			"[\"AlbumInfo\",{\"title\":\"Titled\",\"starred\":true}]".getBytes(StandardCharsets.UTF_8));
		copy("2026/2026-03-04 Retitled");
		_servlet = new ImageServlet(_base.toFile());

		assertAllTilesServed();

		String answered = get("/2026/2026-03-04 Retitled/", "json").body();
		AlbumInfo album = (AlbumInfo) read(answered);
		assertNull(album.getIndexPicture());
		put("/2026/2026-03-04 Retitled/", answered.replaceFirst("\"title\":\"[^\"]*\"", "\"title\":\"New title\""));

		assertAllTilesServed();
	}

	private void assertAllTilesServed() throws Exception {
		ListingInfo year = (ListingInfo) read(get("/2026/", "json").body());
		assertEquals(3, year.getFolders().size());
		for (FolderInfo tile : year.getFolders()) {
			assertNotNull("Tile without picture: " + tile.getName(), tile.getIndexPicture());
			FakeResponse preview =
				get("/2026/" + tile.getName() + "/" + tile.getIndexPicture().getImage(), "tn");
			assertTrue(tile.getName() + ": " + preview.body(), preview.status() == HttpServletResponse.SC_OK);
		}
	}

	private void copy(String name) throws Exception {
		Path folder = _base.resolve(name);
		Files.createDirectories(folder);
		for (File file : FIXTURE.listFiles()) {
			if (file.isFile() && !file.getName().startsWith(".") && !file.getName().equals("index.json")) {
				Files.copy(file.toPath(), folder.resolve(file.getName()));
			}
		}
	}

	private FakeResponse get(String pathInfo, String type) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", type);
		FakeResponse response = new FakeResponse();
		_servlet.doGet(TestImageServletPut.request(pathInfo, null, new byte[0], Collections.emptyMap(), parameters),
			response.response());
		return response;
	}

	private void put(String pathInfo, String body) throws Exception {
		FakeResponse response = new FakeResponse();
		_servlet.doPut(TestImageServletPut.request(pathInfo, "application/json",
			body.getBytes(StandardCharsets.UTF_8), Collections.emptyMap(), Collections.emptyMap()),
			response.response());
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
	}

	private static Resource read(String json) throws Exception {
		return Resource.readResource(new JsonReader(new ReaderAdapter(new StringReader(json))));
	}
}
