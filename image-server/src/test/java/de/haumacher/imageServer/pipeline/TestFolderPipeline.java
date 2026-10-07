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
import java.util.concurrent.atomic.AtomicInteger;
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
		_root = Files.createTempDirectory("valbum-pipeline");
		_album = new File(_root.toFile(), "2020/Trip");
		assertTrue(_album.mkdirs());
		photo("a.jpg");
	}

	@Override
	protected void tearDown() throws Exception {
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

	public void testNothingIsNoticedBeforeTheStart() throws Exception {
		FolderPipeline pipeline = new FolderPipeline(_root, "test");
		Counting step = new Counting("count", false);
		pipeline.addStep(step);
		pipeline.notice(_album, false);
		assertTrue(pipeline.awaitIdle(1000));
		assertEquals(0, step._runs.get());
	}

	/** A step of the videos that records its units and answers what it is told to. */
	private static final class Units implements FolderPipeline.UnitStep {

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

	private void photo(String name) throws IOException {
		Files.write(new File(_album, name).toPath(), name.getBytes());
	}
}
