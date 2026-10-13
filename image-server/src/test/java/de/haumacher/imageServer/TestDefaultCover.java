/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.SpaceStore;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.cache.ResourceCache;
import de.haumacher.imageServer.pipeline.Background;
import de.haumacher.imageServer.pipeline.FolderPipeline;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.Crop;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.ThumbnailInfo;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Test case for the picture of an album that chose no cover, see issue #260.
 *
 * <p>
 * Such an album is shown by its first best-rated photograph (the rule of issue #153) whether it has
 * a sidecar or not, derived from the sidecar and the analysis record whenever a tile is described
 * and never written. Before, an album with a sidecar was shown by exactly the cover the sidecar
 * named &mdash; none &mdash; so the first save of an album without one (a star, a rating, a title)
 * took the picture off its tile for good.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestDefaultCover extends TestCase {

	private static final long TIMEOUT = 180_000;

	private static final File FIXTURE = new File("src/test/fixtures/test-album/2005-08-24 Blumen und Fliegen");

	private static final String ALBUM = "2005-08-24 Blumen und Fliegen";

	private Path _base;

	private ImageServlet _servlet;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-default-cover");
	}

	@Override
	protected void tearDown() throws Exception {
		Background.setQuietMillis(Background.QUIET_MILLIS);
		if (_servlet != null) {
			_servlet.destroy();
			_servlet = null;
		}
		try (Stream<Path> files = Files.walk(_base)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		super.tearDown();
	}

	// --- The reproductions. ---

	/**
	 * The first report: an album without a sidecar loses the picture of its tile when it is
	 * starred, i.e. saved the way the app saves it.
	 */
	public void testStarringAnAlbumWithoutSidecarKeepsItsPicture() throws Exception {
		fixture(ALBUM);
		_servlet = new ImageServlet(_base.toFile());

		FolderInfo before = onlyTile(listing("/"));
		assertNotNull("An album without a sidecar is shown by a picture.", before.getIndexPicture());
		String picture = before.getIndexPicture().getImage();
		assertEquals("Nothing rated: the picture the album shows first.", firstImage(albumNamed(ALBUM)), picture);

		String answered = get("/" + ALBUM + "/").body();
		assertNull("The album answers the cover it chose, and it chose none.", album(answered).getIndexPicture());

		// The app saves the album as it was answered, with the star set.
		assertTrue(answered, answered.contains("\"starred\":false"));
		put("/" + ALBUM + "/", answered.replace("\"starred\":false", "\"starred\":true"));

		FolderInfo after = onlyTile(listing("/"));
		assertTrue("The save took effect.", after.isStarred());
		assertNotNull("Starring keeps the picture on the tile.", after.getIndexPicture());
		assertEquals("The same picture.", picture, after.getIndexPicture().getImage());
		assertNoCoverStored(after.getName());
	}

	/** Rating a photograph saves the album, too, and makes the best-rated one the tile's picture. */
	public void testRatingAPhotoMovesThePictureAlong() throws Exception {
		fixture(ALBUM);
		_servlet = new ImageServlet(_base.toFile());
		String first = onlyTile(listing("/")).getIndexPicture().getImage();

		AlbumInfo album = album(get("/" + ALBUM + "/").body());
		List<ImagePart> images = images(album);
		ImagePart last = images.get(images.size() - 1);
		assertFalse(last.getName().equals(first));
		last.setRating(2);
		put("/" + ALBUM + "/", write(album));

		FolderInfo tile = onlyTile(listing("/"));
		assertNotNull("A rating never takes the picture off the tile.", tile.getIndexPicture());
		assertEquals("The best-rated photograph stands for the album.", last.getName(),
			tile.getIndexPicture().getImage());
		assertNoCoverStored(tile.getName());

		// Unrated again: back to the first.
		album = album(get("/" + tile.getName() + "/").body());
		for (ImagePart image : images(album)) {
			image.setRating(0);
		}
		put("/" + tile.getName() + "/", write(album));
		assertEquals(first, onlyTile(listing("/")).getIndexPicture().getImage());
	}

	/**
	 * The second report: of a year of albums, those with a sidecar that names no cover (an older
	 * tool, a face tag, a rating) were shown without a picture, however many photographs they hold.
	 */
	public void testAnAlbumWhoseSidecarChoseNoCoverShowsAPicture() throws Exception {
		fixture("2005-08-24 With sidecar");
		sidecar("2005-08-24 With sidecar", "[\"AlbumInfo\",{\"title\":\"With sidecar\"}]");
		fixture("2005-08-25 Without");
		_servlet = new ImageServlet(_base.toFile());

		ListingInfo listing = listing("/");
		ThumbnailInfo with = tile(listing, "2005-08-24 With sidecar").getIndexPicture();
		ThumbnailInfo without = tile(listing, "2005-08-25 Without").getIndexPicture();
		assertNotNull("A sidecar without a cover still shows a picture.", with);
		assertNotNull(without);
		assertEquals("One rule, sidecar or not.", without.getImage(), with.getImage());
		assertEquals(without.getScale(), with.getScale(), 1e-9);
		assertEquals(without.getTy(), with.getTy(), 1e-9);
	}

	// --- The rule. ---

	public void testAChosenCoverIsNeverOverridden() throws Exception {
		fixture(ALBUM);
		sidecar(ALBUM, "[\"AlbumInfo\",{\"title\":\"Blumen\","
			+ "\"indexPicture\":{\"image\":\"IMG_0419.JPG\",\"scale\":1.5,\"tx\":3.0,\"ty\":4.0},"
			+ "\"parts\":[[\"ImagePart\",{\"name\":\"IMG_0442.JPG\",\"rating\":3}]]}]");
		_servlet = new ImageServlet(_base.toFile());

		ThumbnailInfo cover = onlyTile(listing("/")).getIndexPicture();
		assertEquals("The author's choice, not the best-rated.", "IMG_0419.JPG", cover.getImage());
		assertEquals("Exactly as chosen.", 1.5, cover.getScale(), 1e-9);
		assertEquals(3.0, cover.getTx(), 1e-9);
		assertEquals(4.0, cover.getTy(), 1e-9);
	}

	public void testTiesGoToTheAlbumOrderAndTrashCountsAsNothing() throws Exception {
		photo("Album", "a.jpg", 400, 300);
		photo("Album", "b.jpg", 400, 300);
		photo("Album", "c.jpg", 400, 300);
		photo("Album", "d.jpg", 400, 300);
		sidecar("Album", "[\"AlbumInfo\",{\"title\":\"Album\",\"parts\":["
			+ "[\"ImagePart\",{\"name\":\"a.jpg\",\"rating\":-2}],"
			+ "[\"ImagePart\",{\"name\":\"b.jpg\",\"rating\":1}],"
			+ "[\"ImagePart\",{\"name\":\"c.jpg\",\"rating\":1}],"
			+ "[\"ImagePart\",{\"name\":\"d.jpg\"}]]}]");
		_servlet = new ImageServlet(_base.toFile());

		assertEquals("The first of the best-rated.", "b.jpg", onlyTile(listing("/")).getIndexPicture().getImage());
	}

	public void testAnAlbumOfTrashHasNoPicture() throws Exception {
		photo("Album", "a.jpg", 400, 300);
		photo("Album", "b.jpg", 400, 300);
		photo("Album", "c.jpg", 400, 300);
		photo("Album", "d.jpg", 400, 300);
		sidecar("Album", "[\"AlbumInfo\",{\"title\":\"Album\",\"parts\":["
			+ "[\"ImagePart\",{\"name\":\"a.jpg\",\"rating\":-2}],"
			+ "[\"ImagePart\",{\"name\":\"b.jpg\",\"rating\":-2}],"
			+ "[\"ImagePart\",{\"name\":\"c.jpg\",\"rating\":-2}],"
			+ "[\"ImagePart\",{\"name\":\"d.jpg\",\"rating\":-2}]]}]");
		_servlet = new ImageServlet(_base.toFile());

		assertNull("An album of trash stands for nothing, see issue #152.",
			onlyTile(listing("/")).getIndexPicture());
	}

	public void testAnAlbumOfVideosIsShownByAVideo() throws Exception {
		Files.createDirectories(_base.resolve("Videos"));
		Files.copy(new File(FIXTURE, "MVI_0450.mp4").toPath(), _base.resolve("Videos/MVI_0450.mp4"));
		sidecar("Videos", "[\"AlbumInfo\",{\"title\":\"Videos\",\"starred\":true}]");
		_servlet = new ImageServlet(_base.toFile());

		ThumbnailInfo cover = onlyTile(listing("/")).getIndexPicture();
		assertNotNull("A video stands for its album by its poster.", cover);
		assertEquals("MVI_0450.mp4", cover.getImage());
		assertNull(cover.getCrop());
	}

	/** The default picture is shown cut where its photograph is cropped, as a chosen one is. */
	public void testTheCropOfTheDefaultPictureIsHonoured() throws Exception {
		photo("Album", "a.jpg", 400, 300);
		photo("Album", "b.jpg", 400, 300);
		sidecar("Album", "[\"AlbumInfo\",{\"title\":\"Album\",\"parts\":["
			+ "[\"ImagePart\",{\"name\":\"a.jpg\"}],"
			+ "[\"ImagePart\",{\"name\":\"b.jpg\",\"width\":400,\"height\":300,\"rating\":2,"
			+ "\"crop\":{\"x\":0.5,\"y\":0.0,\"w\":0.25,\"h\":1.0}}]]}]");
		_servlet = new ImageServlet(_base.toFile());

		ThumbnailInfo cover = onlyTile(listing("/")).getIndexPicture();
		assertEquals("b.jpg", cover.getImage());
		Crop crop = cover.getCrop();
		assertNotNull("The tile asks for the photograph's region.", crop);
		assertEquals(0.5, crop.getX(), 1e-9);
		assertEquals(0.25, crop.getW(), 1e-9);
		assertEquals("Framed in the cut: 100 x 300, a portrait.", 3.0, cover.getScale(), 1e-9);
		assertEquals(ResourceCache.tilePicture(_base.resolve("Album").toFile()).getCrop().getX(), crop.getX(), 1e-9);
	}

	/** A folder of folders is shown by the picture its author chose and by no other, see #110. */
	public void testAFolderOfFoldersStillShowsOnlyAChosenPicture() throws Exception {
		fixture("Year/" + ALBUM);
		sidecar("Year/" + ALBUM, "[\"AlbumInfo\",{\"title\":\"Blumen\"}]");
		_servlet = new ImageServlet(_base.toFile());

		assertNull("No choice, no picture.", onlyTile(listing("/")).getIndexPicture());
		assertNotNull("Its album shows one.", onlyTile(listing("/Year/")).getIndexPicture());
	}

	// --- Privacy. ---

	/**
	 * A viewer is never shown a picture they may not see: the default of what they see takes the
	 * place of a default they may not, by the same rule.
	 */
	public void testAHiddenDefaultIsReplacedByTheDefaultOfWhatTheViewerSees() throws Exception {
		photo("Trip", "a.jpg", 400, 300);
		photo("Trip", "b.jpg", 400, 300);
		photo("Trip", "c.jpg", 400, 300);
		photo("Trip", "d.jpg", 400, 300);
		sidecar("Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":["
			+ "[\"ImagePart\",{\"name\":\"a.jpg\"}],"
			+ "[\"ImagePart\",{\"name\":\"b.jpg\",\"rating\":3,\"privacy\":2}],"
			+ "[\"ImagePart\",{\"name\":\"c.jpg\",\"rating\":2,\"privacy\":1}],"
			+ "[\"ImagePart\",{\"name\":\"d.jpg\",\"rating\":1}]]}]");
		UserStore store = new UserStore(_base);
		store.nameOwner("haui");
		store.store();
		AuthService auth = new AuthService(AuthMode.WRITES, _base);
		_servlet = new ImageServlet(_base.toFile(), auth);
		String token = Codes.signInAdmin(auth, "Phone", "haui").getToken();

		assertEquals("The owner sees the best-rated.", "b.jpg", onlyTile(listing("/", token, null)).getIndexPicture().getImage());
		assertEquals("A member sees the best they may see.", "c.jpg",
			onlyTile(listing("/", token, "members")).getIndexPicture().getImage());
		assertEquals("A guest sees the best public one.", "d.jpg",
			onlyTile(listing("/", null, null)).getIndexPicture().getImage());

		// Nobody is answered a cover in the album itself: none was chosen.
		assertNull(album(get("/Trip/", null, null).body()).getIndexPicture());
		assertNull(album(get("/Trip/", token, null).body()).getIndexPicture());
	}

	public void testAGuestOfAnAlbumWithoutSidecarIsShownItsPicture() throws Exception {
		fixture(ALBUM);
		UserStore store = new UserStore(_base);
		store.nameOwner("haui");
		store.store();
		_servlet = new ImageServlet(_base.toFile(), new AuthService(AuthMode.WRITES, _base));

		assertNotNull(onlyTile(listing("/", null, null)).getIndexPicture());
	}

	// --- The background. ---

	/**
	 * The tile's preview, and the large one of a starred tile (issue #241), are made by the
	 * background for the default picture, too.
	 */
	public void testTheCatchUpMakesTheLargePreviewOfAStarredDefault() throws Exception {
		Files.createDirectories(_base.resolve(UserStore.DIRECTORY_NAME));
		SpaceStore.store(_base, new SpaceStore.Config("Cover", SpaceStore.ANONYMOUS_PUBLIC, "", SpaceStore.FACES_OFF));
		File plain = photo("2023-05-01 Starred", "s1.jpg", 2400, 1600);
		File best = photo("2023-05-01 Starred", "s2.jpg", 2400, 1600);
		sidecar("2023-05-01 Starred", "[\"AlbumInfo\",{\"title\":\"Starred\",\"starred\":true,"
			+ "\"parts\":[[\"ImagePart\",{\"name\":\"s2.jpg\",\"rating\":2}]]}]");
		UserStore store = new UserStore(_base);
		store.nameOwner("haui");
		store.store();

		Background.setQuietMillis(300);
		AuthService auth = new AuthService(AuthMode.WRITES, _base);
		_servlet = new ImageServlet(_base.toFile(), auth, "", SpaceStore.load(_base, ""));
		_servlet.init();
		pipeline().setQuietMillis(200);
		pipeline().setSweepMillis(300);
		_servlet.startIndexing();
		assertTrue("The hash pass ends.", _servlet.index().awaitPass(TIMEOUT));
		assertTrue("The catch-up ends.", pipeline().awaitIdle(TIMEOUT));

		assertEquals("s2.jpg", ResourceCache.tilePicture(_base.resolve("2023-05-01 Starred").toFile()).getImage());
		assertTrue("The default picture of a starred tile is made large.",
			PreviewCache.largePreviewFile(best, null).isFile());
		assertFalse("Only the tile's picture.", PreviewCache.largePreviewFile(plain, null).exists());
		assertNoCoverStored("2023-05-01 Starred");
	}

	// --- Helpers. ---

	private FolderPipeline pipeline() {
		return _servlet.index().pipeline();
	}

	/** A copy of the fixture album (no sidecar) at the given place. */
	private void fixture(String name) throws IOException {
		Path folder = _base.resolve(name);
		Files.createDirectories(folder);
		for (File file : FIXTURE.listFiles()) {
			if (file.isFile() && !file.getName().startsWith(".")) {
				// With the fixture's own stamps: a photograph without a recorded date is dated by
				// its file, and copies made a clock tick apart would order two albums differently.
				Files.copy(file.toPath(), folder.resolve(file.getName()), StandardCopyOption.COPY_ATTRIBUTES);
			}
		}
	}

	private void sidecar(String folder, String json) throws IOException {
		Files.createDirectories(_base.resolve(folder));
		Files.write(_base.resolve(folder).resolve("index.json"), json.getBytes(StandardCharsets.UTF_8));
	}

	private File photo(String album, String name, int width, int height) throws IOException {
		Path folder = _base.resolve(album);
		Files.createDirectories(folder);
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(new Color(name.hashCode() & 0xFFFFFF));
		g.fillRect(0, 0, width, height);
		g.dispose();
		File result = folder.resolve(name).toFile();
		assertTrue(ImageIO.write(image, "jpg", result));
		return result;
	}

	/** The sidecar of the given album states no cover: the default was never written. */
	private void assertNoCoverStored(String album) throws IOException {
		File index = _base.resolve(album).resolve("index.json").toFile();
		assertTrue("The album has a sidecar now.", index.isFile());
		String stored = new String(Files.readAllBytes(index.toPath()), StandardCharsets.UTF_8);
		assertFalse("No cover is invented on disk: " + stored, stored.contains("indexPicture"));
	}

	private FakeResponse get(String pathInfo) throws Exception {
		return get(pathInfo, null, null);
	}

	private FakeResponse get(String pathInfo, String token, String viewAs) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "json");
		if (viewAs != null) {
			parameters.put("viewAs", viewAs);
		}
		FakeResponse response = new FakeResponse();
		_servlet.doGet(TestImageServletPut.request(pathInfo, null, new byte[0], headers(token), parameters),
			response.response());
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		return response;
	}

	private void put(String pathInfo, String body) throws Exception {
		FakeResponse response = new FakeResponse();
		_servlet.doPut(TestImageServletPut.request(pathInfo, "application/json", body.getBytes(StandardCharsets.UTF_8),
			Collections.emptyMap(), Collections.emptyMap()), response.response());
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
	}

	private static Map<String, String> headers(String token) {
		Map<String, String> headers = new HashMap<>();
		if (token != null) {
			headers.put("Authorization", "Bearer " + token);
		}
		return headers;
	}

	private ListingInfo listing(String pathInfo) throws Exception {
		return listing(pathInfo, null, null);
	}

	private ListingInfo listing(String pathInfo, String token, String viewAs) throws Exception {
		Resource resource = read(get(pathInfo, token, viewAs).body());
		assertTrue("Expected a listing: " + resource, resource instanceof ListingInfo);
		return (ListingInfo) resource;
	}

	private AlbumInfo albumNamed(String name) throws Exception {
		return album(get("/" + name + "/").body());
	}

	private static AlbumInfo album(String json) throws IOException {
		Resource resource = read(json);
		assertTrue("Expected an album: " + json, resource instanceof AlbumInfo);
		return (AlbumInfo) resource;
	}

	private static Resource read(String json) throws IOException {
		return Resource.readResource(new JsonReader(new ReaderAdapter(new StringReader(json))));
	}

	private static String write(AlbumInfo album) throws Exception {
		StringWriter buffer = new StringWriter();
		try (JsonWriter json = new JsonWriter(new WriterAdapter(buffer))) {
			album.writeTo(json);
		}
		return buffer.toString();
	}

	private static FolderInfo onlyTile(ListingInfo listing) {
		assertEquals("One tile: " + listing.getFolders(), 1, listing.getFolders().size());
		return listing.getFolders().get(0);
	}

	private static FolderInfo tile(ListingInfo listing, String name) {
		for (FolderInfo folder : listing.getFolders()) {
			if (folder.getName().equals(name)) {
				return folder;
			}
		}
		fail("No tile '" + name + "'.");
		return null;
	}

	private static String firstImage(AlbumInfo album) {
		return images(album).get(0).getName();
	}

	private static List<ImagePart> images(AlbumInfo album) {
		List<ImagePart> result = new ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				result.add((ImagePart) part);
			} else if (part instanceof ImageGroup) {
				result.addAll(((ImageGroup) part).getImages());
			}
		}
		return result;
	}
}
