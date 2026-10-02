/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.faces;

import de.haumacher.imageServer.RawFixtures;
import de.haumacher.imageServer.heif.TestHeifDecoder;
import de.haumacher.imageServer.shared.model.Orientation;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import junit.framework.TestCase;

/**
 * The frame of a face in a raw photograph, see issues #142 and #191.
 *
 * <p>
 * The raw raster of a raw photograph is the JPEG preview it carries, as stored; the raw's own EXIF
 * orientation turns it upright, once, and the preview's own orientation is never read. The face
 * index measures, decodes and turns by exactly that.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestRawFaceFrame extends TestCase {

	public void testTheRawRasterIsTheEmbeddedPreviewTurnedByTheRawsOrientation() throws Exception {
		Path dir = Files.createTempDirectory("valbum-raw-faces");
		File file = dir.resolve("solo.DNG").toFile();
		try {
			// Upright 128 x 192, stored on its side for orientation 6; the preview says 3 about itself.
			Files.write(file.toPath(), RawFixtures.dng(128, 192, 6, 3));

			int[] raster = FaceIndex.rasterOf(file);
			assertEquals(192, raster[0]);
			assertEquals(128, raster[1]);
			assertEquals(Orientation.ROT_L, FaceIndex.exifOrientation(file));
			assertTrue(FaceIndex.isPhotograph(file));

			Originals.Region region = Originals.decodeLongSide(file, 0, 0, 192, 128, 512);
			assertEquals(192, region.getImage().getWidth());
			BufferedImage upright = Originals.upright(region.getImage(), FaceIndex.exifOrientation(file));
			assertEquals(128, upright.getWidth());
			assertEquals(192, upright.getHeight());
			TestHeifDecoder.assertQuadrants(upright);
		} finally {
			file.delete();
			Files.delete(dir);
		}
	}
}
