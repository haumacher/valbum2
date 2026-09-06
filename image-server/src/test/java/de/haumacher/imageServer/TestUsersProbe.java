/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.LibraryMigration;
import de.haumacher.imageServer.auth.LibraryMigration.MigrationRefused;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.shared.model.AuthInfo;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.PairResponse;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Probe for issue #45: the whole life of a library that was paired before users existed — a legacy
 * device store, a nested album with a sidecar written before the migration, the owner being named
 * by a second device, the explicit library migration, and the served library afterwards.
 *
 * <p>
 * Every step composes the user mechanism with something that existed before it: the pre-#45
 * <code>devices.json</code>, the <code>index.json</code> sidecar, a folder name with spaces and a
 * date, the image resource and the always-answerable <code>?type=auth</code>.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestUsersProbe extends TestCase {

	private static final String SECRET = "let-me-in";

	private static final String OLD_TOKEN = "token-issued-before-users-existed";

	private static final String ALBUM = "2020-01-01 Some Trip";

	private static final String TRIP_JSON =
		"[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[[\"ImagePart\",{\"name\":\"image-1.jpg\"}]]}]";

	private Path _base;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-users-probe");

		// A library that was paired with the #28 server: a device store in the pre-#45 format.
		Files.createDirectories(_base.resolve(UserStore.DIRECTORY_NAME));
		Files.write(_base.resolve(UserStore.DIRECTORY_NAME).resolve(UserStore.LEGACY_FILE_NAME),
			("{\"version\":1,\"devices\":[{\"name\":\"Old phone\",\"tokenHash\":\"" + UserStore.hash(OLD_TOKEN)
				+ "\",\"created\":\"2026-09-01T10:11:12Z\"}]}").getBytes(StandardCharsets.UTF_8));

		// A nested album with a real (tiny) image.
		Path album = _base.resolve(ALBUM);
		Files.createDirectories(album);
		BufferedImage image = new BufferedImage(4, 3, BufferedImage.TYPE_3BYTE_BGR);
		ImageIO.write(image, "jpg", album.resolve("image-1.jpg").toFile());
	}

	@Override
	protected void tearDown() throws Exception {
		if (_base != null) {
			try (Stream<Path> files = Files.walk(_base)) {
				files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
		super.tearDown();
	}

	public void testALegacyLibraryGrowsIntoAUserSpace() throws Exception {
		// --- Before users are named: the legacy device works, anonymous reads stay open. ---
		ImageServlet servlet = servlet();

		FakeResponse listing = get(servlet, "/", "json", OLD_TOKEN);
		assertEquals(listing.body(), HttpServletResponse.SC_OK, listing.status());
		assertTrue("The legacy device sees the album folder: " + listing.body(), listing.body().contains(ALBUM));

		FakeResponse anonymous = get(servlet, "/", "json", null);
		assertEquals("A single-user library stays open for anonymous reads.", HttpServletResponse.SC_OK,
			anonymous.status());

		AuthInfo before = authInfo(servlet, OLD_TOKEN);
		assertEquals("Old phone", before.getDeviceName());
		assertEquals("The migrated owner has no name yet.", "", before.getUserName());
		assertEquals(Roles.ADMIN, before.getRole());
		assertEquals("", before.getSpace());

		// The legacy device writes a sidecar into the nested album, at the base folder as before.
		FakeResponse saved = put(servlet, "/" + ALBUM + "/", TRIP_JSON, OLD_TOKEN);
		assertEquals(saved.body(), HttpServletResponse.SC_OK, saved.status());
		assertTrue(Files.exists(_base.resolve(ALBUM).resolve("index.json")));

		// --- A second device names the owner; the legacy device belongs to that owner. ---
		PairResponse tablet = signIn(servlet, " haui ", "Tablet");
		assertEquals("The name is trimmed.", "haui", tablet.getUserName());
		assertEquals(Roles.ADMIN, tablet.getRole());
		assertEquals("", tablet.getSpace());

		AuthInfo named = authInfo(servlet, OLD_TOKEN);
		assertEquals("Naming the owner names the legacy device's user.", "haui", named.getUserName());

		// A third device signing in without a name is the owner too.
		PairResponse laptop = signIn(servlet, "", "Laptop");
		assertEquals("haui", laptop.getUserName());

		// --- The explicit migration. ---
		List<String> moved = LibraryMigration.migrate(_base, "haui");
		assertEquals(Collections.singletonList(ALBUM), moved);
		assertTrue(Files.exists(_base.resolve("haui").resolve(ALBUM).resolve("image-1.jpg")));
		assertTrue("The sidecar moved with its folder.",
			Files.exists(_base.resolve("haui").resolve(ALBUM).resolve("index.json")));
		assertFalse(Files.exists(_base.resolve(ALBUM)));
		assertTrue(Files.isDirectory(_base.resolve(UserStore.DIRECTORY_NAME)));
		assertTrue(Files.exists(_base.resolve(UserStore.DIRECTORY_NAME).resolve(UserStore.FILE_NAME)));
		assertTrue(Files.exists(_base.resolve(UserStore.DIRECTORY_NAME)
			.resolve(UserStore.LEGACY_FILE_NAME + UserStore.MIGRATED_SUFFIX)));

		try {
			LibraryMigration.migrate(_base, "haui");
			fail("A second migration must be refused.");
		} catch (MigrationRefused ex) {
			assertEquals(LibraryMigration.alreadyMigrated("haui"), ex.getMessage());
		}

		// The store on disk knows all three devices of the named owner.
		UserStore reloaded = new UserStore(_base);
		assertEquals("haui", reloaded.getOwner().getName());
		assertEquals("haui", reloaded.getOwner().getSpace());
		assertEquals(3, reloaded.getOwner().getDevices().size());
		assertEquals("haui", reloaded.lookup(OLD_TOKEN).getUser().getName());
		assertEquals("haui", reloaded.lookup(tablet.getToken()).getUser().getName());

		// --- The server restarted after the migration: the space is the root. ---
		servlet = servlet();

		FakeResponse root = get(servlet, "/", "json", OLD_TOKEN);
		assertEquals(root.body(), HttpServletResponse.SC_OK, root.status());
		assertTrue("The owner's root is the space, which holds the album: " + root.body(),
			root.body().contains(ALBUM));
		assertFalse("The space folder itself is not listed inside the space.", root.body().contains("\"haui\""));

		FakeResponse trip = get(servlet, "/" + ALBUM + "/", "json", tablet.getToken());
		assertEquals(trip.body(), HttpServletResponse.SC_OK, trip.status());
		assertTrue("The sidecar written before the migration is read from the space: " + trip.body(),
			trip.body().contains("\"title\":\"Trip\""));

		FakeResponse imageJson = get(servlet, "/" + ALBUM + "/image-1.jpg", "json", laptop.getToken());
		assertEquals(imageJson.body(), HttpServletResponse.SC_OK, imageJson.status());
		assertTrue(imageJson.body().contains("image-1.jpg"));

		FakeResponse thumbnail = get(servlet, "/" + ALBUM + "/image-1.jpg", "tn", OLD_TOKEN);
		assertEquals(HttpServletResponse.SC_OK, thumbnail.status());
		assertTrue("The preview is rendered from the original inside the space.", thumbnail.body().length() > 100);

		FakeResponse escaped = get(servlet, "/../" + UserStore.DIRECTORY_NAME + "/", "json", OLD_TOKEN);
		assertEquals("Escaping the space is a 404.", HttpServletResponse.SC_NOT_FOUND, escaped.status());

		AuthInfo after = authInfo(servlet, OLD_TOKEN);
		assertEquals("haui", after.getUserName());
		assertEquals("haui", after.getSpace());
		assertEquals(Roles.ADMIN, after.getRole());

		// --- Anonymous callers: told why, and still able to ask who they are. ---
		FakeResponse refused = get(servlet, "/", "json", null);
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, refused.status());
		assertEquals("Bearer", refused.header("WWW-Authenticate"));
		assertEquals(AuthService.LIBRARY_REFUSED, errorMessage(refused));

		FakeResponse refusedImage = get(servlet, "/" + ALBUM + "/image-1.jpg", "tn", null);
		assertEquals("Neither is an original open to anonymous callers.", HttpServletResponse.SC_UNAUTHORIZED,
			refusedImage.status());

		AuthInfo anonymousInfo = authInfo(servlet, null);
		assertEquals("writes", anonymousInfo.getMode());
		assertEquals("", anonymousInfo.getUserName());
		assertEquals("", anonymousInfo.getRole());
		assertFalse(anonymousInfo.isWriteAllowed());
	}

	public void testAnotherNameAtTheSecretIsRefusedWithTheOwnersName() throws Exception {
		ImageServlet servlet = servlet();
		signIn(servlet, "haui", "Tablet");

		FakeResponse response = post(servlet, "pair", pairRequest(SECRET, "Phone", "mallory"), null);

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status());
		assertEquals(UserStore.ownerMismatch("haui"), errorMessage(response));
		assertEquals("No device was added by the refused sign-in.", 2,
			new UserStore(_base).getOwner().getDevices().size());
	}

	// --- Helpers. ---

	private ImageServlet servlet() throws IOException {
		return new ImageServlet(_base.toFile(), new AuthService(AuthMode.WRITES, SECRET, _base));
	}

	private PairResponse signIn(ImageServlet servlet, String userName, String deviceName) throws Exception {
		FakeResponse response = post(servlet, "pair", pairRequest(SECRET, deviceName, userName), null);
		assertEquals("Sign-in failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return PairResponse.readPairResponse(reader(response.body()));
	}

	private static String pairRequest(String secret, String deviceName, String userName) {
		return "{\"secret\":\"" + secret + "\",\"deviceName\":\"" + deviceName + "\",\"userName\":\"" + userName
			+ "\"}";
	}

	private AuthInfo authInfo(ImageServlet servlet, String token) throws Exception {
		FakeResponse response = get(servlet, "/", "auth", token);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		return AuthInfo.readAuthInfo(reader(response.body()));
	}

	private static String errorMessage(FakeResponse response) throws IOException {
		Resource resource = Resource.readResource(reader(response.body()));
		assertTrue("Expected an ErrorInfo body, got: " + response.body(), resource instanceof ErrorInfo);
		return ((ErrorInfo) resource).getMessage();
	}

	private static JsonReader reader(String contents) {
		return new JsonReader(new ReaderAdapter(new StringReader(contents)));
	}

	private static FakeResponse put(ImageServlet servlet, String pathInfo, String body, String token)
			throws Exception {
		FakeResponse response = new FakeResponse();
		servlet.doPut(request(pathInfo, "application/json", body, token, Collections.emptyMap()),
			response.response());
		return response;
	}

	private static FakeResponse post(ImageServlet servlet, String action, String body, String token)
			throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", action);
		FakeResponse response = new FakeResponse();
		servlet.doPost(request("/", "application/json", body, token, parameters), response.response());
		return response;
	}

	private static FakeResponse get(ImageServlet servlet, String pathInfo, String type, String token)
			throws Exception {
		Map<String, String> parameters = new HashMap<>();
		if (type != null) {
			parameters.put("type", type);
		}
		FakeResponse response = new FakeResponse();
		servlet.doGet(request(pathInfo, null, "", token, parameters), response.response());
		return response;
	}

	private static HttpServletRequest request(String pathInfo, String contentType, String body, String token,
			Map<String, String> parameters) {
		Map<String, String> headers = new HashMap<>();
		if (token != null) {
			headers.put("Authorization", "Bearer " + token);
		}
		return TestImageServletPut.request(pathInfo, contentType, body.getBytes(StandardCharsets.UTF_8), headers,
			parameters);
	}
}
