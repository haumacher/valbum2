/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.content.ContentCache;
import de.haumacher.imageServer.content.ContentIndex;
import de.haumacher.imageServer.content.ContentText;
import de.haumacher.imageServer.shared.model.CatchUpStatus;
import de.haumacher.imageServer.shared.model.CatchUpStep;
import de.haumacher.imageServer.shared.model.ContentSwitch;
import java.awt.Color;
import java.io.File;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

/**
 * The content search with an embedder service on a machine of its own, see issue #311: every test of
 * {@link TestContentSearch} again, its units on a lane of their own, three at once, and what only a
 * lane does.
 */
@SuppressWarnings("javadoc")
public class TestContentSearchOnALane extends TestContentSearch {

	/** As many as the service embeds at once. */
	private static final int LANE = 3;

	@Override
	int lane() {
		return LANE;
	}

	/**
	 * The photo work of an album that arrives while photos are described is not held up: its previews
	 * are made while every lane is waiting for the embedder.
	 */
	public void testPreviewsOfANewAlbumAreMadeWhileEmbeddingsAreInFlight() throws Exception {
		library(true);
		_embedder._hold = new CountDownLatch(1);
		start();
		_embedder.awaitActive(1);

		photo(LATE, "l1.jpg", Color.CYAN);
		photo(LATE, "l2.jpg", Color.MAGENTA);
		pipeline().notice(folder(LATE), false);
		long end = System.currentTimeMillis() + TIMEOUT;
		while (!PreviewCache.previewFile(_base.resolve(LATE + "/l1.jpg").toFile()).isFile()
			|| !PreviewCache.previewFile(_base.resolve(LATE + "/l2.jpg").toFile()).isFile()) {
			assertTrue("The previews are made while the embedder works.", System.currentTimeMillis() < end);
			Thread.sleep(50);
		}
		assertTrue("Still embedding: " + _embedder._active.get(), _embedder._active.get() >= 1);
		assertEquals("Nothing answered yet.", 0, _embedder._images.size());

		_embedder._hold.countDown();
		awaitDescribed();
		end = System.currentTimeMillis() + TIMEOUT;
		while (ContentCache.read(folder(LATE)).photos().size() < 2) {
			assertTrue("The new album is described too.", System.currentTimeMillis() < end);
			Thread.sleep(50);
		}
	}

	/**
	 * The status says what the content search does while it describes photos on its lane: once the
	 * thread of the photo work rests, the step shown is the content search's.
	 */
	@Override
	public void testTheStatusCountsWhatWaitsForADescription() throws Exception {
		library(true);
		_embedder._hold = new CountDownLatch(1);
		start();
		_embedder.awaitActive(1);
		long end = System.currentTimeMillis() + TIMEOUT;
		CatchUpStatus status = status();
		while (status.getStep() != CatchUpStep.CONTENT) {
			assertTrue("The lane is shown once the photo work rests: " + status.getStep(),
				System.currentTimeMillis() < end);
			Thread.sleep(50);
			status = status();
		}
		assertTrue("Waiting: " + status.getContentRemaining(), status.getContentRemaining() >= 1);
		_embedder._hold.countDown();
		awaitDescribed();
		assertEquals(0, status().getContentRemaining());
	}

	/** As many requests in flight as the service embeds at once, never more. */
	public void testAtMostTheServicesCapacityIsInFlight() throws Exception {
		library(true);
		_embedder._hold = new CountDownLatch(1);
		start();
		_embedder.awaitActive(LANE);
		// Long enough for a fourth to start, were it allowed.
		Thread.sleep(500);
		assertEquals(LANE, _embedder._active.get());
		_embedder._hold.countDown();
		awaitDescribed();
		assertEquals("Up to the capacity, never more.", LANE, _embedder._most.get());
		assertEquals("Every medium sent once.", MEDIA.length, _embedder._images.size());
	}

