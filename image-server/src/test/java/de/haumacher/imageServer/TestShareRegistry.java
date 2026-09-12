/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.links.ShareRegistry;
import de.haumacher.imageServer.links.ShareRegistry.Share;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Test case for the per-space registry of issue #50, see {@link ShareRegistry}.
 */
@SuppressWarnings("javadoc")
public class TestShareRegistry extends TestCase {

	private Path _space;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_space = Files.createTempDirectory("valbum-share-registry-test");
	}

	@Override
	protected void tearDown() throws Exception {
		try (Stream<Path> files = Files.walk(_space)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		super.tearDown();
	}

	public void testAnUntouchedSpaceKnowsNothingAndWritesNothing() throws Exception {
		ShareRegistry registry = new ShareRegistry(_space);

		assertFalse(registry.knows("alice", "2024"));
		assertNull(registry.get("alice", "2024"));
		assertFalse("Reading a registry creates no folder.", _space.resolve(UserStore.DIRECTORY_NAME).toFile()
			.exists());
	}

	public void testWritingAndReadingAgain() throws Exception {
		ShareRegistry registry = new ShareRegistry(_space);
		registry.link("alice", "2024/2024-05-01 Zoo");
		registry.link("carol", "");

		ShareRegistry reloaded = new ShareRegistry(_space);
		assertEquals(2, reloaded.getShares().size());
		Share zoo = reloaded.get("alice", "2024/2024-05-01 Zoo");
		assertEquals(ShareRegistry.LINKED, zoo.getState());
		assertFalse(zoo.getCreated().isEmpty());
		assertTrue(reloaded.knows("carol", ""));
		assertFalse(reloaded.knows("carol", "Inbox"));
	}

	public void testUnlinkingMarksTheShareDeclined() throws Exception {
		ShareRegistry registry = new ShareRegistry(_space);
		registry.link("alice", "2024");

		registry.decline("alice", "2024");

		ShareRegistry reloaded = new ShareRegistry(_space);
		assertEquals(ShareRegistry.DECLINED, reloaded.get("alice", "2024").getState());
		assertEquals("Declining replaces, it does not add.", 1, reloaded.getShares().size());
		assertTrue("A declined share is known, which is what stops it from coming back.",
			reloaded.knows("alice", "2024"));
	}

	public void testAcceptingAgainIsTheSeamOfIssue55() throws Exception {
		ShareRegistry registry = new ShareRegistry(_space);
		registry.decline("alice", "2024");

		assertTrue(registry.clear("alice", "2024"));
		assertFalse(registry.clear("alice", "2024"));
		assertFalse("Forgotten: the next root listing links it again.",
			new ShareRegistry(_space).knows("alice", "2024"));
	}

	public void testADamagedFileIsSetAsideAndKnowsNothing() throws Exception {
		Path file = _space.resolve(UserStore.DIRECTORY_NAME).resolve(ShareRegistry.FILE_NAME);
		Files.createDirectories(file.getParent());
		Files.write(file, "{ this is not json".getBytes(StandardCharsets.UTF_8));

		ShareRegistry registry = new ShareRegistry(_space);
		assertFalse(registry.knows("alice", "2024"));

		registry.link("alice", "2024");

		assertTrue(new ShareRegistry(_space).knows("alice", "2024"));
		assertTrue("The unreadable file was kept for repair.", broken().length > 0);
	}

	public void testARegistryOfALaterVersionIsReadAsFarAsItGoes() throws Exception {
		Path file = _space.resolve(UserStore.DIRECTORY_NAME).resolve(ShareRegistry.FILE_NAME);
		Files.createDirectories(file.getParent());
		Files.write(file, ("{\"version\":" + (ShareRegistry.VERSION + 1) + ",\"shares\":[{\"owner\":\"alice\","
			+ "\"path\":\"2024\",\"state\":\"linked\",\"created\":\"2026-09-12T00:00:00Z\",\"note\":\"later\"}]}")
				.getBytes(StandardCharsets.UTF_8));

		ShareRegistry registry = new ShareRegistry(_space);

		assertTrue("What a later build wrote is read as far as this one understands it.",
			registry.knows("alice", "2024"));
		assertEquals(ShareRegistry.LINKED, registry.get("alice", "2024").getState());
	}

	private File[] broken() {
		return _space.resolve(UserStore.DIRECTORY_NAME).toFile()
			.listFiles(f -> f.getName().startsWith(ShareRegistry.FILE_NAME + ".broken-"));
	}
}
