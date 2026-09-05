package de.haumacher.util.servlet;

import java.nio.charset.StandardCharsets;
import junit.framework.TestCase;

/**
 * The Flutter index page's {@code <base href>} must follow the servlet context path.
 */
public class TestIndexRebase extends TestCase {

	private static final String INDEX = "<!DOCTYPE html><html><head>\n  <base href=\"/\">\n  <title>x</title></head></html>";

	public void testRebasedToContext() {
		assertEquals(INDEX.replace("href=\"/\"", "href=\"/valbum/\""), rebase(INDEX, "/valbum"));
	}

	public void testRootContextUntouched() {
		assertEquals(INDEX, rebase(INDEX, ""));
		assertEquals(INDEX, rebase(INDEX, null));
	}

	public void testExplicitBaseUntouched() {
		String explicit = INDEX.replace("href=\"/\"", "href=\"/photos/\"");
		assertEquals(explicit, rebase(explicit, "/valbum"));
	}

	public void testOnlyIndexIsRebased() {
		assertTrue(ResourceServlet.isIndex("index.html"));
		assertTrue(ResourceServlet.isIndex("sub/index.html"));
		assertFalse(ResourceServlet.isIndex("main.dart.js"));
		assertFalse(ResourceServlet.isIndex("notindex.html"));
	}

	private static String rebase(String html, String contextPath) {
		return new String(ResourceServlet.rebaseIndex(html.getBytes(StandardCharsets.UTF_8), contextPath),
			StandardCharsets.UTF_8);
	}
}
