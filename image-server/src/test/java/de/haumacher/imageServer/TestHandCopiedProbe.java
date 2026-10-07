/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.upload.HashCache;
import de.haumacher.imageServer.upload.HashIndex;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Probe of issue #235 with a library moved in the way rsync does it: a whole tree of years and
 * albums appears below the watched root, folders first and files after, with nobody opening any of
 * the new folders.
 */
@SuppressWarnings("javadoc")
public class TestHandCopiedProbe extends PersonalLinkTestCase {

	public void testADeepTreeCopiedInIsHashedToItsLeavesWithoutARestart() throws Exception {
		HashIndex index = servlet().index();
		index.pipeline().setQuietMillis(400);
		servlet().startIndexing();
		assertTrue(index.awaitPass(30_000));
		FakeResponse root = get("/", "json", SharingFixture.ALICE);
		assertEquals(HttpServletResponse.SC_OK, root.status());

		Path library = _base.resolve("Library");
		String[] albums = { "1955/1955-08 Ostsee", "2019/2019-07 Trip/Day 1", "2026/2026-01 Schnee" };
		for (String album : albums) {
			Files.createDirectories(library.resolve(album));
		}
		Map<String, String> pathByHash = new LinkedHashMap<>();
		int n = 0;
		for (String album : albums) {
			for (int k = 0; k < 3; k++) {
				byte[] photo = photo("tree-" + n++);
				String name = "IMG_" + k + ".jpg";
				Files.write(library.resolve(album).resolve(name), photo);
				pathByHash.put(HashCache.sha256(photo), "Library/" + album + "/" + name);
			}
		}

		long end = System.currentTimeMillis() + 30_000;
		while (System.currentTimeMillis() < end && !allKnown(index, pathByHash)) {
			Thread.sleep(100);
		}
		for (Map.Entry<String, String> entry : pathByHash.entrySet()) {
			assertEquals("Known without anybody opening it: " + entry.getValue(), entry.getValue(),
				index.pathOf(entry.getKey()));
		}
		assertTrue(index.pipeline().awaitIdle(30_000));
		for (String album : albums) {
			assertTrue(album, Files.isRegularFile(library.resolve(album).resolve(HashCache.FILE_NAME)));
		}
	}

	private static boolean allKnown(HashIndex index, Map<String, String> pathByHash) {
		for (Map.Entry<String, String> entry : pathByHash.entrySet()) {
			if (!entry.getValue().equals(index.pathOf(entry.getKey()))) {
				return false;
			}
		}
		return true;
	}
}
