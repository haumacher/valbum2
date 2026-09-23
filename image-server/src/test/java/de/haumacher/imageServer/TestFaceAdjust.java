/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.faces.FaceCache;
import de.haumacher.imageServer.faces.FaceDetection;
import de.haumacher.imageServer.faces.FaceIndex;
import de.haumacher.imageServer.faces.Faces;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.FaceInfo;
import de.haumacher.imageServer.shared.model.FaceState;
import de.haumacher.imageServer.shared.model.FaceTag;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Person;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.util.Orientations;
import de.haumacher.imageServer.upload.HashCache;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Moving and resizing the box of a face, see issue #157.
 *
 * <p>
 * <code>?action=adjust-faces</code> names an answered face and gives it a new box: the tag it
 * carried goes, a tag on the new box carries the decision the request states, the new box is never
 * snapped onto a detection, and in the answer the human box wins over the detector's — the crop of
 * <code>?type=face</code> included. With a detector of the test's own, so that this is asked on every
 * machine.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestFaceAdjust extends FacesTestCase {

	private static final String PHOTO = "photo.jpg";

	private static final int WIDTH = 1600;

	private static final int HEIGHT = 1200;

	/** The one face the pass over the preview finds, as a fraction of the upright picture. */
	private static final double[] DETECTED = { 0.10, 0.10, 0.20, 0.30 };

	/** The detection nudged a little: still the same face by the overlap rule. */
	private static final double[] NUDGED = { 0.12, 0.11, 0.18, 0.28 };

	/** The detection dragged far away: no overlap at all. */
	private static final double[] AWAY = { 0.55, 0.50, 0.20, 0.30 };

	/** A region marked by hand where the detector finds nothing. */
	private static final double[] MARKED = { 0.60, 0.55, 0.10, 0.12 };

	/** A detector that answers {@link #DETECTED} on every preview and finds nothing in a region. */
	private static final class Stub implements FaceDetection.Detector {

		@Override
		public FaceDetection.Result detect(File preview) throws IOException {
			BufferedImage image = ImageIO.read(preview);
			if (image == null) {
				throw new IOException("Cannot read '" + preview + "'.");
			}
			int width = image.getWidth();
			int height = image.getHeight();
			float[] embedding = new float[128];
			embedding[0] = 1;
			List<FaceDetection.Detected> found = new ArrayList<>();
			found.add(new FaceDetection.Detected(DETECTED[0] * width, DETECTED[1] * height, DETECTED[2] * width,
				DETECTED[3] * height, 0.99, embedding));
			return new FaceDetection.Result(width, height, found);
		}

		@Override
		public FaceDetection.Refined search(BufferedImage region, double x, double y, double w, double h) {
			return null;
		}
	}

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		FaceDetection.setDetector(new Stub());
	}

	/** A confirmed face moved a little keeps its person and state, at the new box. */
	public void testAdjustingATaggedDetectionStoresTheNewBox() throws Exception {
		prepare();
		Person anna = created("Anna");
		tag(assignment(PHOTO, 0, anna.getId(), "CONFIRMED"));

		AlbumInfo answer = adjust(adjustment(PHOTO, 0, NUDGED, anna.getId(), "CONFIRMED"));

		List<FaceTag> tags = stored(PHOTO).getTags();
		assertEquals("The old tag went, the new one came: " + tags, 1, tags.size());
		assertBox(NUDGED, tags.get(0));
		assertEquals(FaceState.CONFIRMED, tags.get(0).getState());
		assertEquals(anna.getId(), tags.get(0).getPerson());

		List<FaceInfo> faces = image(answer, PHOTO).getFaces();
		assertEquals("Still the one detection: " + faces, 1, faces.size());
		FaceInfo face = faces.get(0);
		assertEquals("Its number is the detection's.", 0, face.getIndex());
		assertFalse("And so is its cluster.", face.getCluster().isEmpty());
		assertEquals(anna.getId(), face.getPerson());
		assertTrue(face.isConfirmed());
		assertBox("The human box wins in the answer.", NUDGED, face);
	}

	/** The crop follows the adjusted box, under a name of its own. */
	public void testTheCropIsCutByTheAdjustedBox() throws Exception {
		prepare();
		File image = new File(album(), PHOTO);
		String hash = new HashCache(album()).storedHashByName().get(PHOTO);
		FaceCache.Face detected = new FaceCache(album()).facesOf(hash).get(0);
		assertEquals(200, crop(0).status());
		File before = FaceIndex.cropFile(image, hash, detected);
		assertTrue("The crop of the detection was cached.", before.isFile());

		adjust(adjustment(PHOTO, 0, NUDGED, "", "UNDECIDED"));

		FaceTag tag = stored(PHOTO).getTags().get(0);
		File after = FaceIndex.cropFile(image, hash,
			new FaceCache.Face(tag.getX(), tag.getY(), tag.getW(), tag.getH(), 0, null));
		assertFalse("A new box is a new name.", after.getName().equals(before.getName()));
		assertFalse(after.isFile());
		FakeResponse cropped = crop(0);
		assertEquals(cropped.body(), 200, cropped.status());
		assertTrue("The crop was cut by the adjusted box.", after.isFile());
		assertTrue("The old crop is simply missed.", before.isFile());
	}

	/** A detection nobody decided about gets an undecided tag at the new box. */
	public void testAdjustingABareDetectionStoresAnUndecidedTag() throws Exception {
		prepare();

		AlbumInfo answer = adjust(adjustment(PHOTO, 0, NUDGED, "", "UNDECIDED"));

		List<FaceTag> tags = stored(PHOTO).getTags();
		assertEquals(1, tags.size());
		assertEquals(FaceState.UNDECIDED, tags.get(0).getState());
		assertEquals("", tags.get(0).getPerson());
		assertBox(NUDGED, tags.get(0));
		List<FaceInfo> faces = image(answer, PHOTO).getFaces();
		assertEquals(1, faces.size());
		assertEquals(FaceState.UNDECIDED, faces.get(0).getState());
		assertBox(NUDGED, faces.get(0));
	}

	/** One request may move a box and name the face in it. */
	public void testAdjustingAndNamingAtOnce() throws Exception {
		prepare();
		Person anna = created("Anna");

		AlbumInfo answer = adjust(adjustment(PHOTO, 0, NUDGED, anna.getId(), "CONFIRMED"));

		FaceTag tag = stored(PHOTO).getTags().get(0);
		assertEquals(FaceState.CONFIRMED, tag.getState());
		assertBox(NUDGED, tag);
		assertEquals(anna.getId(), image(answer, PHOTO).getFaces().get(0).getPerson());
	}

	/** A face marked by hand is moved like any other. */
	public void testAdjustingAHandMarkedFaceMovesIt() throws Exception {
		prepare();
		Person anna = created("Anna");
		mark(PHOTO, MARKED, anna.getId(), "CONFIRMED");
		double[] moved = { 0.62, 0.40, 0.12, 0.14 };

		AlbumInfo answer = adjust(adjustment(PHOTO, 1, moved, anna.getId(), "CONFIRMED"));

		List<FaceTag> tags = stored(PHOTO).getTags();
		assertEquals(1, tags.size());
		assertBox(moved, tags.get(0));
		List<FaceInfo> faces = image(answer, PHOTO).getFaces();
		assertEquals(2, faces.size());
		assertEquals(1, faces.get(1).getIndex());
		assertBox(moved, faces.get(1));
		assertEquals(anna.getId(), faces.get(1).getPerson());
	}

	/**
	 * A box dragged beyond the overlap rule is no longer the detection's: the detection is answered
	 * bare again and the tag as a face of its own.
	 */
	public void testABoxMovedAwayLeavesTheDetectionBare() throws Exception {
		prepare();
		Person anna = created("Anna");
		tag(assignment(PHOTO, 0, anna.getId(), "CONFIRMED"));

		AlbumInfo answer = adjust(adjustment(PHOTO, 0, AWAY, anna.getId(), "CONFIRMED"));

		List<FaceTag> tags = stored(PHOTO).getTags();
		assertEquals("Not snapped back onto anything: " + tags, 1, tags.size());
		assertBox(AWAY, tags.get(0));
		List<FaceInfo> faces = image(answer, PHOTO).getFaces();
		assertEquals("The detection and the tag: " + faces, 2, faces.size());
		FaceInfo detection = faces.get(0);
		assertEquals(0, detection.getIndex());
		assertEquals(FaceState.UNDECIDED, detection.getState());
		assertFalse(detection.isConfirmed());
		assertBox("The detector's own box again.", DETECTED, detection, 0.01);
		FaceInfo moved = faces.get(1);
		assertEquals("The tag has the next number.", 1, moved.getIndex());
		assertEquals(anna.getId(), moved.getPerson());
		assertTrue(moved.isConfirmed());
		assertEquals("A tag belongs to no group.", "", moved.getCluster());
		assertBox(AWAY, moved);
	}

	/** Forgetting the name of an adjusted detection keeps the box somebody drew. */
	public void testForgettingAnAdjustedDetectionKeepsTheRegion() throws Exception {
		prepare();
		Person anna = created("Anna");
		adjust(adjustment(PHOTO, 0, NUDGED, anna.getId(), "CONFIRMED"));

		AlbumInfo answer = tag(assignment(PHOTO, 0, "", "UNDECIDED"));

		List<FaceTag> tags = stored(PHOTO).getTags();
		assertEquals("The region stays: " + tags, 1, tags.size());
		assertEquals(FaceState.UNDECIDED, tags.get(0).getState());
		assertEquals("", tags.get(0).getPerson());
		assertBox(NUDGED, tags.get(0));
		FaceInfo face = image(answer, PHOTO).getFaces().get(0);
		assertEquals(FaceState.UNDECIDED, face.getState());
		assertBox(NUDGED, face);

		// Forgetting once more changes nothing.
		long written = new File(album(), "index.json").lastModified();
		tag(assignment(PHOTO, 0, "", "UNDECIDED"));
		assertEquals(1, stored(PHOTO).getTags().size());
		assertEquals(written, new File(album(), "index.json").lastModified());
	}

	/** Forgetting a detection whose tag merely copied the detector's box still removes the tag. */
	public void testForgettingAnUnadjustedDetectionStillRemovesTheTag() throws Exception {
		prepare();
		Person anna = created("Anna");
		tag(assignment(PHOTO, 0, anna.getId(), "CONFIRMED"));

		tag(assignment(PHOTO, 0, "", "UNDECIDED"));

		assertEquals(0, stored(PHOTO).getTags().size());
	}

	/** A box that is no place on the photograph, or none at all, is refused and nothing written. */
	public void testAnInvalidBoxIsRefused() throws Exception {
		prepare();
		String[] invalid = {
			adjustment(PHOTO, 0, new double[] { 0.5, 0.5, 0, 0.1 }, "", "UNDECIDED"),
			adjustment(PHOTO, 0, new double[] { 0.9, 0.5, 0.2, 0.1 }, "", "UNDECIDED"),
			adjustment(PHOTO, 0, new double[] { -0.1, 0.5, 0.2, 0.1 }, "", "UNDECIDED"),
			assignment(PHOTO, 0, "", "UNDECIDED"),
		};
		for (String assignment : invalid) {
			FakeResponse refused = post("/" + ALBUM + "/", ImageServlet.ADJUST_FACES_ACTION, body(assignment),
				_adminToken);
			assertEquals(assignment + ": " + refused.body(), 400, refused.status());
			assertEquals(ImageServlet.faceBoxInvalid(PHOTO), errorMessage(refused));
		}
		assertEquals(0, stored(PHOTO).getTags().size());
	}

	/** A number that names no answered face is refused. */
	public void testAnUnknownFaceIsRefused() throws Exception {
		prepare();

		FakeResponse refused = post("/" + ALBUM + "/", ImageServlet.ADJUST_FACES_ACTION,
			body(adjustment(PHOTO, 5, NUDGED, "", "UNDECIDED")), _adminToken);

		assertEquals(refused.body(), 400, refused.status());
		assertEquals(ImageServlet.unknownFace(PHOTO, 5), errorMessage(refused));
	}

	/**
	 * <code>tag-faces</code> keeps its meaning: a face and a box there is a mark, as an app of 2.7.0
	 * sends it, and never moves face 0.
	 */
	public void testATagFacesMarkNeverAdjusts() throws Exception {
		prepare();
		Person anna = created("Anna");
		tag(assignment(PHOTO, 0, anna.getId(), "CONFIRMED"));
		FaceTag before = stored(PHOTO).getTags().get(0);

		tag("{\"image\":\"" + PHOTO + "\",\"face\":0,\"x\":" + MARKED[0] + ",\"y\":" + MARKED[1] + ",\"w\":"
			+ MARKED[2] + ",\"h\":" + MARKED[3] + ",\"person\":\"\",\"state\":\"UNDECIDED\"}");

		List<FaceTag> tags = stored(PHOTO).getTags();
		assertEquals("The mark and the untouched tag of face 0: " + tags, 2, tags.size());
		assertEquals(before.getX(), tags.get(0).getX(), 1e-12);
		assertEquals(anna.getId(), tags.get(0).getPerson());
		assertBox(MARKED, tags.get(1));
	}

	/** An adjusted tag survives every read and write of the sidecar. */
	public void testTheSidecarRoundTripKeepsTheAdjustedBox() throws Exception {
		prepare();
		Person anna = created("Anna");
		adjust(adjustment(PHOTO, 0, NUDGED, anna.getId(), "CONFIRMED"));

		FakeResponse answered = get("/" + ALBUM + "/", "json", _adminToken);
		FakeResponse written = put("/" + ALBUM + "/", answered.body(), _adminToken);
		assertEquals(written.body(), 200, written.status());
		restart();

		List<FaceTag> tags = stored(PHOTO).getTags();
		assertEquals(1, tags.size());
		assertBox(NUDGED, tags.get(0));
		assertEquals(FaceState.CONFIRMED, tags.get(0).getState());
		assertBox(NUDGED, image(album("/" + ALBUM + "/", _adminToken), PHOTO).getFaces().get(0));
	}

	/** The box arrives upright and is stored in the raw raster of a turned file (issue #142). */
	public void testTheAdjustedBoxIsStoredRaw() throws Exception {
		createSpace();
		turned(PHOTO, 6);
		index();
		album("/" + ALBUM + "/", _adminToken);

		AlbumInfo answer = adjust(adjustment(PHOTO, 0, NUDGED, "", "UNDECIDED"));

		double[] raw = Faces.toRaw(Orientations.fromCode(6), NUDGED[0], NUDGED[1], NUDGED[2], NUDGED[3]);
		FaceTag tag = stored(PHOTO).getTags().get(0);
		assertEquals(raw[0], tag.getX(), 1e-9);
		assertEquals(raw[1], tag.getY(), 1e-9);
		assertEquals(raw[2], tag.getW(), 1e-9);
		assertEquals(raw[3], tag.getH(), 1e-9);
		assertTrue("A raw box that were the upright one would say nothing.",
			Math.abs(raw[2] - NUDGED[2]) > 0.05);
		assertBox("And it is answered upright.", NUDGED, image(answer, PHOTO).getFaces().get(0));
	}

	// --- Helpers. ---

	private void prepare() throws Exception {
		createSpace();
		photograph(PHOTO);
		index();
		// The answer the caller reads, so that the numbering exists.
		assertEquals(1, image(album("/" + ALBUM + "/", _adminToken), PHOTO).getFaces().size());
	}

	private static void assertBox(double[] expected, FaceTag tag) {
		assertEquals(expected[0], tag.getX(), 1e-9);
		assertEquals(expected[1], tag.getY(), 1e-9);
		assertEquals(expected[2], tag.getW(), 1e-9);
		assertEquals(expected[3], tag.getH(), 1e-9);
	}

	private static void assertBox(double[] expected, FaceInfo face) {
		assertBox("", expected, face, 1e-9);
	}

	private static void assertBox(String message, double[] expected, FaceInfo face) {
		assertBox(message, expected, face, 1e-9);
	}

	private static void assertBox(String message, double[] expected, FaceInfo face, double delta) {
		assertEquals(message + " x", expected[0], face.getX(), delta);
		assertEquals(message + " y", expected[1], face.getY(), delta);
		assertEquals(message + " w", expected[2], face.getW(), delta);
		assertEquals(message + " h", expected[3], face.getH(), delta);
	}

	private File photograph(String name) throws Exception {
		BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		try {
			graphics.setColor(new Color(0x40, 0x80, 0xC0));
			graphics.fillRect(0, 0, WIDTH, HEIGHT);
			graphics.setColor(Color.ORANGE);
			graphics.fillRect(200, 150, 300, 300);
		} finally {
			graphics.dispose();
		}
		File target = new File(album(), name);
		assertTrue(ImageIO.write(image, "jpg", target));
		return target;
	}

	/** A file stored turned (tall in the raster) and carrying the given EXIF orientation. */
	private File turned(String name, int code) throws Exception {
		BufferedImage stored = new BufferedImage(HEIGHT, WIDTH, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = stored.createGraphics();
		try {
			graphics.setColor(new Color(0x40, 0x80, 0xC0));
			graphics.fillRect(0, 0, HEIGHT, WIDTH);
		} finally {
			graphics.dispose();
		}
		File target = new File(album(), name);
		File plain = new File(album(), name + ".plain");
		try {
			assertTrue(ImageIO.write(stored, "jpg", plain));
			Exif.writeOrientation(plain, target, code);
		} finally {
			plain.delete();
		}
		return target;
	}

	private AlbumInfo adjust(String... assignments) throws Exception {
		FakeResponse response = post("/" + ALBUM + "/", ImageServlet.ADJUST_FACES_ACTION, body(assignments),
			_adminToken);
		assertEquals(response.body(), 200, response.status());
		return (AlbumInfo) Resource.readResource(reader(response.body()));
	}

	private AlbumInfo tag(String... assignments) throws Exception {
		FakeResponse response = post("/" + ALBUM + "/", "tag-faces", body(assignments), _adminToken);
		assertEquals(response.body(), 200, response.status());
		return (AlbumInfo) Resource.readResource(reader(response.body()));
	}

	private void mark(String image, double[] box, String person, String state) throws Exception {
		tag("{\"image\":\"" + image + "\",\"x\":" + box[0] + ",\"y\":" + box[1] + ",\"w\":" + box[2]
			+ ",\"h\":" + box[3] + ",\"person\":\"" + person + "\",\"state\":\"" + state + "\"}");
	}

	private static String adjustment(String image, int face, double[] box, String person, String state) {
		return "{\"image\":\"" + image + "\",\"face\":" + face + ",\"x\":" + box[0] + ",\"y\":" + box[1]
			+ ",\"w\":" + box[2] + ",\"h\":" + box[3] + ",\"person\":\"" + person + "\",\"state\":\"" + state
			+ "\"}";
	}

	private static String assignment(String image, int face, String person, String state) {
		return "{\"image\":\"" + image + "\",\"face\":" + face + ",\"person\":\"" + person
			+ "\",\"state\":\"" + state + "\"}";
	}

	private static String body(String... assignments) {
		return "{\"faces\":[" + String.join(",", assignments) + "]}";
	}

	private Person created(String name) throws Exception {
		FakeResponse response = post("/", "create-person", "{\"name\":\"" + name + "\"}", _adminToken);
		assertEquals(response.body(), 200, response.status());
		return Person.readPerson(reader(response.body()));
	}

	private FakeResponse crop(int index) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("face", Integer.toString(index));
		return get("/" + ALBUM + "/" + PHOTO, "face", _adminToken, parameters);
	}

	private ImagePart stored(String name) throws Exception {
		File file = new File(album(), "index.json");
		if (!file.isFile()) {
			return ImagePart.create().setName(name);
		}
		Resource resource = Resource.readResource(reader(Files.readString(file.toPath(), StandardCharsets.UTF_8)));
		return image((AlbumInfo) resource, name);
	}
}
