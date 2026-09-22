/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.faces.FaceCache;
import de.haumacher.imageServer.faces.FaceDetection;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.FaceInfo;
import de.haumacher.imageServer.shared.model.ImagePart;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Probe of issue #147 composed with #125, #47 and a thrown-away cache: a box drawn where the
 * detector found nothing, a second box over that hand-made tag, the photograph moved elsewhere,
 * and the face cache deleted underneath.
 */
@SuppressWarnings("javadoc")
public class TestFaceMarkProbe extends FacesTestCase {

	private static final String PHOTO = "plain.jpg";

	private static final String ELSEWHERE = "2024-06-01 Elsewhere";

	/** One detected face at a fixed place. */
	private static final double[] DETECTED = { 0.10, 0.10, 0.20, 0.30 };

	/** Where a hand is drawn, far from the detection. */
	private static final double[] DRAWN = { 0.70, 0.60, 0.15, 0.20 };

	/** Nearly the same box as {@link #DRAWN}: the same face by the IoU rule. */
	private static final double[] REDRAWN = { 0.71, 0.61, 0.15, 0.20 };

	private static final class Fixed implements FaceDetection.Detector {
		@Override
		public FaceDetection.Result detect(File preview) throws IOException {
			BufferedImage image = ImageIO.read(preview);
			int width = image.getWidth(), height = image.getHeight();
			float[] embedding = new float[128];
			embedding[0] = 1;
			List<FaceDetection.Detected> found = new ArrayList<>();
			found.add(new FaceDetection.Detected(DETECTED[0] * width, DETECTED[1] * height,
				DETECTED[2] * width, DETECTED[3] * height, 0.99, embedding));
			return new FaceDetection.Result(width, height, found);
		}
	}

	public void testAHandMarkedFaceOutlivesARedrawAMoveAndTheCache() throws Exception {
		FaceDetection.setDetector(new Fixed());
		createSpace();
		plain(PHOTO);
		addAlbum(ELSEWHERE);
		index();
		String anna = createPerson("Anna");
		String bob = createPerson("Bob");

		// Drawn where nothing was detected: a face of its own behind the detection.
		mark("/" + ALBUM + "/", PHOTO, DRAWN, anna, 200);
		AlbumInfo album = album("/" + ALBUM + "/", _adminToken);
		List<FaceInfo> faces = image(album, PHOTO).getFaces();
		assertEquals(faces.toString(), 2, faces.size());
		FaceInfo hand = faces.get(1);
		assertEquals(anna, hand.getPerson());
		assertEquals("", hand.getCluster());
		assertEquals(DRAWN[0], hand.getX(), 0.005);

		// Drawn again over the hand-made tag: that face's decision, not a third face.
		mark("/" + ALBUM + "/", PHOTO, REDRAWN, bob, 200);
		album = album("/" + ALBUM + "/", _adminToken);
		faces = image(album, PHOTO).getFaces();
		assertEquals(faces.toString(), 2, faces.size());
		assertEquals(bob, faces.get(1).getPerson());
		assertEquals(1, image(album, PHOTO).getTags().size());

		// Moved elsewhere: the tag rides along and is answered there.
		FakeResponse move = post("/" + ALBUM + "/", "move",
			"{\"target\":\"" + ELSEWHERE + "\",\"names\":[{\"name\":\"" + PHOTO + "\"}]}", _adminToken);
		assertEquals(move.body(), 200, move.status());
		AlbumInfo there = album("/" + ELSEWHERE + "/", _adminToken);
		ImagePart moved = image(there, PHOTO);
		assertEquals(1, moved.getTags().size());
		boolean bobThere = false;
		for (FaceInfo face : moved.getFaces()) {
			bobThere |= bob.equals(face.getPerson()) && Math.abs(face.getX() - DRAWN[0]) < 0.02;
		}
		assertTrue("The hand-made face is answered where it was moved to: " + moved.getFaces(), bobThere);

		// The cache thrown away: the decision stays, and a forget by the box removes it.
		File cache = FaceCache.file(new File(_base.toFile(), ELSEWHERE));
		if (cache.exists()) {
			assertTrue(cache.delete());
		}
		there = album("/" + ELSEWHERE + "/", _adminToken);
		assertEquals(bob, image(there, PHOTO).getFaces().get(image(there, PHOTO).getFaces().size() - 1).getPerson());
		mark("/" + ELSEWHERE + "/", PHOTO, REDRAWN, "", "UNDECIDED", 200);
		there = album("/" + ELSEWHERE + "/", _adminToken);
		assertEquals(0, image(there, PHOTO).getTags().size());
	}

	private void mark(String pathInfo, String image, double[] box, String person, int expected) throws Exception {
		mark(pathInfo, image, box, person, "CONFIRMED", expected);
	}

	private void mark(String pathInfo, String image, double[] box, String person, String state, int expected)
			throws Exception {
		String body = "{\"faces\":[{\"image\":\"" + image + "\",\"x\":" + box[0] + ",\"y\":" + box[1]
			+ ",\"w\":" + box[2] + ",\"h\":" + box[3] + ",\"person\":\"" + person
			+ "\",\"state\":\"" + state + "\"}]}";
		FakeResponse response = post(pathInfo, "tag-faces", body, _adminToken);
		assertEquals(response.body(), expected, response.status());
	}

	private String createPerson(String name) throws Exception {
		FakeResponse response = post("/", "create-person", "{\"name\":\"" + name + "\"}", _adminToken);
		assertEquals(response.body(), 200, response.status());
		return de.haumacher.imageServer.shared.model.Person.readPerson(reader(response.body())).getId();
	}

	private File plain(String name) throws Exception {
		BufferedImage shown = new BufferedImage(1600, 1200, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = shown.createGraphics();
		try {
			graphics.setColor(new Color(0x40, 0x80, 0xC0));
			graphics.fillRect(0, 0, 1600, 1200);
			graphics.setColor(Color.ORANGE);
			graphics.fillRect(160, 120, 320, 360);
		} finally {
			graphics.dispose();
		}
		File target = new File(album(), name);
		assertTrue(ImageIO.write(shown, "jpg", target));
		return target;
	}
}