	/**
	 * Photos of one album embedded at once all end up in its cache: each unit's write is a read, a
	 * change and a write under the album's lock, and none loses the vectors of another.
	 */
	public void testPhotosOfOneAlbumEmbeddedAtOnceAllEndUpInItsCache() throws Exception {
		library(true);
		_embedder._hold = new CountDownLatch(1);
		start();
		File n1 = PreviewCache.previewFile(_base.resolve(NEW + "/n1.jpg").toFile());
		File n2 = PreviewCache.previewFile(_base.resolve(NEW + "/n2.jpg").toFile());
		long end = System.currentTimeMillis() + TIMEOUT;
		while (!(n1.isFile() && n2.isFile() && inFlight(n1) && inFlight(n2))) {
			assertTrue("Both photos of the album in flight at once: " + _embedder._inFlight.size(),
				System.currentTimeMillis() < end);
			Thread.sleep(20);
		}
		_embedder._hold.countDown();
		awaitDescribed();

		ContentCache cache = ContentCache.read(folder(NEW));
		for (String name : new String[] { "n1.jpg", "n2.jpg", "clip.mp4" }) {
			ContentCache.Photo photo = cache.photos().get(name);
			assertNotNull("Described: " + name, photo);
			assertNotNull("Its picture: " + name, cache.image(photo.image()));
			assertNotNull("Its text: " + name, cache.text(photo.text()));
		}
		byte[] preview = java.nio.file.Files.readAllBytes(n1.toPath());
		assertTrue("The vector is the embedder's.", Arrays.equals(FakeEmbedder.vector(STAMP + Arrays.hashCode(preview)),
			cache.image(cache.photos().get("n1.jpg").image())));
		// Read, written, read again: the same.
		ContentCache.update(folder(NEW), STAMP, ContentText.stamp(STAMP), each -> {
			// Unchanged.
		});
		assertEquals(cache, ContentCache.read(folder(NEW)));
	}

	/**
	 * Probe: the service changes its model while requests are in flight on the lane. The answers of
	 * the old model arrive after the space has started over with the new one; they must not end up as
	 * vectors of the new model, nor throw away what the new model described: in the end every photo
	 * is described by the new model, and only by it.
	 */
	public void testProbeAModelChangedWhileRequestsAreInFlightEndsInTheNewModel() throws Exception {
		library(true);
		_embedder._stampAtStart = true;
		_embedder._hold = new CountDownLatch(1);
		start();
		_embedder.awaitActive(LANE);

		String next = "fake@0000002/256/fp32/1";
		_embedder._stamp = next;
		_servlet.content().setRecheckMillis(0);
		_embedder._hold.countDown();
		pipeline().notice(folder(OLD), false);
		pipeline().notice(folder(NEW), false);

		long end = System.currentTimeMillis() + TIMEOUT;
		while (!everyPhotoOf(next)) {
			assertTrue("Every photo described by the new model in time.", System.currentTimeMillis() < end);
			Thread.sleep(50);
		}
		awaitCatchUp();
		assertTrue("Still every photo by the new model once the work rests.", everyPhotoOf(next));
	}

	/** Whether every medium's album is stamped with the given model and holds that model's vector of it. */
	private boolean everyPhotoOf(String stamp) throws java.io.IOException {
		for (String medium : MEDIA) {
			File file = _base.resolve(medium).toFile();
			ContentCache cache = ContentCache.read(file.getParentFile());
			ContentCache.Photo photo = cache.photos().get(file.getName());
			if (!stamp.equals(cache.getImageStamp()) || photo == null || cache.image(photo.image()) == null) {
				return false;
			}
			File preview = PreviewCache.previewFile(file);
			if (!file.getName().endsWith(".mp4") && preview.isFile()) {
				byte[] bytes = java.nio.file.Files.readAllBytes(preview.toPath());
				if (!Arrays.equals(FakeEmbedder.vector(stamp + Arrays.hashCode(bytes)), cache.image(photo.image()))) {
					return false;
				}
			}
		}
		return true;
	}

