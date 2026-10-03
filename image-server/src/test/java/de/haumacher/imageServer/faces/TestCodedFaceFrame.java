/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.faces;

import de.haumacher.imageServer.PreviewCache;
import de.haumacher.imageServer.heif.TestAvifDecoder;
import de.haumacher.imageServer.heif.TestHeifDecoder;
import de.haumacher.imageServer.jxl.TestJxlFile;
import de.haumacher.imageServer.shared.model.Orientation;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Test case for the face frame of an AVIF and a JPEG XL picture, see issues #142 and #193.
 *
 * <p>
 * The raw raster of either is the coded picture before the orientation its container
 * (<code>irot</code>) or codestream declares, so a box stored in the raw raster and turned upright
 * by {@link Faces#toUpright} lands where the preview shows the same pixels, and a region decoded
 * from the original by that raw box shows them too — exactly as {@link TestHeicFaceFrame} pins it
 * for a HEIC.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestCodedFaceFrame extends TestCase {

	@Override
	protected void tearDown() throws Exception {
		de.haumacher.imageServer.heif.HeifDecoder.setProgramLocator(null);
		super.tearDown();
	}

	public void testARotatedJxl() throws Exception {
		// Orientation 6: the shown top left (red) is the stored bottom left.
		assertFrame(new File(TestJxlFile.FIXTURES, "rotated.jxl"), Orientation.ROT_L, 64, 96,
			new double[] { 0.05, 0.55, 0.4, 0.4 });
	}

	public void testARotatedAvif() throws Exception {
		if (!TestAvifDecoder.av1Decoder(getName())) {
			return;
		}
		// irot 1: the shown top left (red) is the stored top right.
		assertFrame(new File(TestAvifDecoder.FIXTURES, "rotated.avif"), Orientation.ROT_R, 180, 120,
			new double[] { 0.55, 0.05, 0.4, 0.4 });
	}

	private static void assertFrame(File fixture, Orientation orientation, int rawWidth, int rawHeight,
			double[] rawBox) throws Exception {
		Path dir = Files.createTempDirectory("valbum-coded-faces");
		try {
			File file = dir.resolve(fixture.getName()).toFile();
			Files.copy(fixture.toPath(), file.toPath());

			Orientation exif = FaceIndex.exifOrientation(file);
			assertEquals("The container's or codestream's orientation, not the EXIF inside", orientation, exif);
			int[] raw = FaceIndex.rasterOf(file);
			assertEquals(rawWidth, raw[0]);
			assertEquals(rawHeight, raw[1]);

			double[] upright = Faces.toUpright(exif, rawBox[0], rawBox[1], rawBox[2], rawBox[3]);
			BufferedImage preview = ImageIO.read(PreviewCache.createPreview(file));
			int cx = (int) ((upright[0] + upright[2] / 2) * preview.getWidth());
			int cy = (int) ((upright[1] + upright[3] / 2) * preview.getHeight());
			assertTrue("The upright box is in the top left quadrant: " + cx + "," + cy,
				cx < preview.getWidth() / 2 && cy < preview.getHeight() / 2);
			TestHeifDecoder.assertColour(TestHeifDecoder.RED, preview, cx, cy);

			Originals.Region region = Originals.decodeLongSide(file, rawBox[0] * raw[0], rawBox[1] * raw[1],
				(rawBox[0] + rawBox[2]) * raw[0], (rawBox[1] + rawBox[3]) * raw[1], 16);
			BufferedImage crop = region.getImage();
			TestHeifDecoder.assertColour(TestHeifDecoder.RED, crop, crop.getWidth() / 2, crop.getHeight() / 2);
			assertEquals(raw[0], region.getRawWidth());
			assertEquals(raw[1], region.getRawHeight());
		} finally {
			try (java.util.stream.Stream<Path> files = Files.walk(dir)) {
				files.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
	}
}
