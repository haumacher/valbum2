/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.auth.UserStore;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Creating a share link is held to the rule an edit is held to (issue #282): nobody hands out a
 * right they do not hold, a privacy ceiling above their clearance or an expiry in the past &mdash;
 * neither as an anonymous link nor as an addressed one.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestShareCreationLimits extends PersonalLinkTestCase {

	/** Gives dave (role view) and eve (role view, clearance public) the share flag. */
	private void letViewersShare() throws Exception {
		UserStore users = new UserStore(_base);
		users.getUser("dave").setShare(true);
		users.getUser("eve").setShare(true);
		users.store();
		restartServer();
	}

	public void testAViewerCannotCreateAContributeLink() throws Exception {
		letViewersShare();
		FakeResponse refused = share(ZOO, SharingFixture.DAVE, shareBody("Daves", "", 0, -2, "view", "contribute"));
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, refused.status());
		assertEquals(AuthService.shareRightNotHeld(Rights.CONTRIBUTE), errorMessage(refused));
		assertTrue("Nothing was created.", links(shares(ZOO, SharingFixture.DAVE)).getLinks().isEmpty());

		// What a viewer holds they may hand on: looking, and keeping what they see.
		FakeResponse made = share(ZOO, SharingFixture.DAVE, shareBody("Daves", "", 0, -2, "view", "download"));
		assertEquals(made.body(), 200, made.status());
	}

	public void testAViewerCannotCreateAnAddressedContributeLink() throws Exception {
		letViewersShare();
		// The personal body allows contributing.
		FakeResponse refused = sharePersonal(ZOO, SharingFixture.DAVE, email("Tante Petra", PETRA));
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, refused.status());
		assertEquals(AuthService.shareRightNotHeld(Rights.CONTRIBUTE), errorMessage(refused));
		assertTrue("Nobody was entered as a contact either.",
			contactList(get("/", "contacts", SharingFixture.ALICE)).getContacts().isEmpty());
	}

	public void testAPublicOnlyMemberCannotCreateALinkAboveTheirClearance() throws Exception {
		letViewersShare();
		FakeResponse refused = share(ZOO, SharingFixture.EVE, shareBody("Eves", "", Privacy.MEMBERS, -2, "view"));
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, refused.status());
		assertEquals(AuthService.shareAboveClearance(Privacy.PUBLIC), errorMessage(refused));

		String addressed = "{\"label\":\"Eves\",\"type\":\"PERSONAL\",\"maxPrivacy\":1,\"minRating\":-2,"
			+ "\"rights\":[{\"name\":\"view\"}],\"recipients\":[" + email("Tante Petra", PETRA) + "]}";
		FakeResponse refusedPersonal = share(ZOO, SharingFixture.EVE, addressed);
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, refusedPersonal.status());
		assertEquals(AuthService.shareAboveClearance(Privacy.PUBLIC), errorMessage(refusedPersonal));

		assertEquals(200, share(ZOO, SharingFixture.EVE, shareBody("Eves", "", Privacy.PUBLIC, -2, "view")).status());
	}

	public void testAnAdministratorMayGiveEverythingButEditing() throws Exception {
		FakeResponse made = share(ZOO, SharingFixture.ALICE,
			shareBody("All", "", Privacy.PRIVATE, -2, "view", "download", "contribute"));
		assertEquals(made.body(), 200, made.status());
		assertEquals(java.util.List.of(Rights.VIEW, Rights.DOWNLOAD, Rights.CONTRIBUTE),
			rightsOf(created(made).getLink()));
		assertEquals(200, sharePersonal(ZOO, SharingFixture.ALICE, email("Tante Petra", PETRA)).status());
	}

	public void testALinkExpiringInThePastIsRefused() throws Exception {
		FakeResponse refused = share(ZOO, SharingFixture.ALICE, shareBody("Old", "2001-01-01T00:00:00Z", 0, -2, "view"));
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, refused.status());
		assertEquals(AuthService.SHARE_EXPIRY_PAST, errorMessage(refused));

		String tomorrow = Instant.now().plus(1, ChronoUnit.DAYS).toString();
		assertEquals(200, share(ZOO, SharingFixture.ALICE, shareBody("New", tomorrow, 0, -2, "view")).status());
	}
}
