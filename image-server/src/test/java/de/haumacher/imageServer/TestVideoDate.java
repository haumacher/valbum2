/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import static de.haumacher.imageServer.TestImageServletPut.request;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.cache.ImageData;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ImageKind;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;
import test.de.haumacher.valbum.GenerateTestAlbum;

/**
 * Test case for the date a video is sorted by, see issue #72.
 *
 * <p>
 * A video has no EXIF data, so it used to get its file modification time — the time it arrived on
 * the server for anything uploaded or moved — which sorted every video behind every photo. The
 * recording time is in the container instead.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestVideoDate extends TestCase {

	/** 2005-08-24T12:00:00Z, the recording time written into the generated videos. */
	private static final String RECORDED_AT = "2005-08-24T12:00:00Z";

	private static final long RECORDED_MILLIS = 1124884800000L;

	private Path _base;

	private ImageServlet _servlet;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-video-date");
	}

	@Override
	protected void tearDown() throws Exception {
		if (_servlet != null) {
			_servlet.destroy();
			_servlet = null;
		}
		if (_base != null) {
			try (Stream<Path> files = Files.walk(_base)) {
				files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
		super.tearDown();
	}

	/** The recording time in the container wins over the file's modification time. */
	public void testContainerCreationTime() throws Exception {
		File video = new File(_base.toFile(), "recorded.mp4");
		GenerateTestAlbum.recordTinyVideo(video, RECORDED_AT);
		// As if the file had arrived on the server years later.
		assertTrue(video.setLastModified(RECORDED_MILLIS + 5000L * 24 * 3600 * 1000));

		ImagePart part = analyze(video);
		assertEquals(ImageKind.VIDEO, part.getKind());
		assertEquals("The video must be dated by its container, not by its mtime.",
			RECORDED_MILLIS, part.getDate());
	}

	/**
	 * A container without a creation time still gets the modification time.
	 *
	 * <p>
	 * FFmpeg leaves the <code>mvhd</code> field at zero, which is the QuickTime epoch
	 * 1904-01-01 — a date, not a missing value, so the implausible-time bound is what catches it.
	 * </p>
	 */
	public void testWithoutContainerCreationTime() throws Exception {
		File video = new File(_base.toFile(), "undated.mp4");
		GenerateTestAlbum.recordTinyVideo(video);
		long mtime = RECORDED_MILLIS + 86400000L;
		assertTrue(video.setLastModified(mtime));

		ImagePart part = analyze(video);
		assertEquals(ImageKind.VIDEO, part.getKind());
		assertEquals("Without a container time the mtime must stand.", mtime, part.getDate());
		assertTrue("The 1904 epoch must never be a date.", part.getDate() > ImageData.EARLIEST_RECORDING);
	}

	/**
	 * The committed fixture video carries no creation time (FFmpeg wrote the 1904 epoch), so it
	 * answers its modification time — unchanged behaviour for it, and the fixture stays as it is.
	 */
	public void testFixtureVideoFallsBackToMtime() throws Exception {
		File fixture =
			new File("src/test/fixtures/test-album/2005-08-24 Blumen und Fliegen/MVI_0450.mp4");
		assertTrue("Missing fixture: " + fixture.getAbsolutePath(), fixture.exists());

		ImagePart part = analyze(fixture);
		assertEquals(ImageKind.VIDEO, part.getKind());
		assertEquals("The fixture has no container time and keeps its mtime.",
			fixture.lastModified(), part.getDate());
	}

	/**
	 * An album analysed afresh lists a video where it was recorded: between the photo taken before
	 * it and the photo taken after it.
	 */
	public void testVideoSortsBetweenPhotos() throws Exception {
		File album = new File(_base.toFile(), "2005-08-24 Trip");
		assertTrue(album.mkdirs());

		// Four days before and four days after the video, well clear of any time-zone question.
		writeJpegWithDateOriginal(new File(album, "before.jpg"), "2005:08:20 10:00:00");
		writeJpegWithDateOriginal(new File(album, "after.jpg"), "2005:08:28 10:00:00");
		File video = new File(album, "middle.mp4");
		GenerateTestAlbum.recordTinyVideo(video, RECORDED_AT);

		AlbumInfo info = album("/2005-08-24 Trip/");
		assertEquals("The video belongs between the photos.",
			List.of("before.jpg", "middle.mp4", "after.jpg"), names(info));

		// The photo dates are what their EXIF says, so the order is a date order, not a name order.
		assertEquals(exifMillis("2005:08:20 10:00:00"), date(info, "before.jpg"));
		assertEquals(RECORDED_MILLIS, date(info, "middle.mp4"));
		assertEquals(exifMillis("2005:08:28 10:00:00"), date(info, "after.jpg"));
	}

	/**
	 * An album whose sidecar already lists its parts keeps the stored order: the order is the
	 * author's edit, and a corrected video date never moves a part that is already written down.
	 */
	public void testStoredOrderIsKept() throws Exception {
		File album = new File(_base.toFile(), "2005-08-24 Stored");
		assertTrue(album.mkdirs());

		writeJpegWithDateOriginal(new File(album, "before.jpg"), "2005:08:20 10:00:00");
		writeJpegWithDateOriginal(new File(album, "after.jpg"), "2005:08:28 10:00:00");
		File video = new File(album, "middle.mp4");
		GenerateTestAlbum.recordTinyVideo(video, RECORDED_AT);

		// The sidecar of an album written before the fix: the video sits at the end with the
		// modification time it got then.
		long storedVideoDate = RECORDED_MILLIS + 86400000L;
		String index = "[\"AlbumInfo\",{\"title\":\"Stored\",\"parts\":["
			+ part("before.jpg", "IMAGE", exifMillis("2005:08:20 10:00:00")) + ","
			+ part("after.jpg", "IMAGE", exifMillis("2005:08:28 10:00:00")) + ","
			+ part("middle.mp4", "VIDEO", storedVideoDate) + "]}]";
		Files.write(album.toPath().resolve("index.json"), index.getBytes(java.nio.charset.StandardCharsets.UTF_8));

		AlbumInfo info = album("/2005-08-24 Stored/");
		assertEquals("A stored order must not be resorted.",
			List.of("before.jpg", "after.jpg", "middle.mp4"), names(info));
		assertEquals("A stored date must not be recomputed.", storedVideoDate, date(info, "middle.mp4"));
	}

	private static String part(String name, String kind, long date) {
		return "[\"ImagePart\",{\"name\":\"" + name + "\",\"kind\":\"" + kind
			+ "\",\"width\":4,\"height\":3,\"date\":" + date + "}]";
	}

	private static ImagePart analyze(File file) throws Exception {
		return ImageData.analyze(AlbumInfo.create(), file);
	}

	/** The album at the given path, as the server describes it. */
	private AlbumInfo album(String pathInfo) throws Exception {
		if (_servlet == null) {
			_servlet = new ImageServlet(_base.toFile(), new AuthService(AuthMode.OFF, "", _base));
		}
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "json");
		FakeResponse response = new FakeResponse();
		_servlet.doGet(request(pathInfo, null, new byte[0], Map.of(), parameters), response.response());
		assertEquals("Unexpected answer: " + response.body(), HttpServletResponse.SC_OK, response.status());

		Resource resource = Resource.readResource(
			new JsonReader(new ReaderAdapter(new StringReader(response.body()))));
		assertTrue("Expected an album, got: " + resource, resource instanceof AlbumInfo);
		return (AlbumInfo) resource;
	}

	private static List<String> names(AlbumInfo album) {
		List<String> result = new ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				result.add(((ImagePart) part).getName());
			}
		}
		return result;
	}

	private static long date(AlbumInfo album, String name) {
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart && ((ImagePart) part).getName().equals(name)) {
				return ((ImagePart) part).getDate();
			}
		}
		fail("No part '" + name + "' in the album.");
		return 0;
	}

	/**
	 * An EXIF date without a time-zone tag, read as UTC — exactly what
	 * <code>ExifSubIFDDirectory.getDateOriginal()</code> does with it, and the same convention the
	 * container time of a video is taken with.
	 */
	static long exifMillis(String exifDate) {
		LocalDateTime local = LocalDateTime.of(
			Integer.parseInt(exifDate.substring(0, 4)),
			Integer.parseInt(exifDate.substring(5, 7)),
			Integer.parseInt(exifDate.substring(8, 10)),
			Integer.parseInt(exifDate.substring(11, 13)),
			Integer.parseInt(exifDate.substring(14, 16)),
			Integer.parseInt(exifDate.substring(17, 19)));
		return local.atZone(ZoneOffset.UTC).toInstant().toEpochMilli();
	}

	/**
	 * Writes a tiny JPEG carrying nothing but an EXIF <code>DateTimeOriginal</code>.
	 *
	 * <p>
	 * Hand-built like the orientation of <code>TestPreviewCache</code>, because nothing on the
	 * class path writes EXIF: an <code>APP1</code> segment with the <code>Exif</code> preamble, a
	 * big-endian TIFF header, an IFD0 with the Exif pointer (0x8769) and a sub-IFD with the date
	 * (0x9003) as 20 ASCII bytes.
	 * </p>
	 */
	static void writeJpegWithDateOriginal(File file, String exifDate) throws Exception {
		BufferedImage image = new BufferedImage(40, 30, BufferedImage.TYPE_3BYTE_BGR);
		Graphics2D g = image.createGraphics();
		g.setColor(new Color(200, 40, 40));
		g.fillRect(0, 0, 40, 30);
		g.dispose();

		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		assertTrue("Cannot write a JPEG.", ImageIO.write(image, "jpg", buffer));
		byte[] jpeg = buffer.toByteArray();

		byte[] date = new byte[20];
		byte[] ascii = exifDate.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		assertEquals("An EXIF date is 19 characters.", 19, ascii.length);
		System.arraycopy(ascii, 0, date, 0, 19);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(jpeg, 0, 2);
		// APP1 and the segment length: the Exif preamble plus 64 bytes of TIFF, plus the length itself.
		out.write(0xFF);
		out.write(0xE1);
		out.write(0);
		out.write(72);
		out.write(new byte[] { 'E', 'x', 'i', 'f', 0, 0 });
		// Big-endian TIFF header, IFD0 at offset 8.
		out.write(new byte[] { 'M', 'M', 0, 42, 0, 0, 0, 8 });
		// IFD0: one entry, the pointer to the Exif sub-IFD at offset 26.
		out.write(new byte[] { 0, 1 });
		out.write(new byte[] { (byte) 0x87, 0x69, 0, 4, 0, 0, 0, 1, 0, 0, 0, 26 });
		out.write(new byte[] { 0, 0, 0, 0 });
		// The sub-IFD: one entry, DateTimeOriginal as 20 ASCII bytes at offset 44.
		out.write(new byte[] { 0, 1 });
		out.write(new byte[] { (byte) 0x90, 0x03, 0, 2, 0, 0, 0, 20, 0, 0, 0, 44 });
		out.write(new byte[] { 0, 0, 0, 0 });
		out.write(date);
		out.write(jpeg, 2, jpeg.length - 2);

		Files.write(file.toPath(), out.toByteArray());
	}

}
