/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.FaceState;
import de.haumacher.imageServer.shared.model.ImagePart;

/**
 * Review probe of the untagging (issue #138), composed with a decision that was about nobody: a
 * "not a face" taken back is a face again.
 */
@SuppressWarnings("javadoc")
public class TestSmallServerProbe extends FacesTestCase {

	public void testANotAFaceTakenBackIsADetectionAgain() throws Exception {
		createSpace(A_ONE, B);
		if (!detectorAvailable()) {
			return;
		}
		index();
		de.haumacher.imageServer.shared.model.FaceInfo face =
			image(album("/" + ALBUM + "/", _adminToken), A_ONE).getFaces().get(0);
		AlbumInfo hidden = tag("{\"image\":\"" + A_ONE + "\",\"face\":0,\"person\":\"\",\"state\":\"NOT_A_FACE\"}");
		// Since issue #155 a face called no face is answered no more; the statement is stored.
		assertEquals(0, image(hidden, A_ONE).getFaces().size());
		assertEquals(1, image(hidden, A_ONE).getTags().size());
		assertEquals(FaceState.NOT_A_FACE, image(hidden, A_ONE).getTags().get(0).getState());

		// Taken back by marking its box again, which meets the hidden detection.
		AlbumInfo back = tag("{\"image\":\"" + A_ONE + "\",\"x\":" + face.getX() + ",\"y\":" + face.getY()
			+ ",\"w\":" + face.getW() + ",\"h\":" + face.getH() + ",\"person\":\"\",\"state\":\"UNDECIDED\"}");

		ImagePart one = image(back, A_ONE);
		assertEquals("The decision is gone from the sidecar.", 0, one.getTags().size());
		assertEquals(FaceState.UNDECIDED, one.getFaces().get(0).getState());
		assertFalse("It is a plain detection again, in its group.", one.getFaces().get(0).getCluster().isEmpty());
	}

	private AlbumInfo tag(String assignment) throws Exception {
		FakeResponse response = post("/" + ALBUM + "/", "tag-faces", "{\"faces\":[" + assignment + "]}", _adminToken);
		assertEquals(response.body(), 200, response.status());
		return (AlbumInfo) de.haumacher.imageServer.shared.model.Resource.readResource(reader(response.body()));
	}
}
