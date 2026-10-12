/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.pipeline;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Probe for issue #251: clearing the last failure must not hide a folder that still fails.
 */
@SuppressWarnings("javadoc")
public class TestFolderPipelineProbe extends TestCase {

	private static final class Clock implements LongSupplier {
		volatile long _now = 1_000_000;

		@Override
		public long getAsLong() {
			return _now;
		}
	}

	private static final class Reading implements FolderPipeline.Step {
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
			File[] files = folder.listFiles(f -> f.isFile() && f.getName().endsWith(".jpg"));
			for (File file : files) {
				Files.readAllBytes(file.toPath());
			}
			return FolderPipeline.Outcome.DONE;
		}
	}

	private Path _root;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		Background.setQuietMillis(0);
		_root = Files.createTempDirectory("valbum-probe");
	}

	@Override
	protected void tearDown() throws Exception {
		Background.setQuietMillis(Background.QUIET_MILLIS);
		try (Stream<Path> files = Files.walk(_root)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(f -> {
				f.setReadable(true);
				f.delete();
			});
		}
		super.tearDown();
	}

	private File album(String name) throws IOException {
		File album = new File(_root.toFile(), "2020/" + name);
		assertTrue(album.mkdirs());
		File photo = new File(album, "a.jpg");
		Files.write(photo.toPath(), new byte[] { 1, 2, 3 });
		return album;
	}

	public void testFixingOneFolderDoesNotHideAnotherThatStillFails() throws Exception {
		File stuck = album("Stuck");
		File fixed = album("Fixed");
		File stuckPhoto = new File(stuck, "a.jpg");
		File fixedPhoto = new File(fixed, "a.jpg");
		assertTrue(stuckPhoto.setReadable(false));
		if (stuckPhoto.canRead()) {
			return; // root
		}
		assertTrue(fixedPhoto.setReadable(false));

		Clock clock = new Clock();
		FolderPipeline pipeline = new FolderPipeline(_root, "probe");
		pipeline.setClock(clock);
		pipeline.setRetryMillis(10_000, 1_000_000);
		pipeline.addStep(new Reading());
		try {
			// "Stuck" fails twice (back-off 20 s), "Fixed" once (back-off 10 s), and last.
			pipeline.process(stuck);
			clock._now += 10_000;
			pipeline.process(stuck);
			pipeline.process(fixed);
			assertEquals("2020/Fixed", pipeline.status().getFailureFolder());

			assertTrue(fixedPhoto.setReadable(true));
			clock._now += 10_000;
			// Only "Fixed" is due; "Stuck" is still waiting and still broken.
			pipeline.process(fixed);

			FolderPipeline.Status status = pipeline.status();
			assertEquals("A folder that still fails must not read as 'nothing failed'.", "2020/Stuck",
				status.getFailureFolder());
		} finally {
			pipeline.shutdown();
		}
	}
}
