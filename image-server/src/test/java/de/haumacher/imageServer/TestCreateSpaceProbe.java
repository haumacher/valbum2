/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.InviteMode;
import de.haumacher.imageServer.auth.SpaceStore;
import de.haumacher.imageServer.auth.Spaces;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Probe of issue #175: a space created beside a space that is in use leaves every byte of the
 * living space alone — its users and devices, its hash index, its albums and their sidecars — and
 * the server then detects both.
 */
@SuppressWarnings("javadoc")
public class TestCreateSpaceProbe extends TestCase {

	private Path _base;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-create-space-probe");
	}

	@Override
	protected void tearDown() throws Exception {
		try (Stream<Path> files = Files.walk(_base)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		super.tearDown();
	}

	public void testASpaceInUseIsLeftAlone() throws Exception {
		SpaceCreation.create(_base, "family", "", SpaceStore.ANONYMOUS_NONE, SpaceStore.FACES_ON, null);
		Path family = _base.resolve("family");
		Path album = family.resolve("2020/Trip");
		Files.createDirectories(album);
		Files.write(album.resolve("a.jpg"), new byte[] { (byte) 0xFF, (byte) 0xD8, 1, 2 });
		Files.writeString(album.resolve("index.json"), "[\"AlbumInfo\",{\"title\":\"Trip\"}]",
			StandardCharsets.UTF_8);
		Files.writeString(family.resolve(".valbum/users.json"), "{\"users\":[{\"name\":\"anna\"}]}",
			StandardCharsets.UTF_8);
		Files.writeString(family.resolve(".valbum/hash-index.json"), "{\"version\":1,\"folders\":{}}",
			StandardCharsets.UTF_8);
		String before = fingerprint(family);

		SpaceCreation.create(_base, "friends", "Friends", SpaceStore.ANONYMOUS_PUBLIC, SpaceStore.FACES_OFF,
			null);

		assertEquals(before, fingerprint(family));
		assertEquals(List.of("family", "friends"),
			Spaces.detect(_base, null, AuthMode.WRITES, InviteMode.MEMBERS).segments());
		assertTrue(SpaceStore.load(family, "family").isFacesEnabled());
		assertEquals("Friends", SpaceStore.load(_base.resolve("friends"), "friends").getName());
	}

	public void testTheSameSpaceTwiceIsRefusedAndChangesNothing() throws Exception {
		SpaceCreation.create(_base, "family", "Family", SpaceStore.ANONYMOUS_NONE, SpaceStore.FACES_ON, null);
		String before = fingerprint(_base);
		try {
			SpaceCreation.create(_base, "family", "Other", SpaceStore.ANONYMOUS_PUBLIC, SpaceStore.FACES_OFF,
				null);
			fail("A second create of the same space was not refused.");
		} catch (SpaceCreation.Refused ex) {
			assertEquals(before, fingerprint(_base));
		}
	}

	private static String fingerprint(Path root) throws Exception {
		StringBuilder result = new StringBuilder();
		try (Stream<Path> walk = Files.walk(root)) {
			for (Path path : (Iterable<Path>) walk.sorted()::iterator) {
				result.append(root.relativize(path));
				if (Files.isRegularFile(path)) {
					for (byte b : MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))) {
						result.append(String.format("%02x", Byte.valueOf(b)));
					}
				}
				result.append('\n');
			}
		}
		return result.toString();
	}
}
