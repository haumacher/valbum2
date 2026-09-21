/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.faces.FaceDetection;
import de.haumacher.imageServer.shared.model.FaceInfo;
import de.haumacher.imageServer.shared.model.FaceState;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Person;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

/**
 * Review probes of the recognition (issue #127), composed with the moves of #47 and the
 * single-image delete of #131: a confirmed face that travels is still that person, and one that is
 * thrown away stops vouching for anybody.
 */
@SuppressWarnings("javadoc")
public class TestFaceRecognitionProbe extends FacesTestCase {

	private static final String ELSEWHERE = "2024-06-01 Elsewhere";

	private static final String AGAIN = "2024-07-01 Again";

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		FaceDetection.setDetector(preview -> {
			String name = preview.getName();
			if (name.startsWith(PreviewCache.PREVIEW_PREFIX)) {
				name = name.substring(PreviewCache.PREVIEW_PREFIX.length());
			}
			return new FaceDetection.Result(800, 600, Collections.singletonList(
				new FaceDetection.Detected(300, 200, 200, 200, 0.99, embeddingOf(name))));
		});
	}

	public void testAnUndecidedFaceMovedIntoAnotherAlbumIsSuggestedThere() throws Exception {
		createSpace(A_ONE);
		addAlbum(ELSEWHERE, A_TWO, B);
		index();
		Person anna = created("Anna");
		confirm(ALBUM, A_ONE, anna);
		assertEquals(anna.getId(), suggestion(face(ELSEWHERE, A_TWO)));

		Path again = _base.resolve(AGAIN);
		Files.createDirectories(again);
		Files.write(again.resolve("index.json"),
			"[\"AlbumInfo\",{\"title\":\"Again\",\"parts\":[]}]".getBytes(StandardCharsets.UTF_8));
		FakeResponse moved = post("/" + ELSEWHERE + "/", "move",
			"{\"target\":\"" + AGAIN + "\",\"names\":[{\"name\":\"" + A_TWO + "\"}]}", _adminToken);
		assertEquals(moved.body(), 200, moved.status());

		if (album("/" + AGAIN + "/", _adminToken).isFacesPending()) {
			index();
		}
		assertEquals("The face is suggested where the photograph is now.", anna.getId(),
			suggestion(face(AGAIN, A_TWO)));
		assertEquals("And nothing changed about the other one.", "", suggestion(face(ELSEWHERE, B)));
	}

	public void testAConfirmedFaceThrownAwayStopsVouching() throws Exception {
		createSpace(A_ONE);
		addAlbum(ELSEWHERE, A_TWO);
		index();
		Person anna = created("Anna");
		confirm(ALBUM, A_ONE, anna);
		assertEquals(anna.getId(), suggestion(face(ELSEWHERE, A_TWO)));

		FakeResponse deleted = post("/" + ALBUM + "/", "delete",
			"{\"target\":\"\",\"names\":[{\"name\":\"" + A_ONE + "\"}]}", _adminToken);
		assertEquals(deleted.body(), 200, deleted.status());

		// The album the confirmation lived in is described afresh when it is listed.
		album("/" + ALBUM + "/", _adminToken);
		assertEquals("The only confirmed face of Anna is in the trash: nobody is suggested her.", "",
			suggestion(face(ELSEWHERE, A_TWO)));
	}

	// --- Helpers. ---

	private static String suggestion(FaceInfo face) {
		if (face.getState() != FaceState.UNDECIDED || face.isConfirmed()) {
			return "";
		}
		return face.getPerson();
	}

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
		FakeResponse response = post("/" + folder + "/", "tag-faces",
			"{\"faces\":[{\"image\":\"" + name + "\",\"face\":0,\"person\":\"" + person.getId()
				+ "\",\"state\":\"CONFIRMED\"}]}", _adminToken);
		assertEquals(response.body(), 200, response.status());
	}

	private static float[] embeddingOf(String name) {
		float[] result = new float[128];
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
