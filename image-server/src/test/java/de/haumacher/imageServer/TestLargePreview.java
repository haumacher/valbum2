/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.SpaceStore;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.pipeline.Background;
import de.haumacher.imageServer.pipeline.FolderPipeline;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Test case for the large preview of a starred (2&times;2) tile, see issue #241:
 * <code>?type=tn&amp;size=large</code>, made on the first request and by the background catch-up of
 * issue #236 for the picture of a starred tile only.
 */
@SuppressWarnings("javadoc")
public class TestLargePreview extends TestCase {

	private static final long TIMEOUT = 180_000;

	private static final File VIDEO =
		new File("src/test/fixtures/test-album/2005-08-24 Blumen und Fliegen/MVI_0450.mp4");

	private static final String ALBUM = "Album";

	private static final String STARRED = "2023-05-01 Starred";

	private static final String PLAIN = "2022-05-01 Plain";

	private static final String CROPPED = "2021-05-01 Cropped";

	private static final Color RED = new Color(0xE0, 0x10, 0x10);

	private static final Color GREEN = new Color(0x10, 0xE0, 0x10);

	private static final Color BLUE = new Color(0x10, 0x10, 0xE0);

	private static final Color YELLOW = new Color(0xE0, 0xE0, 0x10);

	private Path _base;

	private ImageServlet _servlet;

	private String _adminToken;

