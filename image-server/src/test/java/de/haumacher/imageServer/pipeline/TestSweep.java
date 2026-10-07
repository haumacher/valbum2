/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.pipeline;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Test case for the sweep of folder modification times, see issue #236: what changes where nobody
 * browses (and so nothing is watched) is found all the same, by looking at directories only.
 */
@SuppressWarnings("javadoc")
public class TestSweep extends TestCase {

	private Path _root;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_root = Files.createTempDirectory("valbum-sweep");
		// A request another test made a moment ago must not make a sweep give way here.
		Background.setQuietMillis(0);
	}

	@Override
	protected void tearDown() throws Exception {
		Background.setQuietMillis(Background.QUIET_MILLIS);
		try (Stream<Path> files = Files.walk(_root)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		super.tearDown();
	}

	/** Counts what the sweep asks of the disk, and checks it asks about directories only. */
	private static final class Counting implements FolderPipeline.Disk {

		final AtomicInteger _stats = new AtomicInteger();

		final AtomicInteger _listings = new AtomicInteger();

		final AtomicInteger _files = new AtomicInteger();

		@Override
		public long modified(File directory) {
			_stats.incrementAndGet();
			if (!directory.isDirectory()) {
				_files.incrementAndGet();
			}
			return FolderPipeline.DISK.modified(directory);
		}

		@Override
		public File[] folders(File directory) {
			_listings.incrementAndGet();
			return FolderPipeline.DISK.folders(directory);
		}

		void reset() {
			_stats.set(0);
			_listings.set(0);
			_files.set(0);
		}
	}

	public void testASweepOfAnUnchangedTreeStatsEachDirectoryOnceAndNothingElse() throws Exception {
		// 10 000 folders below the root, each with a photo in it.
		int folders = 0;
		for (int year = 0; year < 100; year++) {
			for (int album = 0; album < 99; album++) {
				Path dir = _root.resolve("Y" + year).resolve("A" + album);
				Files.createDirectories(dir);
				Files.write(dir.resolve("IMG.jpg"), new byte[] { 1 });
				folders++;
			}
			folders++;
		}
		int directories = folders + 1;
		assertEquals(10_001, directories);

		FolderPipeline pipeline = new FolderPipeline(_root, "test");
		Counting disk = new Counting();
		pipeline.setDisk(disk);
		pipeline.sweep(true);
		assertEquals(directories, disk._stats.get());
		assertEquals("The first sweep lists every directory once.", directories, disk._listings.get());
		assertEquals("No file is ever looked at.", 0, disk._files.get());

		long best = Long.MAX_VALUE;
		for (int n = 0; n < 5; n++) {
			disk.reset();
			long start = System.nanoTime();
			pipeline.sweep(false);
			best = Math.min(best, System.nanoTime() - start);
			assertEquals("One stat per directory.", directories, disk._stats.get());
			assertEquals("Nothing listed that did not change.", 0, disk._listings.get());
			assertEquals(0, disk._files.get());
		}
		System.out.println("A sweep of " + directories + " unchanged folders took " + best / 1_000_000 + " ms.");
	}

	public void testAFolderThatIsGoneLeavesTheRecord() throws Exception {
		File album = new File(_root.toFile(), "2020/Trip");
		assertTrue(album.mkdirs());
		Files.write(new File(album, "a.jpg").toPath(), new byte[] { 1 });
		FolderPipeline pipeline = new FolderPipeline(_root, "test");
		pipeline.addStep(new FolderPipeline.Step() {
			@Override
			public String name() {
				return "thumbs";
			}

			@Override
			public FolderPipeline.Outcome run(File folder) {
				return FolderPipeline.Outcome.DONE;
			}
		});
		pipeline.process(album);
		pipeline.sweep(true);
		pipeline.flush();
		assertTrue(read(pipeline).contains("\"2020/Trip\""));

		Files.delete(new File(album, "a.jpg").toPath());
		Files.delete(album.toPath());
		pipeline.sweep(false);
		pipeline.flush();
		assertFalse("Gone from the file: " + read(pipeline), read(pipeline).contains("2020/Trip"));
		assertNull(pipeline.records().get("2020/Trip"));
		assertNull("Nothing counts as failed.", pipeline.status().getFailure());
	}

	public void testAFolderMadeDeepInAnUnbrowsedTreeIsWorkedOnWithinOneSweep() throws Exception {
		Files.createDirectories(_root.resolve("Archive/1999"));
		FolderPipeline pipeline = new FolderPipeline(_root, "test");
		AtomicInteger runs = new AtomicInteger();
		pipeline.addStep(new FolderPipeline.Step() {
			@Override
			public String name() {
				return "hash";
			}

			@Override
			public FolderPipeline.Stage stage() {
				return FolderPipeline.Stage.INDEX;
			}

			@Override
			public FolderPipeline.Outcome run(File folder) {
				if (folder.getName().equals("Deep")) {
					runs.incrementAndGet();
				}
				return FolderPipeline.Outcome.DONE;
			}
		});
		pipeline.setQuietMillis(100);
		pipeline.setSweepMillis(300);
		pipeline.start();
		try {
			Thread.sleep(400);
			Path deep = _root.resolve("Archive/1999/Summer/Deep");
			Files.createDirectories(deep);
			Files.write(deep.resolve("x.jpg"), new byte[] { 1 });
			long end = System.currentTimeMillis() + 10_000;
			while (runs.get() == 0 && System.currentTimeMillis() < end) {
				Thread.sleep(20);
			}
			assertEquals("Found without a request, within a sweep and a quiet period.", 1, runs.get());
		} finally {
			pipeline.shutdown();
		}
	}

	private static String read(FolderPipeline pipeline) throws Exception {
		return new String(Files.readAllBytes(pipeline.getFile().toPath()), StandardCharsets.UTF_8);
	}
}
