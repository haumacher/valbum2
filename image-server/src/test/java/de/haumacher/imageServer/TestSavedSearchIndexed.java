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
 * Every test of {@link TestSavedSearch} again, the search asking the {@link SearchIndex} of issue
 * #227 instead of the albums: built once after every (re)start of the server and kept current by
 * what the tests then change, so each expectation of the scan holds for the index too.
 *
 * <p>
 * And what keeps the index current: a write of the server is seen by the next search (a rating, a
 * label, a tagged face, a move, a delete), a folder copied in by hand is indexed by the step of the
 * background work, and a removed folder is gone.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestSavedSearchIndexed extends TestSavedSearch {

	@Override
	protected void restartServer() throws Exception {
		super.restartServer();
		servlet().searchIndex().build();
	}

	private SearchIndex index() throws Exception {
		SearchIndex index = servlet().searchIndex();
		assertTrue("The search asks the index.", index.isComplete());
		return index;
	}

	public void testTheIndexAnswersAsTheScan() throws Exception {
		SearchIndex index = index();
		for (SearchQuery query : Arrays.asList(and(), and(person(_anne)), and(person(_anne), person(_philipp)),
			and(SearchLabel.create().setLabel("Holiday")), and(SearchRating.create().setMin(1)))) {
			for (String folder : Arrays.asList("", "Trips", LAKE, "Family")) {
				PathInfo scope = folder.isEmpty() ? new PathInfo(_base) : new PathInfo(_base, Path.of(folder));
				assertEquals(relatives(PhotoSearch.find(scope, query, servlet().cache()::lookup, null)),
					relatives(PhotoSearch.find(scope, query, servlet().cache()::lookup, null, index)));
			}
		}
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
		assertFalse(index().folders().containsKey("Family/2024 Garden"));
	}

	public void testAFolderCopiedInIsIndexedByTheStep() throws Exception {
		SearchIndex index = index();
		SharingFixture.album(_base, "Trips/2025 Snow", "Snow", part("snow.jpg", day(2025, 1, 1), "", _anne, _philipp),
			"snow.jpg");
		File snow = _base.resolve("Trips/2025 Snow").toFile();
		assertFalse(index.folders().containsKey("Trips/2025 Snow"));

		index.run(snow);

		SearchIndex.Folder folder = index.folders().get("Trips/2025 Snow");
		assertNotNull("Indexed without any search.", folder);
		assertEquals("snow.jpg", folder._photos[0].getName());
	}

	public void testARemovedFolderIsGone() throws Exception {
		SearchIndex index = index();
		assertTrue(index.folders().containsKey(GARDEN));
		TestPlaceTags.delete(_base.resolve("Family"));

		index.run(_base.resolve("Family").toFile());

		assertFalse(index.folders().containsKey(GARDEN));
		assertFalse(index.folders().containsKey("Family"));
		assertTrue(search("/", and(person(_anne))).stream().noneMatch(name -> name.startsWith("Family/")));
	}

	public void testAFolderRemovedByHandIsNeverFound() throws Exception {
		index();
		TestPlaceTags.delete(_base.resolve(GARDEN));
		assertTrue(search("/", and(person(_anne))).stream().noneMatch(name -> name.startsWith("Family/")));
		assertFalse(Files.exists(_base.resolve(GARDEN)));
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
