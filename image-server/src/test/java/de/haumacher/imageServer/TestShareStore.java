/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.auth.Ratings;
import de.haumacher.imageServer.auth.ShareStore;
import de.haumacher.imageServer.auth.ShareStore.Issued;
import de.haumacher.imageServer.auth.ShareStore.Link;
import de.haumacher.imageServer.auth.UserStore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Test case for {@link ShareStore}, the share links of issue #51.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestShareStore extends TestCase {

	private Path _base;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-share-store-test");
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

	public void testWrittenLinkIsReadAgain() throws Exception {
		ShareStore store = new ShareStore(_base);
		Issued issued = store.create("alice", "2024/Zoo", "Grandma", "2030-01-01T00:00:00Z", Privacy.PUBLIC, 0);

		ShareStore reloaded = new ShareStore(_base);
		List<Link> links = reloaded.getLinks();
		assertEquals(1, links.size());
		Link link = links.get(0);
		assertEquals(issued.getLink().getId(), link.getId());
		assertEquals("alice", link.getOwner());
		assertEquals("2024/Zoo", link.getPath());
		assertEquals("Grandma", link.getLabel());
		assertEquals("2030-01-01T00:00:00Z", link.getExpires());
		assertEquals(Privacy.PUBLIC, link.getMaxPrivacy());
		assertEquals(0, link.getMinRating());
		assertEquals("", link.getRevoked());
		assertEquals("token:" + link.getId(), link.getSubject());
	}

	public void testOnlyTheHashIsStored() throws Exception {
		ShareStore store = new ShareStore(_base);
		Issued issued = store.create("alice", "", "", "", Privacy.MEMBERS, Ratings.MIN);

		String contents = new String(Files.readAllBytes(store.getFile()), StandardCharsets.UTF_8);
		assertFalse("The store must never hold the token: " + contents, contents.contains(issued.getToken()));
		assertTrue(contents, contents.contains(UserStore.hash(issued.getToken())));
	}

	public void testLookupFindsALiveLinkAndNotARevokedOne() throws Exception {
		ShareStore store = new ShareStore(_base);
		Issued live = store.create("alice", "A", "live", "", Privacy.PUBLIC, Ratings.MIN);
		Issued dead = store.create("alice", "B", "dead", "", Privacy.PUBLIC, Ratings.MIN);
		store.revoke(dead.getLink().getId());

		assertEquals(live.getLink().getId(), store.lookup(live.getToken()).getId());
		assertTrue(store.lookup(live.getToken()).isLive());
		// A withdrawn link is still found: the caller is told that it was withdrawn, which is a
		// different answer from a token nobody ever issued.
		assertNotNull(store.lookup(dead.getToken()));
		assertTrue(store.lookup(dead.getToken()).isRevoked());
		assertFalse(store.lookup(dead.getToken()).isLive());
		assertNull(store.lookup("no-such-token"));
	}

	public void testRevokeMarksOneAndSurvivesAReload() throws Exception {
		ShareStore store = new ShareStore(_base);
		Issued first = store.create("alice", "A", "", "", Privacy.PUBLIC, Ratings.MIN);
		Issued second = store.create("alice", "B", "", "", Privacy.PUBLIC, Ratings.MIN);

		Link revoked = store.revoke(second.getLink().getId());
		assertNotNull(revoked);
		assertFalse(revoked.getRevoked().isEmpty());

		ShareStore reloaded = new ShareStore(_base);
		assertFalse(reloaded.get(first.getLink().getId()).isRevoked());
		assertTrue(reloaded.get(second.getLink().getId()).isRevoked());
		assertNull("Withdrawing a link that does not exist is no error.", reloaded.revoke("nothing"));
	}

	public void testExpiry() throws Exception {
		ShareStore store = new ShareStore(_base);
		Instant now = Instant.parse("2026-09-12T12:00:00Z");
		assertFalse(store.create("alice", "", "", "", Privacy.PUBLIC, Ratings.MIN).getLink().isExpired(now));
		assertTrue(store.create("alice", "a", "", "2026-09-12T11:59:59Z", Privacy.PUBLIC, Ratings.MIN).getLink()
			.isExpired(now));
		assertFalse(store.create("alice", "b", "", "2026-09-12T12:00:01Z", Privacy.PUBLIC, Ratings.MIN).getLink()
			.isExpired(now));
		// A lifetime nobody can read is not a lifetime without end.
		assertTrue(store.create("alice", "c", "", "whenever", Privacy.PUBLIC, Ratings.MIN).getLink().isExpired(now));
	}

	public void testCoveringAsksTheFolderAndItsAncestors() throws Exception {
		ShareStore store = new ShareStore(_base);
		store.create("alice", "", "space", "", Privacy.PUBLIC, Ratings.MIN);
		store.create("alice", "2024", "year", "", Privacy.PUBLIC, Ratings.MIN);
		store.create("alice", "2024/Zoo", "album", "", Privacy.PUBLIC, Ratings.MIN);
		store.create("bob", "2024/Zoo", "somebody else", "", Privacy.PUBLIC, Ratings.MIN);

		List<Link> covering = store.covering("alice", "2024/Zoo");
		assertEquals(3, covering.size());
		assertEquals("album", covering.get(0).getLabel());
		assertEquals("year", covering.get(1).getLabel());
		assertEquals("space", covering.get(2).getLabel());

		assertEquals(2, store.covering("alice", "2024").size());
		assertEquals(0, store.covering("carol", "").size());
	}

	public void testDamagedStoreIsSetAsideInsteadOfOverwritten() throws Exception {
		Path file = _base.resolve(UserStore.DIRECTORY_NAME).resolve(ShareStore.FILE_NAME);
		Files.createDirectories(file.getParent());
		Files.write(file, "{ this is not JSON".getBytes(StandardCharsets.UTF_8));

		ShareStore store = new ShareStore(_base);
		assertTrue("A damaged store opens no link.", store.getLinks().isEmpty());

		store.create("alice", "", "", "", Privacy.PUBLIC, Ratings.MIN);

		String[] broken = file.getParent().toFile()
			.list((dir, name) -> name.startsWith(ShareStore.FILE_NAME + ".broken-"));
		assertNotNull(broken);
		assertEquals("The unreadable file must be kept for repair.", 1, broken.length);
		assertEquals(1, new ShareStore(_base).getLinks().size());
	}

	public void testNewerVersionIsReadAndWarnedAbout() throws Exception {
		Path file = _base.resolve(UserStore.DIRECTORY_NAME).resolve(ShareStore.FILE_NAME);
		Files.createDirectories(file.getParent());
		Files.write(file, ("{\"version\":" + (ShareStore.VERSION + 1) + ",\"links\":[{\"id\":\"x1\","
			+ "\"tokenHash\":\"" + UserStore.hash("future") + "\",\"owner\":\"alice\",\"path\":\"A\","
			+ "\"label\":\"from the future\",\"expires\":\"\",\"maxPrivacy\":1,\"minRating\":1,"
			+ "\"created\":\"2026-09-12T00:00:00Z\",\"revoked\":\"\",\"whatIsThis\":42}]}")
				.getBytes(StandardCharsets.UTF_8));

		ShareStore store = new ShareStore(_base);
		assertEquals(1, store.getLinks().size());
		assertEquals("from the future", store.lookup("future").getLabel());
		assertEquals(1, store.lookup("future").getMaxPrivacy());
		assertEquals(1, store.lookup("future").getMinRating());
	}
}
