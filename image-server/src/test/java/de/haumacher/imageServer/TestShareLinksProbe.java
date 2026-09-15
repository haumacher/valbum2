/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.shared.model.AuthInfo;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Probe for the share links of #84, composed with the privacy cap of a link, the rating filter and
 * what the link caller is told about itself.
 */
@SuppressWarnings("javadoc")
public class TestShareLinksProbe extends ShareTestCase {

	private static final String ZOO = "/" + SharingFixture.ZOO + "/";

	/** An admin's link asking for everything shows the members' level and never a private image. */
	public void testAdminLinkNeverShowsPrivate() throws Exception {
		FakeResponse made = share(ZOO, SharingFixture.ALICE, shareBody("All", "", 2, 0, "view", "download"));
		assertEquals(made.body(), HttpServletResponse.SC_OK, made.status());
		ShareLinkCreated link = ShareLinkCreated.readShareLinkCreated(reader(made.body()));
		String listing = get("/", "json", link.getToken()).body();
		assertTrue(listing.contains("members.jpg"));
		assertFalse("A link never shows a private image.", listing.contains("private.jpg"));
		assertEquals(HttpServletResponse.SC_OK, get("/members.jpg", "tn", link.getToken()).status());
		assertTrue(get("/private.jpg", "tn", link.getToken()).status() >= 400);
		// The link caller knows what it is.
		AuthInfo info = AuthInfo.readAuthInfo(reader(get("/", "auth", link.getToken()).body()));
		assertNotNull(info.getShare());
		assertEquals("All", info.getShare().getLabel());
		assertEquals("", info.getUserName());
	}

	/** The rating filter of a link drops what is rated below, whatever the clearance. */
	public void testRatingFilterOnALink() throws Exception {
		FakeResponse made = share(ZOO, SharingFixture.ALICE, shareBody("Good ones", "", 1, 0, "view"));
		assertEquals(made.body(), HttpServletResponse.SC_OK, made.status());
		ShareLinkCreated link = ShareLinkCreated.readShareLinkCreated(reader(made.body()));
		String listing = get("/", "json", link.getToken()).body();
		assertFalse("rejected.jpg is rated -1 and below the link's minimum of 0.", listing.contains("rejected.jpg"));
		assertTrue(listing.contains("public.jpg"));
		// Asking for the dropped image directly is refused too.
		assertTrue(get("/rejected.jpg", "tn", link.getToken()).status() >= 400);
	}

	/** A link of a contributor with the flag cannot be withdrawn by an editor without it. */
	public void testOnlyMakerOrAdminWithdraw() throws Exception {
		FakeResponse made = share(ZOO, SharingFixture.BOB, shareBody("Bobs", "", 0, 0, "view"));
		assertEquals(made.body(), HttpServletResponse.SC_OK, made.status());
		ShareLinkCreated link = ShareLinkCreated.readShareLinkCreated(reader(made.body()));
		FakeResponse byCarol = post(ZOO, "{\"id\":\"" + link.getLink().getId() + "\"}", SharingFixture.CAROL,
			java.util.Collections.singletonMap("action", "unshare"));
		assertTrue("carol may not withdraw bob's link: " + byCarol.status(), byCarol.status() >= 400);
		assertEquals("The link still opens.", HttpServletResponse.SC_OK, get("/", "json", link.getToken()).status());
		FakeResponse byAlice = post(ZOO, "{\"id\":\"" + link.getLink().getId() + "\"}", SharingFixture.ALICE,
			java.util.Collections.singletonMap("action", "unshare"));
		assertEquals(byAlice.body(), HttpServletResponse.SC_OK, byAlice.status());
		assertEquals(HttpServletResponse.SC_GONE, get("/", "json", link.getToken()).status());
	}
}
