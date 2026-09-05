package de.haumacher.util.servlet;

import java.util.Set;
import java.util.function.Predicate;
import junit.framework.TestCase;

/**
 * Probe review for the static web root (issue #10): path shapes the implementation was not written
 * against.
 */
public class TestWebRootProbe extends TestCase {

	private static final Set<String> FILES = Set.of("index.html", "main.dart.js", "assets/fonts/a.otf",
		"2005.08.24 Album/index.html", "sub/index.html");

	private static final Predicate<String> EXISTS = FILES::contains;

	public void testDotInDirectoryNameIsNotAnExtension() {
		// The album folder has dots in its name; the request is a route (trailing slash).
		assertEquals("2005.08.24 Album/index.html", WebRootResolver.resolve("/2005.08.24 Album/", EXISTS));
		// A route whose last segment has a dot but is not a file: a client route like an image name is
		// a file request by definition and must be 404, not the app.
		assertNull(WebRootResolver.resolve("/2005.08.24 Album/IMG_0417.JPG", EXISTS));
	}

	public void testRouteFallbackKeepsAppEvenDeep() {
		assertEquals("index.html", WebRootResolver.resolve("/a/b/c/d", EXISTS));
		assertEquals("index.html", WebRootResolver.resolve("/a/b/c/d/", EXISTS));
	}

	public void testSubdirectoryIndex() {
		assertEquals("sub/index.html", WebRootResolver.resolve("/sub/", EXISTS));
		// Without the trailing slash "sub" is a route: no file "sub" exists, so the app is served.
		assertEquals("index.html", WebRootResolver.resolve("/sub", EXISTS));
	}

	public void testEscapesAreRefused() {
		assertNull(WebRootResolver.resolve("/../index.html", EXISTS));
		assertNull(WebRootResolver.resolve("/sub/../../index.html", EXISTS));
		assertNull(WebRootResolver.resolve("/sub\\..\\index.html", EXISTS));
		assertNull(WebRootResolver.resolve("/index.html\0", EXISTS));
	}

	public void testDotSegmentsInsideRootAreFine() {
		assertEquals("main.dart.js", WebRootResolver.resolve("/sub/../main.dart.js", EXISTS));
		assertEquals("main.dart.js", WebRootResolver.resolve("//sub//.//..//main.dart.js", EXISTS));
	}

	public void testExistingFileWinsOverRouteFallback() {
		assertEquals("assets/fonts/a.otf", WebRootResolver.resolve("/assets/fonts/a.otf", EXISTS));
		assertNull(WebRootResolver.resolve("/assets/fonts/missing.otf", EXISTS));
	}
}
