/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Review probe of #193/#210: one batch holding every coded format — HEIC, AVIF, JPEG XL — beside a
 * JPEG is stored, previewed upright and moved as a whole into another album, where each is
 * previewed again.
 */
@SuppressWarnings("javadoc")
public class TestCodedFormatsProbe extends ShareTestCase {

	private static final File FIXTURES = new File("src/test/fixtures");

	public void testEveryCodedFormatInOneBatchAndAfterAMove() throws Exception {
		Files.createDirectories(_base.resolve("A"));
		Files.createDirectories(_base.resolve("B"));
		LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
		files.put("a.jpg", Files.readAllBytes(new File(FIXTURES, "test-album/2005-08-24 Blumen und Fliegen/IMG_0417.JPG").toPath()));
		files.put("h.HEIC", Files.readAllBytes(new File(FIXTURES, "heic/rotated.heic").toPath()));
		files.put("v.avif", Files.readAllBytes(new File(FIXTURES, "avif/rotated.avif").toPath()));
		files.put("x.jxl", Files.readAllBytes(new File(FIXTURES, "jxl/rotated.jxl").toPath()));
		FakeResponse upload = upload("/A/", SharingFixture.ALICE, files);
		assertEquals(upload.body(), HttpServletResponse.SC_OK, upload.status());
		assertFalse(upload.body(), upload.body().contains("refused\":[{"));

		for (String name : new String[] { "h.HEIC", "v.avif", "x.jxl" }) {
			assertUpright(get("/A/" + name, "tn", SharingFixture.ALICE), name);
		}

		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "move");
		FakeResponse moved = post("/A/", "{\"target\":\"B\",\"names\":[{\"name\":\"h.HEIC\"},{\"name\":\"v.avif\"},"
			+ "{\"name\":\"x.jxl\"}]}", SharingFixture.ALICE, parameters);
		assertEquals(moved.body(), HttpServletResponse.SC_OK, moved.status());
		for (String name : new String[] { "h.HEIC", "v.avif", "x.jxl" }) {
			assertUpright(get("/B/" + name, "tn", SharingFixture.ALICE), name);
			assertEquals(name, HttpServletResponse.SC_OK, get("/B/" + name, ImageServlet.DISPLAY_TYPE, SharingFixture.ALICE).status());
		}
	}

	private static void assertUpright(FakeResponse response, String name) throws Exception {
		assertEquals(name + ": " + response.body(), HttpServletResponse.SC_OK, response.status());
		BufferedImage picture = ImageIO.read(new ByteArrayInputStream(response.bodyBytes()));
		assertNotNull(name, picture);
		// Upright per the fixtures' READMEs: the HEIC and the AVIF stand, the JPEG XL lies.
		boolean portrait = !name.endsWith(".jxl");
		assertEquals(name + ": " + picture.getWidth() + "x" + picture.getHeight(), portrait,
			picture.getHeight() > picture.getWidth());
	}
}
