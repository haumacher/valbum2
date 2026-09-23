/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.cache.ImageData;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.GeoLocation;
import java.awt.Color;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Whether two files hold the same recording, see {@link SameRecording} and issue #167.
 */
@SuppressWarnings("javadoc")
public class TestSameRecording extends TestCase {

	static final File VIDEO = new File("src/test/fixtures/test-album/2005-08-24 Blumen und Fliegen/MVI_0450.mp4");

	private Path _dir;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_dir = Files.createTempDirectory("valbum-same");
	}

	@Override
	protected void tearDown() throws Exception {
		try (Stream<Path> files = Files.walk(_dir)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		super.tearDown();
	}

	/** The fixture is what issue #166 found: the position gone, and nothing else. */
	public void testTheFixtureIsARedaction() throws Exception {
		byte[] picture = Redacted.picture(Color.RED, false);
		File original = file("original.jpg");
		File redacted = file("redacted.jpg");
		Redacted.writeOriginal(original, picture);
		Redacted.writeRedacted(redacted, picture);

		GeoLocation position = ImageData.analyze(AlbumInfo.create(), original).getLocation();
		assertNotNull(position);
		assertEquals(Redacted.LATITUDE, position.getLatitude(), 1e-6);
		assertEquals(Redacted.LONGITUDE, position.getLongitude(), 1e-6);
		assertNull("A redacted copy says nowhere.", ImageData.analyze(AlbumInfo.create(), redacted).getLocation());
		assertEquals("The camera survives the redaction.",
			ImageData.analyze(AlbumInfo.create(), original).getCamera(),
			ImageData.analyze(AlbumInfo.create(), redacted).getCamera());
		assertFalse(Arrays.equals(Files.readAllBytes(original.toPath()), Files.readAllBytes(redacted.toPath())));
	}

	/** An APP1 that differs, and the scan that is the same: one recording. */
	public void testDifferentHeadersSameScan() throws Exception {
		byte[] picture = Redacted.picture(Color.RED, false);
		Redacted.writeOriginal(file("a.jpg"), picture);
		Redacted.writeRedacted(file("b.jpg"), picture);
		assertNull(SameRecording.difference(file("a.jpg"), file("b.jpg")));
	}

	/** A repainted picture is another photograph, whatever its headers say. */
	public void testARepaintedPictureDiffers() throws Exception {
		Redacted.writeOriginal(file("a.jpg"), Redacted.picture(Color.RED, false));
		Redacted.writeRedacted(file("b.jpg"), Redacted.picture(new Color(201, 40, 40), false));
		String difference = SameRecording.difference(file("a.jpg"), file("b.jpg"));
		assertNotNull(difference);
		assertTrue(difference, difference.contains("pixels differ"));
	}

	/** One changed byte of the scan data is enough. */
	public void testOneChangedScanByteDiffers() throws Exception {
		byte[] picture = Redacted.picture(Color.RED, false);
		int[] scan = SameRecording.scan(picture);
		assertNotNull(scan);
		byte[] changed = picture.clone();
		int at = scan[1] - 10;
		changed[at] = (byte) (changed[at] == 0x12 ? 0x13 : 0x12);
		Redacted.writeOriginal(file("a.jpg"), picture);
		Redacted.writeRedacted(file("b.jpg"), changed);
		assertNotNull(SameRecording.difference(file("a.jpg"), file("b.jpg")));
	}

	/** The same scan taken at another time is not the same recording. */
	public void testAnotherRecordingTimeDiffers() throws Exception {
		byte[] picture = Redacted.picture(Color.RED, false);
		Redacted.writeOriginalTakenAt(file("a.jpg"), picture, "2024:05:01 10:00:01");
		Redacted.writeRedacted(file("b.jpg"), picture);
		String difference = SameRecording.difference(file("a.jpg"), file("b.jpg"));
		assertNotNull(difference);
		assertTrue(difference, difference.contains("recording times differ"));
	}

	/** Where only one of them states a recording time, nothing is compared. */
	public void testAMissingRecordingTimeIsNoDifference() throws Exception {
		byte[] picture = Redacted.picture(Color.RED, false);
		Files.write(file("a.jpg").toPath(), picture);
		Redacted.writeRedacted(file("b.jpg"), picture);
		assertNull(SameRecording.difference(file("a.jpg"), file("b.jpg")));
	}

	/** What stands behind the end of the image is not the picture. */
	public void testATrailerIsNotThePicture() throws Exception {
		byte[] picture = Redacted.picture(Color.RED, false);
		Redacted.writeOriginal(file("a.jpg"), picture);
		byte[] trailer = Arrays.copyOf(picture, picture.length + 5);
		System.arraycopy(new byte[] { 1, 2, (byte) 0xFF, (byte) 0xD9, 3 }, 0, trailer, picture.length, 5);
		Redacted.writeRedacted(file("b.jpg"), trailer);
		assertNull(SameRecording.difference(file("a.jpg"), file("b.jpg")));
	}

	/** The scan runs from the first start of scan to the end of the image, a progressive one too. */
	public void testTheScanOfAProgressiveJpeg() throws Exception {
		for (boolean progressive : new boolean[] { false, true }) {
			byte[] picture = Redacted.picture(Color.RED, progressive);
			int[] scan = SameRecording.scan(picture);
			assertNotNull(scan);
			assertEquals(0xFF, picture[scan[0]] & 0xFF);
			assertEquals(0xDA, picture[scan[0] + 1] & 0xFF);
			assertEquals("The end of the image ends the scan.", picture.length, scan[1]);
			int scans = 0;
			for (int i = 0; i + 1 < picture.length; i++) {
				if ((picture[i] & 0xFF) == 0xFF && (picture[i + 1] & 0xFF) == 0xDA) {
					scans++;
				}
			}
			assertTrue("Several scans in a progressive JPEG.", progressive ? scans > 1 : scans == 1);
		}
		assertNull("A truncated JPEG has no complete scan.",
			SameRecording.scan(Arrays.copyOf(Redacted.picture(Color.RED, false), 200)));
	}

	/** A video is compared by its media data, whatever else its container holds. */
	public void testAVideoWithTheSameMediaData() throws Exception {
		File a = file("a.mp4");
		File b = file("b.mp4");
		Files.copy(VIDEO.toPath(), a.toPath());
		Files.copy(VIDEO.toPath(), b.toPath());
		appendFreeBox(b, "redacted");
		assertNull(SameRecording.difference(a, b));

		flipMediaByte(b);
		String difference = SameRecording.difference(a, b);
		assertNotNull(difference);
		assertTrue(difference, difference.contains("recordings differ"));
	}

	/** A PNG, or two files of different kinds, cannot be compared. */
	public void testWhatCannotBeCompared() throws Exception {
		File png = file("a.png");
		javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB),
			"png", png);
		assertTrue(SameRecording.difference(png, png).startsWith("cannot compare"));

		Redacted.writeOriginal(file("a.jpg"), Redacted.picture(Color.RED, false));
		File video = file("a.mp4");
		Files.copy(VIDEO.toPath(), video.toPath());
		assertTrue(SameRecording.difference(file("a.jpg"), video).startsWith("cannot compare"));
	}

	private File file(String name) {
		return _dir.resolve(name).toFile();
	}

	/** Appends a top-level <code>free</code> box: the container differs, its media data not. */
	static void appendFreeBox(File file, String contents) throws Exception {
		byte[] payload = contents.getBytes(StandardCharsets.US_ASCII);
		byte[] box = new byte[8 + payload.length];
		int size = box.length;
		box[0] = (byte) (size >> 24);
		box[1] = (byte) (size >> 16);
		box[2] = (byte) (size >> 8);
		box[3] = (byte) size;
		System.arraycopy("free".getBytes(StandardCharsets.US_ASCII), 0, box, 4, 4);
		System.arraycopy(payload, 0, box, 8, payload.length);
		Files.write(file.toPath(), box, java.nio.file.StandardOpenOption.APPEND);
	}

	/** Changes one byte in the middle of the first <code>mdat</code> payload. */
	static void flipMediaByte(File file) throws Exception {
		try (RandomAccessFile in = new RandomAccessFile(file, "rw")) {
			long[] range = SameRecording.mdat(in).get(0);
			long at = range[0] + range[1] / 2;
			in.seek(at);
			int value = in.read();
			in.seek(at);
			in.write(value ^ 0x01);
		}
	}
}