	private boolean inFlight(File preview) throws java.io.IOException {
		return _embedder._inFlight.contains(Integer.valueOf(Arrays.hashCode(java.nio.file.Files.readAllBytes(preview.toPath()))));
	}

	/**
	 * An embedder that goes down while units are in flight puts the content search into its back-off
	 * once (the failures of the units at once do not double it each), the progress still adds up, and
	 * once it answers again everything is described.
	 */
	public void testAnEmbedderGoingDownMidRunBacksOffOnceAndTheCountsStayRight() throws Exception {
		library(true);
		_embedder._hold = new CountDownLatch(1);
		start();
		_servlet.content().setRetryMillis(5_000, 60_000);
		_embedder.awaitActive(LANE);
		_embedder._problem = "the embedder service at http://gpu:8091/ does not answer";
		long down = System.currentTimeMillis();
		_embedder._hold.countDown();
		awaitCatchUp();

		CatchUpStatus status = status();
		assertEquals(_embedder._problem, status.getContentWaiting());
		assertTrue("Backed off once, not doubled per unit: " + (status.getContentRetryAt() - down),
			status.getContentRetryAt() - down <= 5_000 + 1_000);
		assertEquals(MEDIA.length, status.getContentTotal());
		int described = 0;
		for (String medium : MEDIA) {
			File file = _base.resolve(medium).toFile();
			if (ContentCache.read(file.getParentFile()).photos().containsKey(file.getName())) {
				described++;
			}
		}
		assertEquals("Described as the caches say.", described, status.getContentDescribed());
		assertEquals(MEDIA.length - described, status.getContentRemaining());
		assertTrue("The units in flight failed: " + described, described <= MEDIA.length - LANE);

		_embedder._problem = null;
		assertTrue(_servlet.content().retryNow());
		awaitDescribed();
		status = status();
		assertEquals(MEDIA.length, status.getContentDescribed());
		assertEquals(0, status.getContentRemaining());
		assertEquals("", status.getContentWaiting());
	}

	/** Switched off while units are in flight, the lane hands out nothing more. */
	public void testSwitchedOffTheLaneStops() throws Exception {
		library(true);
		_embedder._hold = new CountDownLatch(1);
		start();
		_embedder.awaitActive(LANE);
		saveSettings(settings(get("space-settings")).setContent(ContentSwitch.OFF));
		int started = _embedder._started.get();
		_embedder._hold.countDown();
		awaitCatchUp();
		walk();
		awaitCatchUp();
		assertEquals("Nothing asked for after the switch.", started, _embedder._started.get());
		assertFalse(stepNames().contains(ContentIndex.STEP));
		assertEquals(0, pipeline().status().getRemaining(ContentIndex.STEP));
	}

	/** A shutdown stops the lane while it waits for the embedder; nothing is recorded as done. */
	public void testAShutdownStopsTheLane() throws Exception {
		library(true);
		_embedder._hold = new CountDownLatch(1);
		start();
		_embedder.awaitActive(LANE);
		long start = System.currentTimeMillis();
		_servlet.destroy();
		_servlet = null;
		assertTrue("Stopped at once.", System.currentTimeMillis() - start < 10_000);
		long end = System.currentTimeMillis() + 10_000;
		while (laneThreads() > 0) {
			assertTrue("The lane's threads end: " + laneThreads(), System.currentTimeMillis() < end);
			Thread.sleep(50);
		}
		assertEquals("Nothing answered, nothing written.", 0, _embedder._images.size());
	}

	private static int laneThreads() {
		int result = 0;
		for (Thread thread : Thread.getAllStackTraces().keySet()) {
			if (thread.isAlive() && thread.getName().endsWith(" lane")) {
				result++;
			}
		}
		return result;
	}
}
