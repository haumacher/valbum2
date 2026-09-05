/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Test case for {@link ImageServlet#doPut(HttpServletRequest, HttpServletResponse)}, especially for
 * a PUT addressing the data root.
 *
 * <p>
 * The servlet is driven directly on a temporary base folder with hand-rolled request and response
 * fakes; no server and no fixture album is involved.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestImageServletPut extends TestCase {

	private static final String ALBUM_JSON = "[\"AlbumInfo\",{\"title\":\"Root\",\"parts\":[]}]";

	private static final String LISTING_JSON = "[\"ListingInfo\",{\"title\":\"Root listing\"}]";

	private Path _base;

	private ImageServlet _servlet;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-put-test");
		_servlet = new ImageServlet(_base.toFile());
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

	public void testPutToRootStoresIndexJson() throws Exception {
		FakeResponse response = put("/", "application/json", ALBUM_JSON);

		assertEquals(HttpServletResponse.SC_OK, response.status());
		assertEquals(ALBUM_JSON, indexContents());
	}

	public void testPutToRootWithoutTrailingSlash() throws Exception {
		FakeResponse response = put(null, "application/json", LISTING_JSON);

		assertEquals(HttpServletResponse.SC_OK, response.status());
		assertEquals(LISTING_JSON, indexContents());
	}

	public void testSecondPutCreatesBackup() throws Exception {
		put("/", "application/json", ALBUM_JSON);
		File index = new File(_base.toFile(), "index.json");
		long firstModified = index.lastModified();

		FakeResponse response = put("/", "application/json", LISTING_JSON);

		assertEquals(HttpServletResponse.SC_OK, response.status());
		assertEquals(LISTING_JSON, indexContents());

		File backup = new File(_base.toFile(), "index.json." + firstModified);
		assertTrue("Expected backup file " + backup.getName() + ", found: " + list(),
			backup.exists());
		assertEquals(ALBUM_JSON, read(backup));
	}

	public void testNonJsonBodyRejected() throws Exception {
		FakeResponse response = put("/", "text/plain", ALBUM_JSON);

		assertEquals(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, response.status());
		assertFalse(new File(_base.toFile(), "index.json").exists());
	}

	public void testUnparsableBodyRejectedAndIndexUntouched() throws Exception {
		put("/", "application/json", ALBUM_JSON);

		FakeResponse response = put("/", "application/json", "this is not JSON at all");

		assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status());
		assertEquals("The previous sidecar must survive a rejected PUT.", ALBUM_JSON, indexContents());
		assertEquals("A rejected PUT must not leave a backup behind.", 1, indexFileCount());
	}

	public void testNonFolderResourceRejected() throws Exception {
		FakeResponse response = put("/", "application/json", "[\"Heading\",{\"text\":\"nope\"}]");

		assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status());
		assertFalse(new File(_base.toFile(), "index.json").exists());
	}

	public void testPutToNestedFolder() throws Exception {
		File folder = new File(_base.toFile(), "album");
		assertTrue(folder.mkdir());

		FakeResponse response = put("/album/", "application/json", ALBUM_JSON);

		assertEquals(HttpServletResponse.SC_OK, response.status());
		assertEquals(ALBUM_JSON, read(new File(folder, "index.json")));
	}

	public void testPathEscapeRejected() throws Exception {
		FakeResponse response = put("/../escape/", "application/json", ALBUM_JSON);

		assertEquals(HttpServletResponse.SC_NOT_FOUND, response.status());
	}

	private String indexContents() throws IOException {
		return read(new File(_base.toFile(), "index.json"));
	}

	private static String read(File file) throws IOException {
		return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
	}

	private int indexFileCount() {
		String[] names = _base.toFile().list((dir, name) -> name.startsWith("index.json"));
		return names == null ? 0 : names.length;
	}

	private String list() {
		String[] names = _base.toFile().list();
		return names == null ? "<none>" : String.join(", ", names);
	}

	private FakeResponse put(String pathInfo, String contentType, String body) throws Exception {
		FakeResponse response = new FakeResponse();
		_servlet.doPut(request(pathInfo, contentType, body.getBytes(StandardCharsets.UTF_8)), response.response());
		return response;
	}

	private static HttpServletRequest request(String pathInfo, String contentType, byte[] body) {
		InvocationHandler handler = new InvocationHandler() {
			@Override
			public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
				switch (method.getName()) {
					case "getPathInfo":
						return pathInfo;
					case "getContentType":
						return contentType;
					case "getCharacterEncoding":
						return "utf-8";
					case "getContentLengthLong":
						return Long.valueOf(body.length);
					case "getInputStream":
						return new FakeInputStream(body);
					case "getHeader":
						return null;
					case "toString":
						return "FakeRequest[" + pathInfo + "]";
					default:
						throw new UnsupportedOperationException(
							"Unexpected request method in test: " + method.getName());
				}
			}
		};
		return (HttpServletRequest) Proxy.newProxyInstance(TestImageServletPut.class.getClassLoader(),
			new Class<?>[] { HttpServletRequest.class }, handler);
	}

	static final class FakeInputStream extends ServletInputStream {

		private final ByteArrayInputStream _in;

		FakeInputStream(byte[] contents) {
			_in = new ByteArrayInputStream(contents);
		}

		@Override
		public int read() {
			return _in.read();
		}

		@Override
		public int read(byte[] b, int off, int len) {
			return _in.read(b, off, len);
		}

		@Override
		public boolean isFinished() {
			return _in.available() == 0;
		}

		@Override
		public boolean isReady() {
			return true;
		}

		@Override
		public void setReadListener(ReadListener readListener) {
			throw new UnsupportedOperationException();
		}
	}

	static final class FakeResponse implements InvocationHandler {

		private int _status = HttpServletResponse.SC_OK;

		private final Map<String, String> _headers = new HashMap<>();

		private final HttpServletResponse _response = (HttpServletResponse) Proxy.newProxyInstance(
			TestImageServletPut.class.getClassLoader(), new Class<?>[] { HttpServletResponse.class }, this);

		HttpServletResponse response() {
			return _response;
		}

		int status() {
			return _status;
		}

		String header(String name) {
			return _headers.get(name);
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			switch (method.getName()) {
				case "setStatus":
					_status = ((Integer) args[0]).intValue();
					return null;
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
				case "toString":
					return "FakeResponse[" + _status + "]";
				default:
					throw new UnsupportedOperationException(
						"Unexpected response method in test: " + method.getName());
			}
		}
	}

}
