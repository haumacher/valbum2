/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.Rights;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Probe of #191 the delivery did not write: the raw of a photo is exactly as visible as the
 * photo — through a share link that may show public photos only, the raw of a private photo is
 * neither served nor zipped, while the raw of a public one is.
 */
@SuppressWarnings("javadoc")
public class TestRawPairsProbe extends ShareTestCase {

	public void testTheRawFollowsThePrivacyOfItsPhoto() throws Exception {
		Path zoo = _base.resolve(SharingFixture.ZOO);
		Files.write(zoo.resolve("public.CR2"), "raw of the public one".getBytes());
		Files.write(zoo.resolve("private.CR2"), "raw of the private one".getBytes());
		String link = zooToken(Rights.VIEW, Rights.DOWNLOAD);

		String listing = get("/", "json", link).body();
		assertFalse("A raw is no tile of its own: " + listing, listing.matches("(?s).*\"name\":\\s*\"public\\.CR2\".*"));

		FakeResponse open = get("/public.CR2", null, link);
		assertEquals(open.body(), HttpServletResponse.SC_OK, open.status());
		assertEquals("raw of the public one", open.body());

		FakeResponse secret = get("/private.CR2", null, link);
		assertTrue("The private raw is refused: " + secret.status(), secret.status() >= 400);
		assertFalse(secret.body().contains("raw of the private one"));

		assertTrue(zipNames(link, "private.jpg").isEmpty());
		List<String> zipped = zipNames(link, "public.jpg");
		assertTrue(zipped.toString(), zipped.contains("public.CR2"));
		assertTrue(zipped.toString(), zipped.contains("public.jpg"));

		// The owner sees both.
		assertEquals(HttpServletResponse.SC_OK,
			get("/" + SharingFixture.ZOO + "/private.CR2", null, SharingFixture.ALICE).status());
	}

	private List<String> zipNames(String token, String name) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", ImageServlet.ZIP_ACTION);
		FakeResponse zip =
			post("/", "{\"target\":\"\",\"names\":[{\"name\":\"" + name + "\"}]}", token, parameters);
		List<String> names = new ArrayList<>();
		if (zip.status() != HttpServletResponse.SC_OK) {
			return names;
		}
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip.bodyBytes()))) {
			for (ZipEntry entry; (entry = in.getNextEntry()) != null;) {
				names.add(entry.getName());
			}
		}
		return names;
	}
}
