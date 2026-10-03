/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.InviteMode;
import de.haumacher.imageServer.auth.SpaceStore;
import de.haumacher.imageServer.auth.Spaces;
import de.haumacher.imageServer.oidc.MockOidcIssuer;
import de.haumacher.imageServer.shared.model.ContactCredential;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.OidcStarted;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import java.io.File;
import java.io.StringReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import junit.framework.TestCase;
import org.eclipse.jetty.server.Server;

/**
 * Test case for "Continue with Google" through the real server, see issue #200: the callback is
 * one address at the context root of a multi-space server, beside the spaces, and the sign-in
 * started in a space returns to that space's link.
 */
@SuppressWarnings("javadoc")
public class TestOidcServer extends TestCase {

	private Path _base;

	private Server _server;

	private MockOidcIssuer _issuer;

	private final HttpClient _client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

	private String _root;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-oidc");
		Path family = _base.resolve("family");
		Files.createDirectories(family);
		SharingFixture.create(family);
		SpaceStore.create(family, "Family", SpaceStore.ANONYMOUS_NONE, SpaceStore.FACES_OFF);
		_issuer = new MockOidcIssuer();
	}

	@Override
	protected void tearDown() throws Exception {
		if (_server != null) {
			_server.stop();
		}
		_issuer.close();
		try (Stream<Path> files = Files.walk(_base)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		super.tearDown();
	}

	private void start(boolean configured) throws Exception {
		int port;
		try (ServerSocket socket = new ServerSocket(0)) {
			port = socket.getLocalPort();
		}
		_root = "http://127.0.0.1:" + port + "/valbum";
		Map<String, String> env = new HashMap<>();
		if (configured) {
			env.put("VALBUM_PUBLIC_URL", _root);
			env.put("VALBUM_OIDC_GOOGLE_CLIENT_ID", MockOidcIssuer.CLIENT_ID);
			env.put("VALBUM_OIDC_GOOGLE_CLIENT_SECRET", MockOidcIssuer.SECRET);
			env.put("VALBUM_OIDC_GOOGLE_DISCOVERY_URL", _issuer.discoveryUrl());
		}
		Spaces spaces = Spaces.detect(_base, null, AuthMode.WRITES, InviteMode.MEMBERS);
		_server = Main.createServer(port, "/valbum", _base.toFile(), null, spaces, ServerEnvironment.read(env));
		_server.start();
	}

	public void testASignInStartedInASpaceReturnsToItsLink() throws Exception {
		start(true);
		String data = _root + "/family/data/";
		HttpResponse<String> shared = post(data + "2024/2024-05-01%20Zoo/?action=share",
			PersonalLinkTestCase.personalBody("Party"), SharingFixture.ALICE, null);
		assertEquals(shared.body(), 200, shared.statusCode());
		String token = ShareLinkCreated.readShareLinkCreated(reader(shared.body())).getToken();

		HttpResponse<String> asked = get(data + "?type=json", token, null);
		assertEquals(401, asked.statusCode());
		assertTrue(asked.body(), asked.body().contains("oidc:google"));

		HttpResponse<String> started = post(data + "?action=oidc-start", "{\"provider\":\"google\",\"remember\":true}",
			token, null);
		assertEquals(started.body(), 200, started.statusCode());
		OidcStarted answer = OidcStarted.readOidcStarted(reader(started.body()));
		assertEquals(_root + "/oidc/callback", MockOidcIssuer.redirectUri(answer.getUrl()));

		Map<String, String[]> back = _issuer.authorize(answer.getUrl());
		HttpResponse<String> callback = get(_root + "/oidc/callback?code=" + encode(back.get("code")[0]) + "&state="
			+ encode(back.get("state")[0]), null, null);
		assertEquals(callback.body(), 303, callback.statusCode());
		String location = callback.headers().firstValue("Location").orElse("");
		String prefix = _root + "/family/s/" + token + "/#oidc=";
		assertTrue(location, location.startsWith(prefix));

		HttpResponse<String> exchanged = post(data + "?action=oidc-exchange", "{\"code\":\""
			+ location.substring(prefix.length()) + "\",\"binding\":\"" + answer.getBinding() + "\"}", token, null);
		assertEquals(exchanged.body(), 200, exchanged.statusCode());
		ContactCredential credential = ContactCredential.readContactCredential(reader(exchanged.body()));
		assertEquals("Vera", credential.getContact().getDisplayName());

		assertEquals(200, get(data + "?type=json", token, credential.getCredential()).statusCode());

		HttpResponse<String> again = get(_root + "/oidc/callback?code=" + encode(back.get("code")[0]) + "&state="
			+ encode(back.get("state")[0]), null, null);
		assertEquals("A state is taken back once.", 400, again.statusCode());
		assertEquals("text/html;charset=utf-8", again.headers().firstValue("Content-Type").orElse("").replace(" ", "")
			.toLowerCase());
		assertEquals(404, get(_root + "/oidc/elsewhere", null, null).statusCode());
	}

	public void testWithoutAProviderTheCallbackIsNotThere() throws Exception {
		start(false);
		HttpResponse<String> callback = get(_root + "/oidc/callback?code=c&state=s", null, null);
		assertEquals(404, callback.statusCode());
		Resource answer = Resource.readResource(reader(callback.body()));
		assertEquals(OidcCallbackServlet.NOT_HERE, ((ErrorInfo) answer).getMessage());
		assertEquals(0, _issuer.tokenRequests());
	}

	private HttpResponse<String> get(String url, String bearer, String credential) throws Exception {
		return _client.send(request(url, bearer, credential).GET().build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> post(String url, String body, String bearer, String credential) throws Exception {
		return _client.send(request(url, bearer, credential).header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
	}

	private static HttpRequest.Builder request(String url, String bearer, String credential) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url));
		if (bearer != null) {
			builder.header("Authorization", "Bearer " + bearer);
		}
		if (credential != null) {
			builder.header("X-VAlbum-Contact", credential);
		}
		return builder;
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static JsonReader reader(String json) {
		return new JsonReader(new ReaderAdapter(new StringReader(json)));
	}
}
