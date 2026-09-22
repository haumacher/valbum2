/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.faces.FaceCache;
import de.haumacher.imageServer.faces.FaceIndex;
import de.haumacher.imageServer.faces.Faces;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.FaceInfo;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Orientation;
import de.haumacher.imageServer.upload.HashCache;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Probe of issues #140, #141 and #142 over the four EXIF orientations a camera writes and both aspect
 * ratios: the stored box brought upright, the box the wire answers (already upright, #142) and the
 * crop must all show the pasted portrait. The mirrored codes are issue #143.
 */
@SuppressWarnings("javadoc")
public class TestFaceOrientationsProbe extends FacesTestCase {

	private static final Color BACKGROUND = new Color(0x40, 0x80, 0xC0);

	private static final int SMALL = 928;

	public void testEveryOrientationLandscape() throws Exception {
		check(4000, 3000);
	}

	public void testEveryOrientationPortrait() throws Exception {
		check(3000, 4000);
	}

	private void check(int width, int height) throws Exception {
		createSpace();
		if (!detectorAvailable()) {
			return;
		}
		int pasteX = 1500, pasteY = 300;
		int[] codes = { 1, 3, 6, 8 };
		for (int code : codes) {
			paste("o" + code + ".jpg", width, height, A_ONE, SMALL, pasteX, pasteY, code);
		}
		index();
		AlbumInfo answered = album("/" + ALBUM + "/", _adminToken);
		StringBuilder problems = new StringBuilder();
		for (int code : codes) {
			String name = "o" + code + ".jpg";
			Orientation exif = Orientation.values()[code - 1];
			try {
				FaceCache.Face face = only(name);
				double[] up = Faces.toUpright(exif, face.getX(), face.getY(), face.getW(), face.getH());
				double left = up[0] * width, top = up[1] * height, w = up[2] * width, h = up[3] * height;
				boolean inside = left >= pasteX - 20 && top >= pasteY - 20 && left + w <= pasteX + SMALL + 20
					&& top + h <= pasteY + 3 * SMALL;
				// The wire speaks the frame of the shown picture (#142): the same place, no conversion.
				ImagePart part = image(answered, name);
				assertEquals(name + " answers one face", 1, part.getFaces().size());
				FaceInfo wire = part.getFaces().get(0);
				double wl = wire.getX() * width, wt = wire.getY() * height;
				double ww = wire.getW() * width, wh = wire.getH() * height;
				boolean wireInside = wl >= pasteX - 20 && wt >= pasteY - 20 && wl + ww <= pasteX + SMALL + 20
					&& wt + wh <= pasteY + 3 * SMALL && Math.abs(wl - left) < 2 && Math.abs(wt - top) < 2;
				inside = inside && wireInside;
				FaceIndex.Crop crop = _servlet.faces().crop(new File(album(), name), 0);
				String cropProblem = crop.getReason();
				boolean cropShowsFace = false;
				if (cropProblem == null) {
					BufferedImage cut = ImageIO.read(crop.getFile());
					cropShowsFace = !isBackground(cut, cut.getWidth() / 2, cut.getHeight() / 2);
				}
				System.out.println(String.format("%s exif=%d refined=%s upright=%.0f,%.0f %.0fx%.0f wire=%.0f,%.0f inside=%s crop=%s",
					name, code, face.isRefined(), left, top, w, h, wl, wt, inside, cropProblem == null ? (cropShowsFace ? "face" : "BACKGROUND") : cropProblem));
				if (!inside || !cropShowsFace) {
					problems.append(name).append(" ");
				}
			} catch (Throwable ex) {
				System.out.println(name + " exif=" + code + " -> " + ex);
				problems.append(name).append("(").append(ex.getMessage()).append(") ");
			}
		}
		assertEquals("Problems: " + problems, "", problems.toString());
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

	/** Writes the shown picture as a file whose raster is stored the way EXIF code says. */
	private File paste(String name, int width, int height, String portrait, int pasted, int x, int y, int code)
			throws Exception {
		BufferedImage source = ImageIO.read(new File(PORTRAITS, portrait));
		int pastedHeight = (int) Math.round(((double) pasted) * source.getHeight() / source.getWidth());
		BufferedImage shown = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = shown.createGraphics();
		try {
			graphics.setColor(BACKGROUND);
			graphics.fillRect(0, 0, width, height);
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			graphics.drawImage(source, x, y, pasted, pastedHeight, null);
		} finally {
			graphics.dispose();
		}
		boolean transposed = code >= 5;
		int rw = transposed ? height : width;
		int rh = transposed ? width : height;
		int[] shownPixels = shown.getRGB(0, 0, width, height, null, 0, width);
		int[] raw = new int[rw * rh];
		for (int ry = 0; ry < rh; ry++) {
			for (int rx = 0; rx < rw; rx++) {
				int dx, dy;
				switch (code) {
					case 1: dx = rx; dy = ry; break;
					case 2: dx = rw - 1 - rx; dy = ry; break;
					case 3: dx = rw - 1 - rx; dy = rh - 1 - ry; break;
					case 4: dx = rx; dy = rh - 1 - ry; break;
					case 5: dx = ry; dy = rx; break;
					case 6: dx = rh - 1 - ry; dy = rx; break;
					case 7: dx = rh - 1 - ry; dy = rw - 1 - rx; break;
					default: dx = ry; dy = rw - 1 - rx; break;
				}
				raw[ry * rw + rx] = shownPixels[dy * width + dx];
			}
		}
		BufferedImage stored = new BufferedImage(rw, rh, BufferedImage.TYPE_INT_RGB);
		stored.setRGB(0, 0, rw, rh, raw, 0, rw);
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
}
