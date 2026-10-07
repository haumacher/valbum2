/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.pipeline.ReadOnlyFolders;
import de.haumacher.imageServer.shared.model.PresentFile;
import de.haumacher.imageServer.shared.model.UploadCheckResult;
import de.haumacher.imageServer.upload.HashCache;
import de.haumacher.imageServer.upload.HashIndex;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import javax.imageio.ImageIO;

/**
 * Test case for photographs copied into a space by hand, see issue #235.
 *
 * <p>
 * "Is a resync required?" &mdash; no: a folder copied in while the server runs is hashed by itself,
 * once, after the copy came to rest; the hash pass, the face pass and an upload never hash a folder
 * twice nor lose each other's entries; and a folder the server cannot write into is known all the
 * same, said once, and not hashed again and again.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestHandCopied extends ShareTestCase {

	private static final String INBOX = "/" + SharingFixture.CAROLS_ALBUM + "/";

	@Override
	protected void tearDown() throws Exception {
		HashCache.setHook(null);
		super.tearDown();
	}

	public void testAFolderCopiedInWhileRunningIsHashedOnceWithoutARestart() throws Exception {
		HashIndex index = servlet().index();
		index.pipeline().setQuietMillis(400);
		servlet().startIndexing();
		assertTrue(index.awaitPass(30_000));
		// Somebody looks at the library, so the root folder is watched.
		assertEquals(HttpServletResponse.SC_OK, get("/", "json", SharingFixture.ALICE).status());

		// The copy: a new folder, filled in a burst of files, each well within the quiet period.
		File copied = _base.resolve("Copied").toFile();
		assertTrue(copied.mkdir());
		List<String> hashes = new ArrayList<>();
		for (int n = 0; n < 6; n++) {
			byte[] photo = photo("copied-" + n);
			Files.write(new File(copied, "IMG_" + n + ".jpg").toPath(), photo);
			hashes.add(HashCache.sha256(photo));
			Thread.sleep(80);
		}

		long end = System.currentTimeMillis() + 30_000;
		while (index.hashRuns("Copied") == 0 && System.currentTimeMillis() < end) {
			Thread.sleep(50);
		}
		assertTrue(index.pipeline().awaitIdle(30_000));
		// Long enough for a second run, if there were going to be one.
		Thread.sleep(1500);
		assertTrue(index.pipeline().awaitIdle(30_000));

		assertEquals("The burst is hashed exactly once.", 1, index.hashRuns("Copied"));
		UploadCheckResult result = check(INBOX, hashes.toArray(new String[0]));
		assertEquals("Every copied photo is present, without a restart.", hashes.size(),
			result.getPresent().size());
		for (PresentFile present : result.getPresent()) {
			assertTrue(present.getName(), present.getName().startsWith("Copied/IMG_"));
		}
		assertTrue("The sidecar is written.", new File(copied, HashCache.FILE_NAME).isFile());
	}

	public void testAPhotoAddedToALoadedAlbumIsHashed() throws Exception {
		HashIndex index = servlet().index();
		index.pipeline().setQuietMillis(300);
		servlet().startIndexing();
		assertTrue(index.awaitPass(30_000));
		assertEquals(HttpServletResponse.SC_OK, get("/" + SharingFixture.PUBLIC + "/", "json", SharingFixture.ALICE).status());

		byte[] photo = photo("added");
		Files.write(_base.resolve(SharingFixture.PUBLIC).resolve("added.jpg"), photo);

		String hash = HashCache.sha256(photo);
		long end = System.currentTimeMillis() + 30_000;
		while (index.pathOf(hash) == null && System.currentTimeMillis() < end) {
			Thread.sleep(50);
		}
		assertEquals(SharingFixture.PUBLIC + "/added.jpg", index.pathOf(hash));
	}

	public void testTheHashPassAndAnUploadHashOnceAndLoseNothing() throws Exception {
		File inbox = _base.resolve(SharingFixture.CAROLS_ALBUM).toFile();
		for (int n = 0; n < 12; n++) {
			Files.write(new File(inbox, "hand-" + n + ".jpg").toPath(), photo("hand-" + n));
		}
		List<String> before = names(inbox);
		Map<String, AtomicInteger> hashed = countHashing();
		byte[] upload = photo("uploaded");
		servlet();

		CyclicBarrier start = new CyclicBarrier(2);
		List<Throwable> failures = new CopyOnWriteArrayList<>();
		Thread pass = new Thread(() -> {
			try {
				start.await();
				servlet().index().indexNow();
			} catch (Throwable ex) {
				failures.add(ex);
			}
		});
		pass.start();
		start.await();
		FakeResponse response = upload(INBOX, SharingFixture.ALICE, "uploaded.jpg", upload);
		pass.join(60_000);

		assertTrue("" + failures, failures.isEmpty());
		assertEquals(body(response), HttpServletResponse.SC_OK, response.status());
		for (String name : before) {
			assertEquals("Hashed once: " + name, 1, count(hashed, new File(inbox, name)));
		}
		assertTrue(count(hashed, new File(inbox, "uploaded.jpg")) <= 1);

		HashCache sidecar = new HashCache(inbox);
		Map<String, String> stored = sidecar.storedHashByName();
		for (String name : before) {
			assertTrue("No entry lost: " + name, stored.containsKey(name));
		}
		assertEquals(HashCache.sha256(upload), stored.get("uploaded.jpg"));
		assertTrue("The upload keeps who sent it.", sidecar.attributionOf("uploaded.jpg").isSet());
		assertNotNull(servlet().index().pathOf(HashCache.sha256(upload)));
	}

	public void testTwoWritersOfOneSidecarKeepBothEntries() throws Exception {
		File folder = _base.resolve(SharingFixture.PUBLIC).toFile();
		File a = new File(folder, "a.jpg");
		File b = new File(folder, "b.jpg");
		Files.write(a.toPath(), photo("a"));
		Files.write(b.toPath(), photo("b"));

		HashCache first = new HashCache(folder);
		HashCache second = new HashCache(folder);
		first.put(a, HashCache.sha256(a), new HashCache.Attribution("user:a", "A"));
		second.put(b, HashCache.sha256(b), new HashCache.Attribution("user:b", "B"));
		first.flush();
		second.flush();

		HashCache read = new HashCache(folder);
		assertEquals("A", read.attributionOf("a.jpg").getLabel());
		assertEquals("B", read.attributionOf("b.jpg").getLabel());
	}

	public void testAFolderThatCannotBeWrittenIsKnownSaidOnceAndNotHashedAgain() throws Exception {
		File locked = _base.resolve("Locked").toFile();
		assertTrue(locked.mkdir());
		byte[] photo = photo("locked");
		Files.write(new File(locked, "a.jpg").toPath(), photo);
		Files.write(new File(locked, "b.jpg").toPath(), photo("locked-b"));
		assertTrue(locked.setWritable(false, false));
		List<LogRecord> warnings = new CopyOnWriteArrayList<>();
		Handler handler = new Handler() {
			@Override
			public void publish(LogRecord record) {
				if (record.getLevel() == Level.WARNING && record.getMessage().contains(locked.getAbsolutePath())) {
					warnings.add(record);
				}
			}

			@Override
			public void flush() {
				// Nothing.
			}

			@Override
			public void close() {
				// Nothing.
			}
		};
		ReadOnlyFolders.logger().addHandler(handler);
		try {
			if (locked.canWrite()) {
				// The operating system ignores the mode (running as root): nothing to test here.
				return;
			}
			Map<String, AtomicInteger> hashed = countHashing();
			HashIndex index = servlet().index();

			index.indexNow();
			assertEquals("Known to the index all the same.", "Locked/a.jpg", index.pathOf(HashCache.sha256(photo)));
			assertFalse(new File(locked, HashCache.FILE_NAME).exists());

			// A second pass and two looks at the album within the process.
			index.indexNow();
			assertEquals(HttpServletResponse.SC_OK, get("/Locked/", "json", SharingFixture.ALICE).status());
			assertEquals(HttpServletResponse.SC_OK, get("/Locked/", "json", SharingFixture.ALICE).status());
			HashCache again = new HashCache(locked);
			again.refresh();

			assertEquals("Hashed once: " + hashed, 1, count(hashed, new File(locked, "a.jpg")));
			assertEquals(1, count(hashed, new File(locked, "b.jpg")));
			assertEquals("One warning, naming the folder: " + warnings, 1, warnings.size());
			assertTrue(warnings.get(0).getMessage().contains("chown -R valbum:valbum"));
			assertEquals("Locked/a.jpg", index.pathOf(HashCache.sha256(photo)));
		} finally {
			ReadOnlyFolders.logger().removeHandler(handler);
			locked.setWritable(true, false);
		}
	}

	// --- Helpers. ---

	private static Map<String, AtomicInteger> countHashing() {
		Map<String, AtomicInteger> result = new ConcurrentHashMap<>();
		HashCache.setHook(new HashCache.Hook() {
			@Override
			public void hashed(File file) {
				result.computeIfAbsent(file.getAbsolutePath(), x -> new AtomicInteger()).incrementAndGet();
			}
		});
		return result;
	}

	private static int count(Map<String, AtomicInteger> hashed, File file) {
		AtomicInteger count = hashed.get(file.getAbsolutePath());
		return count == null ? 0 : count.get();
	}

	private static List<String> names(File folder) {
		List<String> result = new ArrayList<>();
		for (File file : folder.listFiles()) {
			if (file.isFile() && de.haumacher.imageServer.cache.ResourceCache.isImage(file)) {
				result.add(file.getName());
			}
		}
		return result;
	}

	private UploadCheckResult check(String pathInfo, String... hashes) throws Exception {
		StringBuilder body = new StringBuilder("{\"hashes\":[");
		for (int n = 0; n < hashes.length; n++) {
			body.append(n == 0 ? "" : ",").append("{\"hash\":\"").append(hashes[n]).append("\"}");
		}
		body.append("]}");
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "check");
		FakeResponse response = post(pathInfo, body.toString(), SharingFixture.ALICE, parameters);
		assertEquals(body(response), HttpServletResponse.SC_OK, response.status());
		return UploadCheckResult.readUploadCheckResult(reader(body(response)));
	}

	/** A small JPEG with contents of its own. */
	static byte[] photo(String seed) throws Exception {
		Random random = new Random(("hand/" + seed).hashCode());
		BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_3BYTE_BGR);
		for (int x = 0; x < 32; x++) {
			for (int y = 0; y < 32; y++) {
				image.setRGB(x, y, random.nextInt(0xFFFFFF));
			}
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "jpg", out);
		return out.toByteArray();
	}
}
