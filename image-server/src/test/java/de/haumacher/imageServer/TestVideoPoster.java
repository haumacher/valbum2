/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.auth.UserStore.Device;
import de.haumacher.imageServer.auth.UserStore.User;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.ThumbnailInfo;
import de.haumacher.imageServer.shared.model.VideoPoster;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;
import test.de.haumacher.valbum.GenerateTestAlbum;

/**
 * The poster of a video chosen at a moment of it, see issue #285: the frame of that moment (not the
 * key frame before it, not the first frame), how the moment is stored, who may choose it, and that
 * another moment is another picture.
 *
 * <p>
 * The video is generated: <code>clip.mp4</code> shows one solid colour per second for five
 * seconds &mdash; red, green, blue, yellow, magenta &mdash; with a single key frame at its start, so
 * the colour of a poster says which second it was drawn from, and a poster drawn from the key frame
 * a plain seek lands on is red. The stored form written before the field existed is the fixture
 * <code>src/test/fixtures/poster/index.json</code>.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestVideoPoster extends TestCase {

	private static final File FIXTURE = new File("src/test/fixtures/poster/index.json");

	private static final String ALBUM = "Poster";

	private static final String VIDEO = "clip.mp4";

	private static final String PHOTO = "photo.jpg";

	private static final String ALICE_TOKEN = "alice-token";

	private static final String DAVE_TOKEN = "dave-token";

	private static final Color[] SECONDS = { new Color(0xE0, 0x10, 0x10), new Color(0x10, 0xE0, 0x10),
		new Color(0x10, 0x10, 0xE0), new Color(0xE0, 0xE0, 0x10), new Color(0xE0, 0x10, 0xE0) };

	private Path _base;

	private File _album;

	private File _video;

	private ImageServlet _servlet;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-poster");
		_album = new File(_base.toFile(), ALBUM);
		assertTrue(_album.mkdir());
		_video = new File(_album, VIDEO);
		GenerateTestAlbum.recordColourClip(_video, SECONDS);
		BufferedImage photo = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
		assertTrue(ImageIO.write(photo, "jpg", new File(_album, PHOTO)));

		UserStore store = new UserStore(_base);
		store.nameOwner("haui");
		store.addUser(user("alice", Roles.EDIT, ALICE_TOKEN));
		store.addUser(user("dave", Roles.VIEW, DAVE_TOKEN));
		store.store();
		AuthService auth = new AuthService(AuthMode.WRITES, _base);
		_servlet = new ImageServlet(_base.toFile(), auth);
		_servlet.init();
	}

	private static User user(String name, String role, String token) {
		User result = new User(name, role, "", Instant.now().toString());
		result.addDevice(new Device(name + "'s device", UserStore.hash(token), Instant.now().toString()));
		return result;
	}

	@Override
	protected void tearDown() throws Exception {
		if (_servlet != null) {
			_servlet.destroy();
		}
		try (Stream<Path> files = Files.walk(_base)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		super.tearDown();
	}

	/** Probe: a video moved to another album keeps the moment chosen for it, and its tile there. */
	public void testTheMomentTravelsWithAMovedVideo() throws Exception {
		File other = new File(_base.toFile(), "Other");
		assertTrue(other.mkdir());
		assertTrue(ImageIO.write(new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB), "jpg",
			new File(other, "other.jpg")));
		poster(VIDEO, 3200, ALICE_TOKEN);

		FakeResponse moved = new FakeResponse();
		_servlet.doPost(TestImageServletPut.request("/" + ALBUM + "/", "application/json",
			("{\"target\":\"Other\",\"names\":[{\"name\":\"" + VIDEO + "\"}]}").getBytes(StandardCharsets.UTF_8),
			headers(ALICE_TOKEN), params("action", "move")), moved.response());
		assertEquals(moved.body(), HttpServletResponse.SC_OK, moved.status());

		FakeResponse there = get("/Other/", params("type", "json"), ALICE_TOKEN);
		AlbumInfo album = (AlbumInfo) resource(there.body());
		ImagePart video = image(album, VIDEO);
		assertNotNull("The moment went along with the video.", video.getPoster());
		assertEquals(3200, video.getPoster().getMillis());

		FakeResponse tile = get("/Other/" + VIDEO, params("type", "tn", "poster", "3200"), ALICE_TOKEN);
		assertEquals(tile.body(), HttpServletResponse.SC_OK, tile.status());
		assertColour(SECONDS[3], ImageIO.read(new ByteArrayInputStream(tile.bodyBytes())));
	}

	// --- The frame. ---

	public void testThePosterIsTheFrameOfItsMoment() throws Exception {
		assertColour(SECONDS[0], ImageIO.read(PreviewCache.createPreview(_video)));
		for (int second = 1; second < SECONDS.length; second++) {
			File poster = PreviewCache.createPoster(_video, second * 1000L + 200);
			assertEquals("preview-clip.mp4-p" + (second * 1000L + 200) + ".jpg", poster.getName());
			BufferedImage picture = ImageIO.read(poster);
			assertEquals(160, picture.getWidth());
			assertEquals(120, picture.getHeight());
			assertColour("The frame of second " + second + ", not the key frame before it.", SECONDS[second],
				picture);
		}
		assertColour("Without a moment the poster is the first frame, as before.", SECONDS[0],
			ImageIO.read(PreviewCache.createPoster(_video, 0)));
		assertEquals(PreviewCache.previewFile(_video), PreviewCache.posterFile(_video, 0));
	}

	public void testAMomentPastTheLastFrameIsTheLastFrame() throws Exception {
		assertColour(SECONDS[4], ImageIO.read(PreviewCache.createPoster(_video, 5300)));
	}

	public void testThePostersAreDroppedWithTheirVideo() throws Exception {
		File poster = PreviewCache.createPoster(_video, 3200);
		File first = PreviewCache.createPreview(_video);
		assertTrue(DeleteService.generatedOf(_album, VIDEO).contains(poster));
		PreviewCache.drop(_video);
		assertFalse(poster.exists());
		assertFalse(first.exists());
	}

	// --- The stored form. ---

	public void testASidecarFromBeforeReadsAndWritesBackWithoutAPoster() throws Exception {
		Files.copy(FIXTURE.toPath(), new File(_album, "index.json").toPath(), StandardCopyOption.REPLACE_EXISTING);

		AlbumInfo album = album(ALICE_TOKEN);
		assertNull(image(album, VIDEO).getPoster());
		assertNull(image(album, PHOTO).getPoster());

		assertEquals(HttpServletResponse.SC_OK, put(write(album), ALICE_TOKEN).status());
		String stored = read(new File(_album, "index.json"));
		assertFalse(stored, stored.contains("\"poster\""));
		assertEquals(write(album), write(album(ALICE_TOKEN)));
	}

	public void testAStoredPosterRoundTrips() throws Exception {
		String withPoster = read(FIXTURE).replace("\"name\":\"clip.mp4\",",
			"\"name\":\"clip.mp4\",\"poster\":{\"millis\":3200},");
		Files.write(new File(_album, "index.json").toPath(), withPoster.getBytes(StandardCharsets.UTF_8));

		AlbumInfo album = album(ALICE_TOKEN);
		assertEquals(3200, image(album, VIDEO).getPoster().getMillis());

		// read -> write -> read is equal.
		assertEquals(HttpServletResponse.SC_OK, put(write(album), ALICE_TOKEN).status());
		AlbumInfo again = album(ALICE_TOKEN);
		assertEquals(write(album), write(again));
		String stored = read(new File(_album, "index.json"));
		assertTrue(stored, stored.contains("\"poster\":{\"millis\":3200}"));
		assertEquals("No poster is written where there is none.", 1, count(stored, "\"poster\""));
	}

	public void testAStoredPosterThatIsNoMomentReadsAsNone() throws Exception {
		String broken = read(FIXTURE).replace("\"name\":\"clip.mp4\",", "\"name\":\"clip.mp4\",\"poster\":{\"millis\":0},")
			.replace("\"name\":\"photo.jpg\",", "\"name\":\"photo.jpg\",\"poster\":{\"millis\":1000},");
		Files.write(new File(_album, "index.json").toPath(), broken.getBytes(StandardCharsets.UTF_8));
		AlbumInfo album = album(ALICE_TOKEN);
		assertNull(image(album, VIDEO).getPoster());
		assertNull("A photograph has no poster.", image(album, PHOTO).getPoster());
	}

	public void testThePutKeepsTheStoredPoster() throws Exception {
		poster(VIDEO, 3200, ALICE_TOKEN);
		AlbumInfo album = album(ALICE_TOKEN);

		// An app that does not know the field writes the album back without it.
		image(album, VIDEO).setPoster(null);
		assertEquals(HttpServletResponse.SC_OK, put(write(album), ALICE_TOKEN).status());
		assertEquals(3200, image(album(ALICE_TOKEN), VIDEO).getPoster().getMillis());

		// Nor does a PUT choose one.
		image(album, VIDEO).setPoster(VideoPoster.create().setMillis(1000));
		assertEquals(HttpServletResponse.SC_OK, put(write(album), ALICE_TOKEN).status());
		assertEquals(3200, image(album(ALICE_TOKEN), VIDEO).getPoster().getMillis());
	}

	// --- The action and the thumbnail. ---

	public void testChooseAndResetThePoster() throws Exception {
		assertColour(SECONDS[0], thumbnail(VIDEO, null, DAVE_TOKEN));

		AlbumInfo answer = poster(VIDEO, 3200, ALICE_TOKEN);
		assertEquals(3200, image(answer, VIDEO).getPoster().getMillis());
		assertTrue(read(new File(_album, "index.json")).contains("\"poster\":{\"millis\":3200}"));
		assertEquals("The album answers the moment to every caller.", 3200,
			image(album(DAVE_TOKEN), VIDEO).getPoster().getMillis());

		assertColour("The tile asks for the moment and gets its frame.", SECONDS[3],
			thumbnail(VIDEO, "3200", DAVE_TOKEN));
		assertColour("The plain address stays the first frame.", SECONDS[0], thumbnail(VIDEO, null, DAVE_TOKEN));
		FakeResponse large = get("/" + ALBUM + "/" + VIDEO, params("type", "tn", "poster", "3200", "size", "large"),
			DAVE_TOKEN);
		assertEquals(HttpServletResponse.SC_OK, large.status());
		assertColour(SECONDS[3], ImageIO.read(new ByteArrayInputStream(large.bodyBytes())));

		AlbumInfo reset = poster(VIDEO, 0, ALICE_TOKEN);
		assertNull(image(reset, VIDEO).getPoster());
		assertFalse(read(new File(_album, "index.json")).contains("\"poster\""));
		assertNull(image(album(DAVE_TOKEN), VIDEO).getPoster());
		assertColour(SECONDS[0], thumbnail(VIDEO, null, DAVE_TOKEN));
		assertTrue("The original is untouched.", _video.length() > 0);
	}

	public void testWhoMayChooseThePoster() throws Exception {
		FakeResponse viewer = posterResponse(VIDEO, 3200, DAVE_TOKEN);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, viewer.status());
		assertEquals(ImageServlet.POSTER_REFUSED, error(viewer));

		FakeResponse anonymous = posterResponse(VIDEO, 3200, null);
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, anonymous.status());

		assertFalse("Nothing was written.", new File(_album, "index.json").exists());
	}

	public void testWhatIsRefused() throws Exception {
		FakeResponse late = posterResponse(VIDEO, 9000, ALICE_TOKEN);
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, late.status());
		assertEquals(ImageServlet.posterBeyondEnd(VIDEO, 9000,
			de.haumacher.imageServer.cache.VideoProbe.probe(_video).getDurationMillis()), error(late));

		FakeResponse photo = posterResponse(PHOTO, 1000, ALICE_TOKEN);
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, photo.status());
		assertEquals(ImageServlet.POSTER_NOT_A_VIDEO, error(photo));

		FakeResponse unknown = posterResponse("nothing.mp4", 1000, ALICE_TOKEN);
		assertEquals(HttpServletResponse.SC_NOT_FOUND, unknown.status());

		assertFalse("Nothing was written.", new File(_album, "index.json").exists());

		// The last frame of the rendition may lie a little after the end the container says.
		assertEquals(5400, image(poster(VIDEO, 5400, ALICE_TOKEN), VIDEO).getPoster().getMillis());
	}

	public void testWhoMayAskForWhichMoment() throws Exception {
		poster(VIDEO, 3200, ALICE_TOKEN);
		assertEquals(HttpServletResponse.SC_OK, thumbnailResponse(VIDEO, "3200", DAVE_TOKEN).status());

		FakeResponse other = thumbnailResponse(VIDEO, "1200", DAVE_TOKEN);
		assertEquals(HttpServletResponse.SC_CONFLICT, other.status());
		assertEquals(ImageServlet.POSTER_NOT_STORED, error(other));

		assertColour("An editor may ask for any moment.", SECONDS[1], thumbnail(VIDEO, "1200", ALICE_TOKEN));
		assertEquals(HttpServletResponse.SC_OK, thumbnailResponse(VIDEO, "0", DAVE_TOKEN).status());

		FakeResponse garbage = thumbnailResponse(VIDEO, "3.2", ALICE_TOKEN);
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, garbage.status());
		assertEquals(ImageServlet.POSTER_UNREADABLE, error(garbage));

		FakeResponse photo = thumbnailResponse(PHOTO, "1000", ALICE_TOKEN);
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, photo.status());
		assertEquals(ImageServlet.POSTER_NOT_A_VIDEO, error(photo));
	}

	public void testTheListingTileAsksForTheMoment() throws Exception {
		Files.copy(FIXTURE.toPath(), new File(_album, "index.json").toPath(), StandardCopyOption.REPLACE_EXISTING);
		AlbumInfo album = album(ALICE_TOKEN);
		album.setIndexPicture(ThumbnailInfo.create().setImage(VIDEO).setScale(1));
		assertEquals(HttpServletResponse.SC_OK, put(write(album), ALICE_TOKEN).status());
		assertNull(tile(listing(DAVE_TOKEN)).getPoster());

		poster(VIDEO, 3200, ALICE_TOKEN);
		ThumbnailInfo tile = tile(listing(DAVE_TOKEN));
		assertEquals(VIDEO, tile.getImage());
		assertEquals(3200, tile.getPoster().getMillis());
		String stored = read(new File(_album, "index.json"));
		assertEquals("The tile's moment is derived, never stored with the album picture.", 1,
			count(stored, "\"poster\""));

		poster(VIDEO, 0, ALICE_TOKEN);
		assertNull(tile(listing(DAVE_TOKEN)).getPoster());
	}

	// --- Helpers. ---

	private static ThumbnailInfo tile(ListingInfo listing) {
		for (FolderInfo folder : listing.getFolders()) {
			if (folder.getName().equals(ALBUM)) {
				return folder.getIndexPicture();
			}
		}
		fail("No " + ALBUM + " in the listing.");
		return null;
	}

	private AlbumInfo poster(String name, long millis, String token) throws Exception {
		FakeResponse response = posterResponse(name, millis, token);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		return (AlbumInfo) resource(response.body());
	}

	private FakeResponse posterResponse(String name, long millis, String token) throws Exception {
		ImagePart request = ImagePart.create().setName(name)
			.setPoster(millis == 0 ? null : VideoPoster.create().setMillis(millis));
		FakeResponse response = new FakeResponse();
		_servlet.doPost(TestImageServletPut.request("/" + ALBUM + "/", "application/json",
			write(request).getBytes(StandardCharsets.UTF_8), headers(token), params("action", ImageServlet.POSTER_ACTION)),
			response.response());
		return response;
	}

	private BufferedImage thumbnail(String name, String poster, String token) throws Exception {
		FakeResponse response = thumbnailResponse(name, poster, token);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		BufferedImage result = ImageIO.read(new ByteArrayInputStream(response.bodyBytes()));
		assertNotNull(result);
		return result;
	}

	private FakeResponse thumbnailResponse(String name, String poster, String token) throws Exception {
		Map<String, String> parameters = params("type", "tn");
		if (poster != null) {
			parameters.put("poster", poster);
		}
		return get("/" + ALBUM + "/" + name, parameters, token);
	}

	private AlbumInfo album(String token) throws Exception {
		FakeResponse response = get("/" + ALBUM + "/", params("type", "json"), token);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		return (AlbumInfo) resource(response.body());
	}

	private ListingInfo listing(String token) throws Exception {
		FakeResponse response = get("/", params("type", "json"), token);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		return (ListingInfo) resource(response.body());
	}

	private FakeResponse get(String path, Map<String, String> parameters, String token) throws Exception {
		FakeResponse response = new FakeResponse();
		_servlet.doGet(TestImageServletPut.request(path, null, new byte[0], headers(token), parameters),
			response.response());
		return response;
	}

	private FakeResponse put(String body, String token) throws Exception {
		FakeResponse response = new FakeResponse();
		_servlet.doPut(TestImageServletPut.request("/" + ALBUM + "/", "application/json",
			body.getBytes(StandardCharsets.UTF_8), headers(token), new HashMap<>()), response.response());
		return response;
	}

	private static Map<String, String> params(String... keyValues) {
		Map<String, String> result = new HashMap<>();
		for (int n = 0; n < keyValues.length; n += 2) {
			result.put(keyValues[n], keyValues[n + 1]);
		}
		return result;
	}

	private static Map<String, String> headers(String token) {
		Map<String, String> headers = new HashMap<>();
		if (token != null) {
			headers.put("Authorization", "Bearer " + token);
		}
		return headers;
	}

	private static ImagePart image(AlbumInfo album, String name) {
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart && ((ImagePart) part).getName().equals(name)) {
				return (ImagePart) part;
			}
		}
		fail("No " + name + " in the album.");
		return null;
	}

	private static Resource resource(String json) throws Exception {
		return Resource.readResource(new JsonReader(new ReaderAdapter(new StringReader(json))));
	}

	private static String error(FakeResponse response) throws Exception {
		Resource resource = resource(response.body());
		assertTrue(response.body(), resource instanceof ErrorInfo);
		return ((ErrorInfo) resource).getMessage();
	}

	private static String write(de.haumacher.msgbuf.data.AbstractDataObject object) throws Exception {
		StringWriter buffer = new StringWriter();
		try (JsonWriter json = new JsonWriter(new WriterAdapter(buffer))) {
			object.writeTo(json);
		}
		return buffer.toString();
	}

	private static String read(File file) throws Exception {
		return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
	}

	private static int count(String text, String part) {
		int result = 0;
		for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) {
			result++;
		}
		return result;
	}

	private static void assertColour(Color expected, BufferedImage image) {
		assertColour("", expected, image);
	}

	private static void assertColour(String message, Color expected, BufferedImage image) {
		Color at = new Color(image.getRGB(image.getWidth() / 2, image.getHeight() / 2));
		assertTrue(message + " Expected " + expected + " but found " + at,
			Math.abs(at.getRed() - expected.getRed()) < 60 && Math.abs(at.getGreen() - expected.getGreen()) < 60
				&& Math.abs(at.getBlue() - expected.getBlue()) < 60);
	}
}
