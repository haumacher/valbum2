/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.faces.FaceCache;
import de.haumacher.imageServer.faces.FaceDetection;
import de.haumacher.imageServer.faces.FaceIndex;
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
 * That a cached face crop always shows the face it is asked for, see issue #141.
 *
 * <p>
 * The crop of <code>?type=face&amp;face=&lt;n&gt;</code> used to be cached under the photograph's
 * name and that number, so a photograph that was described again — which is what the walk of issue
 * #140 did to every library holding a small face — renumbered its faces and handed out the old
 * crop under the new number: the right box and the wrong person. The crop is named after the face
 * it shows now, so a renumbering simply misses the cache.
 * </p>
 *
 * <p>
 * With a detector of the test's own, so that this regression is asked on every machine, and with a
 * synthetic photograph whose two faces sit on differently coloured blocks, so that what is answered
 * can be told apart by looking at it.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestFaceCrops extends FacesTestCase {

	/** The photograph the faces are painted into. */
	private static final String PICTURE = "two-faces.jpg";

	/** The width of the synthetic photograph; larger than a preview, so it is described again. */
	private static final int WIDTH = 1600;

	/** The height of the synthetic photograph. */
	private static final int HEIGHT = 1200;

	/** The face on the red block, as a fraction of the picture. */
	private static final double[] RED_FACE = { 0.10, 0.40, 0.10, 0.10 };

	/** The face on the blue block, as a fraction of the picture. */
	private static final double[] BLUE_FACE = { 0.70, 0.40, 0.10, 0.10 };

	/** A detector that answers the boxes it is told to, in the order it is told to. */
	private static final class Painted implements FaceDetection.Detector {

		final List<double[]> _faces = new ArrayList<>();

		@Override
		public FaceDetection.Result detect(File preview) throws IOException {
			BufferedImage image = ImageIO.read(preview);
			if (image == null) {
				throw new IOException("Cannot read '" + preview + "'.");
			}
			int width = image.getWidth();
			int height = image.getHeight();
			List<FaceDetection.Detected> found = new ArrayList<>();
			for (double[] face : _faces) {
				found.add(new FaceDetection.Detected(face[0] * width, face[1] * height,
					face[2] * width, face[3] * height, 0.99, embedding()));
			}
			return new FaceDetection.Result(width, height, found);
		}

		private static float[] embedding() {
			float[] result = new float[128];
			result[0] = 1;
			return result;
		}
	}

	private Painted _detector;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_detector = new Painted();
		_detector._faces.add(RED_FACE);
		_detector._faces.add(BLUE_FACE);
		FaceDetection.setDetector(_detector);
	}

	// --- The regression of issue #141. ---

	/** A photograph described again renumbers its faces, and every crop follows. */
	public void testACropNeverOutlivesTheFaceItWasCutFor() throws Exception {
		createSpace();
		paintPicture(new File(album(), PICTURE));
		index();

		assertColour("The first face is the one on the red block.", Color.RED, cropOf(0));
		assertColour(Color.BLUE, cropOf(1));

		// What the walk of issue #140 did everywhere: the same faces, described again, in another
		// order. Nothing about the photograph changed.
		_detector._faces.clear();
		_detector._faces.add(BLUE_FACE);
		_detector._faces.add(RED_FACE);
		index();

		assertEquals("The crops of the old description are gone.", 0, crops().size());
		assertColour("The face at zero is now the one on the blue block.", Color.BLUE, cropOf(0));
		assertColour(Color.RED, cropOf(1));
	}

	/** A crop of the naming of issue #124, as every library out there holds it, is never served. */
	public void testACropOfTheOldNamingIsNeverServed() throws Exception {
		createSpace();
		File picture = new File(album(), PICTURE);
		paintPicture(picture);
		index();

		// Exactly what a 2.5.0 or 2.6.0 library has lying in its cache directory, and newer than
		// the photograph, which is all the old rule ever asked.
		File planted = new File(CacheRefresh.cacheDir(album()), "face-" + PICTURE + "-0.jpg");
		planted.getParentFile().mkdirs();
		write(block(Color.GREEN), planted);
		assertTrue(planted.setLastModified(System.currentTimeMillis()));

		assertColour("A name of the old scheme addresses nothing.", Color.RED, cropOf(0));
	}

	/** A crop older than the album's detections is cut again, whatever it is called. */
	public void testACropOlderThanTheDetectionsIsCutAgain() throws Exception {
		createSpace();
		File picture = new File(album(), PICTURE);
		paintPicture(picture);
		index();
		assertColour(Color.RED, cropOf(0));

		File crop = FaceIndex.cropFile(picture, 0);
		assertNotNull(crop);
		write(block(Color.GREEN), crop);

		long now = System.currentTimeMillis();
		assertTrue(picture.setLastModified(now - 60000));
		assertTrue(crop.setLastModified(now - 30000));
		assertTrue(FaceCache.file(album()).setLastModified(now - 10000));

		assertColour("A crop the detections are newer than says nothing.", Color.RED, cropOf(0));
	}

	/** The same picture under two names has its own crops, and both are right. */
	public void testTwoNamesOfTheSameContents() throws Exception {
		createSpace();
		File one = new File(album(), PICTURE);
		paintPicture(one);
		File two = new File(album(), "copy.jpg");
		Files.copy(one.toPath(), two.toPath());
		index();

		assertColour(Color.RED, cropOf(PICTURE, 0));
		assertColour(Color.BLUE, cropOf(PICTURE, 1));
		assertColour(Color.RED, cropOf("copy.jpg", 0));
		assertColour(Color.BLUE, cropOf("copy.jpg", 1));

		assertFalse("Two photographs, two crops.",
			FaceIndex.cropFile(one, 0).getName().equals(FaceIndex.cropFile(two, 0).getName()));
	}

	// --- What the cache directory holds. ---

	/** The name rule of issue #98 knows the crops of the new naming, and refreshing removes them. */
	public void testTheCropsAreGeneratedFiles() throws Exception {
		createSpace();
		File picture = new File(album(), PICTURE);
		paintPicture(picture);
		index();
		assertEquals(200, face("/" + ALBUM + "/" + PICTURE, "0", _adminToken).status());

		File crop = FaceIndex.cropFile(picture, 0);
		assertNotNull(crop);
		assertTrue(crop.isFile());
		assertTrue("A crop is one of the server's own files: " + crop.getName(),
			CacheRefresh.isGenerated(crop.getName()));

		FakeResponse response = post("/" + ALBUM + "/", "refresh-cache", "{}", _adminToken);
		assertEquals(response.body(), 200, response.status());
		assertFalse("A refresh throws the crops away.", crop.exists());
	}

	/** Describing a photograph again touches nothing but the crops of that photograph. */
	public void testNothingButACropIsEverDeleted() throws Exception {
		createSpace();
		File picture = new File(album(), PICTURE);
		paintPicture(picture);
		index();
		assertColour(Color.RED, cropOf(0));

		File cacheDir = CacheRefresh.cacheDir(album());
		File foreign = new File(cacheDir, "notes.txt");
		Files.writeString(foreign.toPath(), "mine", StandardCharsets.UTF_8);
		File alien = new File(cacheDir, "face-" + PICTURE + "-mine.txt");
		Files.writeString(alien.toPath(), "mine too", StandardCharsets.UTF_8);
		File other = new File(cacheDir, "face-elsewhere.jpg-0.jpg");
		write(block(Color.GREEN), other);

		_detector._faces.clear();
		_detector._faces.add(BLUE_FACE);
		_detector._faces.add(RED_FACE);
		index();

		assertTrue("A file the server did not write stays.", foreign.isFile());
		assertTrue("Whatever it is called.", alien.isFile());
		assertTrue("And a crop of another photograph is none of this one's business.", other.isFile());
		assertEquals("Of this photograph, nothing is left.", List.of(other.getName()), crops());
	}

	// --- The library. ---

	/** The crop of the given face of the picture, as the servlet answers it. */
	private BufferedImage cropOf(int index) throws Exception {
		return cropOf(PICTURE, index);
	}

	private BufferedImage cropOf(String name, int index) throws Exception {
		FakeResponse response = face("/" + ALBUM + "/" + name, Integer.toString(index), _adminToken);
		assertEquals(response.body(), 200, response.status());
		BufferedImage image = ImageIO.read(new ByteArrayInputStream(response.bodyBytes()));
		assertNotNull("The answer is a JPEG.", image);
		return image;
	}

	/** Every crop lying in the album's cache directory. */
	private List<String> crops() {
		List<String> result = new ArrayList<>();
		File[] files = CacheRefresh.cacheDir(album()).listFiles();
		if (files == null) {
			return result;
		}
		for (File file : files) {
			if (file.getName().startsWith(FaceIndex.CROP_PREFIX)
				&& file.getName().endsWith("." + FaceIndex.CROP_EXTENSION)) {
				result.add(file.getName());
			}
		}
		return result;
	}

	private static void assertColour(Color expected, BufferedImage image) {
		assertColour("The wrong face was answered.", expected, image);
	}

	/** That the middle of the given crop is of the expected colour, JPEG noise allowed for. */
	private static void assertColour(String message, Color expected, BufferedImage image) {
		Color found = new Color(image.getRGB(image.getWidth() / 2, image.getHeight() / 2));
		int distance = Math.abs(found.getRed() - expected.getRed())
			+ Math.abs(found.getGreen() - expected.getGreen())
			+ Math.abs(found.getBlue() - expected.getBlue());
		assertTrue(message + " Expected " + expected + ", found " + found + ".", distance < 120);
	}

	/**
	 * Writes the photograph the two faces are found in: white, with a red block around the one and
	 * a blue block around the other, each far larger than the face's own box, so that whatever a
	 * crop is cut from — the preview or the original — its middle is of that colour.
	 */
	private static void paintPicture(File target) throws IOException {
		BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		try {
			graphics.setColor(Color.WHITE);
			graphics.fillRect(0, 0, WIDTH, HEIGHT);
			block(graphics, RED_FACE, Color.RED);
			block(graphics, BLUE_FACE, Color.BLUE);
		} finally {
			graphics.dispose();
		}
		write(image, target);
	}

	private static void block(Graphics2D graphics, double[] face, Color colour) {
		graphics.setColor(colour);
		double margin = 0.5;
		int left = (int) Math.round((face[0] - face[2] * margin) * WIDTH);
		int top = (int) Math.round((face[1] - face[3] * margin) * HEIGHT);
		int width = (int) Math.round(face[2] * (1 + 2 * margin) * WIDTH);
		int height = (int) Math.round(face[3] * (1 + 2 * margin) * HEIGHT);
		graphics.fillRect(left, top, width, height);
	}

	/** A small picture of one colour, for what a stale crop file holds. */
	private static BufferedImage block(Color colour) {
		BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		try {
			graphics.setColor(colour);
			graphics.fillRect(0, 0, 64, 64);
		} finally {
			graphics.dispose();
		}
		return image;
	}

	private static void write(BufferedImage image, File target) throws IOException {
		target.getParentFile().mkdirs();
		assertTrue("No JPEG writer.", ImageIO.write(image, "jpg", target));
	}

	/** A request for one face crop. */
	private FakeResponse face(String pathInfo, String index, String token) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("face", index);
		return get(pathInfo, ImageServlet.FACE_TYPE, token, parameters);
	}
}
