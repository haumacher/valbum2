/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.auth.Ratings;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.auth.ShareStore;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumKind;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.DuplicateList;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.FolderKind;
import de.haumacher.imageServer.shared.model.Heading;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.LabelName;
import de.haumacher.imageServer.shared.model.MoveResult;
import de.haumacher.imageServer.shared.model.Orientation;
import de.haumacher.imageServer.shared.model.Resource;
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
 * Test case for the collections of issue #221: albums of references to photographs of other
 * albums.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestCollections extends ShareTestCase {

	private static final File FIXTURE = new File("src/test/fixtures/collection/index.json");

	private static final File TEST_ALBUM = new File("src/test/fixtures/test-album");

	private static final String BEST = "/Best of/";

	private static final String ZOO = "/" + SharingFixture.ZOO + "/";

	private static final String PUBLIC = "/" + SharingFixture.PUBLIC + "/";

	// --- The stored form. ---

	public void testTheStoredFormIsReadAndResolved() throws Exception {
		fixtureSpace();

		AlbumInfo answer = album(get(BEST, "json", SharingFixture.ALICE));

		assertEquals(AlbumKind.COLLECTION, answer.getKind());
		assertEquals(Arrays.asList("# Karlsruhe", "P3031375.JPG", "## Flowers", "IMG_0415.JPG", "gone.jpg"),
			outline(answer));
		ImagePart karlsruhe = image(answer, "P3031375.JPG");
		assertEquals("A stale hint is found again by the hash.", "Schlosspark/P3031375.JPG",
			karlsruhe.getRef().getPath());
		assertTrue("The photograph is described as its own album describes it.", karlsruhe.getWidth() > 0);
		assertEquals("The labels are the collection's own.", Arrays.asList("Favourites"), labels(karlsruhe));
		assertEquals("Blumen/IMG_0415.JPG", image(answer, "IMG_0415.JPG").getRef().getPath());
		assertTrue("Contents the space no longer holds are missing.", image(answer, "gone.jpg").isMissing());
		assertEquals("IMG_0415.JPG", answer.getIndexPicture().getImage());

		AlbumInfo viewer = album(get(BEST, "json", SharingFixture.DAVE));
		assertEquals("A missing part is its editors' business only.",
			Arrays.asList("# Karlsruhe", "P3031375.JPG", "## Flowers", "IMG_0415.JPG"), outline(viewer));
	}

	public void testAPhotoOfACollectionIsServedFromItsAlbum() throws Exception {
		fixtureSpace();

		FakeResponse original = get(BEST + "P3031375.JPG", null, SharingFixture.ALICE);
		assertEquals(original.body(), HttpServletResponse.SC_OK, original.status());
		assertTrue("The original's bytes.", Arrays.equals(Files.readAllBytes(_base.resolve("Schlosspark/P3031375.JPG")),
			original.bodyBytes()));
		assertEquals(HttpServletResponse.SC_OK, get(BEST + "IMG_0415.JPG", "tn", SharingFixture.DAVE).status());

		FakeResponse gone = get(BEST + "gone.jpg", "tn", SharingFixture.ALICE);
		assertEquals(HttpServletResponse.SC_NOT_FOUND, gone.status());
		assertEquals(PhotoCollections.MISSING, errorMessage(gone));
	}

	public void testTheServerWritesNothingButReferences() throws Exception {
		createCollection("Best of");
		MoveResult result = moveResult(collect(ZOO, "/Best of", SharingFixture.ALICE, "public.jpg", "members.jpg"));
		assertEquals("public.jpg", outcome(result, "public.jpg").getNewName());
		assertEquals("", outcome(result, "public.jpg").getMessage());

		AlbumInfo stored = stored("Best of");
		assertEquals(AlbumKind.COLLECTION, stored.getKind());
		ImagePart reference = (ImagePart) stored.getParts().get(0);
		assertEquals("public.jpg", reference.getName());
		assertEquals(64, reference.getRef().getHash().length());
		assertEquals(SharingFixture.ZOO + "/public.jpg", reference.getRef().getPath());
		assertEquals("Nothing of the photograph is copied.", 0, reference.getWidth());
		assertEquals("Covered by what arrived (#153).", "public.jpg", stored.getIndexPicture().getImage());
		for (String file : files(_base.resolve("Best of"))) {
			assertTrue("Nothing but the sidecar (and its backups) is in the folder: " + file,
				file.startsWith("index.json"));
		}
	}

	// --- Adding and removing. ---

	public void testAddingTwiceAndTwoFilesOfOneName() throws Exception {
		createCollection("Best of");
		write("Other", "public.jpg", photo("other"));
		collect(ZOO, "/Best of", SharingFixture.CAROL, "public.jpg");

		MoveResult again = moveResult(collect(ZOO, "/Best of", SharingFixture.CAROL, "public.jpg"));
		assertEquals(PhotoCollections.alreadyCollected("public.jpg"), outcome(again, "public.jpg").getMessage());
		MoveResult other = moveResult(collect("/Other/", "/Best of", SharingFixture.CAROL, "public.jpg"));
		assertEquals("A name the collection holds is numbered.", "public-2.jpg",
			outcome(other, "public.jpg").getNewName());

		assertEquals(Arrays.asList("public.jpg", "public-2.jpg"), outline(album(get(BEST, "json", SharingFixture.ALICE))));
	}

	public void testAddingFromACollectionCopiesTheReference() throws Exception {
		createCollection("Best of");
		createCollection("Second");
		collect(ZOO, "/Best of", SharingFixture.ALICE, "public.jpg");

		MoveResult result = moveResult(collect(BEST, "/Second", SharingFixture.ALICE, "public.jpg"));

		assertEquals("public.jpg", outcome(result, "public.jpg").getNewName());
		assertEquals(stored("Best of").getParts().get(0) instanceof ImagePart
			? ((ImagePart) stored("Best of").getParts().get(0)).getRef().getHash() : null,
			((ImagePart) stored("Second").getParts().get(0)).getRef().getHash());
	}

	public void testAHiddenPhotoIsNotAdded() throws Exception {
		createCollection("Best of");
		MoveResult result = moveResult(collect(ZOO, "/Best of", SharingFixture.CAROL, "private.jpg"));
		assertEquals("Carol may not see the private photograph.", ImageServlet.notInAlbum("private.jpg"),
			outcome(result, "private.jpg").getMessage());
		assertEquals(Collections.emptyList(), stored("Best of").getParts());
	}

	public void testRemovingAReferenceNeverTouchesThePhoto() throws Exception {
		createCollection("Best of");
		collect(ZOO, "/Best of", SharingFixture.ALICE, "public.jpg", "members.jpg");
		String zoo = fingerprint(_base.resolve(SharingFixture.ZOO));

		MoveResult result = moveResult(delete(BEST, SharingFixture.CAROL, "public.jpg"));

		assertEquals(PhotoCollections.REMOVED, outcome(result, "public.jpg").getMessage());
		assertEquals(Arrays.asList("members.jpg"), outline(album(get(BEST, "json", SharingFixture.ALICE))));
		assertEquals("The photograph is where it was, untouched.", zoo, fingerprint(_base.resolve(SharingFixture.ZOO)));
		assertEquals("A viewer may not remove anything.", HttpServletResponse.SC_FORBIDDEN,
			delete(BEST, SharingFixture.DAVE, "members.jpg").status());
	}

	public void testAPutLeavingOutAReferenceRemovesIt() throws Exception {
		createCollection("Best of");
		collect(ZOO, "/Best of", SharingFixture.ALICE, "public.jpg", "members.jpg");
		AlbumInfo answer = album(get(BEST, "json", SharingFixture.ALICE));
		answer.getParts().remove(0);

		assertEquals(HttpServletResponse.SC_OK, put(BEST, write(answer), SharingFixture.ALICE).status());

		assertEquals(Arrays.asList("members.jpg"), outline(album(get(BEST, "json", SharingFixture.ALICE))));
	}

	public void testDeletingTheCollectionRemovesItAndNoPhoto() throws Exception {
		createCollection("Best of");
		collect(ZOO, "/Best of", SharingFixture.ALICE, "public.jpg");
		String zoo = fingerprint(_base.resolve(SharingFixture.ZOO));

		MoveResult result = moveResult(delete("/", SharingFixture.ALICE, "Best of"));

		assertEquals(DeleteService.REMOVED, outcome(result, "Best of").getMessage());
		assertFalse(Files.exists(_base.resolve("Best of")));
		assertEquals(zoo, fingerprint(_base.resolve(SharingFixture.ZOO)));
	}

	// --- Resolution. ---

	public void testAReferenceFollowsItsPhotoThroughAMoveAndARename() throws Exception {
		createCollection("Best of");
		collect(ZOO, "/Best of", SharingFixture.ALICE, "public.jpg");

		assertEquals(HttpServletResponse.SC_OK,
			move(ZOO, SharingFixture.PUBLIC, SharingFixture.ALICE, "public.jpg").status());
		assertEquals(SharingFixture.PUBLIC + "/public.jpg", refPath(SharingFixture.ALICE, "public.jpg"));

		AlbumInfo album = album(get(PUBLIC, "json", SharingFixture.ALICE));
		album.setTitle("Renamed");
		assertEquals(HttpServletResponse.SC_OK, put(PUBLIC, write(album), SharingFixture.ALICE).status());
		assertTrue(Files.isDirectory(_base.resolve("Renamed")));

		assertEquals("Renamed/public.jpg", refPath(SharingFixture.ALICE, "public.jpg"));
		assertEquals(HttpServletResponse.SC_OK, get(BEST + "public.jpg", "tn", SharingFixture.ALICE).status());
	}

	public void testAPurgedPhotoIsMissingAndRemovable() throws Exception {
		createCollection("Best of");
		collect(ZOO, "/Best of", SharingFixture.ALICE, "public.jpg", "members.jpg");
		AlbumInfo zoo = album(get(ZOO, "json", SharingFixture.ALICE));
		image(zoo, "public.jpg").setRating(Ratings.TRASH);
		assertEquals(HttpServletResponse.SC_OK, put(ZOO, write(zoo), SharingFixture.ALICE).status());
		Map<String, String> purge = new HashMap<>();
		purge.put("action", "purge");
		assertEquals(HttpServletResponse.SC_OK, post(ZOO, "", SharingFixture.ALICE, purge).status());

		AlbumInfo answer = album(get(BEST, "json", SharingFixture.ALICE));
		assertTrue(image(answer, "public.jpg").isMissing());
		assertEquals(HttpServletResponse.SC_NOT_FOUND, get(BEST + "public.jpg", "tn", SharingFixture.ALICE).status());

		assertEquals(PhotoCollections.REMOVED,
			outcome(moveResult(delete(BEST, SharingFixture.ALICE, "public.jpg")), "public.jpg").getMessage());
		assertEquals(Arrays.asList("members.jpg"), outline(album(get(BEST, "json", SharingFixture.ALICE))));
	}

	// --- Visibility. ---

	public void testEveryCallerSeesWhatTheyWouldSeeInTheAlbum() throws Exception {
		createCollection("Best of");
		collect(ZOO, "/Best of", SharingFixture.ALICE, "public.jpg", "members.jpg", "private.jpg");

		assertEquals(Arrays.asList("public.jpg", "members.jpg", "private.jpg"),
			outline(album(get(BEST, "json", SharingFixture.ALICE))));
		assertEquals(Arrays.asList("public.jpg", "members.jpg"),
			outline(album(get(BEST, "json", SharingFixture.CAROL))));
		assertEquals(Arrays.asList("public.jpg", "members.jpg"),
			outline(album(get(BEST, "json", SharingFixture.DAVE))));
		assertEquals(Arrays.asList("public.jpg"), outline(album(get(BEST, "json", SharingFixture.EVE))));

		AlbumInfo anonymous = album(get(BEST, "json", null));
		assertEquals(Arrays.asList("public.jpg"), outline(anonymous));
		assertNull("Where a photograph lies is the members' business.", image(anonymous, "public.jpg").getRef());

		assertEquals(HttpServletResponse.SC_FORBIDDEN, get(BEST + "private.jpg", "tn", SharingFixture.CAROL).status());
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, get(BEST + "members.jpg", null, null).status());
	}

	public void testAPhotoOfTheInboxIsShownAsTheInboxShowsIt() throws Exception {
		createCollection("Best of");
		assertEquals(HttpServletResponse.SC_OK, upload("/Inbox/", SharingFixture.CAROL, "new.jpg", photo("new")).status());
		collect("/Inbox/", "/Best of", SharingFixture.CAROL, "new.jpg");

		assertEquals(Arrays.asList("new.jpg"), outline(album(get(BEST, "json", SharingFixture.CAROL))));
		assertEquals("A member who may only look sees no inbox.", Collections.emptyList(),
			outline(album(get(BEST, "json", SharingFixture.DAVE))));
		assertEquals(HttpServletResponse.SC_NOT_FOUND, get(BEST + "new.jpg", "tn", SharingFixture.DAVE).status());
	}

	public void testAPersonalViewAsIsAPreview() throws Exception {
		createCollection("Best of");
		collect(ZOO, "/Best of", SharingFixture.ALICE, "public.jpg", "members.jpg");
		AlbumInfo preview = album(get(BEST, "json", SharingFixture.ALICE, "public"));
		assertEquals(Arrays.asList("public.jpg"), outline(preview));
	}

	public void testALinkOnACollectionAppliesItsOwnFiltersOnTop() throws Exception {
		createCollection("Best of");
		collect(ZOO, "/Best of", SharingFixture.ALICE, "public.jpg", "members.jpg", "private.jpg");
		String token = issue("alice", "Best of", "Grandma", "", Privacy.MEMBERS, Ratings.MIN, Rights.VIEW);

		AlbumInfo answer = album(get("/", "json", token));
		assertEquals("A link never shows a private photograph.", Arrays.asList("public.jpg", "members.jpg"),
			outline(answer));
		assertNull(image(answer, "members.jpg").getRef());
		assertEquals(HttpServletResponse.SC_OK, get("/members.jpg", "tn", token).status());
		assertEquals(HttpServletResponse.SC_FORBIDDEN, get("/private.jpg", "tn", token).status());
		assertEquals("The link reaches nothing outside the collection.", HttpServletResponse.SC_NOT_FOUND,
			get(ZOO + "public.jpg", "tn", token).status());
		assertEquals(HttpServletResponse.SC_FORBIDDEN,
			collect("/", "/Best of", token, "public.jpg").status());
	}

	public void testALabeledLinkShowsTheCollectionsOwnLabel() throws Exception {
		createCollection("Best of");
		collect(ZOO, "/Best of", SharingFixture.ALICE, "public.jpg", "members.jpg");
		AlbumInfo answer = album(get(BEST, "json", SharingFixture.ALICE));
		image(answer, "members.jpg").setLabels(Arrays.asList(LabelName.create().setName("Grandma")));
		assertEquals(HttpServletResponse.SC_OK, put(BEST, write(answer), SharingFixture.ALICE).status());
		assertEquals("The label is the collection's, not the album's.", Collections.emptyList(),
			labels(image(album(get(ZOO, "json", SharingFixture.ALICE)), "members.jpg")));

		ShareStore shares = new ShareStore(_base);
		ShareStore.Issued issued = shares.create("alice", "Best of", "Grandma", "", Privacy.MEMBERS, Ratings.MIN,
			Arrays.asList(Rights.VIEW, Rights.DOWNLOAD));
		shares.setPhotoLabel(issued.getLink(), "Grandma");
		restartServer();
		String token = issued.getToken();

		assertEquals(Arrays.asList("members.jpg"), outline(album(get("/", "json", token))));
		assertEquals(HttpServletResponse.SC_OK, get("/members.jpg", "tn", token).status());
		FakeResponse other = get("/public.jpg", "tn", token);
		assertEquals(HttpServletResponse.SC_NOT_FOUND, other.status());
		assertEquals(Labels.NOT_FOUND, errorMessage(other));
	}

	public void testTheListingShowsACollectionWithAPictureItsCallerMaySee() throws Exception {
		createCollection("Best of");
		collect(ZOO, "/Best of", SharingFixture.ALICE, "members.jpg");

		FolderInfo forAlice = entry(listing(get("/", "json", SharingFixture.ALICE)), "Best of");
		assertEquals(FolderKind.COLLECTION, forAlice.getKind());
		assertEquals("members.jpg", forAlice.getIndexPicture().getImage());
		FolderInfo forEve = entry(listing(get("/", "json", SharingFixture.EVE)), "Best of");
		assertNull("A picture the caller may not see is no picture of the tile.", forEve.getIndexPicture());
	}

	// --- Edits through the collection. ---

	public void testEditsAreWrittenToThePhotographsAlbum() throws Exception {
		createCollection("Best of");
		collect(ZOO, "/Best of", SharingFixture.ALICE, "public.jpg", "members.jpg");
		AlbumInfo answer = album(get(BEST, "json", SharingFixture.CAROL));
		ImagePart turned = image(answer, "members.jpg");
		turned.setOrientation(Orientation.ROT_R).setRating(2).setComment("The lion").setPrivacy(0);
		// The order and a heading are the collection's own.
		List<AlbumPart> parts = new ArrayList<>(answer.getParts());
		Collections.reverse(parts);
		parts.add(0, Heading.create().setText("Animals").setLevel(1));
		answer.setParts(parts);
		String zooOrder = outline(album(get(ZOO, "json", SharingFixture.ALICE))).toString();

		assertEquals(HttpServletResponse.SC_OK, put(BEST, write(answer), SharingFixture.CAROL).status());

		ImagePart source = image(album(get(ZOO, "json", SharingFixture.ALICE)), "members.jpg");
		assertEquals("Seen in the album it lies in.", Orientation.ROT_R, source.getOrientation());
		assertEquals(2, source.getRating());
		assertEquals("The lion", source.getComment());
		assertEquals(0, source.getPrivacy());
		assertEquals("The album keeps its own order.", zooOrder,
			outline(album(get(ZOO, "json", SharingFixture.ALICE))).toString());
		assertEquals(Arrays.asList("# Animals", "members.jpg", "public.jpg"),
			outline(album(get(BEST, "json", SharingFixture.CAROL))));
		assertEquals("The collection stores no statement about a photograph.", Orientation.IDENTITY,
			((ImagePart) stored("Best of").getParts().get(1)).getOrientation());
	}

	public void testAnInvisibleReferenceSurvivesAWriteOfSomebodyWhoCannotSeeIt() throws Exception {
		createCollection("Best of");
		collect(ZOO, "/Best of", SharingFixture.ALICE, "public.jpg", "private.jpg", "members.jpg");
		AlbumInfo answer = album(get(BEST, "json", SharingFixture.CAROL));
		assertEquals(Arrays.asList("public.jpg", "members.jpg"), outline(answer));

		assertEquals(HttpServletResponse.SC_OK, put(BEST, write(answer), SharingFixture.CAROL).status());

		assertEquals(Arrays.asList("public.jpg", "private.jpg", "members.jpg"),
			outline(album(get(BEST, "json", SharingFixture.ALICE))));
	}

	public void testACropThroughTheCollectionCutsThePhotograph() throws Exception {
		createCollection("Best of");
		collect(ZOO, "/Best of", SharingFixture.ALICE, "public.jpg");
		ImagePart request = ImagePart.create().setName("public.jpg")
			.setCrop(de.haumacher.imageServer.shared.model.Crop.create().setX(0).setY(0).setW(0.5).setH(0.5));
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", ImageServlet.CROP_ACTION);

		FakeResponse response = post(BEST, write(request), SharingFixture.CAROL, parameters);

		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		assertEquals(AlbumKind.COLLECTION, album(response).getKind());
		assertNotNull(image(album(get(ZOO, "json", SharingFixture.ALICE)), "public.jpg").getCrop());
		assertNotNull(image(album(get(BEST, "json", SharingFixture.ALICE)), "public.jpg").getCrop());
	}

	public void testAnAlbumNeverBecomesACollection() throws Exception {
		AlbumInfo album = album(get(PUBLIC, "json", SharingFixture.ALICE));
		album.setKind(AlbumKind.COLLECTION);
		assertEquals(HttpServletResponse.SC_OK, put(PUBLIC, write(album), SharingFixture.ALICE).status());
		assertEquals(AlbumKind.ALBUM, album(get(PUBLIC, "json", SharingFixture.ALICE)).getKind());
		assertEquals(Arrays.asList("open.jpg"), outline(album(get(PUBLIC, "json", SharingFixture.ALICE))));
	}

	// --- Downloads. ---

	public void testTheZipOfACollectionHoldsTheOriginals() throws Exception {
		createCollection("Best of");
		write("Other", "public.jpg", photo("other"));
		collect(ZOO, "/Best of", SharingFixture.ALICE, "public.jpg");
		collect("/Other/", "/Best of", SharingFixture.ALICE, "public.jpg");
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", ImageServlet.ZIP_ACTION);

		FakeResponse response = post(BEST, "{\"names\":[{\"name\":\"public.jpg\"},{\"name\":\"public-2.jpg\"}]}",
			SharingFixture.DAVE, parameters);

		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		Map<String, byte[]> entries = new LinkedHashMap<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(response.bodyBytes()))) {
			for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
				entries.put(entry.getName(), zip.readAllBytes());
			}
		}
		assertEquals(Arrays.asList("public.jpg", "public-2.jpg"), new ArrayList<>(entries.keySet()));
		assertTrue(Arrays.equals(Files.readAllBytes(_base.resolve(SharingFixture.ZOO).resolve("public.jpg")),
			entries.get("public.jpg")));
		assertTrue(Arrays.equals(Files.readAllBytes(_base.resolve("Other/public.jpg")), entries.get("public-2.jpg")));
	}

	public void testTheZipRefusesWhatTheCallerMayNotSee() throws Exception {
		createCollection("Best of");
		collect(ZOO, "/Best of", SharingFixture.ALICE, "private.jpg");
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", ImageServlet.ZIP_ACTION);
		assertEquals(HttpServletResponse.SC_FORBIDDEN,
			post(BEST, "{\"names\":[{\"name\":\"private.jpg\"}]}", SharingFixture.DAVE, parameters).status());
	}

	public void testASignedAddressOfAPhotoOfACollectionOpensIt() throws Exception {
		createCollection("Best of");
		collect(ZOO, "/Best of", SharingFixture.ALICE, "public.jpg");
		Map<String, String> ask = new HashMap<>();
		ask.put("type", ImageServlet.MEDIA_URL_TYPE);
		ask.put("for", "original");
		FakeResponse issued = getWith(BEST + "public.jpg", SharingFixture.DAVE, ask);
		assertEquals(issued.body(), HttpServletResponse.SC_OK, issued.status());
		String media = de.haumacher.imageServer.shared.model.MediaUrl.readMediaUrl(reader(body(issued))).getMedia();

		Map<String, String> open = new HashMap<>();
		open.put("media", media);
		FakeResponse original = getWith(BEST + "public.jpg", null, open);
		assertEquals(original.body(), HttpServletResponse.SC_OK, original.status());
		assertTrue(Arrays.equals(Files.readAllBytes(_base.resolve(SharingFixture.ZOO).resolve("public.jpg")),
			original.bodyBytes()));
	}

	private FakeResponse getWith(String pathInfo, String token, Map<String, String> parameters) throws Exception {
		Map<String, String> headers = new HashMap<>();
		if (token != null) {
			headers.put("Authorization", "Bearer " + token);
		}
		FakeResponse response = new FakeResponse();
		servlet().doGet(TestImageServletPut.request(pathInfo, null, new byte[0], headers, parameters),
			response.response());
		return response;
	}

	// --- The duplicates. ---

	public void testAReferenceIsNoDuplicate() throws Exception {
		createCollection("Best of");
		createCollection("Second");
		collect(ZOO, "/Best of", SharingFixture.ALICE, "public.jpg");
		collect(ZOO, "/Second", SharingFixture.ALICE, "public.jpg");
		servlet().index().indexNow();

		FakeResponse response = get("/", ImageServlet.DUPLICATES_TYPE, SharingFixture.ALICE);
		assertEquals("References are not copies.", Collections.emptyList(),
			DuplicateList.readDuplicateList(reader(body(response))).getGroups());
	}

	// --- What a collection refuses. ---

	public void testACollectionTakesNoFiles() throws Exception {
		createCollection("Best of");
		FakeResponse upload = upload(BEST, SharingFixture.ALICE, "new.jpg", photo("new"));
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, upload.status());
		assertEquals(PhotoCollections.UPLOAD_REFUSED, errorMessage(upload));

		FakeResponse into = move(ZOO, "Best of", SharingFixture.ALICE, "public.jpg");
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, into.status());
		assertEquals(PhotoCollections.MOVE_REFUSED, errorMessage(into));

		collect(ZOO, "/Best of", SharingFixture.ALICE, "public.jpg");
		assertEquals(HttpServletResponse.SC_BAD_REQUEST,
			move(BEST, SharingFixture.PUBLIC, SharingFixture.ALICE, "public.jpg").status());
		assertTrue(Files.exists(_base.resolve(SharingFixture.ZOO).resolve("public.jpg")));

		FakeResponse folder = put("/Best of/Inner", "[\"AlbumInfo\",{\"title\":\"Inner\"}]", SharingFixture.ALICE);
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, folder.status());

		Map<String, String> tag = new HashMap<>();
		tag.put("action", "tag-faces");
		FakeResponse faces = post(BEST, "{\"faces\":[]}", SharingFixture.ALICE, tag);
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, faces.status());
		assertEquals(PhotoCollections.FACES_REFUSED, errorMessage(faces));
	}

	public void testOnlyACollectionIsCollectedInto() throws Exception {
		FakeResponse response = collect(ZOO, PUBLIC, SharingFixture.ALICE, "public.jpg");
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status());
		assertEquals(PhotoCollections.NOT_A_COLLECTION, errorMessage(response));
	}

	public void testCollectingNeedsTheEditRight() throws Exception {
		createCollection("Best of");
		assertEquals(HttpServletResponse.SC_FORBIDDEN, collect(ZOO, "/Best of", SharingFixture.DAVE, "public.jpg").status());
		assertEquals(HttpServletResponse.SC_FORBIDDEN, collect(ZOO, "/Best of", SharingFixture.BOB, "public.jpg").status());
		assertEquals(HttpServletResponse.SC_FORBIDDEN, createCollectionAs("Mine", SharingFixture.DAVE).status());
	}

	// --- Helpers. ---

	private void fixtureSpace() throws Exception {
		Path source = TEST_ALBUM.toPath();
		write("Schlosspark", "P3031375.JPG",
			Files.readAllBytes(source.resolve("2002-03-03 Schlosspark Karlsruhe/P3031375.JPG")));
		write("Blumen", "IMG_0415.JPG", Files.readAllBytes(source.resolve("2005-08-24 Blumen und Fliegen/IMG_0415.JPG")));
		Files.createDirectories(_base.resolve("Best of"));
		Files.copy(FIXTURE.toPath(), _base.resolve("Best of").resolve("index.json"), StandardCopyOption.REPLACE_EXISTING);
		servlet().index().indexNow();
	}

	private void createCollection(String name) throws Exception {
		FakeResponse response = createCollectionAs(name, SharingFixture.ALICE);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
	}

	private FakeResponse createCollectionAs(String name, String token) throws Exception {
		return put("/" + name, "[\"AlbumInfo\",{\"kind\":\"COLLECTION\",\"title\":\"" + name + "\"}]", token);
	}

	private FakeResponse collect(String pathInfo, String target, String token, String... names) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", ImageServlet.COLLECT_ACTION);
		// A target is spelled relative to the space, without the slashes of an address.
		return post(pathInfo, body(target.replaceAll("^/+|/+$", ""), names), token, parameters);
	}

	private FakeResponse delete(String pathInfo, String token, String... names) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "delete");
		return post(pathInfo, body("", names), token, parameters);
	}

	private static String body(String target, String... names) {
		StringBuilder body = new StringBuilder("{\"target\":\"").append(target).append("\",\"names\":[");
		for (int n = 0; n < names.length; n++) {
			body.append(n == 0 ? "" : ",").append("{\"name\":\"").append(names[n]).append("\"}");
		}
		return body.append("]}").toString();
	}

	private String refPath(String token, String name) throws Exception {
		return image(album(get(BEST, "json", token)), name).getRef().getPath();
	}

	private AlbumInfo stored(String folder) throws Exception {
		return (AlbumInfo) Resource.readResource(reader(new String(
			Files.readAllBytes(_base.resolve(folder).resolve("index.json")), StandardCharsets.UTF_8)));
	}

	private static List<String> files(Path folder) throws Exception {
		List<String> result = new ArrayList<>();
		for (File file : folder.toFile().listFiles()) {
			result.add(file.getName());
		}
		Collections.sort(result);
		return result;
	}

	private void write(String folder, String name, byte[] contents) throws Exception {
		Files.createDirectories(_base.resolve(folder));
		Files.write(_base.resolve(folder).resolve(name), contents);
	}

	private static List<String> outline(AlbumInfo album) {
		List<String> result = new ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof Heading) {
				Heading heading = (Heading) part;
				result.add((heading.getLevel() == 2 ? "## " : "# ") + heading.getText());
			} else if (part instanceof ImagePart) {
				result.add(((ImagePart) part).getName());
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

	private static String write(Resource object) throws Exception {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (JsonWriter json = new JsonWriter(new WriterAdapter(new OutputStreamWriter(buffer, StandardCharsets.UTF_8)))) {
			object.writeTo(json);
		}
		return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
	}

	/** A tiny JPEG with contents of its own. */
	private static byte[] photo(String seed) throws Exception {
		java.util.Random random = new java.util.Random(("collections/" + seed).hashCode());
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
