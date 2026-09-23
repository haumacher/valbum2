/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.faces.FaceCache;
import de.haumacher.imageServer.faces.FaceDetection;
import de.haumacher.imageServer.faces.Originals;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.FaceInfo;
import de.haumacher.imageServer.shared.model.FaceState;
import de.haumacher.imageServer.shared.model.FaceTag;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Person;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.upload.HashCache;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
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
 * A marked region is a region, see issue #155.
 *
 * <p>
 * Marking a box nobody decided about looks for a face in the original around it and keeps what it
 * finds as a detection; a region the detector finds nothing in is kept as an undecided tag; a face
 * called no face is gone from every answer; and a detected region and a hand-marked one behave the
 * same when a decision is forgotten.
 * </p>
 *
 * <p>
 * With a detector of the test's own, both for the pass over the preview and for the look into a
 * marked region, so that all of this is asked on every machine, OpenCV or not.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestFaceRegions extends FacesTestCase {

	/** The photograph every test marks on. */
	private static final String PHOTO = "photo.jpg";

	/** A second photograph, for the person recognition knows. */
	private static final String OTHER = "other.jpg";

	/** The size of the synthetic photographs; larger than a preview, so the original says more. */
	private static final int WIDTH = 1600;

	private static final int HEIGHT = 1200;

	/** The one face the pass over the preview finds in {@link #PHOTO}, as a fraction. */
	private static final double[] DETECTED = { 0.10, 0.10, 0.20, 0.30 };

	/** Where a box is marked, far away from the detection. */
	private static final double[] MARKED = { 0.60, 0.55, 0.10, 0.12 };

	/** How large the face is that the look into a region finds, in pixels of that region. */
	private static final double FOUND_PIXELS = 80;

	/** A detector that answers fixed boxes on the preview and a face around a marked box. */
	private static final class Stub implements FaceDetection.Detector {

		/** What the pass over the preview finds, by photograph. */
		final Map<String, double[]> _preview = new HashMap<>();

		/** Whether the look into a marked region finds a face. */
		boolean _finds = true;

		/** The embedding the look into a marked region describes its face with. */
		float[] _embedding = embedding(1);

		/** The regions that were looked into: width, height and the box handed over. */
		final List<double[]> _searched = new ArrayList<>();

		@Override
		public FaceDetection.Result detect(File preview) throws IOException {
			BufferedImage image = ImageIO.read(preview);
			if (image == null) {
				throw new IOException("Cannot read '" + preview + "'.");
			}
			int width = image.getWidth();
			int height = image.getHeight();
			List<FaceDetection.Detected> found = new ArrayList<>();
			for (Map.Entry<String, double[]> entry : _preview.entrySet()) {
				if (preview.getName().contains(entry.getKey())) {
					double[] box = entry.getValue();
					found.add(new FaceDetection.Detected(box[0] * width, box[1] * height, box[2] * width,
						box[3] * height, 0.99, embedding(0)));
				}
			}
			return new FaceDetection.Result(width, height, found);
		}

		@Override
		public FaceDetection.Refined search(BufferedImage region, double x, double y, double w, double h) {
			_searched.add(new double[] { region.getWidth(), region.getHeight(), x, y, w, h });
			if (!_finds) {
				return null;
			}
			double centreX = x + w / 2;
			double centreY = y + h / 2;
			return new FaceDetection.Refined(centreX - FOUND_PIXELS / 2, centreY - FOUND_PIXELS / 2,
				FOUND_PIXELS, FOUND_PIXELS, _embedding);
		}
	}

	private Stub _detector;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_detector = new Stub();
		_detector._preview.put(PHOTO, DETECTED);
		FaceDetection.setDetector(_detector);
	}

	// --- Detecting on a marked region. ---

	/** A face found in a marked region is a detection of its own, with an embedding. */
	public void testAMarkedRegionWithAFaceIsStoredAsADetection() throws Exception {
		createSpace();
		photograph(PHOTO);
		index();

		AlbumInfo answer = mark(PHOTO, MARKED, "", "UNDECIDED", 200);

		ImagePart image = image(answer, PHOTO);
		assertEquals("The detection and the face found where it was marked: " + image.getFaces(), 2,
			image.getFaces().size());
		FaceInfo found = image.getFaces().get(1);
		assertEquals("It is numbered like any detection.", 1, found.getIndex());
		assertEquals(FaceState.UNDECIDED, found.getState());
		assertFalse("And clustered like one.", found.getCluster().isEmpty());
		assertEquals("It lies where it was marked.", MARKED[0] + MARKED[2] / 2, found.getX() + found.getW() / 2, 0.01);
		assertEquals(MARKED[1] + MARKED[3] / 2, found.getY() + found.getH() / 2, 0.01);
		assertEquals("The detector's own size, not the drawn one.", FOUND_PIXELS / WIDTH, found.getW(), 0.005);

		List<FaceCache.Face> cached = new FaceCache(album()).facesOf(hashOf(PHOTO));
		assertEquals(2, cached.size());
		FaceCache.Face stored = cached.get(1);
		assertTrue("Found where somebody pointed.", stored.isMarked());
		assertTrue("And looked at in the original.", stored.isRefined());
		assertEquals("With the embedding the detector computed.", 1.0f, stored.getEmbedding()[1]);
		assertEquals("A detection is no decision: nothing is written beside the photographs.", 0,
			stored(PHOTO).getTags().size());
	}

	/** Describing the photograph again keeps the face somebody pointed at. */
	public void testAMarkedFaceOutlivesADescriptionAgain() throws Exception {
		// Small on the preview, so that the walk of issue #140 comes back to this photograph.
		_detector._preview.put(PHOTO, new double[] { 0.10, 0.10, 0.05, 0.05 });
		createSpace();
		photograph(PHOTO);
		index();
		mark(PHOTO, MARKED, "", "UNDECIDED", 200);
		assertEquals(2, new FaceCache(album()).facesOf(hashOf(PHOTO)).size());

		index();

		List<FaceCache.Face> cached = new FaceCache(album()).facesOf(hashOf(PHOTO));
		assertEquals("The new description and the marked face: " + cached.size(), 2, cached.size());
		assertTrue(cached.get(1).isMarked());
	}

	/** A face somebody pointed at is suggested as the person recognition knows. */
	public void testTheFoundFaceIsSuggestedWhenRecognitionKnowsThePerson() throws Exception {
		_detector._preview.put(OTHER, DETECTED);
		createSpace();
		photograph(PHOTO);
		photograph(OTHER);
		index();
		Person anna = created("Anna");
		// Anna is confirmed on the other photograph, and what the region finds looks like her.
		assertEquals(200, post("/" + ALBUM + "/", "tag-faces", body(assignment(OTHER, 0, anna.getId(), "CONFIRMED")),
			_adminToken).status());
		_detector._embedding = embedding(0);

		ImagePart image = image(mark(PHOTO, MARKED, "", "UNDECIDED", 200), PHOTO);

		FaceInfo found = image.getFaces().get(1);
		assertEquals("A suggestion, never a decision.", anna.getId(), found.getPerson());
		assertFalse(found.isConfirmed());
		assertEquals(FaceState.UNDECIDED, found.getState());
		assertEquals("Nothing was stored about it.", 0, stored(PHOTO).getTags().size());

		// One confirmation later it is Anna, and a prototype of her like any other face.
		AlbumInfo confirmed = tag(assignment(PHOTO, 1, anna.getId(), "CONFIRMED"));
		assertTrue(image(confirmed, PHOTO).getFaces().get(1).isConfirmed());
	}

	/** The crop of a face found in a marked region is cut from the original. */
	public void testTheCropOfAFoundFaceIsCutFromTheOriginal() throws Exception {
		createSpace();
		photograph(PHOTO);
		index();
		mark(PHOTO, MARKED, "", "UNDECIDED", 200);

		long before = Originals.decodes();
		BufferedImage crop = crop(PHOTO, 1);
		assertTrue("The original was read for the crop.", Originals.decodes() > before);
		assertTrue("And it is larger than the preview could make it: " + crop.getWidth(),
			Math.min(crop.getWidth(), crop.getHeight()) >= 100);
	}

	/** A region the detector finds nothing in is kept as an undecided region, with a crop. */
	public void testNothingFoundStoresAnUndecidedRegion() throws Exception {
		_detector._finds = false;
		createSpace();
		photograph(PHOTO);
		index();

		ImagePart image = image(mark(PHOTO, MARKED, "", "UNDECIDED", 200), PHOTO);

		assertEquals(1, stored(PHOTO).getTags().size());
		FaceTag tag = stored(PHOTO).getTags().get(0);
		assertEquals("A region nobody decided about.", FaceState.UNDECIDED, tag.getState());
		assertEquals("", tag.getPerson());
		assertEquals(MARKED[0], tag.getX(), 0.001);

		assertEquals(2, image.getFaces().size());
		FaceInfo region = image.getFaces().get(1);
		assertEquals(1, region.getIndex());
		assertEquals(FaceState.UNDECIDED, region.getState());
		assertEquals("A tag belongs to no group.", "", region.getCluster());
		assertEquals("", region.getPerson());
		assertEquals("Nothing was added to the detections.", 1,
			new FaceCache(album()).facesOf(hashOf(PHOTO)).size());

		// Item 5 of issue #155: a face that is only a tag has a crop, cut by its own box.
		BufferedImage crop = crop(PHOTO, 1);
		assertNotNull(crop);
	}

	/** A click is a tiny box, and the region it is looked for in is a fifth of the long side. */
	public void testAClickIsLookedForInAFifthOfTheLongSide() throws Exception {
		createSpace();
		photograph(PHOTO);
		index();

		double[] click = { 0.50, 0.50, 0.01, 0.01 };
		mark(PHOTO, click, "", "UNDECIDED", 200);

		assertEquals(1, _detector._searched.size());
		double[] searched = _detector._searched.get(0);
		double least = 0.2 * Math.max(WIDTH, HEIGHT);
		assertTrue("The region is at least a fifth of the long side wide: " + searched[0],
			searched[0] >= least - 2);
		assertTrue("And high: " + searched[1], searched[1] >= least - 2);
		assertTrue("And not the whole picture.", searched[0] < WIDTH / 2);
		// The click lies in the middle of what is looked at.
		assertEquals(searched[0] / 2, searched[2] + searched[4] / 2, 2);
		assertEquals(searched[1] / 2, searched[3] + searched[5] / 2, 2);
		assertEquals("The box handed over is the click's.", 0.01 * WIDTH, searched[4], 1);
	}

	/** A face is marked on a photograph, never on a video. */
	public void testABoxOnAVideoIsRefused() throws Exception {
		createSpace(VIDEO);
		photograph(PHOTO);
		index();
		album("/" + ALBUM + "/", _adminToken);

		FakeResponse refused = post("/" + ALBUM + "/", "tag-faces",
			body(box(VIDEO, MARKED, "", "UNDECIDED")), _adminToken);
		assertEquals(refused.body(), 400, refused.status());
		assertEquals(ImageServlet.TAG_VIDEO_REFUSED, errorMessage(refused));
		assertTrue("Nothing was looked for.", _detector._searched.isEmpty());
	}

	/**
	 * The real detector: a face too small for the preview is found by a click in its middle.
	 *
	 * <p>
	 * A portrait pasted so small into a large canvas that its face is some twenty pixels on the
	 * preview — below what the pass over the preview keeps — and a click sent as the small box the
	 * app sends.
	 * </p>
	 */
	public void testTheRealDetectorFindsAFaceByAClick() throws Exception {
		FaceDetection.setDetector(null);
		createSpace();
		if (!detectorAvailable()) {
			return;
		}
		int width = 4000;
		int height = 3000;
		// Outside the centre half of the picture, which the second look of issue #163 would search
		// and find it in: this face is found by nothing but the click.
		int pasteX = 300;
		int pasteY = 300;
		int pasted = 400;
		BufferedImage source = ImageIO.read(new File(PORTRAITS, A_ONE));
		int pastedHeight = (int) Math.round(((double) pasted) * source.getHeight() / source.getWidth());
		BufferedImage shown = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = shown.createGraphics();
		try {
			graphics.setColor(new Color(0x40, 0x80, 0xC0));
			graphics.fillRect(0, 0, width, height);
			graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
				java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			graphics.drawImage(source, pasteX, pasteY, pasted, pastedHeight, null);
		} finally {
			graphics.dispose();
		}
		assertTrue(ImageIO.write(shown, "jpg", new File(album(), PHOTO)));
		index();
		assertEquals("Too small for the preview.", 0, new FaceCache(album()).facesOf(hashOf(PHOTO)).size());

		// Where the face is in the portrait itself, and so in the canvas.
		FaceDetection.Detected face = FaceDetection.detect(new File(PORTRAITS, A_ONE)).getFaces().get(0);
		double scale = ((double) pasted) / source.getWidth();
		double centreX = (pasteX + (face.getX() + face.getW() / 2) * scale) / width;
		double centreY = (pasteY + (face.getY() + face.getH() / 2) * scale) / height;
		double[] click = { centreX - 0.003, centreY - 0.004, 0.006, 0.008 };

		ImagePart image = image(mark(PHOTO, click, "", "UNDECIDED", 200), PHOTO);

		assertEquals("The face was found where it was clicked: " + image.getFaces(), 1, image.getFaces().size());
		FaceInfo found = image.getFaces().get(0);
		assertTrue("It contains the click.", found.getX() < centreX && found.getX() + found.getW() > centreX
			&& found.getY() < centreY && found.getY() + found.getH() > centreY);
		assertEquals("And it is the face, not the whole region.", face.getW() * scale / width, found.getW(),
			0.5 * face.getW() * scale / width);
		assertEquals("A detection, nothing stored beside the photographs.", 0, stored(PHOTO).getTags().size());
		assertTrue(new FaceCache(album()).facesOf(hashOf(PHOTO)).get(0).getEmbedding().length > 0);
	}

	// --- Forget and no face, the same for both kinds of region. ---

	/** Forgetting a detection's decision removes the tag; the detection stays. */
	public void testForgettingADetectionKeepsTheDetection() throws Exception {
		createSpace();
		photograph(PHOTO);
		index();
		Person anna = created("Anna");
		tag(assignment(PHOTO, 0, anna.getId(), "CONFIRMED"));

		AlbumInfo answer = tag(assignment(PHOTO, 0, "", "UNDECIDED"));

		assertEquals(0, stored(PHOTO).getTags().size());
		assertEquals(1, image(answer, PHOTO).getFaces().size());
		assertEquals(FaceState.UNDECIDED, image(answer, PHOTO).getFaces().get(0).getState());
	}

	/** Forgetting a hand-marked face's decision keeps the region, undecided. */
	public void testForgettingAHandMarkedFaceKeepsTheRegion() throws Exception {
		_detector._finds = false;
		createSpace();
		photograph(PHOTO);
		index();
		Person anna = created("Anna");
		mark(PHOTO, MARKED, anna.getId(), "CONFIRMED", 200);
		assertEquals(FaceState.CONFIRMED, stored(PHOTO).getTags().get(0).getState());

		AlbumInfo answer = tag(assignment(PHOTO, 1, "", "UNDECIDED"));

		assertEquals(1, stored(PHOTO).getTags().size());
		assertEquals(FaceState.UNDECIDED, stored(PHOTO).getTags().get(0).getState());
		assertEquals("", stored(PHOTO).getTags().get(0).getPerson());
		List<FaceInfo> faces = image(answer, PHOTO).getFaces();
		assertEquals(2, faces.size());
		assertEquals(FaceState.UNDECIDED, faces.get(1).getState());
		assertEquals("", faces.get(1).getPerson());
	}

	/** No face on a detection: the statement is stored, and the face is answered no more. */
	public void testNotAFaceHidesADetection() throws Exception {
		createSpace();
		photograph(PHOTO);
		index();

		AlbumInfo answer = tag(assignment(PHOTO, 0, "", "NOT_A_FACE"));

		assertEquals(1, stored(PHOTO).getTags().size());
		assertEquals(FaceState.NOT_A_FACE, stored(PHOTO).getTags().get(0).getState());
		assertEquals("Gone from the answer.", 0, image(answer, PHOTO).getFaces().size());
		assertEquals("And from the crops.", 404, face("/" + ALBUM + "/" + PHOTO, "0").status());
		FakeResponse refused = post("/" + ALBUM + "/", "tag-faces", body(assignment(PHOTO, 0, "", "UNDECIDED")),
			_adminToken);
		assertEquals("A hidden number names no face.", 400, refused.status());
		assertEquals(ImageServlet.unknownFace(PHOTO, 0), errorMessage(refused));
	}

	/** No face on a hand-marked region removes it entirely. */
	public void testNotAFaceRemovesAHandMarkedRegion() throws Exception {
		_detector._finds = false;
		createSpace();
		photograph(PHOTO);
		index();
		mark(PHOTO, MARKED, "", "UNDECIDED", 200);
		assertEquals(1, stored(PHOTO).getTags().size());

		AlbumInfo answer = tag(assignment(PHOTO, 1, "", "NOT_A_FACE"));

		assertEquals("Nothing is left of the region.", 0, stored(PHOTO).getTags().size());
		assertEquals("Only the detection is answered.", 1, image(answer, PHOTO).getFaces().size());
	}

	/** Marking a hidden detection again brings it back, number and all. */
	public void testMarkingAHiddenDetectionAgainBringsItBack() throws Exception {
		createSpace();
		photograph(PHOTO);
		index();
		FaceInfo detected = image(album("/" + ALBUM + "/", _adminToken), PHOTO).getFaces().get(0);
		tag(assignment(PHOTO, 0, "", "NOT_A_FACE"));

		// Nearly its own box, as a hand draws it.
		AlbumInfo answer = mark(PHOTO, new double[] { detected.getX() + 0.01, detected.getY(), detected.getW(),
			detected.getH() }, "", "UNDECIDED", 200);

		assertEquals("The statement is gone.", 0, stored(PHOTO).getTags().size());
		List<FaceInfo> faces = image(answer, PHOTO).getFaces();
		assertEquals(1, faces.size());
		assertEquals(0, faces.get(0).getIndex());
		assertEquals(FaceState.UNDECIDED, faces.get(0).getState());
		assertTrue("It was the detection that came back, not a new region.", _detector._searched.isEmpty());

		// And a name given by a box over a hidden detection is that detection's name.
		tag(assignment(PHOTO, 0, "", "NOT_A_FACE"));
		Person anna = created("Anna");
		answer = mark(PHOTO, new double[] { detected.getX(), detected.getY() + 0.01, detected.getW(),
			detected.getH() }, anna.getId(), "CONFIRMED", 200);
		assertEquals(1, stored(PHOTO).getTags().size());
		assertEquals(FaceState.CONFIRMED, stored(PHOTO).getTags().get(0).getState());
		assertEquals(anna.getId(), image(answer, PHOTO).getFaces().get(0).getPerson());
	}

	/** A "no face" an older build stored on a hand-marked box is replaced by marking it again. */
	public void testMarkingAnOldNotAFaceRegionAgainBringsItBack() throws Exception {
		_detector._finds = false;
		createSpace();
		photograph(PHOTO);
		index();
		// What #147 wrote for a box called no face: a tag nothing was detected in.
		mark(PHOTO, MARKED, "", "UNDECIDED", 200);
		String sidecar = Files.readString(new File(album(), "index.json").toPath(), StandardCharsets.UTF_8);
		Files.writeString(new File(album(), "index.json").toPath(),
			sidecar.replace("\"UNDECIDED\"", "\"NOT_A_FACE\""), StandardCharsets.UTF_8);
		restart();
		assertEquals(FaceState.NOT_A_FACE, stored(PHOTO).getTags().get(0).getState());
		assertEquals("Not answered.", 1, image(album("/" + ALBUM + "/", _adminToken), PHOTO).getFaces().size());

		AlbumInfo answer = mark(PHOTO, MARKED, "", "UNDECIDED", 200);

		assertEquals(1, stored(PHOTO).getTags().size());
		assertEquals(FaceState.UNDECIDED, stored(PHOTO).getTags().get(0).getState());
		assertEquals(2, image(answer, PHOTO).getFaces().size());
	}

	/** An undecided region and a "no face" survive every read and write of the sidecar. */
	public void testTheSidecarRoundTripKeepsBothKinds() throws Exception {
		_detector._finds = false;
		createSpace();
		photograph(PHOTO);
		index();
		mark(PHOTO, MARKED, "", "UNDECIDED", 200);
		tag(assignment(PHOTO, 0, "", "NOT_A_FACE"));
		List<FaceTag> before = stored(PHOTO).getTags();
		assertEquals(2, before.size());

		// Read, write back, read again: through the model directly …
		Resource read = Resource.readResource(reader(Files.readString(new File(album(), "index.json").toPath(),
			StandardCharsets.UTF_8)));
		java.io.StringWriter buffer = new java.io.StringWriter();
		try (de.haumacher.msgbuf.json.JsonWriter out =
			new de.haumacher.msgbuf.json.JsonWriter(new de.haumacher.msgbuf.server.io.WriterAdapter(buffer))) {
			read.writeTo(out);
		}
		Resource again = Resource.readResource(reader(buffer.toString()));
		assertSameTags(before, image((AlbumInfo) again, PHOTO).getTags());

		// … and through the application's own round trip.
		FakeResponse answered = get("/" + ALBUM + "/", "json", _adminToken);
		FakeResponse written = put("/" + ALBUM + "/", answered.body(), _adminToken);
		assertEquals(written.body(), 200, written.status());
		assertSameTags(before, stored(PHOTO).getTags());
	}

	// --- Helpers. ---

	private static void assertSameTags(List<FaceTag> expected, List<FaceTag> actual) {
		assertEquals(expected.size(), actual.size());
		for (int n = 0; n < expected.size(); n++) {
			assertEquals(expected.get(n).getState(), actual.get(n).getState());
			assertEquals(expected.get(n).getPerson(), actual.get(n).getPerson());
			assertEquals(expected.get(n).getX(), actual.get(n).getX(), 1e-9);
			assertEquals(expected.get(n).getY(), actual.get(n).getY(), 1e-9);
			assertEquals(expected.get(n).getW(), actual.get(n).getW(), 1e-9);
			assertEquals(expected.get(n).getH(), actual.get(n).getH(), 1e-9);
		}
	}

	private static float[] embedding(int axis) {
		float[] result = new float[128];
		result[axis] = 1;
		return result;
	}

	/** A plain synthetic photograph; the stub decides where its faces are. */
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

	private AlbumInfo mark(String image, double[] box, String person, String state, int expected) throws Exception {
		FakeResponse response = post("/" + ALBUM + "/", "tag-faces", body(box(image, box, person, state)),
			_adminToken);
		assertEquals(response.body(), expected, response.status());
		if (expected != 200) {
			return null;
		}
		return (AlbumInfo) Resource.readResource(reader(response.body()));
	}

	private AlbumInfo tag(String... assignments) throws Exception {
		FakeResponse response = post("/" + ALBUM + "/", "tag-faces", body(assignments), _adminToken);
		assertEquals(response.body(), 200, response.status());
		return (AlbumInfo) Resource.readResource(reader(response.body()));
	}

	private static String box(String image, double[] box, String person, String state) {
		return "{\"image\":\"" + image + "\",\"x\":" + box[0] + ",\"y\":" + box[1] + ",\"w\":" + box[2]
			+ ",\"h\":" + box[3] + ",\"person\":\"" + person + "\",\"state\":\"" + state + "\"}";
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

	private FakeResponse face(String path, String index) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("face", index);
		return get(path, "face", _adminToken, parameters);
	}

	private BufferedImage crop(String name, int index) throws Exception {
		FakeResponse response = face("/" + ALBUM + "/" + name, Integer.toString(index));
		assertEquals(response.body(), 200, response.status());
		assertEquals("image/jpeg", response.contentType());
		BufferedImage result = ImageIO.read(new ByteArrayInputStream(response.bodyBytes()));
		assertNotNull("Not a picture.", result);
		return result;
	}

	private ImagePart stored(String name) throws Exception {
		File file = new File(album(), "index.json");
		if (!file.isFile()) {
			return ImagePart.create().setName(name);
		}
		Resource resource = Resource.readResource(reader(Files.readString(file.toPath(), StandardCharsets.UTF_8)));
		return image((AlbumInfo) resource, name);
	}

	private String hashOf(String name) {
		return new HashCache(album()).storedHashByName().get(name);
	}
}
