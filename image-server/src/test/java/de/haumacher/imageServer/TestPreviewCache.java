/*
 * Copyright (c) 2022 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Test case for {@link PreviewCache}.
 */
@SuppressWarnings("javadoc")
public class TestPreviewCache extends TestCase {

	/**
	 * The generated video fixture, see
	 * <code>test.de.haumacher.valbum.GenerateTestAlbum#recordTinyVideo(File)</code>.
	 */
	private static final File VIDEO_FIXTURE =
		new File("src/test/fixtures/test-album/2005-08-24 Blumen und Fliegen/MVI_0450.mp4");

	private static final File IMAGE_FIXTURE =
		new File("src/test/fixtures/test-album/2005-08-24 Blumen und Fliegen/IMG_0417.JPG");

	public void testUpdateDate() {
		assertTrue(PreviewCache.lastUpdate() > 0);
	}

	public void testVideoPreview() throws Exception {
		assertPreview(VIDEO_FIXTURE);
	}

	public void testImagePreview() throws Exception {
		assertPreview(IMAGE_FIXTURE);
	}

	/**
	 * Creates a preview for a copy of the given fixture in a temporary folder.
	 *
	 * <p>
	 * The copy keeps the preview cache out of the git-tracked fixture album.
	 * </p>
	 */
	private static void assertPreview(File fixture) throws Exception {
		assertTrue("Missing fixture: " + fixture.getAbsolutePath(), fixture.exists());

		Path dir = Files.createTempDirectory("valbum-preview-test");
		try {
			File copy = new File(dir.toFile(), fixture.getName());
			Files.copy(fixture.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING);

			File preview = PreviewCache.createPreview(copy);
			assertTrue("No preview created for " + fixture.getName() + ".", preview.exists());
			assertTrue("Empty preview created for " + fixture.getName() + ".", preview.length() > 0);
			assertEquals("The original must not be modified.", fixture.length(), copy.length());
		} finally {
			try (Stream<Path> files = Files.walk(dir)) {
				files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
	}

}
