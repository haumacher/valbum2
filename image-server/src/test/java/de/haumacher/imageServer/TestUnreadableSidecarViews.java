/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.FolderInfo;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import junit.framework.TestSuite;

/**
 * Test case for issue #254 in the answers drawn from many albums: an album whose
 * <code>index.json</code> cannot be read is left out of each of them, and the others are answered.
 * Only a request addressing that album (or a photograph of it) by name is refused.
 *
 * <p>
 * The library of {@link TestSavedSearch}: <code>Trips/2021 Lake</code> and
 * <code>Family/2024 Garden</code>, whose sidecar is cut off by hand here.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestUnreadableSidecarViews extends TestSavedSearch {

	/** What Anne and Philipp are found in once the garden cannot be read. */
	private static final List<String> LAKE_AP = Arrays.asList("Trips/2021 Lake/old.jpg",
		"Trips/2021 Lake/anne_philipp.jpg", "Trips/2021 Lake/private_ap.jpg");

	private static final byte[] BROKEN =
		"[\"AlbumInfo\",{\"title\":\"Garden\",\"parts\":[".getBytes(StandardCharsets.UTF_8);

	/** This class's own tests alone: the tests of {@link TestSavedSearch} run there, not twice. */
	public static junit.framework.Test suite() {
		return own(TestUnreadableSidecarViews.class);
	}

	/** A suite of the test methods the given class declares itself. */
	static junit.framework.Test own(Class<? extends junit.framework.TestCase> type) {
		TestSuite suite = new TestSuite(type.getName());
		for (Method method : type.getDeclaredMethods()) {
			if (method.getName().startsWith("test") && Modifier.isPublic(method.getModifiers())
				&& method.getParameterCount() == 0) {
				suite.addTest(TestSuite.createTest(type, method.getName()));
			}
		}
		return suite;
	}

	/** The live search, read from the index of the space the background built before the break. */
	public void testAnIndexedSearchSkipsTheUnreadableAlbum() throws Exception {
		servlet().index().indexNow();
		assertTrue(names(search("/", and(person(_anne), person(_philipp)))).contains("Family/2024 Garden/both.jpg"));

		Path sidecar = breakGarden();

		assertEquals(LAKE_AP, names(search("/", and(person(_anne), person(_philipp)))));
		assertNeverWritten(sidecar);
	}

	/** A photograph addressed through a saved search: one of a readable album is served. */
	public void testAPhotoOfASavedSearchIsServedFromAReadableAlbum() throws Exception {
		createSearch("/AP", and(person(_anne), person(_philipp)));
		Path sidecar = breakGarden();

		FakeResponse lake = get("/AP/Trips/2021 Lake/old.jpg", null, SharingFixture.ALICE);
		assertEquals(lake.body(), HttpServletResponse.SC_OK, lake.status());

		FakeResponse garden = get("/AP/Family/2024 Garden/both.jpg", "tn", SharingFixture.ALICE);
		assertEquals("Not found through the search, never shown: " + garden.body(), HttpServletResponse.SC_NOT_FOUND,
			garden.status());
		assertNeverWritten(sidecar);
	}

	/** The archive of a saved search's photographs of readable albums. */
	public void testTheZipOfASavedSearchSkipsTheUnreadableAlbum() throws Exception {
		createSearch("/AP", and(person(_anne), person(_philipp)));
		Path sidecar = breakGarden();
		Map<String, String> zip = new HashMap<>();
		zip.put("action", ImageServlet.ZIP_ACTION);

		FakeResponse response = post("/AP/", "{\"names\":[{\"name\":\"Trips/2021 Lake/old.jpg\"}]}",
			SharingFixture.ALICE, zip);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());

		FakeResponse named = post("/AP/", "{\"names\":[{\"name\":\"Family/2024 Garden/both.jpg\"}]}",
			SharingFixture.ALICE, zip);
		assertEquals("A photograph of it is no match any more: " + named.body(), HttpServletResponse.SC_NOT_FOUND,
			named.status());
		assertNeverWritten(sidecar);
	}

	/**
	 * The archive of photographs found below a folder: those of readable albums are packed; one
	 * named in the unreadable album refuses the archive (its privacy cannot be read), never 500.
	 */
	public void testTheZipOfASearchViewSkipsNothingSilently() throws Exception {
		Path sidecar = breakGarden();
		Map<String, String> zip = new HashMap<>();
		zip.put("action", ImageServlet.ZIP_ACTION);

		FakeResponse lake = post("/", "{\"names\":[{\"name\":\"Trips/2021 Lake/old.jpg\"}]}", SharingFixture.ALICE, zip);
		assertEquals(lake.body(), HttpServletResponse.SC_OK, lake.status());

		FakeResponse garden =
			post("/", "{\"names\":[{\"name\":\"Family/2024 Garden/both.jpg\"}]}", SharingFixture.ALICE, zip);
		assertEquals(garden.body(), 422, garden.status());
		assertNeverWritten(sidecar);
	}

	/** What a search below the root offers: read off the readable albums. */
	public void testTheSearchOptionsSkipTheUnreadableAlbum() throws Exception {
		Path sidecar = breakGarden();
		FakeResponse options = get("/", ImageServlet.SEARCH_OPTIONS_TYPE, SharingFixture.ALICE);
		assertEquals(options.body(), HttpServletResponse.SC_OK, options.status());
		assertFalse("Nothing of the garden: " + options.body(), options.body().contains("\"Garden\""));
		assertTrue(options.body(), options.body().contains("Holiday"));
		assertNeverWritten(sidecar);
	}

	/**
	 * The tile of a saved search in a listing whose stored picture lies in an unreadable album: the
	 * search icon, never an error (its privacy cannot be read), see issue #287.
	 */
	public void testTheTileOfASavedSearchSkipsTheUnreadableAlbum() throws Exception {
		createSearch("/AP", and(person(_anne), person(_philipp)));
		AlbumInfo answer = album(get("/AP/", "json", SharingFixture.ALICE));
		answer.setIndexPicture(PrivacyFilter.thumbnail(image(answer, "Family/2024 Garden/both.jpg")));
		assertEquals(HttpServletResponse.SC_OK, put("/AP/", write(answer), SharingFixture.ALICE).status());
		assertEquals("The garden's before.", "Family/2024 Garden/both.jpg",
			entry(listing(get("/", "json", SharingFixture.ALICE)), "AP").getIndexPicture().getImage());
		Path sidecar = breakGarden();

		FakeResponse root = get("/", "json", SharingFixture.ALICE);
		assertEquals(root.body(), HttpServletResponse.SC_OK, root.status());
		FolderInfo tile = entry(listing(root), "AP");
		assertNull(tile.getIndexPicture());
		assertNeverWritten(sidecar);
	}

	/** A collection answers its photographs of readable albums; its tile is answered as well. */
	public void testACollectionSkipsTheUnreadableAlbum() throws Exception {
		assertEquals(HttpServletResponse.SC_OK,
			put("/Best", "[\"AlbumInfo\",{\"kind\":\"COLLECTION\",\"title\":\"Best\"}]", SharingFixture.ALICE).status());
		Map<String, String> collect = new HashMap<>();
		collect.put("action", ImageServlet.COLLECT_ACTION);
		moveResult(post("/Trips/", "{\"target\":\"Best\",\"names\":[{\"name\":\"2021 Lake/old.jpg\"}]}",
			SharingFixture.ALICE, collect));
		moveResult(post("/Family/", "{\"target\":\"Best\",\"names\":[{\"name\":\"2024 Garden/both.jpg\"}]}",
			SharingFixture.ALICE, collect));
		assertEquals(Arrays.asList("old.jpg", "both.jpg"), names(album(get("/Best/", "json", SharingFixture.ALICE))));
		Path sidecar = breakGarden();

		FakeResponse best = get("/Best/", "json", SharingFixture.ALICE);
		assertEquals(best.body(), HttpServletResponse.SC_OK, best.status());
		assertEquals(Arrays.asList("old.jpg"), names(album(best)));
		FakeResponse root = get("/", "json", SharingFixture.ALICE);
		assertEquals(root.body(), HttpServletResponse.SC_OK, root.status());
		assertNotNull(entry(listing(root), "Best"));
		assertNeverWritten(sidecar);
	}

	/**
	 * An edit written through a saved search by a client that still shows a photograph of the
	 * album that broke meanwhile: the readable album is written, the unreadable one never.
	 */
	public void testAnEditThroughASavedSearchSkipsTheUnreadableAlbum() throws Exception {
		createSearch("/AP", and(person(_anne), person(_philipp)));
		de.haumacher.imageServer.shared.model.AlbumInfo answer = album(get("/AP/", "json", SharingFixture.ALICE));
		image(answer, "Trips/2021 Lake/old.jpg").setRating(2);
		image(answer, "Family/2024 Garden/both.jpg").setRating(2);
		Path sidecar = breakGarden();

		FakeResponse put = put("/AP/", write(answer), SharingFixture.ALICE);
		assertEquals(put.body(), HttpServletResponse.SC_OK, put.status());
		assertEquals(2, image(album(get("/" + LAKE + "/", "json", SharingFixture.ALICE)), "old.jpg").getRating());
		assertNeverWritten(sidecar);
	}

	/** A re-reading of the whole library goes past the unreadable album and never writes it. */
	public void testAReanalysisOfTheLibrarySkipsTheUnreadableAlbum() throws Exception {
		Path sidecar = breakGarden();
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "reanalyze");
		FakeResponse response = post("/", "{}", SharingFixture.ALICE, parameters);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		assertNeverWritten(sidecar);
	}

	/** The listing above shows the folder holding it; the album itself is refused. */
	public void testTheListingsAboveAreAnswered() throws Exception {
		Path sidecar = breakGarden();
		assertEquals(HttpServletResponse.SC_OK, get("/", "json", SharingFixture.ALICE).status());
		FakeResponse family = get("/Family/", "json", SharingFixture.ALICE);
		assertEquals(family.body(), HttpServletResponse.SC_OK, family.status());
		assertNull("Nothing out of it is shown.", entry(listing(family), "2024 Garden").getIndexPicture());
		assertEquals(422, get("/" + GARDEN + "/", "json", SharingFixture.ALICE).status());
		assertNeverWritten(sidecar);
	}

	// --- Helpers. ---

	private Path breakGarden() throws Exception {
		Path sidecar = _base.resolve(GARDEN).resolve("index.json");
		Files.write(sidecar, BROKEN);
		assertTrue(sidecar.toFile().setLastModified(System.currentTimeMillis() + 10_000));
		return sidecar;
	}

	private static void assertNeverWritten(Path sidecar) throws Exception {
		assertTrue("Never written.", Arrays.equals(BROKEN, Files.readAllBytes(sidecar)));
		try (java.util.stream.Stream<Path> files = Files.list(sidecar.getParent())) {
			assertEquals("No backup either.", 0,
				files.filter(file -> file.getFileName().toString().startsWith("index.json.")).count());
		}
	}
}
