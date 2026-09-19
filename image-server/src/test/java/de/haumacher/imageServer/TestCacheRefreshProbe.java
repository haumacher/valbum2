/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.shared.model.CacheRefreshed;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import junit.framework.TestCase;

/**
 * Probe for issue #98: the cache refresh composed with a folder name carrying blanks, the root
 * folder, and a symbolic link inside the cache that points at an original — the one shape in which
 * "delete a cache file" could reach a photo.
 */
@SuppressWarnings("javadoc")
public class TestCacheRefreshProbe extends TestCase {

	/** The delivered test's fixture, driven from here so that its tests do not run twice. */
	private final TestCacheRefresh _server = new TestCacheRefresh();

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_server.setUp();
		_server.signIn();
	}

	@Override
	protected void tearDown() throws Exception {
		_server.tearDown();
		super.tearDown();
	}

	/** An album whose folder name carries blanks is addressed as every other folder is. */
	public void testAFolderWithBlanksInItsName() throws Exception {
		_server.seedCache(TestCacheRefresh.ALBUM);
		Map<String, String> before = _server.hashes();

		CacheRefreshed answer = _server.refresh("/" + TestCacheRefresh.ALBUM + "/", _server._adminToken);
		assertEquals(4, answer.getRemoved());
		assertEquals("Nothing outside the cache changed.", before, _server.hashes());
	}

	/** The root folder is a folder too: its own cache goes, the albums' caches stay. */
	public void testTheRootFolder() throws Exception {
		_server.seedCache("");
		File albumCache = _server.seedCache(TestCacheRefresh.ALBUM);
		Map<String, String> before = _server.hashes();

		CacheRefreshed answer = _server.refresh("/", _server._adminToken);
		assertEquals(4, answer.getRemoved());
		assertEquals("The album's cache is not the root's.", 5, albumCache.listFiles().length);
		assertEquals(before, _server.hashes());
	}

	/**
	 * A link named like a preview but pointing at an original: the link is a cache file and goes,
	 * the photo it pointed at stays, byte for byte.
	 */
	public void testALinkToAnOriginalIsRemovedAsALinkOnly() throws Exception {
		File cacheDir = _server.seedCache(TestCacheRefresh.ALBUM);
		File album = new File(_server._base.toFile(), TestCacheRefresh.ALBUM);
		File[] photos = album.listFiles((dir, name) -> name.toLowerCase().endsWith(".jpg"));
		assertTrue("The fixture album has photos.", photos != null && photos.length > 0);
		Path original = photos[0].toPath();
		Path link = new File(cacheDir, "preview-link.jpg").toPath();
		try {
			Files.createSymbolicLink(link, original);
		} catch (UnsupportedOperationException | java.io.IOException ex) {
			// A file system without links: nothing to probe here.
			return;
		}
		Map<String, String> before = _server.hashes();
		long size = Files.size(original);

		CacheRefreshed answer = _server.refresh("/" + TestCacheRefresh.ALBUM + "/", _server._adminToken);
		assertEquals("The four seeded files and the link.", 5, answer.getRemoved());
		assertFalse("The link is gone.", Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS));
		assertTrue("The original is still there.", Files.exists(original));
		assertEquals(size, Files.size(original));
		assertEquals("Nothing outside the cache changed.", before, _server.hashes());
	}

	/** A GET with the action does nothing: the refresh is a POST like every other action. */
	public void testTheActionIsAPost() throws Exception {
		_server.seedCache(TestCacheRefresh.ALBUM);
		File cacheDir = new File(new File(_server._base.toFile(), TestCacheRefresh.ALBUM), PreviewCache.CACHE_DIRECTORY_NAME);
		int filesBefore = cacheDir.listFiles().length;

		FakeResponse response = new FakeResponse();
		Map<String, String> parameters = new java.util.HashMap<>();
		parameters.put("action", "refresh-cache");
		Map<String, String> headers = new java.util.HashMap<>();
		headers.put("Authorization", "Bearer " + _server._adminToken);
		_server._servletForProbe().doGet(
			TestImageServletPut.request("/" + TestCacheRefresh.ALBUM + "/", null, new byte[0], headers, parameters),
			response.response());
		assertTrue("A GET never deletes: status " + response.status(), response.status() != HttpServletResponse.SC_NO_CONTENT);
		assertEquals("Nothing was deleted by a GET.", filesBefore, cacheDir.listFiles().length);
	}

}
