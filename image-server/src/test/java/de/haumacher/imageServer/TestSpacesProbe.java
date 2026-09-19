/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import static de.haumacher.imageServer.TestSpaces.config;
import static de.haumacher.imageServer.TestSpaces.request;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.InviteMode;
import de.haumacher.imageServer.auth.Spaces;
import de.haumacher.imageServer.shared.model.AuthInfo;
import de.haumacher.imageServer.shared.model.DeviceCodeCreated;
import de.haumacher.imageServer.shared.model.InvitationCreated;
import de.haumacher.imageServer.shared.model.PairResponse;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import de.haumacher.util.servlet.ResourceServlet;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Probe for the space boundary of #82: credentials and sessions minted in one space must be worth
 * nothing in another, and a share link made inside a space must still open.
 */
@SuppressWarnings("javadoc")
public class TestSpacesProbe extends TestCase {

	private Path _base;

	private Path _webRoot;

	private SpaceServlet _servlet;

	@Override
	protected void setUp() throws Exception {
		_base = Files.createTempDirectory("valbum-spaces-probe");
		_webRoot = Files.createTempDirectory("valbum-spaces-probe-web");
		Files.write(_webRoot.resolve("index.html"),
			"<html><head><base href=\"/\"></head><body>app</body></html>".getBytes(StandardCharsets.UTF_8));
		for (String space : new String[] { "alice", "bob" }) {
			Path root = _base.resolve(space);
			Files.createDirectories(root.resolve(".valbum"));
			Files.write(root.resolve(".valbum").resolve("space.json"), "{}".getBytes(StandardCharsets.UTF_8));
			Path album = root.resolve("2024 " + space);
			Files.createDirectories(album);
			ImageIO.write(new BufferedImage(4, 3, BufferedImage.TYPE_3BYTE_BGR), "jpg",
				album.resolve("image.jpg").toFile());
		}
		_spaces = Spaces.detect(_base, null, AuthMode.WRITES, InviteMode.MEMBERS);
		Spaces spaces = _spaces;
		ResourceServlet app = new ResourceServlet(_webRoot, "/data", "s", "i");
		app.setBaseSegments(spaces.segments());
		_servlet = new SpaceServlet(spaces, app);
		_servlet.init(config());
	}

