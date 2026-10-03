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
import de.haumacher.imageServer.shared.model.Crop;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.Orientation;
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
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * The crop of a photograph, see issue #212: how it is stored, how a rendition of it is cut, who may
 * ask for which region, and what it does to the album picture and the listing.
 *
 * <p>
 * The pictures are generated: <code>quadrants.jpg</code> is 1200&nbsp;&times;&nbsp;800 with a red
 * top left, a green top right, a blue bottom left and a yellow bottom right quarter, so that a
 * pixel of a cut rendition says which quarter it was cut from. The stored form is the fixture
 * <code>src/test/fixtures/crop/index.json</code>; the table of the stored orientation is the shared
 * fixture <code>src/test/fixtures/crop-orientations.json</code>, which the app's test reads too.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestCrop extends TestCase {

	private static final File FIXTURE = new File("src/test/fixtures/crop/index.json");

	private static final File ORIENTATIONS = new File("src/test/fixtures/crop-orientations.json");

	private static final File VIDEO = new File("src/test/fixtures/test-album/2005-08-24 Blumen und Fliegen/MVI_0450.mp4");

	private static final String ALBUM = "Crop";

	private static final String IMAGE = "quadrants.jpg";

	private static final String ALICE_TOKEN = "alice-token";

	private static final String DAVE_TOKEN = "dave-token";

	private static final Color RED = new Color(0xE0, 0x10, 0x10);

	private static final Color GREEN = new Color(0x10, 0xE0, 0x10);

	private static final Color BLUE = new Color(0x10, 0x10, 0xE0);

	private static final Color YELLOW = new Color(0xE0, 0xE0, 0x10);

	private Path _base;

	private File _album;

	private ImageServlet _servlet;

	private String _adminToken;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-crop");
		_album = new File(_base.toFile(), ALBUM);
		assertTrue(_album.mkdir());
		quadrants(new File(_album, IMAGE), 1200, 800);
		quadrants(new File(_album, "whole.jpg"), 1200, 800);
		Files.copy(VIDEO.toPath(), new File(_album, "clip.mp4").toPath());

		UserStore store = new UserStore(_base);
		store.nameOwner("haui");
		store.addUser(user("alice", Roles.EDIT, ALICE_TOKEN));
		store.addUser(user("dave", Roles.VIEW, DAVE_TOKEN));
		store.store();
		AuthService auth = new AuthService(AuthMode.WRITES, _base);
		_servlet = new ImageServlet(_base.toFile(), auth);
		_servlet.init();
		_adminToken = Codes.signInAdmin(auth, "Phone", "haui").getToken();
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

	// --- The stored form. ---

	public void testTheStoredFormReadsAndRoundTrips() throws Exception {
		Files.copy(FIXTURE.toPath(), new File(_album, "index.json").toPath(), StandardCopyOption.REPLACE_EXISTING);

		AlbumInfo album = album(ALICE_TOKEN);
		Crop crop = image(album, IMAGE).getCrop();
		assertNotNull(crop);
		assertEquals(0.5, crop.getX(), 0);
		assertEquals(0.0, crop.getY(), 0);
		assertEquals(0.5, crop.getW(), 0);
		assertEquals(0.5, crop.getH(), 0);
		assertNull("A part written before the field existed shows the whole picture.",
			image(album, "whole.jpg").getCrop());

		// read -> write -> read is equal.
		assertEquals(HttpServletResponse.SC_OK, put(write(album), ALICE_TOKEN).status());
		AlbumInfo again = album(ALICE_TOKEN);
		assertEquals(write(album), write(again));
		String stored = read(new File(_album, "index.json"));
		assertTrue(stored, stored.contains("\"crop\":{\"x\":0.5,\"y\":0.0,\"w\":0.5,\"h\":0.5}"));
		assertEquals("No crop is written where there is none.", 1, count(stored, "\"crop\""));
	}

	public void testAnInvalidStoredCropReadsAsNone() throws Exception {
		String broken = read(FIXTURE).replace("\"w\":0.5,\"h\":0.5", "\"w\":0.9,\"h\":0.5");
		Files.write(new File(_album, "index.json").toPath(), broken.getBytes(StandardCharsets.UTF_8));
		assertNull("x + w > 1 is no region of the picture.", image(album(ALICE_TOKEN), IMAGE).getCrop());
	}

	// --- The action. ---

	public void testCropAndResetAreStored() throws Exception {
		AlbumInfo answer = crop(IMAGE, crop(0.5, 0, 0.5, 0.5), ALICE_TOKEN);
		assertEquals(0.5, image(answer, IMAGE).getCrop().getX(), 0);
		assertTrue(read(new File(_album, "index.json")).contains("\"crop\""));
		assertEquals(0.5, image(album(DAVE_TOKEN), IMAGE).getCrop().getW(), 0);

		AlbumInfo reset = crop(IMAGE, null, ALICE_TOKEN);
		assertNull(image(reset, IMAGE).getCrop());
		assertFalse(read(new File(_album, "index.json")).contains("\"crop\""));

		// The whole picture is no crop either.
		assertNull(image(crop(IMAGE, crop(0, 0, 1, 1), ALICE_TOKEN), IMAGE).getCrop());
	}

	public void testWhoMayCrop() throws Exception {
		FakeResponse viewer = cropResponse(IMAGE, crop(0, 0, 0.5, 0.5), DAVE_TOKEN);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, viewer.status());
		assertEquals(ImageServlet.CROP_REFUSED, error(viewer));

		FakeResponse anonymous = cropResponse(IMAGE, crop(0, 0, 0.5, 0.5), null);
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, anonymous.status());

		assertFalse("Nothing was written.", new File(_album, "index.json").exists());
	}

	public void testWhatIsRefused() throws Exception {
		FakeResponse outside = cropResponse(IMAGE, crop(0.6, 0, 0.5, 0.5), ALICE_TOKEN);
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, outside.status());
		assertEquals(ImageServlet.cropInvalid(IMAGE), error(outside));

		FakeResponse empty = cropResponse(IMAGE, crop(0.1, 0.1, 0, 0.5), ALICE_TOKEN);
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, empty.status());

		FakeResponse video = cropResponse("clip.mp4", crop(0, 0, 0.5, 0.5), ALICE_TOKEN);
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, video.status());
		assertEquals(ImageServlet.CROP_VIDEO_REFUSED, error(video));

		FakeResponse unknown = cropResponse("nothing.jpg", crop(0, 0, 0.5, 0.5), ALICE_TOKEN);
		assertEquals(HttpServletResponse.SC_NOT_FOUND, unknown.status());

		assertFalse("Nothing was written.", new File(_album, "index.json").exists());
	}

	/** An inbox writes at once, and so does its crop (issue #160); it is answered with the part. */
	public void testAnInboxIsCroppedLikeAnAlbum() throws Exception {
		// The album is the space's inbox, see issue #226.
		_servlet.destroy();
		_servlet = new ImageServlet(_base.toFile(), new AuthService(AuthMode.WRITES, _base), "",
			new de.haumacher.imageServer.auth.SpaceStore.Config("", "none", "", "off", "", ALBUM));
		_servlet.init();
		assertEquals(de.haumacher.imageServer.shared.model.AlbumKind.INBOX, album(ALICE_TOKEN).getKind());

		AlbumInfo answer = crop(IMAGE, crop(0.5, 0, 0.5, 0.5), ALICE_TOKEN);
		assertEquals(de.haumacher.imageServer.shared.model.AlbumKind.INBOX, answer.getKind());
		assertEquals(0.5, image(answer, IMAGE).getCrop().getX(), 0);

		// The inbox's own write of a turn carries the crop back unchanged.
		AlbumInfo again = album(ALICE_TOKEN);
		image(again, IMAGE).setOrientation(Orientation.ROT_180);
		assertEquals(HttpServletResponse.SC_OK, put(write(again), ALICE_TOKEN).status());
		assertEquals(0.5, image(album(ALICE_TOKEN), IMAGE).getCrop().getW(), 0);
	}

	// --- The cut rendition. ---

	public void testTheCutThumbnailShowsTheRegionAtItsAspect() throws Exception {
		crop(IMAGE, crop(0.5, 0, 0.5, 0.5), ALICE_TOKEN);
		BufferedImage tn = thumbnail(IMAGE, "0.5000,0.0000,0.5000,0.5000", DAVE_TOKEN);
		// 600 x 400 of the file, a landscape: 600 px high and never larger than the region.
		assertEquals(600, tn.getWidth());
		assertEquals(400, tn.getHeight());
		assertColor(GREEN, tn, tn.getWidth() / 2, tn.getHeight() / 2);
		assertColor(GREEN, tn, 5, 5);
		assertColor(GREEN, tn, tn.getWidth() - 5, tn.getHeight() - 5);

		File cut = PreviewCache.croppedPreviewFile(new File(_album, IMAGE), new double[] { 0.5, 0, 0.5, 0.5 });
		assertTrue("Cached under a name carrying the region: " + cut, cut.isFile());
		assertTrue(cut.getName(), cut.getName().matches("preview-quadrants\\.jpg-c[0-9a-f]{12}\\.jpg"));
		assertTrue(CacheRefresh.isGenerated(cut.getName()));
		assertTrue("A purge takes the cut previews with the photograph.",
			DeleteService.generatedOf(_album, IMAGE).contains(cut));
		assertFalse(DeleteService.generatedOf(_album, "whole.jpg").contains(cut));

		BufferedImage whole = thumbnail(IMAGE, null, DAVE_TOKEN);
		assertEquals("Without the parameter the whole picture, as an older app asks.", 900, whole.getWidth());
	}

	public void testAPortraitRegionOfALargePictureIsSharp() throws Exception {
		File large = new File(_album, "large.jpg");
		quadrants(large, 4000, 3000);
		// The left quarter of the left half: 1000 x 3000 px of the file, a portrait.
		BufferedImage tn = cut(large, new double[] { 0, 0, 0.25, 1 });
		assertEquals("A portrait preview is 1200 px high.", 1200, tn.getHeight());
		assertEquals(400, tn.getWidth());
		assertColor(RED, tn, 200, 100);
		assertColor(BLUE, tn, 200, 1100);
	}

	public void testAFileTurnedByItsExifIsCutUpright() throws Exception {
		// Stored 800 x 1200 and turned a quarter by EXIF 6: shown as the 1200 x 800 quadrants.
		File plain = new File(_album, "turned.plain.jpg");
		BufferedImage stored = new BufferedImage(800, 1200, BufferedImage.TYPE_INT_RGB);
		BufferedImage shown = picture(1200, 800);
		// EXIF 6 shows the raw pixel (s, t) at (H - t, s) of the 1200 x 800 picture.
		for (int t = 0; t < 1200; t++) {
			for (int s = 0; s < 800; s++) {
				stored.setRGB(s, t, shown.getRGB(1199 - t, s));
			}
		}
		assertTrue(ImageIO.write(stored, "jpg", plain));
		File turned = new File(_album, "turned.jpg");
		Exif.writeOrientation(plain, turned, 6);
		assertTrue(plain.delete());

		BufferedImage tn = cut(turned, new double[] { 0, 0.5, 0.5, 0.5 });
		assertEquals(600, tn.getWidth());
		assertEquals(400, tn.getHeight());
		assertColor(BLUE, tn, 300, 200);
	}

	public void testTheStoredOrientationFramesTheRegion() throws Exception {
		// The photograph is shown turned a quarter (the app's rotL: counter-clockwise), so the top
		// left quarter of what is shown is the top right quarter of the rendition.
		AlbumInfo album = album(ALICE_TOKEN);
		image(album, IMAGE).setOrientation(Orientation.ROT_L);
		assertEquals(HttpServletResponse.SC_OK, put(write(album), ALICE_TOKEN).status());
		crop(IMAGE, crop(0, 0, 0.5, 0.5), ALICE_TOKEN);

		ImagePart part = image(album(DAVE_TOKEN), IMAGE);
		double[] region = Crops.renditionRegion(part);
		assertEquals("0.5000,0.0000,0.5000,0.5000", Crops.token(region));
		BufferedImage tn = thumbnail(IMAGE, Crops.token(region), DAVE_TOKEN);
		assertColor(GREEN, tn, tn.getWidth() / 2, tn.getHeight() / 2);
	}

	public void testTheOrientationTableIsTheSharedOne() throws Exception {
		String json = read(ORIENTATIONS);
		int seen = 0;
		for (Orientation orientation : Orientation.values()) {
			String name = orientation.name();
			int at = json.indexOf("\"orientation\": \"" + name + "\"");
			assertTrue("The fixture names " + name, at >= 0);
			double[] shown = numbers(json, json.indexOf("\"shown\"", at));
			double[] rendition = numbers(json, json.indexOf("\"rendition\"", at));
			Crop crop = crop(shown[0], shown[1], shown[2], shown[3]);
			double[] computed = Crops.toRendition(orientation, crop);
			for (int n = 0; n < 4; n++) {
				assertEquals(name + " " + n, rendition[n], computed[n], 1e-9);
			}
			Crop back = Crops.toShown(orientation, computed);
			assertEquals(name, shown[0], back.getX(), 1e-9);
			assertEquals(name, shown[1], back.getY(), 1e-9);
			assertEquals(name, shown[2], back.getW(), 1e-9);
			assertEquals(name, shown[3], back.getH(), 1e-9);
			seen++;
		}
		assertEquals(8, seen);
	}

	// --- Who may ask for which region. ---

	public void testAViewerIsCutTheStoredRegionAndNoOther() throws Exception {
		crop(IMAGE, crop(0.5, 0, 0.5, 0.5), ALICE_TOKEN);
		assertEquals(HttpServletResponse.SC_OK, thumbnailResponse(IMAGE, "0.5,0,0.5,0.5", DAVE_TOKEN).status());

		FakeResponse other = thumbnailResponse(IMAGE, "0.0000,0.0000,0.5000,0.5000", DAVE_TOKEN);
		assertEquals(HttpServletResponse.SC_CONFLICT, other.status());
		assertEquals(ImageServlet.CROP_NOT_STORED, error(other));
		FakeResponse anonymous = thumbnailResponse(IMAGE, "0.0000,0.0000,0.5000,0.5000", null);
		assertEquals(HttpServletResponse.SC_CONFLICT, anonymous.status());

		// An editor draws the crop it is about to write.
		BufferedImage tn = thumbnail(IMAGE, "0.0000,0.5000,0.5000,0.5000", ALICE_TOKEN);
		assertColor(BLUE, tn, tn.getWidth() / 2, tn.getHeight() / 2);
	}

	public void testAnUnreadableRegionAndAVideoAreRefused() throws Exception {
		FakeResponse garbage = thumbnailResponse(IMAGE, "a,b,c,d", ALICE_TOKEN);
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, garbage.status());
		assertEquals(ImageServlet.CROP_UNREADABLE, error(garbage));
		assertEquals(HttpServletResponse.SC_BAD_REQUEST,
			thumbnailResponse(IMAGE, "0.8,0,0.5,0.5", ALICE_TOKEN).status());
		FakeResponse video = thumbnailResponse("clip.mp4", "0,0,0.5,0.5", ALICE_TOKEN);
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, video.status());
		assertEquals(ImageServlet.CROP_VIDEO_REFUSED, error(video));
	}

	// --- What stays the whole picture. ---

	public void testTheDownloadIsTheWholeOriginal() throws Exception {
		byte[] original = Files.readAllBytes(new File(_album, IMAGE).toPath());
		crop(IMAGE, crop(0.5, 0, 0.5, 0.5), ALICE_TOKEN);
		FakeResponse response = get("/" + ALBUM + "/" + IMAGE, new HashMap<>(), DAVE_TOKEN);
		assertEquals(HttpServletResponse.SC_OK, response.status());
		assertTrue("The original, byte for byte.", Arrays.equals(original, response.bodyBytes()));
		assertTrue("The original is never touched.",
			Arrays.equals(original, Files.readAllBytes(new File(_album, IMAGE).toPath())));
	}

	// --- The album picture and the listing. ---

	public void testTheAlbumPictureIsMeasuredOnTheCroppedPhotograph() throws Exception {
		AlbumInfo album = album(ALICE_TOKEN);
		album.setIndexPicture(ThumbnailInfo.create().setImage(IMAGE).setScale(3).setTx(20).setTy(-10)
			.setOrientation(Orientation.IDENTITY));
		assertEquals(HttpServletResponse.SC_OK, put(write(album), ALICE_TOKEN).status());

		// A portrait region, 300 x 800 px of the file.
		AlbumInfo answer = crop(IMAGE, crop(0, 0, 0.25, 1), ALICE_TOKEN);
		ThumbnailInfo picture = answer.getIndexPicture();
		assertEquals(IMAGE, picture.getImage());
		assertEquals("The framing of the whole picture is replaced by the default on the cut one.",
			800.0 / 300, picture.getScale(), 1e-9);
		assertEquals(0, picture.getTx(), 0);
		assertEquals((800.0 - 300) / 800 * 150, picture.getTy(), 1e-9);
		assertNull("The album's own answer carries no region; the app has the part.", picture.getCrop());

		// A crop of another photograph leaves the framing alone.
		answer = crop("whole.jpg", crop(0, 0, 0.5, 0.5), ALICE_TOKEN);
		assertEquals(800.0 / 300, answer.getIndexPicture().getScale(), 1e-9);
	}

	public void testTheListingAsksForTheRegionAndNeverStoresIt() throws Exception {
		AlbumInfo album = album(ALICE_TOKEN);
		album.setIndexPicture(ThumbnailInfo.create().setImage(IMAGE).setScale(1.5)
			.setOrientation(Orientation.IDENTITY));
		assertEquals(HttpServletResponse.SC_OK, put(write(album), ALICE_TOKEN).status());
		crop(IMAGE, crop(0.5, 0.5, 0.5, 0.5), ALICE_TOKEN);

		ListingInfo listing = listing(DAVE_TOKEN);
		FolderInfo entry = listing.getFolders().get(0);
		Crop region = entry.getIndexPicture().getCrop();
		assertNotNull("The tile asks for the cut rendition.", region);
		assertEquals("0.5000,0.5000,0.5000,0.5000",
			Crops.token(new double[] { region.getX(), region.getY(), region.getW(), region.getH() }));
		assertEquals("The region the listing names is one a viewer is cut.", HttpServletResponse.SC_OK,
			thumbnailResponse(IMAGE, "0.5000,0.5000,0.5000,0.5000", DAVE_TOKEN).status());

		// Written back with a region on the album picture, the region is not stored.
		AlbumInfo again = album(ALICE_TOKEN);
		again.getIndexPicture().setCrop(Crop.create().setX(0.1).setY(0.1).setW(0.2).setH(0.2));
		assertEquals(HttpServletResponse.SC_OK, put(write(again), ALICE_TOKEN).status());
		String stored = read(new File(_album, "index.json"));
		assertEquals("Only the photograph's own crop is stored.", 1, count(stored, "\"crop\""));
	}

	// --- Helpers. ---

	/** Review probe: a crop travels with its photograph in a move and is cut there again. */
	public void testACropTravelsWithAMove() throws Exception {
		crop(IMAGE, crop(0.5, 0, 0.5, 0.5), ALICE_TOKEN);
		File target = new File(_base.toFile(), "Target");
		assertTrue(target.mkdir());
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "move");
		FakeResponse moved = new FakeResponse();
		_servlet.doPost(TestImageServletPut.request("/" + ALBUM + "/", "application/json",
			("{\"target\":\"Target\",\"names\":[{\"name\":\"" + IMAGE + "\"}]}").getBytes(StandardCharsets.UTF_8),
			headers(ALICE_TOKEN), parameters), moved.response());
		assertEquals(moved.body(), HttpServletResponse.SC_OK, moved.status());

		Map<String, String> json = new HashMap<>();
		json.put("type", "json");
		FakeResponse there = get("/Target/", json, DAVE_TOKEN);
		AlbumInfo album = (AlbumInfo) resource(there.body());
		assertEquals(0.5, image(album, IMAGE).getCrop().getX(), 0);

		Map<String, String> tn = new HashMap<>();
		tn.put("type", "tn");
		tn.put("crop", "0.5000,0.0000,0.5000,0.5000");
		FakeResponse cut = get("/Target/" + IMAGE, tn, DAVE_TOKEN);
		assertEquals(cut.body(), HttpServletResponse.SC_OK, cut.status());
		BufferedImage picture = ImageIO.read(new ByteArrayInputStream(cut.bodyBytes()));
		assertColor(GREEN, picture, picture.getWidth() / 2, picture.getHeight() / 2);
	}

	private static Crop crop(double x, double y, double w, double h) {
		return Crop.create().setX(x).setY(y).setW(w).setH(h);
	}

	private BufferedImage cut(File file, double[] region) throws Exception {
		BufferedImage result = ImageIO.read(PreviewCache.createPreview(file, region));
		assertNotNull(result);
		return result;
	}

	private AlbumInfo crop(String name, Crop crop, String token) throws Exception {
		FakeResponse response = cropResponse(name, crop, token);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		return (AlbumInfo) resource(response.body());
	}

	private FakeResponse cropResponse(String name, Crop crop, String token) throws Exception {
		ImagePart request = ImagePart.create().setName(name).setCrop(crop);
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", ImageServlet.CROP_ACTION);
		FakeResponse response = new FakeResponse();
		_servlet.doPost(TestImageServletPut.request("/" + ALBUM + "/", "application/json",
			write(request).getBytes(StandardCharsets.UTF_8), headers(token), parameters), response.response());
		return response;
	}

	private BufferedImage thumbnail(String name, String crop, String token) throws Exception {
		FakeResponse response = thumbnailResponse(name, crop, token);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		BufferedImage result = ImageIO.read(new ByteArrayInputStream(response.bodyBytes()));
		assertNotNull(result);
		return result;
	}

	private FakeResponse thumbnailResponse(String name, String crop, String token) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "tn");
		if (crop != null) {
			parameters.put("crop", crop);
		}
		return get("/" + ALBUM + "/" + name, parameters, token);
	}

	private AlbumInfo album(String token) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "json");
		FakeResponse response = get("/" + ALBUM + "/", parameters, token);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		return (AlbumInfo) resource(response.body());
	}

	private ListingInfo listing(String token) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "json");
		FakeResponse response = get("/", parameters, token);
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

	/** The four numbers of the JSON array following the given position. */
	private static double[] numbers(String json, int from) {
		int open = json.indexOf('[', from);
		int close = json.indexOf(']', open);
		String[] parts = json.substring(open + 1, close).split(",");
		double[] result = new double[parts.length];
		for (int n = 0; n < parts.length; n++) {
			result[n] = Double.parseDouble(parts[n].trim());
		}
		return result;
	}

	private static BufferedImage picture(int width, int height) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		try {
			g.setColor(RED);
			g.fillRect(0, 0, width / 2, height / 2);
			g.setColor(GREEN);
			g.fillRect(width / 2, 0, width - width / 2, height / 2);
			g.setColor(BLUE);
			g.fillRect(0, height / 2, width / 2, height - height / 2);
			g.setColor(YELLOW);
			g.fillRect(width / 2, height / 2, width - width / 2, height - height / 2);
		} finally {
			g.dispose();
		}
		return image;
	}

	private static void quadrants(File file, int width, int height) throws Exception {
		assertTrue(ImageIO.write(picture(width, height), "jpg", file));
	}

	private static void assertColor(Color expected, BufferedImage image, int x, int y) {
		Color at = new Color(image.getRGB(x, y));
		assertTrue("At " + x + "," + y + " expected " + expected + " but found " + at,
			Math.abs(at.getRed() - expected.getRed()) < 60 && Math.abs(at.getGreen() - expected.getGreen()) < 60
				&& Math.abs(at.getBlue() - expected.getBlue()) < 60);
	}
}
