/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.faces;

import de.haumacher.imageServer.PreviewCache;
import de.haumacher.imageServer.heif.TestHeifDecoder;
import de.haumacher.imageServer.shared.model.Orientation;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Test case that a HEIC turned by its container is one frame for the face index, see issues #142
 * and #186.
 *
 * <p>
 * The raw raster of a HEIC is the coded picture before <code>irot</code>/<code>imir</code>, and
 * those two are its orientation, so a box stored in the raw raster and turned upright by
 * {@link Faces#toUpright} lands where the preview shows the same pixels, and a region decoded from
 * the original by that raw box shows them too.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestHeicFaceFrame extends TestCase {

	public void testTheRawBoxAndThePreviewAgree() throws Exception {
		Path dir = Files.createTempDirectory("valbum-heic-faces");
		try {
			File file = dir.resolve("rotated.heic").toFile();
			Files.copy(new File(TestHeifDecoder.FIXTURES, "rotated.heic").toPath(), file.toPath());

			Orientation exif = FaceIndex.exifOrientation(file);
			assertEquals("irot 1, not the EXIF orientation 8 inside", Orientation.ROT_R, exif);
			int[] raw = FaceIndex.rasterOf(file);
			assertEquals(180, raw[0]);
			assertEquals(120, raw[1]);

			// The red quadrant, which is shown at the top left, lies at the top right of the raw raster.
			double[] rawBox = { 0.55, 0.05, 0.4, 0.4 };
			double[] upright = Faces.toUpright(exif, rawBox[0], rawBox[1], rawBox[2], rawBox[3]);
			BufferedImage preview = ImageIO.read(PreviewCache.createPreview(file));
			int cx = (int) ((upright[0] + upright[2] / 2) * preview.getWidth());
			int cy = (int) ((upright[1] + upright[3] / 2) * preview.getHeight());
			assertTrue("The upright box is in the top left quadrant: " + cx + "," + cy,
				cx < preview.getWidth() / 2 && cy < preview.getHeight() / 2);
			TestHeifDecoder.assertColour(TestHeifDecoder.RED, preview, cx, cy);

			double[] back = Faces.toRaw(exif, upright[0], upright[1], upright[2], upright[3]);
			for (int n = 0; n < 4; n++) {
				assertEquals(rawBox[n], back[n], 1e-9);
			}

			Originals.Region region = Originals.decodeLongSide(file, rawBox[0] * raw[0], rawBox[1] * raw[1],
				(rawBox[0] + rawBox[2]) * raw[0], (rawBox[1] + rawBox[3]) * raw[1], 32);
			BufferedImage crop = region.getImage();
			TestHeifDecoder.assertColour(TestHeifDecoder.RED, crop, crop.getWidth() / 2, crop.getHeight() / 2);
			assertEquals(raw[0], region.getRawWidth());
			assertEquals(raw[1], region.getRawHeight());
			BufferedImage turned = Originals.upright(crop, exif);
			assertEquals(crop.getHeight(), turned.getWidth());
		} finally {
			try (java.util.stream.Stream<Path> files = Files.walk(dir)) {
				files.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
	}
}
