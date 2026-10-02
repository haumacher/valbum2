/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Probes of #192 the delivery did not write: an upper-case camcorder file moved into another album
 * keeps its MDPM date, and the zip of an AVI and a WebM carries their bytes.
 */
@SuppressWarnings("javadoc")
public class TestFfmpegContainersProbe extends ShareTestCase {

	private static final File FIXTURES = new File("src/test/fixtures/video");

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		Files.createDirectories(_base.resolve("A"));
		Files.createDirectories(_base.resolve("B"));
	}

	public void testAMovedMtsKeepsItsRecordingDate() throws Exception {
		Files.copy(new File(FIXTURES, "clip.mts").toPath(), _base.resolve("A/00001.MTS"));
		String before = get("/A/", "json", SharingFixture.ALICE).body();
		assertTrue(before, before.contains("00001.MTS"));
		String date = dateOf(before, "00001.MTS");

		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "move");
		FakeResponse moved = post("/A/", "{\"target\":\"B\",\"names\":[{\"name\":\"00001.MTS\"}]}",
			SharingFixture.ALICE, parameters);
		assertEquals(moved.body(), HttpServletResponse.SC_OK, moved.status());

		String after = get("/B/", "json", SharingFixture.ALICE).body();
		assertEquals(date, dateOf(after, "00001.MTS"));
		assertEquals(HttpServletResponse.SC_OK, get("/B/00001.MTS", "tn", SharingFixture.ALICE).status());
		assertEquals("video/mp2t", get("/B/00001.MTS", null, SharingFixture.ALICE).contentType());
	}

	public void testTheZipCarriesTheOriginals() throws Exception {
		Files.copy(new File(FIXTURES, "clip.avi").toPath(), _base.resolve("A/clip.AVI"));
		Files.copy(new File(FIXTURES, "screen-2024-05-17_12-37-00.webm").toPath(), _base.resolve("A/s.webm"));
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", ImageServlet.ZIP_ACTION);
		FakeResponse zip = post("/A/", "{\"target\":\"\",\"names\":[{\"name\":\"clip.AVI\"},{\"name\":\"s.webm\"}]}",
			SharingFixture.ALICE, parameters);
		assertEquals(zip.body(), HttpServletResponse.SC_OK, zip.status());
		int entries = 0;
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip.bodyBytes()))) {
			for (ZipEntry entry; (entry = in.getNextEntry()) != null;) {
				byte[] expected = Files.readAllBytes(_base.resolve("A").resolve(entry.getName()));
				assertTrue(entry.getName(), Arrays.equals(expected, in.readAllBytes()));
				entries++;
			}
		}
		assertEquals(2, entries);
	}

	private static String dateOf(String json, String name) throws Exception {
		de.haumacher.imageServer.shared.model.AlbumInfo album =
			(de.haumacher.imageServer.shared.model.AlbumInfo) de.haumacher.imageServer.shared.model.Resource
				.readResource(reader(json));
		for (de.haumacher.imageServer.shared.model.AlbumPart part : album.getParts()) {
			if (part instanceof de.haumacher.imageServer.shared.model.ImagePart image && name.equals(image.getName())) {
				assertTrue("A date", image.getDate() > 0);
				return Long.toString(image.getDate());
			}
		}
		fail("No " + name);
		return null;
	}
}
