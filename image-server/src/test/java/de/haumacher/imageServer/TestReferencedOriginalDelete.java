/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.Ratings;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.CreateResult;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.KeptPhoto;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Test case for issue #307, package 2: deleting an original that other albums reference never
 * leaves references into nothing, see {@link ReferencedOriginals}.
 *
 * <p>
 * The library of {@link TestAlbumReferences}: <code>Trip</code> holding <code>a.jpg</code> and
 * <code>c.jpg</code>, <code>Other</code> holding <code>b.jpg</code> (described "Beach") and
 * <code>d.jpg</code>, and an empty album <code>Third</code>.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestReferencedOriginalDelete extends ShareTestCase {

	private static final String TRIP = "/Trip/";

	private static final String OTHER = "/Other/";

	private static final String THIRD = "/Third/";

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		SharingFixture.album(_base, "Trip", "Trip",
			"[\"ImagePart\",{\"name\":\"a.jpg\",\"width\":4,\"height\":3,\"date\":1000}],"
				+ "[\"ImagePart\",{\"name\":\"c.jpg\",\"width\":4,\"height\":3,\"date\":3000}]",
			"a.jpg", "c.jpg");
		SharingFixture.album(_base, "Other", "Other",
			"[\"ImagePart\",{\"name\":\"b.jpg\",\"width\":4,\"height\":3,\"date\":2000,\"comment\":\"Beach\","
				+ "\"labels\":[{\"name\":\"Sea\"}]}],"
				+ "[\"ImagePart\",{\"name\":\"d.jpg\",\"width\":4,\"height\":3,\"date\":4000}]",
			"b.jpg", "d.jpg");
		Files.createDirectories(_base.resolve("Third"));
		Files.write(_base.resolve("Third/index.json"),
			"[\"AlbumInfo\",{\"title\":\"Third\"}]".getBytes(StandardCharsets.UTF_8));
		restartServer();
	}

	// --- Deleting a photograph. ---

	public void testADeletedOriginalGoesToTheAlbumOfItsOldestReference() throws Exception {
		byte[] original = Files.readAllBytes(_base.resolve("Other/b.jpg"));
		collect(OTHER, "Trip", "b.jpg");
		collect(OTHER, "Third", "b.jpg");
		describe(TRIP, "b.jpg", "Where we swam", "Best");

		MoveResult result = moveResult(delete(OTHER, "b.jpg"));

		MoveOutcome outcome = outcome(result, "b.jpg");
		assertEquals("b.jpg", outcome.getNewName());
		assertEquals(ReferencedOriginals.kept("Trip"), outcome.getMessage());
		assertEquals(Arrays.asList("b.jpg -> Trip (Trip) as b.jpg"), kept(outcome));

		assertFalse(Files.exists(_base.resolve("Other/b.jpg")));
		assertTrue("The file itself moved.", Arrays.equals(original, Files.readAllBytes(_base.resolve("Trip/b.jpg"))));
		assertFalse("Nothing went into the trash.", Files.exists(_base.resolve(".valbum/trash/Other/b.jpg")));

		AlbumInfo trip = stored("Trip");
		ImagePart file = image(trip, "b.jpg");
		assertNull("The reference was replaced by the file.", file.getRef());
		assertEquals("The reference's own description is the file's there.", "Where we swam", file.getComment());
		assertEquals("And its labels.", Arrays.asList("Best"), labels(file));
		assertEquals("In the reference's place.", Arrays.asList("a.jpg", "b.jpg", "c.jpg"), outline(trip));
		assertEquals(1, PhotoCollections.ownImages(trip).stream().filter(i -> i.getName().equals("b.jpg")).count());

		ImagePart third = image(album(get(THIRD, "json", SharingFixture.ALICE)), "b.jpg");
		assertFalse("The other reference follows.", third.isMissing());
		assertEquals("Trip/b.jpg", third.getRef().getPath());
		assertEquals("Its hint is refreshed.", "Trip/b.jpg", image(stored("Third"), "b.jpg").getRef().getPath());
		assertEquals(HttpServletResponse.SC_OK, get(THIRD + "b.jpg", "tn", SharingFixture.ALICE).status());
		assertEquals("Where we swam", image(album(get(TRIP, "json", SharingFixture.ALICE)), "b.jpg").getComment());
	}

	public void testWithoutADescriptionOfItsOwnTheOriginalsTravels() throws Exception {
		collect(OTHER, "Trip", "b.jpg");
		collect(OTHER, "Third", "b.jpg");

		moveResult(delete(OTHER, "b.jpg"));

		assertEquals("Beach", image(stored("Trip"), "b.jpg").getComment());
		assertEquals("Beach", image(album(get(TRIP, "json", SharingFixture.ALICE)), "b.jpg").getComment());
		assertEquals("The labels are the reference's: it had none.", Collections.emptyList(),
			labels(image(stored("Trip"), "b.jpg")));
	}

	public void testTheOldestReferenceIsTheOneMadeFirstNotTheFirstByName() throws Exception {
		// "Third" sorts before "Trip"; Trip's reference is the older one.
		collect(OTHER, "Trip", "b.jpg");
		collect(OTHER, "Third", "b.jpg");
		assertTrue(image(stored("Trip"), "b.jpg").getRef().getCreated() < image(stored("Third"), "b.jpg").getRef()
			.getCreated());

		moveResult(delete(OTHER, "b.jpg"));

		assertTrue(Files.exists(_base.resolve("Trip/b.jpg")));
		assertFalse(Files.exists(_base.resolve("Third/b.jpg")));
	}

	public void testAPhotoNobodyReferencesGoesIntoTheTrash() throws Exception {
		collect(OTHER, "Trip", "b.jpg");

		MoveOutcome outcome = outcome(moveResult(delete(OTHER, "d.jpg")), "d.jpg");

		assertEquals(Collections.emptyList(), outcome.getKept());
		assertEquals(DeleteService.trashed("d.jpg"), outcome.getMessage());
		assertTrue(Files.exists(_base.resolve(".valbum/trash/Other/d.jpg")));
	}

	public void testRatingAsTrashIsDeleting() throws Exception {
		collect(OTHER, "Trip", "b.jpg");
		AlbumInfo other = album(get(OTHER, "json", SharingFixture.ALICE));
		image(other, "b.jpg").setRating(1);
		assertEquals(HttpServletResponse.SC_OK, put(OTHER, write(other), SharingFixture.ALICE).status());

		AlbumInfo again = album(get(OTHER, "json", SharingFixture.ALICE));
		image(again, "b.jpg").setRating(Ratings.TRASH);
		FakeResponse response = put(OTHER, write(again), SharingFixture.ALICE);

		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		CreateResult written = CreateResult.readCreateResult(reader(response.body()));
		assertEquals(Arrays.asList("b.jpg -> Trip (Trip) as b.jpg"), kept(written.getKept()));
		assertFalse(Files.exists(_base.resolve("Other/b.jpg")));
		assertNull(Crops.findImage(stored("Other"), "b.jpg"));
		ImagePart file = image(stored("Trip"), "b.jpg");
		assertNull(file.getRef());
		assertEquals("The trash rating was the delete; the photograph keeps the rating it had.", 1, file.getRating());
		assertEquals(1, image(album(get(TRIP, "json", SharingFixture.ALICE)), "b.jpg").getRating());
	}

	public void testAReferenceRatedAsTrashIsTakenOutOfItsAlbum() throws Exception {
		collect(OTHER, "Trip", "b.jpg");
		String other = fingerprint(_base.resolve("Other"));
		AlbumInfo trip = album(get(TRIP, "json", SharingFixture.ALICE));
		image(trip, "b.jpg").setRating(Ratings.TRASH);

		assertEquals(HttpServletResponse.SC_OK, put(TRIP, write(trip), SharingFixture.ALICE).status());

		assertNull(Crops.findImage(stored("Trip"), "b.jpg"));
		assertEquals("The original is untouched.", other, fingerprint(_base.resolve("Other")));
	}

	// --- Deleting an album. ---

	public void testDeletingAnAlbumKeepsWhatOthersReference() throws Exception {
		collect(OTHER, "Trip", "b.jpg");

		MoveResult result = moveResult(delete("/", "Other"));

		MoveOutcome outcome = outcome(result, "Other");
		assertEquals(Arrays.asList("Other/b.jpg -> Trip (Trip) as b.jpg"), kept(outcome));
		assertTrue(Files.exists(_base.resolve("Trip/b.jpg")));
		assertNull(image(stored("Trip"), "b.jpg").getRef());
		assertTrue("The rest went into the trash.", Files.exists(_base.resolve(".valbum/trash/Other/d.jpg")));
		assertFalse(Files.exists(_base.resolve(".valbum/trash/Other/b.jpg")));
		assertFalse(Files.exists(_base.resolve("Other")));
	}

	public void testDeletingTheReferencingAlbumToo() throws Exception {
		collect(OTHER, "Trip", "b.jpg");
		collect(TRIP, "Third", "a.jpg");

		MoveResult result = moveResult(delete("/", "Trip", "Other"));

		assertEquals(Collections.emptyList(), outcome(result, "Other").getKept());
		assertEquals("Trip's own photograph is referenced by a surviving album.",
			Arrays.asList("Trip/a.jpg -> Third (Third) as a.jpg"), kept(outcome(result, "Trip")));
		assertTrue("Every referencing album went away: the trash.",
			Files.exists(_base.resolve(".valbum/trash/Other/b.jpg")));
		assertTrue(Files.exists(_base.resolve("Third/a.jpg")));
		assertNoDanglingReference();
	}

	public void testAChainSkipsTheAlbumDeletedInTheSameStep() throws Exception {
		collect(OTHER, "Trip", "b.jpg");
		collect(OTHER, "Third", "b.jpg");

		MoveResult result = moveResult(delete("/", "Trip", "Other"));

		assertEquals(Arrays.asList("Other/b.jpg -> Third (Third) as b.jpg"), kept(outcome(result, "Other")));
		assertTrue(Files.exists(_base.resolve("Third/b.jpg")));
		assertNoDanglingReference();
	}

	// --- Names. ---

	public void testTheRescuedFileTakesTheNameOfTheReference() throws Exception {
		assertEquals(HttpServletResponse.SC_OK, upload(TRIP, SharingFixture.ALICE, "b.jpg").status());
		byte[] own = Files.readAllBytes(_base.resolve("Trip/b.jpg"));
		byte[] original = Files.readAllBytes(_base.resolve("Other/b.jpg"));
		collect(OTHER, "Trip", "b.jpg");
		assertNotNull(PhotoCollections.referenceNamed(stored("Trip"), "b-2.jpg"));

		MoveOutcome outcome = outcome(moveResult(delete(OTHER, "b.jpg")), "b.jpg");

		assertEquals("b-2.jpg", outcome.getNewName());
		assertEquals(Arrays.asList("b.jpg -> Trip (Trip) as b-2.jpg"), kept(outcome));
		assertTrue(Arrays.equals(own, Files.readAllBytes(_base.resolve("Trip/b.jpg"))));
		assertTrue(Arrays.equals(original, Files.readAllBytes(_base.resolve("Trip/b-2.jpg"))));
		AlbumInfo trip = stored("Trip");
		assertNull(image(trip, "b-2.jpg").getRef());
		assertEquals(PhotoCollections.references(trip).toString(), 0, PhotoCollections.references(trip).size());
	}

	// --- The purge. ---

	public void testAPurgeKeepsAReferencedPhotoInTheTrashOfItsAlbum() throws Exception {
		collect(OTHER, "Trip", "b.jpg");
		byte[] original = Files.readAllBytes(_base.resolve("Other/b.jpg"));
		// Rated as trash by a build before this rule, behind the server's back.
		Path index = _base.resolve("Other/index.json");
		String text = new String(Files.readAllBytes(index), StandardCharsets.UTF_8);
		Files.write(index, text.replace("\"name\":\"b.jpg\",", "\"name\":\"b.jpg\",\"rating\":-2,")
			.replace("\"name\":\"d.jpg\",", "\"name\":\"d.jpg\",\"rating\":-2,").getBytes(StandardCharsets.UTF_8));
		restartServer();

		MoveResult dry = moveResult(purge(OTHER, true));
		MoveResult result = moveResult(purge(OTHER, false));

		MoveOutcome kept = outcome(result, "b.jpg");
		assertEquals(ReferencedOriginals.keptInTrash("Trip"), kept.getMessage());
		assertEquals(Arrays.asList("b.jpg -> Trip (Trip) as b.jpg"), kept(kept));
		assertEquals(kept(kept), kept(outcome(dry, "b.jpg")));
		assertEquals(DeleteService.PURGED, outcome(result, "d.jpg").getMessage());
		assertEquals(Collections.emptyList(), outcome(dry, "d.jpg").getKept());
		assertFalse("Purged.", Files.exists(_base.resolve("Other/d.jpg")));
		assertTrue("Never destroyed.", Arrays.equals(original, Files.readAllBytes(_base.resolve("Trip/b.jpg"))));
		ImagePart healed = image(stored("Trip"), "b.jpg");
		assertNull("The reference into the trash is healed: the file took its place.", healed.getRef());
		assertEquals("Shown there as before, not in a trash.", 0, healed.getRating());
		assertEquals(Arrays.asList("a.jpg", "b.jpg", "c.jpg"), outline(album(get(TRIP, "json", SharingFixture.DAVE))));
		assertEquals("Nothing of Trip is purged.", Collections.emptyList(), moveResult(purge(TRIP, false)).getOutcomes());
		assertTrue(Files.exists(_base.resolve("Trip/b.jpg")));
	}

	public void testAPhotoInTheTrashIsNeverReferenced() throws Exception {
		AlbumInfo other = album(get(OTHER, "json", SharingFixture.ALICE));
		image(other, "b.jpg").setRating(Ratings.TRASH);
		assertEquals(HttpServletResponse.SC_OK, put(OTHER, write(other), SharingFixture.ALICE).status());

		MoveResult result = moveResult(collect(OTHER, "Trip", "b.jpg", "d.jpg"));

		assertEquals(PhotoCollections.TRASHED_REFUSED, outcome(result, "b.jpg").getMessage());
		assertEquals("", outcome(result, "b.jpg").getNewName());
		assertEquals("d.jpg", outcome(result, "d.jpg").getNewName());
		assertNull(Crops.findImage(stored("Trip"), "b.jpg"));
	}

	// --- The question before. ---

	public void testTheDryRunAnswersWhatTheDeleteDoes() throws Exception {
		collect(OTHER, "Trip", "b.jpg");
		collect(OTHER, "Third", "b.jpg");
		String before = albums();

		MoveResult dry = moveResult(delete(OTHER, true, "b.jpg", "d.jpg", "nothing.jpg"));

		assertEquals("Nothing changed.", before, albums());
		MoveResult done = moveResult(delete(OTHER, "b.jpg", "d.jpg", "nothing.jpg"));
		for (String name : Arrays.asList("b.jpg", "d.jpg", "nothing.jpg")) {
			assertEquals(name, kept(outcome(done, name)), kept(outcome(dry, name)));
		}
		assertEquals(Arrays.asList("b.jpg -> Trip (Trip) as b.jpg"), kept(outcome(dry, "b.jpg")));
		assertEquals(DeleteService.notFound("nothing.jpg"), outcome(dry, "nothing.jpg").getMessage());
	}

	public void testTheDryRunOfAnAlbumDelete() throws Exception {
		collect(OTHER, "Trip", "b.jpg");
		assertEquals(HttpServletResponse.SC_OK, upload(TRIP, SharingFixture.ALICE, "b.jpg").status());
		collect(OTHER, "Third", "d.jpg");
		String before = albums();

		MoveResult dry = moveResult(delete("/", true, "Other"));

		assertEquals(before, albums());
		MoveResult done = moveResult(delete("/", "Other"));
		assertEquals(kept(outcome(done, "Other")), kept(outcome(dry, "Other")));
		assertEquals(Arrays.asList("Other/b.jpg -> Trip (Trip) as b.jpg", "Other/d.jpg -> Third (Third) as d.jpg"),
			sorted(kept(outcome(done, "Other"))));
	}

	// --- The stored form. ---

	public void testTheReceivingAlbumRoundTrips() throws Exception {
		collect(OTHER, "Trip", "b.jpg");
		describe(TRIP, "b.jpg", "Where we swam", "Best");
		moveResult(delete(OTHER, "b.jpg"));

		FakeResponse first = get(TRIP, "json", SharingFixture.ALICE);
		assertEquals(HttpServletResponse.SC_OK, put(TRIP, body(first), SharingFixture.ALICE).status());
		FakeResponse second = get(TRIP, "json", SharingFixture.ALICE);

		assertEquals(write(album(first)), write(album(second)));
		restartServer();
		assertEquals(write(album(first)), write(album(get(TRIP, "json", SharingFixture.ALICE))));
	}

	// --- Helpers. ---

	/** Every surviving reference of the library still finds its photograph. */
	private void assertNoDanglingReference() throws Exception {
		List<Path> sidecars = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(_base)) {
			walk.filter(p -> p.getFileName().toString().equals("index.json") && !p.toString().contains("/.valbum/"))
				.forEach(sidecars::add);
		}
		PhotoCollections collections = new PhotoCollections(_base, servlet().index());
		for (Path sidecar : sidecars) {
			Resource resource = Resource.readResource(reader(new String(Files.readAllBytes(sidecar), StandardCharsets.UTF_8)));
			if (resource instanceof AlbumInfo) {
				for (ImagePart reference : PhotoCollections.references((AlbumInfo) resource)) {
					assertNotNull(sidecar + ": " + reference.getName(), collections.locate(reference.getRef()));
				}
			}
		}
	}

	private String albums() throws Exception {
		return fingerprint(_base.resolve("Trip")) + fingerprint(_base.resolve("Other")) + fingerprint(_base.resolve("Third"));
	}

	private void describe(String album, String name, String comment, String label) throws Exception {
		AlbumInfo answer = album(get(album, "json", SharingFixture.ALICE));
		image(answer, name).setComment(comment)
			.setLabels(new ArrayList<>(Arrays.asList(LabelName.create().setName(label))));
		assertEquals(HttpServletResponse.SC_OK, put(album, write(answer), SharingFixture.ALICE).status());
	}

	private FakeResponse collect(String pathInfo, String target, String... names) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", ImageServlet.COLLECT_ACTION);
		FakeResponse response = post(pathInfo, names("{\"target\":\"" + target + "\",", names), SharingFixture.ALICE,
			parameters);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		return response;
	}

	private FakeResponse delete(String pathInfo, String... names) throws Exception {
		return delete(pathInfo, false, names);
	}

	private FakeResponse delete(String pathInfo, boolean dryRun, String... names) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", dryRun ? ImageServlet.DELETE_CHECK_ACTION : "delete");
		FakeResponse response = post(pathInfo, names("{", names), SharingFixture.ALICE, parameters);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		return response;
	}

	private FakeResponse purge(String pathInfo, boolean dryRun) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", dryRun ? ImageServlet.PURGE_CHECK_ACTION : "purge");
		FakeResponse response = post(pathInfo, "", SharingFixture.ALICE, parameters);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		return response;
	}

	private static String names(String start, String... names) {
		StringBuilder body = new StringBuilder(start).append("\"names\":[");
		for (int n = 0; n < names.length; n++) {
			body.append(n == 0 ? "" : ",").append("{\"name\":\"").append(names[n]).append("\"}");
		}
		return body.append("]}").toString();
	}

	private static List<String> kept(MoveOutcome outcome) {
		return kept(outcome.getKept());
	}

	private static List<String> kept(List<KeptPhoto> kept) {
		List<String> result = new ArrayList<>();
		for (KeptPhoto photo : kept) {
			result.add(photo.getPhoto() + " -> " + photo.getAlbum() + " (" + photo.getTitle() + ") as " + photo.getName());
		}
		return result;
	}

	private static List<String> sorted(List<String> list) {
		List<String> result = new ArrayList<>(list);
		Collections.sort(result);
		return result;
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

	private static String write(Resource resource) throws Exception {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (JsonWriter json = new JsonWriter(new WriterAdapter(new OutputStreamWriter(buffer, StandardCharsets.UTF_8)))) {
			resource.writeTo(json);
		}
		return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
	}
}
