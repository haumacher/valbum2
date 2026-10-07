/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.pipeline;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Test case for {@link FolderPipeline}, see issue #235.
 */
@SuppressWarnings("javadoc")
public class TestFolderPipeline extends TestCase {

	/** A step that counts its runs and answers what it is told to. */
	private static final class Counting implements FolderPipeline.Step {

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

	private void photo(String name) throws IOException {
		Files.write(new File(_album, name).toPath(), name.getBytes());
	}
}
