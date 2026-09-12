/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.GroupStore;
import de.haumacher.imageServer.auth.GroupStore.Group;
import de.haumacher.imageServer.auth.UserStore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Test case for {@link GroupStore}: the round trip, the ownership of a group and what a damaged
 * store does (issue #49).
 */
@SuppressWarnings("javadoc")
public class TestGroupStore extends TestCase {

	private Path _base;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-group-store-test");
	}

	@Override
	protected void tearDown() throws Exception {
		if (_base != null) {
			try (Stream<Path> files = Files.walk(_base)) {
				files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
		super.tearDown();
	}

	public void testWrittenAndReadAgain() throws Exception {
		GroupStore store = new GroupStore(_base);
		store.put("family", "alice", Arrays.asList("bob", "carol"));
		store.put("friends", "carol", Collections.singletonList("bob"));

		List<Group> read = new GroupStore(_base).getGroups();

		assertEquals(2, read.size());
		Group family = read.get(0);
		assertEquals("family", family.getName());
		assertEquals("alice", family.getOwner());
		assertEquals(new java.util.LinkedHashSet<>(Arrays.asList("bob", "carol")), family.getMembers());
		assertFalse("A group records when it was created.", family.getCreated().isEmpty());
		assertEquals("carol", read.get(1).getOwner());
	}

	public void testTheStoreLivesBesideTheAlbumTree() throws Exception {
		GroupStore store = new GroupStore(_base);
		store.put("family", "alice", Collections.singletonList("bob"));

		assertEquals(_base.resolve(UserStore.DIRECTORY_NAME).resolve(GroupStore.FILE_NAME), store.getFile());
		assertTrue(store.getFile().toFile().exists());
	}

	public void testAMissingStoreIsEmpty() throws Exception {
		assertTrue(new GroupStore(_base).getGroups().isEmpty());
		assertFalse("Reading must not create the file.",
			_base.resolve(UserStore.DIRECTORY_NAME).resolve(GroupStore.FILE_NAME).toFile().exists());
	}

	public void testADamagedStoreIsSetAsideAndReadAsEmpty() throws Exception {
		Path file = _base.resolve(UserStore.DIRECTORY_NAME).resolve(GroupStore.FILE_NAME);
		Files.createDirectories(file.getParent());
		Files.write(file, "]broken[".getBytes(StandardCharsets.UTF_8));

		GroupStore store = new GroupStore(_base);
		assertTrue("A damaged store knows no group.", store.getGroups().isEmpty());

		store.put("family", "alice", Collections.singletonList("bob"));

		File[] kept = _base.resolve(UserStore.DIRECTORY_NAME).toFile()
			.listFiles((dir, name) -> name.startsWith(GroupStore.FILE_NAME + ".broken-"));
		assertEquals("The unreadable file is kept for repair, not overwritten.", 1, kept.length);
		assertEquals(1, new GroupStore(_base).getGroups().size());
	}

	public void testANewerVersionIsReadAsWellAsItCanBe() throws Exception {
		Path file = _base.resolve(UserStore.DIRECTORY_NAME).resolve(GroupStore.FILE_NAME);
		Files.createDirectories(file.getParent());
		Files.write(file, ("{\"version\":" + (GroupStore.VERSION + 1) + ",\"groups\":[{\"name\":\"family\","
			+ "\"owner\":\"alice\",\"members\":[\"bob\"],\"created\":\"2026-09-12T10:11:12Z\","
			+ "\"colour\":\"green\"}]}").getBytes(StandardCharsets.UTF_8));

		List<Group> read = new GroupStore(_base).getGroups();

		assertEquals("A newer store is warned about, not refused.", 1, read.size());
		assertTrue(read.get(0).holds("bob"));
	}

	public void testPuttingAgainReplacesTheMembers() throws Exception {
		GroupStore store = new GroupStore(_base);
		store.put("family", "alice", Arrays.asList("bob", "carol"));
		store.put("family", "alice", Collections.singletonList("carol"));

		List<Group> read = new GroupStore(_base).getGroups();
		assertEquals(1, read.size());
		assertFalse(read.get(0).holds("bob"));
		assertTrue(read.get(0).holds("carol"));
		assertEquals("The owner of an existing group stays who it was.", "alice", read.get(0).getOwner());
	}

	public void testRemoveTakesExactlyOne() throws Exception {
		GroupStore store = new GroupStore(_base);
		store.put("family", "alice", Collections.singletonList("bob"));
		store.put("friends", "carol", Collections.singletonList("bob"));

		assertTrue(store.remove("family"));
		assertFalse("Removing what is not there changes nothing.", store.remove("family"));

		List<Group> read = new GroupStore(_base).getGroups();
		assertEquals(1, read.size());
		assertEquals("friends", read.get(0).getName());
	}

	public void testWhoIsInWhichGroup() throws Exception {
		GroupStore store = new GroupStore(_base);
		store.put("family", "alice", Arrays.asList("bob", "carol"));
		store.put("friends", "carol", Collections.singletonList("bob"));

		assertEquals(new java.util.LinkedHashSet<>(Arrays.asList("family", "friends")), store.groupsOf("bob"));
		assertEquals(Collections.singleton("family"), store.groupsOf("carol"));
		assertTrue(store.groupsOf("dave").isEmpty());
	}

	public void testWhatAUserSeesOfTheGroups() throws Exception {
		GroupStore store = new GroupStore(_base);
		store.put("family", "alice", Arrays.asList("bob", "carol"));
		store.put("friends", "carol", Collections.singletonList("bob"));

		List<Group> carol = store.visibleTo("carol");
		assertEquals("The groups one owns come first, then the ones one is in.", 2, carol.size());
		assertEquals("friends", carol.get(0).getName());
		assertEquals("family", carol.get(1).getName());

		assertTrue("Somebody in no group and owning none sees nothing.", store.visibleTo("dave").isEmpty());
	}
}
