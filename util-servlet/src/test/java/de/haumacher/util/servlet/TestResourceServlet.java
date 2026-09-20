/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.util.servlet;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Test case for {@link ResourceServlet}, especially the virtual bases of the share links (issue
 * #51) and of the invitations (issue #52).
 *
 * <p>
 * The servlet is driven directly on a temporary web root with hand-rolled request and response
 * fakes; no server is involved.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestResourceServlet extends TestCase {

	private static final String INDEX =
		"<!DOCTYPE html><html><head><base href=\"/\"><title>VAlbum</title></head><body></body></html>";

	private static final String SCRIPT = "console.log('the application');";

	private static final String CONTEXT = "/valbum";

	private Path _webRoot;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_webRoot = Files.createTempDirectory("valbum-webroot-test");
		Files.write(_webRoot.resolve("index.html"), INDEX.getBytes(StandardCharsets.UTF_8));
		Files.write(_webRoot.resolve("main.dart.js"), SCRIPT.getBytes(StandardCharsets.UTF_8));
	}

	@Override
	protected void tearDown() throws Exception {
		if (_webRoot != null) {
			try (Stream<Path> files = Files.walk(_webRoot)) {
				files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
		super.tearDown();
	}

	public void testTheContextRootIsUnchanged() throws Exception {
		FakeResponse response = get("/");
		assertEquals(HttpServletResponse.SC_OK, response.status());
		assertTrue(response.body(), response.body().contains("<base href=\"" + CONTEXT + "/\">"));
	}

	public void testAVirtualBaseServesTheApplicationWithItsOwnBaseHref() throws Exception {
		FakeResponse response = get("/s/abc/");
		assertEquals(HttpServletResponse.SC_OK, response.status());
		assertTrue(response.body(), response.body().contains("<base href=\"" + CONTEXT + "/s/abc/\">"));
	}

	public void testAVirtualBaseWithoutATrailingSlashIsTheEntryPointToo() throws Exception {
		FakeResponse response = get("/s/abc");
		assertEquals(HttpServletResponse.SC_OK, response.status());
		assertTrue(response.body(), response.body().contains("<base href=\"" + CONTEXT + "/s/abc/\">"));
	}

	public void testAssetsBelowAVirtualBaseAreTheSameFiles() throws Exception {
		assertEquals(SCRIPT, get("/main.dart.js").body());
		assertEquals("The same bytes, wherever the application is mounted.", SCRIPT,
			get("/s/abc/main.dart.js").body());
		assertEquals(SCRIPT, get("/s/another-token/main.dart.js").body());
	}

	public void testADeepRouteBelowAVirtualBaseFallsBackToTheApplication() throws Exception {
		FakeResponse response = get("/s/abc/some/deep/route");
		assertEquals(HttpServletResponse.SC_OK, response.status());
		assertTrue(response.body(), response.body().contains("<base href=\"" + CONTEXT + "/s/abc/\">"));
	}

	public void testWithoutAPrefixAVirtualBaseIsAPlainRoute() throws Exception {
		// No virtual prefix configured: "/s/abc/main.dart.js" is a route of the application at the
		// context root, and the index page keeps the context path as its base.
		FakeResponse response = get("/s/abc/main.dart.js", new String[0]);
		assertEquals(HttpServletResponse.SC_OK, response.status());
		assertTrue(response.body(), response.body().contains("<base href=\"" + CONTEXT + "/\">"));
	}

	public void testAMissingFileIsStillMissing() throws Exception {
		// A path with an extension that the web root does not hold falls back to the application,
		// exactly as it does at the context root: the application decides what to show.
		assertEquals(HttpServletResponse.SC_OK, get("/s/abc/album/IMG_0417.JPG").status());
	}

	public void testAnInvitationIsServedTheApplicationToo() throws Exception {
		FakeResponse response = get("/i/abc/");
		assertEquals(HttpServletResponse.SC_OK, response.status());
		assertTrue(response.body(), response.body().contains("<base href=\"" + CONTEXT + "/i/abc/\">"));
	}

	public void testADeepRouteBelowAnInvitationFallsBackToTheApplication() throws Exception {
		FakeResponse response = get("/i/abc/deep/route");
		assertEquals(HttpServletResponse.SC_OK, response.status());
		assertTrue(response.body(), response.body().contains("<base href=\"" + CONTEXT + "/i/abc/\">"));
	}

	public void testAnIncompleteOrUnknownVirtualBaseIsAPlainRoute() throws Exception {
		// "/i/" names no token and "/x/abc/" no prefix this handler serves: both are routes of the
		// application at the context root, which keeps the context path as its base.
		assertTrue(get("/i/").body().contains("<base href=\"" + CONTEXT + "/\">"));
		assertTrue(get("/x/abc/").body().contains("<base href=\"" + CONTEXT + "/\">"));
	}

	// --- The hooks of issue #104. ---

	/** A page load below a session base goes through the decorator, which is told where it is. */
	public void testThePageOfASessionIsDecorated() throws Exception {
		List<String> asked = new ArrayList<>();
		FakeResponse response = decorated("/s/abc/", (request, html, appBase, prefix, token) -> {
			asked.add(appBase + "|" + prefix + "|" + token);
			return html.replace("</head>", "<meta property=\"og:title\" content=\"Zoo\"></head>");
		});
		assertEquals(Arrays.asList("|s|abc"), asked);
		assertTrue(response.body(), response.body().contains("<meta property=\"og:title\" content=\"Zoo\">"));
		assertTrue("The base is rewritten before the page is decorated: " + response.body(),
			response.body().contains("<base href=\"" + CONTEXT + "/s/abc/\">"));
	}

	/** The context root is no session: nothing is asked and nothing is changed. */
	public void testThePageOfTheContextRootIsNotDecorated() throws Exception {
		List<String> asked = new ArrayList<>();
		FakeResponse response = decorated("/", (request, html, appBase, prefix, token) -> {
			asked.add(prefix);
			return "decorated";
		});
		assertEquals(Arrays.asList(), asked);
		assertTrue(response.body(), response.body().contains("<base href=\"" + CONTEXT + "/\">"));
	}

	/** A decorator answering <code>null</code> leaves the page as it is. */
	public void testADecoratorMayLeaveThePageAlone() throws Exception {
		FakeResponse response = decorated("/s/abc/", (request, html, appBase, prefix, token) -> null);
		assertTrue(response.body(), response.body().contains("<base href=\"" + CONTEXT + "/s/abc/\">"));
	}

	/** A file below a session base is no page: it is served, not decorated. */
	public void testAnAssetIsNotDecorated() throws Exception {
		List<String> asked = new ArrayList<>();
		FakeResponse response = decorated("/s/abc/main.dart.js", (request, html, appBase, prefix, token) -> {
			asked.add(prefix);
			return "decorated";
		});
		assertEquals(Arrays.asList(), asked);
		assertEquals(SCRIPT, response.body());
	}

	/** A resource of the session itself is answered before anything is looked for in the web root. */
	public void testASessionResourceIsAnsweredBeforeTheWebRoot() throws Exception {
		ResourceServlet servlet = servlet("s", "i");
		servlet.setSessionResource((request, response, appBase, prefix, token, relative) -> {
			if (!"/cover.jpg".equals(relative)) {
				return false;
			}
			response.setContentType("image/jpeg");
			response.getOutputStream().write((prefix + ":" + token).getBytes(StandardCharsets.UTF_8));
			return true;
		});
		FakeResponse response = new FakeResponse();
		servlet.doGet(request("/s/abc/cover.jpg"), response.response());
		assertEquals(HttpServletResponse.SC_OK, response.status());
		assertEquals("s:abc", response.body());

		FakeResponse other = new FakeResponse();
		servlet.doGet(request("/s/abc/main.dart.js"), other.response());
		assertEquals("What the hook does not answer is served from the web root as before.", SCRIPT,
			other.body());
	}

	private FakeResponse decorated(String pathInfo, ResourceServlet.PageDecorator decorator) throws Exception {
		ResourceServlet servlet = servlet("s", "i");
		servlet.setPageDecorator(decorator);
		FakeResponse response = new FakeResponse();
		servlet.doGet(request(pathInfo), response.response());
		return response;
	}

	private ResourceServlet servlet(String... virtualPrefix) throws Exception {
		ResourceServlet servlet = new ResourceServlet(_webRoot, "/data", virtualPrefix);
		servlet.init(config());
		return servlet;
	}

	private FakeResponse get(String pathInfo) throws Exception {
		return get(pathInfo, "s", "i");
	}

	private FakeResponse get(String pathInfo, String... virtualPrefix) throws Exception {
		ResourceServlet servlet = servlet(virtualPrefix);
		FakeResponse response = new FakeResponse();
		servlet.doGet(request(pathInfo), response.response());
		return response;
	}

	private static ServletConfig config() {
		ServletContext context = (ServletContext) Proxy.newProxyInstance(
			TestResourceServlet.class.getClassLoader(), new Class<?>[] { ServletContext.class },
			(proxy, method, args) -> {
				switch (method.getName()) {
					case "getMimeType":
						return "text/html";
					case "toString":
						return "FakeServletContext";
					default:
						return null;
				}
			});
		return (ServletConfig) Proxy.newProxyInstance(TestResourceServlet.class.getClassLoader(),
			new Class<?>[] { ServletConfig.class }, (proxy, method, args) -> {
				switch (method.getName()) {
					case "getServletContext":
						return context;
					case "getServletName":
						return "resources";
					case "getInitParameterNames":
						return java.util.Collections.enumeration(java.util.Collections.<String> emptyList());
					case "toString":
						return "FakeServletConfig";
					default:
						return null;
				}
			});
	}

	private static HttpServletRequest request(String pathInfo) {
		return (HttpServletRequest) Proxy.newProxyInstance(TestResourceServlet.class.getClassLoader(),
			new Class<?>[] { HttpServletRequest.class }, (proxy, method, args) -> {
				switch (method.getName()) {
					case "getPathInfo":
						return pathInfo;
					case "getContextPath":
						return CONTEXT;
					case "getMethod":
						return "GET";
					case "toString":
						return "FakeRequest[" + pathInfo + "]";
					default:
						return null;
				}
			});
	}

	static final class FakeResponse implements InvocationHandler {

		private int _status = HttpServletResponse.SC_OK;

		private final Map<String, String> _headers = new HashMap<>();

		private final ByteArrayOutputStream _body = new ByteArrayOutputStream();

		private final HttpServletResponse _response = (HttpServletResponse) Proxy.newProxyInstance(
			TestResourceServlet.class.getClassLoader(), new Class<?>[] { HttpServletResponse.class }, this);

		HttpServletResponse response() {
			return _response;
		}

		int status() {
			return _status;
		}

		String body() {
			return new String(_body.toByteArray(), StandardCharsets.UTF_8);
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			switch (method.getName()) {
				case "setStatus":
				case "sendError":
					_status = ((Integer) args[0]).intValue();
					return null;
				case "getStatus":
					return Integer.valueOf(_status);
				case "setHeader":
					_headers.put((String) args[0], (String) args[1]);
					return null;
				case "setContentType":
				case "setCharacterEncoding":
				case "setContentLength":
				case "setContentLengthLong":
					return null;
				case "getOutputStream":
					return new jakarta.servlet.ServletOutputStream() {
						@Override
						public void write(int b) {
							_body.write(b);
						}

						@Override
						public boolean isReady() {
							return true;
						}

						@Override
						public void setWriteListener(jakarta.servlet.WriteListener listener) {
							throw new UnsupportedOperationException();
						}
					};
				case "toString":
					return "FakeResponse[" + _status + "]";
				default:
					throw new UnsupportedOperationException(
						"Unexpected response method in test: " + method.getName());
			}
		}
	}
}
