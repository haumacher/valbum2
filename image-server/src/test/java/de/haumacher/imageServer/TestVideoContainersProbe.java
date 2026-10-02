/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Probes of #189 the delivery did not write: a .MOV moved into another album and a trashed .3gp
 * purged with everything generated for it.
 */
@SuppressWarnings("javadoc")
public class TestVideoContainersProbe extends ShareTestCase {

	private static final File FIXTURES = new File("src/test/fixtures/video");

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		Files.createDirectories(_base.resolve("A"));
		Files.createDirectories(_base.resolve("B"));
	}

	public void testAMovedMovIsShownInItsNewAlbum() throws Exception {
		Files.copy(new File(FIXTURES, "apple.mov").toPath(), _base.resolve("A/IMG_1.MOV"));
		assertEquals(HttpServletResponse.SC_OK, get("/A/IMG_1.MOV", "tn", SharingFixture.ALICE).status());
		String before = get("/A/", "json", SharingFixture.ALICE).body();
		assertTrue(before, before.contains("IMG_1.MOV"));

		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "move");
		FakeResponse moved = post("/A/", "{\"target\":\"B\",\"names\":[{\"name\":\"IMG_1.MOV\"}]}",
			SharingFixture.ALICE, parameters);
		assertEquals(moved.body(), HttpServletResponse.SC_OK, moved.status());

		String after = get("/B/", "json", SharingFixture.ALICE).body();
		assertTrue(after, after.contains("IMG_1.MOV"));
		assertTrue("The Apple date travels along: " + after, after.contains("\"location\""));
		assertEquals(HttpServletResponse.SC_OK, get("/B/IMG_1.MOV", "tn", SharingFixture.ALICE).status());
		FakeResponse original = get("/B/IMG_1.MOV", null, SharingFixture.ALICE);
		assertEquals(HttpServletResponse.SC_OK, original.status());
		assertEquals("video/quicktime", original.contentType());
	}

	public void testAPurged3gpTakesItsPosterAlong() throws Exception {
		Files.copy(new File(FIXTURES, "clip.3gp").toPath(), _base.resolve("A/clip.3gp"));
		Files.copy(new File(FIXTURES, "clip.m4v").toPath(), _base.resolve("A/keep.m4v"));
		assertEquals(HttpServletResponse.SC_OK, get("/A/clip.3gp", "tn", SharingFixture.ALICE).status());

		String json = get("/A/", "json", SharingFixture.ALICE).body();
		String trashed = json.replaceFirst("(\"name\":\\s*\"clip.3gp\"[^}]*\"rating\":\\s*)-?\\d+", "$1-2");
		if (trashed.equals(json)) {
			trashed = json.replaceFirst("\"name\":\\s*\"clip.3gp\"", "\"name\": \"clip.3gp\", \"rating\": -2");
		}
		FakeResponse put = put("/A/", trashed, SharingFixture.ALICE);
		assertEquals(put.body(), HttpServletResponse.SC_OK, put.status());

		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "purge");
		FakeResponse purge = post("/A/", "", SharingFixture.ALICE, parameters);
		assertEquals(purge.body(), HttpServletResponse.SC_OK, purge.status());

		assertFalse(Files.exists(_base.resolve("A/clip.3gp")));
		assertTrue(Files.exists(_base.resolve("A/keep.m4v")));
		Path cache = _base.resolve("A").resolve(PreviewCache.CACHE_DIRECTORY_NAME);
		try (var listing = Files.list(cache)) {
			listing.forEach(file -> assertFalse("Left behind: " + file,
				file.getFileName().toString().contains("clip.3gp")));
		}
		assertTrue(CacheRefresh.isGenerated("video-clip.3gp.mp4"));
		assertTrue(CacheRefresh.isGenerated("teaser-IMG_1.MOV.mp4"));
	}
}
