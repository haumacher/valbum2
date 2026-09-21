/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.Clearances;
import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.auth.UserStore.Device;
import de.haumacher.imageServer.auth.UserStore.User;
import de.haumacher.imageServer.faces.FaceCache;
import de.haumacher.imageServer.faces.FaceDetection;
import de.haumacher.imageServer.faces.PeopleStore;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.CreateResult;
import de.haumacher.imageServer.shared.model.FaceInfo;
import de.haumacher.imageServer.shared.model.FaceState;
import de.haumacher.imageServer.shared.model.FaceTag;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Person;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import de.haumacher.imageServer.upload.HashCache;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * What somebody decided about a face: storing it, answering it and who may say it, see issue #125.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestFaceTags extends FacesTestCase {

	private static final String BOB_TOKEN = "bob-token-0123456789";

	private static final String DAVE_TOKEN = "dave-token-0123456789";

	@Override
	protected void extraUsers(UserStore store) {
		store.addUser(user("bob", BOB_TOKEN, Roles.CONTRIBUTE));
		store.addUser(user("dave", DAVE_TOKEN, Roles.VIEW));
	}

	private static User user(String name, String token, String role) {
		User result = new User(name, role, "", Instant.now().toString(), Clearances.ALL, true);
		result.addDevice(new Device(name + "'s device", UserStore.hash(token), Instant.now().toString()));
		return result;
	}

	// --- Storing and answering. ---

	/**
	 * A confirmed face is written into <code>index.json</code> with its box, answered with the
	 * person, and found again by its box after the detections were thrown away and made afresh.
	 */
	public void testAConfirmedFaceIsStoredAndFoundAgain() throws Exception {
		createSpace(A_ONE, B);
		if (!detectorAvailable()) {
			return;
		}
		index();
		Person anna = created("Anna");

		FaceInfo detected = image(album("/" + ALBUM + "/", _adminToken), A_ONE).getFaces().get(0);
		assertEquals("Nothing is decided yet.", FaceState.UNDECIDED, detected.getState());
		assertFalse(detected.isConfirmed());
		assertEquals("", detected.getPerson());

		AlbumInfo answer = tag(assignment(A_ONE, 0, anna.getId(), "CONFIRMED"));
		FaceInfo confirmed = image(answer, A_ONE).getFaces().get(0);
		assertEquals(anna.getId(), confirmed.getPerson());
		assertTrue(confirmed.isConfirmed());
		assertEquals(FaceState.CONFIRMED, confirmed.getState());
		assertFalse("It is still the detected face, group and all.", confirmed.getCluster().isEmpty());

		String sidecar = sidecar();
		assertTrue("The decision belongs beside the photograph: " + sidecar,
			sidecar.contains("\"state\":\"CONFIRMED\""));
		assertTrue(sidecar.contains("\"person\":\"" + anna.getId() + "\""));
		assertFalse("A detection is not stored: " + sidecar, sidecar.contains("\"cluster\""));

		ImagePart stored = stored(A_ONE);
		assertEquals(1, stored.getTags().size());
		FaceTag tag = stored.getTags().get(0);
		assertEquals("The box is the detector's, in the raw raster.", detected.getX(), tag.getX(), 1e-9);
		assertEquals(detected.getY(), tag.getY(), 1e-9);
		assertEquals(detected.getW(), tag.getW(), 1e-9);
		assertEquals(detected.getH(), tag.getH(), 1e-9);

		// The detections are cache and may be thrown away; the decision is not.
		assertEquals(200, post("/" + ALBUM + "/", "refresh-cache", "{}", _adminToken).status());
		assertFalse(FaceCache.file(album()).exists());
		index();

		FaceInfo again = image(album("/" + ALBUM + "/", _adminToken), A_ONE).getFaces().get(0);
		assertTrue("The same face is the same face.", again.isConfirmed());
		assertEquals(anna.getId(), again.getPerson());
		assertEquals("And it is one face, not two.", 1,
			image(album("/" + ALBUM + "/", _adminToken), A_ONE).getFaces().size());
	}

	/** A decision outlives the detector: what nobody finds any more is still answered. */
	public void testATagWithoutADetectionIsAnsweredAllTheSame() throws Exception {
		createSpace(A_ONE);
		if (!detectorAvailable()) {
			return;
		}
		index();
		Person anna = created("Anna");
		tag(assignment(A_ONE, 0, anna.getId(), "CONFIRMED"));

		// A model that finds nothing at all, and a cache thrown away so that it is asked.
		assertEquals(200, post("/" + ALBUM + "/", "refresh-cache", "{}", _adminToken).status());
		FaceDetection.setDetector(preview -> new FaceDetection.Result(800, 600, Collections.emptyList()));
		index();
		assertTrue("Nothing was found.", new FaceCache(album()).facesOf(hashOf(A_ONE)).isEmpty());

		List<FaceInfo> faces = image(album("/" + ALBUM + "/", _adminToken), A_ONE).getFaces();
		assertEquals("The decision is answered as a face of its own.", 1, faces.size());
		assertEquals(0, faces.get(0).getIndex());
		assertEquals(anna.getId(), faces.get(0).getPerson());
		assertTrue(faces.get(0).isConfirmed());
		assertEquals("A tag belongs to no group.", "", faces.get(0).getCluster());
		assertTrue(faces.get(0).getW() > 0);
	}

	/** A rejection and a false detection are answered, and a later confirmation replaces them. */
	public void testRejectedAndNotAFace() throws Exception {
		createSpace(A_ONE, B);
		if (!detectorAvailable()) {
			return;
		}
		index();
		Person anna = created("Anna");

		AlbumInfo rejected = tag(assignment(A_ONE, 0, anna.getId(), "REJECTED"));
		FaceInfo face = image(rejected, A_ONE).getFaces().get(0);
		assertEquals("The person stands in the rejection.", anna.getId(), face.getPerson());
		assertFalse(face.isConfirmed());
		assertEquals(FaceState.REJECTED, face.getState());

		AlbumInfo none = tag(assignment(B, 0, "", "NOT_A_FACE"));
		FaceInfo nothing = image(none, B).getFaces().get(0);
		assertEquals(FaceState.NOT_A_FACE, nothing.getState());
		assertEquals("", nothing.getPerson());
		assertFalse(nothing.isConfirmed());

		// Somebody names the face after all: one face, one decision.
		AlbumInfo confirmed = tag(assignment(A_ONE, 0, anna.getId(), "CONFIRMED"));
		assertEquals(1, image(confirmed, A_ONE).getFaces().size());
		assertEquals(FaceState.CONFIRMED, image(confirmed, A_ONE).getFaces().get(0).getState());
		assertEquals("The old decision is replaced, not kept beside the new one.", 1,
			stored(A_ONE).getTags().size());
	}

	/** A tag naming an id that was merged away answers the person it was merged into. */
	public void testAMergeIsFeltWithoutRewritingTheAlbum() throws Exception {
		createSpace(A_ONE);
		if (!detectorAvailable()) {
			return;
		}
		index();
		Person anna = created("Anna");
		Person other = created("Annie");
		tag(assignment(A_ONE, 0, other.getId(), "CONFIRMED"));
		String before = sidecar();

		assertEquals(200, post("/", "merge-persons",
			"{\"into\":\"" + anna.getId() + "\",\"from\":\"" + other.getId() + "\"}", _adminToken).status());

		assertEquals("No album is rewritten for a merge.", before, sidecar());
		assertEquals("And the tag names the person that is left.", anna.getId(),
			image(album("/" + ALBUM + "/", _adminToken), A_ONE).getFaces().get(0).getPerson());
	}

	// --- Who may. ---

	/** Naming a face needs the edit right, one's own photograph included (issues #123, #53). */
	public void testAContributorMayNotTagTheirOwnPhotograph() throws Exception {
		createSpace(A_ONE);
		if (!detectorAvailable()) {
			return;
		}
		index();
		Person anna = created("Anna");

		// The photograph is bob's own, recorded the way an upload records it.
		File file = new File(album(), A_ONE);
		HashCache hashes = new HashCache(album());
		hashes.put(file, HashCache.sha256(file), new HashCache.Attribution("user:bob", "bob"));
		hashes.flush();

		FakeResponse refused = post("/" + ALBUM + "/", "tag-faces",
			body(assignment(A_ONE, 0, anna.getId(), "CONFIRMED")), BOB_TOKEN);
		assertEquals(refused.body(), 403, refused.status());
		assertEquals(ImageServlet.TAGGING_REFUSED, errorMessage(refused));
		assertEquals("Nothing was written.", 0, stored(A_ONE).getTags().size());

		FakeResponse viewer = post("/" + ALBUM + "/", "tag-faces",
			body(assignment(A_ONE, 0, anna.getId(), "CONFIRMED")), DAVE_TOKEN);
		assertEquals(403, viewer.status());
		assertEquals(ImageServlet.TAGGING_REFUSED, errorMessage(viewer));

		FakeResponse anonymous = post("/" + ALBUM + "/", "tag-faces",
			body(assignment(A_ONE, 0, anna.getId(), "CONFIRMED")), null);
		assertEquals(401, anonymous.status());
	}

	/** A share link neither tags nor is shown a tag. */
	public void testAShareLinkSeesNothingOfThis() throws Exception {
		createSpace(A_ONE, B);
		if (!detectorAvailable()) {
			return;
		}
		index();
		Person anna = created("Anna");
		tag(assignment(A_ONE, 0, anna.getId(), "CONFIRMED"));
		String token = shareToken();

		FakeResponse refused = post("/", "tag-faces",
			body(assignment(A_ONE, 0, anna.getId(), "CONFIRMED")), token);
		assertEquals(403, refused.status());
		assertEquals(ImageServlet.TAGGING_REFUSED, errorMessage(refused));

		FakeResponse listing = get("/", "json", token);
		assertEquals(200, listing.status());
		assertFalse("A link is never shown a decision: " + listing.body(),
			listing.body().contains(anna.getId()));
		assertFalse(listing.body().contains("\"tags\":[{"));
		Resource shown = Resource.readResource(reader(listing.body()));
		for (ImagePart image : images((de.haumacher.imageServer.shared.model.FolderResource) shown)) {
			assertEquals(0, image.getFaces().size());
			assertEquals(0, image.getTags().size());
		}
	}

	/** Previewing one's own album as the public sees it shows no name either (issue #46). */
	public void testThePublicPreviewShowsNoName() throws Exception {
		createSpace(A_ONE);
		if (!detectorAvailable()) {
			return;
		}
		index();
		Person anna = created("Anna");
		tag(assignment(A_ONE, 0, anna.getId(), "CONFIRMED"));

		AlbumInfo asPublic = album("/" + ALBUM + "/", _adminToken, Privacy.VIEW_AS_PUBLIC);
		ImagePart image = image(asPublic, A_ONE);
		assertEquals(0, image.getFaces().size());
		assertEquals("A tag is stored and has to be taken out again.", 0, image.getTags().size());

		AlbumInfo asMember = album("/" + ALBUM + "/", _adminToken, Privacy.VIEW_AS_MEMBERS);
		assertEquals(anna.getId(), image(asMember, A_ONE).getFaces().get(0).getPerson());
	}

	/** A single photograph asked for by itself hides the tags from an anonymous caller too. */
	public void testAnImageOfItsOwnHidesTheTags() throws Exception {
		createSpace(A_ONE);
		if (!detectorAvailable()) {
			return;
		}
		index();
		Person anna = created("Anna");
		tag(assignment(A_ONE, 0, anna.getId(), "CONFIRMED"));

		FakeResponse mine = get("/" + ALBUM + "/" + A_ONE, "json", _adminToken);
		assertTrue(mine.body(), mine.body().contains(anna.getId()));

		FakeResponse anonymous = get("/" + ALBUM + "/" + A_ONE, "json", null);
		assertEquals(200, anonymous.status());
		assertFalse("The image itself must not carry the name either: " + anonymous.body(),
			anonymous.body().contains(anna.getId()));
	}

	// --- Refusals of the request itself. ---

	/** Everything the request names must exist, and every decision must be one. */
	public void testTheRequestIsChecked() throws Exception {
		createSpace(A_ONE);
		if (!detectorAvailable()) {
			return;
		}
		index();
		Person anna = created("Anna");

		FakeResponse image = post("/" + ALBUM + "/", "tag-faces",
			body(assignment("nothing.jpg", 0, anna.getId(), "CONFIRMED")), _adminToken);
		assertEquals(404, image.status());
		assertEquals(ImageServlet.unknownImage("nothing.jpg"), errorMessage(image));

		FakeResponse face = post("/" + ALBUM + "/", "tag-faces",
			body(assignment(A_ONE, 7, anna.getId(), "CONFIRMED")), _adminToken);
		assertEquals(400, face.status());
		assertEquals(ImageServlet.unknownFace(A_ONE, 7), errorMessage(face));

		FakeResponse person = post("/" + ALBUM + "/", "tag-faces",
			body(assignment(A_ONE, 0, "nobody", "CONFIRMED")), _adminToken);
		assertEquals(400, person.status());
		assertEquals(PeopleStore.unknownPerson("nobody"), errorMessage(person));

		FakeResponse nameless = post("/" + ALBUM + "/", "tag-faces",
			body(assignment(A_ONE, 0, "", "CONFIRMED")), _adminToken);
		assertEquals(400, nameless.status());
		assertEquals(ImageServlet.TAG_PERSON_REQUIRED, errorMessage(nameless));

		FakeResponse named = post("/" + ALBUM + "/", "tag-faces",
			body(assignment(A_ONE, 0, anna.getId(), "NOT_A_FACE")), _adminToken);
		assertEquals(400, named.status());
		assertEquals(ImageServlet.TAG_PERSON_REFUSED, errorMessage(named));

		FakeResponse undecided = post("/" + ALBUM + "/", "tag-faces",
			body(assignment(A_ONE, 0, anna.getId(), "UNDECIDED")), _adminToken);
		assertEquals(400, undecided.status());
		assertEquals(ImageServlet.TAG_UNDECIDED, errorMessage(undecided));

		FakeResponse unreadable = post("/" + ALBUM + "/", "tag-faces", "{{{", _adminToken);
		assertEquals(400, unreadable.status());
		assertEquals(ImageServlet.TAG_UNREADABLE, errorMessage(unreadable));

		assertEquals("Not one of these wrote anything.", 0, stored(A_ONE).getTags().size());
	}

	/** One refused assignment refuses the whole request: an album is never half tagged. */
	public void testAllOrNothing() throws Exception {
		createSpace(A_ONE, B);
		if (!detectorAvailable()) {
			return;
		}
		index();
		Person anna = created("Anna");

		FakeResponse response = post("/" + ALBUM + "/", "tag-faces",
			body(assignment(A_ONE, 0, anna.getId(), "CONFIRMED"), assignment(B, 9, anna.getId(), "CONFIRMED")),
			_adminToken);
		assertEquals(400, response.status());
		assertEquals(0, stored(A_ONE).getTags().size());
	}

	// --- Travelling. ---

	/** A photograph carries its decisions into the album it is moved to (issue #47). */
	public void testAMoveCarriesTheTag() throws Exception {
		createSpace(A_ONE, B);
		if (!detectorAvailable()) {
			return;
		}
		index();
		Person anna = created("Anna");
		tag(assignment(A_ONE, 0, anna.getId(), "CONFIRMED"));

		Files.createDirectories(_base.resolve("2024-06-01 Elsewhere"));
		FakeResponse move = post("/" + ALBUM + "/", "move",
			"{\"target\":\"2024-06-01 Elsewhere\",\"names\":[{\"name\":\"" + A_ONE + "\"}]}", _adminToken);
		assertEquals(move.body(), 200, move.status());

		String moved = Files.readString(_base.resolve("2024-06-01 Elsewhere").resolve("index.json"),
			StandardCharsets.UTF_8);
		assertTrue("The decision travels with the photograph: " + moved,
			moved.contains("\"person\":\"" + anna.getId() + "\""));
		assertTrue(moved.contains("\"state\":\"CONFIRMED\""));
	}

	/** Writing the album back the way the application does keeps the decisions. */
	public void testAnAlbumRoundTripKeepsTheTags() throws Exception {
		createSpace(A_ONE, B);
		if (!detectorAvailable()) {
			return;
		}
		index();
		Person anna = created("Anna");
		tag(assignment(A_ONE, 0, anna.getId(), "CONFIRMED"));

		FakeResponse read = get("/" + ALBUM + "/", "json", _adminToken);
		assertTrue("The answer carries the decision.", read.body().contains("\"tags\":[{"));
		FakeResponse written = put("/" + ALBUM + "/", read.body(), _adminToken);
		assertEquals(written.body(), 200, written.status());

		assertEquals("A round trip through the application keeps them.", 1, stored(A_ONE).getTags().size());
		assertEquals(anna.getId(), stored(A_ONE).getTags().get(0).getPerson());
		assertFalse("And still stores no detection.", sidecar().contains("\"cluster\""));
	}

	/** The same for an inbox, whose arrangement the server restores on the way in (issue #131). */
	public void testAnInboxRoundTripKeepsTheTags() throws Exception {
		createSpace(A_ONE, B);
		if (!detectorAvailable()) {
			return;
		}
		index();
		Person anna = created("Anna");
		tag(assignment(A_ONE, 0, anna.getId(), "CONFIRMED"));

		// Make it an inbox, the way the application does: read, change, write. An inbox is named
		// after its title alone (issue #130), so the folder moves and the answer says where to.
		FakeResponse read = get("/" + ALBUM + "/", "json", _adminToken);
		FakeResponse changed = put("/" + ALBUM + "/", read.body().replace("\"kind\":\"ALBUM\"",
			"\"kind\":\"INBOX\""), _adminToken);
		assertEquals(changed.body(), 200, changed.status());
		String inboxPath = CreateResult.readCreateResult(reader(changed.body())).getPath();
		File inbox = _base.resolve(inboxPath).toFile();
		assertEquals(1, storedIn(inbox, A_ONE).getTags().size());

		// An inbox is answered flat and by date; writing that back must keep the decisions.
		FakeResponse answered = get("/" + inboxPath + "/", "json", _adminToken);
		assertTrue(answered.body(), answered.body().contains("\"tags\":[{"));
		assertEquals(200, put("/" + inboxPath + "/", answered.body(), _adminToken).status());

		assertEquals(1, storedIn(inbox, A_ONE).getTags().size());
		assertEquals(anna.getId(), storedIn(inbox, A_ONE).getTags().get(0).getPerson());
	}

	/** Not one byte of an original is ever touched. */
	public void testTheOriginalsAreNeverChanged() throws Exception {
		createSpace(A_ONE, B);
		if (!detectorAvailable()) {
			return;
		}
		index();
		Person anna = created("Anna");
		Map<String, String> before = hashes();

		tag(assignment(A_ONE, 0, anna.getId(), "CONFIRMED"));

		Map<String, String> after = hashes();
		for (Map.Entry<String, String> entry : before.entrySet()) {
			if (entry.getKey().endsWith(".jpg") || entry.getKey().endsWith(".mp4")) {
				assertEquals("'" + entry.getKey() + "' was changed.", entry.getValue(),
					after.get(entry.getKey()));
			}
		}
	}

	// --- The stored form. ---

	/**
	 * A sidecar this build wrote, committed under <code>src/test/fixtures</code>, still reads.
	 *
	 * <p>
	 * The sidecar-format doctrine: what a build once wrote, every later build reads. A change that
	 * breaks this file breaks somebody's library.
	 * </p>
	 */
	public void testTheFixtureOfThisBuildStillReads() throws Exception {
		Resource resource = Resource.readResource(reader(Files.readString(
			new File("src/test/fixtures/face-tags/index.json").toPath(), StandardCharsets.UTF_8)));
		assertTrue("Expected an album, got: " + resource, resource instanceof AlbumInfo);
		AlbumInfo album = (AlbumInfo) resource;

		ImagePart one = image(album, "IMG_1.jpg");
		assertEquals(1, one.getTags().size());
		FaceTag confirmed = one.getTags().get(0);
		assertEquals(FaceState.CONFIRMED, confirmed.getState());
		assertEquals("Km9mQnQ7RgqLxP2hW1a4dQ", confirmed.getPerson());
		assertEquals(0.31, confirmed.getX(), 1e-9);
		assertEquals(0.12, confirmed.getY(), 1e-9);
		assertEquals(0.2, confirmed.getW(), 1e-9);
		assertEquals(0.27, confirmed.getH(), 1e-9);

		ImagePart two = image(album, "IMG_2.jpg");
		assertEquals(1, two.getTags().size());
		assertEquals(FaceState.NOT_A_FACE, two.getTags().get(0).getState());
		assertEquals("A false detection is about nobody.", "", two.getTags().get(0).getPerson());
	}

	// --- Helpers. ---

	private Person created(String name) throws Exception {
		FakeResponse response = post("/", "create-person", "{\"name\":\"" + name + "\"}", _adminToken);
		assertEquals(response.body(), 200, response.status());
		return Person.readPerson(reader(response.body()));
	}

	/** Stores the given decisions and answers the album the server answered. */
	private AlbumInfo tag(String... assignments) throws Exception {
		FakeResponse response = post("/" + ALBUM + "/", "tag-faces", body(assignments), _adminToken);
		assertEquals(response.body(), 200, response.status());
		Resource resource = Resource.readResource(reader(response.body()));
		assertTrue("Expected an album, got: " + resource, resource instanceof AlbumInfo);
		return (AlbumInfo) resource;
	}

	private static String assignment(String image, int face, String person, String state) {
		return "{\"image\":\"" + image + "\",\"face\":" + face + ",\"person\":\"" + person
			+ "\",\"state\":\"" + state + "\"}";
	}

	private static String body(String... assignments) {
		return "{\"faces\":[" + String.join(",", assignments) + "]}";
	}

	/** The image of the given name as the album's own sidecar has it. */
	private ImagePart stored(String name) throws Exception {
		return storedIn(album(), name);
	}

	/** The image of the given name as the sidecar of the given folder has it. */
	private static ImagePart storedIn(File folder, String name) throws Exception {
		String sidecar = sidecarOf(folder);
		if (sidecar.isEmpty()) {
			// Nothing was ever written here, so nothing is stored about this photograph.
			return ImagePart.create().setName(name);
		}
		Resource resource = Resource.readResource(reader(sidecar));
		List<ImagePart> images = new ArrayList<>(images(resource instanceof AlbumInfo
			? (AlbumInfo) resource : AlbumInfo.create()));
		for (ImagePart image : images) {
			if (image.getName().equals(name)) {
				return image;
			}
		}
		fail("No image '" + name + "' in the sidecar: " + sidecar);
		return null;
	}

	private String sidecar() throws Exception {
		return sidecarOf(album());
	}

	private static String sidecarOf(File folder) throws Exception {
		File file = new File(folder, "index.json");
		return file.isFile() ? Files.readString(file.toPath(), StandardCharsets.UTF_8) : "";
	}

	private String hashOf(String name) {
		return new HashCache(album()).storedHashByName().get(name);
	}

	private String shareToken() throws Exception {
		FakeResponse response = post("/" + ALBUM + "/", "share",
			"{\"label\":\"Look\",\"expires\":\"\",\"maxPrivacy\":0,\"minRating\":0,"
				+ "\"rights\":[{\"name\":\"view\"}]}", _adminToken);
		assertEquals(response.body(), 200, response.status());
		return ShareLinkCreated.readShareLinkCreated(reader(response.body())).getToken();
	}
}
