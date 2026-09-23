/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.faces.FaceDetection;
import de.haumacher.imageServer.faces.Faces;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.FaceInfo;
import de.haumacher.imageServer.shared.model.FaceTag;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Orientation;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.util.Orientations;
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
 * Probe of issue #157, composing the adjustment of a face box with the mirrored EXIF orientations
 * of #143 (the box is stored raw, answered upright — an adjustment must land in the same frame),
 * with a face hidden by "not a face" (#155) and with a share link, which may decide nothing.
 */
@SuppressWarnings("javadoc")
public class TestFaceAdjustProbe extends FacesTestCase {

	private static final int WIDTH = 1600;

	private static final int HEIGHT = 1200;

	/** The detection on the preview, in fractions of the upright picture. */
	private static final double[] DETECTED = { 0.10, 0.10, 0.20, 0.30 };

	/** Where the author drags it, upright fractions. */
	private static final double[] MOVED = { 0.15, 0.12, 0.20, 0.30 };

	private static final class Stub implements FaceDetection.Detector {
		@Override
		public FaceDetection.Result detect(File preview) throws IOException {
			BufferedImage image = ImageIO.read(preview);
			int width = image.getWidth();
			int height = image.getHeight();
			List<FaceDetection.Detected> found = new ArrayList<>();
			float[] embedding = new float[128];
			embedding[0] = 1;
			found.add(new FaceDetection.Detected(DETECTED[0] * width, DETECTED[1] * height, DETECTED[2] * width,
				DETECTED[3] * height, 0.99, embedding));
			return new FaceDetection.Result(width, height, found);
		}
	}

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		FaceDetection.setDetector(new Stub());
	}

	/** A mirrored file (EXIF 2): the adjusted box is stored raw by the same table the answer uses. */
	public void testAnAdjustmentOnAMirroredFileKeepsItsFrame() throws Exception {
		createSpace();
		photograph("mirrored.jpg", 2);
		index();
		AlbumInfo before = album("/" + ALBUM + "/", _adminToken);
		FaceInfo detected = only(before, "mirrored.jpg");
		assertBox("Answered upright before anything.", DETECTED, box(detected));

		AlbumInfo after = adjust("mirrored.jpg", 0, MOVED, "", "UNDECIDED", 200);
		FaceInfo moved = only(after, "mirrored.jpg");
		assertBox("Answered where it was dragged, upright.", MOVED, box(moved));

		FaceTag stored = stored("mirrored.jpg").getTags().get(0);
		Orientation exif = Orientations.fromCode(2);
		assertBox("Stored in the raw raster: the mirror applied.",
			Faces.toRaw(exif, MOVED[0], MOVED[1], MOVED[2], MOVED[3]),
			new double[] { stored.getX(), stored.getY(), stored.getW(), stored.getH() });

		restart();
		assertBox("And upright again after a restart.", MOVED,
			box(only(album("/" + ALBUM + "/", _adminToken), "mirrored.jpg")));
	}

	/** A face hidden by "not a face" is answered nobody, so nobody can adjust it. */
	public void testAHiddenFaceCannotBeAdjusted() throws Exception {
		createSpace();
		photograph("plain.jpg", 1);
		index();
		tag("plain.jpg", 0, "", "NOT_A_FACE");
		assertTrue(image(album("/" + ALBUM + "/", _adminToken), "plain.jpg").getFaces().isEmpty());

		FakeResponse refused = adjustResponse("plain.jpg", 0, MOVED, "", "UNDECIDED");
		assertEquals(refused.body(), 400, refused.status());
		assertEquals("Still hidden, nothing moved.", 1, stored("plain.jpg").getTags().size());
	}

	/** A share link decides nothing about faces, an adjustment included. */
	public void testAShareLinkMayNotAdjust() throws Exception {
		createSpace();
		photograph("plain.jpg", 1);
		index();
		FakeResponse created = post("/" + ALBUM + "/", "share",
			"{\"label\":\"x\",\"expires\":\"\",\"maxPrivacy\":2,\"minRating\":-2,\"rights\":[{\"name\":\"view\"},{\"name\":\"contribute\"}]}",
			_adminToken);
		assertEquals(created.body(), 200, created.status());
		String token = de.haumacher.imageServer.shared.model.ShareLinkCreated
			.readShareLinkCreated(reader(created.body())).getToken();

		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "adjust-faces");
		FakeResponse refused = post("/", "adjust-faces", body("plain.jpg", 0, MOVED, "", "UNDECIDED"), token);
		assertEquals(refused.body(), 403, refused.status());
		assertTrue(stored("plain.jpg").getTags().isEmpty());
	}

	// --- Helpers. ---

	private AlbumInfo adjust(String image, int face, double[] box, String person, String state, int expected)
			throws Exception {
		FakeResponse response = adjustResponse(image, face, box, person, state);
		assertEquals(response.body(), expected, response.status());
		return (AlbumInfo) Resource.readResource(reader(response.body()));
	}

	private FakeResponse adjustResponse(String image, int face, double[] box, String person, String state)
			throws Exception {
		return post("/" + ALBUM + "/", "adjust-faces", body(image, face, box, person, state), _adminToken);
	}

	private static String body(String image, int face, double[] box, String person, String state) {
		return "{\"faces\":[{\"image\":\"" + image + "\",\"face\":" + face + ",\"x\":" + box[0] + ",\"y\":" + box[1]
			+ ",\"w\":" + box[2] + ",\"h\":" + box[3] + ",\"person\":\"" + person + "\",\"state\":\"" + state + "\"}]}";
	}

	private void tag(String image, int face, String person, String state) throws Exception {
		FakeResponse response = post("/" + ALBUM + "/", "tag-faces", "{\"faces\":[{\"image\":\"" + image
			+ "\",\"face\":" + face + ",\"person\":\"" + person + "\",\"state\":\"" + state + "\"}]}", _adminToken);
		assertEquals(response.body(), 200, response.status());
	}

	private static double[] box(FaceInfo face) {
		return new double[] { face.getX(), face.getY(), face.getW(), face.getH() };
	}

	private static FaceInfo only(AlbumInfo album, String name) {
		ImagePart image = image(album, name);
		assertEquals("Expected one face in '" + name + "': " + image.getFaces(), 1, image.getFaces().size());
		return image.getFaces().get(0);
	}

	private static void assertBox(String message, double[] expected, double[] actual) {
		for (int n = 0; n < 4; n++) {
			assertEquals(message + " (" + n + ")", expected[n], actual[n], 0.01);
		}
	}

	private ImagePart stored(String name) throws Exception {
		File file = new File(album(), "index.json");
		if (!file.isFile()) {
			// Nothing was ever written beside the photographs: no statement at all.
			return ImagePart.create().setName(name);
		}
		Resource resource = Resource.readResource(reader(Files.readString(file.toPath(), StandardCharsets.UTF_8)));
		return image((AlbumInfo) resource, name);
	}

	/** A photograph shown 1600 x 1200, stored under the given EXIF orientation code (1 or 2). */
	private File photograph(String name, int code) throws Exception {
		BufferedImage shown = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = shown.createGraphics();
		try {
			graphics.setColor(new Color(0x40, 0x80, 0xC0));
			graphics.fillRect(0, 0, WIDTH, HEIGHT);
			graphics.setColor(Color.ORANGE);
			graphics.fillRect(900, 600, 300, 300);
		} finally {
			graphics.dispose();
		}
		File target = new File(album(), name);
		if (code == 1) {
			assertTrue(ImageIO.write(shown, "jpg", target));
			return target;
		}
		// Code 2 mirrors horizontally: store the mirror so that the file is shown as drawn.
		BufferedImage raw = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < HEIGHT; y++) {
			for (int x = 0; x < WIDTH; x++) {
				raw.setRGB(x, y, shown.getRGB(WIDTH - 1 - x, y));
			}
		}
		File plain = new File(album(), name + ".plain");
		try {
			assertTrue(ImageIO.write(raw, "jpg", plain));
			Exif.writeOrientation(plain, target, code);
		} finally {
			plain.delete();
		}
		return target;
	}
}
