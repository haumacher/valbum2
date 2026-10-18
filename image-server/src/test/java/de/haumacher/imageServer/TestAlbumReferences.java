/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.auth.Ratings;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumKind;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.DuplicateList;
import de.haumacher.imageServer.shared.model.FolderKind;
import de.haumacher.imageServer.shared.model.Heading;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.LabelName;
import de.haumacher.imageServer.shared.model.MoveResult;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.SearchAnd;
import de.haumacher.imageServer.shared.model.SearchQuery;
import de.haumacher.imageServer.shared.model.SearchText;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Test case for issue #307: an ordinary album holds references beside its own files, and a
 * collection (issue #221) is an album without files of its own.
 *
 * <p>
 * The library is the {@link SharingFixture} with two more albums: <code>Trip</code> holding
 * <code>a.jpg</code> and <code>c.jpg</code>, taken at 1000 and 3000, and <code>Other</code> holding
 * <code>b.jpg</code> and <code>d.jpg</code>, taken at 2000 and 4000, <code>b.jpg</code> described
 * "Beach".
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestAlbumReferences extends ShareTestCase {

	private static final String TRIP = "/Trip/";

	private static final String OTHER = "/Other/";

	private static final String ZOO = "/" + SharingFixture.ZOO + "/";

	private static final File FIXTURE = new File("src/test/fixtures/collection/index.json");

	private static final File TEST_ALBUM = new File("src/test/fixtures/test-album");

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		SharingFixture.album(_base, "Trip", "Trip",
			"[\"ImagePart\",{\"name\":\"a.jpg\",\"width\":4,\"height\":3,\"date\":1000}],"
				+ "[\"ImagePart\",{\"name\":\"c.jpg\",\"width\":4,\"height\":3,\"date\":3000}]",
			"a.jpg", "c.jpg");
		SharingFixture.album(_base, "Other", "Other",
			"[\"ImagePart\",{\"name\":\"b.jpg\",\"width\":4,\"height\":3,\"date\":2000,\"comment\":\"Beach\"}],"
				+ "[\"ImagePart\",{\"name\":\"d.jpg\",\"width\":4,\"height\":3,\"date\":4000}]",
			"b.jpg", "d.jpg");
		restartServer();
	}

	// --- Placed and resolved. ---

	public void testAnAlbumAnswersItsFilesAndItsReferencesInDateOrder() throws Exception {
		MoveResult result = moveResult(collect(OTHER, "Trip", SharingFixture.ALICE, "b.jpg", "d.jpg"));
		assertEquals("b.jpg", outcome(result, "b.jpg").getNewName());

		AlbumInfo answer = album(get(TRIP, "json", SharingFixture.ALICE));
		assertEquals(AlbumKind.ALBUM, answer.getKind());
		assertEquals(Arrays.asList("a.jpg", "b.jpg", "c.jpg", "d.jpg"), outline(answer));
		ImagePart b = image(answer, "b.jpg");
		assertEquals("Answered as its own album answers it.", 2000, b.getDate());
		assertEquals(4, b.getWidth());
		assertEquals("Beach", b.getComment());
		assertEquals("Other/b.jpg", b.getRef().getPath());
		assertNull("A file of the album is no reference.", image(answer, "a.jpg").getRef());

		AlbumInfo stored = stored("Trip");
		assertEquals("An album with files of its own is stored as one.", AlbumKind.ALBUM, stored.getKind());
		ImagePart reference = image(stored, "b.jpg");
		assertEquals("The stored form: no width, no description of the photograph's own.", 0, reference.getWidth());
		assertEquals("", reference.getComment());
		assertEquals("The date is the hint the album is ordered by.", 2000, reference.getDate());
		assertFalse("Nothing is copied.", Files.exists(_base.resolve("Trip").resolve("b.jpg")));

		FakeResponse original = get(TRIP + "b.jpg", null, SharingFixture.ALICE);
		assertEquals(HttpServletResponse.SC_OK, original.status());
		assertTrue(Arrays.equals(Files.readAllBytes(_base.resolve("Other/b.jpg")), original.bodyBytes()));
		assertEquals(HttpServletResponse.SC_OK, get(TRIP + "b.jpg", "tn", SharingFixture.DAVE).status());

		// The order is the album's own, by hand as by date.
		List<AlbumPart> parts = new ArrayList<>(answer.getParts());
		Collections.reverse(parts);
		answer.setParts(parts);
		assertEquals(HttpServletResponse.SC_OK, put(TRIP, write(answer), SharingFixture.ALICE).status());
		assertEquals(Arrays.asList("d.jpg", "c.jpg", "b.jpg", "a.jpg"),
			outline(album(get(TRIP, "json", SharingFixture.ALICE))));
	}

	public void testANameIsFreeAmongTheFilesOfTheAlbum() throws Exception {
		SharingFixture.album(_base, "More", "More",
			"[\"ImagePart\",{\"name\":\"a.jpg\",\"width\":4,\"height\":3,\"date\":1500}]", "a.jpg");
		restartServer();
		MoveResult result = moveResult(collect("/More/", "Trip", SharingFixture.ALICE, "a.jpg"));
		assertEquals("a-2.jpg", outcome(result, "a.jpg").getNewName());
		assertEquals(Arrays.asList("a.jpg", "a-2.jpg", "c.jpg"), outline(album(get(TRIP, "json", SharingFixture.ALICE))));

		// An upload never lands under the name of a reference.
		FakeResponse upload = upload(TRIP, SharingFixture.ALICE, "a-2.jpg", photo("x"));
		assertEquals(upload.body(), HttpServletResponse.SC_OK, upload.status());
		assertEquals(4, outline(album(get(TRIP, "json", SharingFixture.ALICE))).size());
		assertTrue("The reference keeps its name.", PhotoCollections.referenceNamed(stored("Trip"), "a-2.jpg") != null);
	}

	public void testAPhotoTheAlbumHoldsIsNotReferencedByIt() throws Exception {
		MoveResult result = moveResult(collect(TRIP, "Trip", SharingFixture.ALICE, "a.jpg"));
		assertEquals(PhotoCollections.alreadyCollected("a.jpg"), outcome(result, "a.jpg").getMessage());
		collect(OTHER, "Trip", SharingFixture.ALICE, "b.jpg");
		MoveResult again = moveResult(collect(OTHER, "Trip", SharingFixture.ALICE, "b.jpg"));
		assertEquals(PhotoCollections.alreadyCollected("b.jpg"), outcome(again, "b.jpg").getMessage());
		assertEquals(Arrays.asList("a.jpg", "b.jpg", "c.jpg"), outline(album(get(TRIP, "json", SharingFixture.ALICE))));
	}

	public void testAReferenceFollowsAMovedOriginal() throws Exception {
		collect(OTHER, "Trip", SharingFixture.ALICE, "b.jpg");
		assertEquals(HttpServletResponse.SC_OK, move(OTHER, SharingFixture.PUBLIC, SharingFixture.ALICE, "b.jpg").status());

		ImagePart b = image(album(get(TRIP, "json", SharingFixture.ALICE)), "b.jpg");
		assertEquals(SharingFixture.PUBLIC + "/b.jpg", b.getRef().getPath());
		assertEquals("Beach", b.getComment());
		assertEquals(HttpServletResponse.SC_OK, get(TRIP + "b.jpg", "tn", SharingFixture.ALICE).status());
	}

	public void testAGoneOriginalIsShownAsMissing() throws Exception {
		collect(OTHER, "Trip", SharingFixture.ALICE, "b.jpg");
		// Deleting it through the server keeps it in Trip, see TestReferencedOriginalDelete; gone
		// behind the server's back, it is missing.
		Files.delete(_base.resolve("Other/b.jpg"));
		restartServer();
		servlet().index().indexNow();

		FakeResponse album = get(TRIP, "json", SharingFixture.ALICE);
		assertEquals("The album opens, never an error page.", HttpServletResponse.SC_OK, album.status());
		AlbumInfo answer = album(album);
		assertEquals(Arrays.asList("a.jpg", "b.jpg", "c.jpg"), outline(answer));
		assertTrue(image(answer, "b.jpg").isMissing());
		FakeResponse tile = get(TRIP + "b.jpg", "tn", SharingFixture.ALICE);
		assertEquals(HttpServletResponse.SC_NOT_FOUND, tile.status());
		assertEquals(PhotoCollections.MISSING, errorMessage(tile));
		assertEquals("A missing part is its editors' business only.", Arrays.asList("a.jpg", "c.jpg"),
			outline(album(get(TRIP, "json", SharingFixture.DAVE))));
	}

	/**
	 * A listing never resolves references for a count (issue #286): the tile of an album holding
	 * some says so ({@link FolderKind#COLLECTION}) and carries none, and the album answers its count
	 * when asked, as opening it would show it. Its picture is one of its own files where it chose
	 * none.
	 */
	public void testTheListingAsksAnAlbumWithReferencesForItsCount() throws Exception {
		collect(ZOO, "Trip", SharingFixture.ALICE, "public.jpg", "private.jpg");

		de.haumacher.imageServer.shared.model.FolderInfo tile = entry(listing(get("/", "json", SharingFixture.ALICE)), "Trip");
		assertEquals(FolderKind.COLLECTION, tile.getKind());
		assertEquals(0, tile.getImageCount());
		assertNotNull(tile.getIndexPicture());
		assertTrue("A file of its own: " + tile.getIndexPicture().getImage(),
			Arrays.asList("a.jpg", "c.jpg").contains(tile.getIndexPicture().getImage()));

		assertEquals(4, imageCount(TRIP, SharingFixture.ALICE));
		assertEquals("A private photograph is not counted for Carol.", 3, imageCount(TRIP, SharingFixture.CAROL));
	}

	private int imageCount(String path, String token) throws Exception {
		FakeResponse response = get(path, ImageServlet.IMAGE_COUNT_TYPE, token);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		return de.haumacher.imageServer.shared.model.ImageCount.readImageCount(reader(response.body())).getCount();
	}

	// --- The stored form. ---

	/**
	 * The fixture is a collection as the build before issue #307 wrote it: it reads and answers
	 * unchanged, and written back as it was answered it stays what it was.
	 */
	public void testAnOlderCollectionReadsAndRoundTrips() throws Exception {
		fixtureSpace();
		AlbumInfo before = readFixture();

		AlbumInfo answer = album(get("/Best of/", "json", SharingFixture.ALICE));
		assertEquals("Answered as before.", AlbumKind.COLLECTION, answer.getKind());
		assertEquals(Arrays.asList("# Karlsruhe", "P3031375.JPG", "## Flowers", "IMG_0415.JPG", "gone.jpg"),
			outline(answer));
		assertEquals(FolderKind.COLLECTION, entry(listing(get("/", "json", SharingFixture.ALICE)), "Best of").getKind());

		assertEquals(HttpServletResponse.SC_OK, put("/Best of/", write(answer), SharingFixture.ALICE).status());

		AlbumInfo after = stored("Best of");
		assertEquals("Stored as a collection, for an older build.", AlbumKind.COLLECTION, after.getKind());
		assertEquals(outline(before), outline(after));
		assertEquals(before.getIndexPicture().getImage(), after.getIndexPicture().getImage());
		for (ImagePart reference : PhotoCollections.references(before)) {
			ImagePart written = image(after, reference.getName());
			assertEquals(reference.getRef().getHash(), written.getRef().getHash());
			assertEquals(labels(reference), labels(written));
			assertEquals("Nothing of the photograph is stored.", 0, written.getWidth());
			assertEquals("", written.getComment());
		}
		assertEquals("A stale hint is refreshed, see PhotoRef.", "Schlosspark/P3031375.JPG",
			image(after, "P3031375.JPG").getRef().getPath());
		assertEquals("A gone photograph keeps its reference.", "Blumen/gone.jpg", image(after, "gone.jpg").getRef().getPath());
		assertEquals("Read again, the same answer.", outline(answer),
			outline(album(get("/Best of/", "json", SharingFixture.ALICE))));
	}

	/**
	 * What a build before issue #307 makes of the stored form, argued from its reader (the
	 * experiment of the issue run on that build): it reads a {@link AlbumKind#COLLECTION} with its
	 * references and nothing else, and an {@link AlbumKind#ALBUM} without its references, which its
	 * next write drops. So an album holding references and no file is stored as a collection, and
	 * one holding files as an album, whatever the client says.
	 */
	public void testTheStoredKindIsWhatAnOlderBuildReadsBest() throws Exception {
		assertEquals(HttpServletResponse.SC_OK,
			put("/Best", "[\"AlbumInfo\",{\"kind\":\"ALBUM\",\"title\":\"Best\"}]", SharingFixture.ALICE).status());
		collect(OTHER, "Best", SharingFixture.ALICE, "b.jpg");
		AlbumInfo pure = stored("Best");
		assertEquals("References and no file: a collection, which an older build reads.", AlbumKind.COLLECTION,
			pure.getKind());
		ImagePart stub = (ImagePart) pure.getParts().get(0);
		assertEquals("Its stored form is the one an older build wrote.", "b.jpg", stub.getName());
		assertEquals(64, stub.getRef().getHash().length());
		assertEquals(0, stub.getWidth());

		AlbumInfo answer = album(get("/Best/", "json", SharingFixture.ALICE));
		answer.setKind(AlbumKind.ALBUM);
		assertEquals(HttpServletResponse.SC_OK, put("/Best/", write(answer), SharingFixture.ALICE).status());
		assertEquals("A client does not say it.", AlbumKind.COLLECTION, stored("Best").getKind());

		collect(OTHER, "Trip", SharingFixture.ALICE, "b.jpg");
		assertEquals("Files and references: an album.", AlbumKind.ALBUM, stored("Trip").getKind());
		AlbumInfo trip = album(get(TRIP, "json", SharingFixture.ALICE));
		trip.setKind(AlbumKind.COLLECTION);
		assertEquals(HttpServletResponse.SC_OK, put(TRIP, write(trip), SharingFixture.ALICE).status());
		assertEquals(AlbumKind.ALBUM, stored("Trip").getKind());
	}

	// --- Sharing. ---

	public void testALinkShowsTheReferencesWithinItsLimits() throws Exception {
		collect(ZOO, "Trip", SharingFixture.ALICE, "public.jpg", "members.jpg", "private.jpg");
		assertEquals(HttpServletResponse.SC_OK, upload("/Inbox/", SharingFixture.CAROL, "new.jpg", photo("new")).status());
		collect("/Inbox/", "Trip", SharingFixture.CAROL, "new.jpg");
		// The zoo's photographs tell no time and stand first; the new one was taken just now.
		assertEquals(Arrays.asList("public.jpg", "members.jpg", "private.jpg", "a.jpg", "c.jpg", "new.jpg"),
			outline(album(get(TRIP, "json", SharingFixture.ALICE))));

		// The link's limits are its maker's (issue #282): members, never private, never an inbox.
		String token = issue("alice", "Trip", "Grandma", "", Privacy.MEMBERS, Ratings.MIN, Rights.VIEW, Rights.DOWNLOAD);
		AlbumInfo answer = album(get("/", "json", token));
		assertEquals(Arrays.asList("public.jpg", "members.jpg", "a.jpg", "c.jpg"), outline(answer));
		assertNull("Where a photograph lies is the members' business.", image(answer, "members.jpg").getRef());
		assertEquals(HttpServletResponse.SC_OK, get("/members.jpg", "tn", token).status());
		assertEquals(HttpServletResponse.SC_OK, get("/members.jpg", null, token).status());
		assertEquals(HttpServletResponse.SC_FORBIDDEN, get("/private.jpg", "tn", token).status());
		assertEquals(HttpServletResponse.SC_NOT_FOUND, get("/new.jpg", "tn", token).status());
		assertEquals("The link reaches nothing outside its album.", HttpServletResponse.SC_NOT_FOUND,
			get("/../" + SharingFixture.ZOO + "/public.jpg", "tn", token).status());

		// A visitor's upload lands as a file of the album.
		String contribute = issue("alice", "Trip", "Party", "", Privacy.MEMBERS, Ratings.MIN, Rights.VIEW,
			Rights.CONTRIBUTE);
		FakeResponse uploaded = upload("/", contribute, "visit.jpg", photo("visit"));
		assertEquals(uploaded.body(), HttpServletResponse.SC_OK, uploaded.status());
		assertTrue(Files.exists(_base.resolve("Trip").resolve("visit.jpg")));
	}

	// --- The album's own description. ---

	public void testAReferenceHasItsOwnDescription() throws Exception {
		collect(OTHER, "Trip", SharingFixture.ALICE, "b.jpg");
		AlbumInfo answer = album(get(TRIP, "json", SharingFixture.CAROL));
		assertEquals("Without one of its own, the original's.", "Beach", image(answer, "b.jpg").getComment());

		image(answer, "b.jpg").setComment("Where we swam").setRating(2);
		assertEquals(HttpServletResponse.SC_OK, put(TRIP, write(answer), SharingFixture.CAROL).status());

		assertEquals("Where we swam", image(album(get(TRIP, "json", SharingFixture.CAROL)), "b.jpg").getComment());
		ImagePart original = image(stored("Other"), "b.jpg");
		assertEquals("The original keeps its own.", "Beach", original.getComment());
		assertEquals("A rating is the photograph's own, written where it lies.", 2, original.getRating());
		assertEquals("Where we swam", image(stored("Trip"), "b.jpg").getComment());
		assertEquals(0, image(stored("Trip"), "b.jpg").getRating());

		// The photograph described as one: its description, its name, its labels in the album.
		FakeResponse single = get(TRIP + "b.jpg", "json", SharingFixture.CAROL);
		assertEquals(single.body(), HttpServletResponse.SC_OK, single.status());
		ImagePart described = (ImagePart) Resource.readResource(reader(single.body()));
		assertEquals("b.jpg", described.getName());
		assertEquals("Where we swam", described.getComment());

		// An album referencing it without a description of its own shows the original's.
		createAlbum("Second");
		collect(OTHER, "Second", SharingFixture.ALICE, "b.jpg");
		assertEquals("Beach", image(album(get("/Second/", "json", SharingFixture.ALICE)), "b.jpg").getComment());

		// Taking the own description away shows the original's again.
		AlbumInfo again = album(get(TRIP, "json", SharingFixture.CAROL));
		image(again, "b.jpg").setComment("");
		assertEquals(HttpServletResponse.SC_OK, put(TRIP, write(again), SharingFixture.CAROL).status());
		assertEquals("Beach", image(album(get(TRIP, "json", SharingFixture.CAROL)), "b.jpg").getComment());
		assertEquals("", image(stored("Trip"), "b.jpg").getComment());
	}

	public void testAPutKeepsTheReferencesAndDescriptionsItWasNotShown() throws Exception {
		collect(ZOO, "Trip", SharingFixture.ALICE, "public.jpg", "private.jpg");
		AlbumInfo alice = album(get(TRIP, "json", SharingFixture.ALICE));
		image(alice, "private.jpg").setComment("Secret");
		image(alice, "public.jpg").setComment("Open").setLabels(new ArrayList<>(Arrays.asList(LabelName.create().setName("Best"))));
		assertEquals(HttpServletResponse.SC_OK, put(TRIP, write(alice), SharingFixture.ALICE).status());

		// Carol may not see the private photograph and writes the album back as she was answered it.
		AlbumInfo carol = album(get(TRIP, "json", SharingFixture.CAROL));
		assertEquals(Arrays.asList("public.jpg", "a.jpg", "c.jpg"), outline(carol));
		carol.setTitle("Our trip");
		assertEquals(HttpServletResponse.SC_OK, put(TRIP, write(carol), SharingFixture.CAROL).status());

		AlbumInfo stored = stored("Our trip");
		assertEquals(Arrays.asList("public.jpg", "private.jpg", "a.jpg", "c.jpg"), outline(stored));
		assertEquals("Secret", image(stored, "private.jpg").getComment());
		assertEquals("Open", image(stored, "public.jpg").getComment());
		assertEquals(Arrays.asList("Best"), labels(image(stored, "public.jpg")));

		// A link answered no labels writes none away; a part naming no reference adds none.
		String token = issue("alice", "Our trip", "Grandma", "", Privacy.MEMBERS, Ratings.MIN, Rights.EDIT);
		AlbumInfo link = album(get("/", "json", token));
		assertEquals(Collections.emptyList(), labels(image(link, "public.jpg")));
		link.addPart(ImagePart.create().setName("smuggled.jpg")
			.setRef(PhotoCollections.references(stored).get(1).getRef()));
		assertEquals(HttpServletResponse.SC_OK, put("/", write(link), token).status());
		AlbumInfo again = stored("Our trip");
		assertEquals(Arrays.asList("Best"), labels(image(again, "public.jpg")));
		assertNull(Crops.findImage(again, "smuggled.jpg"));
		assertEquals("Open", image(again, "public.jpg").getComment());
	}

	public void testAReferenceSentInAGroupStandsAfterIt() throws Exception {
		collect(OTHER, "Trip", SharingFixture.ALICE, "b.jpg");
		AlbumInfo answer = album(get(TRIP, "json", SharingFixture.ALICE));
		ImagePart a = image(answer, "a.jpg");
		ImagePart b = image(answer, "b.jpg");
		ImagePart c = image(answer, "c.jpg");
		answer.setParts(new ArrayList<>(Arrays.asList(
			ImageGroup.create().setImages(new ArrayList<>(Arrays.asList(a, b, c))).setRepresentative(0))));
		assertEquals(HttpServletResponse.SC_OK, put(TRIP, write(answer), SharingFixture.ALICE).status());

		AlbumInfo stored = stored("Trip");
		assertEquals(2, stored.getParts().size());
		assertTrue(stored.getParts().get(0) instanceof ImageGroup);
		assertEquals("b.jpg", ((ImagePart) stored.getParts().get(1)).getName());
	}

	// --- Downloads. ---

	public void testTheZipHoldsTheReferencedOriginals() throws Exception {
		SharingFixture.album(_base, "More", "More",
			"[\"ImagePart\",{\"name\":\"a.jpg\",\"width\":4,\"height\":3,\"date\":1500}]", "a.jpg");
		restartServer();
		collect(OTHER, "Trip", SharingFixture.ALICE, "b.jpg");
		collect("/More/", "Trip", SharingFixture.ALICE, "a.jpg");
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", ImageServlet.ZIP_ACTION);

		FakeResponse response = post(TRIP,
			"{\"names\":[{\"name\":\"a.jpg\"},{\"name\":\"a-2.jpg\"},{\"name\":\"b.jpg\"},{\"name\":\"c.jpg\"}]}",
			SharingFixture.DAVE, parameters);

		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		Map<String, byte[]> entries = new LinkedHashMap<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(response.bodyBytes()))) {
			for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
				entries.put(entry.getName(), zip.readAllBytes());
			}
		}
		assertEquals("As if the photographs lay in the album, each name once.",
			Arrays.asList("a.jpg", "a-2.jpg", "b.jpg", "c.jpg"), new ArrayList<>(entries.keySet()));
		assertTrue(Arrays.equals(Files.readAllBytes(_base.resolve("Trip/a.jpg")), entries.get("a.jpg")));
		assertTrue(Arrays.equals(Files.readAllBytes(_base.resolve("More/a.jpg")), entries.get("a-2.jpg")));
		assertTrue(Arrays.equals(Files.readAllBytes(_base.resolve("Other/b.jpg")), entries.get("b.jpg")));
	}

	// --- No copy. ---

	public void testAReferenceIsNoDuplicateAndIsFoundOnce() throws Exception {
		collect(OTHER, "Trip", SharingFixture.ALICE, "b.jpg");
		collect(OTHER, "/" + SharingFixture.PUBLIC, SharingFixture.ALICE, "b.jpg");
		servlet().index().indexNow();

		FakeResponse duplicates = get("/", ImageServlet.DUPLICATES_TYPE, SharingFixture.ALICE);
		assertEquals("References are not copies.", Collections.emptyList(),
			DuplicateList.readDuplicateList(reader(body(duplicates))).getGroups());

		SearchQuery query = SearchQuery.create().setVersion(1)
			.setRoot(SearchAnd.create().setCriteria(new ArrayList<>(Arrays.asList(SearchText.create().setText("Beach")))));
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", ImageServlet.SEARCH_ACTION);
		FakeResponse found = post("/", write(query), SharingFixture.ALICE, parameters);
		assertEquals(found.body(), HttpServletResponse.SC_OK, found.status());
		assertEquals("Found once, where it lies.", Arrays.asList("Other/b.jpg"), outline(album(found)));
	}

	// --- Moving and removing. ---

	public void testMovingAReferenceMovesTheReference() throws Exception {
		collect(OTHER, "Trip", SharingFixture.ALICE, "b.jpg");
		AlbumInfo trip = album(get(TRIP, "json", SharingFixture.ALICE));
		image(trip, "b.jpg").setComment("Swim");
		assertEquals(HttpServletResponse.SC_OK, put(TRIP, write(trip), SharingFixture.ALICE).status());
		String other = fingerprint(_base.resolve("Other"));

		MoveResult result = moveResult(move(TRIP, SharingFixture.PUBLIC, SharingFixture.ALICE, "b.jpg", "c.jpg"));

		assertEquals("b.jpg", outcome(result, "b.jpg").getNewName());
		assertEquals("c.jpg", outcome(result, "c.jpg").getNewName());
		assertEquals(Arrays.asList("a.jpg"), outline(album(get(TRIP, "json", SharingFixture.ALICE))));
		ImagePart moved = image(album(get("/" + SharingFixture.PUBLIC + "/", "json", SharingFixture.ALICE)), "b.jpg");
		assertEquals("Other/b.jpg", moved.getRef().getPath());
		assertEquals("The album's own description moves along.", "Swim", moved.getComment());
		assertTrue("The file moved.", Files.exists(_base.resolve(SharingFixture.PUBLIC).resolve("c.jpg")));
		assertEquals("The original is untouched.", other, fingerprint(_base.resolve("Other")));

		FakeResponse inbox = move("/" + SharingFixture.PUBLIC + "/", "Inbox", SharingFixture.ALICE, "b.jpg");
		assertEquals(inbox.body(), HttpServletResponse.SC_OK, inbox.status());
		assertEquals(PhotoCollections.MOVE_REFUSED, outcome(moveResult(inbox), "b.jpg").getMessage());
	}

	public void testDeletingAReferenceRemovesTheReferenceAlone() throws Exception {
		collect(OTHER, "Trip", SharingFixture.ALICE, "b.jpg");
		String other = fingerprint(_base.resolve("Other"));
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "delete");

		FakeResponse response = post(TRIP, "{\"names\":[{\"name\":\"b.jpg\"},{\"name\":\"c.jpg\"}]}",
			SharingFixture.ALICE, parameters);

		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		MoveResult result = moveResult(response);
		assertEquals(PhotoCollections.REMOVED, outcome(result, "b.jpg").getMessage());
		assertEquals(Arrays.asList("a.jpg"), outline(album(get(TRIP, "json", SharingFixture.ALICE))));
		assertEquals(other, fingerprint(_base.resolve("Other")));
		assertTrue(Files.exists(_base.resolve("Other/b.jpg")));
		assertFalse("The own file went into the trash.", Files.exists(_base.resolve("Trip/c.jpg")));
	}

	// --- Helpers. ---

	private void fixtureSpace() throws Exception {
		Path source = TEST_ALBUM.toPath();
		Files.createDirectories(_base.resolve("Schlosspark"));
		Files.write(_base.resolve("Schlosspark/P3031375.JPG"),
			Files.readAllBytes(source.resolve("2002-03-03 Schlosspark Karlsruhe/P3031375.JPG")));
		Files.createDirectories(_base.resolve("Blumen"));
		Files.write(_base.resolve("Blumen/IMG_0415.JPG"),
			Files.readAllBytes(source.resolve("2005-08-24 Blumen und Fliegen/IMG_0415.JPG")));
		Files.createDirectories(_base.resolve("Best of"));
		Files.copy(FIXTURE.toPath(), _base.resolve("Best of").resolve("index.json"), StandardCopyOption.REPLACE_EXISTING);
		servlet().index().indexNow();
	}

	private static AlbumInfo readFixture() throws Exception {
		return (AlbumInfo) Resource.readResource(
			reader(new String(Files.readAllBytes(FIXTURE.toPath()), StandardCharsets.UTF_8)));
	}

	private void createAlbum(String name) throws Exception {
		FakeResponse response = put("/" + name, "[\"AlbumInfo\",{\"title\":\"" + name + "\"}]", SharingFixture.ALICE);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
	}

	private FakeResponse collect(String pathInfo, String target, String token, String... names) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", ImageServlet.COLLECT_ACTION);
		StringBuilder body = new StringBuilder("{\"target\":\"").append(target.replaceAll("^/+|/+$", ""))
			.append("\",\"names\":[");
		for (int n = 0; n < names.length; n++) {
			body.append(n == 0 ? "" : ",").append("{\"name\":\"").append(names[n]).append("\"}");
		}
		FakeResponse response = post(pathInfo, body.append("]}").toString(), token, parameters);
		return response;
	}

	private AlbumInfo stored(String folder) throws Exception {
		return (AlbumInfo) Resource.readResource(reader(new String(
			Files.readAllBytes(_base.resolve(folder).resolve("index.json")), StandardCharsets.UTF_8)));
	}

	private static List<String> outline(AlbumInfo album) {
		List<String> result = new ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof Heading) {
				Heading heading = (Heading) part;
				result.add((heading.getLevel() == 2 ? "## " : "# ") + heading.getText());
			} else if (part instanceof ImagePart) {
				result.add(((ImagePart) part).getName());
			} else if (part instanceof ImageGroup) {
				for (ImagePart member : ((ImageGroup) part).getImages()) {
					result.add(member.getName());
				}
			}
		}
		return result;
	}

	private static ImagePart image(AlbumInfo album, String name) {
		ImagePart result = Crops.findImage(album, name);
		assertNotNull("No part '" + name + "' in " + outline(album), result);
		return result;
	}

	private static List<String> labels(ImagePart image) {
		List<String> result = new ArrayList<>();
		for (LabelName label : image.getLabels()) {
			result.add(label.getName());
		}
		return result;
	}

	private static String write(Object object) throws Exception {
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

	/** A tiny JPEG with contents of its own. */
	private static byte[] photo(String seed) throws Exception {
		java.util.Random random = new java.util.Random(("references/" + seed).hashCode());
		java.awt.image.BufferedImage image =
			new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_3BYTE_BGR);
		for (int x = 0; x < 16; x++) {
			for (int y = 0; y < 16; y++) {
				image.setRGB(x, y, random.nextInt(0xFFFFFF));
			}
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		javax.imageio.ImageIO.write(image, "jpg", out);
		return out.toByteArray();
	}
}
