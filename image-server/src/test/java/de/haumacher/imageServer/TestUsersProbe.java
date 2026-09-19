/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
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
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
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


	/** A code says whom it signs in; a name that is not theirs is refused, see issues #65 and #89. */
	public void testAnotherNameAtACodeIsRefused() throws Exception {
		ImageServlet servlet = servlet();
		signIn(servlet, "haui", "Tablet");

		FakeResponse response =
			post(servlet, "pair", Codes.pairRequest(Codes.forServlet(servlet), "Phone", "mallory"), null);

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status());
		assertEquals(AuthService.DEVICE_CODE_OTHER_USER, errorMessage(response));
		assertEquals("No device was added by the refused sign-in.", 2,
			new UserStore(_base).getOwner().getDevices().size());
	}

	// --- Helpers. ---

	private ImageServlet servlet() throws IOException {
		return new ImageServlet(_base.toFile(), new AuthService(AuthMode.WRITES, _base));
	}

	private PairResponse signIn(ImageServlet servlet, String userName, String deviceName) throws Exception {
		FakeResponse response =
			post(servlet, "pair", Codes.pairRequest(Codes.forServlet(servlet), deviceName, userName), null);
		assertEquals("Sign-in failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return PairResponse.readPairResponse(reader(response.body()));
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
