/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.ImagePart;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Review probes of the face index (issue #124), composed with what the package did not look at: the
 * author's own preview of what the public sees, and a photograph moved into another album.
 */
@SuppressWarnings("javadoc")
public class TestFacesProbe extends FacesTestCase {

	/** A member previewing the album as the public sees it is shown what the public is shown: no face. */
	public void testTheAuthorsPreviewAsPublicShowsNoFace() throws Exception {
		createSpace(A_ONE, B);
		if (!detectorAvailable()) {
			return;
		}
		index();
		assertEquals("The member sees the face.", 1, image(album("/" + ALBUM + "/", _adminToken), A_ONE).getFaces().size());

		Map<String, String> asPublic = new HashMap<>();
		asPublic.put("viewAs", "public");
		FakeResponse response = get("/" + ALBUM + "/", "json", _adminToken, asPublic);
		assertEquals(response.body(), 200, response.status());
		AlbumInfo album = (AlbumInfo) de.haumacher.imageServer.shared.model.Resource.readResource(reader(response.body()));
		for (ImagePart image : images(album)) {
			assertEquals("The preview of the public view shows what the public sees: no face on " + image.getName(),
				0, image.getFaces().size());
		}

		Map<String, String> crop = new HashMap<>(asPublic);
		crop.put("face", "0");
		assertEquals("Nor the crop.", 404, get("/" + ALBUM + "/" + A_ONE, "face", _adminToken, crop).status());
	}

	/** A photograph moved into another album takes its face along by hash and is grouped there anew. */
	public void testAMovedPhotographIsIndexedWhereItIsNow() throws Exception {
		createSpace(A_ONE, A_TWO, B);
		if (!detectorAvailable()) {
			return;
		}
		index();

		String other = "2024-06-01 Other";
		Path target = _base.resolve(other);
		Files.createDirectories(target);
		Files.write(target.resolve("index.json"),
			"[\"AlbumInfo\",{\"title\":\"Other\",\"parts\":[]}]".getBytes(StandardCharsets.UTF_8));

		FakeResponse moved = post("/" + ALBUM + "/", "move",
			"{\"target\":\"" + other + "\",\"names\":[{\"name\":\"" + A_ONE + "\"}]}", _adminToken);
		assertEquals(moved.body(), 200, moved.status());
		assertTrue("The file went.", target.resolve(A_ONE).toFile().isFile());

		AlbumInfo arrived = album("/" + other + "/", _adminToken);
		if (arrived.isFacesPending()) {
			index();
			arrived = album("/" + other + "/", _adminToken);
		}
		assertFalse(arrived.isFacesPending());
		assertEquals("The face is answered where the photograph is now.", 1, image(arrived, A_ONE).getFaces().size());
		assertFalse(image(arrived, A_ONE).getFaces().get(0).getCluster().isEmpty());

		AlbumInfo left = album("/" + ALBUM + "/", _adminToken);
		assertFalse(left.isFacesPending());
		assertEquals(1, image(left, A_TWO).getFaces().size());
		assertEquals(1, image(left, B).getFaces().size());
		assertFalse("Two different people are two groups.",
			image(left, A_TWO).getFaces().get(0).getCluster().equals(image(left, B).getFaces().get(0).getCluster()));
	}
}