	private final List<String> _made = new CopyOnWriteArrayList<>();

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-large-preview");
		PreviewCache.setHook(new PreviewCache.Hook() {
			@Override
			public void permitAcquired(File file) {
				// Nothing.
			}

			@Override
			public void generationStarted(File file) {
				_made.add(file.getName());
			}

			@Override
			public void generationFinished(File file) {
				// Nothing.
			}
		});
	}

	@Override
	protected void tearDown() throws Exception {
		PreviewCache.setHook(null);
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

	// --- The request. ---

	public void testALandscapePhotoIsTwiceAsHigh() throws Exception {
		File photo = quadrants(ALBUM, "wide.jpg", 3000, 2000);
		start(AuthMode.WRITES);

		BufferedImage normal = picture(thumbnail("wide.jpg", null, null, null));
		assertEquals(600, normal.getHeight());
		assertEquals(900, normal.getWidth());

		BufferedImage large = picture(thumbnail("wide.jpg", "large", null, null));
		assertEquals(1200, large.getHeight());
		assertEquals(1800, large.getWidth());
		assertQuadrants(large);

		File file = PreviewCache.largePreviewFile(photo, null);
		assertEquals("preview-wide.jpg-large.jpg", file.getName());
		assertEquals("Beside the preview.", PreviewCache.previewFile(photo).getParentFile(), file.getParentFile());
		assertTrue("Both are there.", file.isFile() && PreviewCache.previewFile(photo).isFile());
		assertTrue("With the record of its original.", PreviewCache.recordOf(file).isFile());
		assertEquals(PreviewCache.record(photo),
			new String(Files.readAllBytes(PreviewCache.recordOf(file).toPath()), StandardCharsets.UTF_8));
	}

	public void testAPortraitPhotoIsTwiceAsHigh() throws Exception {
		quadrants(ALBUM, "tall.jpg", 1600, 3200);
		start(AuthMode.WRITES);

		BufferedImage normal = picture(thumbnail("tall.jpg", null, null, null));
		assertEquals(1200, normal.getHeight());
		assertEquals(600, normal.getWidth());

		BufferedImage large = picture(thumbnail("tall.jpg", "large", null, null));
		assertEquals(2400, large.getHeight());
		assertEquals(1200, large.getWidth());
	}

	public void testNeverLargerThanThePicture() throws Exception {
		quadrants(ALBUM, "small.jpg", 1000, 750);
		start(AuthMode.WRITES);

		BufferedImage large = picture(thumbnail("small.jpg", "large", null, null));
		assertEquals(1000, large.getWidth());
		assertEquals(750, large.getHeight());
	}

	public void testWithoutASizeNothingChanges() throws Exception {
		quadrants(ALBUM, "wide.jpg", 3000, 2000);
		start(AuthMode.WRITES);

		FakeResponse plain = thumbnail("wide.jpg", null, null, null);
		FakeResponse normal = thumbnail("wide.jpg", "normal", null, null);
		assertEquals(HttpServletResponse.SC_OK, plain.status());
		assertEquals(HttpServletResponse.SC_OK, normal.status());
		assertTrue("The same preview, byte for byte.", Arrays.equals(plain.bodyBytes(), normal.bodyBytes()));
		assertTrue("The very file.", Arrays.equals(Files.readAllBytes(PreviewCache.previewFile(file(ALBUM, "wide.jpg"))
			.toPath()), plain.bodyBytes()));
		assertFalse("Nothing large made for a plain request.",
			PreviewCache.largePreviewFile(file(ALBUM, "wide.jpg"), null).exists());
		assertEquals("The same headers.", plain.header("Content-Type"),
			thumbnail("wide.jpg", "large", null, null).header("Content-Type"));
	}

	public void testASecondRequestServesTheCachedFile() throws Exception {
		File photo = quadrants(ALBUM, "wide.jpg", 3000, 2000);
		start(AuthMode.WRITES);

		FakeResponse first = thumbnail("wide.jpg", "large", null, null);
		File file = PreviewCache.largePreviewFile(photo, null);
		long made = file.lastModified();
		assertEquals(Arrays.asList("wide.jpg"), _made);

		FakeResponse second = thumbnail("wide.jpg", "large", null, null);
		assertEquals("Not made again.", Arrays.asList("wide.jpg"), _made);
		assertEquals(made, file.lastModified());
		assertTrue(Arrays.equals(first.bodyBytes(), second.bodyBytes()));
		assertTrue(Arrays.equals(Files.readAllBytes(file.toPath()), second.bodyBytes()));
	}

	/**
	 * Review probe: the large preview follows the photograph as the normal one does. A photograph
	 * rewritten in place (its stamp moved) gets a new large preview on the next request, sized by
	 * the new picture, and the cropped large preview is a file of its own beside the whole one.
	 */
	public void testProbeALargePreviewFollowsItsPhotograph() throws Exception {
		File photo = quadrants(ALBUM, "wide.jpg", 3000, 2000);
		start(AuthMode.WRITES);
		assertEquals(1200, picture(thumbnail("wide.jpg", "large", null, null)).getHeight());
		File whole = PreviewCache.largePreviewFile(photo, null);
		assertTrue(whole.isFile());
		assertTrue("A record beside it.", PreviewCache.recordOf(whole).isFile());

		// The photograph is rewritten smaller, its stamp moved on.
		long stamp = photo.lastModified();
		quadrants(ALBUM, "wide.jpg", 1500, 1000);
		assertTrue(photo.setLastModified(stamp + 5000));
		BufferedImage again = picture(thumbnail("wide.jpg", "large", null, null));
		assertEquals("Never larger than the new picture.", 1000, again.getHeight());
		assertEquals("Made twice: once per picture.", 2, _made.stream().filter("wide.jpg"::equals).count());

		// The cropped large preview is its own file beside the whole one.
		String region = "0.5000,0.0000,0.5000,0.5000";
		BufferedImage cropped = picture(thumbnail("wide.jpg", "large", region, _adminToken));
		assertColor(GREEN, cropped, cropped.getWidth() / 2, cropped.getHeight() / 2);
		File croppedFile = PreviewCache.largePreviewFile(photo, new double[] { 0.5, 0.0, 0.5, 0.5 });
		assertTrue(croppedFile.isFile());
		assertFalse(croppedFile.equals(whole));
		assertTrue(whole.isFile());
	}

	public void testTheCropAppliesToTheLargePreview() throws Exception {
		File photo = quadrants(ALBUM, "wide.jpg", 3000, 2000);
		start(AuthMode.WRITES);
		String region = "0.5000,0.0000,0.5000,0.5000";

		BufferedImage normal = picture(thumbnail("wide.jpg", null, region, _adminToken));
		assertEquals(600, normal.getHeight());
		assertColor(GREEN, normal, normal.getWidth() / 2, normal.getHeight() / 2);

		BufferedImage large = picture(thumbnail("wide.jpg", "large", region, _adminToken));
		// The region is 1500 x 1000 pixels: the large box (1800 x 1200) is capped by it.
		assertEquals(1000, large.getHeight());
		assertEquals(1500, large.getWidth());
		assertColor(GREEN, large, large.getWidth() / 2, large.getHeight() / 2);

		File file = PreviewCache.largePreviewFile(photo, new double[] { 0.5, 0, 0.5, 0.5 });
		assertTrue(file.getName(), file.getName().startsWith("preview-wide.jpg-large-c"));
		assertTrue(file.isFile());
		assertTrue(PreviewCache.recordOf(file).isFile());
	}

	public void testAnUnknownSizeIsRefusedWithASentence() throws Exception {
		File photo = quadrants(ALBUM, "wide.jpg", 3000, 2000);
		start(AuthMode.WRITES);

		FakeResponse huge = thumbnail("wide.jpg", "huge", null, null);
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, huge.status());
		assertTrue(huge.body(), huge.body().contains(ImageServlet.SIZE_UNKNOWN));
		assertEquals("Nothing made.", 0, _made.size());
		assertFalse(PreviewCache.previewFile(photo).exists());
	}

	public void testACallerWithoutViewIsRefusedAsForThePreview() throws Exception {
		File photo = quadrants(ALBUM, "wide.jpg", 3000, 2000);
		start(AuthMode.ALL);

		FakeResponse normal = thumbnail("wide.jpg", null, null, null);
		FakeResponse large = thumbnail("wide.jpg", "large", null, null);
		assertTrue("Refused: " + normal.status(), normal.status() >= 400);
		assertEquals(normal.status(), large.status());
		FakeResponse stranger = thumbnail("wide.jpg", "large", null, "no-such-token");
		assertEquals(thumbnail("wide.jpg", null, null, "no-such-token").status(), stranger.status());
		assertFalse("Nothing made for a refused caller.", PreviewCache.largePreviewFile(photo, null).exists());

		assertEquals(HttpServletResponse.SC_OK, thumbnail("wide.jpg", "large", null, _adminToken).status());
	}

	public void testAVideoAnswersItsPoster() throws Exception {
		Files.createDirectories(_base.resolve(ALBUM));
		Files.copy(VIDEO.toPath(), _base.resolve(ALBUM).resolve("clip.mp4"));
		start(AuthMode.WRITES);

		FakeResponse normal = thumbnail("clip.mp4", null, null, null);
		FakeResponse large = thumbnail("clip.mp4", "large", null, null);
		assertEquals(HttpServletResponse.SC_OK, large.status());
		assertTrue("A poster has one size.", Arrays.equals(normal.bodyBytes(), large.bodyBytes()));
		assertFalse(PreviewCache.largePreviewFile(file(ALBUM, "clip.mp4"), null).exists());
	}

	public void testThePurgeOfAPhotographTakesItsLargePreviews() throws Exception {
		File photo = quadrants(ALBUM, "wide.jpg", 3000, 2000);
		quadrants(ALBUM, "wide.jpg-other.jpg", 300, 200);
		PreviewCache.createLargePreview(photo, null);
		PreviewCache.createLargePreview(photo, new double[] { 0.5, 0, 0.5, 0.5 });
		File other = PreviewCache.createLargePreview(file(ALBUM, "wide.jpg-other.jpg"), null);

		List<File> generated = DeleteService.generatedOf(photo.getParentFile(), "wide.jpg");
		assertTrue(generated.toString(), generated.contains(PreviewCache.largePreviewFile(photo, null)));
		assertTrue(generated.toString(), generated.contains(PreviewCache.recordOf(PreviewCache.largePreviewFile(photo, null))));
		assertTrue(generated.toString(),
			generated.contains(PreviewCache.largePreviewFile(photo, new double[] { 0.5, 0, 0.5, 0.5 })));
		assertFalse("Another photograph's is not touched.", generated.contains(other));
		assertTrue(CacheRefresh.isGenerated(PreviewCache.largePreviewFile(photo, null).getName()));
	}

	// --- The background. ---

	public void testTheCatchUpMakesItForAStarredTileOnly() throws Exception {
		Files.createDirectories(_base.resolve(UserStore.DIRECTORY_NAME));
		SpaceStore.store(_base, new SpaceStore.Config("Large", SpaceStore.ANONYMOUS_PUBLIC, "", SpaceStore.FACES_OFF));
		File starred = quadrants(STARRED, "s1.jpg", 2400, 1600);
		File other = quadrants(STARRED, "s2.jpg", 2400, 1600);
		sidecar(STARRED, "[\"AlbumInfo\",{\"title\":\"Starred\",\"starred\":true,"
			+ "\"indexPicture\":{\"image\":\"s1.jpg\",\"scale\":1.0}}]");
		File cropped = quadrants(CROPPED, "c1.jpg", 2400, 1600);
		sidecar(CROPPED, "[\"AlbumInfo\",{\"title\":\"Cropped\",\"starred\":true,"
			+ "\"indexPicture\":{\"image\":\"c1.jpg\",\"scale\":1.0},"
			+ "\"parts\":[[\"ImagePart\",{\"name\":\"c1.jpg\",\"width\":2400,\"height\":1600,"
			+ "\"crop\":{\"x\":0.5,\"y\":0.0,\"w\":0.5,\"h\":0.5}}]]}]");
		File plain = quadrants(PLAIN, "p1.jpg", 2400, 1600);
		sidecar(PLAIN, "[\"AlbumInfo\",{\"title\":\"Plain\",\"indexPicture\":{\"image\":\"p1.jpg\",\"scale\":1.0}}]");
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
		awaitCatchUp();

		File large = PreviewCache.largePreviewFile(starred, null);
		assertTrue("The starred tile's picture is made large.", large.isFile());
		assertTrue("With its record.", PreviewCache.recordOf(large).isFile());
		assertEquals(1200, ImageIO.read(large).getHeight());
		de.haumacher.imageServer.shared.model.Crop crop =
			de.haumacher.imageServer.cache.ResourceCache.tilePicture(_base.resolve(CROPPED).toFile()).getCrop();
		assertNotNull("The cropped tile asks for its cut.", crop);
		File cut = PreviewCache.largePreviewFile(cropped,
			new double[] { crop.getX(), crop.getY(), crop.getW(), crop.getH() });
		assertTrue("A starred cropped tile's cut is made large: " + cut.getName(), cut.isFile());
		BufferedImage cutPicture = ImageIO.read(cut);
		assertEquals("The region's own 1200 x 800 pixels.", 800, cutPicture.getHeight());
		assertColor(GREEN, cutPicture, cutPicture.getWidth() / 2, cutPicture.getHeight() / 2);
		assertFalse("Only the tile's picture.", PreviewCache.largePreviewFile(other, null).exists());
		assertTrue("The others get their ordinary preview.", PreviewCache.previewFile(other).isFile());
		assertFalse("An unstarred tile gets none.", PreviewCache.largePreviewFile(plain, null).exists());
		assertTrue(PreviewCache.previewFile(plain).isFile());

		// A second pass makes nothing again.
		long stamp = large.lastModified();
		_made.clear();
		pipeline().process(_base.resolve(STARRED).toFile());
		pipeline().process(_base.resolve(PLAIN).toFile());
		assertEquals("Nothing made again.", Arrays.asList(), _made);
		assertEquals(stamp, large.lastModified());

		// A tile that gains its star gets it on the next pass; one that loses it keeps it.
		sidecar(PLAIN, "[\"AlbumInfo\",{\"title\":\"Plain\",\"starred\":true,"
			+ "\"indexPicture\":{\"image\":\"p1.jpg\",\"scale\":1.0}}]");
		sidecar(STARRED, "[\"AlbumInfo\",{\"title\":\"Starred\",\"indexPicture\":{\"image\":\"s1.jpg\",\"scale\":1.0}}]");
		pipeline().process(_base.resolve(PLAIN).toFile());
		pipeline().process(_base.resolve(STARRED).toFile());
		assertTrue("Starred now, made large.", PreviewCache.largePreviewFile(plain, null).isFile());
		assertTrue("Nothing is deleted for a lost star.", large.isFile());
		assertEquals(Arrays.asList("p1.jpg"), _made);
	}

	// --- Helpers. ---

	private void start(AuthMode mode) throws Exception {
		UserStore store = new UserStore(_base);
		store.nameOwner("haui");
		store.store();
		AuthService auth = new AuthService(mode, _base);
		_servlet = new ImageServlet(_base.toFile(), auth);
		_servlet.init();
		_adminToken = Codes.signInAdmin(auth, "Phone", "haui").getToken();
	}

	private FolderPipeline pipeline() {
		return _servlet.index().pipeline();
	}

	private void awaitCatchUp() throws Exception {
		assertTrue("The hash pass ends.", _servlet.index().awaitPass(TIMEOUT));
		assertTrue("The catch-up ends.", pipeline().awaitIdle(TIMEOUT));
	}

	private File file(String album, String name) {
		return _base.resolve(album).resolve(name).toFile();
	}

	private void sidecar(String folder, String json) throws IOException {
		Files.createDirectories(_base.resolve(folder));
		Files.write(_base.resolve(folder).resolve("index.json"), json.getBytes(StandardCharsets.UTF_8));
	}

	/** A picture of four coloured quarters: red, green / blue, yellow. */
	private File quadrants(String album, String name, int width, int height) throws IOException {
		Path folder = _base.resolve(album);
		Files.createDirectories(folder);
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(RED);
		g.fillRect(0, 0, width / 2, height / 2);
		g.setColor(GREEN);
		g.fillRect(width / 2, 0, width - width / 2, height / 2);
		g.setColor(BLUE);
		g.fillRect(0, height / 2, width / 2, height - height / 2);
		g.setColor(YELLOW);
		g.fillRect(width / 2, height / 2, width - width / 2, height - height / 2);
		g.dispose();
		File result = folder.resolve(name).toFile();
		assertTrue(ImageIO.write(image, "jpg", result));
		return result;
	}

	private FakeResponse thumbnail(String name, String size, String crop, String token) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "tn");
		if (size != null) {
			parameters.put(ImageServlet.SIZE_PARAMETER, size);
		}
		if (crop != null) {
			parameters.put(ImageServlet.CROP_PARAMETER, crop);
		}
		Map<String, String> headers = new HashMap<>();
		if (token != null) {
			headers.put("Authorization", "Bearer " + token);
		}
		FakeResponse response = new FakeResponse();
		_servlet.doGet(TestImageServletPut.request("/" + ALBUM + "/" + name, null, new byte[0], headers, parameters),
			response.response());
		return response;
	}

	private static BufferedImage picture(FakeResponse response) throws IOException {
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		BufferedImage result = ImageIO.read(new ByteArrayInputStream(response.bodyBytes()));
		assertNotNull(result);
		return result;
	}

	private static void assertQuadrants(BufferedImage image) {
		int w = image.getWidth();
		int h = image.getHeight();
		assertColor(RED, image, w / 4, h / 4);
		assertColor(GREEN, image, 3 * w / 4, h / 4);
		assertColor(BLUE, image, w / 4, 3 * h / 4);
		assertColor(YELLOW, image, 3 * w / 4, 3 * h / 4);
	}

	private static void assertColor(Color expected, BufferedImage image, int x, int y) {
		Color actual = new Color(image.getRGB(x, y));
		assertTrue("Expected " + expected + " at " + x + "," + y + ", found " + actual,
			Math.abs(expected.getRed() - actual.getRed()) < 40 && Math.abs(expected.getGreen() - actual.getGreen()) < 40
				&& Math.abs(expected.getBlue() - actual.getBlue()) < 40);
	}
}
