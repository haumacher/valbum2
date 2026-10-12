/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.mov.media.QuickTimeVideoDirectory;
import com.drew.metadata.mp4.media.Mp4VideoDirectory;
import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.cache.ImageData;
import de.haumacher.imageServer.cache.VideoProbe;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ImageKind;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Resource;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * Test case for issue #255: a video over 4 GiB, which metadata-extractor cannot read, has a poster
 * turned as its tile is, and is found as a copy of the same recording.
 *
 * <p>
 * The videos are the fixtures of issue #189 grown past 4 GiB by a hole, see
 * {@link LargeMp4#write(File, File)}; on a file system that cannot leave a hole, the tests that
 * need one say so and pass.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestLargeVideo extends ShareTestCase {

	private static final File FIXTURES = new File("src/test/fixtures/video");

	private static final String ALBUM = "2024/Videos";

	private static final String PATH = "/" + ALBUM + "/";

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		Files.createDirectories(_base.resolve(ALBUM));
	}

	/**
	 * A portrait movie over 4 GiB: its tile is 64 &times; 96 (FFmpeg read it), and so is its
	 * poster, turned as the small one is.
	 */
	public void testAPortraitMovieOver4GiBHasATurnedPoster() throws Exception {
		File big = _base.resolve(ALBUM).resolve("big.mov").toFile();
		if (!large(new File(FIXTURES, "rotated.mov"), big)) {
			return;
		}
		assertNoVideoTrackRead(big);
		assertEquals("As the small one says it.", ImageData.rotation(ImageData.readMetadata(new File(FIXTURES,
			"rotated.mov"))), ImageData.videoRotation(big));

		ImagePart image = image(album(), "big.mov");
		assertEquals(ImageKind.QUICKTIME, image.getKind());
		assertEquals(64, image.getWidth());
		assertEquals(96, image.getHeight());

		BufferedImage poster = picture(get(PATH + "big.mov", "tn", SharingFixture.ALICE));
		assertEquals("As its tile: " + poster.getWidth() + "x" + poster.getHeight(), 64, poster.getWidth());
		assertEquals(96, poster.getHeight());
		// Exactly as the small one, see TestVideoContainers#testAPortraitMovieIsListedAndPostedUpright().
		assertColor("top left", poster, 8, 8, 0x00FF00);
		assertColor("top right", poster, 56, 8, 0xFFFF00);
		assertColor("bottom left", poster, 8, 88, 0xFF0000);
		assertColor("bottom right", poster, 56, 88, 0x0000FF);
	}

	/** A landscape mp4 over 4 GiB is not turned at all. */
	public void testALandscapeVideoOver4GiBIsNotTurned() throws Exception {
		File big = _base.resolve(ALBUM).resolve("big.mp4").toFile();
		if (!LargeMp4.write(big)) {
			System.err.println("Skipped " + getName() + ": the file system cannot write a sparse file.");
			return;
		}
		assertNoVideoTrackRead(big);
		assertEquals(0, ImageData.videoRotation(big));
		BufferedImage poster = picture(get(PATH + "big.mp4", "tn", SharingFixture.ALICE));
		assertEquals(160, poster.getWidth());
		assertEquals(120, poster.getHeight());
	}

	/**
	 * FFmpeg keys a video exactly as metadata-extractor does: the duration of its <code>mvhd</code>
	 * to the millisecond and the size of its stored raster, so that a copy read one way is found by
	 * an original read the other.
	 */
	public void testFfmpegKeysAVideoAsMetadataExtractorDoes() throws Exception {
		for (File video : List.of(TestSameRecording.VIDEO, new File(FIXTURES, "rotated.mov"),
			new File(FIXTURES, "clip.mov"), new File(FIXTURES, "clip.m4v"))) {
			RecordingIndex.Head read = RecordingIndex.head(video.toPath(), List.of(), new RecordingIndex.Reads());
			assertNull(video + ": " + read.getProblem(), read.getProblem());
			RecordingIndex.Head probed = RecordingIndex.probedHead(video.toPath());
			assertEquals(video.getName(), read.getKeys(), probed.getKeys());
		}
	}

	/**
	 * A video over 4 GiB has its key, the very key of the small one it was grown from (the duration
	 * and the raster are the same), where metadata-extractor reads none.
	 */
	public void testAVideoOver4GiBIsKeyed() throws Exception {
		File small = new File(FIXTURES, "rotated.mov");
		File big = _base.resolve(ALBUM).resolve("big.mov").toFile();
		if (!large(small, big)) {
			return;
		}
		assertNoVideoTrackRead(big);

		RecordingIndex.Head head = RecordingIndex.head(big.toPath(), List.of(), new RecordingIndex.Reads());
		assertNull(head.getProblem(), head.getProblem());
		Set<RecordingIndex.Key> expected =
			RecordingIndex.head(small.toPath(), List.of(), new RecordingIndex.Reads()).getKeys();
		assertEquals(expected, head.getKeys());
		RecordingIndex.Key key = head.getKeys().iterator().next();
		assertTrue(key.video());
		assertEquals("The stored raster, before its turn.", 96, key.width());
		assertEquals(64, key.height());
		assertTrue(key.value() > 0);
	}

	/**
	 * The comparison of two copies reads the media data of a video over 4 GiB by its 64-bit
	 * <code>largesize</code>, without metadata-extractor; a copy whose header differs is the same
	 * recording.
	 */
	public void testTheMediaDataOfAVideoOver4GiBIsFound() throws Exception {
		File big = _base.resolve(ALBUM).resolve("big.mp4").toFile();
		if (!LargeMp4.write(big)) {
			System.err.println("Skipped " + getName() + ": the file system cannot write a sparse file.");
			return;
		}
		try (RandomAccessFile in = new RandomAccessFile(big, "r")) {
			List<long[]> mdat = SameRecording.mdat(in);
			assertNotNull("The box structure is read.", mdat);
			assertEquals(1, mdat.size());
			assertEquals(LargeMp4.MDAT_SIZE - 16, mdat.get(0)[1]);
		}
		assertEquals(SameRecording.Kind.ISO_MEDIA, SameRecording.kindOf(big));
	}

	/**
	 * The question of issue #167 for a video over 4 GiB: the original is found as the copy in the
	 * library whose header a redaction changed, and would take its place.
	 */
	public void testTheOriginalOfAVideoOver4GiBFindsItsCopy() throws Exception {
		Path library = Files.createDirectories(_base.resolve("library/2025/Mission"));
		Path incoming = Files.createDirectories(_base.resolve("incoming"));
		File copy = library.resolve("20250903_185939.mp4").toFile();
		if (!LargeMp4.write(copy)) {
			System.err.println("Skipped " + getName() + ": the file system cannot write a sparse file.");
			return;
		}
		TestSameRecording.appendFreeBox(copy, "redacted");
		File original = incoming.resolve("20250903_185939.mp4").toFile();
		sparseCopy(copy, original, copy.length() - 16);
		if (!sparse(original)) {
			System.err.println("Skipped " + getName() + ": the copy is not sparse.");
			return;
		}

		ReplaceOriginals.Report report = ReplaceOriginals.run(_base.resolve("library"), incoming, null, true,
			java.time.LocalDateTime.of(2026, 10, 12, 20, 0, 0));
		assertEquals(report.getLines().toString(), 1, report.getLines().size());
		assertTrue(report.getLines().get(0), report.getLines().get(0).contains("2025/Mission/20250903_185939.mp4"));
		assertFalse(report.getLines().get(0), report.getLines().get(0).startsWith("skipped"));
	}

	// --- Helpers. ---

	/**
	 * Copies the first given number of bytes of a file {@link LargeMp4} wrote, leaving its hole a
	 * hole: only what lies within a MiB of either end holds anything.
	 */
	private static void sparseCopy(File from, File to, long length) throws Exception {
		int margin = 1 << 20;
		try (RandomAccessFile in = new RandomAccessFile(from, "r"); RandomAccessFile out = new RandomAccessFile(to, "rw")) {
			byte[] head = new byte[margin];
			in.readFully(head);
			out.write(head);
			byte[] tail = new byte[margin];
			in.seek(length - margin);
			in.readFully(tail);
			out.seek(length - margin);
			out.write(tail);
		}
	}

	/** Whether the given file occupies next to nothing on disk. */
	private static boolean sparse(File file) throws Exception {
		Process du = new ProcessBuilder("du", "-k", file.getAbsolutePath()).redirectErrorStream(true).start();
		String out = new String(du.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
		return du.waitFor() == 0 && Long.parseLong(out.split("\\s+")[0]) < 64 * 1024;
	}

	/** Writes the given small video grown past 4 GiB; <code>false</code> where the test cannot run. */
	private boolean large(File small, File big) throws Exception {
		if (!LargeMp4.write(small, big)) {
			System.err.println("Skipped " + getName() + ": the file system cannot write a sparse file.");
			return false;
		}
		assertTrue(big.length() > (1L << 32));
		return true;
	}

	/** metadata-extractor still cannot read the video's track, else the test tests nothing. */
	private static void assertNoVideoTrackRead(File video) {
		try {
			Metadata metadata = ImageData.readMetadata(video);
			assertNull("metadata-extractor reads it now: " + video,
				metadata.getFirstDirectoryOfType(Mp4VideoDirectory.class));
			assertNull("metadata-extractor reads it now: " + video,
				metadata.getFirstDirectoryOfType(QuickTimeVideoDirectory.class));
		} catch (ImageProcessingException | java.io.IOException ex) {
			// Not read at all: as good.
		}
		try {
			assertTrue("FFmpeg reads it.", VideoProbe.probe(video).getDurationMillis() > 0);
		} catch (java.io.IOException ex) {
			fail("FFmpeg cannot read '" + video + "': " + ex.getMessage());
		}
	}

	private AlbumInfo album() throws Exception {
		FakeResponse response = get(PATH, "json", SharingFixture.ALICE);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		return (AlbumInfo) Resource.readResource(reader(response.body()));
	}

	private static ImagePart image(AlbumInfo album, String name) {
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart && name.equals(((ImagePart) part).getName())) {
				return (ImagePart) part;
			}
		}
		fail("No image '" + name + "' in " + album);
		return null;
	}

	private static BufferedImage picture(FakeResponse response) throws Exception {
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		BufferedImage picture = ImageIO.read(new ByteArrayInputStream(response.bodyBytes()));
		assertNotNull("A JPEG", picture);
		return picture;
	}

	/** Each channel within a generous tolerance, as in TestVideoContainers. */
	private static void assertColor(String what, BufferedImage picture, int x, int y, int expected) {
		int actual = picture.getRGB(x, y) & 0xFFFFFF;
		for (int shift = 0; shift <= 16; shift += 8) {
			int a = (actual >> shift) & 0xFF;
			int e = (expected >> shift) & 0xFF;
			assertTrue(what + ": expected " + Integer.toHexString(expected) + ", got " + Integer.toHexString(actual),
				Math.abs(a - e) < 80);
		}
	}
}
