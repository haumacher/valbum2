/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.cache.AnalysisRecord;
import de.haumacher.imageServer.shared.model.ImagePart;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Test case for the freshness of a video rendition, see issues #235, #236 and #256: a rendition
 * made of a half-copied video is made again after <code>cp -p</code> restored the original's older
 * stamp, because the background finds the original changed against its record and drops what was
 * made of it; nothing is recorded beside the rendition.
 */
@SuppressWarnings("javadoc")
public class TestVideoSource extends TestCase {

	private static final long TIMEOUT = 120_000;

	private static final File FIXTURE =
		new File("src/test/fixtures/test-album/2005-08-24 Blumen und Fliegen/MVI_0450.mp4");

	private Path _dir;

	private VideoRenditions _videos;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_dir = Files.createTempDirectory("valbum-video-source");
		_videos = new VideoRenditions();
	}

	@Override
	protected void tearDown() throws Exception {
		_videos.shutdown();
		try (Stream<Path> files = Files.walk(_dir)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		super.tearDown();
	}

	public void testARenditionOfAHalfCopiedVideoIsMadeAgainAfterCpP() throws Exception {
		if (VideoRenditions.unavailability() != null) {
			System.out.println("Skipping " + getName() + ": " + VideoRenditions.unavailability());
			return;
		}
		File video = new File(_dir.toFile(), "clip.mp4");
		// The copy is still going on: what is there is another, smaller video.
		test.de.haumacher.valbum.GenerateTestAlbum.recordTinyVideo(video);
		long copying = video.lastModified();
		record(video);
		for (VideoRenditions.Kind kind : VideoRenditions.Kind.values()) {
			assertEquals(VideoRenditions.State.READY, _videos.make(video, kind).getState());
			assertFalse("No record of the original, see issue #256.",
				new File(VideoRenditions.file(video, kind).getPath() + PreviewCache.SOURCE_SUFFIX).exists());
		}
		File playback = VideoRenditions.file(video, VideoRenditions.Kind.PLAYBACK);

		// The copy ends, and cp -p sets the old stamp of the source.
		Files.copy(FIXTURE.toPath(), video.toPath(), StandardCopyOption.REPLACE_EXISTING);
		assertTrue(video.setLastModified(copying - 3_600_000));
		assertTrue("Older than the renditions: the old rule would keep them.", video.lastModified() < playback.lastModified());
		assertEquals("A request alone does not look at the original.", VideoRenditions.State.READY,
			_videos.lookup(video, VideoRenditions.Kind.PLAYBACK).getState());

		// The background's analysis finds the change against its record.
		record(video);
		for (VideoRenditions.Kind kind : VideoRenditions.Kind.values()) {
			assertEquals("Stale: " + kind, VideoRenditions.State.PENDING, _videos.lookup(video, kind).getState());
		}
		assertTrue(_videos.awaitQueue(TIMEOUT));
		for (VideoRenditions.Kind kind : VideoRenditions.Kind.values()) {
			assertEquals("Made again: " + kind, VideoRenditions.State.READY, _videos.lookup(video, kind).getState());
		}
	}

	public void testARenditionOfAnEarlierBuildIsKept() throws Exception {
		File video = new File(_dir.toFile(), "old.mp4");
		Files.copy(FIXTURE.toPath(), video.toPath());
		File rendition = VideoRenditions.file(video, VideoRenditions.Kind.TEASER);
		assertTrue(rendition.getParentFile().mkdirs());
		Files.write(rendition.toPath(), new byte[] { 1, 2, 3 });
		assertTrue(video.setLastModified(rendition.lastModified() + 60_000));

		assertTrue("There: kept, nothing transcoded for an upgrade, whatever the original's stamp.",
			VideoRenditions.upToDate(video, rendition));
		record(video);
		assertTrue("The first analysis takes it as current.", rendition.isFile());
	}

	/** What the background's analysis does for the folder of the given video. */
	private static void record(File video) throws Exception {
		AnalysisRecord.update(video.getParentFile(), file -> ImagePart.create().setName(file.getName()), true);
	}

	public void testAnUnchangedVideoKeepsItsRendition() throws Exception {
		if (VideoRenditions.unavailability() != null) {
			return;
		}
		File video = new File(_dir.toFile(), "same.mp4");
		test.de.haumacher.valbum.GenerateTestAlbum.recordTinyVideo(video);
		assertEquals(VideoRenditions.State.READY, _videos.make(video, VideoRenditions.Kind.TEASER).getState());
		File rendition = VideoRenditions.file(video, VideoRenditions.Kind.TEASER);
		long stamp = rendition.lastModified();
		assertEquals(VideoRenditions.State.READY, _videos.lookup(video, VideoRenditions.Kind.TEASER).getState());
		assertEquals(VideoRenditions.State.READY, _videos.make(video, VideoRenditions.Kind.TEASER).getState());
		assertEquals("Not made again.", stamp, rendition.lastModified());
	}
}
