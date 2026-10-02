/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.FaceInfo;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Test case that the face index looks at a WebP as at a JPEG, see issue #190.
 *
 * <p>
 * <code>portrait.webp</code> is <code>portrait-b.jpg</code> re-encoded, so the one face is found
 * in both, at nearly the same place, and the two are one person; the crop of the WebP's face is a
 * JPEG cut from the original by a region decode.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestWebpFaces extends FacesTestCase {

	private static final String WEBP = "portrait.webp";

	public void testAWebpIsLookedAtAsAJpegIs() throws Exception {
		createSpace(B);
		Files.copy(new File(TestWebpGif.FIXTURES, WEBP).toPath(), album().toPath().resolve(WEBP));
		if (!detectorAvailable()) {
			return;
		}
		index();

		AlbumInfo album = album("/" + ALBUM + "/", _adminToken);
		assertEquals(1, image(album, WEBP).getFaces().size());
		FaceInfo webp = image(album, WEBP).getFaces().get(0);
		FaceInfo jpeg = image(album, B).getFaces().get(0);
		assertEquals(jpeg.getX(), webp.getX(), 0.03);
		assertEquals(jpeg.getY(), webp.getY(), 0.03);
		assertEquals(jpeg.getW(), webp.getW(), 0.03);
		assertEquals(jpeg.getH(), webp.getH(), 0.03);
		assertEquals("The same picture is the same person.", jpeg.getCluster(), webp.getCluster());

		Map<String, String> parameters = new HashMap<>();
		parameters.put("face", Integer.toString(webp.getIndex()));
		FakeResponse crop = get("/" + ALBUM + "/" + WEBP, ImageServlet.FACE_TYPE, _adminToken, parameters);
		assertEquals(crop.body(), 200, crop.status());
		BufferedImage picture = ImageIO.read(new ByteArrayInputStream(crop.bodyBytes()));
		assertNotNull("A JPEG crop", picture);
		assertTrue(picture.getWidth() > 0 && picture.getHeight() > 0);
	}
}
