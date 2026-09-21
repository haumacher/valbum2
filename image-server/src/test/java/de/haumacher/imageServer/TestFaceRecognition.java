/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.faces.FaceDetection;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.FaceInfo;
import de.haumacher.imageServer.shared.model.FaceState;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Person;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import java.io.File;
import java.util.Collections;

/**
 * Recognising the people of a space across its albums, see issue #127.
 *
 * <p>
 * Driven with a detector of its own rather than with OpenCV: what is under test is the
 * <em>recognition</em> — which confirmed face makes which unnamed face a suggestion — and that is
 * arithmetic on embeddings, not a question of how well a model finds a nose. A stub that hands out
 * a fixed vector per portrait makes every one of these run on every build machine, where a test
 * needing the real models would be skipped on most of them. The real detector is exercised by the
 * tests of issues #124 and #125 beside this one.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestFaceRecognition extends FacesTestCase {

	/** The second album of the space, somewhere else in the library. */
	private static final String ELSEWHERE = "2024-06-01 Elsewhere";

	/** A third one, for the confirmation that must not revive a rejection. */
	private static final String AGAIN = "2024-07-01 Again";

	private static final int DIMENSIONS = 128;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		// One face per photograph, in the middle of it, described by a vector that depends on the
		// person in it alone -- so the two pictures of "A" are nearly the same face wherever they
		// stand, and "B" and "C" are nobody like them.
		FaceDetection.setDetector(preview -> {
			String name = preview.getName();
			if (name.startsWith(PreviewCache.PREVIEW_PREFIX)) {
				name = name.substring(PreviewCache.PREVIEW_PREFIX.length());
			}
			return new FaceDetection.Result(800, 600, Collections.singletonList(
				new FaceDetection.Detected(300, 200, 200, 200, 0.99, embeddingOf(name))));
		});
	}

	// --- The suggestion. ---

	/** A person confirmed in one album is recognised in another, and nobody else is. */
	public void testAConfirmationInOneAlbumIsASuggestionInAnother() throws Exception {
		createSpace(A_ONE);
		addAlbum(ELSEWHERE, A_TWO, B);
		index();
		Person anna = created("Anna");

		assertEquals("Nothing is suggested while nobody is confirmed.", "",
			suggestion(face(ELSEWHERE, A_TWO)));

		confirm(ALBUM, A_ONE, anna);

		assertEquals("The same person elsewhere is suggested.", anna.getId(),
			suggestion(face(ELSEWHERE, A_TWO)));
		FaceInfo suggested = face(ELSEWHERE, A_TWO);
		assertFalse("A suggestion is never a confirmation.", suggested.isConfirmed());
		assertEquals("And nothing was decided about it.", FaceState.UNDECIDED, suggested.getState());

		assertEquals("Somebody else is still nobody.", "", suggestion(face(ELSEWHERE, B)));
		assertEquals("", face(ELSEWHERE, B).getPerson());
	}

	/** The face that was confirmed is that person; it is not "suggested" as them. */
	public void testAConfirmedFaceIsNotASuggestion() throws Exception {
		createSpace(A_ONE);
		index();
		Person anna = created("Anna");
		confirm(ALBUM, A_ONE, anna);

		FaceInfo face = face(ALBUM, A_ONE);
		assertEquals(anna.getId(), face.getPerson());
		assertTrue(face.isConfirmed());
		assertEquals(FaceState.CONFIRMED, face.getState());
		assertEquals("Which is not a guess.", "", suggestion(face));
	}

	/** Nothing of this is written to a sidecar: a suggestion is derived, every time. */
	public void testASuggestionIsNeverStored() throws Exception {
		createSpace(A_ONE);
		addAlbum(ELSEWHERE, A_TWO);
		index();
		Person anna = created("Anna");
		confirm(ALBUM, A_ONE, anna);

		assertEquals(anna.getId(), suggestion(face(ELSEWHERE, A_TWO)));
		assertFalse("The album it was suggested in was never written.",
			new File(_base.resolve(ELSEWHERE).toFile(), "index.json").isFile());
	}

	// --- The feedback. ---

	/** A rejection ends the suggestion for that face, and no later confirmation revives it. */
	public void testARejectionIsFinalForThatFace() throws Exception {
		createSpace(A_ONE);
		addAlbum(ELSEWHERE, A_TWO);
		addAlbum(AGAIN, A_ONE);
		index();
		Person anna = created("Anna");
		confirm(ALBUM, A_ONE, anna);
		assertEquals(anna.getId(), suggestion(face(ELSEWHERE, A_TWO)));

		tag(ELSEWHERE, assignment(A_TWO, 0, anna.getId(), "REJECTED"));

		FaceInfo rejected = face(ELSEWHERE, A_TWO);
		assertEquals("Nobody is suggested for a face somebody decided about.", "", suggestion(rejected));
		assertEquals(FaceState.REJECTED, rejected.getState());
		assertFalse(rejected.isConfirmed());

		// Anna is confirmed once more, somewhere else again: the refusal is pinned by the stored
		// decision and no number brings it back.
		confirm(AGAIN, A_ONE, anna);
		assertEquals("Anna is described by two faces now.", anna.getId(), face(AGAIN, A_ONE).getPerson());
		assertEquals("Still nobody.", "", suggestion(face(ELSEWHERE, A_TWO)));
		assertEquals(FaceState.REJECTED, face(ELSEWHERE, A_TWO).getState());
	}

	/** A face somebody said is no face at all is never matched against anybody. */
	public void testANotAFaceIsNeverSuggested() throws Exception {
		createSpace(A_ONE);
		addAlbum(ELSEWHERE, A_TWO);
		index();
		Person anna = created("Anna");
		tag(ELSEWHERE, assignment(A_TWO, 0, "", "NOT_A_FACE"));
		confirm(ALBUM, A_ONE, anna);

		FaceInfo none = face(ELSEWHERE, A_TWO);
		assertEquals(FaceState.NOT_A_FACE, none.getState());
		assertEquals("", none.getPerson());
		assertEquals("", suggestion(none));
	}

	/** A confirmation taken back takes its prototype with it. */
	public void testAConfirmationTakenBackStopsTheSuggestion() throws Exception {
		createSpace(A_ONE);
		addAlbum(ELSEWHERE, A_TWO);
		index();
		Person anna = created("Anna");
		confirm(ALBUM, A_ONE, anna);
		assertEquals(anna.getId(), suggestion(face(ELSEWHERE, A_TWO)));

		// The one decision Anna was described by becomes a rejection; she is described by nothing
		// any more.
		tag(ALBUM, assignment(A_ONE, 0, anna.getId(), "REJECTED"));
		assertEquals("", suggestion(face(ELSEWHERE, A_TWO)));
	}

	/** Throwing the album cache away takes the prototypes read from it with it (issue #98). */
	public void testRefreshingTheCacheForgetsThePrototypes() throws Exception {
		createSpace(A_ONE);
		addAlbum(ELSEWHERE, A_TWO);
		index();
		Person anna = created("Anna");
		confirm(ALBUM, A_ONE, anna);
		assertEquals(anna.getId(), suggestion(face(ELSEWHERE, A_TWO)));

		assertEquals(200, post("/" + ALBUM + "/", "refresh-cache", "{}", _adminToken).status());
		assertEquals("The numbers Anna was described by are gone.", "",
			suggestion(face(ELSEWHERE, A_TWO)));
		assertEquals("The decision is not.", FaceState.CONFIRMED, face(ALBUM, A_ONE).getState());

		index();
		assertEquals("And the next pass describes her again.", anna.getId(),
			suggestion(face(ELSEWHERE, A_TWO)));
	}

	// --- Across a restart and across a merge. ---

	/** The prototypes are rebuilt from the sidecars and the album caches, and say the same. */
	public void testARestartRebuildsThePrototypes() throws Exception {
		createSpace(A_ONE);
		addAlbum(ELSEWHERE, A_TWO);
		index();
		Person anna = created("Anna");
		confirm(ALBUM, A_ONE, anna);
		assertEquals(anna.getId(), suggestion(face(ELSEWHERE, A_TWO)));

		restart();
		assertTrue("Nothing is remembered across a restart.",
			_servlet.faces().recognition().isEmpty());
		index();

		assertEquals("Read back from what is on disk.", 1, _servlet.faces().recognition().size());
		assertEquals(anna.getId(), suggestion(face(ELSEWHERE, A_TWO)));
	}

	/** Merging two people makes the faces of both count for the survivor (issue #125). */
	public void testAMergeMakesBothPeopleCount() throws Exception {
		createSpace(A_ONE);
		addAlbum(ELSEWHERE, A_TWO);
		index();
		Person anna = created("Anna");
		Person annie = created("Annie");
		confirm(ALBUM, A_ONE, annie);
		assertEquals(annie.getId(), suggestion(face(ELSEWHERE, A_TWO)));

		assertEquals(200, post("/", "merge-persons",
			"{\"into\":\"" + anna.getId() + "\",\"from\":\"" + annie.getId() + "\"}", _adminToken)
				.status());

		assertEquals("Annie's confirmed face now recognises Anna.", anna.getId(),
			suggestion(face(ELSEWHERE, A_TWO)));
	}

	// --- Who is answered one. ---

	/** A suggestion is a face, and a face is for the members of the space alone (issue #124). */
	public void testNobodyButAMemberIsSuggestedAnybody() throws Exception {
		createSpace(A_ONE);
		addAlbum(ELSEWHERE, A_TWO);
		index();
		Person anna = created("Anna");
		confirm(ALBUM, A_ONE, anna);
		assertEquals(anna.getId(), suggestion(face(ELSEWHERE, A_TWO)));

		FakeResponse anonymous = get("/" + ELSEWHERE + "/", "json", null);
		assertEquals(200, anonymous.status());
		assertFalse("An anonymous visitor is told nothing: " + anonymous.body(),
			anonymous.body().contains(anna.getId()));
		assertNoFaces(anonymous);

		FakeResponse shared = get("/", "json", shareToken());
		assertEquals(200, shared.status());
		assertFalse("And neither is a share link: " + shared.body(),
			shared.body().contains(anna.getId()));
		assertNoFaces(shared);

		AlbumInfo asPublic = album("/" + ELSEWHERE + "/", _adminToken, Privacy.VIEW_AS_PUBLIC);
		assertEquals("Nor the author previewing the public view.", 0,
			image(asPublic, A_TWO).getFaces().size());

		AlbumInfo asMember = album("/" + ELSEWHERE + "/", _adminToken, Privacy.VIEW_AS_MEMBERS);
		assertEquals(anna.getId(), suggestion(image(asMember, A_TWO).getFaces().get(0)));
	}

	// --- And the same with the real models. ---

	/**
	 * The acceptance of issue #127, with the bundled detector and three real portraits.
	 *
	 * <p>
	 * Everything above is arithmetic this test cannot add anything to; what it adds is that the
	 * numbers the bundled recogniser really produces for two photographs of one person are close
	 * enough for the threshold, and the ones for two different people are not. A machine without
	 * OpenCV skips it, loudly.
	 * </p>
	 */
	public void testTheRealModelsRecogniseTheRealPortraits() throws Exception {
		FaceDetection.setDetector(null);
		if (!detectorAvailable()) {
			return;
		}
		createSpace(A_ONE);
		addAlbum(ELSEWHERE, A_TWO, B);
		index();
		Person anna = created("Anna");

		confirm(ALBUM, A_ONE, anna);

		assertEquals("The same person in another album.", anna.getId(),
			suggestion(face(ELSEWHERE, A_TWO)));
		assertEquals("Somebody else is nobody.", "", suggestion(face(ELSEWHERE, B)));
	}

	// --- Helpers. ---

	private static void assertNoFaces(FakeResponse response) throws Exception {
		Resource shown = Resource.readResource(reader(response.body()));
		for (ImagePart image : images((FolderResource) shown)) {
			assertEquals(0, image.getFaces().size());
			assertEquals(0, image.getTags().size());
		}
	}

	/** Whom the server suggests for the given face, the empty string when it suggests nobody. */
	private static String suggestion(FaceInfo face) {
		if (face.getState() != FaceState.UNDECIDED || face.isConfirmed()) {
			return "";
		}
		return face.getPerson();
	}

	/** The first face of the given photograph of the given album, as the administrator sees it. */
	private FaceInfo face(String folder, String name) throws Exception {
		ImagePart image = image(album("/" + folder + "/", _adminToken), name);
		assertFalse("No face in '" + folder + "/" + name + "'.", image.getFaces().isEmpty());
		return image.getFaces().get(0);
	}

	private Person created(String name) throws Exception {
		FakeResponse response = post("/", "create-person", "{\"name\":\"" + name + "\"}", _adminToken);
		assertEquals(response.body(), 200, response.status());
		return Person.readPerson(reader(response.body()));
	}

	private void confirm(String folder, String name, Person person) throws Exception {
		tag(folder, assignment(name, 0, person.getId(), "CONFIRMED"));
	}

	private void tag(String folder, String... assignments) throws Exception {
		FakeResponse response = post("/" + folder + "/", "tag-faces",
			"{\"faces\":[" + String.join(",", assignments) + "]}", _adminToken);
		assertEquals(response.body(), 200, response.status());
	}

	private static String assignment(String image, int face, String person, String state) {
		return "{\"image\":\"" + image + "\",\"face\":" + face + ",\"person\":\"" + person
			+ "\",\"state\":\"" + state + "\"}";
	}

	private String shareToken() throws Exception {
		FakeResponse response = post("/" + ELSEWHERE + "/", "share",
			"{\"label\":\"Look\",\"expires\":\"\",\"maxPrivacy\":0,\"minRating\":0,"
				+ "\"rights\":[{\"name\":\"view\"}]}", _adminToken);
		assertEquals(response.body(), 200, response.status());
		return ShareLinkCreated.readShareLinkCreated(reader(response.body())).getToken();
	}

	// --- The synthetic faces. ---

	/**
	 * The vector the stub describes the person in the given portrait with.
	 *
	 * <p>
	 * The two pictures of "A" are 0.95 alike, which is what two photographs of one person score;
	 * "B" and "C" are orthogonal to them and to each other, which is what two different people
	 * score.
	 * </p>
	 */
	private static float[] embeddingOf(String name) {
		float[] result = new float[DIMENSIONS];
		if (A_ONE.equals(name)) {
			result[0] = 1;
		} else if (A_TWO.equals(name)) {
			result[0] = 0.95f;
			result[1] = (float) Math.sqrt(1 - 0.95 * 0.95);
		} else if (B.equals(name)) {
			result[2] = 1;
		} else {
			result[3] = 1;
		}
		return result;
	}
}
