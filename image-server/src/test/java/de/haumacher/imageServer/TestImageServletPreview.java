/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Test case for how a failing preview generator is reported, see issue #68.
 *
 * <p>
 * A failure of the preview generator is a failure of this server: it is answered with
 * <code>500</code> and an {@link de.haumacher.imageServer.shared.model.ErrorInfo} body, not with
 * the former <code>404</code> that told the app the image was gone. A file that really is not
 * there stays a <code>404</code>.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestImageServletPreview extends TestCase {

	private Path _base;

	private ImageServlet _servlet;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-preview-servlet-test");
		_servlet = new ImageServlet(_base.toFile());
	}

	@Override
	protected void tearDown() throws Exception {
		if (_servlet != null) {
			_servlet.destroy();
		}
		if (_base != null) {
			try (Stream<Path> files = Files.walk(_base)) {
				files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
		super.tearDown();
	}

	/** A file that is named like a JPEG but holds no image data at all. */
	public void testBrokenImageAnswersServerError() throws Exception {
		Files.write(new File(_base.toFile(), "broken.jpg").toPath(),
			"This is not a JPEG.".getBytes(StandardCharsets.UTF_8));

		FakeResponse response = thumbnail("/broken.jpg");

		assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, response.status());
		String body = response.body();
		assertTrue(body, body.contains("ErrorInfo"));
		assertTrue(body, body.contains(ImageServlet.PREVIEW_FAILED));
		assertEquals("no-store", response.header("Cache-Control"));
	}

	/** Nothing there is still nothing there. */
	public void testMissingImageStaysNotFound() throws Exception {
		FakeResponse response = thumbnail("/absent.jpg");

		assertEquals(HttpServletResponse.SC_NOT_FOUND, response.status());
	}

	private FakeResponse thumbnail(String pathInfo) throws Exception {
		FakeResponse response = new FakeResponse();
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "tn");
		_servlet.doGet(
			TestImageServletPut.request(pathInfo, null, new byte[0], Collections.emptyMap(), parameters),
			response.response());
		return response;
	}

}
