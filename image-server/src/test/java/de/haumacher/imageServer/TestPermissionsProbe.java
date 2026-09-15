/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.shared.model.AuthInfo;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collections;

/**
 * Probe for the permission model of #83, composed with the upload attribution of #53, the move rule
 * of #47, the privacy of #46, share links and user removal.
 */
@SuppressWarnings("javadoc")
public class TestPermissionsProbe extends ShareTestCase {

	private static final String ZOO = "/" + SharingFixture.ZOO + "/";

	/** A contributor may move what they uploaded, and nothing else. */
	public void testContributorMovesOnlyOwnUploads() throws Exception {
		// A real picture: what the server cannot analyse it stores but never lists.
		java.io.ByteArrayOutputStream jpeg = new java.io.ByteArrayOutputStream();
		javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4, 3, java.awt.image.BufferedImage.TYPE_3BYTE_BGR), "jpg", jpeg);
		FakeResponse uploaded = upload(ZOO, SharingFixture.BOB, "bobs.jpg", jpeg.toByteArray());
		assertEquals(uploaded.body(), HttpServletResponse.SC_OK, uploaded.status());

		FakeResponse own = move(ZOO, SharingFixture.PUBLIC, SharingFixture.BOB, "bobs.jpg");
		assertEquals("A contributor moves their own upload: " + own.body(), HttpServletResponse.SC_OK, own.status());

		FakeResponse foreign = move(ZOO, SharingFixture.PUBLIC, SharingFixture.BOB, "public.jpg");
		assertEquals("A contributor does not move what others brought: " + foreign.body(),
			HttpServletResponse.SC_FORBIDDEN, foreign.status());
		// The album still lists the image bob was refused to move.
		assertTrue(get(ZOO, "json", SharingFixture.ALICE).body().contains("public.jpg"));
	}

	/** Clearance is enforced on the image endpoints, not only in listings. */
	public void testClearanceOnTheImageEndpoints() throws Exception {
		assertEquals(HttpServletResponse.SC_FORBIDDEN, get(ZOO + "members.jpg", "tn", SharingFixture.EVE).status());
		assertEquals(HttpServletResponse.SC_OK, get(ZOO + "public.jpg", "tn", SharingFixture.EVE).status());
		assertEquals(HttpServletResponse.SC_FORBIDDEN, get(ZOO + "private.jpg", "tn", SharingFixture.DAVE).status());
		assertEquals(HttpServletResponse.SC_OK, get(ZOO + "members.jpg", "tn", SharingFixture.DAVE).status());
		// An editor with a non-private clearance does not see private images either, although they may edit.
		assertEquals(HttpServletResponse.SC_FORBIDDEN, get(ZOO + "private.jpg", "tn", SharingFixture.CAROL).status());
		assertFalse(get(ZOO, "json", SharingFixture.CAROL).body().contains("private.jpg"));
	}

	/** A share link cannot hand out more than its maker may; a removed user is nobody. */
	public void testLinksAreCappedAndRemovalTakesTheDevices() throws Exception {
		// bob may share but may not edit: a link with edit is refused, one with contribute is fine.
		FakeResponse tooMuch = share(ZOO, SharingFixture.BOB, shareBody("Edit", "", 0, 0, "view", "edit"));
		assertTrue("A link may not carry edit: " + tooMuch.status(), tooMuch.status() >= 400);
		FakeResponse ok = share(ZOO, SharingFixture.BOB, shareBody("Add", "", 1, 0, "view", "contribute"));
		assertEquals(ok.body(), HttpServletResponse.SC_OK, ok.status());
		ShareLinkCreated link = ShareLinkCreated.readShareLinkCreated(reader(ok.body()));
		// The link's clearance is cut to its maker's: bob sees everything but was cut? bob is contribute+share
		// with the fixture's clearance; the link asked for members level and gets at most that.
		String linkToken = link.getToken();
		assertEquals(HttpServletResponse.SC_OK, get("/", "json", linkToken).status());
		assertFalse(get("/", "json", linkToken).body().contains("private.jpg"));
		// carol may edit but may not share.
		FakeResponse noFlag = share(ZOO, SharingFixture.CAROL, shareBody("Nope", "", 0, 0, "view"));
		assertEquals(HttpServletResponse.SC_FORBIDDEN, noFlag.status());

		// alice removes dave: his token is anonymous from then on.
		FakeResponse removed = post("/", "{\"name\":\"dave\"}", SharingFixture.ALICE,
			Collections.singletonMap("action", "remove-user"));
		assertEquals(removed.body(), HttpServletResponse.SC_OK, removed.status());
		FakeResponse auth = get("/", "auth", SharingFixture.DAVE);
		assertEquals(HttpServletResponse.SC_OK, auth.status());
		AuthInfo info = AuthInfo.readAuthInfo(reader(auth.body()));
		assertEquals("A removed user's device is nobody.", "", info.getUserName());
		// Nobody is anonymous: a members-only image answers 401, not the 403 of a signed-in refusal.
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, get(ZOO + "members.jpg", "tn", SharingFixture.DAVE).status());
		// A viewer cannot remove anybody.
		FakeResponse notAdmin = post("/", "{\"name\":\"eve\"}", SharingFixture.CAROL,
			Collections.singletonMap("action", "remove-user"));
		assertEquals(HttpServletResponse.SC_FORBIDDEN, notAdmin.status());
	}
}
