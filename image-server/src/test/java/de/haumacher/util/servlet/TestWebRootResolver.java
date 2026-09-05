/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.util.servlet;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import junit.framework.TestCase;

/**
 * Test case for {@link WebRootResolver}.
 */
@SuppressWarnings("javadoc")
public class TestWebRootResolver extends TestCase {

	private static final Predicate<String> WEB_APP =
		files("index.html", "main.dart.js", "assets/fonts/MaterialIcons-Regular.otf", "sub/file.txt");

	private static final Predicate<String> NO_WEB_APP = files();

	public void testRoot() {
		assertEquals("index.html", resolve("/"));
		assertEquals("index.html", resolve(""));
		assertEquals("index.html", resolve(null));
	}

	public void testExistingFile() {
		assertEquals("sub/file.txt", resolve("/sub/file.txt"));
		assertEquals("main.dart.js", resolve("/main.dart.js"));
		assertEquals("assets/fonts/MaterialIcons-Regular.otf", resolve("/assets/fonts/MaterialIcons-Regular.otf"));
	}

	public void testDeepLinkFallsBackToIndex() {
		assertEquals("index.html", resolve("/2005-08-24 Blumen und Fliegen/"));
		assertEquals("index.html", resolve("/2005-08-24 Blumen und Fliegen"));
		assertEquals("index.html", resolve("/some/route/"));
		assertEquals("index.html", resolve("/sub/"));
	}

	public void testMissingFileWithExtensionIsNotFound() {
		assertNull(resolve("/missing.png"));
		assertNull(resolve("/sub/missing.txt"));
		assertNull(resolve("/some/route/index.html"));
	}

	public void testNoWebApp() {
		assertNull(WebRootResolver.resolve("/", NO_WEB_APP));
		assertNull(WebRootResolver.resolve("/some/route/", NO_WEB_APP));
		assertNull(WebRootResolver.resolve("/missing.png", NO_WEB_APP));
	}

	public void testEscapeIsRejected() {
		assertNull(resolve("/../../etc/passwd"));
		assertNull(resolve("/.."));
		assertNull(resolve("/sub/../../index.html"));
		assertNull(resolve("/a/b/../../../index.html"));
		assertNull(WebRootResolver.normalize("/../x"));
		assertNull(WebRootResolver.normalize("/sub\\..\\index.html"));
	}

	public void testHarmlessRelativeSegments() {
		assertEquals("index.html", resolve("/sub/../index.html"));
		assertEquals("sub/file.txt", resolve("/./sub/./file.txt"));
		assertEquals("sub/file.txt", resolve("//sub//file.txt"));
	}

	public void testNormalize() {
		assertEquals("", WebRootResolver.normalize(null));
		assertEquals("", WebRootResolver.normalize("/"));
		assertEquals("", WebRootResolver.normalize("/a/.."));
		assertEquals("a/b", WebRootResolver.normalize("/a/b"));
		assertEquals("a/b/", WebRootResolver.normalize("/a/b/"));
		assertEquals("a/", WebRootResolver.normalize("/a/b/../"));
	}

	public void testIsRoute() {
		assertTrue(WebRootResolver.isRoute(null));
		assertTrue(WebRootResolver.isRoute("/"));
		assertTrue(WebRootResolver.isRoute("/album/2005"));
		assertFalse(WebRootResolver.isRoute("/main.dart.js"));
		assertFalse(WebRootResolver.isRoute("/a.b/c.d"));
		assertTrue(WebRootResolver.isRoute("/a.b/c"));
	}

	private static String resolve(String pathInfo) {
		return WebRootResolver.resolve(pathInfo, WEB_APP);
	}

	private static Predicate<String> files(String... names) {
		Set<String> existing = new HashSet<>(List.of(names));
		return existing::contains;
	}

}
