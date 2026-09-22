/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.faces.FaceCache;
import de.haumacher.imageServer.faces.FaceIndex;
import de.haumacher.imageServer.faces.Originals;
import de.haumacher.imageServer.upload.HashCache;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Probe of issue #140 composed with what was there before: a face in the very corner of the
 * file, whose margin has to be clipped in both frames; a PNG original, whose reader is not the
 * JPEG one; and a picture the preview does not shrink, carrying a cache of the older build.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestFaceRefinementProbe extends FacesTestCase {

	private static final Color BACKGROUND = new Color(0x40, 0x80, 0xC0);

	/** 928 px of portrait in a 4000 px file is a 50 px face on the 800 px preview. */
	private static final int SMALL = 928;

	/** A face in the top-left and one in the bottom-right corner: nothing to clip against but the raster. */
	public void testAFaceInTheCornerIsCutWithItsMarginClipped() throws Exception {
		createSpace();
		if (!detectorAvailable()) {
			return;
		}
		File topLeft = paste("top-left.jpg", "jpg", 4000, 3000, A_ONE, SMALL, 0, 0);
		File bottomRight = paste("bottom-right.jpg", "jpg", 4000, 3000, A_TWO, SMALL, -1, -1);
		index();

		for (File photograph : List.of(topLeft, bottomRight)) {
			FaceCache.Face face = only(photograph.getName());
			assertTrue(photograph.getName() + " is described from the original.", face.isRefined());
			assertTrue("The box stays inside the raster: " + face.getX() + "," + face.getY(),
				face.getX() >= 0 && face.getY() >= 0 && face.getX() + face.getW() <= 1.0001
					&& face.getY() + face.getH() <= 1.0001);

			FaceIndex.Crop crop = _servlet.faces().crop(photograph, 0);
			assertNull(crop.getReason(), crop.getReason());
			BufferedImage cut = ImageIO.read(crop.getFile());
			assertNotNull(cut);
			assertTrue(photograph.getName() + ": " + cut.getWidth() + "x" + cut.getHeight(),
				Math.min(cut.getWidth(), cut.getHeight()) >= 200);
			assertFalse("The crop shows the canvas, not the face.",
				isBackground(cut, cut.getWidth() / 2, cut.getHeight() / 2));
		}
	}

	/** A PNG is an original too, and its reader must cut a piece like the JPEG one. */
	public void testAPngOriginalIsDescribedAndCutInPieces() throws Exception {
		createSpace();
		if (!detectorAvailable()) {
			return;
		}
		File photograph = paste("big.png", "png", 4000, 3000, A_ONE, SMALL, 1200, 700);
		long before = Originals.decodes();
		index();
		assertTrue(only("big.png").isRefined());
		assertTrue("The PNG was opened in pieces for the description.", Originals.decodes() > before);

		long beforeCrop = Originals.decodes();
		FaceIndex.Crop crop = _servlet.faces().crop(photograph, 0);
		assertNull(crop.getReason(), crop.getReason());
		assertTrue("And for the crop.", Originals.decodes() > beforeCrop);
		BufferedImage cut = ImageIO.read(crop.getFile());
		assertTrue(cut.getWidth() + "x" + cut.getHeight(), Math.min(cut.getWidth(), cut.getHeight()) >= 256);
		assertFalse(isBackground(cut, cut.getWidth() / 2, cut.getHeight() / 2));
	}

	/**
	 * A picture the preview shows at its own size has nothing to gain from its file: an older
	 * cache of it is neither revisited with the original nor rewritten on every walk.
	 */
	public void testAPictureThePreviewDoesNotShrinkIsLeftAlone() throws Exception {
		createSpace();
		if (!detectorAvailable()) {
			return;
		}
		// 800 x 600 is the preview size itself; the portrait's 86 px face stays below REFINE_PIXELS.
		paste("small.jpg", "jpg", 800, 600, A_ONE, 320, 100, 100);
		index();
		File file = FaceCache.file(album());
		String older = Files.readString(file.toPath(), StandardCharsets.UTF_8).replace(",\"refined\":true", "");
		Files.writeString(file.toPath(), older, StandardCharsets.UTF_8);
		FileTime stamp = FileTime.fromMillis(System.currentTimeMillis() - 10000);
		Files.setLastModifiedTime(file.toPath(), stamp);

		long before = Originals.decodes();
		index();
		assertEquals("Nothing to gain, so the original is never opened in pieces.", before, Originals.decodes());
		assertEquals("And nothing is rewritten.", stamp, Files.getLastModifiedTime(file.toPath()));

		FaceIndex.Crop crop = _servlet.faces().crop(new File(album(), "small.jpg"), 0);
		assertNull(crop.getReason(), crop.getReason());
		assertEquals("The crop of an unshrunk picture comes from the preview.", before, Originals.decodes());
	}

	private FaceCache.Face only(String name) throws Exception {
		String hash = new HashCache(album()).storedHashByName().get(name);
		assertNotNull("No hash for '" + name + "'.", hash);
		List<FaceCache.Face> faces = new FaceCache(album()).facesOf(hash);
		assertEquals("Expected exactly one face in '" + name + "': " + faces.size(), 1, faces.size());
		return faces.get(0);
	}

	private static boolean isBackground(BufferedImage image, int x, int y) {
		int rgb = image.getRGB(x, y);
		return Math.abs(((rgb >> 16) & 0xFF) - BACKGROUND.getRed()) < 24
			&& Math.abs(((rgb >> 8) & 0xFF) - BACKGROUND.getGreen()) < 24
			&& Math.abs((rgb & 0xFF) - BACKGROUND.getBlue()) < 24;
	}

	/** Pastes the portrait at the given place, -1 meaning "against the far edge". */
	private File paste(String name, String format, int width, int height, String portrait, int pasted,
			int x, int y) throws Exception {
		BufferedImage source = ImageIO.read(new File(PORTRAITS, portrait));
		int pastedHeight = (int) Math.round(((double) pasted) * source.getHeight() / source.getWidth());
		int pasteX = x < 0 ? width - pasted : x;
		int pasteY = y < 0 ? height - pastedHeight : y;
		BufferedImage shown = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = shown.createGraphics();
		try {
			graphics.setColor(BACKGROUND);
			graphics.fillRect(0, 0, width, height);
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			graphics.drawImage(source, pasteX, pasteY, pasted, pastedHeight, null);
		} finally {
			graphics.dispose();
		}
		File target = new File(album(), name);
		assertTrue(ImageIO.write(shown, format, target));
		return target;
	}
}
