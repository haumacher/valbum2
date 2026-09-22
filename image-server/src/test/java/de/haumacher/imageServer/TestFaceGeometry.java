/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.faces.Faces;
import de.haumacher.imageServer.shared.model.Orientation;
import de.haumacher.imageServer.shared.util.Orientations;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import junit.framework.TestCase;

/**
 * The frame a face box is written in, see issue #124 and {@link Faces}.
 *
 * <p>
 * Pure arithmetic and no detector: the rule that a box is kept in the raw raster of the file is
 * what makes a box survive a rotation, and it is worth pinning on its own, for all eight
 * orientations, before any picture is involved.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestFaceGeometry extends TestCase {

	private static final double EPSILON = 1e-9;

	/** A box of an upright file is written down unchanged. */
	public void testAnUprightFileKeepsItsBox() {
		assertBox(new double[] { 0.1, 0.2, 0.3, 0.4 },
			Faces.toRaw(Orientation.IDENTITY, 0.1, 0.2, 0.3, 0.4));
	}

	/**
	 * A file that says "turn me a quarter turn clockwise" has the box a quarter turn the other way.
	 *
	 * <p>
	 * The picture is upright on screen either way — that is the point of the EXIF flag — so the
	 * detector finds the face at the same place on the preview. What differs is where that place is
	 * in the file, and that is what is written down.
	 * </p>
	 */
	public void testAQuarterTurn() {
		// Upright: the face is in the upper left, wider than it is tall.
		double[] raw = Faces.toRaw(Orientation.ROT_L, 0.1, 0.2, 0.3, 0.4);

		// In the raster of the file it is in the upper right, and taller than it is wide.
		assertBox(new double[] { 0.2, 1 - 0.1 - 0.3, 0.4, 0.3 }, raw);
	}

	/** The other quarter turn, the other way round. */
	public void testTheOtherQuarterTurn() {
		double[] raw = Faces.toRaw(Orientation.ROT_R, 0.1, 0.2, 0.3, 0.4);

		assertBox(new double[] { 1 - 0.2 - 0.4, 0.1, 0.4, 0.3 }, raw);
	}

	/** A mirrored file mirrors the box. */
	public void testAMirroredFile() {
		assertBox(new double[] { 1 - 0.1 - 0.3, 0.2, 0.3, 0.4 },
			Faces.toRaw(Orientation.FLIP_H, 0.1, 0.2, 0.3, 0.4));
	}

	/** Upside down. */
	public void testUpsideDown() {
		assertBox(new double[] { 1 - 0.1 - 0.3, 1 - 0.2 - 0.4, 0.3, 0.4 },
			Faces.toRaw(Orientation.ROT_180, 0.1, 0.2, 0.3, 0.4));
	}

	/**
	 * Writing the box down and reading it back is the identity, for every one of the eight
	 * orientations.
	 *
	 * <p>
	 * The crop of <code>?type=face</code> depends on it: it cuts the piece out of the preview
	 * again, which means going the whole way back.
	 * </p>
	 */
	public void testEveryOrientationIsUndone() {
		for (int code = 1; code <= 8; code++) {
			Orientation exif = Orientations.fromCode(code);
			double[] raw = Faces.toRaw(exif, 0.11, 0.23, 0.37, 0.19);
			double[] back = Faces.toUpright(exif, raw[0], raw[1], raw[2], raw[3]);
			assertBox("Orientation " + code, new double[] { 0.11, 0.23, 0.37, 0.19 }, back);
		}
	}

	/** A box always stays inside the unit square, whatever the turn. */
	public void testTheBoxStaysInsideTheUnitSquare() {
		for (int code = 1; code <= 8; code++) {
			Orientation exif = Orientations.fromCode(code);
			double[] raw = Faces.toRaw(exif, 0.0, 0.0, 1.0, 1.0);
			assertBox("Orientation " + code, new double[] { 0, 0, 1, 1 }, raw);
		}
	}

	/**
	 * The preview generator turns a picture by exactly the table {@link Faces} writes a box back
	 * with, see issue #143.
	 *
	 * <p>
	 * Two representations of one permutation — a normalised point map here, an
	 * {@link java.awt.geom.AffineTransform} over pixels there — and the whole face pipeline rests
	 * on their being the same: a face is found on the preview and its box written into the raw
	 * raster by {@link Faces#toRaw(Orientation, double, double, double, double)}. When the preview
	 * mirrored a picture about the wrong axis nothing here noticed, because nothing here asked.
	 * </p>
	 */
	public void testThePreviewIsTurnedByTheSameTable() {
		double rawWidth = 400, rawHeight = 300;
		for (int code = 1; code <= 8; code++) {
			Orientation exif = Orientations.fromCode(code);
			AffineTransform tx = PreviewCache.orientationTransform(exif, rawWidth, rawHeight);
			double displayWidth = Orientations.width(exif, rawWidth, rawHeight);
			double displayHeight = Orientations.height(exif, rawWidth, rawHeight);
			for (double s : new double[] { 0, 0.17, 0.5, 0.83, 1 }) {
				for (double t : new double[] { 0, 0.29, 0.5, 0.71, 1 }) {
					// The upright box of a point-sized box at (s,t) is where that point is shown.
					double[] upright = Faces.toUpright(exif, s, t, 0, 0);
					Point2D shown = tx.transform(new Point2D.Double(s * rawWidth, t * rawHeight), null);
					assertEquals("Orientation " + code + " maps (" + s + "," + t + ") horizontally",
						upright[0] * displayWidth, shown.getX(), 1e-6);
					assertEquals("Orientation " + code + " maps (" + s + "," + t + ") vertically",
						upright[1] * displayHeight, shown.getY(), 1e-6);
				}
			}
		}
	}

	/** A quarter turn swaps the two sides of a box, and only a quarter turn does. */
	public void testWhichTurnsSwapTheSides() {
		assertFalse(Faces.swaps(Orientation.IDENTITY));
		assertFalse(Faces.swaps(Orientation.FLIP_H));
		assertFalse(Faces.swaps(Orientation.ROT_180));
		assertFalse(Faces.swaps(Orientation.FLIP_V));
		assertTrue(Faces.swaps(Orientation.ROT_L_FLIP_V));
		assertTrue(Faces.swaps(Orientation.ROT_L));
		assertTrue(Faces.swaps(Orientation.ROT_L_FLIP_H));
		assertTrue(Faces.swaps(Orientation.ROT_R));
	}

	private static void assertBox(double[] expected, double[] actual) {
		assertBox("", expected, actual);
	}

	private static void assertBox(String message, double[] expected, double[] actual) {
		assertEquals(message + " x", expected[0], actual[0], EPSILON);
		assertEquals(message + " y", expected[1], actual[1], EPSILON);
		assertEquals(message + " w", expected[2], actual[2], EPSILON);
		assertEquals(message + " h", expected[3], actual[3], EPSILON);
	}
}
