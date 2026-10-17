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
import de.haumacher.imageServer.shared.model.Heading;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.LabelName;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.MoveResult;
import de.haumacher.imageServer.shared.model.Person;
import de.haumacher.imageServer.shared.model.PlaceInfo;
import de.haumacher.imageServer.shared.model.PlaceKind;
import de.haumacher.imageServer.shared.model.PlaceTag;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.SearchAnd;
import de.haumacher.imageServer.shared.model.SearchCamera;
import de.haumacher.imageServer.shared.model.SearchCriterion;
import de.haumacher.imageServer.shared.model.SearchDate;
import de.haumacher.imageServer.shared.model.SearchFolder;
import de.haumacher.imageServer.shared.model.SearchGroup;
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

	/**
	 * The search opened from an album asks the album itself (issue #249): its photographs alone,
	 * named by their own file name, each as the caller is answered it in the album.
	 */
	public void testTheScopeOfAnAlbumIsTheAlbumAlone() throws Exception {
		SearchQuery query = and(person(_anne), person(_philipp));
		String album = "/" + LAKE + "/";
		AlbumInfo found = album(search(album, query, SharingFixture.ALICE));
		assertEquals("Named below the album, nothing of another album.",
			Arrays.asList("old.jpg", "anne_philipp.jpg", "private_ap.jpg"), names(found));
		assertEquals(LAKE + "/old.jpg", image(found, "old.jpg").getRef().getPath());
		assertEquals("Carol may not see the private photo of the album either.",
			Arrays.asList("old.jpg", "anne_philipp.jpg"), names(album(search(album, query, SharingFixture.CAROL))));
		assertEquals("The garden is another album.", Arrays.asList("both.jpg"),
			search("/" + GARDEN + "/", query));

		SearchOptions options = SearchOptions.readSearchOptions(
			reader(body(get(album, ImageServlet.SEARCH_OPTIONS_TYPE, SharingFixture.ALICE))));
		List<String> labels = new ArrayList<>();
		options.getLabels().forEach(label -> labels.add(label.getName()));
		assertEquals("The options are the album's own.", Arrays.asList("Holiday"), labels);
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

	public void testTheStoredFormIsTheQueryAndThePicture() throws Exception {
		createSearch("/Trips/AP", and(person(_anne), person(_philipp)));
		AlbumInfo stored = stored("Trips/AP");
		assertEquals(AlbumKind.SEARCH, stored.getKind());
		assertEquals(PhotoSearch.VERSION, stored.getQuery().getVersion());
		assertEquals(Collections.emptyList(), stored.getParts());
		assertEquals("The first match its creator is shown, see issue #287.", "2021 Lake/old.jpg",
			stored.getIndexPicture().getImage());
		assertEquals("No grouping was asked for.", Collections.emptyList(), stored.getQuery().getGroupBy());
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

	/**
	 * The grouping of a saved search (issue #244) is stored with its query and read back, and the
	 * server evaluates nothing of it: the same photographs, by date.
	 */
	public void testTheGroupingIsStoredWithTheQuery() throws Exception {
		SearchQuery grouped = and(person(_anne), person(_philipp))
			.setGroupBy(new ArrayList<>(Arrays.asList(SearchGroup.YEAR, SearchGroup.COUNTRY)));
		assertNull("A grouping is no criterion and never refused.", PhotoSearch.refusal(grouped));
		createSearch("/Trips/AP", grouped);

		AlbumInfo stored = stored("Trips/AP");
		assertEquals(Arrays.asList(SearchGroup.YEAR, SearchGroup.COUNTRY), stored.getQuery().getGroupBy());
		assertEquals(Collections.emptyList(), stored.getParts());
		assertEquals("2021 Lake/old.jpg", stored.getIndexPicture().getImage());

		AlbumInfo answer = album(get("/Trips/AP/", "json", SharingFixture.ALICE));
		assertEquals("Answered to the app, which derives the headings.",
			Arrays.asList(SearchGroup.YEAR, SearchGroup.COUNTRY), answer.getQuery().getGroupBy());
		createSearch("/Trips/Plain", and(person(_anne), person(_philipp)));
		assertEquals("The grouping changes nothing of what is found.",
			names(album(get("/Trips/Plain/", "json", SharingFixture.ALICE))), names(answer));
		for (AlbumPart part : answer.getParts()) {
			assertFalse("No heading is made by the server.", part instanceof Heading);
		}
		assertEquals(names(answer), search("/Trips/", grouped));
	}

	/** An edit of a saved search changes its grouping alone, see issue #244. */
	public void testEditingTheGroupingChangesTheStoredQuery() throws Exception {
		createSearch("/Trips/AP", and(person(_anne), person(_philipp)));
		AlbumInfo answer = album(get("/Trips/AP/", "json", SharingFixture.CAROL));
		answer.setQuery(and(person(_anne), person(_philipp)).setGroupBy(new ArrayList<>(Arrays.asList(SearchGroup.ALBUM))));
		assertEquals(HttpServletResponse.SC_OK, put("/Trips/AP/", write(answer), SharingFixture.CAROL).status());

		AlbumInfo stored = stored("Trips/AP");
		assertEquals(Arrays.asList(SearchGroup.ALBUM), stored.getQuery().getGroupBy());
		assertEquals(2, ((SearchAnd) stored.getQuery().getRoot()).getCriteria().size());
		assertEquals(Collections.emptyList(), stored.getParts());
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
		assertEquals("The picture stored at its creation.", "2021 Lake/old.jpg", forAlice.getIndexPicture().getImage());
		FolderInfo forCarol = entry(listing(get("/Trips/", "json", SharingFixture.CAROL)), "AP");
		assertEquals("2021 Lake/old.jpg", forCarol.getIndexPicture().getImage());
		assertEquals("The tile's picture is the one opening shows.", "2021 Lake/old.jpg",
			album(get("/Trips/AP/", "json", SharingFixture.ALICE)).getIndexPicture().getImage());
	}

	// --- The picture of a saved search, see issue #287. ---

	/** A listing showing saved searches runs none of them, whoever asks. */
	public void testAListingNeverRunsASavedSearch() throws Exception {
		createSearch("/Trips/AP", and(person(_anne), person(_philipp)));
		createSearch("/Trips/Anne", and(person(_anne)));
		String link = issue("alice", "Trips", "Grandma", "", Privacy.MEMBERS, 1, Rights.VIEW);
		long before = servlet().searchRuns();

		ListingInfo forAlice = listing(get("/Trips/", "json", SharingFixture.ALICE));
		ListingInfo forCarol = listing(get("/Trips/", "json", SharingFixture.CAROL));
		ListingInfo forDave = listing(get("/Trips/", "json", SharingFixture.DAVE));
		ListingInfo forLink = listing(get("/", "json", link));
		ListingInfo preview = listing(get("/Trips/", "json", SharingFixture.ALICE, "public"));

		assertEquals("No listing ran a query.", before, servlet().searchRuns());
		assertEquals("2021 Lake/old.jpg", entry(forAlice, "AP").getIndexPicture().getImage());
		assertEquals("2021 Lake/old.jpg", entry(forAlice, "Anne").getIndexPicture().getImage());
		assertEquals("2021 Lake/old.jpg", entry(forCarol, "AP").getIndexPicture().getImage());
		assertEquals("2021 Lake/old.jpg", entry(forDave, "Anne").getIndexPicture().getImage());
		assertNull("The rating floor of the link hides the stored picture: the search icon, no other.",
			entry(forLink, "AP").getIndexPicture());
		assertNotNull(entry(preview, "AP"));

		album(get("/Trips/AP/", "json", SharingFixture.ALICE));
		assertTrue("Opening runs it.", servlet().searchRuns() > before);
	}

	/**
	 * Probe: a listing one level up — whose folder-of-folders tiles find their cover further down,
	 * where saved searches lie — runs none either; nor does a search at the root's own tile.
	 */
	public void testAListingAboveTheSearchesRunsNone() throws Exception {
		createSearch("/AP", and(person(_anne), person(_philipp)));
		createSearch("/Trips/AP", and(person(_anne), person(_philipp)));
		createSearch("/Family/AP", and(person(_anne), person(_philipp)));
		long before = servlet().searchRuns();
		for (String caller : new String[] { SharingFixture.ALICE, SharingFixture.CAROL, SharingFixture.DAVE }) {
			ListingInfo root = listing(get("/", "json", caller));
			assertEquals(FolderKind.SEARCH, entry(root, "AP").getKind());
		}
		assertEquals("No listing above the searches ran a query.", before, servlet().searchRuns());
	}

	/** A search that finds nothing at its creation stores no picture, and its tile shows the icon. */
	public void testASearchFindingNothingStoresNoPicture() throws Exception {
		createSearch("/Trips/Nobody", and(SearchCamera.create().setCamera("No such camera")));
		assertNull(stored("Trips/Nobody").getIndexPicture());
		assertNull(entry(listing(get("/Trips/", "json", SharingFixture.ALICE)), "Nobody").getIndexPicture());
	}

	/**
	 * The album picture action works on a saved search: the picture chosen is stored, and the
	 * listing shows it to whoever may see it and the search icon to everybody else.
	 */
	public void testChoosingAnotherPicture() throws Exception {
		createSearch("/Trips/AP", and(person(_anne), person(_philipp)));
		AlbumInfo answer = album(get("/Trips/AP/", "json", SharingFixture.ALICE));
		answer.setIndexPicture(PrivacyFilter.thumbnail(image(answer, "2021 Lake/private_ap.jpg")));
		assertEquals(HttpServletResponse.SC_OK, put("/Trips/AP/", write(answer), SharingFixture.ALICE).status());
		assertEquals("2021 Lake/private_ap.jpg", stored("Trips/AP").getIndexPicture().getImage());

		long before = servlet().searchRuns();
		assertEquals("2021 Lake/private_ap.jpg",
			entry(listing(get("/Trips/", "json", SharingFixture.ALICE)), "AP").getIndexPicture().getImage());
		assertNull("A member without the clearance is shown the search icon, never another photograph.",
			entry(listing(get("/Trips/", "json", SharingFixture.CAROL)), "AP").getIndexPicture());
		assertNull("Nor does the author's preview of the public view show it.",
			entry(listing(get("/Trips/", "json", SharingFixture.ALICE, "public")), "AP").getIndexPicture());
		assertEquals(before, servlet().searchRuns());

		// An editor who may not see the picture is answered another one on opening; written back,
		// it is no choice of theirs.
		AlbumInfo forCarol = album(get("/Trips/AP/", "json", SharingFixture.CAROL));
		assertEquals("2021 Lake/old.jpg", forCarol.getIndexPicture().getImage());
		assertEquals(HttpServletResponse.SC_OK, put("/Trips/AP/", write(forCarol), SharingFixture.CAROL).status());
		assertEquals("2021 Lake/private_ap.jpg", stored("Trips/AP").getIndexPicture().getImage());
		// A deliberate choice of theirs is taken.
		forCarol.setIndexPicture(PrivacyFilter.thumbnail(image(forCarol, "2021 Lake/anne_philipp.jpg")));
		assertEquals(HttpServletResponse.SC_OK, put("/Trips/AP/", write(forCarol), SharingFixture.CAROL).status());
		assertEquals("2021 Lake/anne_philipp.jpg", stored("Trips/AP").getIndexPicture().getImage());
		assertEquals("2021 Lake/anne_philipp.jpg",
			entry(listing(get("/Trips/", "json", SharingFixture.CAROL)), "AP").getIndexPicture().getImage());
	}

	/** A viewer may not choose the picture, and is told why. */
	public void testAViewerMayNotChooseThePicture() throws Exception {
		createSearch("/Trips/AP", and(person(_anne), person(_philipp)));
		AlbumInfo answer = album(get("/Trips/AP/", "json", SharingFixture.DAVE));
		answer.setIndexPicture(PrivacyFilter.thumbnail(image(answer, "2021 Lake/anne_philipp.jpg")));
		FakeResponse refused = put("/Trips/AP/", write(answer), SharingFixture.DAVE);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, refused.status());
		assertFalse("The refusal says why.", errorMessage(refused).isEmpty());
		assertEquals("2021 Lake/old.jpg", stored("Trips/AP").getIndexPicture().getImage());
	}

	/** A new query takes a picture among what it finds now, see issue #287. */
	public void testANewQueryKeepsOrReplacesThePicture() throws Exception {
		createSearch("/Trips/AP", and(person(_anne), person(_philipp)));
		AlbumInfo answer = album(get("/Trips/AP/", "json", SharingFixture.ALICE));
		answer.setQuery(and(person(_anne)));
		assertEquals(HttpServletResponse.SC_OK, put("/Trips/AP/", write(answer), SharingFixture.ALICE).status());
		assertEquals("Still found: kept.", "2021 Lake/old.jpg", stored("Trips/AP").getIndexPicture().getImage());

		answer.setQuery(and(SearchCamera.create().setCamera("Pixel 7")));
		assertEquals(HttpServletResponse.SC_OK, put("/Trips/AP/", write(answer), SharingFixture.ALICE).status());
		assertEquals("No longer found: the first new match.", "2021 Lake/anne.jpg",
			stored("Trips/AP").getIndexPicture().getImage());
	}

	/**
	 * A saved search stored before its picture was (the pinned fixture): its tile shows the icon
	 * without running it, a viewer's opening writes nothing, an editor's opening stores the first
	 * match, and the next listing shows it.
	 */
	public void testAnOldSearchIsGivenItsPictureOnOpening() throws Exception {
		Files.createDirectories(_base.resolve("Trips/Fixture"));
		String fixture = new String(Files.readAllBytes(FIXTURE.toPath()), StandardCharsets.UTF_8)
			.replace("\"anne\"", "\"" + _anne + "\"").replace("\"philipp\"", "\"" + _philipp + "\"");
		Files.write(_base.resolve("Trips/Fixture/index.json"), fixture.getBytes(StandardCharsets.UTF_8));
		assertNull("The fixture predates the picture.", stored("Trips/Fixture").getIndexPicture());

		long before = servlet().searchRuns();
		assertNull(entry(listing(get("/Trips/", "json", SharingFixture.ALICE)), "Fixture").getIndexPicture());
		assertEquals(before, servlet().searchRuns());

		byte[] old = Files.readAllBytes(_base.resolve("Trips/Fixture/index.json"));
		assertFalse(album(get("/Trips/Fixture/", "json", SharingFixture.DAVE)).getParts().isEmpty());
		assertTrue("A viewer's opening writes nothing.",
			Arrays.equals(old, Files.readAllBytes(_base.resolve("Trips/Fixture/index.json"))));

		album(get("/Trips/Fixture/", "json", SharingFixture.ALICE));
		AlbumInfo stored = stored("Trips/Fixture");
		assertEquals("2021 Lake/anne_philipp.jpg", stored.getIndexPicture().getImage());
		assertEquals("Everything else stays.", "Anne and Philipp", stored.getTitle());
		assertTrue(stored.isStarred());
		assertNull(PhotoSearch.refusal(stored.getQuery()));

		before = servlet().searchRuns();
		assertEquals("2021 Lake/anne_philipp.jpg",
			entry(listing(get("/Trips/", "json", SharingFixture.ALICE)), "Fixture").getIndexPicture().getImage());
		assertEquals(before, servlet().searchRuns());
	}

	/**
	 * A stored picture whose photograph left the search shows the icon, and an editor's next
	 * opening stores the first match in its place.
	 */
	public void testAPictureLeadingNowhereIsReplacedOnOpening() throws Exception {
		createSearch("/Trips/AP", and(person(_anne), person(_philipp)));
		FakeResponse moved = move("/" + LAKE + "/", GARDEN, SharingFixture.ALICE, "old.jpg");
		assertEquals(moved.body(), HttpServletResponse.SC_OK, moved.status());

		long before = servlet().searchRuns();
		assertNull("Never an error, never another photograph.",
			entry(listing(get("/Trips/", "json", SharingFixture.ALICE)), "AP").getIndexPicture());
		assertEquals(before, servlet().searchRuns());

		album(get("/Trips/AP/", "json", SharingFixture.ALICE));
		assertEquals("2021 Lake/anne_philipp.jpg", stored("Trips/AP").getIndexPicture().getImage());
		assertEquals("2021 Lake/anne_philipp.jpg",
			entry(listing(get("/Trips/", "json", SharingFixture.ALICE)), "AP").getIndexPicture().getImage());
	}

	/** The picture of a saved search reads, writes and reads again unchanged. */
	public void testReadWriteReadKeepsThePicture() throws Exception {
		String source = "[\"AlbumInfo\",{\"kind\":\"SEARCH\",\"title\":\"Anne\",\"indexPicture\":{\"image\":"
			+ "\"2021 Lake/old.jpg\",\"scale\":1.5,\"tx\":2.0,\"ty\":3.0,\"orientation\":\"IDENTITY\"},"
			+ "\"query\":{\"version\":1,\"root\":[\"SearchAnd\",{\"criteria\":[[\"SearchPerson\",{\"person\":\"anne\"}]]}]}}]";
		AlbumInfo read = (AlbumInfo) Resource.readResource(reader(source));
		assertEquals("2021 Lake/old.jpg", read.getIndexPicture().getImage());
		String written = write(read);
		AlbumInfo again = (AlbumInfo) Resource.readResource(reader(written));
		assertEquals(written, write(again));
		assertEquals(1.5, again.getIndexPicture().getScale(), 0.0);
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

	/** A saved search with a grouping (issue #244) reads, writes and reads again unchanged. */
	public void testReadWriteReadKeepsTheGrouping() throws Exception {
		String source = "[\"AlbumInfo\",{\"kind\":\"SEARCH\",\"title\":\"Anne\",\"query\":{\"version\":1,"
			+ "\"root\":[\"SearchAnd\",{\"criteria\":[[\"SearchPerson\",{\"person\":\"anne\"}]]}],"
			+ "\"groupBy\":[\"MONTH\",\"TOWN\"]}}]";
		AlbumInfo read = (AlbumInfo) Resource.readResource(reader(source));
		assertNull(PhotoSearch.refusal(read.getQuery()));
		assertEquals(Arrays.asList(SearchGroup.MONTH, SearchGroup.TOWN), read.getQuery().getGroupBy());
		String written = write(read);
		AlbumInfo again = (AlbumInfo) Resource.readResource(reader(written));
		assertEquals(written, write(again));
		assertEquals(Arrays.asList(SearchGroup.MONTH, SearchGroup.TOWN), again.getQuery().getGroupBy());
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
				+ "\"places\":{\"tags\":[{\"geonameId\":7,\"name\":\"N\",\"kind\":\"PLACE\"}],\"pending\":\"w\"},"
				+ "\"placeCaption\":\"HIDDEN\"}]"));
		assertEquals(write(PhotoCollections.copy(full)), write(PhotoSearch.answerCopy(full)));
	}

	// --- The slim answer of a search. ---

	public void testASearchAnswersEachPhotographSlim() throws Exception {
		FakeResponse response = search("/Family/", and(person(_anne)), SharingFixture.ALICE);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		ImagePart found = image(album(response), "2024 Garden/garden.jpg");
		assertTrue("A search answers its photographs partial.", found.isPartial());
		assertTrue(found.getTags().isEmpty());
		assertTrue(found.getFaces().isEmpty());
		assertEquals("Every other field is kept.", day(2024, 6, 1), found.getDate());

		ImagePart own = ownDescription("/Family/2024 Garden/garden.jpg");
		assertFalse("The photograph's own description has everything.", own.isPartial());
		assertEquals(2, own.getTags().size());
	}

	public void testASavedSearchAnswersEachPhotographSlim() throws Exception {
		createSearch("/Family/Anne", and(person(_anne)));
		AlbumInfo answer = album(get("/Family/Anne/", "json", SharingFixture.ALICE));
		ImagePart found = image(answer, "2024 Garden/garden.jpg");
		assertTrue(found.isPartial());
		assertTrue(found.getTags().isEmpty());
		assertTrue(found.getFaces().isEmpty());

		ImagePart own = ownDescription("/Family/Anne/2024 Garden/garden.jpg");
		assertFalse("Addressed through the saved search, the photograph answers everything.", own.isPartial());
		assertEquals(2, own.getTags().size());
		assertFalse("The sidecar never stores the word.",
			new String(Files.readAllBytes(_base.resolve(GARDEN).resolve("index.json")), StandardCharsets.UTF_8)
				.contains("partial"));
	}

	/**
	 * Review probe: the slim answer is a copy made on the way out. The album the photograph lies in
	 * answers it full before and after a search, a second search answers slim again, and the
	 * trimmed places read as the place line reads the full ones.
	 */
	public void testProbeTheSlimAnswerNeverTouchesTheAlbum() throws Exception {
		ImagePart before = image(album(get("/Family/2024 Garden/", "json", SharingFixture.ALICE)), "garden.jpg");
		assertFalse(before.isPartial());
		assertEquals(2, before.getTags().size());

		for (int round = 0; round < 2; round++) {
			FakeResponse response = search("/Family/", and(person(_anne)), SharingFixture.ALICE);
			assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
			ImagePart found = image(album(response), "2024 Garden/garden.jpg");
			assertTrue("Round " + round + " answers slim.", found.isPartial());
			assertTrue(found.getTags().isEmpty());
			for (PlaceTag tag : found.getPlaces() == null ? Collections.<PlaceTag> emptyList()
				: found.getPlaces().getTags()) {
				assertEquals("No id travels with a trimmed place.", 0, tag.getGeonameId());
			}
		}

		ImagePart after = image(album(get("/Family/2024 Garden/", "json", SharingFixture.ALICE)), "garden.jpg");
		assertFalse("The album's own answer is untouched by the search.", after.isPartial());
		assertEquals(2, after.getTags().size());
		assertEquals(2, image(album(get("/Family/2024 Garden/", "json", SharingFixture.ALICE)), "garden.jpg")
			.getTags().size());

		// The trimmed places of a full place list keep exactly what the line reads, in order.
		PlaceInfo full = PlaceInfo.create();
		String[][] places = { { "COUNTRY", "Germany" }, { "ADM_1", "Baden-Württemberg" }, { "ADM_2", "Karlsruhe Region" },
			{ "ADM_3", "Stadtkreis Karlsruhe" }, { "ADM_4", "Karlsruhe" }, { "PLACE", "Karlsruhe" },
			{ "DISTRICT", "Innenstadt" }, { "FEATURE", "Karlsruhe Schloss" } };
		int id = 1;
		for (String[] place : places) {
			full.addTag(PlaceTag.create().setGeonameId(id++).setName(place[1]).setKind(PlaceKind.valueOf(place[0]))
				.setCountry("DE"));
		}
		PlaceInfo slim = PhotoSearch.placeLine(full);
		List<String> kept = new ArrayList<>();
		for (PlaceTag tag : slim.getTags()) {
			kept.add(tag.getKind() + ":" + tag.getName());
			assertEquals(0, tag.getGeonameId());
			assertTrue(tag.getCountry() == null || tag.getCountry().isEmpty());
		}
		assertEquals(Arrays.asList("COUNTRY:Germany", "ADM_1:Baden-Württemberg", "PLACE:Karlsruhe",
			"DISTRICT:Innenstadt", "FEATURE:Karlsruhe Schloss"), kept);
		assertEquals("Without a town, the most local division stands in.", Arrays.asList("ADM_3:Stadtkreis Karlsruhe"),
			kindsOf(PhotoSearch.placeLine(PlaceInfo.create()
				.addTag(PlaceTag.create().setGeonameId(9).setName("Karlsruhe Region").setKind(PlaceKind.ADM_2))
				.addTag(PlaceTag.create().setGeonameId(8).setName("Stadtkreis Karlsruhe").setKind(PlaceKind.ADM_3)))));
	}

	private static List<String> kindsOf(PlaceInfo places) {
		List<String> result = new ArrayList<>();
		for (PlaceTag tag : places.getTags()) {
			result.add(tag.getKind() + ":" + tag.getName());
		}
		return result;
	}

	public void testTheOptionsAreReadOffTheFullPhotographs() throws Exception {
		// The options name the persons of what was found; a slim answer must not empty them.
		SearchOptions options = SearchOptions.readSearchOptions(
			reader(body(get("/Family/", ImageServlet.SEARCH_OPTIONS_TYPE, SharingFixture.ALICE))));
		assertEquals(2, options.getPersons().size());
	}

	/** A photograph with two faces, two tags, eight place names, a description, a camera and two labels. */
	public void testTheSlimAnswerIsMuchSmallerThanTheFullOne() throws Exception {
		ImagePart full = (ImagePart) de.haumacher.imageServer.shared.model.AlbumPart.readAlbumPart(reader(
			"[\"ImagePart\",{\"kind\":\"IMAGE\",\"name\":\"2023 Karlsruhe/IMG_20230701_123456.jpg\","
				+ "\"date\":1688214896000,\"width\":4000,\"height\":3000,\"orientation\":\"IDENTITY\",\"rating\":1,"
				+ "\"comment\":\"Anne and Philipp in front of the palace\",\"camera\":\"Google Pixel 7\","
				+ "\"location\":{\"latitude\":49.0134,\"longitude\":8.4044},"
				+ "\"faces\":[" + face(0, 0.12, _anne) + "," + face(1, 0.55, _philipp) + "],"
				+ "\"tags\":[" + tag(_anne, "CONFIRMED", 0.12) + "," + tag(_philipp, "CONFIRMED", 0.55) + "],"
				+ "\"labels\":[{\"name\":\"Holiday\"},{\"name\":\"Family\"}],"
				+ "\"ref\":{\"hash\":\"\",\"path\":\"2023 Karlsruhe/IMG_20230701_123456.jpg\"},"
				+ "\"places\":{\"tags\":["
				+ place(2921044, "Germany", "COUNTRY", "PCLI") + ","
				+ place(2953481, "Baden-Württemberg", "ADM1", "ADM1") + ","
				+ place(3214105, "Regierungsbezirk Karlsruhe", "ADM2", "ADM2") + ","
				+ place(3214104, "Stadtkreis Karlsruhe", "ADM3", "ADM3") + ","
				+ place(6555709, "Karlsruhe", "ADM4", "ADM4") + ","
				+ place(2892794, "Karlsruhe", "PLACE", "PPLA2") + ","
				+ place(6559171, "Innenstadt-West", "DISTRICT", "PPLX") + ","
				+ place(6942213, "Karlsruhe Palace", "FEATURE", "CSTL") + "]}}]"));
		ImagePart slim = PhotoSearch.partialCopy(full);
		int fullBytes = write(full).getBytes(StandardCharsets.UTF_8).length;
		int slimBytes = write(slim).getBytes(StandardCharsets.UTF_8).length;
		System.out.println("A search result photograph: " + fullBytes + " bytes full, " + slimBytes + " bytes partial.");
		assertTrue(slimBytes + " of " + fullBytes, slimBytes < fullBytes * 0.6);

		List<String> kept = new ArrayList<>();
		for (de.haumacher.imageServer.shared.model.PlaceTag tag : slim.getPlaces().getTags()) {
			kept.add(tag.getKind() + " " + tag.getName());
			assertEquals("No id.", 0, tag.getGeonameId());
			assertEquals("No country.", "", tag.getCountry());
			assertEquals("No feature code.", "", tag.getFeatureCode());
		}
		assertEquals("What the place line reads: the town once, the divisions it falls back on left out.",
			Arrays.asList("COUNTRY Germany", "ADM_1 Baden-Württemberg", "PLACE Karlsruhe", "DISTRICT Innenstadt-West",
				"FEATURE Karlsruhe Palace"),
			kept);
		assertEquals("The full photograph is left as it is.", 8, full.getPlaces().getTags().size());
		assertEquals(2, full.getTags().size());
	}

	public void testThePlaceLineFallsBackOnTheMostLocalDivision() {
		de.haumacher.imageServer.shared.model.PlaceInfo places = de.haumacher.imageServer.shared.model.PlaceInfo
			.create().setPending("")
			.setTags(Arrays.asList(placeTag("France", de.haumacher.imageServer.shared.model.PlaceKind.COUNTRY),
				placeTag("Bretagne", de.haumacher.imageServer.shared.model.PlaceKind.ADM_1),
				placeTag("Finistère", de.haumacher.imageServer.shared.model.PlaceKind.ADM_2),
				placeTag("Brest", de.haumacher.imageServer.shared.model.PlaceKind.ADM_3)));
		List<String> kept = new ArrayList<>();
		for (de.haumacher.imageServer.shared.model.PlaceTag tag : PhotoSearch.placeLine(places).getTags()) {
			kept.add(tag.getName());
		}
		assertEquals(Arrays.asList("France", "Bretagne", "Brest"), kept);

		de.haumacher.imageServer.shared.model.PlaceInfo pending = de.haumacher.imageServer.shared.model.PlaceInfo
			.create().setPending("Place names for China are being loaded.");
		assertEquals("The sentence why there are none yet is kept.", pending.getPending(),
			PhotoSearch.placeLine(pending).getPending());
		assertNull(PhotoSearch.placeLine(null));
	}

	private static de.haumacher.imageServer.shared.model.PlaceTag placeTag(String name,
			de.haumacher.imageServer.shared.model.PlaceKind kind) {
		return de.haumacher.imageServer.shared.model.PlaceTag.create().setGeonameId(name.hashCode()).setName(name)
			.setKind(kind).setCountry("FR");
	}

	private static String face(int index, double x, String person) {
		return "{\"index\":" + index + ",\"x\":" + x + ",\"y\":0.2,\"w\":0.15,\"h\":0.2,\"cluster\":\"c" + index
			+ "\",\"person\":\"" + person + "\",\"state\":\"CONFIRMED\"}";
	}

	private static String place(int id, String name, String kind, String featureCode) {
		return "{\"geonameId\":" + id + ",\"name\":\"" + name + "\",\"kind\":\"" + kind + "\",\"featureCode\":\""
			+ featureCode + "\",\"country\":\"DE\"}";
	}

	private ImagePart ownDescription(String path) throws Exception {
		FakeResponse response = get(path, "json", SharingFixture.ALICE);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		return (ImagePart) Resource.readResource(reader(body(response)));
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
