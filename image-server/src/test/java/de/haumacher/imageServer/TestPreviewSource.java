/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Test case for the record of the original a preview was made from, see issue #235.
 *
 * <p>
 * A preview made while the original was still being copied must not stay once
 * <code>cp -p</code> sets the original's old modification stamp at the end of the copy.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestPreviewSource extends TestCase {

	private Path _dir;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_dir = Files.createTempDirectory("valbum-preview-source");
	}

	@Override
	protected void tearDown() throws Exception {
		try (Stream<Path> files = Files.walk(_dir)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		super.tearDown();
	}

	public void testAnOriginalReplacedWithAnOlderStampIsMadeAgain() throws Exception {
		File file = write("x.jpg", Color.RED, 1600);
		assertColor(file, Color.RED);
		long before = file.lastModified();

		// The rest of the copy arrives, and cp -p sets the old stamp of the source.
		write("x.jpg", Color.BLUE, 1600);
		assertTrue(file.setLastModified(before - 3_600_000));

		assertColor(file, Color.BLUE);
	}

	public void testAnUnchangedOriginalKeepsItsPreview() throws Exception {
		File file = write("same.jpg", Color.RED, 1600);
		File preview = PreviewCache.createPreview(file);
		assertTrue(preview.setLastModified(preview.lastModified() - 10_000));
		long stamp = preview.lastModified();

		assertEquals(preview, PreviewCache.createPreview(file));
		assertEquals("Not made again.", stamp, preview.lastModified());
	}

	public void testAPreviewWithoutARecordIsJudgedByTheOldRule() throws Exception {
		File file = write("old.jpg", Color.RED, 1600);
		File preview = PreviewCache.createPreview(file);
		File record = PreviewCache.recordOf(preview);
		assertTrue("The record lies beside the preview.", record.isFile());

		// A preview made by an earlier build: no record, newer than its original.
		assertTrue(record.delete());
		assertTrue(file.setLastModified(preview.lastModified() - 60_000));
		long stamp = preview.lastModified();
		PreviewCache.createPreview(file);
		assertEquals("Accepted under the old rule: not made again.", stamp, preview.lastModified());
		assertFalse(record.exists());

		// The original newer than such a preview: made again, now with a record.
		write("old.jpg", Color.GREEN, 1600);
		assertTrue(file.setLastModified(stamp + 60_000));
		assertColor(file, Color.GREEN);
		assertTrue(record.isFile());
	}

	public void testAnUnreadableRecordMakesThePreviewAgain() throws Exception {
		File file = write("broken.jpg", Color.RED, 1600);
		File preview = PreviewCache.createPreview(file);
		Files.write(PreviewCache.recordOf(preview).toPath(), "garbage".getBytes(StandardCharsets.UTF_8));
		write("broken.jpg", Color.BLUE, 1600);
		assertTrue(file.setLastModified(preview.lastModified() - 60_000));

		assertColor(file, Color.BLUE);
	}

	public void testTheRecordReadsBackWhatWasWritten() throws Exception {
		File record = new File(_dir.toFile(), "preview-r.jpg" + PreviewCache.SOURCE_SUFFIX);
		Files.write(record.toPath(), PreviewCache.record(123456L, 1757000000123L).getBytes(StandardCharsets.UTF_8));
		long[] read = PreviewCache.readRecord(record);
		assertEquals(123456L, read[0]);
		assertEquals(1757000000123L, read[1]);

		Files.write(record.toPath(), PreviewCache.record(read[0], read[1]).getBytes(StandardCharsets.UTF_8));
		long[] again = PreviewCache.readRecord(record);
		assertEquals(read[0], again[0]);
		assertEquals(read[1], again[1]);
		assertNull(PreviewCache.readRecord(new File(_dir.toFile(), "missing")));
	}

	public void testARecordIsGeneratedAndGoesWithItsPreview() throws Exception {
		assertTrue(CacheRefresh.isGenerated("preview-a.jpg" + PreviewCache.SOURCE_SUFFIX));
		assertTrue(CacheRefresh.isGenerated("display-a.heic.jpg" + PreviewCache.SOURCE_SUFFIX));
		assertTrue(CacheRefresh.isGenerated("preview-a.jpg-c0123456789ab.jpg" + PreviewCache.SOURCE_SUFFIX));
		assertFalse("Only a record of something generated.", CacheRefresh.isGenerated("notes" + PreviewCache.SOURCE_SUFFIX));

		File file = write("gone.jpg", Color.RED, 1600);
		PreviewCache.createPreview(file);
		assertEquals("The refresh throws the record away with the preview.", 2,
			CacheRefresh.refresh(file.getParentFile()));
	}

	private File write(String name, Color color, int width) throws Exception {
		BufferedImage image = new BufferedImage(width, width * 3 / 4, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(color);
		g.fillRect(0, 0, image.getWidth(), image.getHeight());
		g.dispose();
		File file = new File(_dir.toFile(), name);
		assertTrue(ImageIO.write(image, "jpg", file));
		return file;
	}

	private static void assertColor(File original, Color expected) throws Exception {
		BufferedImage preview = ImageIO.read(PreviewCache.createPreview(original));
		Color c = new Color(preview.getRGB(preview.getWidth() / 2, preview.getHeight() / 2));
		assertTrue("Expected " + expected + " but got " + c,
			Math.abs(c.getRed() - expected.getRed()) < 40
				&& Math.abs(c.getGreen() - expected.getGreen()) < 40
				&& Math.abs(c.getBlue() - expected.getBlue()) < 40);
	}
}
