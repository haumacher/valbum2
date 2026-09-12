/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.GrantStore;
import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.auth.ShareStore;
import de.haumacher.imageServer.shared.model.AuthInfo;
import de.haumacher.imageServer.shared.model.RightName;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What a link says about itself, and what it says once it is over, see issue #51.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestShareExpiry extends ShareTestCase {

	public void testAnExpiredLinkIsGoneOnEveryEndpoint() throws Exception {
		String token = issue("alice", SharingFixture.ZOO, "Grandma", "2020-01-01T00:00:00Z", Privacy.PUBLIC, 0,
			Rights.VIEW, Rights.DOWNLOAD);

		assertGone(get("/", "json", token), AuthService.LINK_EXPIRED);
		assertGone(get("/public.jpg", "tn", token), AuthService.LINK_EXPIRED);
		assertGone(get("/public.jpg", null, token), AuthService.LINK_EXPIRED);
		assertGone(get("/", "auth", token), AuthService.LINK_EXPIRED);
		assertGone(upload("/", token, "guest.jpg"), AuthService.LINK_EXPIRED);
		assertGone(put("/", "[\"AlbumInfo\",{\"title\":\"x\",\"parts\":[]}]", token), AuthService.LINK_EXPIRED);
		assertGone(move("/", "/", token, "public.jpg"), AuthService.LINK_EXPIRED);
	}

	public void testAWithdrawnLinkSaysSo() throws Exception {
		String token = zooToken(Rights.VIEW);
		new ShareStore(_base).revoke(idOf(token));
		restartServer();

		assertGone(get("/", "json", token), AuthService.LINK_REVOKED);
		assertGone(get("/public.jpg", "tn", token), AuthService.LINK_REVOKED);
		assertGone(get("/", "auth", token), AuthService.LINK_REVOKED);
	}

	public void testAnUnknownTokenIsStillATokenNobodyKnows() throws Exception {
		FakeResponse response = get("/", "json", "not-a-token-this-server-ever-issued");
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status());
		assertEquals(AuthService.TOKEN_REFUSED, errorMessage(response));
	}

	public void testAViewOnlyLinkDescribesItself() throws Exception {
		String token = issue("alice", SharingFixture.ZOO, "Grandma", "2030-01-01T00:00:00Z", Privacy.PUBLIC, 0,
			Rights.VIEW, Rights.DOWNLOAD);

		AuthInfo info = auth(get("/", "auth", token));
		assertNotNull("The app learns from this that it is inside a share link.", info.getShare());
		assertEquals("Grandma", info.getShare().getLabel());
		assertEquals("2030-01-01T00:00:00Z", info.getShare().getExpires());
		assertEquals("~alice/" + SharingFixture.ZOO, info.getShare().getPath());
		assertEquals(Arrays.asList(Rights.VIEW, Rights.DOWNLOAD), names(info.getShare().getRights()));
		assertFalse("A link that may not contribute allows no writes.", info.isWriteAllowed());
		assertEquals("A link is nobody's user.", "", info.getUserName());
		assertEquals("", info.getRole());
		assertEquals("", info.getSpace());
	}

	public void testAContributeLinkMayWrite() throws Exception {
		String token = zooToken(Rights.VIEW, Rights.CONTRIBUTE);

		AuthInfo info = auth(get("/", "auth", token));
		assertTrue(info.isWriteAllowed());
		assertEquals(Arrays.asList(Rights.VIEW, Rights.CONTRIBUTE), names(info.getShare().getRights()));
	}

	public void testEverybodyElseCarriesNoShare() throws Exception {
		assertNull(auth(get("/", "auth", SharingFixture.ALICE)).getShare());
		assertNull(auth(get("/", "auth", SharingFixture.BOB)).getShare());
		assertNull(auth(get("/", "auth", null)).getShare());
	}

	/**
	 * Withdrawing a link removes its grant, so what is left is a record saying "withdrawn" — and
	 * that is what the next request with that token is answered with.
	 */
	public void testWithdrawingThroughTheEndpointClosesTheDoor() throws Exception {
		String token = zooToken(Rights.VIEW);
		String id = idOf(token);
		assertEquals("Zoo", album(get("/", "json", token)).getTitle());

		unshare("/~alice/" + SharingFixture.ZOO + "/", SharingFixture.ALICE, id);
		restartServer();

		assertGone(get("/", "json", token), AuthService.LINK_REVOKED);
		for (GrantStore.Grant grant : grantsOf("alice")) {
			assertFalse(grant.toString(), grant.getSubject().equals("token:" + id));
		}
	}

	public void testAnExpiredLinkChangesNothingInTheLibrary() throws Exception {
		String token = issue("alice", SharingFixture.ZOO, "Grandma", "2020-01-01T00:00:00Z", Privacy.PUBLIC, 0,
			Rights.VIEW, Rights.CONTRIBUTE);
		String before = albumFingerprint("alice");

		get("/", "json", token);
		upload("/", token, "guest.jpg");
		put("/", "[\"AlbumInfo\",{\"title\":\"x\",\"parts\":[]}]", token);

		assertEquals(before, albumFingerprint("alice"));
	}

	private static void assertGone(FakeResponse response, String message) throws Exception {
		assertEquals("Expected 410 Gone, got: " + response.body(), HttpServletResponse.SC_GONE, response.status());
		assertEquals(message, errorMessage(response));
	}

	private static List<String> names(List<RightName> rights) {
		List<String> result = new ArrayList<>();
		for (RightName right : rights) {
			result.add(right.getName());
		}
		return result;
	}
}
