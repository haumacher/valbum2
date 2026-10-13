/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.faces.PeopleStore;
import de.haumacher.imageServer.index.SpaceIndex;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.LabelName;
import de.haumacher.imageServer.shared.model.Person;
import de.haumacher.imageServer.shared.model.SearchCamera;
import de.haumacher.imageServer.shared.model.SearchLabel;
import de.haumacher.imageServer.shared.model.SearchOptions;
import de.haumacher.imageServer.shared.model.SearchQuery;
import de.haumacher.imageServer.shared.model.SearchRating;
import de.haumacher.imageServer.shared.model.SearchText;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test case for the search index in the index of the space, see issue #264: kept on disk and
 * reopened without a rebuild, its text in Lucene rebuilt when broken, written through by every
 * edit of the server with no background running, analysed for German and English, and never
 * offering what a caller may not see.
 *
 * <p>
 * The library of {@link TestSavedSearch}; its tests run there, not here again.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestSearchIndex extends TestSavedSearch {

	/** This class's own tests alone. */
	public static junit.framework.Test suite() {
		return TestUnreadableSidecarViews.own(TestSearchIndex.class);
	}

	/** What Anne is found in, the whole library. */
	private static final List<String> ANNE = Arrays.asList("Trips/2021 Lake/old.jpg", "Trips/2021 Lake/anne_philipp.jpg",
		"Trips/2021 Lake/anne.jpg", "Trips/2021 Lake/private_ap.jpg", "Family/2024 Garden/garden.jpg",
		"Family/2024 Garden/both.jpg");

	// --- On disk. ---

	public void testARestartAnswersWithoutARebuild() throws Exception {
		servlet().searchIndex().build();
		List<String> lake = search("/", and(text("lake")));
		assertEquals(Arrays.asList("Trips/2021 Lake/anne_philipp.jpg", "Trips/2021 Lake/clip.mp4"), lake);
		stopServer();

		SearchIndex index = servlet().searchIndex();
		assertFalse("Reopened, not built again.", servlet().spaceIndex().isFresh());
		index.setSyncOnRequest(Boolean.FALSE);
		assertEquals("Answered from disk before anything is read.", ANNE, search("/", and(person(_anne))));
		assertEquals(lake, search("/", and(text("lake"))));
		assertEquals(0, index.reads());

		index.setSyncOnRequest(null);
		assertEquals("Where the search does the background's work, it finds nothing changed.", ANNE,
			search("/", and(person(_anne))));
		assertEquals("No sidecar read into the index.", 0, index.reads());
	}

	public void testACorruptTextIndexIsRebuilt() throws Exception {
		servlet().searchIndex().build();
		stopServer();
		Path lucene = SpaceIndex.directoryOf(_base).resolve(SpaceIndex.TEXT_DIRECTORY_NAME);
		Random random = new Random(264);
		try (DirectoryStream<Path> files = Files.newDirectoryStream(lucene)) {
			for (Path file : files) {
				byte[] garbage = new byte[(int) Math.max(64, Files.size(file))];
				random.nextBytes(garbage);
				Files.write(file, garbage);
			}
		}

		SearchIndex index = servlet().searchIndex();
		assertFalse("The database is whole.", servlet().spaceIndex().isFresh());
		assertTrue("The text index is on disk again.", servlet().spaceIndex().isTextPersistent());
		assertEquals("Whatever lost its text is read again.", Arrays.asList("Trips/2021 Lake/anne_philipp.jpg",
			"Trips/2021 Lake/clip.mp4"), search("/", and(text("lake"))));
		assertTrue(index.reads() > 0);
		assertEquals(ANNE, search("/", and(person(_anne))));
	}

	public void testATextNotCommittedIsReadAgain() throws Exception {
		servlet().searchIndex().build();
		// A process killed before Lucene committed: the database says more than the text index
		// holds. Simulated by a text index emptied behind the database's back.
		stopServer();
		Path lucene = SpaceIndex.directoryOf(_base).resolve(SpaceIndex.TEXT_DIRECTORY_NAME);
		try (DirectoryStream<Path> files = Files.newDirectoryStream(lucene)) {
			for (Path file : files) {
				Files.delete(file);
			}
		}

		SearchIndex index = servlet().searchIndex();
		index.setSyncOnRequest(Boolean.FALSE);
		assertEquals("The structure answers at once.", ANNE, search("/", and(person(_anne))));
		assertEquals("The text waits for the background.", Collections.emptyList(), search("/", and(text("lake"))));
		index.run(_base.resolve(LAKE).toFile());
		assertEquals(Arrays.asList("Trips/2021 Lake/anne_philipp.jpg", "Trips/2021 Lake/clip.mp4"),
			search("/", and(text("lake"))));
	}

	// --- Written through, with no background running. ---

	public void testARatingAndAPrivacyAreWrittenThrough() throws Exception {
		idle();
		AlbumInfo lake = album(get("/" + LAKE + "/", "json", SharingFixture.ALICE));
		image(lake, "anne.jpg").setRating(2).setPrivacy(Privacy.PRIVATE);
		assertEquals(HttpServletResponse.SC_OK, put("/" + LAKE + "/", write(lake), SharingFixture.ALICE).status());
		String renamed = folderOf(search("/", and(SearchRating.create().setMin(2))));

		assertEquals(Arrays.asList(renamed + "/anne.jpg"), search("/", and(SearchRating.create().setMin(2))));
		assertFalse("Carol may not see it any more.",
			names(album(search("/", and(person(_anne)), SharingFixture.CAROL))).contains(renamed + "/anne.jpg"));
	}

	public void testADescriptionAndALabelAreWrittenThrough() throws Exception {
		idle();
		AlbumInfo lake = album(get("/" + LAKE + "/", "json", SharingFixture.ALICE));
		image(lake, "old.jpg").setComment("Sonnenuntergang am Bodensee")
			.setLabels(new ArrayList<>(Arrays.asList(LabelName.create().setName("Abend"))));
		assertEquals(HttpServletResponse.SC_OK, put("/" + LAKE + "/", write(lake), SharingFixture.ALICE).status());

		List<String> found = search("/", and(text("bodensee")));
		assertEquals(1, found.size());
		assertTrue(found.get(0).endsWith("/old.jpg"));
		assertEquals(found, search("/", and(SearchLabel.create().setLabel("Abend"))));
		assertEquals("A label is a word of the photograph, too.", found, search("/", and(text("abend"))));
	}

	public void testAnAlbumTitleAndItsRenameAreWrittenThrough() throws Exception {
		idle();
		AlbumInfo garden = album(get("/" + GARDEN + "/", "json", SharingFixture.ALICE));
		garden.setTitle("Rosengarten");
		assertEquals(HttpServletResponse.SC_OK, put("/" + GARDEN + "/", write(garden), SharingFixture.ALICE).status());

		List<String> found = search("/", and(text("rosengarten")));
		assertEquals(2, found.size());
		String folder = folderOf(found);
		assertFalse("The folder was named after the title.", folder.equals(GARDEN));
		assertTrue(Files.isDirectory(_base.resolve(folder)));
		assertTrue("Nothing at the old path.", search("/", and(person(_anne))).stream().noneMatch(n -> n.startsWith(GARDEN + "/")));
		assertNull(servlet().searchIndex().photosOf(GARDEN));
	}

	public void testAnUploadIsWrittenThrough() throws Exception {
		SearchIndex index = idle();
		assertEquals(HttpServletResponse.SC_OK,
			upload("/" + LAKE + "/", SharingFixture.ALICE, "new.jpg", jpeg()).status());

		assertTrue(index.photosOf(LAKE).contains("new.jpg"));
		assertTrue(search("/" + LAKE + "/", and()).contains("new.jpg"));
	}

	public void testADeleteIsWrittenThrough() throws Exception {
		idle();
		Map<String, String> delete = new HashMap<>();
		delete.put("action", "delete");
		FakeResponse deleted =
			post("/" + LAKE + "/", "{\"target\":\"\",\"names\":[{\"name\":\"anne.jpg\"}]}", SharingFixture.ALICE, delete);
		assertEquals(body(deleted), HttpServletResponse.SC_OK, deleted.status());

		assertFalse(search("/", and(person(_anne))).contains("Trips/2021 Lake/anne.jpg"));
	}

	public void testAFaceTagIsWrittenThrough() throws Exception {
		idle();
		Map<String, String> tag = new HashMap<>();
		tag.put("action", "tag-faces");
		FakeResponse tagged = post("/" + LAKE + "/", "{\"faces\":[{\"image\":\"anne.jpg\",\"face\":-1,\"x\":0.6,"
			+ "\"y\":0.1,\"w\":0.3,\"h\":0.3,\"person\":\"" + _philipp + "\",\"state\":\"CONFIRMED\"}]}",
			SharingFixture.ALICE, tag);
		assertEquals(tagged.body(), HttpServletResponse.SC_OK, tagged.status());
		assertTrue(search("/", and(person(_anne), person(_philipp))).stream().anyMatch(n -> n.endsWith("/anne.jpg")));
	}

	// --- Words. ---

	public void testGermanAndEnglishWords() throws Exception {
		idle();
		AlbumInfo lake = album(get("/" + LAKE + "/", "json", SharingFixture.ALICE));
		image(lake, "anne.jpg").setComment("Die Hunde von Frau Müller am Straßenrand in Württemberg");
		image(lake, "old.jpg").setComment("Children running along the lakes, the boat's sail");
		assertEquals(HttpServletResponse.SC_OK, put("/" + LAKE + "/", write(lake), SharingFixture.ALICE).status());
		String folder = folderOf(search("/", and(text("hunde"))));
		List<String> anne = Arrays.asList(folder + "/anne.jpg");
		List<String> old = Arrays.asList(folder + "/old.jpg");

		assertEquals("A German inflection, by its stem.", anne, search("/", and(text("Hunden"))));
		assertEquals("Case and accents folded.", anne, search("/", and(text("WURTTEMBERG"))));
		assertEquals("ß is ss.", anne, search("/", and(text("strassenrand"))));
		assertEquals("An umlaut written out.", anne, search("/", and(text("Mueller"))));
		assertEquals("A word begun.", anne, search("/", and(text("Straßenr"))));
		assertEquals("An English inflection, by its stem.", old, search("/", and(text("runs"))));
		assertEquals("A possessive.", old, search("/", and(text("children's"))));
		assertEquals("Every word must be there.", Collections.emptyList(), search("/", and(text("hunde lakes"))));
		assertEquals(old, search("/", and(text("lakes boat"))));
		assertEquals("No word is no criterion.", search("/", and()), search("/", and(text("  "))));
	}

	// --- What a caller may see. ---

	public void testTheOptionsOfTheLibrary() throws Exception {
		SearchOptions options = options("/", SharingFixture.ALICE);
		assertEquals(Arrays.asList("Anne", "Philipp"), personNames(options));
		assertEquals(Arrays.asList("Canon EOS 5D", "Pixel 7"), cameraNames(options));
		assertEquals(Arrays.asList("Garden", "Holiday"), labelNames(options));
		assertTrue(options.getPlaces().isEmpty());
	}

	public void testAHiddenPhotoIsNeitherFoundNorOffered() throws Exception {
		String zoe = new PeopleStore(_base).create("Zoe", "alice").getId();
		restartServer();
		AlbumInfo lake = album(get("/" + LAKE + "/", "json", SharingFixture.ALICE));
		image(lake, "private_ap.jpg").setCamera("Spy Cam").setComment("Geheimnis")
			.setLabels(new ArrayList<>(Arrays.asList(LabelName.create().setName("Secret"))));
		assertEquals(HttpServletResponse.SC_OK, put("/" + LAKE + "/", write(lake), SharingFixture.ALICE).status());
		String folder = folderOf(search("/", and(text("geheimnis"))));
		Map<String, String> tag = new HashMap<>();
		tag.put("action", "tag-faces");
		FakeResponse tagged = post("/" + folder + "/", "{\"faces\":[{\"image\":\"private_ap.jpg\",\"face\":-1,\"x\":0.6,"
			+ "\"y\":0.5,\"w\":0.3,\"h\":0.3,\"person\":\"" + zoe + "\",\"state\":\"CONFIRMED\"}]}", SharingFixture.ALICE, tag);
		assertEquals(tagged.body(), HttpServletResponse.SC_OK, tagged.status());

		SearchOptions alice = options("/", SharingFixture.ALICE);
		assertTrue(personNames(alice).contains("Zoe"));
		assertTrue(cameraNames(alice).contains("Spy Cam"));
		assertTrue(labelNames(alice).contains("Secret"));

		SearchOptions carol = options("/", SharingFixture.CAROL);
		assertFalse("A person only on a photograph Carol may not see.", personNames(carol).contains("Zoe"));
		assertFalse(cameraNames(carol).contains("Spy Cam"));
		assertFalse(labelNames(carol).contains("Secret"));
		for (SearchQuery query : Arrays.asList(and(text("geheimnis")), and(SearchLabel.create().setLabel("Secret")),
			and(SearchCamera.create().setCamera("Spy Cam")), and(person(zoe)))) {
			assertEquals(Collections.emptyList(), names(album(search("/", query, SharingFixture.CAROL))));
		}

		createSearch("/Z", and(person(zoe)));
		assertEquals(Arrays.asList(folder + "/private_ap.jpg"), names(album(get("/Z/", "json", SharingFixture.ALICE))));
		String token = issue("alice", "Z", "Friends", "", Privacy.MEMBERS, 0, Rights.VIEW);
		assertEquals("A link of lower clearance is shown nothing of it.", Collections.emptyList(),
			names(album(get("/", "json", token))));
	}

	// --- Readers beside the writer. ---

	public void testSearchesDuringARebuildAreConsistent() throws Exception {
		SearchIndex index = servlet().searchIndex();
		index.build();
		index.setSyncOnRequest(Boolean.FALSE);
		PathInfo root = new PathInfo(_base);
		SearchQuery query = and(person(_anne), person(_philipp));
		List<String> all = relatives(PhotoSearch.find(root, query, index, null, SearchIndex.Visibility.ALL));
		List<String> withoutGarden = new ArrayList<>(all);
		withoutGarden.removeIf(name -> name.startsWith(GARDEN + "/"));
		assertFalse(all.equals(withoutGarden));

		AtomicBoolean done = new AtomicBoolean();
		CountDownLatch started = new CountDownLatch(4);
		ExecutorService readers = Executors.newFixedThreadPool(4);
		List<Future<Integer>> results = new ArrayList<>();
		for (int n = 0; n < 4; n++) {
			results.add(readers.submit(() -> {
				started.countDown();
				int searches = 0;
				while (!done.get()) {
					List<String> found = relatives(PhotoSearch.find(root, query, index, null, SearchIndex.Visibility.ALL));
					assertTrue("A folder is there whole or not at all: " + found,
						found.equals(all) || found.equals(withoutGarden));
					searches++;
				}
				return Integer.valueOf(searches);
			}));
		}
		try {
			assertTrue(started.await(10, TimeUnit.SECONDS));
			for (int n = 0; n < 5; n++) {
				// What the background does: a folder gone and back, and the whole tree read again.
				index.gone(GARDEN);
				index.treeChanged(_base.resolve(GARDEN).toFile());
				index.treeChanged(_base.toFile());
				index.build();
			}
		} finally {
			done.set(true);
			readers.shutdown();
		}
		assertTrue("No reader may hang.", readers.awaitTermination(30, TimeUnit.SECONDS));
		for (Future<Integer> result : results) {
			assertTrue(result.get().intValue() > 0);
		}
		assertEquals(all, relatives(PhotoSearch.find(root, query, index, null, SearchIndex.Visibility.ALL)));
	}

	// --- Helpers. ---

	/** The servlet's index, built, and from now on hearing only of what the server writes. */
	private SearchIndex idle() throws Exception {
		SearchIndex index = servlet().searchIndex();
		index.build();
		index.setSyncOnRequest(Boolean.FALSE);
		return index;
	}

	/** A tiny JPEG the server can analyse. */
	private static byte[] jpeg() throws Exception {
		java.awt.image.BufferedImage image =
			new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_3BYTE_BGR);
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		javax.imageio.ImageIO.write(image, "jpg", out);
		return out.toByteArray();
	}

	/** Destroys the servlet, which closes its index, so that the next one opens it from disk. */
	private void stopServer() throws Exception {
		servlet().destroy();
		restartServer();
	}

	private static SearchText text(String text) {
		return SearchText.create().setText(text);
	}

	private static String folderOf(List<String> names) {
		assertFalse(names.isEmpty());
		return names.get(0).substring(0, names.get(0).lastIndexOf('/'));
	}

	private SearchOptions options(String path, String token) throws Exception {
		FakeResponse response = get(path, ImageServlet.SEARCH_OPTIONS_TYPE, token);
		assertEquals(body(response), HttpServletResponse.SC_OK, response.status());
		return SearchOptions.readSearchOptions(reader(body(response)));
	}

	private static List<String> personNames(SearchOptions options) {
		List<String> result = new ArrayList<>();
		for (Person person : options.getPersons()) {
			result.add(person.getName());
		}
		return result;
	}

	private static List<String> cameraNames(SearchOptions options) {
		List<String> result = new ArrayList<>();
		options.getCameras().forEach(camera -> result.add(camera.getName()));
		return result;
	}

	private static List<String> labelNames(SearchOptions options) {
		List<String> result = new ArrayList<>();
		options.getLabels().forEach(label -> result.add(label.getName()));
		return result;
	}

	private static List<String> relatives(List<PhotoSearch.Match> matches) {
		List<String> result = new ArrayList<>();
		for (PhotoSearch.Match match : matches) {
			result.add(match.getRelative());
		}
		return result;
	}
}
