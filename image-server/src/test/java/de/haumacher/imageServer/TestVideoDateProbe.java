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
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;
import test.de.haumacher.valbum.GenerateTestAlbum;

/**
 * Probe for the video recording date of #72, composed with the three date sources in one album,
 * the folder's effective date of #48 and a photo's EXIF precedence.
 */
@SuppressWarnings("javadoc")
public class TestVideoDateProbe extends TestCase {

	private Path _base;

	private ImageServlet _servlet;

	@Override
	protected void setUp() throws Exception {
		_base = Files.createTempDirectory("valbum-video-date-probe");
	}

	@Override
	protected void tearDown() throws Exception {
		if (_servlet != null) {
			_servlet.destroy();
		}
		try (Stream<Path> files = Files.walk(_base)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
	}

	/** EXIF, container and modification time sort into one order. */
	public void testThreeDateSourcesInOneAlbum() throws Exception {
		File album = new File(_base.toFile(), "Trip");
		assertTrue(album.mkdirs());
		TestVideoDate.writeJpegWithDateOriginal(new File(album, "photo.jpg"), "2005:08:28 10:00:00");
		File png = new File(album, "shot.png");
		writePng(png);
		long pngTime = TestVideoDate.exifMillis("2005:08:22 10:00:00");
		assertTrue(png.setLastModified(pngTime));
		File video = new File(album, "clip.mp4");
		GenerateTestAlbum.recordTinyVideo(video, "2005-08-20T12:00:00Z");
		assertTrue(video.setLastModified(pngTime + 30L * 86400000L));

		AlbumInfo info = (AlbumInfo) read("/Trip/");
		assertEquals(List.of("clip.mp4", "shot.png", "photo.jpg"), names(info));

		// The album without a date in its name is dated by its earliest image: the video (#48).
		assertEquals("The video's recording time is the earliest date of the album.",
			1124539200000L, info.getEffectiveDate());
		// A listing tile is dated by the sidecar and the folder name alone, never by the images.
		ListingInfo listing = (ListingInfo) read("/");
		FolderInfo folder = listing.getFolders().stream()
			.filter(f -> f.getName().equals("Trip")).findFirst().orElseThrow();
		assertEquals(0L, folder.getEffectiveDate());
	}

	/** A photo's EXIF date beats its modification time, whatever the container reader does. */
	public void testPhotoKeepsExifPrecedence() throws Exception {
		File photo = new File(_base.toFile(), "photo.jpg");
		TestVideoDate.writeJpegWithDateOriginal(photo, "2005:08:20 10:00:00");
		assertTrue(photo.setLastModified(System.currentTimeMillis()));
		ImagePart part = ImageData.analyze(AlbumInfo.create(), photo);
		assertEquals(TestVideoDate.exifMillis("2005:08:20 10:00:00"), part.getDate());
	}

	/** A container time before digital video is nonsense and gives way to the modification time. */
	public void testImplausibleContainerTimeFallsBack() throws Exception {
		File video = new File(_base.toFile(), "old.mp4");
		GenerateTestAlbum.recordTinyVideo(video, "1985-06-01T00:00:00Z");
		long mtime = TestVideoDate.exifMillis("2005:08:22 10:00:00");
		assertTrue(video.setLastModified(mtime));
		ImagePart part = ImageData.analyze(AlbumInfo.create(), video);
		assertEquals(mtime, part.getDate());
	}

	private Resource read(String pathInfo) throws Exception {
		if (_servlet == null) {
			_servlet = new ImageServlet(_base.toFile(), new AuthService(AuthMode.OFF, "", _base));
		}
		FakeResponse response = new FakeResponse();
		_servlet.doGet(request(pathInfo, null, new byte[0], Map.of(), Map.of("type", "json")), response.response());
		assertEquals("Unexpected answer: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return Resource.readResource(new JsonReader(new ReaderAdapter(new StringReader(response.body()))));
	}

	private static void writePng(File file) throws Exception {
		BufferedImage image = new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB);
		assertTrue(ImageIO.write(image, "png", file));
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
}
