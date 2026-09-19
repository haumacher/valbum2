/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.Roles;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import junit.framework.TestCase;

/**
 * Probe for issue #88: the redirect of a dead invitation address composed with what the app server
 * already did — deep links with encoded blanks and query strings, and the half-formed session
 * addresses a hand-typed URL produces.
 */
@SuppressWarnings("javadoc")
public class TestInvitationRedirectProbe extends TestCase {

	/** The fixture of the delivered test, driven from here so that its tests do not run twice. */
	private final TestInvitationRedirect _server = new TestInvitationRedirect();

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_server.setUp();
		_server.single();
	}

	@Override
	protected void tearDown() throws Exception {
		_server.tearDown();
		super.tearDown();
	}

	/**
	 * The album the dead link pointed into is spelled with encoded blanks and carries its own
	 * query: the redirect goes to the start page all the same, without the deep link and without
	 * the caller's query, and the reason is the only parameter.
	 */
	public void testADeepLinkWithEncodedBlanksAndAQueryRedirectsToTheBareStartPage() throws Exception {
		String token = accepted();
		FakeResponse page = _server.get("/i/" + token + "/2002-03-03%20Schlosspark%20Karlsruhe/?viewAs=public");
		assertEquals(HttpServletResponse.SC_FOUND, page.status());
		assertEquals("/valbum/?invitation=used", TestInvitationRedirect.location(page));
	}

	/** A hand-typed address that is only half a session base must not crash the servlet. */
	public void testHalfFormedSessionAddressesDoNotCrash() throws Exception {
		String token = accepted();
		for (String path : new String[] { "/i/", "/i", "/i/" + token, "/s/", "/s" }) {
			FakeResponse page = _server.get(path);
			assertTrue("A server error for '" + path + "': " + page.status(),
				page.status() < HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		}
	}

	/** A live invitation whose page was loaded stays live: the page load consumes nothing. */
	public void testLoadingTheLivePageTwiceKeepsTheInvitationLive() throws Exception {
		String token = _server.issue("", Roles.VIEW, Instant.now().plusSeconds(3600).toString());
		for (int n = 0; n < 2; n++) {
			assertEquals(HttpServletResponse.SC_OK, _server.get("/i/" + token + "/").status());
		}
		FakeResponse joined = _server.post("/data/", "pair",
			"{\"invitation\":\"" + token + "\",\"userName\":\"probe\",\"deviceName\":\"Phone\"}");
		assertEquals(joined.body(), HttpServletResponse.SC_OK, joined.status());
	}

	private String accepted() throws Exception {
		String token = _server.issue("", Roles.VIEW, Instant.now().plusSeconds(3600).toString());
		FakeResponse joined = _server.post("/data/", "pair",
			"{\"invitation\":\"" + token + "\",\"userName\":\"carol\",\"deviceName\":\"Phone\"}");
		assertEquals(joined.body(), HttpServletResponse.SC_OK, joined.status());
		return token;
	}

}
