/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.auth.Ratings;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.DuplicateList;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.LabelName;
import de.haumacher.imageServer.shared.model.MoveOutcome;
import de.haumacher.imageServer.shared.model.MoveResult;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test case for issue #307, package 3: identical copies of a photograph in several albums are kept
 * as references to the one chosen as the original, see {@link KeepAsReferences}.
 *
 * <p>
 * <code>Trip</code> holds <code>x.jpg</code> (described "Sunset") and <code>a.jpg</code>,
 * <code>Other</code> holds <code>b.jpg</code>, an identical copy <code>x.jpg</code> (described "Our
 * sunset", labelled "Sea") and <code>d.jpg</code>, <code>Third</code> holds <code>t.jpg</code> and
 * another identical copy <code>x.jpg</code> (described "Sunset", like the original).
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestKeepAsReferences extends ShareTestCase {

	private static final String ORIGINAL = "Trip/x.jpg";

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		SharingFixture.album(_base, "Trip", "Trip",
			"[\"ImagePart\",{\"name\":\"x.jpg\",\"width\":4,\"height\":3,\"date\":1000,\"comment\":\"Sunset\"}],"
				+ "[\"ImagePart\",{\"name\":\"a.jpg\",\"width\":4,\"height\":3,\"date\":3000}]",
			"x.jpg", "a.jpg");
		SharingFixture.album(_base, "Other", "Other",
			"[\"ImagePart\",{\"name\":\"b.jpg\",\"width\":4,\"height\":3,\"date\":500}],"
				+ "[\"ImagePart\",{\"name\":\"x.jpg\",\"width\":4,\"height\":3,\"date\":1000,\"comment\":\"Our sunset\","
				+ "\"labels\":[{\"name\":\"Sea\"}]}],"
				+ "[\"ImagePart\",{\"name\":\"d.jpg\",\"width\":4,\"height\":3,\"date\":4000}]",
			"b.jpg", "d.jpg");
		SharingFixture.album(_base, "Third", "Third",
			"[\"ImagePart\",{\"name\":\"t.jpg\",\"width\":4,\"height\":3,\"date\":200}],"
				+ "[\"ImagePart\",{\"name\":\"x.jpg\",\"width\":4,\"height\":3,\"date\":1000,\"comment\":\"Sunset\"}]",
			"t.jpg");
		byte[] photo = Files.readAllBytes(_base.resolve(ORIGINAL));
		Files.write(_base.resolve("Other/x.jpg"), photo);
		Files.write(_base.resolve("Third/x.jpg"), photo);
		restartServer();
		servlet().index().indexNow();
	}

	// --- Keeping a copy as a reference. ---

	public void testACopyBecomesAReferenceInItsPlace() throws Exception {
		byte[] bytes = Files.readAllBytes(_base.resolve(ORIGINAL));
		assertEquals("The group to sort out.", Arrays.asList("Other/x.jpg", "Third/x.jpg", "Trip/x.jpg"),
			sorted(paths(duplicates())));
		String trip = fingerprint(_base.resolve("Trip"));

		MoveOutcome outcome = outcome(keep(ORIGINAL, "Other/x.jpg"), "Other/x.jpg");

		assertEquals("x-2.jpg", outcome.getNewName());
		assertEquals(KeepAsReferences.kept("Other", "Trip"), outcome.getMessage());

		AlbumInfo other = stored("Other");
		assertEquals("The reference stands in the copy's place.", Arrays.asList("b.jpg", "x-2.jpg", "x.jpg", "d.jpg"),
			outline(other));
		ImagePart reference = image(other, "x-2.jpg");
		assertNotNull(reference.getRef());
		assertEquals("Trip/x.jpg", reference.getRef().getPath());
		assertEquals("The copy's own description is the reference's.", "Our sunset", reference.getComment());
		assertEquals("And its labels.", Arrays.asList("Sea"), labels(reference));
		assertTrue("Stamped for the rule of the oldest reference.", reference.getRef().getCreated() > 0);

		ImagePart copy = image(other, "x.jpg");
		assertNull(copy.getRef());
		assertEquals("The copy is in the trash of its album.", Ratings.TRASH, copy.getRating());
		assertTrue("Not rescued anywhere: its file waits for the purge.", Files.exists(_base.resolve("Other/x.jpg")));
		assertEquals("Nothing arrived in the original's album.", trip, fingerprint(_base.resolve("Trip")));
		assertTrue(Arrays.equals(bytes, Files.readAllBytes(_base.resolve(ORIGINAL))));

		ImagePart shown = image(album(get("/Other/", "json", SharingFixture.ALICE)), "x-2.jpg");
		assertFalse(shown.isMissing());
		assertEquals("It resolves to the original.", "Trip/x.jpg", shown.getRef().getPath());
		assertEquals("Our sunset", shown.getComment());
		assertEquals(HttpServletResponse.SC_OK, get("/Other/x-2.jpg", "tn", SharingFixture.ALICE).status());
		assertEquals("The trash page still finds the copy.", Ratings.TRASH,
			image(album(get("/Other/", "json", SharingFixture.ALICE)), "x.jpg").getRating());

		servlet().index().indexNow();
		assertEquals("Other's copy is no copy any more; Third's and the original remain.",
			Arrays.asList("Third/x.jpg", "Trip/x.jpg"), sorted(paths(duplicates())));
	}

	public void testTheCopyRoundTrips() throws Exception {
		keep(ORIGINAL, "Other/x.jpg");

		FakeResponse first = get("/Other/", "json", SharingFixture.ALICE);
		assertEquals(HttpServletResponse.SC_OK, put("/Other/", body(first), SharingFixture.ALICE).status());
		FakeResponse second = get("/Other/", "json", SharingFixture.ALICE);

		assertEquals(write(album(first)), write(album(second)));
		restartServer();
		assertEquals(write(album(first)), write(album(get("/Other/", "json", SharingFixture.ALICE))));
		assertEquals("Still the reference and the copy in the trash.", Arrays.asList("b.jpg", "x-2.jpg", "x.jpg", "d.jpg"),
			outline(stored("Other")));
	}

	public void testAllOthersInOneCall() throws Exception {
		MoveResult result = keep(ORIGINAL, "Other/x.jpg", "Third/x.jpg");

		assertEquals("x-2.jpg", outcome(result, "Other/x.jpg").getNewName());
		assertEquals("x-2.jpg", outcome(result, "Third/x.jpg").getNewName());
		ImagePart third = image(stored("Third"), "x-2.jpg");
		assertEquals("Trip/x.jpg", third.getRef().getPath());
		assertEquals("The same description as the original's is none of its own: the original's shows.", "",
			third.getComment());
		assertEquals("Sunset", image(album(get("/Third/", "json", SharingFixture.ALICE)), "x-2.jpg").getComment());
		assertEquals(Ratings.TRASH, image(stored("Third"), "x.jpg").getRating());

		servlet().index().indexNow();
		assertEquals("Nothing left to sort out.", Collections.emptyList(), paths(duplicates()));
	}

	public void testADryRunChangesNothing() throws Exception {
		String before = albums();

		MoveResult result = keep(true, ORIGINAL, "Other/x.jpg", "Other/b.jpg");

		assertEquals(before, albums());
		assertEquals("", outcome(result, "Other/x.jpg").getMessage());
		assertEquals("", outcome(result, "Other/x.jpg").getNewName());
		assertEquals(KeepAsReferences.NOT_A_COPY, outcome(result, "Other/b.jpg").getMessage());
	}

	// --- What follows from it. ---

	public void testDeletingTheOriginalAfterwardsKeepsItWhereItIsReferenced() throws Exception {
		byte[] bytes = Files.readAllBytes(_base.resolve(ORIGINAL));
		keep(ORIGINAL, "Other/x.jpg", "Third/x.jpg");

		MoveOutcome outcome = outcome(moveResult(action("/Trip/", "delete", "x.jpg")), "x.jpg");

		assertEquals(ReferencedOriginals.kept("Other"), outcome.getMessage());
		assertFalse(Files.exists(_base.resolve(ORIGINAL)));
		assertTrue("The original moved into the place of its reference, beside the copy in the trash.",
			Arrays.equals(bytes, Files.readAllBytes(_base.resolve("Other/x-2.jpg"))));
		assertFalse("Not set aside as a duplicate of the copy in the trash.",
			Files.exists(_base.resolve(".valbum/duplicates")));
		AlbumInfo other = stored("Other");
		assertEquals(Arrays.asList("b.jpg", "x-2.jpg", "x.jpg", "d.jpg"), outline(other));
		ImagePart kept = image(other, "x-2.jpg");
		assertNull("A file now.", kept.getRef());
		assertEquals("The original's rating, not the trash.", 0, kept.getRating());
		assertEquals("Our sunset", kept.getComment());
		assertEquals("The copy is still the one in the trash.", Ratings.TRASH, image(other, "x.jpg").getRating());
		assertFalse(image(album(get("/Other/", "json", SharingFixture.ALICE)), "x-2.jpg").isMissing());
		ImagePart third = image(album(get("/Third/", "json", SharingFixture.ALICE)), "x-2.jpg");
		assertEquals("The younger reference follows, not to the copy in the trash.", "Other/x-2.jpg",
			third.getRef().getPath());
	}

	public void testDeletingTheOriginalWhileAnotherCopyRemainsLetsTheReferenceFollowIt() throws Exception {
		keep(ORIGINAL, "Other/x.jpg");

		MoveOutcome outcome = outcome(moveResult(action("/Trip/", "delete", "x.jpg")), "x.jpg");

		assertEquals("Nothing needs to be kept: Third still holds the photo.", DeleteService.trashed("x.jpg"),
			outcome.getMessage());
		assertEquals("Its hint names the remaining copy.", "Third/x.jpg", image(stored("Other"), "x-2.jpg").getRef().getPath());
		assertEquals("Third/x.jpg", image(album(get("/Other/", "json", SharingFixture.ALICE)), "x-2.jpg").getRef().getPath());
	}

	public void testPurgingTheCopyKeepsTheReference() throws Exception {
		keep(ORIGINAL, "Other/x.jpg");
		String trip = fingerprint(_base.resolve("Trip"));

		MoveResult purged = moveResult(action("/Other/", "purge"));

		assertEquals(DeleteService.PURGED, outcome(purged, "x.jpg").getMessage());
		assertTrue(outcome(purged, "x.jpg").getKept().isEmpty());
		assertFalse("The copy is destroyed: it is no longer needed.", Files.exists(_base.resolve("Other/x.jpg")));
		assertEquals("The original is untouched.", trip, fingerprint(_base.resolve("Trip")));
		assertEquals(Arrays.asList("b.jpg", "x-2.jpg", "d.jpg"), outline(stored("Other")));
		ImagePart shown = image(album(get("/Other/", "json", SharingFixture.ALICE)), "x-2.jpg");
		assertFalse(shown.isMissing());
		assertEquals("Trip/x.jpg", shown.getRef().getPath());
	}

	public void testAReferenceNeverFollowsAMorePublicCopy() throws Exception {
		AlbumInfo trip = album(get("/Trip/", "json", SharingFixture.ALICE));
		image(trip, "x.jpg").setPrivacy(Privacy.PRIVATE);
		ok(put("/Trip/", write(trip), SharingFixture.ALICE));
		ok(collect("/Trip/", SharingFixture.ZOO, "x.jpg"));

		MoveOutcome outcome = outcome(moveResult(action("/Trip/", "delete", "x.jpg")), "x.jpg");

		assertEquals("The public copies in Other and Third would show it to more people: it is kept.",
			ReferencedOriginals.kept("Zoo"), outcome.getMessage());
		ImagePart kept = image(stored(SharingFixture.ZOO), "x.jpg");
		assertNull(kept.getRef());
		assertEquals(Privacy.PRIVATE, kept.getPrivacy());
	}

	public void testAReferenceToTheCopyFollowsTheOriginal() throws Exception {
		ok(collect("/Other/", SharingFixture.ZOO, "x.jpg"));
		assertEquals("Other/x.jpg", image(stored(SharingFixture.ZOO), "x.jpg").getRef().getPath());

		keep(ORIGINAL, "Other/x.jpg");

		assertEquals("Its hint names the original now.", "Trip/x.jpg",
			image(stored(SharingFixture.ZOO), "x.jpg").getRef().getPath());
		assertTrue("The copy was not kept in the zoo.", Files.exists(_base.resolve("Other/x.jpg")));
		assertFalse(Files.exists(_base.resolve(SharingFixture.ZOO).resolve("x.jpg")));
		ImagePart shown = image(album(get("/" + SharingFixture.ZOO + "/", "json", SharingFixture.ALICE)), "x.jpg");
		assertEquals("Trip/x.jpg", shown.getRef().getPath());
	}

	public void testAReferenceNeverResolvesToTheCopyInTheTrash() throws Exception {
		keep(ORIGINAL, "Other/x.jpg", "Third/x.jpg");
		Files.createDirectories(_base.resolve("Later"));
		Files.write(_base.resolve("Later/index.json"), "[\"AlbumInfo\",{\"title\":\"Later\"}]".getBytes(StandardCharsets.UTF_8));
		restartServer();

		// The original moves on: the reference's hint is stale, and the index knows the copy's folder
		// first.
		ok(move("/Trip/", "Later", SharingFixture.ALICE, "x.jpg"));

		ImagePart shown = image(album(get("/Other/", "json", SharingFixture.ALICE)), "x-2.jpg");
		assertFalse(shown.isMissing());
		assertEquals("The original where it is now, not the copy in the trash.", "Later/x.jpg", shown.getRef().getPath());
		PhotoCollections collections = new PhotoCollections(_base, servlet().index());
		assertEquals(_base.resolve("Later/x.jpg").toAbsolutePath().normalize(),
			PhotoCollections.normalized(collections.locate(image(stored("Other"), "x-2.jpg").getRef())));
	}

	public void testAnOriginalMovedIntoAnAlbumReferencingItIsOneFileThere() throws Exception {
		keep(ORIGINAL, "Other/x.jpg");

		MoveOutcome outcome = outcome(moveResult(move("/Trip/", "Other", SharingFixture.ALICE, "x.jpg")), "x.jpg");

		assertEquals("Under the name of the reference.", "x-2.jpg", outcome.getNewName());
		assertEquals(MoveService.TOOK_REFERENCE_PLACE, outcome.getMessage());
		AlbumInfo other = stored("Other");
		assertEquals("One entry for the photo, in the reference's place.", Arrays.asList("b.jpg", "x-2.jpg", "x.jpg", "d.jpg"),
			outline(other));
		ImagePart file = image(other, "x-2.jpg");
		assertNull("A file.", file.getRef());
		assertEquals("The album's own description.", "Our sunset", file.getComment());
		assertEquals(Arrays.asList("Sea"), labels(file));
		assertEquals(0, file.getRating());
		assertFalse("Not set aside beside the copy in the trash.", Files.exists(_base.resolve(".valbum/duplicates")));
		assertTrue(PhotoCollections.references(other).isEmpty());
	}

	public void testAMovedPhotoTakesThePlaceOfItsReference() throws Exception {
		ok(collect("/Trip/", "Other", "a.jpg"));
		AlbumInfo before = stored("Other");
		assertEquals(Arrays.asList("b.jpg", "x.jpg", "a.jpg", "d.jpg"), outline(before));

		MoveOutcome outcome = outcome(moveResult(move("/Trip/", "Other", SharingFixture.ALICE, "a.jpg")), "a.jpg");

		assertEquals("a.jpg", outcome.getNewName());
		assertEquals(MoveService.TOOK_REFERENCE_PLACE, outcome.getMessage());
		AlbumInfo other = stored("Other");
		assertEquals(Arrays.asList("b.jpg", "x.jpg", "a.jpg", "d.jpg"), outline(other));
		assertNull(image(other, "a.jpg").getRef());
		assertTrue(PhotoCollections.references(other).isEmpty());
	}

	public void testAnUploadTakesThePlaceOfItsReference() throws Exception {
		ok(collect("/Trip/", "Other", "a.jpg"));
		byte[] bytes = Files.readAllBytes(_base.resolve("Trip/a.jpg"));
		AlbumInfo described = album(get("/Other/", "json", SharingFixture.ALICE));
		image(described, "a.jpg").setComment("Ours").setLabels(new ArrayList<>(Arrays.asList(LabelName.create().setName("Best"))));
		ok(put("/Other/", write(described), SharingFixture.ALICE));

		FakeResponse response = upload("/Other/", SharingFixture.ALICE, "upload.jpg", bytes);

		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		AlbumInfo other = stored("Other");
		assertEquals("The upload lands as a file in the reference's place.", Arrays.asList("b.jpg", "x.jpg", "upload.jpg", "d.jpg"),
			outline(other));
		ImagePart file = image(other, "upload.jpg");
		assertNull(file.getRef());
		assertEquals("Ours", file.getComment());
		assertEquals(Arrays.asList("Best"), labels(file));
		assertTrue(PhotoCollections.references(other).isEmpty());
		ImagePart shown = image(album(get("/Other/", "json", SharingFixture.ALICE)), "upload.jpg");
		assertTrue("Completed from the analysis.", shown.getWidth() > 0);
	}

	// --- Refusals. ---

	public void testACopyMorePrivateThanTheOriginalIsRefused() throws Exception {
		AlbumInfo other = album(get("/Other/", "json", SharingFixture.ALICE));
		image(other, "x.jpg").setPrivacy(Privacy.PRIVATE);
		ok(put("/Other/", write(other), SharingFixture.ALICE));
		String before = albums();

		MoveOutcome outcome = outcome(keep(ORIGINAL, "Other/x.jpg"), "Other/x.jpg");

		assertEquals(KeepAsReferences.morePrivate("Trip"), outcome.getMessage());
		assertEquals("", outcome.getNewName());
		assertEquals("Nothing changed.", before, albums());
	}

	public void testAnOriginalMorePrivateThanTheCopyIsKept() throws Exception {
		AlbumInfo trip = album(get("/Trip/", "json", SharingFixture.ALICE));
		image(trip, "x.jpg").setPrivacy(Privacy.MEMBERS);
		ok(put("/Trip/", write(trip), SharingFixture.ALICE));

		MoveOutcome outcome = outcome(keep(ORIGINAL, "Other/x.jpg"), "Other/x.jpg");

		assertEquals("x-2.jpg", outcome.getNewName());
		assertNull("The reference shows the photo as the original is shown: to fewer, never to more.",
			Crops.findImage(album(get("/Other/", "json", SharingFixture.ALICE, Privacy.VIEW_AS_PUBLIC)), "x-2.jpg"));
	}

	public void testACopyInAnAlbumTheCallerMayNotEditIsRefused() throws Exception {
		String before = albums();

		MoveResult result = keepAs(SharingFixture.BOB, false, ORIGINAL, "Other/x.jpg");

		assertEquals(KeepAsReferences.editRefused("Other"), outcome(result, "Other/x.jpg").getMessage());
		assertEquals(before, albums());
	}

	public void testEveryCopyIsJudgedByItself() throws Exception {
		MoveResult result = keep(ORIGINAL, "Other/b.jpg", ORIGINAL, "Trip/a.jpg", "Nowhere/x.jpg", "Third/x.jpg",
			"Third/x.jpg");

		assertEquals(KeepAsReferences.NOT_A_COPY, outcome(result, "Other/b.jpg").getMessage());
		assertEquals(KeepAsReferences.IS_ORIGINAL, outcome(result, ORIGINAL).getMessage());
		assertEquals(KeepAsReferences.SAME_ALBUM, outcome(result, "Trip/a.jpg").getMessage());
		assertEquals(ImageServlet.notInAlbum("Nowhere/x.jpg"), outcome(result, "Nowhere/x.jpg").getMessage());
		assertEquals("x-2.jpg", result.getOutcomes().get(4).getNewName());
		assertEquals(KeepAsReferences.NAMED_TWICE, result.getOutcomes().get(5).getMessage());
		assertEquals(Ratings.TRASH, image(stored("Third"), "x.jpg").getRating());
		assertEquals("Other is untouched.", "x.jpg", outline(stored("Other")).get(1));
		assertEquals(0, image(stored("Other"), "x.jpg").getRating());
	}

	public void testAnOriginalInTheTrashIsRefused() throws Exception {
		AlbumInfo trip = album(get("/Trip/", "json", SharingFixture.ALICE));
		image(trip, "x.jpg").setRating(Ratings.TRASH);
		ok(put("/Trip/", write(trip), SharingFixture.ALICE));

		FakeResponse response = post("/", body(ORIGINAL, "Other/x.jpg"), SharingFixture.ALICE,
			parameters(ImageServlet.KEEP_AS_REFERENCES_ACTION));

		assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status());
		assertTrue(response.body(), response.body().contains(PhotoCollections.TRASHED_REFUSED));
	}

	public void testAShareLinkIsRefused() throws Exception {
		String token = zooToken("view");

		FakeResponse response = post("/", body(ORIGINAL, "Other/x.jpg"), token,
			parameters(ImageServlet.KEEP_AS_REFERENCES_ACTION));

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
	}

	public void testAGroupMemberIsReplacedInItsGroup() throws Exception {
		Files.write(_base.resolve("Other/index.json"), ("[\"AlbumInfo\",{\"title\":\"Other\",\"parts\":["
			+ "[\"ImageGroup\",{\"representative\":0,\"images\":["
			+ "{\"name\":\"x.jpg\",\"width\":4,\"height\":3,\"date\":1000},"
			+ "{\"name\":\"b.jpg\",\"width\":4,\"height\":3,\"date\":1001}]}],"
			+ "[\"ImagePart\",{\"name\":\"d.jpg\",\"width\":4,\"height\":3,\"date\":4000}]]}]").getBytes(StandardCharsets.UTF_8));
		restartServer();

		keep(ORIGINAL, "Other/x.jpg");

		AlbumInfo other = stored("Other");
		ImageGroup group = (ImageGroup) other.getParts().get(0);
		List<String> members = new ArrayList<>();
		for (ImagePart member : group.getImages()) {
			members.add(member.getName());
		}
		assertEquals(Arrays.asList("x-2.jpg", "x.jpg", "b.jpg"), members);
		assertEquals("The group is shown by the reference, as it was by the copy.", 0, group.getRepresentative());
	}

	// --- Helpers. ---

	private DuplicateList duplicates() throws Exception {
		return DuplicateList.readDuplicateList(reader(body(get("/", ImageServlet.DUPLICATES_TYPE, SharingFixture.ALICE))));
	}

	/** The copies of the group of the photo of this test, the only one asked about. */
	private List<String> paths(DuplicateList list) throws Exception {
		String hash = new de.haumacher.imageServer.upload.HashCache(_base.resolve("Trip").toFile()).hashByName().get("x.jpg");
		List<String> result = new ArrayList<>();
		list.getGroups().stream().filter(g -> g.getHash().equals(hash))
			.forEach(g -> g.getCopies().forEach(c -> result.add(c.getAlbum() + "/" + c.getName())));
		return result;
	}

	private MoveResult keep(String original, String... copies) throws Exception {
		return keep(false, original, copies);
	}

	private MoveResult keep(boolean dryRun, String original, String... copies) throws Exception {
		return keepAs(SharingFixture.ALICE, dryRun, original, copies);
	}

	private MoveResult keepAs(String token, boolean dryRun, String original, String... copies) throws Exception {
		FakeResponse response = post("/", body(original, copies), token,
			parameters(dryRun ? ImageServlet.KEEP_AS_REFERENCES_CHECK_ACTION : ImageServlet.KEEP_AS_REFERENCES_ACTION));
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		return moveResult(response);
	}

	private static String body(String original, String... copies) {
		StringBuilder body = new StringBuilder("{\"original\":\"").append(original).append("\",\"copies\":[");
		for (int n = 0; n < copies.length; n++) {
			body.append(n == 0 ? "" : ",").append("{\"name\":\"").append(copies[n]).append("\"}");
		}
		return body.append("]}").toString();
	}

	private static Map<String, String> parameters(String action) {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", action);
		return parameters;
	}

	private FakeResponse action(String pathInfo, String action, String... names) throws Exception {
		StringBuilder body = new StringBuilder("{\"names\":[");
		for (int n = 0; n < names.length; n++) {
			body.append(n == 0 ? "" : ",").append("{\"name\":\"").append(names[n]).append("\"}");
		}
		body.append("]}");
		FakeResponse response = post(pathInfo, names.length == 0 ? "" : body.toString(), SharingFixture.ALICE,
			parameters(action));
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		return response;
	}

	private FakeResponse collect(String pathInfo, String target, String... names) throws Exception {
		StringBuilder body = new StringBuilder("{\"target\":\"").append(target).append("\",\"names\":[");
		for (int n = 0; n < names.length; n++) {
			body.append(n == 0 ? "" : ",").append("{\"name\":\"").append(names[n]).append("\"}");
		}
		body.append("]}");
		return post(pathInfo, body.toString(), SharingFixture.ALICE, parameters(ImageServlet.COLLECT_ACTION));
	}

	private static void ok(FakeResponse response) {
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
	}

	private String albums() throws Exception {
		return fingerprint(_base.resolve("Trip")) + fingerprint(_base.resolve("Other")) + fingerprint(_base.resolve("Third"));
	}

	private AlbumInfo stored(String folder) throws Exception {
		return (AlbumInfo) Resource.readResource(reader(new String(
			Files.readAllBytes(_base.resolve(folder).resolve("index.json")), StandardCharsets.UTF_8)));
	}

	private static List<String> outline(AlbumInfo album) {
		List<String> result = new ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
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

	private static List<String> sorted(List<String> list) {
		List<String> result = new ArrayList<>(list);
		Collections.sort(result);
		return result;
	}

	private static String write(Resource resource) throws Exception {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (JsonWriter json = new JsonWriter(new WriterAdapter(new OutputStreamWriter(buffer, StandardCharsets.UTF_8)))) {
			resource.writeTo(json);
		}
		return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
	}
}
