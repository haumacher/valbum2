/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.cache.AnalysisRecord;
import de.haumacher.imageServer.cache.ImageData;
import de.haumacher.imageServer.pipeline.FolderPipeline;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImageKind;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ListingInfo;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;

/**
 * Test case for issue #253: a request never analyses a file; the background records what a folder
 * holds, broken files included, and a request answers what was recorded.
 *
 * <p>
 * The servlet here runs as a server does, with a background ({@link de.haumacher.imageServer.cache.ResourceCache#setBackground(boolean)}),
 * whose work the tests drive by hand: {@link #record(String)} runs the step a running server runs
 * when a watch or the sweep reports a folder.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestBackgroundRecord extends SpaceTestCase {

	private static final String TOKEN = SharingFixture.ALICE;

	@Override
	protected ImageServlet servlet() throws Exception {
		ImageServlet result = super.servlet();
		result.cache().setBackground(true);
		return result;
	}

	/**
	 * The user's words: "if I have 100 broken files like that in the folder, it will be bad. It
	 * serves nothing scanning a folder when accessing it." An album of 100 files nobody analysed is
	 * answered without opening one, as being prepared, and the listing above it likewise.
	 */
	public void testARequestOpensNoMediaFile() throws Exception {
		Path folder = Files.createDirectories(_base.resolve("Library/2025-01-01 Hundred"));
		for (int n = 0; n < 100; n++) {
			jpeg(folder.resolve(String.format("IMG_%03d.jpg", n)), 4, 3);
		}
		servlet();
		// The start-up's first look at every folder: the library is known as a whole.
		discover("Library");

		long before = ImageData.analyses();
		AlbumInfo album = albumOf(get("/Library/2025-01-01 Hundred/", "json", TOKEN));
		assertTrue("Never recorded: being prepared.", album.isPreparing());
		assertEquals("Nothing recorded, nothing answered.", 0, album.getParts().size());
		ListingInfo listing = listing(get("/Library/", "json", TOKEN));
		FolderInfo tile = entry(listing, "2025-01-01 Hundred");
		assertEquals("By its name.", "Hundred", tile.getTitle());
		assertNull("No picture is opened for it.", tile.getIndexPicture());
		assertEquals("Not one file opened.", before, ImageData.analyses());

		record("Library/2025-01-01 Hundred");
		long recorded = ImageData.analyses();
		assertEquals("The background analysed each file once.", before + 100, recorded);

		album = albumOf(get("/Library/2025-01-01 Hundred/", "json", TOKEN));
		assertFalse(album.isPreparing());
		assertEquals(100, names(album).size());
		tile = entry(listing(get("/Library/", "json", TOKEN)), "2025-01-01 Hundred");
		assertNotNull("The tile has its picture now.", tile.getIndexPicture());
		assertEquals("Answered from the record, still nothing opened.", recorded, ImageData.analyses());
	}

	/** A photograph the background records is in the next answer, with its size. */
	public void testTheBackgroundRecordsANewPhotograph() throws Exception {
		Path folder = Files.createDirectories(_base.resolve("Library/Trip"));
		jpeg(folder.resolve("a.jpg"), 4, 3);
		servlet();
		record("Library/Trip");
		assertEquals(Arrays.asList("a.jpg"), names(albumOf(get("/Library/Trip/", "json", TOKEN))));

		jpeg(folder.resolve("b.jpg"), 8, 6);
		assertEquals("Not recorded yet: not there.", Arrays.asList("a.jpg"),
			names(albumOf(get("/Library/Trip/", "json", TOKEN))));
		assertEquals("Not even its picture.", HttpServletResponse.SC_NOT_FOUND,
			get("/Library/Trip/b.jpg", "tn", TOKEN).status());

		record("Library/Trip");
		AlbumInfo album = albumOf(get("/Library/Trip/", "json", TOKEN));
		assertEquals(Arrays.asList("a.jpg", "b.jpg"), sorted(names(album)));
		ImagePart b = album.getImageByName().get("b.jpg");
		assertEquals(8, b.getWidth());
		assertEquals(6, b.getHeight());
	}

	/**
	 * The reported file: a recording cut off before its index. Recorded once as broken; a second
	 * pass over the unchanged file does not open it again; replaced by a readable video, it is
	 * analysed and an ordinary part.
	 */
	public void testACutOffVideoIsRecordedOnceAndAReplacementIsRead() throws Exception {
		Path folder = Files.createDirectories(_base.resolve("Library/Mission"));
		jpeg(folder.resolve("a.jpg"), 4, 3);
		Path video = folder.resolve("20250903_185939.mp4");
		Files.write(video, LargeMp4.truncated());
		servlet();

		long before = ImageData.analyses();
		record("Library/Mission");
		assertEquals(before + 2, ImageData.analyses());

		AlbumInfo album = albumOf(get("/Library/Mission/", "json", TOKEN));
		ImagePart broken = album.getImageByName().get("20250903_185939.mp4");
		assertNotNull("Shown, not left out: " + names(album), broken);
		assertFalse("A reason: " + broken.getBroken(), broken.getBroken().isEmpty());
		assertEquals(ImageKind.VIDEO, broken.getKind());
		assertEquals(LargeMp4.truncated().length, broken.getFileSize());
		assertEquals(Files.getLastModifiedTime(video).toMillis(), broken.getDate());
		assertEquals("Nothing is made of it.", HttpServletResponse.SC_NOT_FOUND,
			get("/Library/Mission/20250903_185939.mp4", "tn", TOKEN).status());
		assertEquals("Its original can be taken.", HttpServletResponse.SC_OK,
			get("/Library/Mission/20250903_185939.mp4", null, TOKEN).status());

		long once = ImageData.analyses();
		record("Library/Mission");
		assertEquals("Unchanged: not opened again.", once, ImageData.analyses());

		// The repair: a readable video in its place.
		Files.copy(fixture("video/clip.m4v"), video, StandardCopyOption.REPLACE_EXISTING);
		assertTrue(video.toFile().setLastModified(System.currentTimeMillis() + 10_000));
		record("Library/Mission");
		assertEquals("The repaired file alone is analysed.", once + 1, ImageData.analyses());

		ImagePart repaired = albumOf(get("/Library/Mission/", "json", TOKEN)).getImageByName().get("20250903_185939.mp4");
		assertEquals("", repaired.getBroken());
		assertEquals(ImageKind.VIDEO, repaired.getKind());
		assertTrue(repaired.getWidth() > 0 && repaired.getHeight() > 0);
		assertEquals(0L, repaired.getFileSize());
	}

	/**
	 * A sidecar written by the build before issue #253 (see
	 * <code>src/test/fixtures/sidecar-2.13/</code>) answers its parts at once, before the
	 * background ever looked at the folder, and none of them is opened afterwards either: the
	 * sidecar's parts are the truth for the files it lists.
	 */
	public void testAnOlderSidecarStillReadsWithItsParts() throws Exception {
		Path folder = Files.createDirectories(_base.resolve("Library/2025-06-01 Garden"));
		for (String name : new String[] { "index.json", "a.jpg", "b.jpg" }) {
			Files.copy(fixture("sidecar-2.13/" + name), folder.resolve(name));
		}
		jpeg(folder.resolve("c.jpg"), 2, 2);
		servlet();

		long before = ImageData.analyses();
		AlbumInfo album = albumOf(get("/Library/2025-06-01 Garden/", "json", TOKEN));
		assertEquals("Its parts, at once.", Arrays.asList("a.jpg", "b.jpg"), names(album));
		assertTrue("The folder is not recorded yet.", album.isPreparing());
		assertEquals(8, album.getImageByName().get("a.jpg").getWidth());
		assertEquals(2, album.getImageByName().get("b.jpg").getRating());
		assertEquals("Roses", album.getImageByName().get("b.jpg").getComment());
		assertEquals("Garden", album.getTitle());
		assertEquals(before, ImageData.analyses());

		record("Library/2025-06-01 Garden");
		assertEquals("The new file alone is analysed.", before + 1, ImageData.analyses());
		album = albumOf(get("/Library/2025-06-01 Garden/", "json", TOKEN));
		assertFalse(album.isPreparing());
		assertEquals(Arrays.asList("a.jpg", "b.jpg", "c.jpg"), names(album));
		assertEquals(2, album.getImageByName().get("b.jpg").getRating());
	}

	/** A listing never opens a photograph of a folder without a sidecar; it shows it by its name. */
	public void testAListingOpensNoPhotographOfAFolderWithoutSidecar() throws Exception {
		jpeg(Files.createDirectories(_base.resolve("Library/2025-03-01 Ski")).resolve("a.jpg"), 4, 3);
		jpeg(Files.createDirectories(_base.resolve("Library/Holiday_InSpain")).resolve("a.jpg"), 4, 3);
		servlet();
		discover("Library");

		long before = ImageData.analyses();
		ListingInfo listing = listing(get("/Library/", "json", TOKEN));
		assertEquals(before, ImageData.analyses());
		assertEquals("Ski", entry(listing, "2025-03-01 Ski").getTitle());
		assertFalse("The date of its name.", entry(listing, "2025-03-01 Ski").getSubTitle().isEmpty());
		assertEquals("Holiday In Spain", entry(listing, "Holiday_InSpain").getTitle());
		assertNull(entry(listing, "Holiday_InSpain").getIndexPicture());
	}

	/** A file deleted by hand stays until the background notices; its pictures answer 404, never 500. */
	public void testADeletedFileStaysUntilTheBackgroundNotices() throws Exception {
		Path folder = Files.createDirectories(_base.resolve("Library/Trip"));
		jpeg(folder.resolve("a.jpg"), 4, 3);
		jpeg(folder.resolve("b.jpg"), 4, 3);
		servlet();
		record("Library/Trip");
		assertEquals(Arrays.asList("a.jpg", "b.jpg"), sorted(names(albumOf(get("/Library/Trip/", "json", TOKEN)))));

		Files.delete(folder.resolve("b.jpg"));
		assertEquals("Not noticed yet.", Arrays.asList("a.jpg", "b.jpg"),
			sorted(names(albumOf(get("/Library/Trip/", "json", TOKEN)))));
		assertEquals(HttpServletResponse.SC_NOT_FOUND, get("/Library/Trip/b.jpg", "tn", TOKEN).status());
		assertEquals(HttpServletResponse.SC_NOT_FOUND, get("/Library/Trip/b.jpg", null, TOKEN).status());

		record("Library/Trip");
		assertEquals(Arrays.asList("a.jpg"), names(albumOf(get("/Library/Trip/", "json", TOKEN))));
	}

	/**
	 * The album answer carries the broken part; read, written and read again is equal, what was said
	 * about it (the trash rating, by which it is deleted) is kept, and the failure never reaches the
	 * sidecar, so a repaired file is analysed again and becomes an ordinary part.
	 */
	public void testABrokenPartSurvivesAWriteAndIsReadAgainOnceRepaired() throws Exception {
		Path folder = Files.createDirectories(_base.resolve("Library/Broken"));
		jpeg(folder.resolve("a.jpg"), 4, 3);
		Path video = folder.resolve("x.mp4");
		Files.write(video, LargeMp4.withoutVideoTrack());
		servlet();
		record("Library/Broken");

		FakeResponse first = get("/Library/Broken/", "json", TOKEN);
		AlbumInfo album = albumOf(first);
		assertFalse(album.getImageByName().get("x.mp4").getBroken().isEmpty());

		// The trash rating, given in the edit mode and saved with the whole album.
		album.getImageByName().get("x.mp4").setRating(-2);
		String body = json(album);
		FakeResponse stored = put("/Library/Broken/", body, TOKEN);
		assertEquals(body(stored), HttpServletResponse.SC_OK, stored.status());
		String sidecar = new String(Files.readAllBytes(folder.resolve("index.json")), StandardCharsets.UTF_8);
		assertTrue(sidecar, sidecar.contains("x.mp4"));
		assertFalse("The failure is never stored: " + sidecar,
			sidecar.contains(album.getImageByName().get("x.mp4").getBroken()));

		AlbumInfo again = albumOf(get("/Library/Broken/", "json", TOKEN));
		assertEquals("Read, written, read again: equal.", body, json(again));
		ImagePart x = again.getImageByName().get("x.mp4");
		assertFalse(x.getBroken().isEmpty());
		assertEquals(-2, x.getRating());

		// Repaired: analysed again, its trash rating kept.
		Files.copy(fixture("video/clip.m4v"), video, StandardCopyOption.REPLACE_EXISTING);
		assertTrue(video.toFile().setLastModified(System.currentTimeMillis() + 10_000));
		record("Library/Broken");
		x = albumOf(get("/Library/Broken/", "json", TOKEN)).getImageByName().get("x.mp4");
		assertEquals("", x.getBroken());
		assertTrue(x.getWidth() > 0);
		assertEquals(-2, x.getRating());
	}

	/**
	 * A broken file is deleted the way any photograph of an album is: rated as trash in the edit
	 * mode, the album saved, and purged.
	 */
	public void testABrokenFileRatedAsTrashIsPurged() throws Exception {
		Path folder = Files.createDirectories(_base.resolve("Library/Purge"));
		jpeg(folder.resolve("a.jpg"), 4, 3);
		Files.write(folder.resolve("cut.mp4"), LargeMp4.truncated());
		servlet();
		record("Library/Purge");

		AlbumInfo album = albumOf(get("/Library/Purge/", "json", TOKEN));
		album.getImageByName().get("cut.mp4").setRating(-2);
		assertEquals(HttpServletResponse.SC_OK, put("/Library/Purge/", json(album), TOKEN).status());

		java.util.Map<String, String> parameters = new java.util.HashMap<>();
		parameters.put("action", "purge");
		FakeResponse purged = post("/Library/Purge/", "", TOKEN, parameters);
		assertEquals(body(purged), HttpServletResponse.SC_OK, purged.status());
		assertFalse("Deleted from disk.", Files.exists(folder.resolve("cut.mp4")));
		assertTrue(Files.exists(folder.resolve("a.jpg")));
		assertEquals(Arrays.asList("a.jpg"), names(albumOf(get("/Library/Purge/", "json", TOKEN))));
	}

	/** An upload is analysed by the upload itself and answered at once. */
	public void testAnUploadIsAnsweredAtOnce() throws Exception {
		Path folder = Files.createDirectories(_base.resolve("Library/Uploads"));
		jpeg(folder.resolve("a.jpg"), 4, 3);
		servlet();
		record("Library/Uploads");

		LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
		files.put("up.jpg", jpegBytes(6, 4));
		files.put("cut.mp4", LargeMp4.truncated());
		FakeResponse response = upload("/Library/Uploads/", TOKEN, files);
		assertEquals(body(response), HttpServletResponse.SC_OK, response.status());

		AlbumInfo album = albumOf(get("/Library/Uploads/", "json", TOKEN));
		assertEquals(Arrays.asList("a.jpg", "cut.mp4", "up.jpg"), sorted(names(album)));
		assertEquals(6, album.getImageByName().get("up.jpg").getWidth());
		assertFalse(album.getImageByName().get("cut.mp4").getBroken().isEmpty());
	}

	/**
	 * The broken file is an outstanding failure of the background work (issue #251) as long as it is
	 * broken, and no more once it is repaired.
	 */
	public void testABrokenFileIsAnOutstandingFailure() throws Exception {
		Path folder = Files.createDirectories(_base.resolve("Library/Mission"));
		Path video = folder.resolve("cut.mp4");
		Files.write(video, LargeMp4.truncated());
		servlet();
		record("Library/Mission");
		record("Library/Mission");
		FolderPipeline.Status status = servlet().index().pipeline().status();
		assertNotNull("Still outstanding after a second pass.", status.getFailure());
		assertTrue(status.getFailure(), status.getFailure().startsWith("cut.mp4: "));
		assertEquals("Library/Mission", status.getFailureFolder());

		Files.copy(fixture("video/clip.m4v"), video, StandardCopyOption.REPLACE_EXISTING);
		assertTrue(video.toFile().setLastModified(System.currentTimeMillis() + 10_000));
		record("Library/Mission");
		assertNull(servlet().index().pipeline().status().getFailure());
	}

	/** A broken file is no photograph to search, and never the cover of a folder. */
	public void testABrokenFileIsNeitherFoundNorACover() throws Exception {
		Path folder = Files.createDirectories(_base.resolve("Library/Only broken"));
		Files.write(folder.resolve("a.mp4"), LargeMp4.truncated());
		servlet();
		discover("Library");
		record("Library/Only broken");

		FolderInfo tile = entry(listing(get("/Library/", "json", TOKEN)), "Only broken");
		assertNull("No picture to show.", tile.getIndexPicture());
		// The search step of the background, see SearchIndex.
		servlet().searchIndex().build();
		assertTrue(PhotoSearch.find(new PathInfo(_base, Path.of("Library")), null, servlet().searchIndex(), null,
			SearchIndex.Visibility.ALL).isEmpty());
	}

	/**
	 * A raw and the JPEG of its name are one photograph (issue #191), paired from what the background
	 * recorded; the raw companion is never analysed.
	 */
	public void testARawPairIsOnePhotographAndTheRawIsNotAnalysed() throws Exception {
		Path folder = Files.createDirectories(_base.resolve("Library/Raw"));
		jpeg(folder.resolve("IMG_1.JPG"), 6, 4);
		Files.write(folder.resolve("IMG_1.CR2"), new byte[] { 1, 2, 3 });
		servlet();

		long before = ImageData.analyses();
		record("Library/Raw");
		assertEquals("The JPEG alone.", before + 1, ImageData.analyses());
		AlbumInfo album = albumOf(get("/Library/Raw/", "json", TOKEN));
		assertEquals(Arrays.asList("IMG_1.JPG"), names(album));
		assertEquals("IMG_1.CR2", album.getImageByName().get("IMG_1.JPG").getRaw());
	}

	/**
	 * A folder copied in by hand while the server runs is noticed by the watches of the background,
	 * recorded, and answered, without a request ever looking at its files.
	 */
	public void testAFolderCopiedInWhileRunningAppears() throws Exception {
		Files.createDirectories(_base.resolve("Library"));
		servlet().index().pipeline().setQuietMillis(200);
		servlet().startIndexing();
		assertTrue(servlet().index().awaitPass(30_000));
		assertTrue(servlet().index().pipeline().awaitIdle(30_000));
		assertTrue("Every folder is watched.", servlet().index().pipeline().watch().watchCount() > 0);

		Path copied = Files.createDirectories(_base.resolve("Library/2025-07-01 Copied"));
		jpeg(copied.resolve("a.jpg"), 4, 3);
		jpeg(copied.resolve("b.jpg"), 4, 3);

		long end = System.currentTimeMillis() + 30_000;
		List<String> names = new ArrayList<>();
		while (System.currentTimeMillis() < end) {
			names = names(albumOf(get("/Library/2025-07-01 Copied/", "json", TOKEN)));
			if (names.size() == 2) {
				break;
			}
			Thread.sleep(50);
		}
		assertEquals(Arrays.asList("a.jpg", "b.jpg"), sorted(names));
		FolderInfo tile = entry(listing(get("/Library/", "json", TOKEN)), "2025-07-01 Copied");
		assertNotNull("Its tile shows a picture.", tile.getIndexPicture());
	}

	// --- Helpers. ---

	/** The album answered, its parts found by their names. */
	private static AlbumInfo albumOf(FakeResponse response) throws java.io.IOException {
		AlbumInfo result = SpaceTestCase.album(response);
		de.haumacher.imageServer.shared.util.UpdateTransient.updateTransient(result);
		return result;
	}

	/** The first look the walk at start-up takes of the given folder, see FolderPipeline#discover. */
	private void discover(String path) throws Exception {
		servlet().index().pipeline().discover(_base.resolve(path).toFile());
	}

	/** What the background of a running server does for the given folder. */
	private void record(String path) throws Exception {
		servlet().index().pipeline().process(_base.resolve(path).toFile(), FolderPipeline.Stage.RECORD);
		assertNotNull("Recorded.", AnalysisRecord.read(_base.resolve(path).toFile()));
	}

	private static Path fixture(String path) {
		return Path.of("src/test/fixtures").resolve(path);
	}

	private static void jpeg(Path file, int width, int height) throws Exception {
		Files.write(file, jpegBytes(width, height));
	}

	private static byte[] jpegBytes(int width, int height) throws Exception {
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR), "jpg", out);
		return out.toByteArray();
	}

	private static String json(AlbumInfo album) throws Exception {
		java.io.StringWriter buffer = new java.io.StringWriter();
		try (de.haumacher.msgbuf.json.JsonWriter json = new de.haumacher.msgbuf.json.JsonWriter(
			new de.haumacher.msgbuf.server.io.WriterAdapter(buffer))) {
			album.writeTo(json);
		}
		return buffer.toString();
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
}
