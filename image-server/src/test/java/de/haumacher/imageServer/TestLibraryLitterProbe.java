/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.upload.HashIndex;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Probe of issue #173 across builds: a hash index written by a server that did not know the NAS
 * litter yet holds the thumbnails of <code>@eaDir</code>. The upgraded server must not answer
 * them, neither from the loaded file before its first pass nor after it — the thumbnail is still
 * there on disk, so the "is the file still there" check of {@link HashIndex#pathOf(String)} alone
 * does not forget it.
 */
@SuppressWarnings("javadoc")
public class TestLibraryLitterProbe extends TestCase {

	private static final String HASH = "ab".repeat(32);

	private Path _base;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-litter-probe");
		Path litter = _base.resolve("2020/Trip/@eaDir");
		Files.createDirectories(litter);
		Files.write(litter.resolve("SYNOPHOTO_THUMB_M.jpg"), new byte[] { 1, 2, 3 });
		Path store = _base.resolve(UserStore.DIRECTORY_NAME);
		Files.createDirectories(store);
		Files.writeString(store.resolve(HashIndex.FILE_NAME),
			"{\"version\":1,\"folders\":{\"2020/Trip/@eaDir\":{\"stamp\":1,\"hashes\":{\"" + HASH
				+ "\":\"SYNOPHOTO_THUMB_M.jpg\"}}}}",
			StandardCharsets.UTF_8);
	}

	@Override
	protected void tearDown() throws Exception {
		try (Stream<Path> paths = Files.walk(_base)) {
			paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
		}
		super.tearDown();
	}

	public void testAnOlderIndexNamesNoLitterBeforeTheFirstPass() {
		HashIndex index = new HashIndex(_base);
		try {
			assertNull(index.pathOf(HASH));
		} finally {
			index.shutdown();
		}
	}

	public void testAnOlderIndexNamesNoLitterAfterThePass() {
		HashIndex index = new HashIndex(_base);
		try {
			index.indexNow();
			assertNull(index.pathOf(HASH));
		} finally {
			index.shutdown();
		}
	}
}
