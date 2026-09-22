/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * That the preview of {@link PreviewCache} shows the picture the file says it shows — for every one
 * of the eight EXIF orientations, the four mirrored ones included, see issue #143.
 *
 * <p>
 * Before issue #143 a mirrored file (the codes 2, 4, 5 and 7) was drawn <em>beside</em> the canvas:
 * the mirror was a {@code scale(-1, 1)} about the axis <code>x = 0</code>, which puts every pixel of
 * the picture at a negative coordinate, so the preview was an empty rectangle. Nothing showed it —
 * no camera writes a mirrored orientation — until the face index of issue #124 handed the detector
 * such a preview and was answered no face at all.
 * </p>
 *
 * <p>
 * The probe is a picture painted in four quarters of four colours: a preview that shows the same
 * four quarters in the same places is the picture, upright, and no other transform of the eight
 * produces it.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestPreviewOrientation extends TestCase {

	private static final Color TOP_LEFT = new Color(0xFF, 0x00, 0x00);

	private static final Color TOP_RIGHT = new Color(0x00, 0xFF, 0x00);

	private static final Color BOTTOM_LEFT = new Color(0x00, 0x00, 0xFF);

	private static final Color BOTTOM_RIGHT = new Color(0xFF, 0xFF, 0x00);

	private Path _dir;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_dir = Files.createTempDirectory("valbum-preview-orientation");
	}

	@Override
	protected void tearDown() throws Exception {
		if (_dir != null) {
			try (Stream<Path> files = Files.walk(_dir)) {
				files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
		super.tearDown();
	}

	/** The mirror of issue #143: a code-2 file is shown mirrored, not beside the canvas. */
	public void testMirroredFileIsShownMirrored() throws Exception {
		checkQuarters("m2.jpg", 800, 600, 2);
	}

	public void testEveryOrientationLandscape() throws Exception {
		for (int code = 1; code <= 8; code++) {
			checkQuarters("l" + code + ".jpg", 800, 600, code);
		}
	}

	public void testEveryOrientationPortrait() throws Exception {
		for (int code = 1; code <= 8; code++) {
			checkQuarters("p" + code + ".jpg", 600, 800, code);
		}
	}

	private void checkQuarters(String name, int width, int height, int code) throws Exception {
		File file = write(name, width, height, code);
		File preview = PreviewCache.createPreview(file);
		BufferedImage shown = ImageIO.read(preview);
		assertEquals(name + ": the preview keeps the shown aspect",
			Math.round(1000.0 * width / height), Math.round(1000.0 * shown.getWidth() / shown.getHeight()));
		int w = shown.getWidth(), h = shown.getHeight();
		assertQuarter(name, shown, w / 4, h / 4, TOP_LEFT, "top left");
		assertQuarter(name, shown, 3 * w / 4, h / 4, TOP_RIGHT, "top right");
		assertQuarter(name, shown, w / 4, 3 * h / 4, BOTTOM_LEFT, "bottom left");
		assertQuarter(name, shown, 3 * w / 4, 3 * h / 4, BOTTOM_RIGHT, "bottom right");
	}

	private static void assertQuarter(String name, BufferedImage image, int x, int y, Color expected, String where) {
		int rgb = image.getRGB(x, y);
		int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
		String found = String.format("#%02x%02x%02x", r, g, b);
		assertTrue(name + ": the " + where + " quarter of the preview is " + found + ", expected "
			+ String.format("#%02x%02x%02x", expected.getRed(), expected.getGreen(), expected.getBlue()),
			Math.abs(r - expected.getRed()) < 40 && Math.abs(g - expected.getGreen()) < 40
				&& Math.abs(b - expected.getBlue()) < 40);
	}

	/**
	 * A file whose <em>shown</em> picture is the four quarters, stored the way the given EXIF code
	 * says: the raster is built by the inverse of that code's mapping.
	 */
	private File write(String name, int width, int height, int code) throws Exception {
		BufferedImage shown = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = shown.createGraphics();
		try {
			graphics.setColor(TOP_LEFT);
			graphics.fillRect(0, 0, width / 2, height / 2);
			graphics.setColor(TOP_RIGHT);
			graphics.fillRect(width / 2, 0, width - width / 2, height / 2);
			graphics.setColor(BOTTOM_LEFT);
			graphics.fillRect(0, height / 2, width / 2, height - height / 2);
			graphics.setColor(BOTTOM_RIGHT);
			graphics.fillRect(width / 2, height / 2, width - width / 2, height - height / 2);
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
		File target = new File(_dir.toFile(), name);
		File plain = new File(_dir.toFile(), name + ".plain");
		try {
			assertTrue(ImageIO.write(stored, "jpg", plain));
			Exif.writeOrientation(plain, target, code);
		} finally {
			plain.delete();
		}
		return target;
	}
}
