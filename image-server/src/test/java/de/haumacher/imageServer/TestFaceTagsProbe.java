/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.FaceState;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Person;
import de.haumacher.imageServer.shared.model.Resource;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Review probes of the face tags (issue #125), composed with a merge that happened before a tag,
 * with the single-image delete of #131 and with a decision that is taken back.
 */
@SuppressWarnings("javadoc")
public class TestFaceTagsProbe extends FacesTestCase {

	/** A tag naming a person that was merged away is a tag naming the survivor. */
	public void testATagNamingAMergedAwayPersonNamesTheSurvivor() throws Exception {
		createSpace(A_ONE, B);
		if (!detectorAvailable()) {
			return;
		}
		index();
		Person anna = created("Anna");
		Person annie = created("Annie");
		assertEquals(200, post("/", "merge-persons",
			"{\"into\":\"" + anna.getId() + "\",\"from\":\"" + annie.getId() + "\"}", _adminToken).status());

		AlbumInfo album = tag(assignment(A_ONE, 0, annie.getId(), "CONFIRMED"));

		assertEquals("Answered as the person that is left.", anna.getId(),
			image(album, A_ONE).getFaces().get(0).getPerson());
		assertTrue(image(album, A_ONE).getFaces().get(0).isConfirmed());
	}

	/** A photograph thrown away carries its tag into the trash album, like any other statement about it. */
	public void testATaggedPhotographKeepsItsTagInTheTrash() throws Exception {
		createSpace(A_ONE, B);
		if (!detectorAvailable()) {
			return;
		}
		index();
		Person anna = created("Anna");
		tag(assignment(A_ONE, 0, anna.getId(), "CONFIRMED"));

		FakeResponse deleted = post("/" + ALBUM + "/", "delete",
			"{\"target\":\"\",\"names\":[{\"name\":\"" + A_ONE + "\"}]}", _adminToken);
		assertEquals(deleted.body(), 200, deleted.status());

		File trashAlbum = _base.resolve(".valbum").resolve(DeleteService.TRASH_FOLDER).resolve(ALBUM).toFile();
		assertTrue(new File(trashAlbum, A_ONE).isFile());
		String sidecar = new String(Files.readAllBytes(new File(trashAlbum, "index.json").toPath()),
			StandardCharsets.UTF_8);
		assertTrue("The tag rides along: " + sidecar, sidecar.contains(anna.getId()));
		assertTrue(sidecar.contains("CONFIRMED"));
	}

	/** A decision is replaced by the next one on the same face, in either direction. */
	public void testTheLaterDecisionOnAFaceReplacesTheEarlierOne() throws Exception {
		createSpace(A_ONE);
		if (!detectorAvailable()) {
			return;
		}
		index();
		Person anna = created("Anna");
		tag(assignment(A_ONE, 0, anna.getId(), "CONFIRMED"));
		AlbumInfo album = tag(assignment(A_ONE, 0, "", "NOT_A_FACE"));

		ImagePart one = image(album, A_ONE);
		assertEquals("One tag, the later one.", 1, one.getTags().size());
		assertEquals(FaceState.NOT_A_FACE, one.getTags().get(0).getState());
		assertEquals("And the face is answered no more (issue #155).", 0, one.getFaces().size());
	}

	// --- Helpers. ---

	private Person created(String name) throws Exception {
		FakeResponse response = post("/", "create-person", "{\"name\":\"" + name + "\"}", _adminToken);
		assertEquals(response.body(), 200, response.status());
		return Person.readPerson(reader(response.body()));
	}

	private AlbumInfo tag(String... assignments) throws Exception {
		FakeResponse response = post("/" + ALBUM + "/", "tag-faces",
			"{\"faces\":[" + String.join(",", assignments) + "]}", _adminToken);
		assertEquals(response.body(), 200, response.status());
		Resource resource = Resource.readResource(reader(response.body()));
		assertTrue("Expected an album, got: " + resource, resource instanceof AlbumInfo);
		return (AlbumInfo) resource;
	}

	private static String assignment(String image, int face, String person, String state) {
		return "{\"image\":\"" + image + "\",\"face\":" + face + ",\"person\":\"" + person
			+ "\",\"state\":\"" + state + "\"}";
	}
}
