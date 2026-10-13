/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import com.drew.imaging.ImageProcessingException;
import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.cache.ImageData;
import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.pipeline.FolderPipeline;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.FolderKind;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImageKind;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Test case for a file that cannot be read, see issue #252.
 *
 * <p>
 * The report: opening a folder whose albums were newly processed answered 500, because one video
 * of one album could not be read (&quot;Neither JPG, PNG, WebP, GIF, MOV, nor MP4 file&quot;).
 * The video was a phone recording over 4 GiB, which metadata-extractor gives up on and FFmpeg
 * reads. One unreadable file never fails more than itself, one broken folder never fails a
 * listing, and what is left out is said.
 * </p>
 *
 * <p>
 * Since issue #253 such a file is no longer left out: the background records it as broken, and its
 * album answers it as a broken part (its name, its size and why it cannot be read), which the app
 * shows with a broken-picture icon. It is analysed once per state of the file, never by a request
 * of a running server.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestUnreadableFile extends TestCase {

	/** The message of the reported failure. */
	private static final String NEITHER = "Neither JPG, PNG, WebP, GIF, MOV, nor MP4 file";

	private Path _base;

	private ImageServlet _servlet;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-unreadable-test");
		_servlet = new ImageServlet(_base.toFile());
	}

	@Override
	protected void tearDown() throws Exception {
		if (_servlet != null) {
			_servlet.destroy();
		}
		if (_base != null) {
			try (Stream<Path> files = Files.walk(_base)) {
				files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
		super.tearDown();
	}

	/**
	 * The fixture fails exactly where the reported file failed, and as a checked failure: an mp4
	 * container without a video track, which FFmpeg cannot read either.
	 */
	public void testAVideoWithoutTrackFailsAsTheReportedFileDid() throws Exception {
		File file = noTrack("Broken", "x.mp4").toFile();
		try {
			ImageData.analyze(AlbumInfo.create(), file);
			fail("A video without a track cannot be described.");
		} catch (ImageProcessingException ex) {
			assertTrue(ex.getMessage(), ex.getMessage().contains(NEITHER));
			assertTrue("FFmpeg was asked as well: " + ex.getMessage(), ex.getMessage().contains("FFmpeg"));
		}
	}

	/**
	 * An album holding a file that cannot be read is answered, with every other photograph, and the
	 * file as a broken part (issue #253).
	 */
	public void testAnAlbumHoldingAnUnreadableFileIsAnswered() throws Exception {
		jpeg("2025-08-14 Mission", "a.jpg");
		jpeg("2025-08-14 Mission", "b.jpg");
		Path broken = noTrack("2025-08-14 Mission", "x.mp4");

		AlbumInfo album = album("/2025-08-14 Mission/");

		assertEquals(Arrays.asList("a.jpg", "b.jpg", "x.mp4"), sorted(names(album)));
		assertEquals("", album.getImageByName().get("a.jpg").getBroken());
		ImagePart x = album.getImageByName().get("x.mp4");
		assertTrue(x.getBroken(), x.getBroken().contains(NEITHER));
		assertEquals(ImageKind.VIDEO, x.getKind());
		assertEquals(Files.size(broken), x.getFileSize());
		assertEquals("No size in pixels is known.", 0, x.getWidth());
	}

	/**
	 * The reported case: a listing whose album holds nothing but a file that cannot be read, so that
	 * its tile is to show that file, is answered with every folder.
	 */
	public void testAListingOfAFolderHoldingOnlyAnUnreadableFileIsAnswered() throws Exception {
		noTrack("2025/2025-08-14 Mission", "x.mp4");
		jpeg("2025/2025-08-01 Trip", "a.jpg");

		ListingInfo listing = listing("/2025/");

		// A year folder lists its albums oldest first, see issue #270.
		assertEquals(Arrays.asList("2025-08-01 Trip", "2025-08-14 Mission"), folderNames(listing));
		FolderInfo broken = byName(listing, "2025-08-14 Mission");
		assertEquals("Mission", broken.getTitle());
		assertEquals(FolderKind.ALBUM, broken.getKind());
		assertNull("Nothing to show of it.", broken.getIndexPicture());
		assertNotNull(byName(listing, "2025-08-01 Trip").getIndexPicture());
	}

	/**
	 * A folder whose tile cannot be built at all (here a hand-broken sidecar) is shown by its name,
	 * and the listing holds every other folder.
	 */
	public void testAFolderWhoseTileCannotBeBuiltIsShownByItsName() throws Exception {
		jpeg("Root/2025-08-14 Mission_Camino", "a.jpg");
		Files.write(_base.resolve("Root/2025-08-14 Mission_Camino/index.json"),
			"[\"AlbumInfo\",{\"title\":\"T\",\"indexPicture\":{\"scale\":\"broken\"}}]"
				.getBytes(StandardCharsets.UTF_8));
		jpeg("Root/2025-08-01 Trip", "a.jpg");

		ListingInfo listing = listing("/Root/");

		assertEquals(Arrays.asList("2025-08-14 Mission_Camino", "2025-08-01 Trip"), folderNames(listing));
		FolderInfo broken = byName(listing, "2025-08-14 Mission_Camino");
		assertEquals("Mission Camino", broken.getTitle());
		assertEquals(FolderKind.ALBUM, broken.getKind());
		assertFalse("Its subtitle is the date of its name.", broken.getSubTitle().isEmpty());

		// The very tile the background work and the link entries build.
		assertEquals("Mission Camino", ResourceCache.folderInfo(_base.resolve("Root/2025-08-14 Mission_Camino")
			.toFile()).getTitle());
	}

	/**
	 * The file the user actually had: a recording that was cut off before its <code>moov</code>.
	 * It is left out of its album, which is answered with everything else, the listing above holds
	 * every folder, and the file is named in the status.
	 */
	public void testACutOffRecordingIsABrokenPartAndSaid() throws Exception {
		Path dir = Files.createDirectories(_base.resolve("2025/2025-08-14 Mission"));
		Files.write(dir.resolve("20250903_185939.mp4"), LargeMp4.truncated());
		jpeg("2025/2025-08-14 Mission", "a.jpg");
		Path alone = Files.createDirectories(_base.resolve("2025/2025-08-15 Alone"));
		Files.write(alone.resolve("20250903_185939.mp4"), LargeMp4.truncated());
		jpeg("2025/2025-08-01 Trip", "a.jpg");

		ListingInfo listing = listing("/2025/");
		assertEquals(Arrays.asList("2025-08-01 Trip", "2025-08-14 Mission", "2025-08-15 Alone"),
			folderNames(listing));
		assertEquals("Alone", byName(listing, "2025-08-15 Alone").getTitle());

		AlbumInfo mission = album("/2025/2025-08-14 Mission/");
		assertEquals(Arrays.asList("20250903_185939.mp4", "a.jpg"), sorted(names(mission)));
		assertTrue(mission.getImageByName().get("20250903_185939.mp4").getBroken().contains("FFmpeg"));

		FolderPipeline.Status status = _servlet.index().pipeline().status();
		assertNotNull("The failure is said.", status.getFailure());
		assertTrue(status.getFailure(), status.getFailure().startsWith("20250903_185939.mp4: "));
		assertTrue("FFmpeg was asked as well: " + status.getFailure(), status.getFailure().contains("FFmpeg"));
		// Both albums hold the file; the most recent failure is the one said.
		assertTrue(status.getFailureFolder(), Arrays.asList("2025/2025-08-14 Mission", "2025/2025-08-15 Alone")
			.contains(status.getFailureFolder()));
		assertTrue("The original is untouched.", Arrays.equals(LargeMp4.truncated(),
			Files.readAllBytes(dir.resolve("20250903_185939.mp4"))));
	}

	/** The file left out is named in the status of the background work. */
	public void testTheFileLeftOutIsReported() throws Exception {
		jpeg("2025-08-14 Mission", "a.jpg");
		noTrack("2025-08-14 Mission", "x.mp4");

		album("/2025-08-14 Mission/");

		FolderPipeline.Status status = _servlet.index().pipeline().status();
		assertNotNull("The failure is said.", status.getFailure());
		assertTrue(status.getFailure(), status.getFailure().startsWith("x.mp4: "));
		assertTrue(status.getFailure(), status.getFailure().contains(NEITHER));
		assertEquals("2025-08-14 Mission", status.getFailureFolder());
	}

	/**
	 * The file left out is said once, not on every read of its album; again once it changed.
	 */
	public void testTheFileLeftOutIsSaidOncePerState() throws Exception {
		jpeg("Album", "a.jpg");
		Path broken = noTrack("Album", "x.mp4");

		List<String> said = new ArrayList<>();
		ResourceCache cache = new ResourceCache();
		try {
			cache.setRefusals((folder, reason) -> said.add(folder.getName() + " " + reason));
			PathInfo album = new PathInfo(_base, Path.of("Album"));

			assertEquals(Arrays.asList("a.jpg", "x.mp4"), sorted(names((AlbumInfo) cache.lookup(album))));
			cache.invalidate(album);
			long analyses = ImageData.analyses();
			assertEquals(Arrays.asList("a.jpg", "x.mp4"), sorted(names((AlbumInfo) cache.lookup(album))));
			assertEquals("Recorded as broken: not analysed again, see issue #253.", analyses, ImageData.analyses());
			assertEquals("Said once, though read twice: " + said, 1, said.size());
			assertTrue(said.get(0), said.get(0).startsWith("Album x.mp4: "));

			// Changed, and still unreadable: said again.
			Files.write(broken, new byte[] { 0, 0, 0, 8, 'f', 'r', 'e', 'e' }, StandardOpenOption.APPEND);
			cache.invalidate(album);
			assertEquals(Arrays.asList("a.jpg", "x.mp4"), sorted(names((AlbumInfo) cache.lookup(album))));
			assertEquals(said.toString(), 2, said.size());
		} finally {
			cache.close();
		}
	}

	/**
	 * Read, written and read again says the same: why the file cannot be read never reaches the
	 * sidecar (its part does, with what was said about it), so that a build that can read it does.
	 */
	public void testTheFailureNeverReachesTheSidecar() throws Exception {
		jpeg("2025-08-14 Mission", "a.jpg");
		jpeg("2025-08-14 Mission", "b.jpg");
		noTrack("2025-08-14 Mission", "x.mp4");

		FakeResponse first = get("/2025-08-14 Mission/");
		assertEquals(first.body(), HttpServletResponse.SC_OK, first.status());

		FakeResponse stored = put("/2025-08-14 Mission/", first.body());
		assertEquals(stored.body(), HttpServletResponse.SC_OK, stored.status());
		String sidecar = new String(Files.readAllBytes(_base.resolve("2025-08-14 Mission/index.json")),
			StandardCharsets.UTF_8);
		assertTrue("Written: " + sidecar, sidecar.contains("a.jpg"));
		assertFalse("Never written: " + sidecar, sidecar.contains(NEITHER));

		FakeResponse again = get("/2025-08-14 Mission/");
		assertEquals(again.body(), HttpServletResponse.SC_OK, again.status());
		assertEquals(first.body(), again.body());
		assertTrue("The original is untouched.",
			Arrays.equals(LargeMp4.withoutVideoTrack(), Files.readAllBytes(_base.resolve("2025-08-14 Mission/x.mp4"))));
	}

	/**
	 * The reported file: an mp4 over 4 GiB, which metadata-extractor does not read, is read through
	 * FFmpeg, and is a video of its album with its size and its recording time.
	 */
	public void testAVideoOver4GiBIsReadThroughFfmpeg() throws Exception {
		Path folder = Files.createDirectories(_base.resolve("2025-08-14 Mission"));
		File video = folder.resolve("20250903_185939.mp4").toFile();
		if (!LargeMp4.write(video)) {
			System.err.println("Skipped " + getName() + ": the file system cannot write a sparse file.");
			return;
		}
		assertTrue(video.length() > (1L << 32));

		try {
			ImageData.readMetadata(video).getDirectories().forEach(directory -> {
				assertFalse("metadata-extractor still cannot read it, else this test tests nothing.",
					directory instanceof com.drew.metadata.mp4.media.Mp4VideoDirectory);
			});
		} catch (ImageProcessingException ex) {
			// Not read at all: as good.
		}

		ImageData data = ImageData.analyze(AlbumInfo.create(), video, ImageData.Analysis.NONE, ZoneOffset.UTC);
		assertEquals(ImageKind.VIDEO, data.getKind());
		assertEquals(160, data.getWidth());
		assertEquals(120, data.getHeight());
		assertEquals(Instant.parse("2025-09-03T18:59:39Z").toEpochMilli(), data.getDate());

		jpeg("2025-08-14 Mission", "a.jpg");
		AlbumInfo album = album("/2025-08-14 Mission/");
		assertEquals(Arrays.asList("20250903_185939.mp4", "a.jpg"),
			names(album).stream().sorted().collect(Collectors.toList()));
		assertEquals("Nothing failed: " + _servlet.index().pipeline().status().getFailure(), null,
			_servlet.index().pipeline().status().getFailure());
	}

	// --- Helpers. ---

	private Path noTrack(String folder, String name) throws Exception {
		Path dir = Files.createDirectories(_base.resolve(folder));
		Path file = dir.resolve(name);
		Files.write(file, LargeMp4.withoutVideoTrack());
		return file;
	}

	private void jpeg(String folder, String name) throws IOException {
		Path dir = Files.createDirectories(_base.resolve(folder));
		ImageIO.write(new BufferedImage(4, 3, BufferedImage.TYPE_3BYTE_BGR), "jpg", dir.resolve(name).toFile());
	}

	private static List<String> names(AlbumInfo album) {
		List<String> result = new ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				result.add(((ImagePart) part).getName());
			} else if (part instanceof ImageGroup) {
				for (ImagePart image : ((ImageGroup) part).getImages()) {
					result.add(image.getName());
				}
			}
		}
		return result;
	}

	private static List<String> sorted(List<String> names) {
		return names.stream().sorted().collect(Collectors.toList());
	}

	private static List<String> folderNames(ListingInfo listing) {
		return listing.getFolders().stream().map(FolderInfo::getName).collect(Collectors.toList());
	}

	private static FolderInfo byName(ListingInfo listing, String name) {
		for (FolderInfo folder : listing.getFolders()) {
			if (name.equals(folder.getName())) {
				return folder;
			}
		}
		fail("No folder '" + name + "' in " + folderNames(listing));
		return null;
	}

	private AlbumInfo album(String pathInfo) throws Exception {
		Resource resource = json(pathInfo);
		assertTrue("Expected an album, got: " + resource, resource instanceof AlbumInfo);
		de.haumacher.imageServer.shared.util.UpdateTransient.updateTransient(resource);
		return (AlbumInfo) resource;
	}

	private ListingInfo listing(String pathInfo) throws Exception {
		Resource resource = json(pathInfo);
		assertTrue("Expected a listing, got: " + resource, resource instanceof ListingInfo);
		return (ListingInfo) resource;
	}

	private Resource json(String pathInfo) throws Exception {
		FakeResponse response = get(pathInfo);
		assertEquals("Reading failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return Resource.readResource(new JsonReader(new ReaderAdapter(new StringReader(response.body()))));
	}

	private FakeResponse get(String pathInfo) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "json");
		FakeResponse response = new FakeResponse();
		_servlet.doGet(TestImageServletPut.request(pathInfo, null, new byte[0], new HashMap<>(), parameters),
			response.response());
		return response;
	}

	private FakeResponse put(String pathInfo, String body) throws Exception {
		FakeResponse response = new FakeResponse();
		_servlet.doPut(TestImageServletPut.request(pathInfo, "application/json",
			body.getBytes(StandardCharsets.UTF_8)), response.response());
		return response;
	}
}
