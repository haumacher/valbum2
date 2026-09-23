/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.faces.FaceDetection;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.FaceInfo;
import de.haumacher.imageServer.shared.model.FaceState;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Person;
import de.haumacher.imageServer.shared.model.Resource;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Probe of issue #155, composing the marked regions and the new forget/not-a-face rules with what
 * stood before them: the face crop of #124/#141, the author's own <code>?viewAs</code> preview of
 * #96, the recognition of #127 and the cache refresh of #98.
 */
@SuppressWarnings("javadoc")
public class TestFaceRegionsProbe extends FacesTestCase {

	private static final String PHOTO = "photo.jpg";

	private static final int WIDTH = 1600;

	private static final int HEIGHT = 1200;

	/** The one face the stub detects on the preview, in fractions of the picture. */
	private static final double[] DETECTED = { 0.10, 0.10, 0.20, 0.30 };

	/** A region somebody marks by hand, away from the detection. */
	private static final double[] MARKED = { 0.60, 0.55, 0.10, 0.12 };

	private static final double FOUND_PIXELS = 80;

	private static final class Stub implements FaceDetection.Detector {
		boolean _finds = true;

		@Override
		public FaceDetection.Result detect(File preview) throws IOException {
			BufferedImage image = ImageIO.read(preview);
			if (image == null) {
				throw new IOException("Cannot read '" + preview + "'.");
			}
			int width = image.getWidth();
			int height = image.getHeight();
			List<FaceDetection.Detected> found = new ArrayList<>();
			if (preview.getName().contains(PHOTO)) {
				found.add(new FaceDetection.Detected(DETECTED[0] * width, DETECTED[1] * height,
					DETECTED[2] * width, DETECTED[3] * height, 0.99, embedding(0)));
			}
			return new FaceDetection.Result(width, height, found);
		}

		@Override
		public FaceDetection.Refined search(BufferedImage region, double x, double y, double w, double h) {
			if (!_finds) {
				return null;
			}
			return new FaceDetection.Refined(x + w / 2 - FOUND_PIXELS / 2, y + h / 2 - FOUND_PIXELS / 2,
				FOUND_PIXELS, FOUND_PIXELS, embedding(1));
		}
	}

