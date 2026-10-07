/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.auth.Ratings;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.auth.ShareStore;
import de.haumacher.imageServer.faces.PeopleStore;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumKind;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.FolderKind;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.LabelName;
import de.haumacher.imageServer.shared.model.MoveResult;
import de.haumacher.imageServer.shared.model.Person;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.SearchAnd;
import de.haumacher.imageServer.shared.model.SearchCamera;
import de.haumacher.imageServer.shared.model.SearchCriterion;
import de.haumacher.imageServer.shared.model.SearchDate;
import de.haumacher.imageServer.shared.model.SearchFolder;
import de.haumacher.imageServer.shared.model.SearchLabel;
import de.haumacher.imageServer.shared.model.SearchMedia;
import de.haumacher.imageServer.shared.model.SearchNot;
import de.haumacher.imageServer.shared.model.SearchOptions;
import de.haumacher.imageServer.shared.model.SearchOr;
import de.haumacher.imageServer.shared.model.SearchPerson;
import de.haumacher.imageServer.shared.model.SearchQuery;
import de.haumacher.imageServer.shared.model.SearchRating;
import de.haumacher.imageServer.shared.model.SearchText;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test case for the search of issue #227: the search view below a folder, and saved searches, an
 * album kind that stores only its query.
 *
 * <p>
 * The library is the {@link SharingFixture} with two more albums of photographs whose sidecars say
 * who is in them, when they were taken, with which camera, labelled how, rated how:
 * </p>
 *
 * <pre>
 * Trips/2021 Lake   "Summer trip"
 *   anne_philipp.jpg  2021-07-01  Anne, Philipp  Holiday  Canon EOS 5D  rated 1  "At the lake"
 *   anne.jpg          2021-07-02  Anne                    Pixel 7
 *   old.jpg           2019-05-01  Anne, Philipp
 *   private_ap.jpg    2022-01-01  Anne, Philipp  private
 *   trash_ap.jpg      2022-02-01  Anne, Philipp  rated -2 (the trash)
 *   clip.mp4          2023-01-01  a video                                       "Lake video"
 * Family/2024 Garden "Garden"
 *   garden.jpg        2024-06-01  Anne (Philipp rejected)
 *   both.jpg          2024-06-02  Anne, Philipp  Garden
 * </pre>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestSavedSearch extends ShareTestCase {

	static final File FIXTURE = new File("src/test/fixtures/search/index.json");

	static final String LAKE = "Trips/2021 Lake";

	static final String GARDEN = "Family/2024 Garden";

	String _anne;

	String _philipp;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		PeopleStore people = new PeopleStore(_base);
		_anne = people.create("Anne", "alice").getId();
		_philipp = people.create("Philipp", "alice").getId();
		SharingFixture.album(_base, LAKE, "Summer trip",
			part("anne_philipp.jpg", day(2021, 7, 1), ",\"labels\":[{\"name\":\"Holiday\"}],\"camera\":\"Canon EOS 5D\","
				+ "\"rating\":1,\"comment\":\"At the lake\"", _anne, _philipp) + ","
				+ part("anne.jpg", day(2021, 7, 2), ",\"camera\":\"Pixel 7\"", _anne) + ","
				+ part("old.jpg", day(2019, 5, 1), "", _anne, _philipp) + ","
				+ part("private_ap.jpg", day(2022, 1, 1), ",\"privacy\":2", _anne, _philipp) + ","
				+ part("trash_ap.jpg", day(2022, 2, 1), ",\"rating\":-2", _anne, _philipp) + ","
				+ "[\"ImagePart\",{\"name\":\"clip.mp4\",\"kind\":\"VIDEO\",\"width\":4,\"height\":3,\"date\":"
				+ day(2023, 1, 1) + ",\"comment\":\"Lake video\"}]",
			"anne_philipp.jpg", "anne.jpg", "old.jpg", "private_ap.jpg", "trash_ap.jpg", "clip.mp4");
		SharingFixture.album(_base, GARDEN, "Garden",
			part("garden.jpg", day(2024, 6, 1), ",\"tags\":[" + tag(_anne, "CONFIRMED", 0.1) + ","
				+ tag(_philipp, "REJECTED", 0.5) + "]", (String[]) null) + ","
				+ part("both.jpg", day(2024, 6, 2), ",\"labels\":[{\"name\":\"Garden\"}]", _anne, _philipp),
			"garden.jpg", "both.jpg");
		restartServer();
	}

	// --- Each criterion alone. ---

	public void testAPersonAlone() throws Exception {
		assertEquals(Arrays.asList("Trips/2021 Lake/old.jpg", "Trips/2021 Lake/anne_philipp.jpg",
			"Trips/2021 Lake/anne.jpg", "Trips/2021 Lake/private_ap.jpg", "Family/2024 Garden/garden.jpg",
			"Family/2024 Garden/both.jpg"), names(search("/", and(person(_anne)))));
	}

	public void testPersonsAllMustBeInThePhoto() throws Exception {
		assertEquals("A photo with Anne alone does not match, nor one where Philipp was rejected.",
			Arrays.asList("Trips/2021 Lake/old.jpg", "Trips/2021 Lake/anne_philipp.jpg",
				"Trips/2021 Lake/private_ap.jpg", "Family/2024 Garden/both.jpg"),
			names(search("/", and(person(_anne), person(_philipp)))));
	}

	public void testAPersonIsComparedThroughAMerge() throws Exception {
		PeopleStore people = new PeopleStore(_base);
		String annie = people.create("Annie", "alice").getId();
		people.merge(_anne, annie);
		restartServer();
		assertEquals("The merged id finds the survivor's photos.",
			names(search("/", and(person(_anne)))), names(search("/", and(person(annie)))));
	}

	public void testADateRange() throws Exception {
		assertEquals(Arrays.asList("Trips/2021 Lake/anne_philipp.jpg", "Trips/2021 Lake/anne.jpg"),
			names(search("/", and(SearchDate.create().setFrom(day(2021, 1, 1)).setTo(day(2022, 1, 1))))));
		assertEquals("Open below.", Arrays.asList("Trips/2021 Lake/old.jpg"),
			names(search("/", and(SearchDate.create().setTo(day(2020, 1, 1))))));
		assertEquals("Open above; the end is exclusive.",
			Arrays.asList("Family/2024 Garden/both.jpg"),
			names(search("/", and(SearchDate.create().setFrom(day(2024, 6, 2))))));
	}

	public void testALabel() throws Exception {
		assertEquals(Arrays.asList("Trips/2021 Lake/anne_philipp.jpg", "Family/2024 Garden/both.jpg"),
			names(search("/", and(or(SearchLabel.create().setLabel("Holiday"), SearchLabel.create().setLabel("Garden"))))));
	}

	public void testAMinimumRating() throws Exception {
		assertEquals("The trash never matches, not even a rating of -2 or better.",
			Arrays.asList("Trips/2021 Lake/anne_philipp.jpg"), names(search("/", and(SearchRating.create().setMin(1)))));
		assertFalse(names(search("/", and(SearchRating.create().setMin(-2)))).contains("Trips/2021 Lake/trash_ap.jpg"));
	}

	public void testAMediaKind() throws Exception {
		assertEquals(Arrays.asList("Trips/2021 Lake/clip.mp4"),
			names(search("/", and(SearchMedia.create().setVideo(true)))));
		assertFalse(names(search("/", and(SearchMedia.create().setVideo(false)))).contains("Trips/2021 Lake/clip.mp4"));
	}

	public void testATextInCommentOrAlbumTitle() throws Exception {
		assertEquals("The comment, ignoring case.", Arrays.asList("Trips/2021 Lake/anne_philipp.jpg",
			"Trips/2021 Lake/clip.mp4"), names(search("/", and(SearchText.create().setText("LAKE")))));
		assertEquals("The album's title.", Arrays.asList("Family/2024 Garden/garden.jpg", "Family/2024 Garden/both.jpg"),
			names(search("/", and(SearchText.create().setText("garden")))));
	}

	public void testACamera() throws Exception {
		assertEquals(Arrays.asList("Trips/2021 Lake/anne.jpg"),
			names(search("/", and(SearchCamera.create().setCamera("Pixel 7")))));
	}

	public void testAFolderAndANot() throws Exception {
		assertEquals(Arrays.asList("Family/2024 Garden/garden.jpg", "Family/2024 Garden/both.jpg"),
			names(search("/", and(SearchFolder.create().setPath("Family")))));
		assertEquals(Arrays.asList("Trips/2021 Lake/anne.jpg", "Family/2024 Garden/garden.jpg"),
			names(search("/", and(person(_anne), SearchNot.create().setCriterion(person(_philipp))))));
	}

	// --- Combinations. ---

	public void testAnneAndPhilippFrom2020To2026() throws Exception {
		SearchQuery query = and(person(_anne), person(_philipp),
			SearchDate.create().setFrom(day(2020, 1, 1)).setTo(day(2027, 1, 1)));
		assertEquals(Arrays.asList("Trips/2021 Lake/anne_philipp.jpg", "Trips/2021 Lake/private_ap.jpg",
			"Family/2024 Garden/both.jpg"), names(search("/", query)));
		assertEquals("Carol may not see the private photo.",
			Arrays.asList("Trips/2021 Lake/anne_philipp.jpg", "Family/2024 Garden/both.jpg"),
			names(album(search("/", query, SharingFixture.CAROL))));
	}

	// --- The scope. ---

	public void testTheScopeIsTheFolderAndBelow() throws Exception {
		AlbumInfo below = album(search("/Trips/", and(person(_anne), person(_philipp)), SharingFixture.ALICE));
		assertEquals("Named below the folder searched.",
			Arrays.asList("2021 Lake/old.jpg", "2021 Lake/anne_philipp.jpg", "2021 Lake/private_ap.jpg"), names(below));
		assertEquals(AlbumKind.SEARCH, below.getKind());
		assertEquals(LAKE + "/old.jpg", image(below, "2021 Lake/old.jpg").getRef().getPath());
		assertTrue("A search not saved is looked at, never edited.", rightsOf(below).contains(Rights.VIEW)
			&& !rightsOf(below).contains(Rights.EDIT));
		assertEquals("The name below the folder is the photo's own address there.", HttpServletResponse.SC_OK,
			get("/Trips/2021 Lake/old.jpg", "tn", SharingFixture.ALICE).status());
	}

	public void testASavedSearchAtTheRootAndInAFolder() throws Exception {
		createSearch("/AP", and(person(_anne), person(_philipp)));
		createSearch("/Family/AP", and(person(_anne), person(_philipp)));

		AlbumInfo root = album(get("/AP/", "json", SharingFixture.ALICE));
		assertEquals(AlbumKind.SEARCH, root.getKind());
		assertEquals(Arrays.asList("Trips/2021 Lake/old.jpg", "Trips/2021 Lake/anne_philipp.jpg",
			"Trips/2021 Lake/private_ap.jpg", "Family/2024 Garden/both.jpg"), names(root));
		assertEquals("A saved search in a folder searches below that folder.",
			Arrays.asList("2024 Garden/both.jpg"), names(album(get("/Family/AP/", "json", SharingFixture.ALICE))));
	}

	// --- Saved searches. ---

	public void testTheStoredFormIsTheQueryAndNothingElse() throws Exception {
		createSearch("/Trips/AP", and(person(_anne), person(_philipp)));
		AlbumInfo stored = stored("Trips/AP");
		assertEquals(AlbumKind.SEARCH, stored.getKind());
		assertEquals(PhotoSearch.VERSION, stored.getQuery().getVersion());
		assertEquals(Collections.emptyList(), stored.getParts());
		assertNull(stored.getIndexPicture());
		for (File file : _base.resolve("Trips/AP").toFile().listFiles()) {
			assertTrue("Nothing but the sidecar: " + file.getName(), file.getName().startsWith("index.json"));
		}
	}

	public void testOpeningNeverWritesTheSidecar() throws Exception {
		createSearch("/Trips/AP", and(person(_anne), person(_philipp)));
		byte[] before = Files.readAllBytes(_base.resolve("Trips/AP/index.json"));

		AlbumInfo answer = album(get("/Trips/AP/", "json", SharingFixture.ALICE));
		assertEquals(HttpServletResponse.SC_OK, get("/Trips/AP/2021 Lake/old.jpg", "tn", SharingFixture.ALICE).status());
		get("/Trips/", "json", SharingFixture.ALICE);
		get("/Trips/AP/", "json", SharingFixture.CAROL);
		assertFalse(answer.getParts().isEmpty());

		assertTrue("No part and no reference ever reaches the sidecar.",
			Arrays.equals(before, Files.readAllBytes(_base.resolve("Trips/AP/index.json"))));
	}

	public void testANewMatchingPhotoAppearsByItself() throws Exception {
		createSearch("/Trips/AP", and(person(_anne), person(_philipp)));
		byte[] before = Files.readAllBytes(_base.resolve("Trips/AP/index.json"));
		assertEquals(3, names(album(get("/Trips/AP/", "json", SharingFixture.ALICE))).size());

		SharingFixture.album(_base, "Trips/2025 Snow", "Snow",
			part("snow.jpg", day(2025, 1, 1), "", _anne, _philipp), "snow.jpg");

		assertEquals(Arrays.asList("2021 Lake/old.jpg", "2021 Lake/anne_philipp.jpg", "2021 Lake/private_ap.jpg",
			"2025 Snow/snow.jpg"), names(album(get("/Trips/AP/", "json", SharingFixture.ALICE))));
		assertTrue(Arrays.equals(before, Files.readAllBytes(_base.resolve("Trips/AP/index.json"))));
	}

	public void testAPhotoIsServedFromItsAlbumWhileItMatches() throws Exception {
		createSearch("/Trips/AP", and(person(_anne), person(_philipp)));
		FakeResponse original = get("/Trips/AP/2021 Lake/old.jpg", null, SharingFixture.ALICE);
		assertEquals(HttpServletResponse.SC_OK, original.status());
		assertTrue(Arrays.equals(Files.readAllBytes(_base.resolve(LAKE + "/old.jpg")), original.bodyBytes()));

		FakeResponse other = get("/Trips/AP/2021 Lake/anne.jpg", "tn", SharingFixture.ALICE);
		assertEquals("A photo the search does not find is not there.", HttpServletResponse.SC_NOT_FOUND, other.status());
		assertEquals(PhotoSearch.NO_MATCH, errorMessage(other));
		assertEquals(HttpServletResponse.SC_NOT_FOUND,
			get("/Trips/AP/2021 Lake/trash_ap.jpg", "tn", SharingFixture.ALICE).status());
	}

	public void testATrashedPhotoNeverMatches() throws Exception {
		createSearch("/Trips/AP", and(person(_anne), person(_philipp)));
		assertFalse("Not even for its album's editor.",
			names(album(get("/Trips/AP/", "json", SharingFixture.ALICE))).contains("2021 Lake/trash_ap.jpg"));
	}

	public void testEveryCallerSeesWhatTheirAlbumsShowThem() throws Exception {
		createSearch("/Trips/AP", and(person(_anne), person(_philipp)));
		assertEquals(Arrays.asList("2021 Lake/old.jpg", "2021 Lake/anne_philipp.jpg", "2021 Lake/private_ap.jpg"),
			names(album(get("/Trips/AP/", "json", SharingFixture.ALICE))));
		assertEquals("A member without the clearance does not see the private match.",
			Arrays.asList("2021 Lake/old.jpg", "2021 Lake/anne_philipp.jpg"),
			names(album(get("/Trips/AP/", "json", SharingFixture.CAROL))));
		assertEquals(HttpServletResponse.SC_FORBIDDEN,
			get("/Trips/AP/2021 Lake/private_ap.jpg", "tn", SharingFixture.CAROL).status());
	}

	public void testALinkOnASavedSearchAppliesItsLimits() throws Exception {
		createSearch("/Trips/AP", and(person(_anne), person(_philipp)));
		String token = issue("alice", "Trips/AP", "Grandma", "", Privacy.MEMBERS, 1, Rights.VIEW);

		AlbumInfo answer = album(get("/", "json", token));
		assertEquals("The rating floor of the link.", Arrays.asList("2021 Lake/anne_philipp.jpg"), names(answer));
		assertNull("Where a photo lies is the members' business.", image(answer, "2021 Lake/anne_philipp.jpg").getRef());
		assertTrue("Labels are the members' business.", image(answer, "2021 Lake/anne_philipp.jpg").getLabels().isEmpty());
		assertEquals(HttpServletResponse.SC_OK, get("/2021 Lake/anne_philipp.jpg", "tn", token).status());
		assertEquals(HttpServletResponse.SC_FORBIDDEN, get("/2021 Lake/old.jpg", "tn", token).status());
		assertEquals("The link reaches nothing outside the saved search.", HttpServletResponse.SC_NOT_FOUND,
			get("/../2021 Lake/anne.jpg", "tn", token).status());
		assertEquals("A link does not search.", HttpServletResponse.SC_FORBIDDEN,
			search("/", and(person(_anne)), token).status());
	}

	public void testALabelledLinkShowsThePhotosOfItsLabel() throws Exception {
		createSearch("/AP", and(person(_anne), person(_philipp)));
		ShareStore shares = new ShareStore(_base);
		ShareStore.Issued issued = shares.create("alice", "AP", "Garden", "", Privacy.MEMBERS, Ratings.MIN,
			Arrays.asList(Rights.VIEW));
		shares.setPhotoLabel(issued.getLink(), "Garden");
		restartServer();
		String token = issued.getToken();

		assertEquals("The label is the photo's own.", Arrays.asList("Family/2024 Garden/both.jpg"),
			names(album(get("/", "json", token))));
		assertEquals(HttpServletResponse.SC_OK, get("/Family/2024 Garden/both.jpg", "tn", token).status());
		assertEquals(HttpServletResponse.SC_NOT_FOUND, get("/Trips/2021 Lake/old.jpg", "tn", token).status());
	}

	public void testSearchingIsForMembers() throws Exception {
		FakeResponse anonymous = search("/", and(person(_anne)), null);
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, anonymous.status());
		assertEquals(PhotoSearch.MEMBERS_ONLY, errorMessage(anonymous));
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, get("/", ImageServlet.SEARCH_OPTIONS_TYPE, null).status());
	}

	public void testRatingThroughASavedSearchChangesTheSourceAlbum() throws Exception {
		createSearch("/Trips/AP", and(person(_anne), person(_philipp)));
		AlbumInfo answer = album(get("/Trips/AP/", "json", SharingFixture.CAROL));
		image(answer, "2021 Lake/old.jpg").setRating(2)
			.setLabels(new ArrayList<>(Arrays.asList(LabelName.create().setName("Best"))));

		FakeResponse put = put("/Trips/AP/", write(answer), SharingFixture.CAROL);
		assertEquals(put.body(), HttpServletResponse.SC_OK, put.status());

		ImagePart source = image(album(get("/" + LAKE + "/", "json", SharingFixture.ALICE)), "old.jpg");
		assertEquals(2, source.getRating());
		assertEquals("The labels are the photo's own.", Arrays.asList("Best"), labels(source));
		assertEquals(2, image(album(get("/Trips/AP/", "json", SharingFixture.ALICE)), "2021 Lake/old.jpg").getRating());
		assertEquals("The saved search holds no part after a write.", Collections.emptyList(),
			stored("Trips/AP").getParts());
	}

	public void testEditingTheSearchChangesTheStoredQuery() throws Exception {
		createSearch("/Trips/AP", and(person(_anne), person(_philipp)));
		AlbumInfo answer = album(get("/Trips/AP/", "json", SharingFixture.CAROL));
		answer.setQuery(and(person(_anne)));
		assertEquals(HttpServletResponse.SC_OK, put("/Trips/AP/", write(answer), SharingFixture.CAROL).status());

		assertEquals(1, ((SearchAnd) stored("Trips/AP").getQuery().getRoot()).getCriteria().size());
		assertTrue(names(album(get("/Trips/AP/", "json", SharingFixture.ALICE))).contains("2021 Lake/anne.jpg"));
		assertEquals("A viewer may not change it.", HttpServletResponse.SC_FORBIDDEN,
			put("/Trips/AP/", write(answer), SharingFixture.DAVE).status());
	}

	public void testAnAlbumNeverBecomesASavedSearch() throws Exception {
		AlbumInfo album = album(get("/Public/", "json", SharingFixture.ALICE));
		album.setKind(AlbumKind.SEARCH).setQuery(and(person(_anne)));
		assertEquals(HttpServletResponse.SC_OK, put("/Public/", write(album), SharingFixture.ALICE).status());
		AlbumInfo again = album(get("/Public/", "json", SharingFixture.ALICE));
		assertEquals(AlbumKind.ALBUM, again.getKind());
		assertNull(again.getQuery());
	}

	public void testTheListingShowsTheSavedSearch() throws Exception {
		createSearch("/Trips/AP", and(person(_anne), person(_philipp)));
		FolderInfo forAlice = entry(listing(get("/Trips/", "json", SharingFixture.ALICE)), "AP");
		assertEquals(FolderKind.SEARCH, forAlice.getKind());
		assertEquals("The newest match.", "2021 Lake/private_ap.jpg", forAlice.getIndexPicture().getImage());
		FolderInfo forCarol = entry(listing(get("/Trips/", "json", SharingFixture.CAROL)), "AP");
		assertEquals("The newest match the caller may see.", "2021 Lake/anne_philipp.jpg",
			forCarol.getIndexPicture().getImage());
	}

	public void testWhatASavedSearchRefuses() throws Exception {
		createSearch("/Trips/AP", and(person(_anne)));
		FakeResponse upload = upload("/Trips/AP/", SharingFixture.ALICE, "new.jpg");
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, upload.status());
		assertEquals(PhotoSearch.UPLOAD_REFUSED, errorMessage(upload));
		FakeResponse into = move("/" + LAKE + "/", "Trips/AP", SharingFixture.ALICE, "anne.jpg");
		assertEquals(PhotoSearch.MOVE_REFUSED, errorMessage(into));
		Map<String, String> delete = new HashMap<>();
		delete.put("action", "delete");
		FakeResponse deleted = post("/Trips/AP/", "{\"names\":[{\"name\":\"2021 Lake/anne.jpg\"}]}",
			SharingFixture.ALICE, delete);
		assertEquals(PhotoSearch.DELETE_REFUSED, errorMessage(deleted));
		assertTrue(Files.exists(_base.resolve(LAKE + "/anne.jpg")));
		FakeResponse folder = put("/Trips/AP/Inner", "[\"AlbumInfo\",{\"title\":\"Inner\"}]", SharingFixture.ALICE);
		assertEquals(PhotoSearch.FOLDER_REFUSED, errorMessage(folder));
		Map<String, String> tag = new HashMap<>();
		tag.put("action", "tag-faces");
		assertEquals(PhotoSearch.FACES_REFUSED, errorMessage(post("/Trips/AP/", "{\"faces\":[]}", SharingFixture.ALICE, tag)));
	}

	public void testCreateACollectionFromASavedSearch() throws Exception {
		createSearch("/Trips/AP", and(person(_anne), person(_philipp)));
		assertEquals(HttpServletResponse.SC_OK,
			put("/Best", "[\"AlbumInfo\",{\"kind\":\"COLLECTION\",\"title\":\"Best\"}]", SharingFixture.ALICE).status());
		Map<String, String> collect = new HashMap<>();
		collect.put("action", ImageServlet.COLLECT_ACTION);
		MoveResult result = moveResult(post("/Trips/AP/",
			"{\"target\":\"Best\",\"names\":[{\"name\":\"2021 Lake/old.jpg\"},{\"name\":\"2021 Lake/anne.jpg\"}]}",
			SharingFixture.ALICE, collect));
		assertEquals("old.jpg", outcome(result, "2021 Lake/old.jpg").getNewName());
		assertEquals("Not a match, not added.", ImageServlet.notInAlbum("2021 Lake/anne.jpg"),
			outcome(result, "2021 Lake/anne.jpg").getMessage());
		assertEquals(Arrays.asList("old.jpg"), names(album(get("/Best/", "json", SharingFixture.ALICE))));

		// The search view below a folder names its photos by their paths, and collects them so.
		MoveResult fromView = moveResult(post("/Family/",
			"{\"target\":\"Best\",\"names\":[{\"name\":\"2024 Garden/both.jpg\"}]}", SharingFixture.ALICE, collect));
		assertEquals("both.jpg", outcome(fromView, "2024 Garden/both.jpg").getNewName());
	}

	/**
	 * Probe: a collection holding a photo and a saved search lying in the scope never make the photo
	 * appear twice, nor does a saved search find itself.
	 */
	public void testProbeCollectionsAndSavedSearchesAreNoSecondSource() throws Exception {
		createSearch("/AP", and(person(_anne), person(_philipp)));
		assertEquals(HttpServletResponse.SC_OK,
			put("/Best", "[\"AlbumInfo\",{\"kind\":\"COLLECTION\",\"title\":\"Best\"}]", SharingFixture.ALICE).status());
		Map<String, String> collect = new HashMap<>();
		collect.put("action", ImageServlet.COLLECT_ACTION);
		moveResult(post("/Trips/", "{\"target\":\"Best\",\"names\":[{\"name\":\"2021 Lake/old.jpg\"}]}",
			SharingFixture.ALICE, collect));
		assertEquals(Arrays.asList("old.jpg"), names(album(get("/Best/", "json", SharingFixture.ALICE))));

		List<String> expected = Arrays.asList("Trips/2021 Lake/old.jpg", "Trips/2021 Lake/anne_philipp.jpg",
			"Trips/2021 Lake/private_ap.jpg", "Family/2024 Garden/both.jpg");
		assertEquals("The view: each photo once, from its own album.", expected,
			names(search("/", and(person(_anne), person(_philipp)))));
		assertEquals("The saved search at the root: the same, and not itself.", expected,
			names(album(get("/AP/", "json", SharingFixture.ALICE))).stream()
				.map(name -> name.replace("\\", "/")).collect(java.util.stream.Collectors.toList()));
	}

	public void testTheZipOfASavedSearch() throws Exception {
		createSearch("/Trips/AP", and(person(_anne), person(_philipp)));
		Map<String, String> zip = new HashMap<>();
		zip.put("action", ImageServlet.ZIP_ACTION);
		FakeResponse response = post("/Trips/AP/", "{\"names\":[{\"name\":\"2021 Lake/old.jpg\"}]}",
			SharingFixture.DAVE, zip);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		assertEquals(HttpServletResponse.SC_NOT_FOUND, post("/Trips/AP/",
			"{\"names\":[{\"name\":\"2021 Lake/anne.jpg\"}]}", SharingFixture.DAVE, zip).status());
	}

	public void testTheOptionsAreWhatTheCallerMaySee() throws Exception {
		SearchOptions options = SearchOptions.readSearchOptions(
			reader(body(get("/Trips/", ImageServlet.SEARCH_OPTIONS_TYPE, SharingFixture.CAROL))));
		List<String> persons = new ArrayList<>();
		for (Person person : options.getPersons()) {
			persons.add(person.getName());
		}
		assertEquals(Arrays.asList("Anne", "Philipp"), persons);
		List<String> cameras = new ArrayList<>();
		options.getCameras().forEach(camera -> cameras.add(camera.getName()));
		assertEquals(Arrays.asList("Canon EOS 5D", "Pixel 7"), cameras);
		List<String> labels = new ArrayList<>();
		options.getLabels().forEach(label -> labels.add(label.getName()));
		assertEquals(Arrays.asList("Holiday"), labels);
	}

	// --- The stored format. ---

	public void testThePinnedFixtureIsReadAndEvaluated() throws Exception {
		Files.createDirectories(_base.resolve("Trips/Fixture"));
		String fixture = new String(Files.readAllBytes(FIXTURE.toPath()), StandardCharsets.UTF_8)
			.replace("\"anne\"", "\"" + _anne + "\"").replace("\"philipp\"", "\"" + _philipp + "\"");
		Files.write(_base.resolve("Trips/Fixture/index.json"), fixture.getBytes(StandardCharsets.UTF_8));

		AlbumInfo answer = album(get("/Trips/Fixture/", "json", SharingFixture.ALICE));
		assertEquals(AlbumKind.SEARCH, answer.getKind());
		assertEquals("Anne and Philipp", answer.getTitle());
		assertTrue(answer.isStarred());
		assertEquals(Arrays.asList("2021 Lake/anne_philipp.jpg"), names(answer));
	}

	public void testReadWriteReadIsEqual() throws Exception {
		AlbumInfo read = (AlbumInfo) Resource.readResource(reader(new String(Files.readAllBytes(FIXTURE.toPath()),
			StandardCharsets.UTF_8)));
		assertNull("Every criterion of the fixture is known.", PhotoSearch.refusal(read.getQuery()));
		String written = write(read);
		AlbumInfo again = (AlbumInfo) Resource.readResource(reader(written));
		assertEquals(written, write(again));
		assertEquals(8, ((SearchAnd) again.getQuery().getRoot()).getCriteria().size());
	}

	public void testAnUnknownCriterionIsRefusedVisibly() throws Exception {
		Files.createDirectories(_base.resolve("Trips/Future"));
		Files.write(_base.resolve("Trips/Future/index.json"),
			("[\"AlbumInfo\",{\"kind\":\"SEARCH\",\"title\":\"Future\",\"query\":{\"version\":1,\"root\":[\"SearchAnd\","
				+ "{\"criteria\":[[\"SearchPerson\",{\"person\":\"" + _anne + "\"}],[\"SearchMood\",{\"mood\":\"happy\"}]]}]}}]")
				.getBytes(StandardCharsets.UTF_8));
		FakeResponse response = get("/Trips/Future/", "json", SharingFixture.ALICE);
		assertEquals(HttpServletResponse.SC_NOT_IMPLEMENTED, response.status());
		assertEquals(PhotoSearch.NEWER, errorMessage(response));
		byte[] before = Files.readAllBytes(_base.resolve("Trips/Future/index.json"));
		FakeResponse put = put("/Trips/Future/", "[\"AlbumInfo\",{\"kind\":\"SEARCH\",\"title\":\"Future\"}]",
			SharingFixture.ALICE);
		assertEquals("Never overwritten by a build that cannot read it.", HttpServletResponse.SC_BAD_REQUEST, put.status());
		assertTrue(Arrays.equals(before, Files.readAllBytes(_base.resolve("Trips/Future/index.json"))));

		Files.write(_base.resolve("Trips/Future/index.json"),
			("[\"AlbumInfo\",{\"kind\":\"SEARCH\",\"query\":{\"version\":2,\"root\":[\"SearchAnd\",{}]}}]")
				.getBytes(StandardCharsets.UTF_8));
		servlet().cache().invalidate(new PathInfo(_base, Path.of("Trips/Future")));
		assertEquals("A newer version is refused too.", PhotoSearch.NEWER,
			errorMessage(get("/Trips/Future/", "json", SharingFixture.ALICE)));
		assertEquals(PhotoSearch.NEWER, errorMessage(search("/", unknownRoot(), SharingFixture.ALICE)));
	}

	/** The cheap copy of an answer holds what the model's own copy holds. */
	public void testTheAnswerCopyHoldsEveryField() throws Exception {
		ImagePart full = (ImagePart) de.haumacher.imageServer.shared.model.AlbumPart.readAlbumPart(reader(
			"[\"ImagePart\",{\"kind\":\"VIDEO\",\"name\":\"a.mp4\",\"date\":1,\"width\":2,\"height\":3,"
				+ "\"orientation\":\"ROT_R\",\"rating\":1,\"privacy\":1,\"comment\":\"c\",\"camera\":\"k\","
				+ "\"location\":{\"latitude\":1.5,\"longitude\":2.5},\"contributor\":\"user:x\","
				+ "\"contributorLabel\":\"X\",\"faces\":[{\"index\":0,\"x\":0.1,\"person\":\"p\"}],"
				+ "\"tags\":[{\"x\":0.1,\"person\":\"p\",\"state\":\"CONFIRMED\"}],\"raw\":\"a.dng\","
				+ "\"crop\":{\"x\":0.1,\"y\":0.1,\"w\":0.5,\"h\":0.5},\"labels\":[{\"name\":\"L\"}],"
				+ "\"ref\":{\"hash\":\"h\",\"path\":\"p/a.mp4\"},\"missing\":true,"
				+ "\"places\":{\"tags\":[{\"geonameId\":7,\"name\":\"N\",\"kind\":\"PLACE\"}],\"pending\":\"w\"}}]"));
		assertEquals(write(PhotoCollections.copy(full)), write(PhotoSearch.answerCopy(full)));
	}

	// --- Helpers. ---

	static String unknownRoot() {
		return "{\"version\":1,\"root\":[\"SearchMood\",{\"mood\":\"happy\"}]}";
	}

	void createSearch(String path, SearchQuery query) throws Exception {
		AlbumInfo search = AlbumInfo.create().setKind(AlbumKind.SEARCH).setTitle(path.substring(path.lastIndexOf('/') + 1))
			.setQuery(query);
		FakeResponse response = put(path, write(search), SharingFixture.ALICE);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
	}

	FakeResponse search(String path, SearchQuery query, String token) throws Exception {
		return search(path, write(query), token);
	}

	FakeResponse search(String path, String query, String token) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", ImageServlet.SEARCH_ACTION);
		return post(path, query, token, parameters);
	}

	List<String> search(String path, SearchQuery query) throws Exception {
		FakeResponse response = search(path, query, SharingFixture.ALICE);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		return names(album(response));
	}

	static List<String> names(List<String> names) {
		return names;
	}

	static SearchQuery and(SearchCriterion... criteria) {
		return SearchQuery.create().setVersion(1)
			.setRoot(SearchAnd.create().setCriteria(new ArrayList<>(Arrays.asList(criteria))));
	}

	static SearchOr or(SearchCriterion... criteria) {
		return SearchOr.create().setCriteria(new ArrayList<>(Arrays.asList(criteria)));
	}

	static SearchPerson person(String id) {
		return SearchPerson.create().setPerson(id);
	}

	static long day(int year, int month, int day) {
		return LocalDate.of(year, month, day).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
	}

	static String part(String name, long date, String more, String... persons) {
		StringBuilder result = new StringBuilder("[\"ImagePart\",{\"name\":\"").append(name)
			.append("\",\"width\":4,\"height\":3,\"date\":").append(date).append(more);
		if (persons != null) {
			result.append(",\"tags\":[");
			for (int n = 0; n < persons.length; n++) {
				result.append(n == 0 ? "" : ",").append(tag(persons[n], "CONFIRMED", 0.1 + 0.3 * n));
			}
			result.append("]");
		}
		return result.append("}]").toString();
	}

	static String tag(String person, String state, double x) {
		return "{\"x\":" + x + ",\"y\":0.1,\"w\":0.2,\"h\":0.2,\"person\":\"" + person + "\",\"state\":\"" + state + "\"}";
	}

	AlbumInfo stored(String folder) throws Exception {
		return (AlbumInfo) Resource.readResource(reader(new String(
			Files.readAllBytes(_base.resolve(folder).resolve("index.json")), StandardCharsets.UTF_8)));
	}

	static List<String> names(AlbumInfo album) {
		List<String> result = new ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				result.add(((ImagePart) part).getName());
			}
		}
		return result;
	}

	static ImagePart image(AlbumInfo album, String name) {
		ImagePart result = Crops.findImage(album, name);
		assertNotNull("No part '" + name + "' in " + names(album), result);
		return result;
	}

	static List<String> labels(ImagePart image) {
		List<String> result = new ArrayList<>();
		for (LabelName label : image.getLabels()) {
			result.add(label.getName());
		}
		return result;
	}

	static String write(Object object) throws Exception {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (JsonWriter json = new JsonWriter(new WriterAdapter(new OutputStreamWriter(buffer, StandardCharsets.UTF_8)))) {
			if (object instanceof Resource) {
				((Resource) object).writeTo(json);
			} else {
				((SearchQuery) object).writeTo(json);
			}
		}
		return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
	}
}
