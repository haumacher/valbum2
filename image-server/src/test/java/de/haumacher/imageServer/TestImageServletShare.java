/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.auth.ShareStore;
import de.haumacher.imageServer.shared.model.ShareLink;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import de.haumacher.imageServer.shared.model.ShareLinkList;
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

	private static final String ZOO = "/" + SharingFixture.ZOO + "/";


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
		String onTheYear = created(share("/" + SharingFixture.YEAR + "/", SharingFixture.ALICE,
			shareBody("Year", "", Privacy.MEMBERS, -2, Rights.VIEW, Rights.CONTRIBUTE))).getLink().getId();
		created(share("/" + SharingFixture.PUBLIC + "/", SharingFixture.ALICE,
			shareBody("Elsewhere", "", Privacy.PUBLIC, 0, Rights.VIEW)));

		ShareLinkList list = links(shares(ZOO, SharingFixture.ALICE));
		List<String> ids = Arrays.asList(list.getLinks().get(0).getId(), list.getLinks().get(1).getId());
		assertEquals("The nearest link first, the one on another folder not at all.", 2, list.getLinks().size());
		assertEquals(Arrays.asList(onTheAlbum, onTheYear), ids);
		assertEquals(Arrays.asList(Rights.VIEW, Rights.CONTRIBUTE), rightsOf(link(list, onTheYear)));
		assertEquals("" + SharingFixture.YEAR, link(list, onTheYear).getPath());
	}

	public void testSomebodyWithoutTheShareFlagMayNeitherCreateNorList() throws Exception {
		// carol may change every album of the space and still hands out no link: the share flag is
		// its own axis, see issue #83.
		FakeResponse creating = share(ZOO, SharingFixture.CAROL,
			shareBody("Mine now", "", Privacy.PUBLIC, 0, Rights.VIEW));
		assertEquals(HttpServletResponse.SC_FORBIDDEN, creating.status());
		assertEquals(AuthService.GRANTS_REFUSED, errorMessage(creating));

		FakeResponse listing = shares(ZOO, SharingFixture.CAROL);
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



	public void testCreatingALinkTouchesNoPhoto() throws Exception {
		String before = albumFingerprint("alice");
		ShareLinkCreated created = created(share(ZOO, SharingFixture.ALICE,
			shareBody("Grandma", "", Privacy.PUBLIC, 0, Rights.VIEW)));
		unshare(ZOO, SharingFixture.ALICE, created.getLink().getId());
		assertEquals("Sharing writes nothing into a user's library.", before, albumFingerprint("alice"));
	}

}
