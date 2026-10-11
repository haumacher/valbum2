/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.ContactStore;
import de.haumacher.imageServer.shared.model.OtherSessionsEnded;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Review probe of <code>?action=end-own-session</code> composed with a contact who holds sessions on
 * two personal links of the same space: the end is of this browser's session only, and a session
 * that was ended is gone everywhere, for good.
 */
public class TestMenuSignOutProbe extends PersonalLinkTestCase {

	private static final String ZOO = "/" + SharingFixture.ZOO + "/";

	public void testEndingOneLinksSessionLeavesTheOtherLinks() throws Exception {
		ShareLinkCreated first = created(ZOO, email("Tante Petra", "petra@example.org"));
		String petra = contactOf(first, "Tante Petra");
		ShareLinkCreated second = created(ZOO, known(petra));
		String firstLink = first.getToken();
		String secondLink = second.getToken();
		String onFirst = credential(tokenOf(first, "Tante Petra"));
		String onSecond = credential(tokenOf(second, "Tante Petra"));
		assertEquals(2, new ContactStore(_base).get(petra).getSessions().size());

		FakeResponse ended = postAs("/", "end-own-session", "{}", firstLink, onFirst);
		assertEquals(ended.body(), 200, ended.status());
		assertEquals(1, OtherSessionsEnded.readOtherSessionsEnded(reader(body(ended))).getEnded());

		// The other link's session is untouched, and sees no other browser any more.
		assertEquals(200, getAs("/", "json", secondLink, onSecond).status());
		assertEquals(0, auth(getAs("/", "auth", secondLink, onSecond)).getShare().getOtherSessions());
		FakeResponse others = postAs("/", "end-other-sessions", "{}", secondLink, onSecond);
		assertEquals(others.body(), 200, others.status());
		assertEquals("Nothing is left to end.", 0,
			OtherSessionsEnded.readOtherSessionsEnded(reader(body(others))).getEnded());

		// The ended credential opens neither link: it is one never issued now.
		FakeResponse neverIssued = getAs("/", "json", firstLink, "never-issued");
		for (String link : new String[] { firstLink, secondLink }) {
			FakeResponse gone = getAs("/", "json", link, onFirst);
			assertEquals(neverIssued.status(), gone.status());
			assertEquals(errorMessage(neverIssued), errorMessage(gone));
		}

		// Ending it twice is asking as nobody: the question who is there, not a count.
		FakeResponse twice = postAs("/", "end-own-session", "{}", firstLink, onFirst);
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, twice.status());
		assertNotNull(identifyRequired(twice));

		// Written to the store: the one session left is the second link's.
		ContactStore reread = new ContactStore(_base);
		assertEquals(1, reread.get(petra).getSessions().size());
		assertEquals(second.getLink().getId(), reread.get(petra).getSessions().get(0).getLink());
	}
}
