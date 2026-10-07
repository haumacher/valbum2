/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Test case for requests going first with videos, see issue #236: a request for a rendition that is
 * not there preempts the transcode of the background, and is preempted by nothing.
 *
 * <p>
 * The transcoder is a shell script of the test's own (the seam {@link VideoRenditions#setCommands}):
 * it writes some output at once and then takes as long as the test says, so that "running" and
 * "killed half-way" are observable without a minute of real FFmpeg.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestVideoPreempt extends TestCase {

	private static final long TIMEOUT = 60_000;

	private Path _dir;

	private VideoRenditions _videos;

	/** How many seconds the fake transcode of a video takes, by file name; 0 if not named. */
	private final Map<String, Integer> _seconds = new ConcurrentHashMap<>();

	/** The videos whose transcode started, in order. */
	private final List<String> _started = new CopyOnWriteArrayList<>();

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_dir = Files.createTempDirectory("valbum-video-preempt");
		_videos = new VideoRenditions();
		_videos.setCommands((file, target, kind) -> {
			_started.add(file.getName());
			int seconds = _seconds.getOrDefault(file.getName(), 0);
			// Some output at once, the rest after the given time: a half-made file while it runs.
			return Arrays.asList("sh", "-c", "printf partial > \"$0\"; sleep " + seconds + "; printf ' made' >> \"$0\"",
				target.getAbsolutePath());
		});
	}

	@Override
	protected void tearDown() throws Exception {
		_videos.shutdown();
		try (Stream<Path> files = Files.walk(_dir)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		super.tearDown();
	}

	private boolean available() {
		if (VideoRenditions.unavailability() != null) {
			System.out.println("Skipping " + getName() + ": " + VideoRenditions.unavailability());
			return false;
		}
		return true;
	}

	private File video(String name) throws Exception {
		File file = new File(_dir.toFile(), name);
		Files.write(file.toPath(), name.getBytes(StandardCharsets.UTF_8));
		return file;
	}

	private static File tmpOf(File video) {
		File rendition = VideoRenditions.file(video, VideoRenditions.Kind.PLAYBACK);
		return new File(rendition.getParentFile(), rendition.getName() + PreviewCache.TMP_SUFFIX);
	}

	private static void await(File file, boolean exists) throws Exception {
		long end = System.currentTimeMillis() + TIMEOUT;
		while (file.exists() != exists && System.currentTimeMillis() < end) {
			Thread.sleep(10);
		}
		assertEquals(file.getName(), exists, file.exists());
	}

	public void testARequestPreemptsTheBackgroundAndTheBackgroundComesBack() throws Exception {
		if (!available()) {
			return;
		}
		File longOne = video("long.mp4");
		File asked = video("asked.mp4");
		_seconds.put("long.mp4", 60);
		_seconds.put("asked.mp4", 1);

		AtomicReference<VideoRenditions.Rendition> background = new AtomicReference<>();
		Thread pipeline = new Thread(() -> {
			try {
				background.set(_videos.make(longOne, VideoRenditions.Kind.PLAYBACK));
			} catch (InterruptedException ex) {
				// Ends the test thread.
			}
		});
		pipeline.start();
		await(tmpOf(longOne), true);

		long start = System.currentTimeMillis();
		assertEquals(VideoRenditions.State.PENDING, _videos.lookup(asked, VideoRenditions.Kind.PLAYBACK).getState());
		pipeline.join(TIMEOUT);
		assertFalse(pipeline.isAlive());
		assertEquals("Put aside, not failed.", VideoRenditions.State.DEFERRED, background.get().getState());
		assertNull("Nothing remembered as failed.", _videos.failure(VideoRenditions.file(longOne, VideoRenditions.Kind.PLAYBACK)));
		assertFalse("The half-made output is gone.", tmpOf(longOne).exists());
		assertFalse("Nothing that counts as the rendition.",
			VideoRenditions.file(longOne, VideoRenditions.Kind.PLAYBACK).exists());

		assertTrue(_videos.awaitQueue(TIMEOUT));
		long took = System.currentTimeMillis() - start;
		assertTrue("The request did not wait for the background's minute: " + took + " ms", took < 20_000);
		File made = VideoRenditions.file(asked, VideoRenditions.Kind.PLAYBACK);
		assertEquals("partial made", new String(Files.readAllBytes(made.toPath()), StandardCharsets.UTF_8));

		// The unit comes back and is made from the start.
		_seconds.put("long.mp4", 0);
		assertEquals(VideoRenditions.State.READY, _videos.make(longOne, VideoRenditions.Kind.PLAYBACK).getState());
		assertEquals("partial made", new String(Files.readAllBytes(
			VideoRenditions.file(longOne, VideoRenditions.Kind.PLAYBACK).toPath()), StandardCharsets.UTF_8));
		assertEquals(Arrays.asList("long.mp4", "asked.mp4", "long.mp4"), _started);
	}

	public void testARequestIsNeverPreempted() throws Exception {
		if (!available()) {
			return;
		}
		File first = video("first.mp4");
		File second = video("second.mp4");
		File later = video("later.mp4");
		_seconds.put("first.mp4", 2);

		assertEquals(VideoRenditions.State.PENDING, _videos.lookup(first, VideoRenditions.Kind.PLAYBACK).getState());
		await(tmpOf(first), true);

		// The background does not even start while a request's transcode is there.
		assertEquals(VideoRenditions.State.DEFERRED, _videos.make(later, VideoRenditions.Kind.PLAYBACK).getState());
		// Another request queues behind it.
		assertEquals(VideoRenditions.State.PENDING, _videos.lookup(second, VideoRenditions.Kind.PLAYBACK).getState());

		assertTrue(_videos.awaitQueue(TIMEOUT));
		for (File video : new File[] { first, second }) {
			assertEquals("Made whole: " + video.getName(), "partial made", new String(Files.readAllBytes(
				VideoRenditions.file(video, VideoRenditions.Kind.PLAYBACK).toPath()), StandardCharsets.UTF_8));
		}
		assertEquals(Arrays.asList("first.mp4", "second.mp4"), _started);
		assertEquals(VideoRenditions.State.READY, _videos.make(later, VideoRenditions.Kind.PLAYBACK).getState());
	}

	public void testARequestForTheRenditionTheBackgroundMakesWaitsForIt() throws Exception {
		if (!available()) {
			return;
		}
		File video = video("same.mp4");
		_seconds.put("same.mp4", 2);
		AtomicReference<VideoRenditions.Rendition> background = new AtomicReference<>();
		Thread pipeline = new Thread(() -> {
			try {
				background.set(_videos.make(video, VideoRenditions.Kind.PLAYBACK));
			} catch (InterruptedException ex) {
				// Ends the test thread.
			}
		});
		pipeline.start();
		await(tmpOf(video), true);

		assertEquals(VideoRenditions.State.PENDING, _videos.lookup(video, VideoRenditions.Kind.PLAYBACK).getState());
		pipeline.join(TIMEOUT);
		assertEquals("Not killed: it is what the request wants.", VideoRenditions.State.READY, background.get().getState());
		assertTrue(_videos.awaitQueue(TIMEOUT));
		assertEquals(VideoRenditions.State.READY, _videos.lookup(video, VideoRenditions.Kind.PLAYBACK).getState());
		assertEquals("Made once.", Arrays.asList("same.mp4"), _started);
	}
}
