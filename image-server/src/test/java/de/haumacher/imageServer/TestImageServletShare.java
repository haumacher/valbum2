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
import de.haumacher.imageServer.shared.model.ShareLink;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import de.haumacher.imageServer.shared.model.ShareLinkList;
import de.haumacher.util.servlet.WebRootResolver;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.List;

/**
 * Creating, listing and withdrawing share links, see issue #51.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestImageServletShare extends ShareTestCase {

	private static final String ZOO = "/~alice/" + SharingFixture.ZOO + "/";

	public void testOwnerCreatesALinkAndIsShownTheTokenOnce() throws Exception {
		FakeResponse response = share(ZOO, SharingFixture.ALICE,
			shareBody("Grandma", "", Privacy.PUBLIC, 0, Rights.VIEW, Rights.DOWNLOAD));
		assertEquals(HttpServletResponse.SC_OK, response.status());

		ShareLinkCreated created = created(response);
		ShareLink link = created.getLink();
		assertFalse(created.getToken().isEmpty());
		assertEquals("Grandma", link.getLabel());
		assertEquals("~alice/" + SharingFixture.ZOO, link.getPath());
		assertEquals(Privacy.PUBLIC, link.getMaxPrivacy());
		assertEquals(0, link.getMinRating());
		assertEquals(Arrays.asList(Rights.VIEW, Rights.DOWNLOAD), rightsOf(link));
		assertEquals("", link.getRevoked());
		assertFalse(link.getCreated().isEmpty());

		// The URL is relative to the server: the origin is the client's business.
		assertEquals("/valbum/" + ShareStore.URL_SEGMENT + "/" + created.getToken() + "/", created.getUrl());
		// And it is a path the static handler recognises as the application's virtual base.
		assertEquals("/" + ShareStore.URL_SEGMENT + "/" + created.getToken(),
			WebRootResolver.virtualBase("/" + ShareStore.URL_SEGMENT + "/" + created.getToken() + "/",
				ShareStore.URL_SEGMENT));

		// The grant is what actually opens the album.
		GrantStore.Grant grant = grantOf("alice", "token:" + link.getId());
		assertNotNull("A link without a grant would open nothing.", grant);
		assertEquals(SharingFixture.ZOO, grant.getPath());
		assertEquals(2, grant.getRights().size());
	}

	public void testTheTokenIsNeverStoredAndNeverAnsweredAgain() throws Exception {
		ShareLinkCreated created = created(share(ZOO, SharingFixture.ALICE,
			shareBody("Grandma", "", Privacy.PUBLIC, 0, Rights.VIEW)));

		String contents = shareStoreContents();
		assertFalse("The token must not reach the disk: " + contents, contents.contains(created.getToken()));

		String listed = body(shares(ZOO, SharingFixture.ALICE));
		assertFalse("A listing must never carry a token: " + listed, listed.contains(created.getToken()));
		assertFalse(listed, listed.contains("tokenHash"));
		assertFalse(listed, listed.contains("\"token\""));
	}

	public void testListingShowsTheLinksOfTheFolderAndItsAncestors() throws Exception {
		String onTheAlbum = created(share(ZOO, SharingFixture.ALICE,
			shareBody("Album", "", Privacy.PUBLIC, 0, Rights.VIEW))).getLink().getId();
		String onTheYear = created(share("/~alice/" + SharingFixture.YEAR + "/", SharingFixture.ALICE,
			shareBody("Year", "", Privacy.MEMBERS, -2, Rights.VIEW, Rights.CONTRIBUTE))).getLink().getId();
		created(share("/~alice/" + SharingFixture.PUBLIC + "/", SharingFixture.ALICE,
			shareBody("Elsewhere", "", Privacy.PUBLIC, 0, Rights.VIEW)));

		ShareLinkList list = links(shares(ZOO, SharingFixture.ALICE));
		List<String> ids = Arrays.asList(list.getLinks().get(0).getId(), list.getLinks().get(1).getId());
		assertEquals("The nearest link first, the one on another folder not at all.", 2, list.getLinks().size());
		assertEquals(Arrays.asList(onTheAlbum, onTheYear), ids);
		assertEquals(Arrays.asList(Rights.VIEW, Rights.CONTRIBUTE), rightsOf(link(list, onTheYear)));
		assertEquals("~alice/" + SharingFixture.YEAR, link(list, onTheYear).getPath());
	}

	public void testSomebodyElseMayNeitherCreateNorList() throws Exception {
		FakeResponse creating = share(ZOO, SharingFixture.BOB,
			shareBody("Mine now", "", Privacy.PUBLIC, 0, Rights.VIEW));
		assertEquals(HttpServletResponse.SC_FORBIDDEN, creating.status());
		assertEquals(AuthService.GRANTS_REFUSED, errorMessage(creating));

		FakeResponse listing = shares(ZOO, SharingFixture.BOB);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, listing.status());
		assertEquals(AuthService.GRANTS_REFUSED, errorMessage(listing));

		assertTrue("Nothing may have been recorded.", new ShareStore(_base).getLinks().isEmpty());
	}

	public void testAnonymousMayNotCreateALink() throws Exception {
		FakeResponse response = share(ZOO, null, shareBody("Nobody", "", Privacy.PUBLIC, 0, Rights.VIEW));
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status());
		assertTrue(new ShareStore(_base).getLinks().isEmpty());
	}

	public void testALinkThatWouldAllowEditingIsRefused() throws Exception {
		FakeResponse response = share(ZOO, SharingFixture.ALICE,
			shareBody("Co-author", "", Privacy.PUBLIC, 0, Rights.VIEW, Rights.EDIT));
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status());
		assertEquals(AuthService.SHARE_EDIT_REFUSED, errorMessage(response));
		assertTrue(new ShareStore(_base).getLinks().isEmpty());
	}

	public void testLimitsOutsideTheScaleAreRefused() throws Exception {
		FakeResponse privacy = share(ZOO, SharingFixture.ALICE, shareBody("x", "", 3, 0, Rights.VIEW));
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, privacy.status());
		assertEquals(AuthService.SHARE_PRIVACY_REFUSED, errorMessage(privacy));

		FakeResponse rating = share(ZOO, SharingFixture.ALICE, shareBody("x", "", 0, 7, Rights.VIEW));
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, rating.status());
		assertEquals(AuthService.SHARE_RATING_REFUSED, errorMessage(rating));

		FakeResponse expiry = share(ZOO, SharingFixture.ALICE, shareBody("x", "next summer", 0, 0, Rights.VIEW));
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, expiry.status());
		assertEquals(AuthService.SHARE_EXPIRY_REFUSED, errorMessage(expiry));

		FakeResponse right = share(ZOO, SharingFixture.ALICE, shareBody("x", "", 0, 0, "fly"));
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, right.status());
		assertEquals(AuthService.unknownRight("fly"), errorMessage(right));

		assertTrue(new ShareStore(_base).getLinks().isEmpty());
	}

	public void testALinkWithoutRightsMayLook() throws Exception {
		ShareLink link = created(share(ZOO, SharingFixture.ALICE, shareBody("Plain", "", 0, 0))).getLink();
		assertEquals(Arrays.asList(Rights.VIEW), rightsOf(link));
	}

	public void testUnshareRevokesTheRecordAndRemovesTheGrant() throws Exception {
		ShareLinkCreated created = created(share(ZOO, SharingFixture.ALICE,
			shareBody("Grandma", "", Privacy.PUBLIC, 0, Rights.VIEW)));
		String id = created.getLink().getId();

		FakeResponse response = unshare(ZOO, SharingFixture.ALICE, id);
		assertEquals(HttpServletResponse.SC_OK, response.status());
		assertFalse(links(response).getLinks().get(0).getRevoked().isEmpty());

		assertNull("The grant must be gone.", grantOf("alice", "token:" + id));
		assertTrue("The record stays, marked.", new ShareStore(_base).get(id).isRevoked());

		// A withdrawn link is still listed, so that a management screen can say what happened.
		ShareLink listed = link(links(shares(ZOO, SharingFixture.ALICE)), id);
		assertNotNull(listed);
		assertFalse(listed.getRevoked().isEmpty());
		assertEquals("A withdrawn link allows nothing.", Arrays.asList(), rightsOf(listed));
	}

	public void testUnshareOfAnUnknownOrForeignLinkIsRefused() throws Exception {
		String elsewhere = created(share("/~alice/" + SharingFixture.PUBLIC + "/", SharingFixture.ALICE,
			shareBody("Elsewhere", "", Privacy.PUBLIC, 0, Rights.VIEW))).getLink().getId();

		FakeResponse unknown = unshare(ZOO, SharingFixture.ALICE, "nothing");
		assertEquals(HttpServletResponse.SC_NOT_FOUND, unknown.status());
		assertEquals(AuthService.SHARE_UNKNOWN, errorMessage(unknown));

		FakeResponse foreign = unshare(ZOO, SharingFixture.ALICE, elsewhere);
		assertEquals("A link on another folder is not this folder's business.",
			HttpServletResponse.SC_NOT_FOUND, foreign.status());
		assertFalse(new ShareStore(_base).get(elsewhere).isRevoked());

		FakeResponse bobs = unshare("/~alice/" + SharingFixture.PUBLIC + "/", SharingFixture.BOB, elsewhere);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, bobs.status());
		assertFalse(new ShareStore(_base).get(elsewhere).isRevoked());
	}

	/**
	 * The administrator keeps the server in order, which includes the links somebody handed out of
	 * their own space — that is what the management screens of issue #55 call.
	 */
	public void testTheAdminManagesTheLinksOfEverySpace() throws Exception {
		String carols = "/~carol/" + SharingFixture.CAROLS_ALBUM + "/";
		ShareLinkCreated created = created(share(carols, SharingFixture.ALICE,
			shareBody("Carol's inbox", "", Privacy.PUBLIC, 0, Rights.VIEW)));
		assertEquals("carol", new ShareStore(_base).get(created.getLink().getId()).getOwner());

		assertNotNull(link(links(shares(carols, SharingFixture.ALICE)), created.getLink().getId()));
		assertEquals(HttpServletResponse.SC_OK, unshare(carols, SharingFixture.ALICE,
			created.getLink().getId()).status());
	}

	public void testCreatingALinkTouchesNoPhoto() throws Exception {
		String before = albumFingerprint("alice");
		ShareLinkCreated created = created(share(ZOO, SharingFixture.ALICE,
			shareBody("Grandma", "", Privacy.PUBLIC, 0, Rights.VIEW)));
		unshare(ZOO, SharingFixture.ALICE, created.getLink().getId());
		assertEquals("Sharing writes nothing into a user's library.", before, albumFingerprint("alice"));
	}

	private GrantStore.Grant grantOf(String owner, String subject) {
		for (GrantStore.Grant grant : grantsOf(owner)) {
			if (grant.getSubject().equals(subject)) {
				return grant;
			}
		}
		return null;
	}
}
