/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.faces.FaceDetection;
import de.haumacher.imageServer.pipeline.FolderPipeline;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test case for faces as a step of the background work, see issue #236.
 *
 * <p>
 * The face index has no start-up walk of its own any more: the pipeline's step is the primary path.
 * An album opened before the pipeline reaches it is still queued by its listing, the fallback, and
 * no photograph is looked at twice.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestFaceCatchUp extends FacesTestCase {

	private static final String SECOND = "2020-01-01 Second";

	/** A detector that counts what it is handed and finds nothing. */
	private static final class Counting implements FaceDetection.Detector {

		final Map<String, AtomicInteger> _seen = new ConcurrentHashMap<>();

		@Override
		public FaceDetection.Result detect(File preview) throws IOException {
			_seen.computeIfAbsent(preview.getParentFile().getParentFile().getName() + "/" + preview.getName(),
				x -> new AtomicInteger()).incrementAndGet();
			return new FaceDetection.Result(800, 600, new ArrayList<>());
		}
	}

	public void testTheStepIsThePrimaryPathAndOpeningAnAlbumStillQueuesIt() throws Exception {
		Counting detector = new Counting();
		FaceDetection.setDetector(detector);
		createSpace(A_ONE, B);
		addAlbum(SECOND, A_TWO, C);

		// The background is held at its first album.
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger held = new AtomicInteger();
		FolderPipeline pipeline = _servlet.index().pipeline();
		pipeline.addStep(new FolderPipeline.Step() {
			@Override
			public String name() {
				return "hold";
			}

			@Override
			public FolderPipeline.Outcome run(File folder) {
				if (held.getAndIncrement() == 0) {
					try {
						release.await(120, TimeUnit.SECONDS);
					} catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
					}
				}
				return FolderPipeline.Outcome.DONE;
			}
		});
		_servlet.startIndexing();
		try {
			assertTrue(_servlet.index().awaitPass(60_000));
			Thread.sleep(1000);
			assertTrue("Nothing walks the space for faces by itself.", detector._seen.isEmpty());

			// Somebody opens the album the background has not reached: it is queued.
			AlbumInfo album = album("/" + SECOND + "/", _adminToken);
			assertTrue("Its faces are said to come.", album.isFacesPending());
			assertTrue(_servlet.faces().awaitQueue(60_000));
			assertEquals(2, detector._seen.size());
			assertTrue(detector._seen.containsKey(SECOND + "/preview-" + A_TWO));
			assertTrue(detector._seen.containsKey(SECOND + "/preview-" + C));
		} finally {
			release.countDown();
		}

		assertTrue(_servlet.index().pipeline().awaitIdle(120_000));
		assertEquals("Then the step looks at the rest.", 4, detector._seen.size());
		for (Map.Entry<String, AtomicInteger> seen : detector._seen.entrySet()) {
			assertEquals("Looked at once: " + seen.getKey(), 1, seen.getValue().get());
		}
		assertFalse("Nothing pending any more.", album("/" + ALBUM + "/", _adminToken).isFacesPending());
	}
}
