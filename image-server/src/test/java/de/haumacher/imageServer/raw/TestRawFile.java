/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.raw;

import de.haumacher.imageServer.RawFixtures;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import junit.framework.TestCase;

/**
 * Test case for {@link RawFile}: which JPEG of a raw file is its preview, see issue #191.
 */
@SuppressWarnings("javadoc")
public class TestRawFile extends TestCase {

	private Path _dir;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_dir = Files.createTempDirectory("valbum-raw");
		RawFile.forget();
	}

	@Override
	protected void tearDown() throws Exception {
		try (java.util.stream.Stream<Path> files = Files.list(_dir)) {
			files.forEach(p -> p.toFile().delete());
		}
		Files.delete(_dir);
		super.tearDown();
	}

	public void testTheLargestPreviewOfADngWinsAndTheSensorStreamNever() throws Exception {
		EmbeddedPreview jpeg = RawFile.embedded(write("a.dng", RawFixtures.dng(128, 192, 6, 0)));
		// Stored on its side: the raster of the preview, before the orientation.
		assertEquals(192, jpeg.getWidth());
		assertEquals(128, jpeg.getHeight());
	}

	public void testTheFullSizeJpegOfACr2Wins() throws Exception {
		EmbeddedPreview jpeg = RawFile.embedded(write("a.CR2", RawFixtures.cr2(300, 200, 1)));
		assertEquals(300, jpeg.getWidth());
		assertEquals(200, jpeg.getHeight());
	}

	public void testTheTrackJpegOfACr3BeatsPrvwAndThmb() throws Exception {
		EmbeddedPreview jpeg = RawFile.embedded(write("a.cr3", RawFixtures.cr3(240, 160)));
		assertEquals(240, jpeg.getWidth());
		assertEquals(160, jpeg.getHeight());
	}

	public void testARafNamesItsJpegInItsHeader() throws Exception {
		EmbeddedPreview jpeg = RawFile.embedded(write("a.raf", RawFixtures.raf(160, 120, 1)));
		assertEquals(160, jpeg.getWidth());
		assertEquals(120, jpeg.getHeight());
	}

	public void testAPreviewNoStructureNamesIsFoundByTheScan() throws Exception {
		// An ORF keeps its large preview in the maker note: here a TIFF naming only a thumbnail, with
		// the large JPEG behind it where no tag points.
		byte[] tiff = RawFixtures.cr2(32, 24, 1);
		byte[] hidden = RawFixtures.stored(400, 300, 1);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(tiff);
		out.write(RawFixtures.sensorStream());
		out.write(hidden);
		out.write(RawFixtures.sensorStream());
		EmbeddedPreview jpeg = RawFile.embedded(write("a.orf", out.toByteArray()));
		assertEquals(400, jpeg.getWidth());
		assertEquals(300, jpeg.getHeight());
		assertEquals(tiff.length + RawFixtures.sensorStream().length, jpeg.getOffset());
		assertEquals(hidden.length, jpeg.getLength());
	}

	public void testARawWithoutAJpegHasNone() throws Exception {
		File file = write("a.dng", RawFixtures.dngWithoutPreview());
		assertNull(RawFile.embedded(file));
		try {
			RawFile.require(file);
			fail("No preview.");
		} catch (NoEmbeddedPreviewException ex) {
			assertEquals(RawFile.noPreview("a.dng"), ex.getMessage());
		}
	}

	public void testGarbageIsNoRawAndNoCrash() throws Exception {
		assertNull(RawFile.embedded(write("a.nef", "not a raw at all".getBytes())));
		assertNull(RawFile.embedded(write("b.cr3", new byte[] { 0, 0, 0, 8, 'f', 't', 'y', 'p', 0, 0, 0, 0 })));
		assertNull(RawFile.embedded(write("c.raf", "FUJIFILMCCD-RAW and then nothing".getBytes())));
	}

	public void testTheExtensionsAndContentTypes() {
		for (String name : new String[] { "a.dng", "a.CR2", "a.cr3", "a.NEF", "a.arw", "a.orf", "a.Rw2", "a.raf" }) {
			assertTrue(name, RawFile.isRawName(name));
			assertTrue(name, RawFile.contentType(name).startsWith("image/x-"));
		}
		assertFalse(RawFile.isRawName("a.jpg"));
		assertFalse(RawFile.isRawName("dng"));
		assertEquals("image/x-adobe-dng", RawFile.contentType("IMG.DNG"));
	}

	private File write(String name, byte[] contents) throws Exception {
		Path file = _dir.resolve(name);
		Files.write(file, contents);
		return file.toFile();
	}
}
