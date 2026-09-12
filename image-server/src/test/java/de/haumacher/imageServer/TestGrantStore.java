/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.GrantStore;
import de.haumacher.imageServer.auth.GrantStore.Grant;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.auth.Subjects;
import de.haumacher.imageServer.auth.UserStore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Test case for {@link GrantStore}: the round trip, the identity of a grant and what a damaged
 * store does (issue #49).
 */
@SuppressWarnings("javadoc")
public class TestGrantStore extends TestCase {

	private Path _base;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-grant-store-test");
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
		GrantStore store = new GrantStore(_base);
		store.grant("alice", "2024", Subjects.user("bob"), Arrays.asList(Rights.VIEW, Rights.DOWNLOAD));
		store.grant("alice", "", Subjects.ANONYMOUS, Arrays.asList(Rights.VIEW));

		List<Grant> read = new GrantStore(_base).getGrants();

		assertEquals(2, read.size());
		Grant first = read.get(0);
		assertEquals("alice", first.getOwner());
		assertEquals("2024", first.getPath());
		assertEquals(Subjects.user("bob"), first.getSubject());
		assertEquals(Rights.READ_ONLY, first.getRights());
		assertFalse("A grant records when it was made.", first.getCreated().isEmpty());
		assertEquals("", read.get(1).getPath());
	}

	public void testTheStoreLivesBesideTheAlbumTree() throws Exception {
		GrantStore store = new GrantStore(_base);
		store.grant("alice", "", Subjects.ANONYMOUS, Arrays.asList(Rights.VIEW));

		assertEquals(_base.resolve(UserStore.DIRECTORY_NAME).resolve(GrantStore.FILE_NAME), store.getFile());
		assertTrue(store.getFile().toFile().exists());
	}

	public void testAMissingStoreIsEmpty() throws Exception {
		assertTrue(new GrantStore(_base).getGrants().isEmpty());
		assertFalse("Reading must not create the file.",
			_base.resolve(UserStore.DIRECTORY_NAME).resolve(GrantStore.FILE_NAME).toFile().exists());
	}

	public void testADamagedStoreIsSetAsideAndReadAsEmpty() throws Exception {
		Path file = _base.resolve(UserStore.DIRECTORY_NAME).resolve(GrantStore.FILE_NAME);
		Files.createDirectories(file.getParent());
		Files.write(file, "{not json at all".getBytes(StandardCharsets.UTF_8));

		GrantStore store = new GrantStore(_base);
		assertTrue("A damaged store grants nothing.", store.getGrants().isEmpty());

		store.grant("alice", "", Subjects.ANONYMOUS, Arrays.asList(Rights.VIEW));

		File[] kept = _base.resolve(UserStore.DIRECTORY_NAME).toFile()
			.listFiles((dir, name) -> name.startsWith(GrantStore.FILE_NAME + ".broken-"));
		assertEquals("The unreadable file is kept for repair, not overwritten.", 1, kept.length);
		assertEquals(1, new GrantStore(_base).getGrants().size());
	}

	public void testANewerVersionIsReadAsWellAsItCanBe() throws Exception {
		Path file = _base.resolve(UserStore.DIRECTORY_NAME).resolve(GrantStore.FILE_NAME);
		Files.createDirectories(file.getParent());
		Files.write(file, ("{\"version\":" + (GrantStore.VERSION + 1) + ",\"grants\":[{\"owner\":\"alice\","
			+ "\"path\":\"2024\",\"subject\":\"user:bob\",\"rights\":[\"view\",\"teleport\"],"
			+ "\"created\":\"2026-09-12T10:11:12Z\",\"future\":42}]}").getBytes(StandardCharsets.UTF_8));

		List<Grant> read = new GrantStore(_base).getGrants();

		assertEquals("A newer store is warned about, not refused.", 1, read.size());
		assertTrue(read.get(0).getRights().contains(Rights.VIEW));
		assertTrue("What is stored is kept as read; the enforcement drops what it cannot enforce.",
			read.get(0).getRights().contains("teleport"));
		assertFalse(Rights.closure(read.get(0).getRights()).contains("teleport"));
	}

	public void testGrantingAgainReplacesTheRights() throws Exception {
		GrantStore store = new GrantStore(_base);
		store.grant("alice", "2024", Subjects.user("bob"), Arrays.asList(Rights.VIEW));
		store.grant("alice", "2024", Subjects.user("bob"), Arrays.asList(Rights.EDIT));

		List<Grant> read = new GrantStore(_base).getGrants();
		assertEquals("A grant is identified by owner, path and subject.", 1, read.size());
		assertEquals(java.util.Collections.singleton(Rights.EDIT), read.get(0).getRights());
	}

	public void testAnotherSubjectPathOrOwnerIsAnotherGrant() throws Exception {
		GrantStore store = new GrantStore(_base);
		store.grant("alice", "2024", Subjects.user("bob"), Arrays.asList(Rights.VIEW));
		store.grant("alice", "2024", Subjects.group("family"), Arrays.asList(Rights.VIEW));
		store.grant("alice", "2025", Subjects.user("bob"), Arrays.asList(Rights.VIEW));
		store.grant("carol", "2024", Subjects.user("bob"), Arrays.asList(Rights.VIEW));

		assertEquals(4, new GrantStore(_base).getGrants().size());
		assertEquals(3, new GrantStore(_base).ofOwner("alice").size());
	}

	public void testRevokeRemovesExactlyOne() throws Exception {
		GrantStore store = new GrantStore(_base);
		store.grant("alice", "2024", Subjects.user("bob"), Arrays.asList(Rights.VIEW));
		store.grant("alice", "2024", Subjects.group("family"), Arrays.asList(Rights.VIEW));
		store.grant("alice", "2025", Subjects.user("bob"), Arrays.asList(Rights.VIEW));

		assertTrue(store.revoke("alice", "2024", Subjects.user("bob")));

		List<Grant> read = new GrantStore(_base).getGrants();
		assertEquals(2, read.size());
		assertEquals(Subjects.group("family"), read.get(0).getSubject());
		assertEquals("2025", read.get(1).getPath());
	}

	public void testRevokingWhatWasNeverGranted() throws Exception {
		GrantStore store = new GrantStore(_base);
		assertFalse(store.revoke("alice", "2024", Subjects.user("bob")));
		assertFalse("Nothing to write, nothing written.", store.getFile().toFile().exists());
	}

	public void testGrantsAreInheritedDownwards() throws Exception {
		GrantStore store = new GrantStore(_base);
		store.grant("alice", "", Subjects.ANONYMOUS, Arrays.asList(Rights.VIEW));
		store.grant("alice", "2024", Subjects.user("bob"), Arrays.asList(Rights.DOWNLOAD));
		store.grant("alice", "2024/Zoo", Subjects.group("family"), Arrays.asList(Rights.CONTRIBUTE));
		store.grant("alice", "2024-other", Subjects.user("dave"), Arrays.asList(Rights.EDIT));

		List<Grant> covering = store.covering("alice", "2024/Zoo/inner");

		assertEquals("The nearest first: a sibling whose name is a prefix does not cover.", 3, covering.size());
		assertEquals("2024/Zoo", covering.get(0).getPath());
		assertEquals("2024", covering.get(1).getPath());
		assertEquals("", covering.get(2).getPath());
		assertTrue(store.covering("carol", "2024/Zoo").isEmpty());
	}

	public void testATokenSubjectIsStoredAndReadBack() throws Exception {
		GrantStore store = new GrantStore(_base);
		store.grant("alice", "2024", Subjects.TOKEN_PREFIX + "abc", Arrays.asList(Rights.VIEW));

		List<Grant> read = new GrantStore(_base).getGrants();
		assertEquals("A share-link grant of issue #51 is kept faithfully.", 1, read.size());
		assertEquals(Subjects.TOKEN_PREFIX + "abc", read.get(0).getSubject());
		assertFalse("This build grants nothing to a token.", Subjects.isKnown(read.get(0).getSubject()));
	}
}
