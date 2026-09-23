/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.faces.FaceCache;
import de.haumacher.imageServer.faces.FaceDetection;
import de.haumacher.imageServer.faces.Faces;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.FaceInfo;
import de.haumacher.imageServer.shared.model.FaceState;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Orientation;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.util.Orientations;
import de.haumacher.imageServer.upload.HashCache;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Probe of issue #163, composing the second look in the centre with what stood before it: the
 * mirrored EXIF orientations of #143 (the codes the preview used to draw blank), the "not a face"
 * of #155 that hides a region, and the walk's promise to describe a photograph once.
 */
@SuppressWarnings("javadoc")
public class TestFaceCentreProbe extends FacesTestCase {

	private static final Color BACKGROUND = new Color(0x40, 0x80, 0xC0);

	/** Where the stub finds the face on the centre crop, in fractions of the crop. */
	private static final double[] IN_CENTRE = { 0.40, 0.40, 0.20, 0.30 };

	/** The same face in fractions of the upright picture: the crop is its middle half. */
	private static final double[] IN_PICTURE = { 0.45, 0.45, 0.10, 0.15 };

	private static final class Stub implements FaceDetection.Detector {
		int _centres;

		@Override
		public FaceDetection.Result detect(File preview) throws IOException {
			BufferedImage image = ImageIO.read(preview);
			return new FaceDetection.Result(image.getWidth(), image.getHeight(), new ArrayList<>());
		}

		@Override
		public FaceDetection.Result detectIn(BufferedImage picture, FaceDetection.Refiner refiner) {
			_centres++;
			double width = picture.getWidth();
			double height = picture.getHeight();
			List<FaceDetection.Detected> found = new ArrayList<>();
			float[] embedding = new float[128];
			embedding[0] = 1;
			found.add(new FaceDetection.Detected(IN_CENTRE[0] * width, IN_CENTRE[1] * height, IN_CENTRE[2] * width,
				IN_CENTRE[3] * height, 0.95, embedding, true));
			return new FaceDetection.Result(picture.getWidth(), picture.getHeight(), found);
		}
	}

