/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Resource;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Probes of #186 the delivery did not write: a broken HEIC beside a good photograph, a HEIC moved
 * into another album and the trash purge of a HEIC with a display rendition.
 */
@SuppressWarnings("javadoc")
public class TestHeicProbe extends ShareTestCase {

	private static final File FIXTURES = new File("src/test/fixtures/heic");

	private static final File JPEG =
		new File("src/test/fixtures/test-album/2005-08-24 Blumen und Fliegen/IMG_0417.JPG");

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		Files.createDirectories(_base.resolve("A"));
		Files.createDirectories(_base.resolve("B"));
	}

	public void testABrokenHeicSpoilsNothingElse() throws Exception {
		byte[] grid = Files.readAllBytes(new File(FIXTURES, "grid.heic").toPath());
		LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
		files.put("a.jpg", Files.readAllBytes(JPEG.toPath()));
		files.put("half.heic", Arrays.copyOf(grid, grid.length / 2));
		files.put("noise.HEIF", "this is no picture at all".getBytes());
		FakeResponse upload = upload("/A/", SharingFixture.ALICE, files);
		assertEquals(upload.body(), HttpServletResponse.SC_OK, upload.status());

		AlbumInfo album = album("/A/");
		assertNotNull(image(album, "a.jpg"));
		assertEquals(HttpServletResponse.SC_OK, get("/A/a.jpg", "tn", SharingFixture.ALICE).status());
		for (String name : Arrays.asList("half.heic", "noise.HEIF")) {
			for (String type : Arrays.asList("tn", ImageServlet.DISPLAY_TYPE)) {
				long start = System.currentTimeMillis();
				FakeResponse response = get("/A/" + name, type, SharingFixture.ALICE);
				assertTrue(name + " " + type + " answered " + response.status(), response.status() >= 400);
				assertNotNull("A refusal speaks: " + name + " " + type, errorMessage(response));
				assertTrue("No hang: " + name, System.currentTimeMillis() - start < 30_000);
			}
		}
		// The listing still stands after the failures.
		assertNotNull(image(album("/A/"), "a.jpg"));
	}

	public void testAMovedHeicIsShownInItsNewAlbum() throws Exception {
		Files.copy(new File(FIXTURES, "rotated.heic").toPath(), _base.resolve("A/rotated.heic"));
		assertPicture(get("/A/rotated.heic", "tn", SharingFixture.ALICE), 120, 180);
		assertPicture(get("/A/rotated.heic", ImageServlet.DISPLAY_TYPE, SharingFixture.ALICE), 120, 180);

		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "move");
		FakeResponse moved = post("/A/", "{\"target\":\"B\",\"names\":[{\"name\":\"rotated.heic\"}]}",
			SharingFixture.ALICE, parameters);
		assertEquals(moved.body(), HttpServletResponse.SC_OK, moved.status());

		ImagePart part = image(album("/B/"), "rotated.heic");
		assertEquals(120, part.getWidth());
		assertEquals(180, part.getHeight());
		assertPicture(get("/B/rotated.heic", "tn", SharingFixture.ALICE), 120, 180);
		assertPicture(get("/B/rotated.heic", ImageServlet.DISPLAY_TYPE, SharingFixture.ALICE), 120, 180);
	}

	public void testAPurgedHeicTakesItsDisplayRenditionAlong() throws Exception {
		Files.copy(new File(FIXTURES, "single.heic").toPath(), _base.resolve("A/single.heic"));
		Files.copy(JPEG.toPath(), _base.resolve("A/keep.jpg"));
		assertPicture(get("/A/single.heic", ImageServlet.DISPLAY_TYPE, SharingFixture.ALICE), 96, 64);

		String json = get("/A/", "json", SharingFixture.ALICE).body();
		String trashed = json.replaceFirst("(\"name\":\\s*\"single.heic\"[^}]*\"rating\":\\s*)-?\\d+", "$1-2");
		if (trashed.equals(json)) {
			trashed = json.replaceFirst("\"name\":\\s*\"single.heic\"", "\"name\": \"single.heic\", \"rating\": -2");
		}
		FakeResponse put = put("/A/", trashed, SharingFixture.ALICE);
		assertEquals(put.body(), HttpServletResponse.SC_OK, put.status());

		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "purge");
		FakeResponse purge = post("/A/", "", SharingFixture.ALICE, parameters);
		assertEquals(purge.body(), HttpServletResponse.SC_OK, purge.status());

		assertFalse(Files.exists(_base.resolve("A/single.heic")));
		assertTrue(Files.exists(_base.resolve("A/keep.jpg")));
		Path cache = _base.resolve("A").resolve(PreviewCache.CACHE_DIRECTORY_NAME);
		try (var listing = Files.list(cache)) {
			listing.forEach(file -> assertFalse("Left behind: " + file,
				file.getFileName().toString().contains("single.heic")));
		}
	}

	private AlbumInfo album(String path) throws Exception {
		FakeResponse response = get(path, "json", SharingFixture.ALICE);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		return (AlbumInfo) Resource.readResource(reader(response.body()));
	}

	private static ImagePart image(AlbumInfo album, String name) {
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart && name.equals(((ImagePart) part).getName())) {
				return (ImagePart) part;
			}
		}
		fail("No image '" + name + "' in " + album);
		return null;
	}

	private static void assertPicture(FakeResponse response, int width, int height) throws Exception {
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		BufferedImage picture = ImageIO.read(new ByteArrayInputStream(response.bodyBytes()));
		assertNotNull("A JPEG", picture);
		assertEquals(width, picture.getWidth());
		assertEquals(height, picture.getHeight());
		de.haumacher.imageServer.heif.TestHeifDecoder.assertQuadrants(picture);
	}
}
