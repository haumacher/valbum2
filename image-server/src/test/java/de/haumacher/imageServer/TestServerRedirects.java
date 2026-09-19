/*
 * Copyright (c) 2026 Bernhard Haumacher. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import junit.framework.TestCase;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

/**
 * The server never spells an absolute URL to itself (issue #62).
 *
 * <p>
 * Deployed behind a reverse proxy it is reached as <code>https://home.example.org/valbum/</code>
 * and sees <code>http://localhost:8082/</code>; a redirect made absolute from what it sees would
 * send the browser to localhost.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestServerRedirects extends TestCase {

	private Path _base;

	private Server _server;

	private int _port;

	private final HttpClient _client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-redirect-test");
		Files.createDirectory(_base.resolve("album"));
		AuthService auth = new AuthService(AuthMode.OFF, _base);
		_server = Main.createServer(0, "/valbum", _base.toFile(), null, auth);

		// What a request looks like from inside: a servlet echoing the surface it reports.
		ServletContextHandler probe = new ServletContextHandler();
		probe.setContextPath("/probe");
		probe.addServlet(new ServletHolder(new HttpServlet() {
			@Override
			protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
				resp.getWriter().print(req.getScheme() + " " + req.getServerName() + " " + req.getServerPort() + " "
					+ req.getRequestURL());
			}
		}), "/*");
		// In front of the catch-all redirect at the root context, which would answer it otherwise.
		((HandlerCollection) _server.getHandler()).prependHandler(probe);

		_server.start();
		_port = ((ServerConnector) _server.getConnectors()[0]).getLocalPort();
	}

	@Override
	protected void tearDown() throws Exception {
		if (_server != null) {
			_server.stop();
		}
		if (_base != null) {
			try (Stream<Path> files = Files.walk(_base)) {
				files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
		super.tearDown();
	}

	public void testFolderWithoutSlashRedirectsToPathOnly() throws Exception {
		HttpResponse<String> response = get("/valbum/data/album?type=json");
		assertEquals(302, response.statusCode());
		assertEquals("/valbum/data/album/?type=json", location(response));
	}

	public void testDataRootWithoutSlashRedirectsToPathOnly() throws Exception {
		HttpResponse<String> response = get("/valbum/data?type=json&viewAs=public");
		assertEquals(302, response.statusCode());
		assertEquals("/valbum/data/?type=json&viewAs=public", location(response));
	}

	public void testServerRootRedirectsToContextPathOnly() throws Exception {
		HttpResponse<String> response = get("/");
		assertEquals(302, response.statusCode());
		assertEquals("/valbum/", location(response));
	}

	public void testRedirectStaysRelativeWhateverTheHostHeaderSays() throws Exception {
		HttpResponse<String> response = get("/valbum/data/album?type=json", "X-Forwarded-Host", "home.example.org",
			"X-Forwarded-Proto", "https");
		assertEquals(302, response.statusCode());
		assertEquals("/valbum/data/album/?type=json", location(response));
	}

	public void testRequestReportsTheForwardedSurface() throws Exception {
		HttpResponse<String> response = get("/probe/", "X-Forwarded-Host", "home.example.org", "X-Forwarded-Proto",
			"https");
		assertEquals(200, response.statusCode());
		assertEquals("https home.example.org 443 https://home.example.org/probe/", response.body());
	}

	public void testRequestReportsItsOwnSurfaceWithoutAProxy() throws Exception {
		HttpResponse<String> response = get("/probe/");
		assertEquals(200, response.statusCode());
		assertEquals("http localhost " + _port + " http://localhost:" + _port + "/probe/", response.body());
	}

	private HttpResponse<String> get(String path, String... headers) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + _port + path)).GET();
		if (headers.length > 0) {
			request.headers(headers);
		}
		return _client.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static String location(HttpResponse<?> response) {
		return response.headers().firstValue("Location").orElseThrow(() -> new AssertionError("No Location header."));
	}
}