	@Override
	protected void tearDown() throws Exception {
		_servlet.destroy();
		for (Path dir : new Path[] { _base, _webRoot }) {
			try (Stream<Path> files = Files.walk(dir)) {
				files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
	}

	/** A share link made in a space opens the shared album — its address must lead back into the space. */
	public void testShareLinkMadeInsideASpaceOpens() throws Exception {
		String alice = pair("/alice/data/", "alice");
		FakeResponse created = post("/alice/data/2024 alice/", "share", alice,
			"{\"label\":\"Grandma\",\"expires\":\"\",\"maxPrivacy\":0,\"minRating\":0,\"rights\":[{\"name\":\"view\"}]}");
		assertEquals(created.body(), HttpServletResponse.SC_OK, created.status());
		ShareLinkCreated link = ShareLinkCreated.readShareLinkCreated(reader(created.body()));
		String url = link.getUrl();
		assertTrue("The link must lead into the space it was made in: " + url, url.startsWith("/valbum/alice/"));

		// The app served at the link's address asks its data root with the token as bearer.
		String appPath = url.substring("/valbum".length());
		FakeResponse app = get(appPath, null, null);
		assertEquals(HttpServletResponse.SC_OK, app.status());
		FakeResponse auth = get("/alice/data/", "auth", link.getToken());
		assertEquals(auth.body(), HttpServletResponse.SC_OK, auth.status());
		AuthInfo info = AuthInfo.readAuthInfo(reader(auth.body()));
		assertNotNull("A link caller carries its share info.", info.getShare());
		assertEquals("The link's root is the shared album.", HttpServletResponse.SC_OK,
			get("/alice/data/", "json", link.getToken()).status());
		// And it is worth nothing in the other space.
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, get("/bob/data/", "json", link.getToken()).status());
	}

	/** A device code and an invitation of one space pair nobody into another. */
	public void testCodesAndInvitationsStayInTheirSpace() throws Exception {
		String alice = pair("/alice/data/", "alice");
		FakeResponse code = post("/alice/data/", "device-code", alice, "{}");
		assertEquals(code.body(), HttpServletResponse.SC_OK, code.status());
		String theCode = DeviceCodeCreated.readDeviceCodeCreated(reader(code.body())).getCode();
		FakeResponse inBob = post("/bob/data/", "pair", null,
			"{\"deviceCode\":\"" + theCode + "\",\"deviceName\":\"Stranger\"}");
		assertTrue("A code of alice must not pair into bob: " + inBob.status(),
			inBob.status() == HttpServletResponse.SC_UNAUTHORIZED || inBob.status() == HttpServletResponse.SC_GONE);

		FakeResponse invited = post("/alice/data/", "invite", alice, "{\"role\":\"member\",\"expires\":\"\",\"note\":\"\"}");
		assertEquals(invited.body(), HttpServletResponse.SC_OK, invited.status());
		InvitationCreated invitation = InvitationCreated.readInvitationCreated(reader(invited.body()));
		assertTrue("The invitation must lead into its space: " + invitation.getUrl(),
			invitation.getUrl().startsWith("/valbum/alice/"));
		FakeResponse accepted = post("/bob/data/", "pair", null,
			"{\"invitation\":\"" + invitation.getToken() + "\",\"userName\":\"carol\",\"deviceName\":\"Phone\"}");
		assertTrue("An invitation of alice must not create a user in bob: " + accepted.status(),
			accepted.status() == HttpServletResponse.SC_UNAUTHORIZED || accepted.status() == HttpServletResponse.SC_GONE);
		assertFalse(Files.exists(_base.resolve("bob").resolve("carol")));
	}

	/** A path cannot climb out of its space. */
	public void testNoClimbingBetweenSpaces() throws Exception {
		String alice = pair("/alice/data/", "alice");
		FakeResponse climb = get("/alice/data/../bob/2024 bob/", "json", alice);
		assertTrue("Climbing out of the space must be refused: " + climb.status() + " " + climb.body(),
			climb.status() >= 400);
		assertFalse(climb.body().contains("2024 bob"));
		FakeResponse tilde = get("/alice/data/~bob/2024 bob/", "json", alice);
		assertTrue("The other-space form of Phase 3 leads nowhere: " + tilde.status(),
			tilde.status() >= 400);
	}

	/** The spaces under test; each has a seat code of its own, see issue #89. */
	private Spaces _spaces;

	private String pair(String pathInfo, String userName) throws Exception {
		String segment = pathInfo.substring(1, pathInfo.indexOf('/', 1));
		String code = Codes.forOwner(_spaces.bySegment(segment).getAuth());
		FakeResponse response = post(pathInfo, "pair", null, Codes.pairRequest(code, "Phone", userName));
		assertEquals("Pairing failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return PairResponse.readPairResponse(reader(response.body())).getToken();
	}

	private FakeResponse get(String pathInfo, String type, String token) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		if (type != null) {
			parameters.put("type", type);
		}
		FakeResponse response = new FakeResponse();
		_servlet.service(request("GET", pathInfo, parameters, headers(token), new byte[0], null), response.response());
		return response;
	}

	private FakeResponse post(String pathInfo, String action, String token, String body) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", action);
		FakeResponse response = new FakeResponse();
		_servlet.service(request("POST", pathInfo, parameters, headers(token),
			body.getBytes(StandardCharsets.UTF_8), "application/json"), response.response());
		return response;
	}

	private static Map<String, String> headers(String token) {
		Map<String, String> headers = new HashMap<>();
		if (token != null) {
			headers.put("Authorization", "Bearer " + token);
		}
		return headers;
	}

	private static JsonReader reader(String contents) {
		return new JsonReader(new ReaderAdapter(new StringReader(contents)));
	}
}
