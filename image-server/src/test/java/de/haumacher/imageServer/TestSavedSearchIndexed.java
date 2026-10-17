/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.LabelName;
import de.haumacher.imageServer.shared.model.SearchLabel;
import de.haumacher.imageServer.shared.model.SearchQuery;
import de.haumacher.imageServer.shared.model.SearchRating;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every test of {@link TestSavedSearch} again, the {@link SearchIndex} of issue #227 built once
 * after every (re)start of the server as the background builds it, and kept current by what the
 * tests then change, so each expectation holds for an index built ahead as for one filled on the
 * request.
 *
 * <p>
 * And what keeps the index current where no search does the background's work (issue #264): a
 * write of the server is seen by the next search (a rating, a label, a tagged face, a move, a
 * delete), a folder copied in by hand is indexed by the step of the background work, and a removed
 * folder is gone.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestSavedSearchIndexed extends TestSavedSearch {

	@Override
	protected void restartServer() throws Exception {
		super.restartServer();
		servlet().searchIndex().build();
	}

	/**
	 * The index of the servlet, which from now on hears only of what the server writes, as while
	 * the background of a server is idle: no search does the background's work.
	 */
	private SearchIndex index() throws Exception {
		SearchIndex index = servlet().searchIndex();
		index.setSyncOnRequest(Boolean.FALSE);
		return index;
	}

	public void testTheIndexAnswersAsTheScan() throws Exception {
		SearchIndex index = index();
		for (SearchQuery query : Arrays.asList(and(), and(person(_anne)), and(person(_anne), person(_philipp)),
			and(SearchLabel.create().setLabel("Holiday")), and(SearchRating.create().setMin(1)))) {
			for (String folder : Arrays.asList("", "Trips", LAKE, "Family")) {
				PathInfo scope = folder.isEmpty() ? new PathInfo(_base) : new PathInfo(_base, Path.of(folder));
				assertEquals(scan(scope, query), relatives(PhotoSearch.find(scope, query, index, null,
					SearchIndex.Visibility.ALL)));
			}
		}
	}

	/**
	 * What a scan of the albums below the given folder answers the given query of a single
	 * criterion (all of the persons, a label, a rating): the search before issue #264, kept here as
	 * the measure of the index.
	 */
	private List<String> scan(PathInfo scope, SearchQuery query) throws Exception {
		List<PhotoSearch.Match> found = new ArrayList<>();
		scan(scope, "", query, found);
		found.sort(PhotoSearch.ORDER);
		return relatives(found);
	}

	private void scan(PathInfo folder, String prefix, SearchQuery query, List<PhotoSearch.Match> found)
			throws Exception {
		File[] children = folder.toFile().listFiles();
		if (children == null) {
			return;
		}
		List<String> folders = new ArrayList<>();
		for (File child : children) {
			if (child.isDirectory() && !child.getName().startsWith(".")) {
				folders.add(child.getName());
			}
		}
		if (new File(folder.toFile(), "index.json").isFile()
			&& servlet().cache().lookup(folder) instanceof de.haumacher.imageServer.shared.model.AlbumInfo album
			&& !PhotoSearch.holdsNoFiles(album)) {
			for (de.haumacher.imageServer.shared.model.ImagePart image : PhotoSearch.images(album)) {
				if (image.getRating() != de.haumacher.imageServer.auth.Ratings.TRASH && accepts(query, image)) {
					found.add(new PhotoSearch.Match(0, folder, prefix, image.getName(), image.getDate()));
				}
			}
		}
		for (String child : folders) {
			scan(folder.child(child), prefix + child + "/", query, found);
		}
	}

	private static boolean accepts(SearchQuery query, de.haumacher.imageServer.shared.model.ImagePart image) {
		for (de.haumacher.imageServer.shared.model.SearchCriterion criterion : ((de.haumacher.imageServer.shared.model.SearchAnd) query
			.getRoot()).getCriteria()) {
			if (criterion instanceof de.haumacher.imageServer.shared.model.SearchPerson person) {
				if (image.getTags().stream().noneMatch(tag -> tag.getPerson().equals(person.getPerson())
					&& tag.getState() == de.haumacher.imageServer.shared.model.FaceState.CONFIRMED)) {
					return false;
				}
			} else if (criterion instanceof SearchLabel label) {
				if (image.getLabels().stream().noneMatch(name -> name.getName().equals(label.getLabel()))) {
					return false;
				}
			} else if (criterion instanceof SearchRating rating) {
				if (image.getRating() < rating.getMin()) {
					return false;
				}
			} else {
				fail("Not a criterion of this test: " + criterion);
			}
		}
		return true;
	}

	public void testARatingWrittenIsSeen() throws Exception {
		index();
		assertEquals(Arrays.asList("Trips/2021 Lake/anne_philipp.jpg"), search("/", and(SearchRating.create().setMin(1))));
		AlbumInfo lake = album(get("/" + LAKE + "/", "json", SharingFixture.ALICE));
		image(lake, "old.jpg").setRating(2);
		assertEquals(HttpServletResponse.SC_OK, put("/" + LAKE + "/", write(lake), SharingFixture.ALICE).status());

		// The write names the folder after the album's title, which the index follows too.
		assertEquals(Arrays.asList("old.jpg", "anne_philipp.jpg"), fileNames(search("/", and(SearchRating.create().setMin(1)))));
	}

	public void testALabelWrittenIsSeen() throws Exception {
		index();
		AlbumInfo lake = album(get("/" + LAKE + "/", "json", SharingFixture.ALICE));
		image(lake, "anne.jpg").setLabels(new ArrayList<>(Arrays.asList(LabelName.create().setName("Best"))));
		assertEquals(HttpServletResponse.SC_OK, put("/" + LAKE + "/", write(lake), SharingFixture.ALICE).status());
		List<String> best = search("/", and(SearchLabel.create().setLabel("Best")));
		assertEquals(Arrays.asList("anne.jpg"), fileNames(best));
		String renamed = best.get(0).substring(0, best.get(0).lastIndexOf('/'));

		Map<String, String> relabel = new HashMap<>();
		relabel.put("action", ImageServlet.RELABEL_ACTION);
		assertEquals(HttpServletResponse.SC_OK,
			post("/" + renamed + "/", "{\"from\":\"Best\",\"to\":\"Top\"}", SharingFixture.ALICE, relabel).status());
		assertEquals(Collections.emptyList(), search("/", and(SearchLabel.create().setLabel("Best"))));
		assertEquals(Arrays.asList(renamed + "/anne.jpg"), search("/", and(SearchLabel.create().setLabel("Top"))));
	}

	public void testATaggedFaceIsSeen() throws Exception {
		index();
		assertFalse(search("/", and(person(_anne), person(_philipp))).contains("Trips/2021 Lake/anne.jpg"));
		Map<String, String> tag = new HashMap<>();
		tag.put("action", "tag-faces");
		// A hand-marked face over the right half of the photo, confirmed as Philipp.
		FakeResponse tagged = post("/" + LAKE + "/", "{\"faces\":[{\"image\":\"anne.jpg\",\"face\":-1,\"x\":0.6,"
			+ "\"y\":0.1,\"w\":0.3,\"h\":0.3,\"person\":\"" + _philipp + "\",\"state\":\"CONFIRMED\"}]}",
			SharingFixture.ALICE, tag);
		assertEquals(tagged.body(), HttpServletResponse.SC_OK, tagged.status());
		assertTrue(search("/", and(person(_anne), person(_philipp))).contains("Trips/2021 Lake/anne.jpg"));
	}

	public void testAMoveAndADeleteAreSeen() throws Exception {
		index();
		assertEquals(HttpServletResponse.SC_OK,
			move("/" + LAKE + "/", "Family/2024 Garden", SharingFixture.ALICE, "old.jpg").status());
		List<String> moved = search("/", and(person(_anne), person(_philipp)));
		assertTrue(moved.toString(), moved.contains("Family/2024 Garden/old.jpg"));
		assertFalse(moved.contains("Trips/2021 Lake/old.jpg"));

		Map<String, String> delete = new HashMap<>();
		delete.put("action", "delete");
		assertEquals(HttpServletResponse.SC_OK,
			post("/Family/", "{\"names\":[{\"name\":\"2024 Garden\"}]}", SharingFixture.ALICE, delete).status());
		List<String> deleted = search("/", and(person(_anne)));
		assertTrue(deleted.toString(), deleted.stream().noneMatch(name -> name.startsWith("Family/")));
		assertNull("Its rows are gone.", index().photosOf("Family/2024 Garden"));
	}

	public void testAFolderCopiedInIsIndexedByTheStep() throws Exception {
		SearchIndex index = index();
		SharingFixture.album(_base, "Trips/2025 Snow", "Snow", part("snow.jpg", day(2025, 1, 1), "", _anne, _philipp),
			"snow.jpg");
		File snow = _base.resolve("Trips/2025 Snow").toFile();
		assertNull(index.photosOf("Trips/2025 Snow"));
		assertFalse("A search does not find it before the background did.",
			search("/", and(person(_anne))).contains("Trips/2025 Snow/snow.jpg"));

		index.run(snow);

		assertEquals("Indexed without any search.", Arrays.asList("snow.jpg"), index.photosOf("Trips/2025 Snow"));
		assertTrue(search("/", and(person(_anne))).contains("Trips/2025 Snow/snow.jpg"));
	}

	public void testARemovedFolderIsGone() throws Exception {
		SearchIndex index = index();
		assertNotNull(index.photosOf(GARDEN));
		TestPlaceTags.delete(_base.resolve("Family"));

		index.run(_base.resolve("Family").toFile());

		assertNull(index.photosOf(GARDEN));
		assertNull(index.photosOf("Family"));
		assertTrue(search("/", and(person(_anne))).stream().noneMatch(name -> name.startsWith("Family/")));
	}

	public void testAFolderRemovedByHandIsNeverFound() throws Exception {
		index();
		TestPlaceTags.delete(_base.resolve(GARDEN));
		// The index still holds its rows: the album, gone, hides them, see ImageServlet#searchMatches.
		assertNotNull(servlet().searchIndex().photosOf(GARDEN));
		assertTrue(search("/", and(person(_anne))).stream().noneMatch(name -> name.startsWith("Family/")));
		assertFalse(Files.exists(_base.resolve(GARDEN)));
	}

	/**
	 * The options of the whole library on a cold cache, as after a start (the index on disk, no
	 * album read yet), are complete &mdash; the very answer the warm cache gives &mdash; and read no
	 * album: the persons chooser at the top level stood empty while they were read.
	 */
	public void testTheRootOptionsOnAColdCacheReadNoAlbum() throws Exception {
		index();
		String warm = body(get("/", ImageServlet.SEARCH_OPTIONS_TYPE, SharingFixture.ALICE));
		de.haumacher.imageServer.cache.ResourceCache cache = servlet().cache();
		PathInfo root = new PathInfo(_base);
		cache.invalidateTree(root);
		// The listing of the root, which every answer at the root looks at, is no album.
		cache.lookup(root);
		long loads = cache.loadCount();
		String cold = body(get("/", ImageServlet.SEARCH_OPTIONS_TYPE, SharingFixture.ALICE));
		assertEquals("No album read.", loads, cache.loadCount());
		assertEquals(warm, cold);
		List<String> persons = new ArrayList<>();
		for (de.haumacher.imageServer.shared.model.Person person : de.haumacher.imageServer.shared.model.SearchOptions
			.readSearchOptions(reader(cold)).getPersons()) {
			persons.add(person.getName());
		}
		assertEquals(Arrays.asList("Anne", "Philipp"), persons);
	}

	/**
	 * A sidecar changed behind the server's back is what the options answer, as its album would:
	 * the folder is read into the index first.
	 */
	public void testTheOptionsReadAChangedFolderFirst() throws Exception {
		index();
		assertTrue(body(get("/", ImageServlet.SEARCH_OPTIONS_TYPE, SharingFixture.DAVE)).contains("Canon EOS 5D"));
		Path sidecar = _base.resolve(LAKE).resolve("index.json");
		String contents = new String(Files.readAllBytes(sidecar), java.nio.charset.StandardCharsets.UTF_8);
		// The only photograph of that camera turns private, by hand.
		Files.write(sidecar, contents.replace("\"camera\":\"Canon EOS 5D\",", "\"camera\":\"Canon EOS 5D\",\"privacy\":2,")
			.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		sidecar.toFile().setLastModified(sidecar.toFile().lastModified() + 5000);
		assertFalse(body(get("/", ImageServlet.SEARCH_OPTIONS_TYPE, SharingFixture.DAVE)).contains("Canon EOS 5D"));
		assertTrue(body(get("/", ImageServlet.SEARCH_OPTIONS_TYPE, SharingFixture.ALICE)).contains("Canon EOS 5D"));
	}

	private static List<String> fileNames(List<String> names) {
		List<String> result = new ArrayList<>();
		for (String name : names) {
			result.add(name.substring(name.lastIndexOf('/') + 1));
		}
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
