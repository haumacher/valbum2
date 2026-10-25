/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.pipeline;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Test case for {@link FolderPipeline}, see issue #235.
 */
@SuppressWarnings("javadoc")
public class TestFolderPipeline extends TestCase {

	/** A step that counts its runs and answers what it is told to. */
	private static class Counting implements FolderPipeline.Step {

		final String _name;

		final boolean _trusts;

		final AtomicInteger _runs = new AtomicInteger();

		volatile FolderPipeline.Outcome _outcome = FolderPipeline.Outcome.DONE;

		Counting(String name, boolean trusts) {
			_name = name;
			_trusts = trusts;
		}

		@Override
		public String name() {
			return _name;
		}

		@Override
		public boolean trustsRecord() {
			return _trusts;
		}

		@Override
		public FolderPipeline.Outcome run(File folder) throws IOException {
			_runs.incrementAndGet();
			return _outcome;
		}
	}

	private Path _root;

	private File _album;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		// A request another test made a moment ago must not make a sweep give way here.
		Background.setQuietMillis(0);
		_root = Files.createTempDirectory("valbum-pipeline");
		_album = new File(_root.toFile(), "2020/Trip");
		assertTrue(_album.mkdirs());
		photo("a.jpg");
	}

	@Override
	protected void tearDown() throws Exception {
		Background.setQuietMillis(Background.QUIET_MILLIS);
		try (Stream<Path> files = Files.walk(_root)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		super.tearDown();
	}

	public void testTheRecordIsWrittenAndResumesAfterARestart() throws Exception {
		FolderPipeline pipeline = new FolderPipeline(_root, "test");
		Counting step = new Counting("thumbs", true);
		pipeline.addStep(step);
		pipeline.process(_album);
		pipeline.flush();
		assertEquals(1, step._runs.get());
		assertTrue(pipeline.getFile().isFile());

		FolderPipeline restarted = new FolderPipeline(_root, "test");
		Counting again = new Counting("thumbs", true);
		restarted.addStep(again);
		Map<String, FolderPipeline.Record> records = restarted.records();
		assertEquals(pipeline.records().keySet(), records.keySet());
		FolderPipeline.Record record = records.get("2020/Trip");
		assertEquals(pipeline.records().get("2020/Trip")._fingerprint, record._fingerprint);
		assertEquals(Arrays.asList("thumbs"), Arrays.asList(record._done.toArray()));

		restarted.process(_album);
		assertEquals("Done before the restart: not run again.", 0, again._runs.get());
		assertTrue(restarted.isDone(_album, "thumbs"));

		photo("b.jpg");
		restarted.process(_album);
		assertEquals("The folder changed: run again.", 1, again._runs.get());

		restarted.flush();
		FolderPipeline third = new FolderPipeline(_root, "test");
		assertEquals(restarted.records().get("2020/Trip")._fingerprint,
			third.records().get("2020/Trip")._fingerprint);
	}

	public void testAStepThatDoesNotTrustTheRecordAlwaysRuns() throws Exception {
		FolderPipeline pipeline = new FolderPipeline(_root, "test");
		Counting step = new Counting("hash", false);
		pipeline.addStep(step);
		pipeline.process(_album);
		pipeline.process(_album);
		assertEquals(2, step._runs.get());
	}

	public void testAFailedStepIsNotRetriedInALoopAndStopsTheStepsBehindIt() throws Exception {
		FolderPipeline pipeline = new FolderPipeline(_root, "test");
		Counting first = new Counting("first", false);
		Counting second = new Counting("second", true);
		pipeline.addStep(first);
		pipeline.addStep(second);
		first._outcome = FolderPipeline.Outcome.FAILED;

		pipeline.process(_album);
		pipeline.process(_album);
		assertEquals("Not retried while the folder is unchanged.", 1, first._runs.get());
		assertEquals("What builds on it does not run.", 0, second._runs.get());

		first._outcome = FolderPipeline.Outcome.DONE;
		photo("c.jpg");
		pipeline.process(_album);
		assertEquals("Retried once the folder changed.", 2, first._runs.get());
		assertEquals(1, second._runs.get());
	}

	public void testAnUnwritableStepLetsTheOthersRunAndIsNotRetried() throws Exception {
		FolderPipeline pipeline = new FolderPipeline(_root, "test");
		Counting first = new Counting("first", false);
		Counting second = new Counting("second", true);
		pipeline.addStep(first);
		pipeline.addStep(second);
		first._outcome = FolderPipeline.Outcome.UNWRITABLE;

		pipeline.process(_album);
		pipeline.process(_album);
		assertEquals(1, first._runs.get());
		assertEquals(1, second._runs.get());
		assertTrue(ReadOnlyFolders.isReadOnly(_album));
		ReadOnlyFolders.writtenAgain(_album);
	}

	public void testABurstOfNoticesIsWorkedOnOnceAfterTheQuietPeriod() throws Exception {
		FolderPipeline pipeline = new FolderPipeline(_root, "test");
		Counting step = new Counting("count", false);
		pipeline.addStep(step);
		pipeline.setQuietMillis(300);
		pipeline.start();
		try {
			for (int n = 0; n < 8; n++) {
				photo("burst-" + n + ".jpg");
				pipeline.notice(_album, false);
				Thread.sleep(60);
			}
			assertTrue(pipeline.awaitIdle(30_000));
			assertEquals("One run for the whole burst.", 1, step._runs.get());

			// A new folder with a tree in it is worked on as a whole.
			File copied = new File(_root.toFile(), "Copied/Inner");
			assertTrue(copied.mkdirs());
			Files.write(new File(copied, "x.jpg").toPath(), new byte[] { 1, 2, 3 });
			pipeline.notice(copied.getParentFile(), true);
			assertTrue(pipeline.awaitIdle(30_000));
			assertEquals("The new folder and the one inside it.", 3, step._runs.get());
		} finally {
			pipeline.shutdown();
		}
	}

	/**
	 * A folder of a tree copied in that the watch saw change (it watches the new folders while the
	 * copy still writes) waits with the tree, and is worked on once, see issue #266.
	 */
	public void testAFolderNoticedInsideATreeThatWaitsIsWorkedOnOnce() throws Exception {
		FolderPipeline pipeline = new FolderPipeline(_root, "test");
		Counting step = new Counting("count", false);
		pipeline.addStep(step);
		pipeline.setQuietMillis(300);
		pipeline.start();
		try {
			File copied = new File(_root.toFile(), "Copied");
			File inner = new File(copied, "Inner");
			assertTrue(inner.mkdirs());
			Files.write(new File(inner, "x.jpg").toPath(), new byte[] { 1, 2, 3 });
			File other = new File(_root.toFile(), "Other/Inner");
			assertTrue(other.mkdirs());
			Files.write(new File(other, "y.jpg").toPath(), new byte[] { 1, 2, 3 });

			// The tree first, a folder in it a moment later; and the other way round.
			pipeline.notice(copied, true);
			pipeline.notice(other, false);
			Thread.sleep(150);
			pipeline.notice(inner, false);
			pipeline.notice(other.getParentFile(), true);
			assertTrue(pipeline.awaitIdle(30_000));
			assertEquals("Each folder of the two trees once.", 4, step._runs.get());
		} finally {
			pipeline.shutdown();
		}
	}

	public void testNothingIsNoticedBeforeTheStart() throws Exception {
		FolderPipeline pipeline = new FolderPipeline(_root, "test");
		Counting step = new Counting("count", false);
		pipeline.addStep(step);
		pipeline.notice(_album, false);
		assertTrue(pipeline.awaitIdle(1000));
		assertEquals(0, step._runs.get());
	}

	/** A step of the videos that records its units and answers what it is told to. */
	private static class Units implements FolderPipeline.UnitStep {

		final List<String> _log;

		final Set<String> _waiting = new HashSet<>();

		/** Units answered {@link FolderPipeline.Outcome#AGAIN} once. */
		final Set<String> _again = new HashSet<>();

		Units(List<String> log) {
			_log = log;
		}

		@Override
		public String name() {
			return "videos";
		}

		@Override
		public List<String> units(File folder) {
			return Arrays.asList("teaser:" + folder.getName(), "video:" + folder.getName());
		}

		@Override
		public String itemOf(String unit) {
			return unit.substring(unit.indexOf(':') + 1);
		}

		@Override
		public FolderPipeline.Outcome runUnit(File folder, String unit) {
			_log.add(unit);
			if (_again.remove(unit)) {
				return FolderPipeline.Outcome.AGAIN;
			}
			return _waiting.contains(unit) ? FolderPipeline.Outcome.WAITING : FolderPipeline.Outcome.DONE;
		}
	}

	public void testAFileOfTheFormerVersionStillReads() throws Exception {
		String fingerprint = FolderPipeline.fingerprint(Arrays.asList(_album));
		File file = new FolderPipeline(_root, "test").getFile();
		assertTrue(file.getParentFile().mkdirs() || file.getParentFile().isDirectory());
		// Exactly what the build of issue #235 wrote.
		Files.write(file.toPath(), ("{\"version\":1,\"folders\":{\"2020/Trip\":{\"fingerprint\":\"" + fingerprint
			+ "\",\"done\":[\"hash\",\"thumbs\"]}}}").getBytes(StandardCharsets.UTF_8));

		FolderPipeline pipeline = new FolderPipeline(_root, "test");
		FolderPipeline.Record record = pipeline.records().get("2020/Trip");
		assertEquals(fingerprint, record._fingerprint);
		assertEquals(Arrays.asList("hash", "thumbs"), new ArrayList<>(record._done));
		assertTrue(record._units.isEmpty());
		Counting step = new Counting("thumbs", true);
		pipeline.addStep(step);
		pipeline.process(_album);
		assertEquals("Done under the old file: not run again.", 0, step._runs.get());
	}

	public void testUnitsAreRecordedAndResumeAfterARestart() throws Exception {
		List<String> log = new ArrayList<>();
		FolderPipeline pipeline = new FolderPipeline(_root, "test");
		Units units = new Units(log);
		units._waiting.add("video:Trip");
		pipeline.addStep(units);
		pipeline.process(_album);
		assertEquals("Units never run in the photo steps.", 0, log.size());
		assertEquals(1, pipeline.status().getVideos());
		pipeline.drain();
		assertEquals(Arrays.asList("teaser:Trip", "video:Trip"), log);
		assertFalse(pipeline.isDone(_album, "videos"));

		// read -> write -> read
		FolderPipeline restarted = new FolderPipeline(_root, "test");
		FolderPipeline.Record record = restarted.records().get("2020/Trip");
		assertEquals(Collections.singleton("teaser:Trip"), record._units.get("videos"));
		restarted.flush();
		String written = new String(Files.readAllBytes(restarted.getFile().toPath()), StandardCharsets.UTF_8);
		assertTrue(written, written.contains("\"units\":{\"videos\":[\"teaser:Trip\"]}"));
		assertTrue(written, written.contains("\"version\":" + FolderPipeline.VERSION));
		FolderPipeline third = new FolderPipeline(_root, "test");
		assertEquals(record._units, third.records().get("2020/Trip")._units);
		assertEquals(record._fingerprint, third.records().get("2020/Trip")._fingerprint);

		log.clear();
		Units again = new Units(log);
		third.addStep(again);
		third.process(_album);
		third.drain();
		assertEquals("Only what was not finished.", Arrays.asList("video:Trip"), log);
		assertTrue(third.isDone(_album, "videos"));
		assertTrue("A finished step keeps no units.", third.records().get("2020/Trip")._units.isEmpty());

		third.process(_album);
		third.drain();
		assertEquals("Nothing again.", 1, log.size());
	}

	public void testVideosRunBehindThePhotosOfEveryAlbum() throws Exception {
		File one = new File(_root.toFile(), "2021-01-01 One");
		File two = new File(_root.toFile(), "2022-01-01 Two");
		assertTrue(one.mkdirs() && two.mkdirs());
		Files.write(new File(one, "x.jpg").toPath(), new byte[] { 1 });
		Files.write(new File(two, "y.jpg").toPath(), new byte[] { 2 });

		List<String> log = new java.util.concurrent.CopyOnWriteArrayList<>();
		FolderPipeline pipeline = new FolderPipeline(_root, "test");
		pipeline.addStep(new FolderPipeline.Step() {
			@Override
			public String name() {
				return "photos";
			}

			@Override
			public FolderPipeline.Outcome run(File folder) {
				log.add("photos:" + folder.getName());
				return FolderPipeline.Outcome.DONE;
			}
		});
		pipeline.addStep(new Units(log));
		pipeline.start();
		try {
			pipeline.catchUp(FolderPipeline.newestFirst(Arrays.asList(one, _album, two)));
			assertTrue(pipeline.awaitIdle(30_000));
		} finally {
			pipeline.shutdown();
		}
		assertEquals(Arrays.asList(
			"photos:2022-01-01 Two", "photos:2021-01-01 One", "photos:Trip",
			"teaser:2022-01-01 Two", "video:2022-01-01 Two", "teaser:2021-01-01 One", "video:2021-01-01 One",
			"teaser:Trip", "video:Trip"), log);
		FolderPipeline.Status status = pipeline.status();
		assertEquals(3, status.getDone());
		assertEquals(3, status.getTotal());
		assertEquals(0, status.getVideos());
	}

	public void testAUnitPutAsideComesBackAndIsDoneThen() throws Exception {
		List<String> log = new java.util.concurrent.CopyOnWriteArrayList<>();
		FolderPipeline pipeline = new FolderPipeline(_root, "test");
		Units units = new Units(log);
		units._again.add("teaser:Trip");
		pipeline.addStep(units);
		pipeline.start();
		try {
			pipeline.process(_album);
			assertTrue(pipeline.awaitIdle(30_000));
		} finally {
			pipeline.shutdown();
		}
		assertEquals("Put aside, then made from the start, then the rest.",
			Arrays.asList("teaser:Trip", "teaser:Trip", "video:Trip"), log);
		assertTrue(pipeline.isDone(_album, "videos"));
	}

	/**
	 * Issue #311: the units of a step with a lane run on threads of their own, as many at once as the
	 * lane allows and never more, while the photo work of an album noticed meanwhile goes on; they are
	 * recorded as the units of the thread are.
	 */
	public void testTheUnitsOfALaneRunBesideThePhotoWork() throws Exception {
		List<String> log = new java.util.concurrent.CopyOnWriteArrayList<>();
		AtomicInteger active = new AtomicInteger();
		AtomicInteger most = new AtomicInteger();
		java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
		FolderPipeline pipeline = new FolderPipeline(_root, "test");
		pipeline.addStep(new FolderPipeline.Step() {
			@Override
			public String name() {
				return "photos";
			}

			@Override
			public FolderPipeline.Outcome run(File folder) {
				log.add("photos:" + folder.getName());
				return FolderPipeline.Outcome.DONE;
			}
		});
		pipeline.addStep(new FolderPipeline.UnitStep() {
			@Override
			public String name() {
				return "remote";
			}

			@Override
			public int lane() {
				return 2;
			}

			@Override
			public List<String> units(File folder) {
				return folder.equals(_album) ? List.of("u1", "u2", "u3", "u4", "u5") : List.of();
			}

			@Override
			public FolderPipeline.Outcome runUnit(File folder, String unit) {
				most.accumulateAndGet(active.incrementAndGet(), Math::max);
				try {
					release.await(30, java.util.concurrent.TimeUnit.SECONDS);
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					return FolderPipeline.Outcome.WAITING;
				} finally {
					active.decrementAndGet();
				}
				log.add("unit:" + unit);
				return FolderPipeline.Outcome.DONE;
			}
		});
		pipeline.start();
		try {
			pipeline.catchUp(List.of(_album));
			long end = System.currentTimeMillis() + 30_000;
			while (active.get() < 2) {
				assertTrue("Two units at once.", System.currentTimeMillis() < end);
				Thread.sleep(10);
			}
			assertEquals("A unit of the lane is shown as the step at work.", "remote", pipeline.status().getStep());
			File late = new File(_root.toFile(), "2024-01-01 Late");
			assertTrue(late.mkdirs());
			Files.write(new File(late, "z.jpg").toPath(), new byte[] { 3 });
			pipeline.catchUp(List.of(late));
			while (!log.contains("photos:" + late.getName())) {
				assertTrue("The photo work goes on beside the lane.", System.currentTimeMillis() < end);
				Thread.sleep(10);
			}
			assertEquals("No unit finished meanwhile.", 2, log.size());
			Thread.sleep(200);
			assertEquals("Never more than the lane.", 2, active.get());
			release.countDown();
			assertTrue(pipeline.awaitIdle(30_000));
		} finally {
			pipeline.shutdown();
		}
		assertEquals(2, most.get());
		for (String unit : List.of("u1", "u2", "u3", "u4", "u5")) {
			assertEquals("Once: " + unit + " in " + log, 1, Collections.frequency(log, "unit:" + unit));
		}
		assertTrue(pipeline.isDone(_album, "remote"));
		assertEquals(0, pipeline.status().getRemaining("remote"));
	}

	public void testAVideoStepMustWorkInUnits() {
		FolderPipeline pipeline = new FolderPipeline(_root, "test");
		try {
			pipeline.addStep(new Counting("videos", true) {
				@Override
				public FolderPipeline.Stage stage() {
					return FolderPipeline.Stage.VIDEOS;
				}
			});
			fail("Refused.");
		} catch (IllegalArgumentException ex) {
			assertTrue(ex.getMessage(), ex.getMessage().contains("videos"));
		}
	}

	/** A step that reads every photograph of a folder, as hashing does. */
	private static final class Reading implements FolderPipeline.Step {

		final AtomicInteger _runs = new AtomicInteger();

		@Override
		public String name() {
			return "read";
		}

		@Override
		public boolean trustsRecord() {
			return false;
		}

		@Override
		public FolderPipeline.Outcome run(File folder) throws IOException {
			if (folder.getName().equals("Trip")) {
				_runs.incrementAndGet();
			}
			File[] files = folder.listFiles(f -> f.isFile() && f.getName().endsWith(".jpg"));
			Arrays.sort(files);
			for (File file : files) {
				Files.readAllBytes(file.toPath());
			}
			return FolderPipeline.Outcome.DONE;
		}
	}

	/**
	 * A {@link Counting} step that works on the album only: a started pipeline also works on the
	 * folders a sweep finds changed, the root among them when the progress file is first written.
	 */
	private Counting onAlbum(String name) {
		return new Counting(name, false) {
			@Override
			public FolderPipeline.Outcome run(File folder) throws IOException {
				return folder.equals(_album) ? super.run(folder) : FolderPipeline.Outcome.DONE;
			}
		};
	}

	/** A clock the test moves. */
	private static final class Clock implements LongSupplier {

		volatile long _now = 1_000_000;

		@Override
		public long getAsLong() {
			return _now;
		}
	}

	/** Whether this process can make a file unreadable for itself: not as root, who reads everything. */
	private static boolean canLock(File file) {
		assertTrue(file.setReadable(false));
		boolean result = !file.canRead();
		assertTrue(file.setReadable(true));
		return result;
	}

	public void testAFolderThatCouldNotBeReadIsRetriedAfterItsBackOffAndTheFailureCleared() throws Exception {
		File locked = new File(_album, "a.jpg");
		if (!canLock(locked)) {
			// Running as root: there is no unreadable file.
			return;
		}
		Clock clock = new Clock();
		FolderPipeline pipeline = new FolderPipeline(_root, "test");
		pipeline.setClock(clock);
		pipeline.setRetryMillis(10_000, 80_000);
		pipeline.setQuietMillis(20);
		pipeline.setSweepMillis(20);
		// A folder nobody watches: a watch would report the chmod below as a change, which ends the
		// back-off at once (see TestLibraryWatch); this is about the back-off itself.
		pipeline.setMaxWatches(0);
		Reading step = new Reading();
		Counting behind = onAlbum("behind");
		pipeline.addStep(step);
		pipeline.addStep(behind);
		assertTrue(locked.setReadable(false));
		pipeline.start();
		try {
			pipeline.process(_album);
			assertEquals(1, step._runs.get());
			assertEquals(0, behind._runs.get());
			FolderPipeline.Status status = pipeline.status();
			assertEquals("2020/Trip", status.getFailureFolder());
			assertTrue(status.getFailure(), status.getFailure().startsWith("read: "));

			// The permissions are fixed: no file name, size or time changes.
			assertTrue(locked.setReadable(true));
			Thread.sleep(200);
			assertTrue(pipeline.awaitIdle(30_000));
			assertEquals("Not before the back-off is over.", 1, step._runs.get());

			clock._now += 10_000;
			long end = System.currentTimeMillis() + 30_000;
			while (behind._runs.get() == 0 && System.currentTimeMillis() < end) {
				Thread.sleep(20);
			}
			assertTrue(pipeline.awaitIdle(30_000));
			assertEquals("Retried by itself, once.", 2, step._runs.get());
			assertEquals("And what builds on it runs.", 1, behind._runs.get());
			status = pipeline.status();
			assertNull("The failure is no more: " + status.getFailure(), status.getFailure());
			assertNull(status.getFailureFolder());
		} finally {
			locked.setReadable(true);
			pipeline.shutdown();
		}
	}

	public void testAFolderThatKeepsFailingWaitsLongerAndLonger() throws Exception {
		Clock clock = new Clock();
		FolderPipeline pipeline = new FolderPipeline(_root, "test");
		pipeline.setClock(clock);
		pipeline.setRetryMillis(1000, 4000);
		Counting step = new Counting("failing", false);
		step._outcome = FolderPipeline.Outcome.FAILED;
		pipeline.addStep(step);

		pipeline.process(_album);
		assertEquals(1, step._runs.get());

		// Back-offs of 1, 2, 4 and (capped) 4 seconds.
		long[] waits = { 1000, 2000, 4000, 4000 };
		for (int n = 0; n < waits.length; n++) {
			clock._now += waits[n] - 1;
			pipeline.process(_album);
			assertEquals("Not before the back-off of " + waits[n] + " ms.", n + 1, step._runs.get());
			clock._now += 1;
			pipeline.process(_album);
			assertEquals("After the back-off of " + waits[n] + " ms.", n + 2, step._runs.get());
			pipeline.process(_album);
			assertEquals("Once.", n + 2, step._runs.get());
		}

		// Succeeding ends the series: the next failure waits the first back-off again.
		step._outcome = FolderPipeline.Outcome.DONE;
		clock._now += 4000;
		pipeline.process(_album);
		assertEquals(6, step._runs.get());
		assertNull(pipeline.status().getFailure());
		step._outcome = FolderPipeline.Outcome.FAILED;
		pipeline.process(_album);
		assertEquals(7, step._runs.get());
		clock._now += 1000;
		pipeline.process(_album);
		assertEquals(8, step._runs.get());
	}

	public void testTheSweepRetriesAFailedFolderOnceItsBackOffIsOver() throws Exception {
		Clock clock = new Clock();
		FolderPipeline pipeline = new FolderPipeline(_root, "test");
		pipeline.setClock(clock);
		pipeline.setRetryMillis(1000, 1000);
		pipeline.setQuietMillis(20);
		pipeline.setSweepMillis(20);
		Counting step = onAlbum("failing");
		step._outcome = FolderPipeline.Outcome.FAILED;
		pipeline.addStep(step);
		pipeline.start();
		try {
			pipeline.process(_album);
			// Many sweeps: none retries before the back-off.
			Thread.sleep(300);
			assertTrue(pipeline.awaitIdle(30_000));
			assertEquals(1, step._runs.get());
			for (int n = 2; n <= 4; n++) {
				clock._now += 1000;
				long end = System.currentTimeMillis() + 30_000;
				while (step._runs.get() < n && System.currentTimeMillis() < end) {
					Thread.sleep(20);
				}
				Thread.sleep(200);
				assertTrue(pipeline.awaitIdle(30_000));
				assertEquals("One retry per back-off, no loop.", n, step._runs.get());
			}
		} finally {
			pipeline.shutdown();
		}
	}

	public void testAChangeAWatchReportsEndsTheBackOff() throws Exception {
		Clock clock = new Clock();
		FolderPipeline pipeline = new FolderPipeline(_root, "test");
		pipeline.setClock(clock);
		pipeline.setQuietMillis(20);
		Counting step = onAlbum("failing");
		step._outcome = FolderPipeline.Outcome.FAILED;
		pipeline.addStep(step);
		pipeline.start();
		try {
			pipeline.process(_album);
			pipeline.notice(_album, false);
			assertTrue(pipeline.awaitIdle(30_000));
			assertEquals("Being looked at again is no reason to retry.", 1, step._runs.get());

			step._outcome = FolderPipeline.Outcome.DONE;
			pipeline.changed(_album);
			assertTrue(pipeline.awaitIdle(30_000));
			assertEquals("A change (chmod) is.", 2, step._runs.get());
			assertNull(pipeline.status().getFailure());
		} finally {
			pipeline.shutdown();
		}
	}

	public void testAFailureIsNotClearedBySuccessElsewhereOrByAnotherStep() throws Exception {
		File other = new File(_root.toFile(), "2021/Other");
		assertTrue(other.mkdirs());
		Files.write(new File(other, "o.jpg").toPath(), new byte[] { 1 });
		FolderPipeline pipeline = new FolderPipeline(_root, "test");
		Counting first = new Counting("first", false) {
			@Override
			public FolderPipeline.Outcome run(File folder) throws IOException {
				_runs.incrementAndGet();
				if (folder.equals(_album)) {
					throw new IOException("Permission denied");
				}
				return FolderPipeline.Outcome.DONE;
			}
		};
		pipeline.addStep(first);
		pipeline.process(_album);
		assertEquals("first: Permission denied", pipeline.status().getFailure());

		pipeline.process(other);
		assertEquals("A success in another folder clears nothing.", "first: Permission denied",
			pipeline.status().getFailure());
		assertEquals("2020/Trip", pipeline.status().getFailureFolder());

		// A failure reported by a later step, for another folder (as the cover does for the folders
		// above), stands until that step does its work on the folder it ran on.
		FolderPipeline second = new FolderPipeline(_root, "test");
		AtomicInteger broken = new AtomicInteger(1);
		second.addStep(new Counting("hash", false));
		second.addStep(new Counting("cover", false) {
			@Override
			public FolderPipeline.Outcome run(File folder) {
				if (folder.equals(_album) && broken.get() > 0) {
					second.reportFailure(_album.getParentFile(), "cover: a.jpg: broken");
				}
				return FolderPipeline.Outcome.DONE;
			}
		});
		second.process(_album);
		assertEquals("cover: a.jpg: broken", second.status().getFailure());
		assertEquals("2020", second.status().getFailureFolder());
		second.process(other);
		assertEquals("cover: a.jpg: broken", second.status().getFailure());
		second.process(_album);
		assertEquals("Failed again: stands.", "cover: a.jpg: broken", second.status().getFailure());
		broken.set(0);
		second.process(_album);
		assertNull("Done without failing: gone.", second.status().getFailure());
	}

	public void testWhatAFinishedStepReportedStaysUntilItIsReportedNoMore() throws Exception {
		File other = new File(_root.toFile(), "2021/Other");
		assertTrue(other.mkdirs());
		Set<String> broken = Collections.synchronizedSet(new HashSet<>(Arrays.asList("x.jpg", "y.jpg")));
		FolderPipeline pipeline = new FolderPipeline(_root, "test");
		pipeline.addStep(new Counting("previews", false) {
			@Override
			public FolderPipeline.Outcome run(File folder) {
				if (folder.equals(_album)) {
					for (String name : new TreeSet<>(broken)) {
						pipeline.reportFailure(folder, "previews: " + name + ": broken");
					}
				}
				return FolderPipeline.Outcome.DONE;
			}
		});
		pipeline.process(_album);
		assertEquals("previews: y.jpg: broken", pipeline.status().getFailure());

		// Another folder failing later is shown; when it is fixed, the album's failure is again.
		pipeline.reportFailure(other, "elsewhere: broken");
		assertEquals("2021/Other", pipeline.status().getFailureFolder());
		pipeline.gone("2021/Other");
		assertEquals("Gone with its folder.", "previews: y.jpg: broken", pipeline.status().getFailure());

		// y.jpg is fixed, x.jpg is not: the step finishes again, reporting only x.jpg.
		broken.remove("y.jpg");
		pipeline.process(_album);
		assertEquals("previews: x.jpg: broken", pipeline.status().getFailure());
		assertEquals("2020/Trip", pipeline.status().getFailureFolder());

		broken.clear();
		pipeline.process(_album);
		assertNull("Nothing is outstanding.", pipeline.status().getFailure());

		broken.add("x.jpg");
		pipeline.process(_album);
		pipeline.gone("2020/Trip");
		assertNull("A folder that is gone fails no more.", pipeline.status().getFailure());
	}

	public void testOnlyTheMostRecentOutstandingFailuresAreRemembered() throws Exception {
		FolderPipeline pipeline = new FolderPipeline(_root, "test");
		for (int n = 0; n < FolderPipeline.MAX_OUTSTANDING + 500; n++) {
			pipeline.reportFailure(_album, "broken " + n);
		}
		assertEquals("broken " + (FolderPipeline.MAX_OUTSTANDING + 499), pipeline.status().getFailure());
		assertEquals(FolderPipeline.MAX_OUTSTANDING, pipeline.outstanding());
	}

	public void testARedoneUnitClearsItsFailureAfterTheBackOff() throws Exception {
		Clock clock = new Clock();
		List<String> log = new ArrayList<>();
		FolderPipeline pipeline = new FolderPipeline(_root, "test");
		pipeline.setClock(clock);
		pipeline.setRetryMillis(1000, 1000);
		Set<String> failing = new HashSet<>(Arrays.asList("video:Trip"));
		pipeline.addStep(new Units(log) {
			@Override
			public FolderPipeline.Outcome runUnit(File folder, String unit) {
				FolderPipeline.Outcome result = super.runUnit(folder, unit);
				return failing.contains(unit) ? FolderPipeline.Outcome.FAILED : result;
			}
		});
		pipeline.process(_album);
		pipeline.drain();
		assertEquals(Arrays.asList("teaser:Trip", "video:Trip"), log);
		assertEquals("videos: Trip", pipeline.status().getFailure());

		pipeline.process(_album);
		pipeline.drain();
		assertEquals("Not before the back-off.", 2, log.size());

		failing.clear();
		clock._now += 1000;
		pipeline.process(_album);
		pipeline.drain();
		assertEquals("Only the unit that failed.", Arrays.asList("teaser:Trip", "video:Trip", "video:Trip"), log);
		assertNull(pipeline.status().getFailure());
		assertTrue(pipeline.isDone(_album, "videos"));
	}

	private void photo(String name) throws IOException {
		Files.write(new File(_album, name).toPath(), name.getBytes());
	}
}
