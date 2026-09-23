/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.faces.Clustering;
import de.haumacher.imageServer.faces.FaceCache;
import de.haumacher.imageServer.faces.FaceDetection;
import de.haumacher.imageServer.faces.Faces;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.FaceInfo;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Orientation;
import de.haumacher.imageServer.shared.util.Orientations;
import de.haumacher.imageServer.upload.HashCache;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * The second look into the centre of an original whose preview shows no face, see issue #163.
 *
 * <p>
 * Most tests install a detector of their own: the preview says what the test wants it to say and so
 * does the centre, which pins where the centre is cut, how its box comes back into the frame a face
 * is stored in, and when it is asked at all — without a model. The last test asks the real models,
 * with one of the public-domain portraits of <code>src/test/fixtures/faces</code> pasted small into
 * the middle of a large canvas.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestFaceCentre extends FacesTestCase {

	/** The colour of every canvas. */
	private static final Color BACKGROUND = new Color(0x40, 0x80, 0xC0);

	/** Where the stub finds its face in the centre it is shown, as a fraction of that centre. */
	private static final double[] IN_CENTRE = { 0.40, 0.40, 0.20, 0.30 };

	/** The same box as a fraction of the whole picture as it is shown: the centre is its middle half. */
	private static final double[] IN_PICTURE = { 0.45, 0.45, 0.10, 0.15 };

	/** A detector whose preview pass finds a face only where the file name says so. */
	private static final class Stub implements FaceDetection.Detector {

		int _previews;

		int _centres;

		/** The size of the last centre the detector was shown. */
		int[] _centreSize;

		/** After how many centres the walk is interrupted, 0 for never. */
		int _interruptAfter;

		@Override
		public FaceDetection.Result detect(File preview) throws IOException {
			_previews++;
			BufferedImage image = ImageIO.read(preview);
			int width = image.getWidth();
			int height = image.getHeight();
			List<FaceDetection.Detected> found = new ArrayList<>();
			if (preview.getName().contains("face")) {
				found.add(new FaceDetection.Detected(0.3 * width, 0.2 * height, 0.2 * width, 0.3 * height, 0.99,
					embedding(), true));
			}
			return new FaceDetection.Result(width, height, found);
		}

		@Override
		public FaceDetection.Result detectIn(BufferedImage picture, FaceDetection.Refiner refiner) {
			_centres++;
			_centreSize = new int[] { picture.getWidth(), picture.getHeight() };
			if (_interruptAfter > 0 && _centres >= _interruptAfter) {
				Thread.currentThread().interrupt();
			}
			double width = picture.getWidth();
			double height = picture.getHeight();
			List<FaceDetection.Detected> found = new ArrayList<>();
			found.add(new FaceDetection.Detected(IN_CENTRE[0] * width, IN_CENTRE[1] * height,
				IN_CENTRE[2] * width, IN_CENTRE[3] * height, 0.95, embedding(), true));
			return new FaceDetection.Result(picture.getWidth(), picture.getHeight(), found);
		}

		private static float[] embedding() {
			float[] result = new float[128];
			result[0] = 1;
			return result;
		}
	}

	private Stub _stub;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_stub = new Stub();
	}

	// --- The second look. ---

	/** Nobody on the preview: the centre is asked, and its face is stored where it lies. */
	public void testTheCentreFindsWhatThePreviewMissed() throws Exception {
		FaceDetection.setDetector(_stub);
		createSpace();
		canvas("wide.jpg", 2400, 1600, 1);
		index();

		assertEquals("The centre was asked once.", 1, _stub._centres);
		assertEquals("At the size the preview reached the detector at.", 900, _stub._centreSize[0]);
		assertEquals(600, _stub._centreSize[1]);

		FaceCache.Face face = stored("wide.jpg");
		assertBox("Stored in the raw raster of a file that is not turned.", IN_PICTURE,
			new double[] { face.getX(), face.getY(), face.getW(), face.getH() });
		assertTrue("Described as well as this build can.", face.isRefined());
		assertFalse("And put into a group of the album.", face.getCluster().isEmpty());
		assertTrue("The second look is written down.", searched("wide.jpg"));

		FaceInfo info = only(album("/" + ALBUM + "/", _adminToken), "wide.jpg");
		assertBox("Answered as it lies in the picture.", IN_PICTURE,
			new double[] { info.getX(), info.getY(), info.getW(), info.getH() });
		assertFalse(info.getCluster().isEmpty());
	}

	/** A file lying on its side is cut in its middle and stored raw, and answered upright. */
	public void testATurnedFileIsLookedAtUpright() throws Exception {
		FaceDetection.setDetector(_stub);
		createSpace();
		for (int code : new int[] { 3, 6, 8, 5 }) {
			canvas("turned-" + code + ".jpg", 2400, 1600, code);
		}
		index();
		assertEquals(4, _stub._centres);
		assertEquals("The centre is upright: wide as the picture is shown.", 900, _stub._centreSize[0]);
		assertEquals(600, _stub._centreSize[1]);

		AlbumInfo album = album("/" + ALBUM + "/", _adminToken);
		for (int code : new int[] { 3, 6, 8, 5 }) {
			String name = "turned-" + code + ".jpg";
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

	/** A picture the preview draws pixel for pixel has no finer centre to show. */
	public void testAPictureThePreviewDoesNotShrinkGetsNoSecondLook() throws Exception {
		FaceDetection.setDetector(_stub);
		createSpace();
		canvas("small.jpg", 600, 400, 1);
		index();

		assertEquals("The preview was asked.", 1, _stub._previews);
		assertEquals("The centre never.", 0, _stub._centres);
		assertTrue("No face.", faces("small.jpg").isEmpty());
		assertTrue("And that is the final word.", searched("small.jpg"));
	}

	/** Where the preview found somebody, the centre is not asked. */
	public void testAFaceOnThePreviewNeedsNoSecondLook() throws Exception {
		FaceDetection.setDetector(_stub);
		createSpace();
		canvas("face.jpg", 2400, 1600, 1);
		index();

		assertEquals(1, _stub._previews);
		assertEquals(0, _stub._centres);
		FaceCache.Face face = stored("face.jpg");
		assertEquals("The face of the preview, not one of the centre.", 0.3, face.getX(), 0.01);
		assertTrue(searched("face.jpg"));
	}

	/** A video is never handed to the detector, the centre of its poster included. */
	public void testAVideoIsNeverLookedAt() throws Exception {
		FaceDetection.setDetector(_stub);
		createSpace(VIDEO);
		index();
		assertEquals(0, _stub._previews);
		assertEquals(0, _stub._centres);
	}

	/** A space that asks for no faces asks no centre either. */
	public void testASpaceWithoutFacesLooksNowhere() throws Exception {
		_faces = false;
		FaceDetection.setDetector(_stub);
		createSpace();
		canvas("wide.jpg", 2400, 1600, 1);
		index();
		assertEquals(0, _stub._previews);
		assertEquals(0, _stub._centres);
	}

	// --- The library described before. ---

	/**
	 * A photograph an older build found nobody in is looked at once more, resumably, and a photograph
	 * with a face is not.
	 */
	public void testTheWalkGivesAnOlderLibraryTheSecondLookOnce() throws Exception {
		FaceDetection.setDetector(_stub);
		createSpace();
		canvas("a.jpg", 2400, 1600, 1);
		canvas("b.jpg", 2400, 1600, 1);
		canvas("face.jpg", 2400, 1600, 1);
		canvas("small.jpg", 600, 400, 1);
		index();
		assertEquals(4, _stub._previews);
		assertEquals(2, _stub._centres);

		// What the album looked like before this build: nobody in a and b, and no marker at all.
		File file = FaceCache.file(album());
		FaceCache old = new FaceCache(album());
		for (String name : new String[] { "a.jpg", "b.jpg" }) {
			old.put(hash(name), new ArrayList<>());
		}
		old.flush();
		String older = Files.readString(file.toPath(), StandardCharsets.UTF_8).replace(",\"searched\":\"centre\"", "");
		assertFalse("The fixture must really lose the marker.", older.contains("searched"));
		Files.writeString(file.toPath(), older, StandardCharsets.UTF_8);
		assertTrue(faces("a.jpg").isEmpty());
		assertFalse(searched("a.jpg"));

		// The walk is stopped after the first photograph it gives the second look.
		_stub._previews = 0;
		_stub._centres = 0;
		_stub._interruptAfter = 1;
		try {
			_servlet.faces().indexNow();
		} finally {
			Thread.interrupted();
		}
		assertEquals("One photograph was looked at before the walk stopped.", 1, _stub._centres);
		assertTrue("And it is written down.", searched("a.jpg"));
		assertEquals("With what the centre found.", 1, faces("a.jpg").size());
		assertFalse("The other one is still to come.", searched("b.jpg"));

		// A restart goes on where the walk stopped, and only there.
		_stub._previews = 0;
		_stub._centres = 0;
		_stub._interruptAfter = 0;
		restart();
		index();
		assertEquals("The rest follows.", 1, _stub._centres);
		assertEquals("Neither the photograph with a face nor the one shown pixel for pixel is detected again.",
			1, _stub._previews);
		assertTrue(searched("b.jpg"));
		assertEquals(1, faces("b.jpg").size());
		assertTrue("The one shown pixel for pixel is marked without being looked at.", searched("small.jpg"));

		// And a further walk has nothing left to do.
		_stub._previews = 0;
		_stub._centres = 0;
		index();
		assertEquals(0, _stub._previews);
		assertEquals(0, _stub._centres);
	}

	/** The marker survives the file, and a file without it reads as it always did. */
	public void testTheMarkerRoundTrips() throws Exception {
		File folder = Files.createTempDirectory("valbum-centre").toFile();
		try {
			FaceCache cache = new FaceCache(folder);
			cache.put("aaa", new ArrayList<>());
			cache.putSearched("aaa");
			cache.put("bbb", new ArrayList<>());
			cache.flush();
			String json = Files.readString(FaceCache.file(folder).toPath(), StandardCharsets.UTF_8);
			assertTrue(json, json.contains("\"searched\":\"centre\""));

			FaceCache read = new FaceCache(folder);
			assertTrue(read.isSearched("aaa"));
			assertFalse(read.isSearched("bbb"));
			assertTrue(read.knows("bbb"));

			read.retain(java.util.Set.of("bbb"));
			read.flush();
			assertFalse("What the album no longer holds is forgotten.", new FaceCache(folder).isSearched("aaa"));

			// A file of a build before issue #140, and therefore before #163 too.
			Files.writeString(FaceCache.file(folder).toPath(),
				Files.readString(new File(PORTRAITS, "faces-before-140.json").toPath(), StandardCharsets.UTF_8)
					.replace("@MODEL@", FaceDetection.MODEL),
				StandardCharsets.UTF_8);
			FaceCache older = new FaceCache(folder);
			String hash = older.hashes().iterator().next();
			assertEquals(2, older.facesOf(hash).size());
			assertFalse(older.isSearched(hash));
		} finally {
			for (File file : CacheRefresh.cacheDir(folder).listFiles()) {
				file.delete();
			}
			CacheRefresh.cacheDir(folder).delete();
			folder.delete();
		}
	}

	// --- The real models. ---

	/**
	 * A small face in the middle of a large photograph: the preview misses it, the centre finds it,
	 * and the original describes it.
	 */
	public void testTheModelsFindASmallFaceInTheCentre() throws Exception {
		createSpace();
		if (!detectorAvailable()) {
			return;
		}
		int width = 4800;
		int height = 3200;
		int pasted = PASTED;
		File photograph = portrait("small-face.jpg", width, height, pasted);
		File preview = PreviewCache.createPreview(photograph);

		// The first pass as the index runs it, a refiner that says whether it was even asked.
		int[] asked = { 0 };
		FaceDetection.Result first = FaceDetection.detect(preview, candidate -> {
			asked[0]++;
			return null;
		});
		assertEquals("The preview alone finds nobody.", 0, first.getFaces().size());
		assertEquals("Not even a candidate for the original to confirm.", 0, asked[0]);

		index();
		List<FaceCache.Face> faces = faces("small-face.jpg");
		assertEquals("The centre found the face: " + faces.size(), 1, faces.size());
		FaceCache.Face face = faces.get(0);
		double centreX = (face.getX() + face.getW() / 2) * width;
		double centreY = (face.getY() + face.getH() / 2) * height;
		int pastedHeight = pastedHeight(pasted);
		assertTrue("It lies in the pasted portrait: " + centreX + "," + centreY,
			centreX > (width - pasted) / 2 && centreX < (width + pasted) / 2
				&& centreY > (height - pastedHeight) / 2 && centreY < (height + pastedHeight) / 2);
		assertTrue(searched("small-face.jpg"));

		float[] reference = FaceDetection.detect(new File(PORTRAITS, A_ONE)).getFaces().get(0).getEmbedding();
		double similarity = Clustering.cosine(face.getEmbedding(), reference);
		System.out.println("The centre of a " + width + "x" + height + " photograph with a portrait "
			+ pasted + " px wide: face " + Math.round(face.getW() * width) + " px in the file, similarity "
			+ similarity + ".");
		assertEquals(128, face.getEmbedding().length);
		assertTrue("The face is the portrait's: " + similarity, similarity >= 0.5);
	}

	/** How wide the portrait is pasted into the middle of the large canvas. */
	private static final int PASTED = 400;

	// --- The library. ---

	private String hash(String name) {
		String hash = new HashCache(album()).storedHashByName().get(name);
		assertNotNull("No hash for '" + name + "'.", hash);
		return hash;
	}

	private List<FaceCache.Face> faces(String name) {
		return new FaceCache(album()).facesOf(hash(name));
	}

	private boolean searched(String name) {
		return new FaceCache(album()).isSearched(hash(name));
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

	private static int pastedHeight(int pasted) throws IOException {
		BufferedImage source = ImageIO.read(new File(PORTRAITS, A_ONE));
		return (int) Math.round(((double) pasted) * source.getHeight() / source.getWidth());
	}

	/**
	 * An empty canvas of the given size as it is shown, stored turned by the given EXIF code.
	 *
	 * <p>
	 * A corner of it carries a colour of its name, so that no two canvases have the same contents —
	 * the face cache is keyed by contents, and two equal files would be one entry.
	 * </p>
	 */
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
		return store(shown, name, code);
	}

	/** A large canvas with the portrait pasted into its very middle. */
	private File portrait(String name, int width, int height, int pasted) throws Exception {
		BufferedImage source = ImageIO.read(new File(PORTRAITS, A_ONE));
		int pastedHeight = pastedHeight(pasted);
		BufferedImage shown = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = shown.createGraphics();
		try {
			graphics.setColor(BACKGROUND);
			graphics.fillRect(0, 0, width, height);
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			graphics.drawImage(source, (width - pasted) / 2, (height - pastedHeight) / 2, pasted, pastedHeight,
				null);
		} finally {
			graphics.dispose();
		}
		return store(shown, name, 1);
	}

	/**
	 * Writes the given picture as a file whose own raster is turned, carrying the EXIF code that
	 * turns it back.
	 */
	private File store(BufferedImage shown, String name, int code) throws Exception {
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
