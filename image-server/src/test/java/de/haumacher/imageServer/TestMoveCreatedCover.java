/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.MoveOutcome;
import de.haumacher.imageServer.shared.model.MoveResult;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.ThumbnailInfo;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Test case for the cover of an album a move creates, see issues #273 and #277.
 *
 * <p>
 * An album the folder picker created for the move (the request says so) is covered by the first
 * best-rated photograph that landed if its rating is above normal, else by the photograph the move
 * was opened on, else by the first that landed in date order. Adding photographs to an album that
 * existed before never touches its cover. Driven through the servlet's move endpoint, the sidecar
 * read back from disk.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestMoveCreatedCover extends TestCase {

	private Path _base;

	private ImageServlet _servlet;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-move-cover");
		_servlet = new ImageServlet(_base.toFile(), new AuthService(AuthMode.OFF, _base));
		_servlet.init();
	}

	@Override
	protected void tearDown() throws Exception {
		_servlet.destroy();
		try (Stream<Path> files = Files.walk(_base)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		super.tearDown();
	}

	/**
	 * An inbox of five photographs; the third is a portrait, the fourth the earliest. The second is
	 * rated as given, every other one is normal.
	 */
	private void inboxOfFive(int secondRating) throws IOException {
		image("Inbox/p1.jpg", 8, 6, Color.RED);
		image("Inbox/p2.jpg", 8, 6, Color.GREEN);
		image("Inbox/p3.jpg", 6, 8, Color.BLUE);
		image("Inbox/p4.jpg", 8, 6, Color.YELLOW);
		image("Inbox/p5.jpg", 8, 6, Color.CYAN);
		sidecar("Inbox", "[\"AlbumInfo\",{\"kind\":\"INBOX\",\"title\":\"Inbox\",\"parts\":["
			+ part("p1.jpg", 8, 6, "\"date\":3000") + ","
			+ part("p2.jpg", 8, 6, "\"date\":2000,\"rating\":" + secondRating) + ","
			+ part("p3.jpg", 6, 8, "\"date\":5000") + "," + part("p4.jpg", 8, 6, "\"date\":1000") + ","
			+ part("p5.jpg", 8, 6, "\"date\":4000") + "]}]");
	}

	/** The picker's "Create new album…" (#114): an empty album written by the sidecar PUT. */
	private void newAlbum(String name) throws Exception {
		FakeResponse created = put("/" + name + "/", "[\"AlbumInfo\",{\"title\":\"" + name + "\",\"parts\":[]}]");
		assertTrue("Creating failed: " + created.body(), created.status() < 300);
		assertNull("A new album has no cover.", album(name).getIndexPicture());
	}

	private static final String[] ALL = { "p1.jpg", "p2.jpg", "p3.jpg", "p4.jpg", "p5.jpg" };

	// --- An album created for the move. ---

	public void testABestRatingAboveNormalWinsOverTheHint() throws Exception {
		inboxOfFive(2);
		newAlbum("New");

		moveIntoCreated("/Inbox/", "New", "p3.jpg", ALL);

		assertEquals("The first best-rated photo, its rating above normal.", "p2.jpg",
			album("New").getIndexPicture().getImage());
	}

	public void testAllNormalTheHintedPhotoCovers() throws Exception {
		inboxOfFive(0);
		newAlbum("New");

		MoveResult result = moveIntoCreated("/Inbox/", "New", "p3.jpg", ALL);

		assertEquals(Arrays.asList(ALL), newNames(result));
		ThumbnailInfo cover = album("New").getIndexPicture();
		assertEquals("The photo the menu was opened on.", "p3.jpg", cover.getImage());
		assertEquals("Framed like a default cover: a portrait fills the square.", 8.0 / 6, cover.getScale(), 1e-9);
		assertEquals((8.0 - 6) / 8 * 150, cover.getTy(), 1e-9);
		assertEquals(0.0, cover.getTx(), 1e-9);
	}

	public void testAllNormalWithoutAHintTheEarliestCovers() throws Exception {
		inboxOfFive(0);
		newAlbum("New");

		// The selection bar's move into a new album: no photo under the pointer, no hint.
		moveIntoCreated("/Inbox/", "New", "", ALL);

		assertEquals("The first photo in date order, not the first one named.", "p4.jpg",
			album("New").getIndexPicture().getImage());
	}

	public void testARatingBelowNormalIsNoBestRatingAndTheTrashCoversNothing() throws Exception {
		inboxOfFive(-1);
		// The earliest photo is in the trash.
		sidecar("Inbox", read(_base.resolve("Inbox/index.json")).replace("\"date\":1000", "\"date\":1000,\"rating\":-2"));
		newAlbum("New");

		moveIntoCreated("/Inbox/", "New", "", ALL);

		assertEquals("The earliest that is not trashed; a rating below normal still counts.", "p2.jpg",
			album("New").getIndexPicture().getImage());
	}

	public void testAHintNamingNothingThatMovedIsIgnored() throws Exception {
		inboxOfFive(0);
		newAlbum("New");

		moveIntoCreated("/Inbox/", "New", "p4.jpg", "p1.jpg", "p3.jpg");

		assertEquals("The earliest of what landed.", "p1.jpg", album("New").getIndexPicture().getImage());
	}

	public void testTheCoverFollowsTheNameTheHintedPhotoLandedWith() throws Exception {
		inboxOfFive(0);
		image("New/p3.jpg", 12, 9, Color.MAGENTA);
		sidecar("New", "[\"AlbumInfo\",{\"title\":\"New\",\"parts\":[" + part("p3.jpg", 12, 9, "") + "]}]");

		MoveResult result = moveIntoCreated("/Inbox/", "New", "p3.jpg", "p2.jpg", "p3.jpg");

		assertEquals(Arrays.asList("p2.jpg", "p3-2.jpg"), newNames(result));
		assertEquals("The hinted photo under the name it landed with, not the one that was there.", "p3-2.jpg",
			album("New").getIndexPicture().getImage());
	}

	public void testAHintOnASetAsideDuplicateCoversWithTheCopyThatLanded() throws Exception {
		byte[] same = jpeg(8, 6, Color.ORANGE);
		write("Inbox/x.jpg", same);
		write("Inbox/x-copy.jpg", same);
		image("Inbox/y.jpg", 8, 6, Color.PINK);
		sidecar("Inbox", "[\"AlbumInfo\",{\"kind\":\"INBOX\",\"title\":\"Inbox\",\"parts\":["
			+ part("y.jpg", 8, 6, "") + "," + part("x.jpg", 8, 6, "") + "," + part("x-copy.jpg", 8, 6, "") + "]}]");
		newAlbum("New");

		MoveResult result = moveIntoCreated("/Inbox/", "New", "x-copy.jpg", "y.jpg", "x.jpg", "x-copy.jpg");

		assertEquals(Arrays.asList("y.jpg", "x.jpg", "x.jpg"), newNames(result));
		assertEquals(MoveService.duplicate("x.jpg"), result.getOutcomes().get(2).getMessage());
		assertFalse("The duplicate was set aside.", _base.resolve("New/x-copy.jpg").toFile().exists());
		assertEquals("The copy that landed stands for the one set aside.", "x.jpg",
			album("New").getIndexPicture().getImage());
	}

	public void testTheCropOfTheCoverIsHonoured() throws Exception {
		image("Inbox/a.jpg", 8, 6, Color.RED);
		image("Inbox/b.jpg", 8, 6, Color.GREEN);
		sidecar("Inbox", "[\"AlbumInfo\",{\"kind\":\"INBOX\",\"title\":\"Inbox\",\"parts\":["
			+ part("a.jpg", 8, 6, "") + ","
			+ part("b.jpg", 8, 6, "\"crop\":{\"x\":0.25,\"y\":0.0,\"w\":0.5,\"h\":1.0}") + "]}]");
		newAlbum("New");

		moveIntoCreated("/Inbox/", "New", "b.jpg", "a.jpg", "b.jpg");

		ThumbnailInfo cover = album("New").getIndexPicture();
		assertEquals("b.jpg", cover.getImage());
		// The 8x6 photograph cut to its left-right half is a 4x6 portrait, and is framed as one.
		assertEquals(6.0 / 4, cover.getScale(), 1e-9);
		assertEquals((6.0 - 4) / 6 * 150, cover.getTy(), 1e-9);
		assertNull("The region of the crop is derived on every read, never stored.", cover.getCrop());

		// The tile of the album shows the cut rendition.
		ThumbnailInfo tile = tile("New");
		assertEquals("b.jpg", tile.getImage().substring(tile.getImage().lastIndexOf('/') + 1));
		assertNotNull("The tile carries the region of the crop.", tile.getCrop());
		assertEquals(0.25, tile.getCrop().getX(), 1e-9);
		assertEquals(0.5, tile.getCrop().getW(), 1e-9);
	}

	public void testTheCoverSurvivesAReadWriteRoundTrip() throws Exception {
		inboxOfFive(0);
		newAlbum("New");
		moveIntoCreated("/Inbox/", "New", "p3.jpg", "p1.jpg", "p3.jpg", "p5.jpg");

		String stored = read(_base.resolve("New/index.json"));
		// What the GET answers is written back as the app's editor does; the album's derived date
		// is answered and never stored.
		FakeResponse written = put("/New/", withoutRights(getJson("/New/")));
		assertTrue("Writing back failed: " + written.body(), written.status() < 300);

		ThumbnailInfo reread = album("New").getIndexPicture();
		assertEquals("p3.jpg", reread.getImage());
		assertEquals(8.0 / 6, reread.getScale(), 1e-9);
		assertEquals((8.0 - 6) / 8 * 150, reread.getTy(), 1e-9);
		assertEquals(withoutRights(stored), withoutRights(read(_base.resolve("New/index.json"))));
	}

	public void testACreatedTargetWithALiveCoverKeepsIt() throws Exception {
		inboxOfFive(2);
		image("New/t.jpg", 12, 9, Color.MAGENTA);
		sidecar("New", "[\"AlbumInfo\",{\"title\":\"New\","
			+ "\"indexPicture\":{\"image\":\"t.jpg\",\"scale\":2.0,\"tx\":10.0,\"ty\":5.0},"
			+ "\"parts\":[" + part("t.jpg", 12, 9, "") + "]}]");

		moveIntoCreated("/Inbox/", "New", "p3.jpg", ALL);

		assertEquals("A choice is never overridden.", "t.jpg", album("New").getIndexPicture().getImage());
	}

	// --- An album that existed before. ---

	public void testAddingToAnAlbumWithoutCoverWritesNone() throws Exception {
		inboxOfFive(2);
		image("Trip/t.jpg", 12, 9, Color.MAGENTA);
		sidecar("Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[" + part("t.jpg", 12, 9, "") + "]}]");

		moveIntoExisting("/Inbox/", "Trip", ALL);

		assertNull("The album goes on being shown by its default (#260).", album("Trip").getIndexPicture());
		assertEquals("The tile shows the default of what the album holds now.", "p2.jpg",
			tile("Trip").getImage().substring(tile("Trip").getImage().lastIndexOf('/') + 1));
	}

	public void testAddingToAnAlbumWithACoverKeepsIt() throws Exception {
		inboxOfFive(2);
		image("Trip/t.jpg", 12, 9, Color.MAGENTA);
		sidecar("Trip", "[\"AlbumInfo\",{\"title\":\"Trip\","
			+ "\"indexPicture\":{\"image\":\"t.jpg\",\"scale\":2.0,\"tx\":10.0,\"ty\":5.0},"
			+ "\"parts\":[" + part("t.jpg", 12, 9, "") + "]}]");

		moveIntoExisting("/Inbox/", "Trip", "p2.jpg", "p3.jpg");

		ThumbnailInfo cover = album("Trip").getIndexPicture();
		assertEquals("t.jpg", cover.getImage());
		assertEquals(2.0, cover.getScale(), 1e-9);
		assertEquals(10.0, cover.getTx(), 1e-9);
		assertEquals(5.0, cover.getTy(), 1e-9);
	}

	// --- Helpers. ---

	/** The JSON of the given folder without the rights the server derives on every read. */
	private static String withoutRights(String json) {
		return json.replaceAll("\"rights\":\\[[^\\]]*\\],", "");
	}

	/**
	 * Moves the given names into a target the dialog created for them.
	 *
	 * @param cover
	 *        The photo the move was opened on (issue #273), empty for none.
	 */
	private MoveResult moveIntoCreated(String pathInfo, String target, String cover, String... names)
			throws Exception {
		return move(pathInfo, target, ",\"created\":{\"cover\":\"" + cover + "\"}", names);
	}

	/** Moves the given names into a target that existed before: the request says nothing more. */
	private MoveResult moveIntoExisting(String pathInfo, String target, String... names) throws Exception {
		return move(pathInfo, target, "", names);
	}

	private MoveResult move(String pathInfo, String target, String extra, String... names) throws Exception {
		String body = "{\"target\":\"" + target + "\",\"names\":["
			+ Arrays.stream(names).map(n -> "{\"name\":\"" + n + "\"}").collect(Collectors.joining(",")) + "]"
			+ extra + "}";
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "move");
		FakeResponse response = new FakeResponse();
		_servlet.doPost(request(pathInfo, "application/json", body, parameters), response.response());
		assertEquals("The move failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return MoveResult.readMoveResult(reader(response.body()));
	}

	private FakeResponse put(String pathInfo, String body) throws Exception {
		FakeResponse response = new FakeResponse();
		_servlet.doPut(request(pathInfo, "application/json", body, Collections.emptyMap()), response.response());
		return response;
	}

	private String getJson(String pathInfo) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "json");
		FakeResponse response = new FakeResponse();
		_servlet.doGet(request(pathInfo, null, "", parameters), response.response());
		assertEquals("Reading failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return response.body();
	}

	/** The cover the tile of the given folder shows in the listing of the space root. */
	private ThumbnailInfo tile(String folder) throws Exception {
		Resource root = Resource.readResource(reader(getJson("/")));
		assertTrue("Expected a listing, got: " + root, root instanceof ListingInfo);
		for (FolderInfo info : ((ListingInfo) root).getFolders()) {
			if (info.getName().equals(folder)) {
				assertNotNull("The tile of " + folder + " shows a picture.", info.getIndexPicture());
				return info.getIndexPicture();
			}
		}
		throw new AssertionError("No tile for " + folder);
	}

	private static jakarta.servlet.http.HttpServletRequest request(String pathInfo, String contentType, String body,
			Map<String, String> parameters) {
		Map<String, String> headers = new HashMap<>();
		if (contentType != null) {
			headers.put("Content-Type", contentType);
		}
		return TestImageServletPut.request(pathInfo, contentType, body.getBytes(StandardCharsets.UTF_8), headers,
			parameters);
	}

	private AlbumInfo album(String folder) throws IOException {
		File index = _base.resolve(folder + "/index.json").toFile();
		assertTrue("Expected a sidecar at " + index, index.exists());
		FolderResource resource = FolderResource.readFolderResource(reader(read(index.toPath())));
		assertTrue("Expected an album in " + folder + ", got: " + resource, resource instanceof AlbumInfo);
		return (AlbumInfo) resource;
	}

	private static List<String> newNames(MoveResult result) {
		return result.getOutcomes().stream().map(MoveOutcome::getNewName).collect(Collectors.toList());
	}

	private static JsonReader reader(String contents) {
		return new JsonReader(new ReaderAdapter(new StringReader(contents)));
	}

	private static String read(Path file) throws IOException {
		return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
	}

	private static String part(String name, int width, int height, String extra) {
		return "[\"ImagePart\",{\"name\":\"" + name + "\",\"kind\":\"IMAGE\",\"width\":" + width + ",\"height\":"
			+ height + (extra.isEmpty() ? "" : "," + extra) + "}]";
	}

	private void sidecar(String folder, String contents) throws IOException {
		Path path = _base.resolve(folder);
		Files.createDirectories(path);
		Files.write(path.resolve("index.json"), contents.getBytes(StandardCharsets.UTF_8));
	}

	private void image(String relativePath, int width, int height, Color color) throws IOException {
		write(relativePath, jpeg(width, height, color));
	}

	private void write(String relativePath, byte[] contents) throws IOException {
		Path path = _base.resolve(relativePath);
		Files.createDirectories(path.getParent());
		Files.write(path, contents);
	}

	private static byte[] jpeg(int width, int height, Color color) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		graphics.setColor(color);
		graphics.fillRect(0, 0, width, height);
		graphics.dispose();
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		ImageIO.write(image, "jpg", buffer);
		return buffer.toByteArray();
	}
}
