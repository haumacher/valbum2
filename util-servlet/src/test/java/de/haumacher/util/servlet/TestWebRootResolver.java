/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.util.servlet;

import junit.framework.TestCase;

/**
 * Test case for {@link WebRootResolver#virtualBase(String, String...)}, see issues #51 and #52.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestWebRootResolver extends TestCase {

	public void testAVirtualBaseIsTwoSegments() {
		assertEquals("/s/abc", WebRootResolver.virtualBase("/s/abc", "s"));
		assertEquals("/s/abc", WebRootResolver.virtualBase("/s/abc/", "s"));
		assertEquals("/s/abc", WebRootResolver.virtualBase("/s/abc/main.dart.js", "s"));
		assertEquals("/s/abc", WebRootResolver.virtualBase("/s/abc/some/deep/route", "s"));
	}

	public void testATokenIsOpaqueToTheStaticHandler() {
		// Whatever the second segment is, the handler serves the application: whether the token
		// opens anything is the JSON API's business, see issue #51.
		assertEquals("/s/AAbb--__99", WebRootResolver.virtualBase("/s/AAbb--__99/", "s"));
	}

	public void testEverythingElseCarriesNoVirtualBase() {
		assertNull(WebRootResolver.virtualBase("/", "s"));
		assertNull(WebRootResolver.virtualBase("/s", "s"));
		assertNull(WebRootResolver.virtualBase("/s/", "s"));
		assertNull(WebRootResolver.virtualBase("/s//x", "s"));
		assertNull(WebRootResolver.virtualBase("/sx/abc/", "s"));
		assertNull(WebRootResolver.virtualBase("/album/IMG_0417.JPG", "s"));
		assertNull(WebRootResolver.virtualBase(null, "s"));
		assertNull("A handler without a prefix serves no virtual base at all.",
			WebRootResolver.virtualBase("/s/abc/", null));
		assertNull(WebRootResolver.virtualBase("/s/abc/", ""));
	}

	public void testAHandlerSeveralPrefixes() {
		// The invitations of issue #52 are served the same way, under their own segment.
		assertEquals("/i/abc", WebRootResolver.virtualBase("/i/abc/", "s", "i"));
		assertEquals("/i/abc", WebRootResolver.virtualBase("/i/abc/deep/route", "s", "i"));
		assertEquals("/s/abc", WebRootResolver.virtualBase("/s/abc/", "s", "i"));
		assertNull("A segment no prefix names carries no virtual base.",
			WebRootResolver.virtualBase("/x/abc/", "s", "i"));
		assertNull(WebRootResolver.virtualBase("/i/", "s", "i"));
		assertNull(WebRootResolver.virtualBase("/i", "s", "i"));
		assertNull("A handler without any prefix serves no virtual base at all.",
			WebRootResolver.virtualBase("/i/abc/"));
	}

	public void testADotSegmentIsNoToken() {
		assertNull(WebRootResolver.virtualBase("/s/../secret", "s"));
		assertNull(WebRootResolver.virtualBase("/s/./x", "s"));
	}
}