	private Stub _detector;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_detector = new Stub();
		FaceDetection.setDetector(_detector);
	}

	/** A detection hidden by "not a face" is gone from the answer — and from the crop endpoint too. */
	public void testAHiddenDetectionServesNoCrop() throws Exception {
		createSpace();
		photograph(PHOTO);
		index();
		assertEquals(200, face(PHOTO, 0).status());

		tag(assignment(PHOTO, 0, "", "NOT_A_FACE"));

		ImagePart image = image(album("/" + ALBUM + "/", _adminToken), PHOTO);
		assertTrue("Hidden from the answer: " + image.getFaces(), image.getFaces().isEmpty());
		FakeResponse crop = face(PHOTO, 0);
		assertEquals("A face nobody is answered has no crop either, and 404 says so — not 403, which would say there is something to refuse.",
			404, crop.status());
	}

	/**
	 * A hand-marked region the detector found nothing in and nobody has decided about is an
	 * undecided region for the members, and nothing at all for the public — the author's own
	 * preview included (#96) — and it never becomes a prototype of the recognition.
	 */
	public void testAnUndecidedRegionIsTheMembersBusinessAlone() throws Exception {
		createSpace();
		photograph(PHOTO);
		index();
		_detector._finds = false;

		AlbumInfo answer = mark(PHOTO, MARKED, "", "UNDECIDED");
		ImagePart marked = image(answer, PHOTO);
		assertEquals("The detection and the region.", 2, marked.getFaces().size());
		FaceInfo region = marked.getFaces().get(1);
		assertEquals(FaceState.UNDECIDED, region.getState());
		assertEquals("", region.getPerson());
		assertTrue("No detection, no cluster.", region.getCluster().isEmpty());

		ImagePart asPublic = image(album("/" + ALBUM + "/", _adminToken, "public"), PHOTO);
		assertTrue("The public is answered no face, an undecided region included.", asPublic.getFaces().isEmpty());
		ImagePart asMember = image(album("/" + ALBUM + "/", _adminToken, "members"), PHOTO);
		assertEquals("A member is answered the region.", 2, asMember.getFaces().size());

		// The detection is confirmed as somebody: the recognition learns from it and must not
		// trip over the region that carries no embedding.
		Person somebody = created("Somebody");
		AlbumInfo confirmed = tag(assignment(PHOTO, 0, somebody.getId(), "CONFIRMED"));
		ImagePart after = image(confirmed, PHOTO);
		assertEquals(somebody.getId(), after.getFaces().get(0).getPerson());
		assertEquals("The region is still undecided and suggested nobody: it has no embedding to compare.",
			"", after.getFaces().get(1).getPerson());
		assertEquals(FaceState.UNDECIDED, after.getFaces().get(1).getState());
	}

	/**
	 * The cache refresh of #98 drops <code>faces.json</code>: a face found by a mark and left
	 * undecided goes with it (it is cache), while a mark that was confirmed survives as its tag and
	 * is still answered — with a crop cut from the original.
	 */
	public void testARefreshKeepsAConfirmedMarkAndForgetsAnUndecidedOne() throws Exception {
		createSpace();
		photograph(PHOTO);
		index();
		Person somebody = created("Somebody");

		AlbumInfo found = mark(PHOTO, MARKED, "", "UNDECIDED");
		assertEquals(2, image(found, PHOTO).getFaces().size());
		AlbumInfo confirmed = tag(assignment(PHOTO, 1, somebody.getId(), "CONFIRMED"));
		assertEquals(somebody.getId(), image(confirmed, PHOTO).getFaces().get(1).getPerson());
		double[] second = { 0.30, 0.60, 0.10, 0.12 };
		AlbumInfo foundAgain = mark(PHOTO, second, "", "UNDECIDED");
		assertEquals("The detection, the confirmed mark and the undecided one.", 3,
			image(foundAgain, PHOTO).getFaces().size());

		FakeResponse refreshed = post("/" + ALBUM + "/", "refresh-cache", "{}", _adminToken);
		assertEquals(refreshed.body(), 200, refreshed.status());
		index();

		ImagePart image = image(album("/" + ALBUM + "/", _adminToken), PHOTO);
		List<String> people = new ArrayList<>();
		for (FaceInfo face : image.getFaces()) {
			people.add(face.getPerson());
		}
		assertTrue("The confirmed mark is a stored statement and is still answered: " + people,
			people.contains(somebody.getId()));
		int confirmedIndex = people.indexOf(somebody.getId());
		FaceInfo kept = image.getFaces().get(confirmedIndex);
		assertEquals(FaceState.CONFIRMED, kept.getState());
		assertEquals("It lies where it was found.", MARKED[0] + MARKED[2] / 2, kept.getX() + kept.getW() / 2, 0.02);
		FakeResponse crop = face(PHOTO, confirmedIndex);
		assertEquals("A tag without a detection has a crop of its own since #155.", 200, crop.status());
		assertEquals("image/jpeg", crop.contentType());
		int undecided = 0;
		for (FaceInfo face : image.getFaces()) {
			if (face.getState() == FaceState.UNDECIDED && face.getPerson().isEmpty()
				&& Math.abs(face.getX() + face.getW() / 2 - (second[0] + second[2] / 2)) < 0.02) {
				undecided++;
			}
		}
		assertEquals("The undecided mark was cache and is gone with it.", 0, undecided);
	}

	// --- Helpers, the shape of TestFaceRegions'. ---

	private static float[] embedding(int axis) {
		float[] result = new float[128];
		result[axis] = 1;
		return result;
	}

	private File photograph(String name) throws Exception {
		BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		try {
			graphics.setColor(new Color(0x40, 0x80, 0xC0));
			graphics.fillRect(0, 0, WIDTH, HEIGHT);
			graphics.setColor(Color.ORANGE);
			graphics.fillRect(900, 600, 300, 300);
		} finally {
			graphics.dispose();
		}
		File target = new File(album(), name);
		assertTrue(ImageIO.write(image, "jpg", target));
		return target;
	}

	private AlbumInfo mark(String image, double[] box, String person, String state) throws Exception {
		String assignment = "{\"image\":\"" + image + "\",\"x\":" + box[0] + ",\"y\":" + box[1] + ",\"w\":"
			+ box[2] + ",\"h\":" + box[3] + ",\"person\":\"" + person + "\",\"state\":\"" + state + "\"}";
		return tag(assignment);
	}

	private AlbumInfo tag(String... assignments) throws Exception {
		FakeResponse response = post("/" + ALBUM + "/", "tag-faces",
			"{\"faces\":[" + String.join(",", assignments) + "]}", _adminToken);
		assertEquals(response.body(), 200, response.status());
		return (AlbumInfo) Resource.readResource(reader(response.body()));
	}

	private static String assignment(String image, int face, String person, String state) {
		return "{\"image\":\"" + image + "\",\"face\":" + face + ",\"person\":\"" + person + "\",\"state\":\""
			+ state + "\"}";
	}

	private Person created(String name) throws Exception {
		FakeResponse response = post("/", "create-person", "{\"name\":\"" + name + "\"}", _adminToken);
		assertEquals(response.body(), 200, response.status());
		return Person.readPerson(reader(response.body()));
	}

	private FakeResponse face(String name, int index) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("face", Integer.toString(index));
		return get("/" + ALBUM + "/" + name, "face", _adminToken, parameters);
	}
}
