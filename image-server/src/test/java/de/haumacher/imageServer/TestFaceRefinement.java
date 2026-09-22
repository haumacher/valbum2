/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.faces.Clustering;
import de.haumacher.imageServer.faces.FaceCache;
import de.haumacher.imageServer.faces.FaceDetection;
import de.haumacher.imageServer.faces.FaceIndex;
import de.haumacher.imageServer.faces.Originals;
import de.haumacher.imageServer.shared.model.Orientation;
import de.haumacher.imageServer.upload.HashCache;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.Iterator;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * A face found on the preview and described from the original, see issue #140.
 *
 * <p>
 * The photographs are made here rather than checked in, and they are synthetic on purpose: a large
 * canvas of one colour with one of the public-domain portraits of
 * <code>src/test/fixtures/faces</code> pasted into it small, which is exactly the situation the
 * issue is about — a face that is four hundred pixels in the file and fifty on the 600&nbsp;px
 * preview the whole pipeline of issue #124 works on.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestFaceRefinement extends FacesTestCase {

	/** The photograph every test of this class builds. */
	private static final String BIG = "big.jpg";

	/** The same photograph lying on its side in its file. */
	private static final String SIDEWAYS = "sideways.jpg";

	/** The colour everything around the pasted portrait has. */
	private static final Color BACKGROUND = new Color(0x40, 0x80, 0xC0);

	/** Where the portrait is pasted, in the raw raster of the big photograph. */
	private static final int PASTE_X = 1200;

	/** Where the portrait is pasted, in the raw raster of the big photograph. */
	private static final int PASTE_Y = 700;

	/** Where the portrait really landed, which is {@link #PASTE_X} unless it would not have fitted. */
	private int _pasteX;

	/** Where the portrait really landed, see {@link #_pasteX}. */
	private int _pasteY;

	/**
	 * A face of about fifty pixels on the preview.
	 *
	 * <p>
	 * The portrait is 320&nbsp;px wide and its face 86&nbsp;px across; a 4000&nbsp;&times;&nbsp;3000
	 * photograph has a preview of 800&nbsp;&times;&nbsp;600, so a pasted width of 928&nbsp;px puts
	 * 250&nbsp;px of face in the file and 50 on the preview.
	 * </p>
	 */
	private static final int SMALL_FACE_WIDTH = 928;

	/** A face of about thirty pixels on the preview, below {@link FaceDetection#MIN_FACE_PIXELS}. */
	private static final int TINY_FACE_WIDTH = 560;

	// --- The description. ---

	/** A small face is looked up in the file, and what comes back is better than the preview. */
	public void testASmallFaceIsDescribedFromTheOriginal() throws Exception {
		createSpace();
		if (!detectorAvailable()) {
			return;
		}
		File photograph = photograph(BIG, 4000, 3000, A_ONE, SMALL_FACE_WIDTH, Orientation.IDENTITY);

		long started = System.currentTimeMillis();
		index();
		long took = System.currentTimeMillis() - started;

		FaceCache.Face face = only(BIG);
		assertTrue("The one face was looked up in the original.", face.isRefined());
		assertEquals("And it lies where the portrait was pasted.", true, inside(face, photograph));

		// What the same portrait says about itself, at the resolution it has.
		float[] reference = FaceDetection.detect(new File(PORTRAITS, A_ONE)).getFaces().get(0).getEmbedding();
		// And what the preview alone would have said: the very same detection, without a refiner.
		float[] fromPreview =
			FaceDetection.detect(PreviewCache.createPreview(photograph)).getFaces().get(0).getEmbedding();

		double refined = Clustering.cosine(face.getEmbedding(), reference);
		double blurred = Clustering.cosine(fromPreview, reference);
		System.out.println("Refinement of a 50 px face: refined " + refined + ", from the preview "
			+ blurred + ", one photograph in " + took + " ms.");
		assertTrue("The refined face is the portrait: " + refined, refined >= 0.6);
		assertTrue("And it is more so than what the preview said: " + refined + " vs. " + blurred,
			refined > blurred);
	}

	/** The crop the editor shows is cut from the file and is large enough to look at. */
	public void testTheCropIsCutFromTheOriginal() throws Exception {
		createSpace();
		if (!detectorAvailable()) {
			return;
		}
		File photograph = photograph(BIG, 4000, 3000, A_ONE, SMALL_FACE_WIDTH, Orientation.IDENTITY);
		index();

		FaceIndex.Crop crop = _servlet.faces().crop(photograph, 0);
		assertNull(crop.getReason(), crop.getReason());
		BufferedImage cut = ImageIO.read(crop.getFile());
		assertNotNull("Nothing was written.", cut);
		System.out.println("The crop of a 50 px face: " + cut.getWidth() + "x" + cut.getHeight() + ".");
		assertTrue("A crop of " + cut.getWidth() + "x" + cut.getHeight() + " is not worth showing.",
			Math.min(cut.getWidth(), cut.getHeight()) >= 256);
		assertTrue("And it is bounded.", Math.min(cut.getWidth(), cut.getHeight()) <= 512);

		// It shows the pasted portrait and not the background it was pasted on.
		assertFalse("The crop shows the canvas, not the face.", isBackground(cut, cut.getWidth() / 2,
			cut.getHeight() / 2));
	}

	/** A photograph on its side is cut and measured upright, as issue #124 made it. */
	public void testATurnedFileIsMeasuredAndCutUpright() throws Exception {
		createSpace();
		if (!detectorAvailable()) {
			return;
		}
		File photograph = photograph(SIDEWAYS, 4000, 3000, A_ONE, SMALL_FACE_WIDTH, Orientation.ROT_L);
		index();

		FaceCache.Face face = only(SIDEWAYS);
		assertTrue("A small face of a turned file is looked up too.", face.isRefined());

		// The file is 3000 x 4000 and is shown turned; upright, the face lies where it was pasted.
		double[] upright = de.haumacher.imageServer.faces.Faces.toUpright(Orientation.ROT_L,
			face.getX(), face.getY(), face.getW(), face.getH());
		double left = upright[0] * 4000;
		double top = upright[1] * 3000;
		double width = upright[2] * 4000;
		double height = upright[3] * 3000;
		assertTrue("The face is not where the portrait is: " + left + "," + top,
			left > _pasteX && left + width < _pasteX + SMALL_FACE_WIDTH
				&& top > _pasteY && top + height < _pasteY + 3 * SMALL_FACE_WIDTH);

		FaceIndex.Crop crop = _servlet.faces().crop(photograph, 0);
		assertNull(crop.getReason(), crop.getReason());
		BufferedImage cut = ImageIO.read(crop.getFile());
		assertTrue("A face crop is taller than it is wide, whichever way the file lies: "
			+ cut.getWidth() + "x" + cut.getHeight(), cut.getHeight() > cut.getWidth());
		assertTrue("And it is worth showing.", Math.min(cut.getWidth(), cut.getHeight()) >= 256);
	}

	/**
	 * A face that is large enough on the preview costs the original nothing — neither the
	 * description nor the crop opens it.
	 */
	public void testALargeFaceIsNeverRegionDecoded() throws Exception {
		createSpace();
		if (!detectorAvailable()) {
			return;
		}
		// 2400 x 1800 has a preview of 800 x 600, a third of the file: the portrait's 169 px of face
		// pasted 1100 px wide are 581 px in the file and 194 on the preview, so neither the
		// description nor the crop has anything to gain from the original.
		File photograph = photograph(BIG, 2400, 1800, C, 1100, Orientation.IDENTITY);

		long before = Originals.decodes();
		index();
		FaceCache.Face face = only(BIG);
		assertTrue("Large enough on the preview is described as well as this build can.",
			face.isRefined());
		assertEquals("The original was never opened in pieces.", before, Originals.decodes());

		FaceIndex.Crop crop = _servlet.faces().crop(photograph, 0);
		assertNull(crop.getReason(), crop.getReason());
		assertEquals("And the crop comes from the preview.", before, Originals.decodes());
		BufferedImage cut = ImageIO.read(crop.getFile());
		assertTrue("Which is large enough here: " + cut.getWidth() + "x" + cut.getHeight(),
			Math.min(cut.getWidth(), cut.getHeight()) >= 256);
	}

	// --- What is kept. ---

	/** A candidate too small on the preview is kept when the original confirms it. */
	public void testATinyFaceIsKeptWhenTheOriginalConfirmsIt() throws Exception {
		createSpace();
		if (!detectorAvailable()) {
			return;
		}
		File photograph = photograph(BIG, 4000, 3000, A_ONE, TINY_FACE_WIDTH, Orientation.IDENTITY);
		File preview = PreviewCache.createPreview(photograph);

		FaceDetection.Result seen = FaceDetection.detect(preview);
		assertEquals("The preview alone keeps nothing of a face this small.", 0, seen.getFaces().size());

		index();
		FaceCache.Face face = only(BIG);
		assertTrue("The original confirmed it, so it is kept.", face.isRefined());
		assertTrue("And it lies where the portrait was pasted.", inside(face, photograph));
	}

	/** And it is dropped again when nothing is found where the preview said something is. */
	public void testATinyFaceIsDroppedWhenNothingConfirmsIt() throws Exception {
		createSpace();
		if (!detectorAvailable()) {
			return;
		}
		File photograph = photograph(BIG, 4000, 3000, A_ONE, TINY_FACE_WIDTH, Orientation.IDENTITY);
		File preview = PreviewCache.createPreview(photograph);

		// A refiner that looks and finds nothing, which is what a candidate that is no face gets.
		FaceDetection.Result seen = FaceDetection.detect(preview, candidate -> null);
		assertEquals("Nothing confirmed it, so it is no face.", 0, seen.getFaces().size());

		// And with a refiner that confirms it, the very same pass keeps it.
		FaceDetection.Result confirmed = FaceDetection.detect(preview,
			candidate -> new FaceDetection.Refined(candidate.getX(), candidate.getY(), candidate.getW(),
				candidate.getH(), new float[] { 1, 0, 0 }));
		assertEquals("The one candidate is kept when it is confirmed.", 1, confirmed.getFaces().size());
		assertTrue(confirmed.getFaces().get(0).isRefined());
	}

	// --- The cache. ---

	/** A cache written before this build is read, and says that nothing was refined. */
	public void testACacheOfAnOlderBuildIsRead() throws Exception {
		File folder = Files.createTempDirectory("valbum-old-faces").toFile();
		Files.createDirectories(CacheRefresh.cacheDir(folder).toPath());
		Files.writeString(FaceCache.file(folder).toPath(),
			Files.readString(new File("src/test/fixtures/faces/faces-before-140.json").toPath(),
				StandardCharsets.UTF_8).replace("@MODEL@", FaceDetection.MODEL),
			StandardCharsets.UTF_8);

		FaceCache cache = new FaceCache(folder);
		List<FaceCache.Face> faces = cache.facesOf("11119ab0f4bf3aa3e5e0fa9b0a36ff6dc0a0d0e3a2e0c0d0e0f0a1b2c3d4e5f6");
		assertEquals(2, faces.size());
		assertEquals(0.31, faces.get(0).getX(), 1e-9);
		assertEquals("c1", faces.get(0).getCluster());
		assertEquals(128, faces.get(0).getEmbedding().length);
		assertFalse("Nobody said anything about the resolution back then.", faces.get(0).isRefined());
		assertFalse(faces.get(1).isRefined());
	}

	/** The walk comes back to a photograph an older build described from the preview alone. */
	public void testTheWalkRevisitsAnUnrefinedPhotograph() throws Exception {
		createSpace();
		if (!detectorAvailable()) {
			return;
		}
		photograph(BIG, 4000, 3000, A_ONE, SMALL_FACE_WIDTH, Orientation.IDENTITY);
		index();
		assertTrue(only(BIG).isRefined());

		// What the same album looked like before this build: the flag was not written at all.
		File file = FaceCache.file(album());
		String older = Files.readString(file.toPath(), StandardCharsets.UTF_8).replace(",\"refined\":true", "");
		assertFalse("The fixture must really lose the flag.", older.contains("refined"));
		Files.writeString(file.toPath(), older, StandardCharsets.UTF_8);
		assertFalse(only(BIG).isRefined());

		index();
		assertTrue("A face the preview alone described is looked at again.", only(BIG).isRefined());

		FileTime stamp = FileTime.fromMillis(System.currentTimeMillis() - 10000);
		Files.setLastModifiedTime(file.toPath(), stamp);
		index();
		assertEquals("And a second walk has nothing left to do.", stamp,
			Files.getLastModifiedTime(file.toPath()));
	}

	// --- The region decode. ---

	/** A piece of a large photograph is read as a piece, never as the whole raster. */
	public void testARegionIsNotTheWholeRaster() throws Exception {
		createSpace();
		File photograph = photograph(BIG, 4000, 3000, A_ONE, SMALL_FACE_WIDTH, Orientation.IDENTITY);

		try (ImageInputStream in = ImageIO.createImageInputStream(photograph)) {
			Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
			ImageReader reader = readers.next();
			reader.setInput(in, true, true);
			assertEquals(4000, reader.getWidth(0));
			assertEquals(3000, reader.getHeight(0));
			reader.dispose();
		}

		Originals.Region region =
			Originals.decodeShortSide(photograph, _pasteX, _pasteY, _pasteX + 600, _pasteY + 600, 256);
		BufferedImage image = region.getImage();
		assertEquals(_pasteX, region.getLeft());
		assertEquals(_pasteY, region.getTop());
		assertEquals("600 px of file at a short side of 256 px is every second pixel.", 2,
			region.getSampling());
		assertEquals(300, image.getWidth());
		assertEquals(300, image.getHeight());
		assertTrue("A region is a region, not the photograph.",
			image.getWidth() * image.getHeight() < 4000 * 3000 / 100);

		// And the same piece, read at full resolution, is where it was asked for.
		Originals.Region whole =
			Originals.decodeLongSide(photograph, _pasteX, _pasteY, _pasteX + 600, _pasteY + 600, 1600);
		assertEquals(1, whole.getSampling());
		assertEquals(600, whole.getImage().getWidth());
		assertEquals(4000, whole.getRawWidth());
	}

	/** The sampling is chosen, not guessed. */
	public void testTheSampling() {
		assertEquals(1, Originals.samplingForLongSide(1600, 1600));
		assertEquals(2, Originals.samplingForLongSide(2400, 1600));
		assertEquals(4, Originals.samplingForLongSide(6000, 1600));
		assertEquals(1, Originals.samplingForShortSide(300, 512));
		assertEquals(1, Originals.samplingForShortSide(1000, 512));
		assertEquals(2, Originals.samplingForShortSide(1024, 512));
		assertEquals(7, Originals.samplingForShortSide(4000, 512));
	}

	// --- The library. ---

	/** The one face of the named photograph of the album. */
	private FaceCache.Face only(String name) throws Exception {
		String hash = new HashCache(album()).storedHashByName().get(name);
		assertNotNull("No hash for '" + name + "'.", hash);
		List<FaceCache.Face> faces = new FaceCache(album()).facesOf(hash);
		assertEquals("Expected exactly one face in '" + name + "': " + faces.size(), 1, faces.size());
		return faces.get(0);
	}

	/** Whether the given face lies inside the pasted portrait of the given photograph. */
	private boolean inside(FaceCache.Face face, File photograph) throws Exception {
		BufferedImage image = ImageIO.read(photograph);
		int left = (int) Math.round(face.getX() * image.getWidth());
		int top = (int) Math.round(face.getY() * image.getHeight());
		int right = (int) Math.round((face.getX() + face.getW()) * image.getWidth());
		int bottom = (int) Math.round((face.getY() + face.getH()) * image.getHeight());
		return !isBackground(image, (left + right) / 2, (top + bottom) / 2)
			&& left >= _pasteX && top >= _pasteY;
	}

	/** Whether the given pixel is the canvas the portrait was pasted on. */
	private static boolean isBackground(BufferedImage image, int x, int y) {
		int rgb = image.getRGB(x, y);
		return Math.abs(((rgb >> 16) & 0xFF) - BACKGROUND.getRed()) < 24
			&& Math.abs(((rgb >> 8) & 0xFF) - BACKGROUND.getGreen()) < 24
			&& Math.abs((rgb & 0xFF) - BACKGROUND.getBlue()) < 24;
	}

	/**
	 * Writes a large photograph with one of the portraits pasted into it.
	 *
	 * @param name
	 *        The file to write into the album.
	 * @param width
	 *        The width of the picture as it is shown.
	 * @param height
	 *        Its height as it is shown.
	 * @param portrait
	 *        Which portrait of the fixtures to paste.
	 * @param pasted
	 *        How wide the portrait is pasted, in pixels.
	 * @param exif
	 *        {@link Orientation#IDENTITY} for an ordinary file, {@link Orientation#ROT_L} for one
	 *        whose raster lies on its side and that says so.
	 */
	private File photograph(String name, int width, int height, String portrait, int pasted,
			Orientation exif) throws Exception {
		BufferedImage source = ImageIO.read(new File(PORTRAITS, portrait));
		int pastedHeight = (int) Math.round(((double) pasted) * source.getHeight() / source.getWidth());
		// Wherever it fits; a clipped portrait is a different picture to the detector.
		_pasteX = Math.min(PASTE_X, width - pasted);
		_pasteY = Math.min(PASTE_Y, height - pastedHeight);
		assertTrue("The portrait does not fit into this canvas.", _pasteX >= 0 && _pasteY >= 0);

		BufferedImage shown = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = shown.createGraphics();
		try {
			graphics.setColor(BACKGROUND);
			graphics.fillRect(0, 0, width, height);
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			graphics.drawImage(source, _pasteX, _pasteY, pasted, pastedHeight, null);
		} finally {
			graphics.dispose();
		}

		File target = new File(album(), name);
		if (exif == Orientation.IDENTITY) {
			assertTrue(ImageIO.write(shown, "jpg", target));
			return target;
		}

		// The same picture, its raster a quarter turn anticlockwise and the EXIF flag that undoes it.
		BufferedImage turned = new BufferedImage(height, width, BufferedImage.TYPE_INT_RGB);
		Graphics2D turning = turned.createGraphics();
		try {
			AffineTransform transform = new AffineTransform();
			transform.translate(0, width);
			transform.rotate(-Math.PI / 2);
			turning.drawImage(shown, transform, null);
		} finally {
			turning.dispose();
		}
		File plain = new File(album(), name + ".plain");
		try {
			assertTrue(ImageIO.write(turned, "jpg", plain));
			Exif.writeOrientation(plain, target, 6);
		} finally {
			plain.delete();
		}
		return target;
	}
}
