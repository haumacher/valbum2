/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.faces.FaceCache;
import de.haumacher.imageServer.faces.FaceDetection;
import de.haumacher.imageServer.faces.FaceIndex;
import de.haumacher.imageServer.faces.FaceTags;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.FaceInfo;
import de.haumacher.imageServer.shared.model.FaceState;
import de.haumacher.imageServer.shared.model.FaceTag;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.upload.HashCache;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Which frame a face box is answered in, see issue #142.
 *
 * <p>
 * A box is <em>stored</em> in the raw raster of the file — the frame nothing can move, see
 * {@link de.haumacher.imageServer.faces.Faces} — and <em>answered</em> in the frame the picture is
 * shown in, the EXIF orientation of the file applied, because that is the rendition the client
 * draws it on and the file's orientation is not on the wire. Before this, every box of a portrait
 * shot (EXIF&nbsp;6 or 8) landed nowhere near the face it named.
 * </p>
 *
 * <p>
 * The exact half of this is asked with a detector of the test's own, so that it is asked on every
 * machine: whatever box the detector saw on the (upright) preview must come back on the wire. The
 * real detector then confirms it on a photograph pasted into a rotated file.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestFaceWireFrame extends FacesTestCase {

	/** What the pasted portrait lies on, so that a crop can be told from a miss. */
	private static final Color BACKGROUND = new Color(0x40, 0x80, 0xC0);

	/** The codes this is asked for; the mirrored ones find no face at all, see issue #143. */
	private static final int[] CODES = { 1, 3, 6, 8 };

	/** The picture as it is shown, before it is stored turned. */
	private static final int SHOWN_WIDTH = 1600;

	/** The picture as it is shown, before it is stored turned. */
	private static final int SHOWN_HEIGHT = 1200;

	/** Where the one face sits in the picture as it is shown, as a fraction. */
	private static final double[] UPRIGHT_FACE = { 0.30, 0.20, 0.20, 0.30 };

	/** A detector that answers one box at a fixed place of the preview it is handed. */
	private static final class Fixed implements FaceDetection.Detector {

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
			found.add(new FaceDetection.Detected(UPRIGHT_FACE[0] * width, UPRIGHT_FACE[1] * height,
				UPRIGHT_FACE[2] * width, UPRIGHT_FACE[3] * height, 0.99, embedding));
			return new FaceDetection.Result(width, height, found);
		}
	}

	/** What the detector saw on the upright preview is what the wire says, whatever the file does. */
	public void testTheWireSpeaksTheFrameOfTheRendition() throws Exception {
		FaceDetection.setDetector(new Fixed());
		createSpace();
		for (int code : CODES) {
			turned(name(code), code);
		}
		index();

		AlbumInfo album = album("/" + ALBUM + "/", _adminToken);
		for (int code : CODES) {
			String name = name(code);
			FaceInfo face = only(album, name);
			assertEquals(name + ": the left edge is the one of the picture on screen.",
				UPRIGHT_FACE[0], face.getX(), 0.02);
			assertEquals(name + ": the top edge is the one of the picture on screen.",
				UPRIGHT_FACE[1], face.getY(), 0.02);
			assertEquals(name + ": the width is the one of the picture on screen.",
				UPRIGHT_FACE[2], face.getW(), 0.02);
			assertEquals(name + ": the height is the one of the picture on screen.",
				UPRIGHT_FACE[3], face.getH(), 0.02);
		}
	}

	/** And what is stored stays the raw raster of the file, which is what the turn is about. */
	public void testWhatIsStoredStaysRaw() throws Exception {
		FaceDetection.setDetector(new Fixed());
		createSpace();
		turned(name(6), 6);
		index();

		FaceCache.Face stored = stored(name(6));
		// A quarter turn: what is wide on screen is tall in the file, and the axes are swapped.
		assertEquals(UPRIGHT_FACE[3], stored.getW(), 0.02);
		assertEquals(UPRIGHT_FACE[2], stored.getH(), 0.02);
		assertFalse("A stored box that already were the upright one would say nothing.",
			Math.abs(stored.getX() - UPRIGHT_FACE[0]) < 0.01);
	}

	/** The orientation is written into the album's cache, so no album read opens a file for it. */
	public void testTheOrientationIsCached() throws Exception {
		FaceDetection.setDetector(new Fixed());
		createSpace();
		turned(name(6), 6);
		index();

		String hash = new HashCache(album()).storedHashByName().get(name(6));
		assertEquals("The EXIF code of the file is written down beside its faces.",
			Integer.valueOf(6), new FaceCache(album()).exifOf(hash));
	}

	/** A library indexed before this build carries no orientation, and answers upright all the same. */
	public void testAnOlderCacheIsReadFromTheFile() throws Exception {
		FaceDetection.setDetector(new Fixed());
		createSpace();
		turned(name(6), 6);
		index();

		// What a cache written before issue #142 looks like: everything but the orientation.
		File cacheFile = FaceCache.file(album());
		String written = java.nio.file.Files.readString(cacheFile.toPath());
		java.nio.file.Files.writeString(cacheFile.toPath(), written.replace(",\"exif\":6", ""));
		assertNull(new FaceCache(album()).exifOf(new HashCache(album()).storedHashByName().get(name(6))));

		FaceInfo face = only(album("/" + ALBUM + "/", _adminToken), name(6));
		assertEquals("Read from the file itself.", UPRIGHT_FACE[0], face.getX(), 0.02);
		assertEquals(UPRIGHT_FACE[1], face.getY(), 0.02);
	}

	// --- What is decided about a face. ---

	/** A tagging names a face of the answer and stores a raw box the detection matches again. */
	public void testATagOfARotatedFileIsStoredRaw() throws Exception {
		FaceDetection.setDetector(new Fixed());
		createSpace();
		turned(name(6), 6);
		index();

		String person = createPerson("Anna");
		tag(name(6), 0, person);

		FaceTag tag = onlyTag(name(6));
		FaceCache.Face detection = stored(name(6));
		assertTrue("The stored box is the detection's own, in the raw raster of the file.",
			FaceTags.iou(tag.getX(), tag.getY(), tag.getW(), tag.getH(),
				detection.getX(), detection.getY(), detection.getW(), detection.getH()) > FaceTags.IOU_MATCH);

		// And the way out is the way in, turned: one face, carrying the decision, upright.
		FaceInfo face = only(album("/" + ALBUM + "/", _adminToken), name(6));
		assertEquals(person, face.getPerson());
		assertEquals(UPRIGHT_FACE[0], face.getX(), 0.02);
		assertEquals(UPRIGHT_FACE[1], face.getY(), 0.02);
	}

	/** A tag no detection matches — the album's own statement — is answered upright too. */
	public void testATagWithoutADetectionIsAnsweredUpright() throws Exception {
		FaceDetection.setDetector(new Fixed());
		createSpace();
		turned(name(6), 6);
		index();
		String person = createPerson("Anna");
		tag(name(6), 0, person);

		// A machine without a detector, or a cache that was thrown away: the decision stays.
		assertTrue(FaceCache.file(album()).delete());

		AlbumInfo album = album("/" + ALBUM + "/", _adminToken);
		FaceInfo face = only(album, name(6));
		assertEquals(person, face.getPerson());
		assertEquals("", face.getCluster());
		assertEquals(UPRIGHT_FACE[0], face.getX(), 0.02);
		assertEquals(UPRIGHT_FACE[1], face.getY(), 0.02);
	}

	// --- What is marked by hand, see issue #147. ---

	/** A box drawn on the picture is stored in the raw raster and answered where it was drawn. */
	public void testAHandMarkedBoxOfARotatedFileComesBackWhereItWasDrawn() throws Exception {
		FaceDetection.setDetector(new Fixed());
		createSpace();
		turned(name(6), 6);
		index();

		String person = createPerson("Anna");
		// Far away from the one detection, so that this is a face of its own.
		double[] drawn = { 0.60, 0.55, 0.15, 0.20 };
		mark(name(6), drawn, person, "CONFIRMED", 200);

		FaceTag tag = onlyTag(name(6));
		assertFalse("A stored box that already were the drawn one would say nothing about the turn.",
			Math.abs(tag.getX() - drawn[0]) < 0.01 && Math.abs(tag.getY() - drawn[1]) < 0.01);
		// A quarter turn swaps the axes: what is wide on screen is tall in the file.
		assertEquals(drawn[3], tag.getW(), 0.02);
		assertEquals(drawn[2], tag.getH(), 0.02);

		AlbumInfo album = album("/" + ALBUM + "/", _adminToken);
		ImagePart image = image(album, name(6));
		assertEquals("The detection and the hand-marked face: " + image.getFaces(),
			2, image.getFaces().size());
		FaceInfo marked = image.getFaces().get(1);
		assertEquals(person, marked.getPerson());
		assertTrue(marked.isConfirmed());
		assertEquals("A tag no detection matches has no cluster.", "", marked.getCluster());
		assertEquals(drawn[0], marked.getX(), 0.02);
		assertEquals(drawn[1], marked.getY(), 0.02);
		assertEquals(drawn[2], marked.getW(), 0.02);
		assertEquals(drawn[3], marked.getH(), 0.02);
	}

	/** A box drawn over a face the detector did find is that face's decision, not a second one. */
	public void testAHandMarkedBoxOverADetectionIsThatFacesDecision() throws Exception {
		FaceDetection.setDetector(new Fixed());
		createSpace();
		turned(name(6), 6);
		index();

		String anna = createPerson("Anna");
		tag(name(6), 0, anna);
		String berta = createPerson("Berta");
		// Almost the detection's own box: what somebody draws by hand never matches to the pixel.
		mark(name(6), new double[] { UPRIGHT_FACE[0] + 0.01, UPRIGHT_FACE[1] + 0.01,
			UPRIGHT_FACE[2], UPRIGHT_FACE[3] }, berta, "CONFIRMED", 200);

		FaceTag tag = onlyTag(name(6));
		assertEquals("The decision moved to the new person instead of standing beside the old one.",
			berta, tag.getPerson());
		FaceCache.Face detection = stored(name(6));
		assertEquals("The face's own box is kept, not the drawn one.",
			detection.getX(), tag.getX(), 0.001);
		assertEquals(detection.getY(), tag.getY(), 0.001);

		FaceInfo face = only(album("/" + ALBUM + "/", _adminToken), name(6));
		assertEquals(berta, face.getPerson());
	}

	/** And the way back out of a hand-marked face is the take-back of issue #138. */
	public void testAHandMarkedBoxIsForgottenByTheSameBox() throws Exception {
		FaceDetection.setDetector(new Fixed());
		createSpace();
		turned(name(6), 6);
		index();

		String person = createPerson("Anna");
		double[] drawn = { 0.60, 0.55, 0.15, 0.20 };
		mark(name(6), drawn, person, "CONFIRMED", 200);
		assertEquals(1, image(album("/" + ALBUM + "/", _adminToken), name(6)).getTags().size());

		// The box as the app draws it again: near enough is the same face.
		mark(name(6), new double[] { drawn[0] + 0.01, drawn[1], drawn[2], drawn[3] }, "",
			"UNDECIDED", 200);

		ImagePart image = image(album("/" + ALBUM + "/", _adminToken), name(6));
		// A region is a region (issue #155): the decision goes, the hand-marked box stays.
		assertEquals("The region is kept, undecided.", 1, image.getTags().size());
		assertEquals(FaceState.UNDECIDED, image.getTags().get(0).getState());
		assertEquals("", image.getTags().get(0).getPerson());
		assertEquals("The detection and the region.", 2, image.getFaces().size());
		assertEquals(FaceState.UNDECIDED, image.getFaces().get(1).getState());
		assertEquals("", image.getFaces().get(1).getPerson());
	}

	/** A box that is not a place on the photograph is refused, and nothing is written. */
	public void testABoxThatIsNoPlaceIsRefused() throws Exception {
		FaceDetection.setDetector(new Fixed());
		createSpace();
		turned(name(6), 6);
		index();
		String person = createPerson("Anna");

		double[][] impossible = {
			{ 0.9, 0.1, 0.2, 0.1 },
			{ -0.1, 0.1, 0.2, 0.1 },
			{ 0.1, 0.9, 0.1, 0.2 },
			{ 0.1, 0.1, 0.0, 0.2 },
			{ 0.1, 0.1, 0.2, -0.3 },
		};
		for (double[] box : impossible) {
			FakeResponse refused = mark(name(6), box, person, "CONFIRMED", 400);
			assertEquals(ImageServlet.faceBoxInvalid(name(6)), errorMessage(refused));
		}
		assertEquals("Nothing was written.", 0,
			image(album("/" + ALBUM + "/", _adminToken), name(6)).getTags().size());
	}

	/** A request that carries no box is what it always was: the face of the answer, by its index. */
	public void testAnAssignmentWithoutABoxNamesTheAnsweredFace() throws Exception {
		FaceDetection.setDetector(new Fixed());
		createSpace();
		turned(name(6), 6);
		index();
		String person = createPerson("Anna");

		tag(name(6), 0, person);

		FaceCache.Face detection = stored(name(6));
		FaceTag tag = onlyTag(name(6));
		assertEquals(detection.getX(), tag.getX(), 0.001);
		assertEquals(detection.getY(), tag.getY(), 0.001);
	}

	// --- The real detector. ---

	/** A real face, pasted into a file that lies on its side, is named where it is shown. */
	public void testARealFaceOfARotatedFile() throws Exception {
		createSpace();
		if (!detectorAvailable()) {
			return;
		}
		int pasteX = 400;
		int pasteY = 150;
		int pasted = 600;
		for (int code : CODES) {
			paste(name(code), code, pasteX, pasteY, pasted);
		}
		index();

		AlbumInfo album = album("/" + ALBUM + "/", _adminToken);
		for (int code : CODES) {
			String name = name(code);
			FaceInfo face = only(album, name);
			double left = face.getX() * SHOWN_WIDTH;
			double top = face.getY() * SHOWN_HEIGHT;
			double width = face.getW() * SHOWN_WIDTH;
			double height = face.getH() * SHOWN_HEIGHT;
			assertTrue(name + ": the box lies on the pasted portrait, not beside it ("
				+ left + "," + top + " " + width + "x" + height + ").",
				left >= pasteX - 20 && top >= pasteY - 20 && left + width <= pasteX + pasted + 20
					&& top + height <= pasteY + 3 * pasted);

			// And the crop is untouched by all this: raw in, raw out.
			FaceIndex.Crop crop = _servlet.faces().crop(new File(album(), name), 0);
			assertNull(name + ": " + crop.getReason(), crop.getReason());
			BufferedImage cut = ImageIO.read(crop.getFile());
			assertFalse(name + ": the crop shows the background instead of the face.",
				isBackground(cut, cut.getWidth() / 2, cut.getHeight() / 2));
		}
	}

	// --- The library. ---

	private static String name(int code) {
		return "exif-" + code + ".jpg";
	}

	private FaceInfo only(AlbumInfo album, String name) {
		ImagePart image = image(album, name);
		assertEquals("Expected one face in '" + name + "': " + image.getFaces(), 1, image.getFaces().size());
		return image.getFaces().get(0);
	}

	private FaceCache.Face stored(String name) {
		String hash = new HashCache(album()).storedHashByName().get(name);
		assertNotNull("No hash for '" + name + "'.", hash);
		List<FaceCache.Face> faces = new FaceCache(album()).facesOf(hash);
		assertEquals("Expected one detection in '" + name + "'.", 1, faces.size());
		return faces.get(0);
	}

	private FaceTag onlyTag(String name) throws Exception {
		AlbumInfo album = album("/" + ALBUM + "/", _adminToken);
		ImagePart image = image(album, name);
		assertEquals("Expected one decision about '" + name + "'.", 1, image.getTags().size());
		return image.getTags().get(0);
	}

	private String createPerson(String name) throws Exception {
		FakeResponse response = post("/", "create-person", "{\"name\":\"" + name + "\"}", _adminToken);
		assertEquals(response.body(), 200, response.status());
		de.haumacher.imageServer.shared.model.Person person =
			de.haumacher.imageServer.shared.model.Person.readPerson(reader(response.body()));
		return person.getId();
	}

	/** Marks a face by hand, the box being a fraction of the picture as it is shown. */
	private FakeResponse mark(String image, double[] box, String person, String state, int expected)
			throws Exception {
		String body = "{\"faces\":[{\"image\":\"" + image + "\",\"x\":" + box[0] + ",\"y\":" + box[1]
			+ ",\"w\":" + box[2] + ",\"h\":" + box[3] + ",\"person\":\"" + person
			+ "\",\"state\":\"" + state + "\"}]}";
		FakeResponse response = post("/" + ALBUM + "/", "tag-faces", body, _adminToken);
		assertEquals(response.body(), expected, response.status());
		return response;
	}

	private void tag(String image, int face, String person) throws Exception {
		String body = "{\"faces\":[{\"image\":\"" + image + "\",\"face\":" + face + ",\"person\":\""
			+ person + "\",\"state\":\"CONFIRMED\"}]}";
		FakeResponse response = post("/" + ALBUM + "/", "tag-faces", body, _adminToken);
		assertEquals(response.body(), 200, response.status());
	}

	/** A plain picture, stored the way the given EXIF code says it is to be turned back. */
	private File turned(String name, int code) throws Exception {
		BufferedImage shown = new BufferedImage(SHOWN_WIDTH, SHOWN_HEIGHT, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = shown.createGraphics();
		try {
			graphics.setColor(BACKGROUND);
			graphics.fillRect(0, 0, SHOWN_WIDTH, SHOWN_HEIGHT);
		} finally {
			graphics.dispose();
		}
		return store(shown, name, code);
	}

	/** The same, with a real portrait pasted into it where the detector is to find it. */
	private File paste(String name, int code, int x, int y, int pasted) throws Exception {
		BufferedImage source = ImageIO.read(new File(PORTRAITS, A_ONE));
		int height = (int) Math.round(((double) pasted) * source.getHeight() / source.getWidth());
		BufferedImage shown = new BufferedImage(SHOWN_WIDTH, SHOWN_HEIGHT, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = shown.createGraphics();
		try {
			graphics.setColor(BACKGROUND);
			graphics.fillRect(0, 0, SHOWN_WIDTH, SHOWN_HEIGHT);
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			graphics.drawImage(source, x, y, pasted, height, null);
		} finally {
			graphics.dispose();
		}
		return store(shown, name, code);
	}

	/**
	 * Writes the given picture as a file whose own raster is turned, carrying the EXIF code that
	 * turns it back — which is exactly what a camera held sideways produces.
	 */
	private File store(BufferedImage shown, String name, int code) throws Exception {
		boolean transposed = code >= 5;
		int rawWidth = transposed ? SHOWN_HEIGHT : SHOWN_WIDTH;
		int rawHeight = transposed ? SHOWN_WIDTH : SHOWN_HEIGHT;
		int[] shownPixels = shown.getRGB(0, 0, SHOWN_WIDTH, SHOWN_HEIGHT, null, 0, SHOWN_WIDTH);
		int[] raw = new int[rawWidth * rawHeight];
		for (int ry = 0; ry < rawHeight; ry++) {
			for (int rx = 0; rx < rawWidth; rx++) {
				int dx;
				int dy;
				switch (code) {
					case 1: dx = rx; dy = ry; break;
					case 2: dx = rawWidth - 1 - rx; dy = ry; break;
					case 3: dx = rawWidth - 1 - rx; dy = rawHeight - 1 - ry; break;
					case 4: dx = rx; dy = rawHeight - 1 - ry; break;
					case 5: dx = ry; dy = rx; break;
					case 6: dx = rawHeight - 1 - ry; dy = rx; break;
					case 7: dx = rawHeight - 1 - ry; dy = rawWidth - 1 - rx; break;
					default: dx = ry; dy = rawWidth - 1 - rx; break;
				}
				raw[ry * rawWidth + rx] = shownPixels[dy * SHOWN_WIDTH + dx];
			}
		}
		BufferedImage stored = new BufferedImage(rawWidth, rawHeight, BufferedImage.TYPE_INT_RGB);
		stored.setRGB(0, 0, rawWidth, rawHeight, raw, 0, rawWidth);

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

	private static boolean isBackground(BufferedImage image, int x, int y) {
		int rgb = image.getRGB(x, y);
		return Math.abs(((rgb >> 16) & 0xFF) - BACKGROUND.getRed()) < 24
			&& Math.abs(((rgb >> 8) & 0xFF) - BACKGROUND.getGreen()) < 24
			&& Math.abs((rgb & 0xFF) - BACKGROUND.getBlue()) < 24;
	}

}
