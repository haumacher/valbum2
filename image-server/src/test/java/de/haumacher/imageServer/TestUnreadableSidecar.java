/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.cache.AnalysisRecord;
import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.pipeline.FolderPipeline;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.FolderKind;
import de.haumacher.imageServer.shared.model.ListingInfo;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

/**
 * Test case for issue #254: an album whose own <code>index.json</code> cannot be read.
 *
 * <p>
 * The album is refused with a reason (never a <code>500</code>, never an album made up from the
 * files), every write that would store its sidecar is refused with the same reason, and the file
 * is never written over: the user's titles may be in it. The listing above shows the folder by its
 * name (issue #252), and the repaired file is read on the next request.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestUnreadableSidecar extends SpaceTestCase {

	private static final String TOKEN = SharingFixture.ALICE;

	/** The status of the refusal, which the app's failed-load page shows with its sentence. */
	private static final int REFUSED = 422;

	private static final String ALBUM = "Library/2025-08-14 Mission";

	/** A file cut off in the middle, as a crash of an editor leaves it. */
	private static final String TRUNCATED = "[\"AlbumInfo\",{\"title\":\"Our mission\",\"parts\":[";

	/** Well-formed JSON with a field of the wrong type: the reader fails with a NumberFormatException. */
	private static final String WRONG_TYPE =
		"[\"AlbumInfo\",{\"title\":\"Our mission\",\"indexPicture\":{\"scale\":\"broken\"}}]";

	@Override
	protected ImageServlet servlet() throws Exception {
		ImageServlet result = super.servlet();
		result.cache().setBackground(true);
		return result;
	}

	/** The fixtures fail as the issue says, through the reader the cache reads with. */
	public void testTheFixturesFailAsReported() throws Exception {
		jpeg(ALBUM, "a.jpg");
		Path sidecar = sidecar(TRUNCATED);
		try {
			ResourceCache.readSidecar(sidecar.getParent().toFile());
			fail("Truncated.");
		} catch (ResourceCache.UnreadableSidecar ex) {
			assertTrue(ex.getCause().toString(), ex.getCause() instanceof java.io.IOException);
		}
		sidecar(WRONG_TYPE);
		try {
			ResourceCache.readSidecar(sidecar.getParent().toFile());
			fail("Wrong type.");
		} catch (ResourceCache.UnreadableSidecar ex) {
			assertTrue(ex.getCause().toString(), ex.getCause() instanceof NumberFormatException);
		}
		assertNull("Lenient where only a look is taken.", ResourceCache.sidecar(sidecar.getParent().toFile()));
	}

	/** A truncated sidecar: the album is refused with the reason, not answered 500. */
	public void testATruncatedSidecarIsRefusedWithItsReason() throws Exception {
		assertRefusedWithReason(TRUNCATED);
	}

	/** A field of the wrong type (a NumberFormatException, which escaped before): the same refusal. */
	public void testAWrongTypedFieldIsRefusedWithItsReason() throws Exception {
		assertRefusedWithReason(WRONG_TYPE);
	}

	private void assertRefusedWithReason(String contents) throws Exception {
		jpeg(ALBUM, "a.jpg");
		Path sidecar = sidecar(contents);
		servlet();
		record(ALBUM);

		FakeResponse album = get("/" + ALBUM + "/", "json", TOKEN);
		assertEquals(album.body(), REFUSED, album.status());
		String message = errorMessage(album);
		assertTrue(message, message.startsWith("index.json of this album cannot be read: "));
		assertTrue(message, message.endsWith(" — repair or remove it"));
		assertTrue("The reason is said: " + message,
			message.length() > "index.json of this album cannot be read:  — repair or remove it".length());

		// A photograph of it: whose privacy cannot be read is not shown.
		FakeResponse photo = get("/" + ALBUM + "/a.jpg", "json", TOKEN);
		assertEquals(photo.body(), REFUSED, photo.status());
		assertEquals(message, errorMessage(photo));
		assertEquals(REFUSED, get("/" + ALBUM + "/a.jpg", "tn", TOKEN).status());

		// The listing above is answered and shows it by its name.
		ListingInfo listing = listing(get("/Library/", "json", TOKEN));
		FolderInfo tile = entry(listing, "2025-08-14 Mission");
		assertEquals("Mission", tile.getTitle());
		assertEquals(FolderKind.ALBUM, tile.getKind());
		assertNull("Nothing out of it is shown.", tile.getIndexPicture());

		assertEquals("Read, never written.", contents, read(sidecar));
	}

	/** Neither the album's PUT nor a move in or out nor an upload writes over what could not be read. */
	public void testEveryWriteIsRefusedAndTheFileStaysAsItIs() throws Exception {
		jpeg(ALBUM, "a.jpg");
		jpeg("Library/Other", "b.jpg");
		Path sidecar = sidecar(TRUNCATED);
		servlet();
		record(ALBUM);
		record("Library/Other");
		String message = errorMessage(get("/" + ALBUM + "/", "json", TOKEN));

		FakeResponse put = put("/" + ALBUM + "/", "[\"AlbumInfo\",{\"title\":\"Overwritten\"}]", TOKEN);
		assertEquals(put.body(), REFUSED, put.status());
		assertEquals(message, errorMessage(put));

		FakeResponse moveIn = move("/Library/Other/", "Library/2025-08-14 Mission", TOKEN, "b.jpg");
		assertEquals(moveIn.body(), REFUSED, moveIn.status());
		assertEquals(message, errorMessage(moveIn));
		assertTrue("Nothing moved.", Files.isRegularFile(_base.resolve("Library/Other/b.jpg")));
		assertFalse(Files.exists(_base.resolve(ALBUM).resolve("b.jpg")));

		FakeResponse moveOut = move("/" + ALBUM + "/", "Library/Other", TOKEN, "a.jpg");
		assertEquals(moveOut.body(), REFUSED, moveOut.status());
		assertTrue("Nothing moved.", Files.isRegularFile(_base.resolve(ALBUM).resolve("a.jpg")));

		FakeResponse uploaded = upload("/" + ALBUM + "/", TOKEN, "up.jpg", jpegBytes());
		assertEquals(uploaded.body(), REFUSED, uploaded.status());
		assertEquals(message, errorMessage(uploaded));
		assertFalse("Nothing stored.", Files.exists(_base.resolve(ALBUM).resolve("up.jpg")));

		java.util.Map<String, String> parameters = new java.util.HashMap<>();
		parameters.put("action", "delete");
		FakeResponse deleted =
			post("/" + ALBUM + "/", "{\"target\":\"\",\"names\":[{\"name\":\"a.jpg\"}]}", TOKEN, parameters);
		assertEquals(deleted.body(), REFUSED, deleted.status());
		assertEquals(message, errorMessage(deleted));
		assertTrue("Nothing deleted.", Files.isRegularFile(_base.resolve(ALBUM).resolve("a.jpg")));

		assertEquals("Never written over.", TRUNCATED, read(sidecar));
		assertEquals("No backup either: nothing was written.", Arrays.asList("index.json"), sidecars(ALBUM));
	}

	/**
	 * The background goes on recording the folder (its own file, not the sidecar), and nothing it
	 * does writes the sidecar.
	 */
	public void testTheBackgroundRecordsButNeverWritesTheSidecar() throws Exception {
		jpeg(ALBUM, "a.jpg");
		Path sidecar = sidecar(WRONG_TYPE);
		servlet();
		for (FolderPipeline.Stage stage : FolderPipeline.Stage.values()) {
			servlet().index().pipeline().process(_base.resolve(ALBUM).toFile(), stage);
		}
		assertNotNull("Recorded.", AnalysisRecord.read(_base.resolve(ALBUM).toFile()));
		assertEquals(WRONG_TYPE, read(sidecar));
		assertEquals(Arrays.asList("index.json"), sidecars(ALBUM));
	}

	/**
	 * An album answered and held by the cache, then broken by hand, is refused; repaired, it is
	 * answered from the repaired file.
	 */
	public void testARepairedSidecarIsReadAgain() throws Exception {
		jpeg(ALBUM, "a.jpg");
		Path sidecar = sidecar("[\"AlbumInfo\",{\"title\":\"Before\"}]");
		servlet();
		record(ALBUM);
		assertEquals("Before", album(get("/" + ALBUM + "/", "json", TOKEN)).getTitle());

		write(sidecar, TRUNCATED);
		assertEquals(REFUSED, get("/" + ALBUM + "/", "json", TOKEN).status());
		assertEquals("Still refused: a refusal is never held.", REFUSED,
			get("/" + ALBUM + "/", "json", TOKEN).status());

		write(sidecar, "[\"AlbumInfo\",{\"title\":\"Repaired\"}]");
		AlbumInfo album = album(get("/" + ALBUM + "/", "json", TOKEN));
		assertEquals("Repaired", album.getTitle());
		assertEquals(Arrays.asList("a.jpg"),
			album.getParts().stream().map(part -> ((de.haumacher.imageServer.shared.model.ImagePart) part).getName())
				.collect(Collectors.toList()));
		assertEquals("Repaired", entry(listing(get("/Library/", "json", TOKEN)), "2025-08-14 Mission").getTitle());

		// And writable again.
		FakeResponse put = put("/" + ALBUM + "/", "[\"AlbumInfo\",{\"title\":\"Written\"}]", TOKEN);
		assertEquals(put.body(), 200, put.status());
	}

	/** A removed sidecar makes the album one the server describes by itself. */
	public void testARemovedSidecarMakesAnOrdinaryAlbum() throws Exception {
		jpeg(ALBUM, "a.jpg");
		Path sidecar = sidecar(TRUNCATED);
		servlet();
		record(ALBUM);
		assertEquals(REFUSED, get("/" + ALBUM + "/", "json", TOKEN).status());

		Files.delete(sidecar);
		assertEquals("Mission", album(get("/" + ALBUM + "/", "json", TOKEN)).getTitle());
	}

	/**
	 * The duplicates of the library: a copy in the unreadable album is left out (whose privacy
	 * cannot be read is shown to nobody), the answer is given.
	 */
	public void testTheDuplicatesSkipTheUnreadableAlbum() throws Exception {
		byte[] photo = jpegBytes();
		for (String folder : List.of("Library/Trip", "Library/Best", ALBUM)) {
			Files.write(Files.createDirectories(_base.resolve(folder)).resolve("same.jpg"), photo);
		}
		servlet();
		for (String folder : List.of("Library/Trip", "Library/Best", ALBUM)) {
			record(folder);
		}
		servlet().index().indexNow();
		Path sidecar = sidecar(TRUNCATED);

		FakeResponse response = get("/", ImageServlet.DUPLICATES_TYPE, TOKEN);
		assertEquals(response.body(), 200, response.status());
		de.haumacher.imageServer.shared.model.DuplicateList list =
			de.haumacher.imageServer.shared.model.DuplicateList.readDuplicateList(reader(response.body()));
		assertEquals(1, list.getGroups().size());
		List<String> albums = list.getGroups().get(0).getCopies().stream()
			.map(de.haumacher.imageServer.shared.model.DuplicateCopy::getAlbum).sorted().collect(Collectors.toList());
		assertEquals(Arrays.asList("Library/Best", "Library/Trip"), albums);
		assertEquals(TRUNCATED, read(sidecar));
	}

	/** The inbox, unreadable: what the app asks first is answered all the same, without its count. */
	public void testAnUnreadableInboxNeverFailsTheSignIn() throws Exception {
		Path inbox = Files.createDirectories(_base.resolve(de.haumacher.imageServer.auth.SpaceStore.DEFAULT_INBOX));
		Files.write(inbox.resolve("index.json"), TRUNCATED.getBytes(StandardCharsets.UTF_8));
		FakeResponse auth = get("/", "auth", TOKEN);
		assertEquals(auth.body(), 200, auth.status());
		assertEquals(TRUNCATED, read(inbox.resolve("index.json")));
	}

	// --- Helpers. ---

	private Path sidecar(String contents) throws Exception {
		Path file = Files.createDirectories(_base.resolve(ALBUM)).resolve("index.json");
		write(file, contents);
		return file;
	}

	/** Writes the file so that its modification time surely differs from the last one. */
	private static void write(Path file, String contents) throws Exception {
		long before = Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : 0L;
		Files.write(file, contents.getBytes(StandardCharsets.UTF_8));
		Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(
			Math.max(System.currentTimeMillis(), before + 2000)));
	}

	private static String read(Path file) throws Exception {
		return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
	}

	private List<String> sidecars(String folder) throws Exception {
		try (Stream<Path> files = Files.list(_base.resolve(folder))) {
			return files.map(file -> file.getFileName().toString()).filter(name -> name.startsWith("index"))
				.sorted().collect(Collectors.toList());
		}
	}

	/** What the background of a running server does for the given folder and the one above it. */
	private void record(String path) throws Exception {
		servlet().index().pipeline().process(_base.resolve(path).toFile(), FolderPipeline.Stage.RECORD);
		servlet().index().pipeline().process(_base.resolve(path).getParent().toFile(), FolderPipeline.Stage.RECORD);
	}

	private void jpeg(String folder, String name) throws Exception {
		Path dir = Files.createDirectories(_base.resolve(folder));
		Files.write(dir.resolve(name), jpegBytes());
	}

	private static byte[] jpegBytes() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(4, 3, BufferedImage.TYPE_3BYTE_BGR), "jpg", out);
		return out.toByteArray();
	}
}
