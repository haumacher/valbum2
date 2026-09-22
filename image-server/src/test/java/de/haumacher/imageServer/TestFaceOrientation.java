/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.faces.FaceCache;
import de.haumacher.imageServer.upload.HashCache;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * A photograph that lies on its side in its file answers its faces in that file's own raster, see
 * issue #124.
 *
 * <p>
 * The rotated copy is made here rather than checked in: the same picture, its pixels turned a
 * quarter turn anticlockwise and an EXIF orientation of 6 written on it, which is exactly what a
 * phone held sideways produces. On screen the two are the same picture, so the detector finds the
 * same face at the same place on the preview; in the file they are not, and that is what the stored
 * box has to say.
 * </p>
 *
 * <p>
 * What is <em>stored</em> is asked here; since issue #142 the wire speaks another frame — a box is
 * answered upright, in the frame of the rendition the client draws it on — which is asked in
 * {@link TestFaceWireFrame}. The two must not be confused: the cache and the album's own
 * {@link de.haumacher.imageServer.shared.model.FaceTag decisions} stay in the raw raster, and only
 * the answer is turned.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestFaceOrientation extends FacesTestCase {

	/** The picture on its side, with the flag that turns it upright again. */
	private static final String SIDEWAYS = "sideways.jpg";

	/**
	 * The box of the turned file is the box of the upright one, turned.
	 *
	 * <p>
	 * A quarter turn clockwise for display means the file's own raster is a quarter turn
	 * anticlockwise, so <code>x</code> of the file is <code>y</code> on screen, the two sides of the
	 * box swap, and the other axis is counted from the far edge.
	 * </p>
	 */
	public void testAPhotographOnItsSide() throws Exception {
		createSpace(A_ONE);
		if (!detectorAvailable()) {
			return;
		}
		turnSideways(new File(album(), A_ONE), new File(album(), SIDEWAYS));
		index();

		FaceCache cache = new FaceCache(album());
		FaceCache.Face upright = only(cache, A_ONE);
		FaceCache.Face sideways = only(cache, SIDEWAYS);

		// The very rule of Faces#toRaw, read off two real files.
		assertEquals("The left edge of the file is the top edge of the screen.",
			upright.getY(), sideways.getX(), 0.04);
		assertEquals("The top edge of the file is the far right edge of the screen.",
			1 - upright.getX() - upright.getW(), sideways.getY(), 0.04);
		assertEquals("The two sides swap.", upright.getH(), sideways.getW(), 0.04);
		assertEquals("The two sides swap.", upright.getW(), sideways.getH(), 0.04);

		assertFalse("A turned picture is not the same picture to the box.",
			Math.abs(upright.getX() - sideways.getX()) < 0.01
				&& Math.abs(upright.getY() - sideways.getY()) < 0.01);

		// And on the wire the two are the same picture again, see issue #142.
		de.haumacher.imageServer.shared.model.AlbumInfo album = album("/" + ALBUM + "/", _adminToken);
		de.haumacher.imageServer.shared.model.FaceInfo shown = wire(album, A_ONE);
		de.haumacher.imageServer.shared.model.FaceInfo turned = wire(album, SIDEWAYS);
		assertEquals("The answer is upright, whatever the file does.", shown.getX(), turned.getX(), 0.04);
		assertEquals(shown.getY(), turned.getY(), 0.04);
		assertEquals(shown.getW(), turned.getW(), 0.04);
		assertEquals(shown.getH(), turned.getH(), 0.04);
	}

	/** The one face of the given photograph, as the album answers it. */
	private static de.haumacher.imageServer.shared.model.FaceInfo wire(
			de.haumacher.imageServer.shared.model.AlbumInfo album, String name) {
		de.haumacher.imageServer.shared.model.ImagePart image = image(album, name);
		assertEquals("Expected one face in '" + name + "'.", 1, image.getFaces().size());
		return image.getFaces().get(0);
	}

	/** And the same person is still the same person, however the file lies. */
	public void testATurnedPictureIsTheSamePerson() throws Exception {
		createSpace(A_ONE);
		if (!detectorAvailable()) {
			return;
		}
		turnSideways(new File(album(), A_ONE), new File(album(), SIDEWAYS));
		index();

		FaceCache cache = new FaceCache(album());
		assertEquals("Upright and sideways are one person.", only(cache, A_ONE).getCluster(),
			only(cache, SIDEWAYS).getCluster());
	}

	/** Writes the given picture with its raster turned a quarter turn left and an EXIF 6 on it. */
	private static void turnSideways(File source, File target) throws Exception {
		BufferedImage image = ImageIO.read(source);
		BufferedImage turned = new BufferedImage(image.getHeight(), image.getWidth(), image.getType());
		Graphics2D graphics = turned.createGraphics();
		AffineTransform transform = new AffineTransform();
		// A quarter turn anticlockwise, so that the EXIF flag "turn me clockwise" undoes it.
		transform.translate(0, image.getWidth());
		transform.rotate(-Math.PI / 2);
		graphics.drawImage(image, transform, null);
		graphics.dispose();

		File plain = new File(target.getParentFile(), target.getName() + ".plain");
		try {
			assertTrue(ImageIO.write(turned, "jpg", plain));
			Exif.writeOrientation(plain, target, 6);
		} finally {
			plain.delete();
		}
	}

	private FaceCache.Face only(FaceCache cache, String name) throws Exception {
		String hash = new HashCache(album()).storedHashByName().get(name);
		assertNotNull("No hash for '" + name + "'.", hash);
		List<FaceCache.Face> faces = cache.facesOf(hash);
		assertEquals("Expected exactly one face in '" + name + "'.", 1, faces.size());
		return faces.get(0);
	}
}
