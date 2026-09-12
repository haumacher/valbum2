/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.links.LinkStore;
import de.haumacher.imageServer.links.LinkStore.Link;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Test case for the per-folder link sidecar of issue #50, see {@link LinkStore}.
 */
@SuppressWarnings("javadoc")
public class TestLinkStore extends TestCase {

	private Path _folder;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_folder = Files.createTempDirectory("valbum-link-store-test");
	}

	@Override
	protected void tearDown() throws Exception {
		try (Stream<Path> files = Files.walk(_folder)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		super.tearDown();
	}

	public void testAFolderWithoutLinksHasNoFile() throws Exception {
		LinkStore store = new LinkStore(_folder.toFile());

		assertTrue(store.isEmpty());
		assertNull(store.get("Zoo"));
		store.store();
		assertFalse("An empty store writes nothing.", store.getFile().exists());
		assertFalse(LinkStore.exists(_folder.toFile()));
	}

	public void testWritingAndReadingAgain() throws Exception {
		LinkStore store = new LinkStore(_folder.toFile());
		store.put("Zoo", "alice", "2024/2024-05-01 Zoo");
		store.put("Alice", "alice", "");

		LinkStore reloaded = new LinkStore(_folder.toFile());
		assertEquals(Arrays.asList("Zoo", "Alice"), names(reloaded));

		Link zoo = reloaded.get("Zoo");
		assertEquals("alice", zoo.getOwner());
		assertEquals("2024/2024-05-01 Zoo", zoo.getPath());
		assertFalse("The record says when it was made.", zoo.getCreated().isEmpty());
		assertEquals("~alice/2024/2024-05-01 Zoo", zoo.canonical());
		assertEquals("A grant on a whole library is linked as that library.", "~alice",
			reloaded.get("Alice").canonical());
		assertTrue(reloaded.links("alice", ""));
		assertFalse(reloaded.links("carol", ""));
	}

	public void testRemovingAndReplacing() throws Exception {
		LinkStore store = new LinkStore(_folder.toFile());
		store.put("Zoo", "alice", "2024/2024-05-01 Zoo");
		store.put("Zoo", "carol", "Inbox");

		assertEquals("A name is used once.", 1, store.getLinks().size());
		assertEquals("carol", store.get("Zoo").getOwner());

		assertNotNull(store.remove("Zoo"));
		assertNull(store.remove("Zoo"));
		assertTrue(new LinkStore(_folder.toFile()).isEmpty());
	}

	public void testMovingALinkToAnotherFolder() throws Exception {
		Path other = Files.createDirectories(_folder.resolve("Trips"));
		LinkStore source = new LinkStore(_folder.toFile());
		Link link = source.put("Zoo", "alice", "2024/2024-05-01 Zoo");
		LinkStore target = new LinkStore(other.toFile());

		source.move(link, target, "Zoo-2");

		assertTrue("The link left the source folder.", new LinkStore(_folder.toFile()).isEmpty());
		Link moved = new LinkStore(other.toFile()).get("Zoo-2");
		assertEquals("alice", moved.getOwner());
		assertEquals("2024/2024-05-01 Zoo", moved.getPath());
	}

	public void testADamagedFileIsSetAsideAndLinksNothing() throws Exception {
		Files.write(_folder.resolve(LinkStore.FILE_NAME), "this is not json".getBytes(StandardCharsets.UTF_8));

		LinkStore store = new LinkStore(_folder.toFile());
		assertTrue("A broken sidecar links nothing rather than refusing the folder.", store.isEmpty());

		store.put("Zoo", "alice", "2024");

		assertEquals(Arrays.asList("Zoo"), names(new LinkStore(_folder.toFile())));
		assertTrue("The unreadable file was kept for repair.", broken().length > 0);
	}

	public void testAStoreOfALaterVersionIsReadAsFarAsItGoes() throws Exception {
		Files.write(_folder.resolve(LinkStore.FILE_NAME),
			("{\"version\":" + (LinkStore.VERSION + 1) + ",\"links\":[{\"name\":\"Zoo\",\"owner\":\"alice\","
				+ "\"path\":\"2024\",\"created\":\"2026-09-12T00:00:00Z\",\"colour\":\"red\"}]}")
					.getBytes(StandardCharsets.UTF_8));

		LinkStore store = new LinkStore(_folder.toFile());

		assertEquals("What a later build wrote is read as far as this one understands it.",
			Arrays.asList("Zoo"), names(store));
		assertEquals("2024", store.get("Zoo").getPath());
	}

	private File[] broken() {
		return _folder.toFile().listFiles(f -> f.getName().startsWith(LinkStore.FILE_NAME + ".broken-"));
	}

	private static List<String> names(LinkStore store) {
		List<String> result = new ArrayList<>();
		for (Link link : store.getLinks()) {
			result.add(link.getName());
		}
		return result;
	}
}
