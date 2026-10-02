/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.nio.file.Files;

/**
 * Probe of #207 the delivery did not write: a picture refused for its size spoils neither the
 * listing nor its neighbours, and the refusal names it; the budget restored, it is served.
 */
@SuppressWarnings("javadoc")
public class TestPictureReaderProbe extends ShareTestCase {

	private static final File FIXTURES = new File("src/test/fixtures/webp-gif");

	private static final File JPEG =
		new File("src/test/fixtures/test-album/2005-08-24 Blumen und Fliegen/IMG_0417.JPG");

	public void testATooLargePictureIsRefusedAloneAndByName() throws Exception {
		Files.createDirectories(_base.resolve("A"));
		Files.copy(new File(FIXTURES, "lossless-4mp.webp").toPath(), _base.resolve("A/big.webp"));
		Files.copy(JPEG.toPath(), _base.resolve("A/small.jpg"));
		long before = PictureReader.budget();
		PictureReader.setBudget(1024 * 1024);
		try {
			FakeResponse listing = get("/A/", "json", SharingFixture.ALICE);
			assertEquals(listing.body(), HttpServletResponse.SC_OK, listing.status());
			assertTrue(listing.body().contains("big.webp"));

			FakeResponse refused = get("/A/big.webp", "tn", SharingFixture.ALICE);
			assertTrue("refused: " + refused.status(), refused.status() >= 400);
			String reason = errorMessage(refused);
			assertNotNull(reason);
			assertTrue(reason, reason.contains("big.webp"));

			assertEquals(HttpServletResponse.SC_OK, get("/A/small.jpg", "tn", SharingFixture.ALICE).status());
			// The original itself is never refused: it is not decoded.
			assertEquals(HttpServletResponse.SC_OK, get("/A/big.webp", null, SharingFixture.ALICE).status());
		} finally {
			PictureReader.setBudget(before);
		}
		assertEquals(HttpServletResponse.SC_OK, get("/A/big.webp", "tn", SharingFixture.ALICE).status());
	}
}
