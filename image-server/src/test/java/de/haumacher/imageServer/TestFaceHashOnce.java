/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.faces.FaceDetection;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.upload.HashCache;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test case for the face pass and the hash pass meeting in one album, see issue #235.
 *
 * <p>
 * Both hash an album nobody hashed before, both at start-up. One of them hashes it, and the other
 * takes the result over; the sidecar is written once, and nothing either recorded is lost. An album
 * whose cache cannot be written is not looked at in a loop.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestFaceHashOnce extends FacesTestCase {

	/** A detector that counts what it is handed and finds nothing. */
	private static final class Counting implements FaceDetection.Detector {

		final List<String> _seen = new CopyOnWriteArrayList<>();

		@Override
		public FaceDetection.Result detect(File preview) throws IOException {
			_seen.add(preview.getName());
			return new FaceDetection.Result(800, 600, new ArrayList<>());
		}
	}

	private Counting _detector;

	private final Map<String, AtomicInteger> _hashed = new ConcurrentHashMap<>();

	private final Map<String, AtomicInteger> _written = new ConcurrentHashMap<>();

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_detector = new Counting();
		FaceDetection.setDetector(_detector);
		HashCache.setHook(new HashCache.Hook() {
			@Override
			public void hashed(File file) {
				_hashed.computeIfAbsent(file.getAbsolutePath(), x -> new AtomicInteger()).incrementAndGet();
			}

			@Override
			public void written(File folder) {
				_written.computeIfAbsent(folder.getAbsolutePath(), x -> new AtomicInteger()).incrementAndGet();
			}
		});
	}

	@Override
	protected void tearDown() throws Exception {
		HashCache.setHook(null);
		super.tearDown();
	}

	public void testTheFacePassAndTheHashPassHashAnAlbumOnce() throws Exception {
		createSpace(A_ONE, A_TWO, B, C);
		assertTrue(_servlet.faces().isEnabled());
		File album = _base.resolve(ALBUM).toFile();

		CyclicBarrier start = new CyclicBarrier(2);
		List<Throwable> failures = new CopyOnWriteArrayList<>();
		Thread faces = new Thread(() -> {
			try {
				start.await();
				_servlet.faces().indexNow();
			} catch (Throwable ex) {
				failures.add(ex);
			}
		});
		faces.start();
		start.await();
		_servlet.index().indexNow();
		faces.join(120_000);

		assertTrue("" + failures, failures.isEmpty());
		for (String name : new String[] { A_ONE, A_TWO, B, C }) {
			AtomicInteger count = _hashed.get(new File(album, name).getAbsolutePath());
			assertEquals("Hashed exactly once: " + name, 1, count == null ? 0 : count.get());
		}
		AtomicInteger writes = _written.get(album.getAbsolutePath());
		assertEquals("One write of the sidecar.", 1, writes == null ? 0 : writes.get());
		assertEquals("No entry lost.", 4, new HashCache(album).storedHashByName().size());
		assertEquals("The face pass looked at every photograph.", 4, _detector._seen.size());
	}

	public void testAnAlbumWhoseCacheCannotBeWrittenIsNotLookedAtAgainAndAgain() throws Exception {
		createSpace(A_ONE, B);
		File album = _base.resolve(ALBUM).toFile();
		assertTrue(album.setWritable(false, false));
		try {
			if (album.canWrite()) {
				// The operating system ignores the mode (running as root): nothing to test here.
				return;
			}
			_servlet.index().indexNow();
			_servlet.faces().indexNow();

			for (int n = 0; n < 2; n++) {
				AlbumInfo answer = album("/" + ALBUM + "/", _adminToken);
				assertFalse("Nothing pending that could ever be stored.", answer.isFacesPending());
				assertTrue(_servlet.faces().awaitQueue(60_000));
			}
			_servlet.faces().indexNow();

			assertEquals("Not one photograph handed to the detector.", 0, _detector._seen.size());
			for (String name : new String[] { A_ONE, B }) {
				AtomicInteger count = _hashed.get(new File(album, name).getAbsolutePath());
				assertEquals("Hashed once, by the hash pass: " + name, 1, count == null ? 0 : count.get());
			}
		} finally {
			album.setWritable(true, false);
		}
	}
}