	private Stub _stub;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_stub = new Stub();
		FaceDetection.setDetector(_stub);
	}

	/**
	 * The mirrored orientations (2, 4, 7) are the ones whose preview was blank until #143; the
	 * centre crop is cut from the raw raster and turned by the same table, so a face found there
	 * is stored raw and answered upright for them too.
	 */
	public void testAMirroredFileIsLookedAtUprightToo() throws Exception {
		createSpace();
		for (int code : new int[] { 2, 4, 7 }) {
			canvas("mirrored-" + code + ".jpg", 2400, 1600, code);
		}
		index();
		assertEquals(3, _stub._centres);

		AlbumInfo album = album("/" + ALBUM + "/", _adminToken);
		for (int code : new int[] { 2, 4, 7 }) {
			String name = "mirrored-" + code + ".jpg";
			Orientation exif = Orientations.fromCode(code);
			FaceCache.Face face = stored(name);
			assertBox(name + ": stored in the raw raster.",
				Faces.toRaw(exif, IN_PICTURE[0], IN_PICTURE[1], IN_PICTURE[2], IN_PICTURE[3]),
				new double[] { face.getX(), face.getY(), face.getW(), face.getH() });
			FaceInfo info = only(album, name);
			assertBox(name + ": answered upright.", IN_PICTURE,
				new double[] { info.getX(), info.getY(), info.getW(), info.getH() });
		}
	}

	/**
	 * A face found in the centre is a detection like any other: "not a face" (#155) hides it, the
	 * entry still holds a face, and neither the listing nor the walk looks a second time.
	 */
	public void testAHiddenCentreFaceIsNotLookedForAgain() throws Exception {
		createSpace();
		canvas("small.jpg", 2400, 1600, 1);
		index();
		assertEquals(1, _stub._centres);
		assertEquals(1, only(album("/" + ALBUM + "/", _adminToken), "small.jpg").getIndex() + 1);

		FakeResponse response = post("/" + ALBUM + "/", "tag-faces",
			"{\"faces\":[{\"image\":\"small.jpg\",\"face\":0,\"person\":\"\",\"state\":\"NOT_A_FACE\"}]}", _adminToken);
		assertEquals(response.body(), 200, response.status());

		ImagePart hidden = image(album("/" + ALBUM + "/", _adminToken), "small.jpg");
		assertTrue("Hidden from the answer: " + hidden.getFaces(), hidden.getFaces().isEmpty());
		assertEquals("The detection stays in the cache with its marker.", 1, faces("small.jpg").size());
		assertTrue(new FaceCache(album()).isSearched(hash("small.jpg")));

		index();
		restart();
		index();
		assertEquals("Described once: the entry has a face, the walk has nothing to do.", 1, _stub._centres);
		ImagePart afterRestart = image(album("/" + ALBUM + "/", _adminToken), "small.jpg");
		assertTrue(afterRestart.getFaces().isEmpty());
		assertEquals(FaceState.NOT_A_FACE, ((AlbumInfo) Resource.readResource(reader(
			java.nio.file.Files.readString(new File(album(), "index.json").toPath())))).getParts().stream()
			.filter(p -> p instanceof ImagePart).map(p -> (ImagePart) p).findFirst().get().getTags().get(0).getState());
	}

	// --- Helpers, the shape of TestFaceCentre's. ---

	private String hash(String name) {
		String hash = new HashCache(album()).storedHashByName().get(name);
		assertNotNull("No hash for '" + name + "'.", hash);
		return hash;
	}

	private List<FaceCache.Face> faces(String name) {
		return new FaceCache(album()).facesOf(hash(name));
	}

	private FaceCache.Face stored(String name) {
		List<FaceCache.Face> faces = faces(name);
		assertEquals("Expected one detection in '" + name + "'.", 1, faces.size());
		return faces.get(0);
	}

	private static FaceInfo only(AlbumInfo album, String name) {
		ImagePart image = image(album, name);
		assertEquals("Expected one face in '" + name + "': " + image.getFaces(), 1, image.getFaces().size());
		return image.getFaces().get(0);
	}

	private static void assertBox(String message, double[] expected, double[] actual) {
		for (int n = 0; n < 4; n++) {
			assertEquals(message + " (" + n + ")", expected[n], actual[n], 0.005);
		}
	}

	/** A blank canvas of the shown size, stored under the given EXIF orientation code. */
	private File canvas(String name, int width, int height, int code) throws Exception {
		BufferedImage shown = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = shown.createGraphics();
		try {
			graphics.setColor(BACKGROUND);
			graphics.fillRect(0, 0, width, height);
			graphics.setColor(new Color(name.hashCode() & 0xFFFFFF));
			graphics.fillRect(0, 0, 16, 16);
		} finally {
			graphics.dispose();
		}
		int shownWidth = shown.getWidth();
		int shownHeight = shown.getHeight();
		File target = new File(album(), name);
		if (code == 1) {
			assertTrue(ImageIO.write(shown, "jpg", target));
			return target;
		}
		boolean transposed = code >= 5;
		int rawWidth = transposed ? shownHeight : shownWidth;
		int rawHeight = transposed ? shownWidth : shownHeight;
		int[] shownPixels = shown.getRGB(0, 0, shownWidth, shownHeight, null, 0, shownWidth);
		int[] raw = new int[rawWidth * rawHeight];
		for (int ry = 0; ry < rawHeight; ry++) {
			for (int rx = 0; rx < rawWidth; rx++) {
				int dx;
				int dy;
				switch (code) {
					case 2: dx = rawWidth - 1 - rx; dy = ry; break;
					case 3: dx = rawWidth - 1 - rx; dy = rawHeight - 1 - ry; break;
					case 4: dx = rx; dy = rawHeight - 1 - ry; break;
					case 5: dx = ry; dy = rx; break;
					case 6: dx = rawHeight - 1 - ry; dy = rx; break;
					case 7: dx = rawHeight - 1 - ry; dy = rawWidth - 1 - rx; break;
					default: dx = ry; dy = rawWidth - 1 - rx; break;
				}
				raw[ry * rawWidth + rx] = shownPixels[dy * shownWidth + dx];
			}
		}
		BufferedImage stored = new BufferedImage(rawWidth, rawHeight, BufferedImage.TYPE_INT_RGB);
		stored.setRGB(0, 0, rawWidth, rawHeight, raw, 0, rawWidth);
		File plain = new File(album(), name + ".plain");
		try {
			assertTrue(ImageIO.write(stored, "jpg", plain));
			Exif.writeOrientation(plain, target, code);
		} finally {
			plain.delete();
		}
		return target;
	}
}
