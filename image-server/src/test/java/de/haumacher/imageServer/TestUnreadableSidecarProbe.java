/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Probe for issue #254, composed with the search of issue #227: an album whose index.json cannot
 * be read is skipped by a search over the library and by a saved search, which answer what the
 * other albums hold, and the broken file is never written.
 */
@SuppressWarnings("javadoc")
public class TestUnreadableSidecarProbe extends TestSavedSearch {

	/** This probe's own test alone: the tests of {@link TestSavedSearch} run there, not twice. */
	public static junit.framework.Test suite() {
		return TestUnreadableSidecarViews.own(TestUnreadableSidecarProbe.class);
	}

	public void testASearchOverTheLibrarySkipsTheUnreadableAlbum() throws Exception {
		createSearch("/AP", and(person(_anne), person(_philipp)));
		Path sidecar = _base.resolve(GARDEN).resolve("index.json");
		byte[] broken = "[\"AlbumInfo\",{\"title\":\"Garden\",\"parts\":[".getBytes(StandardCharsets.UTF_8);
		Files.write(sidecar, broken);
		assertTrue(sidecar.toFile().setLastModified(System.currentTimeMillis() + 10_000));

		FakeResponse live = search("/", and(person(_anne), person(_philipp)), SharingFixture.ALICE);
		assertEquals(live.body(), HttpServletResponse.SC_OK, live.status());
		assertEquals(Arrays.asList("Trips/2021 Lake/old.jpg", "Trips/2021 Lake/anne_philipp.jpg",
			"Trips/2021 Lake/private_ap.jpg"), names(album(live)));

		FakeResponse saved = get("/AP/", "json", SharingFixture.ALICE);
		assertEquals(saved.body(), HttpServletResponse.SC_OK, saved.status());
		assertEquals(Arrays.asList("Trips/2021 Lake/old.jpg", "Trips/2021 Lake/anne_philipp.jpg",
			"Trips/2021 Lake/private_ap.jpg"), names(album(saved)));

		FakeResponse refused = get("/" + GARDEN + "/", "json", SharingFixture.ALICE);
		assertEquals(422, refused.status());
		assertTrue(refused.body(), refused.body().contains("repair or remove it"));
		assertTrue("Never written.", Arrays.equals(broken, Files.readAllBytes(sidecar)));
	}
}
