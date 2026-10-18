/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ImagePart;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Probe for issue #307, package 3: a copy kept as a reference is shown, downloaded and followed
 * exactly once (under a free name while the copy keeps its own in the trash), as if the photograph lay in the album, while its file waits in the trash.
 */
@SuppressWarnings("javadoc")
public class TestKeepAsReferencesProbe extends ShareTestCase {

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		SharingFixture.album(_base, "Trip", "Trip",
			"[\"ImagePart\",{\"name\":\"x.jpg\",\"width\":4,\"height\":3,\"date\":1000}]", "x.jpg");
		SharingFixture.album(_base, "Other", "Other",
			"[\"ImagePart\",{\"name\":\"b.jpg\",\"width\":4,\"height\":3,\"date\":500}],"
				+ "[\"ImagePart\",{\"name\":\"x.jpg\",\"width\":4,\"height\":3,\"date\":1000}]",
			"b.jpg");
		Files.write(_base.resolve("Other/x.jpg"), Files.readAllBytes(_base.resolve("Trip/x.jpg")));
		restartServer();
		servlet().index().indexNow();
	}

	public void testAKeptCopyIsShownDownloadedAndFollowedOnce() throws Exception {
		byte[] bytes = Files.readAllBytes(_base.resolve("Trip/x.jpg"));
		Map<String, String> keep = new HashMap<>();
		keep.put("action", ImageServlet.KEEP_AS_REFERENCES_ACTION);
		FakeResponse kept = post("/", "{\"original\":\"Trip/x.jpg\",\"copies\":[{\"name\":\"Other/x.jpg\"}]}",
			SharingFixture.ALICE, keep);
		assertEquals(kept.body(), HttpServletResponse.SC_OK, kept.status());

		for (String token : new String[] { SharingFixture.ALICE, SharingFixture.DAVE }) {
			assertEquals(token, Arrays.asList("b.jpg", "x-2.jpg"), names(album(get("/Other/", "json", token))));
		}
		assertEquals("Trip/x.jpg", find(album(get("/Other/", "json", SharingFixture.ALICE)), "x-2.jpg").getRef().getPath());

		// The whole album downloaded: the photograph once, the original's bytes.
		Map<String, String> zip = new HashMap<>();
		zip.put("action", ImageServlet.ZIP_ACTION);
		FakeResponse download = post("/Other/", "{\"names\":[{\"name\":\"b.jpg\"},{\"name\":\"x-2.jpg\"}]}",
			SharingFixture.DAVE, zip);
		assertEquals(download.body(), HttpServletResponse.SC_OK, download.status());
		Map<String, byte[]> entries = new LinkedHashMap<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(download.bodyBytes()))) {
			for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
				entries.put(entry.getName(), in.readAllBytes());
			}
		}
		assertEquals(Arrays.asList("b.jpg", "x-2.jpg"), new ArrayList<>(entries.keySet()));
		assertTrue(Arrays.equals(bytes, entries.get("x-2.jpg")));

		// The original moves on: the reference follows it, never the copy waiting in the trash.
		Map<String, String> move = new HashMap<>();
		move.put("action", "move");
		FakeResponse moved = post("/Trip/",
			"{\"target\":\"" + SharingFixture.PUBLIC + "\",\"names\":[{\"name\":\"x.jpg\"}]}", SharingFixture.ALICE,
			move);
		assertEquals(moved.body(), HttpServletResponse.SC_OK, moved.status());
		ImagePart reference = find(album(get("/Other/", "json", SharingFixture.ALICE)), "x-2.jpg");
		assertEquals(SharingFixture.PUBLIC + "/x.jpg", reference.getRef().getPath());
		assertEquals(HttpServletResponse.SC_OK, get("/Other/x-2.jpg", "tn", SharingFixture.DAVE).status());
		assertEquals(Arrays.asList("b.jpg", "x-2.jpg"), names(album(get("/Other/", "json", SharingFixture.DAVE))));
	}

	private static ImagePart find(AlbumInfo album, String name) {
		ImagePart result = Crops.findImage(album, name);
		assertNotNull(name + " in " + names(album), result);
		return result;
	}

	private static List<String> names(AlbumInfo album) {
		List<String> result = new ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart && ((ImagePart) part).getRating() != de.haumacher.imageServer.auth.Ratings.TRASH) {
				result.add(((ImagePart) part).getName());
			}
		}
		return result;
	}
}
