/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.index.SpaceIndex;
import de.haumacher.imageServer.upload.HashCache;
import de.haumacher.imageServer.upload.HashIndex;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Test case for the index of a space in H2 and the hash index kept in it, see issue #264.
 *
 * <p>
 * The index is derived: built from the <code>.hashes.json</code> sidecars, reopened without a
 * rebuild, rebuilt when its stamp is foreign or its files are garbage, written through by the
 * server's own edits, one per space, and closed with its servlet.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestSpaceIndex extends ShareTestCase {

	private static final String INBOX = "/" + SharingFixture.CAROLS_ALBUM + "/";

	private static final String PUBLIC = "/" + SharingFixture.PUBLIC + "/";

	private static final String OPEN = SharingFixture.PUBLIC + "/open.jpg";

	private final List<HashIndex> _indexes = new ArrayList<>();

	private final List<Path> _others = new ArrayList<>();

	@Override
	protected void tearDown() throws Exception {
		for (HashIndex index : _indexes) {
			index.shutdown();
		}
		_indexes.clear();
		for (Path other : _others) {
			try (Stream<Path> files = Files.walk(other)) {
				files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
			}
		}
		super.tearDown();
	}

	// --- Built, reopened, rebuilt. ---

	public void testAFreshSpaceBuildsTheIndexFromTheSidecars() throws Exception {
		HashIndex index = open(_base);
		assertTrue("A space without an index starts with an empty one.", index.store().isFresh());
		assertTrue(index.store().isPersistent());
		assertEquals(0, index.size());

		index.indexNow();

		assertTrue(Files.isRegularFile(databaseFile(_base)));
		assertEquals(SharingFixture.PUBLIC + "/open.jpg", index.pathOf(hashOf(OPEN)));
		assertEquals("The folders holding images: the zoo, Public, Private and the inbox.", 4, index.folderCount());
		assertTrue(index.size() >= 4);
	}

	public void testARestartReopensTheIndexWithoutARebuild() throws Exception {
		HashIndex first = open(_base);
		first.indexNow();
		int contents = first.size();
		int folders = first.folderCount();
		close(first);

		HashIndex restarted = open(_base);
		assertFalse("An index of this schema is reopened, not rebuilt.", restarted.store().isFresh());
		assertEquals("Everything is there before any pass.", contents, restarted.size());
		assertEquals(folders, restarted.folderCount());
		assertEquals(SharingFixture.PUBLIC + "/open.jpg", restarted.pathOf(hashOf(OPEN)));

		restarted.indexNow();
		assertEquals("A pass over an index that is up to date opens no sidecar.", 0, restarted.sidecarReads());
		assertEquals("And hashes nothing.", 0, restarted.hashRuns(SharingFixture.PUBLIC));
		assertEquals(contents, restarted.size());
	}

	public void testAForeignSchemaStampIsRebuilt() throws Exception {
		HashIndex first = open(_base);
		first.indexNow();
		first.store().write(connection -> {
			try (PreparedStatement update = connection.prepareStatement("UPDATE meta SET val = ? WHERE name = ?")) {
				update.setString(1, "0");
				update.setString(2, SpaceIndex.SCHEMA_KEY);
				update.executeUpdate();
			}
		});
		close(first);

		HashIndex rebuilt = open(_base);
		assertTrue("An index of another schema is built again.", rebuilt.store().isFresh());
		assertEquals("Nothing of the old one is kept.", 0, rebuilt.size());

		rebuilt.indexNow();
		assertEquals(SharingFixture.PUBLIC + "/open.jpg", rebuilt.pathOf(hashOf(OPEN)));
		assertTrue("Rebuilt from the sidecars.", rebuilt.sidecarReads() >= 4);
		assertEquals("Rebuilding hashes nothing: the sidecars know every photo.", 0,
			rebuilt.hashRuns(SharingFixture.PUBLIC));

		close(rebuilt);
		assertFalse("And then stamped: the next start reopens it.", open(_base).store().isFresh());
	}

	public void testAnIndexWithoutStampIsRebuilt() throws Exception {
		HashIndex first = open(_base);
		first.indexNow();
		first.store().write(connection -> {
			try (PreparedStatement drop = connection.prepareStatement("DROP TABLE meta")) {
				drop.executeUpdate();
			}
		});
		close(first);

		HashIndex rebuilt = open(_base);
		assertTrue(rebuilt.store().isFresh());
		rebuilt.indexNow();
		assertEquals(SharingFixture.PUBLIC + "/open.jpg", rebuilt.pathOf(hashOf(OPEN)));
	}

	public void testACorruptIndexIsDeletedAndRebuilt() throws Exception {
		HashIndex first = open(_base);
		first.indexNow();
		close(first);
		byte[] garbage = new byte[64 * 1024];
		new Random(264).nextBytes(garbage);
		Files.write(databaseFile(_base), garbage);

		// Through the servlet: a broken index never fails the space.
		ImageServlet servlet = servlet();
		HashIndex index = servlet.index();
		assertTrue("Garbage is deleted and built again.", index.store().isFresh());
		assertTrue("On disk again, not in memory.", index.store().isPersistent());

		index.indexNow();
		assertEquals(SharingFixture.PUBLIC + "/open.jpg", index.pathOf(hashOf(OPEN)));
		FakeResponse listing = get(PUBLIC, "json", SharingFixture.ALICE);
		assertEquals(listing.body(), HttpServletResponse.SC_OK, listing.status());
	}

	public void testAnOldIndexFileIsRemovedAfterTheFirstBuild() throws Exception {
		Path legacy = _base.resolve(UserStore.DIRECTORY_NAME).resolve(HashIndex.LEGACY_FILE_NAME);
		Files.writeString(legacy, "{\"version\":1,\"folders\":{}}", StandardCharsets.UTF_8);
		HashIndex index = open(_base);
		assertTrue("Nothing is deleted before the index is built.", Files.exists(legacy));

		index.indexNow();

		assertFalse("The file of an earlier build is litter.", Files.exists(legacy));
	}

	public void testAnIndexThatCannotBeWrittenLivesInMemory() throws Exception {
		Path blocked = SpaceIndex.directoryOf(_base);
		Files.createDirectories(blocked.getParent());
		// A file where the folder belongs: nothing can be created there, whoever runs the test.
		Files.writeString(blocked, "not a folder", StandardCharsets.UTF_8);

		HashIndex index = open(_base);
		assertFalse(index.store().isPersistent());
		index.indexNow();
		assertEquals(SharingFixture.PUBLIC + "/open.jpg", index.pathOf(hashOf(OPEN)));
	}

	public void testAnInterruptDoesNotBreakTheIndex() throws Exception {
		HashIndex index = open(_base);
		index.indexNow();
		String hash = hashOf(OPEN);

		Thread.currentThread().interrupt();
		try {
			index.folderChanged(_base.resolve(SharingFixture.PUBLIC).toFile());
			// Forces the store to write its file in this very thread.
			assertTrue(index.store().write(connection -> {
				try (java.sql.Statement statement = connection.createStatement()) {
					statement.execute("CHECKPOINT SYNC");
				}
			}));
			assertEquals(SharingFixture.PUBLIC + "/open.jpg", index.pathOf(hash));
		} finally {
			Thread.interrupted();
		}

		assertEquals("An interrupted thread closes no file of the index.", SharingFixture.PUBLIC + "/open.jpg",
			index.pathOf(hash));
		index.folderChanged(_base.resolve(SharingFixture.PUBLIC).toFile());
		assertEquals(SharingFixture.PUBLIC + "/open.jpg", index.pathOf(hash));
		close(index);
		HashIndex reopened = open(_base);
		assertFalse("The file is whole.", reopened.store().isFresh());
		assertEquals(SharingFixture.PUBLIC + "/open.jpg", reopened.pathOf(hash));
	}

	public void testTheBudgetIsTheCacheOfH2() throws Exception {
		ImageServlet servlet = servlet();
		assertEquals(SpaceIndex.defaultBudget(), servlet.index().store().getBudget());
		// H2's share: Lucene's buffer of new documents takes a quarter, see SpaceIndex.
		assertEquals(Long.valueOf(SpaceIndex.cacheBudget(SpaceIndex.defaultBudget()) / 1024), cacheSize(servlet.index()));

		servlet.setIndexMemory(8L * 1024 * 1024);

		assertEquals(8L * 1024 * 1024, servlet.index().store().getBudget());
		assertEquals("H2 counts its cache in kilobytes.", Long.valueOf(6 * 1024), cacheSize(servlet.index()));
	}

	// --- Written through. ---

	public void testAnUploadIsInTheIndexAtOnce() throws Exception {
		// Nothing indexed and no background running: only the write-through can know the upload.
		byte[] photo = photo("first");
		String hash = HashCache.sha256(photo);
		assertEquals(HttpServletResponse.SC_OK, upload(INBOX, SharingFixture.ALICE, "first.jpg", photo).status());

		assertEquals(SharingFixture.CAROLS_ALBUM + "/first.jpg", servlet().index().pathOf(hash));
		assertEquals("A duplicate offered to another album right after is known at once.",
			Collections.singletonList(SharingFixture.CAROLS_ALBUM + "/first.jpg"), present(PUBLIC, hash));
	}

	public void testAMoveIsInTheIndexAtOnce() throws Exception {
		servlet().index().indexNow();
		String hash = hashOf(SharingFixture.CAROLS_ALBUM + "/carols.jpg");

		assertEquals(HttpServletResponse.SC_OK,
			move(INBOX, SharingFixture.PUBLIC, SharingFixture.ALICE, "carols.jpg").status());

		HashIndex index = servlet().index();
		assertEquals(SharingFixture.PUBLIC + "/carols.jpg", index.pathOf(hash));
		assertEquals("The index holds the new place, and the old one no more.",
			Collections.singletonList(SharingFixture.PUBLIC + "/carols.jpg"), index.recordedPaths(hash));
	}

	public void testADeleteIsInTheIndexAtOnce() throws Exception {
		servlet().index().indexNow();
		String hash = hashOf(OPEN);
		assertEquals(Collections.singletonList(OPEN), servlet().index().recordedPaths(hash));

		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "delete");
		FakeResponse deleted =
			post(PUBLIC, "{\"target\":\"\",\"names\":[{\"name\":\"open.jpg\"}]}", SharingFixture.ALICE, parameters);
		assertEquals(body(deleted), HttpServletResponse.SC_OK, deleted.status());
		assertFalse(Files.exists(_base.resolve(OPEN)));

		assertNull(servlet().index().pathOf(hash));
		assertEquals("The row is gone, not only hidden.", Collections.emptyList(),
			servlet().index().recordedPaths(hash));
	}

	/**
	 * Probe: what the server wrote through survives a restart before any pass, and a second upload
	 * of the same photograph after the restart is known as present at once.
	 */
	public void testProbeAWriteThroughSurvivesARestart() throws Exception {
		byte[] photo = photo("restart");
		String hash = HashCache.sha256(photo);
		assertEquals(HttpServletResponse.SC_OK, upload(INBOX, SharingFixture.ALICE, "kept.jpg", photo).status());

		restartServer();

		assertEquals(SharingFixture.CAROLS_ALBUM + "/kept.jpg", servlet().index().pathOf(hash));
		assertEquals(Collections.singletonList(SharingFixture.CAROLS_ALBUM + "/kept.jpg"), present(PUBLIC, hash));
	}

	// --- One per space, closed with its servlet. ---

	public void testTwoSpacesHaveTwoIndexes() throws Exception {
		Path other = Files.createTempDirectory("valbum-space-index-other");
		_others.add(other);
		Path album = Files.createDirectories(other.resolve("Album"));
		byte[] photo = photo("other");
		Files.write(album.resolve("other.jpg"), photo);

		HashIndex mine = open(_base);
		HashIndex theirs = open(other);
		mine.indexNow();
		theirs.indexNow();

		assertFalse(mine.store().getDirectory().equals(theirs.store().getDirectory()));
		assertTrue(Files.isRegularFile(databaseFile(other)));
		assertEquals("Album/other.jpg", theirs.pathOf(HashCache.sha256(photo)));
		assertNull("A space never answers from another's index.", mine.pathOf(HashCache.sha256(photo)));
		assertNull(theirs.pathOf(hashOf(OPEN)));
	}

	public void testDestroyingTheServletClosesItsIndex() throws Exception {
		ImageServlet servlet = new ImageServlet(_base.toFile());
		servlet.index().indexNow();
		Path file = databaseFile(_base);
		assertTrue(lockedHere(file));

		servlet.destroy();

		assertFalse("No lock of the index is left in this process.", lockedHere(file));
		HashIndex reopened = open(_base);
		assertFalse("Reopened in the same process, as it was left.", reopened.store().isFresh());
		assertEquals(SharingFixture.PUBLIC + "/open.jpg", reopened.pathOf(hashOf(OPEN)));
	}

	// --- Readers beside the writer. ---

	public void testLookupsDuringABackgroundPassAreConsistent() throws Exception {
		HashIndex index = open(_base);
		index.indexNow();
		String open = hashOf(OPEN);
		String carols = hashOf(SharingFixture.CAROLS_ALBUM + "/carols.jpg");
		int shared = index.shared().size();

		AtomicBoolean done = new AtomicBoolean();
		CountDownLatch started = new CountDownLatch(4);
		ExecutorService readers = Executors.newFixedThreadPool(4);
		List<Future<Integer>> results = new ArrayList<>();
		for (int n = 0; n < 4; n++) {
			results.add(readers.submit(() -> {
				started.countDown();
				int lookups = 0;
				while (!done.get()) {
					assertEquals(OPEN, index.pathOf(open));
					assertEquals(SharingFixture.CAROLS_ALBUM + "/carols.jpg", index.pathOf(carols));
					assertNull(index.elsewhere(open, SharingFixture.PUBLIC));
					assertEquals(shared, index.shared().size());
					lookups++;
				}
				return Integer.valueOf(lookups);
			}));
		}
		try {
			assertTrue(started.await(10, TimeUnit.SECONDS));
			for (int n = 0; n < 5; n++) {
				// What the background does: a pass, and every folder (the whole tree) read again.
				index.treeChanged(_base.toFile());
				index.folderChanged(_base.resolve(SharingFixture.PUBLIC).toFile());
				index.indexNow();
			}
		} finally {
			done.set(true);
			readers.shutdown();
		}
		assertTrue("No reader may hang.", readers.awaitTermination(30, TimeUnit.SECONDS));
		for (Future<Integer> result : results) {
			assertTrue(result.get().intValue() > 0);
		}
	}

	// --- Helpers. ---

	private HashIndex open(Path root) {
		HashIndex result = new HashIndex(root);
		_indexes.add(result);
		return result;
	}

	private void close(HashIndex index) {
		index.shutdown();
		_indexes.remove(index);
	}

	private static Long cacheSize(HashIndex index) {
		return index.store().read(connection -> {
			try (PreparedStatement select = connection
				.prepareStatement("SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME = ?")) {
				select.setString(1, "CACHE_SIZE");
				try (java.sql.ResultSet rows = select.executeQuery()) {
					return rows.next() ? Long.valueOf(rows.getString(1)) : null;
				}
			}
		}, null);
	}

	private static Path databaseFile(Path root) {
		return SpaceIndex.directoryOf(root).resolve(SpaceIndex.DATABASE_NAME + ".mv.db");
	}

	/** Whether this process holds a lock of the given file. */
	private static boolean lockedHere(Path file) throws Exception {
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
			FileLock lock = channel.tryLock();
			if (lock == null) {
				return true;
			}
			lock.release();
			return false;
		} catch (OverlappingFileLockException ex) {
			return true;
		}
	}

	private String hashOf(String path) throws Exception {
		return HashCache.sha256(_base.resolve(path).toFile());
	}

	/** What <code>?action=check</code> in the given folder answers present for the given hash. */
	private List<String> present(String pathInfo, String hash) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "check");
		FakeResponse response =
			post(pathInfo, "{\"hashes\":[{\"hash\":\"" + hash + "\"}]}", SharingFixture.ALICE, parameters);
		assertEquals(body(response), HttpServletResponse.SC_OK, response.status());
		List<String> result = new ArrayList<>();
		de.haumacher.imageServer.shared.model.UploadCheckResult.readUploadCheckResult(reader(body(response)))
			.getPresent().forEach(file -> result.add(file.getName()));
		return result;
	}

	/** A tiny JPEG with contents of its own. */
	private static byte[] photo(String seed) throws Exception {
		Random random = new Random(("space-index/" + seed).hashCode());
		java.awt.image.BufferedImage image =
			new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_3BYTE_BGR);
		for (int x = 0; x < 16; x++) {
			for (int y = 0; y < 16; y++) {
				image.setRGB(x, y, random.nextInt(0xFFFFFF));
			}
		}
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		javax.imageio.ImageIO.write(image, "jpg", out);
		return out.toByteArray();
	}
}
