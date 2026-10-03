/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;

/**
 * Review probe of #198: a contact session is a link session and nothing more — it reaches no
 * register, no management action and no edit, whatever the link lets it contribute — and a
 * withdrawn link shuts its contacts out at once.
 */
@SuppressWarnings("javadoc")
public class TestPersonalLinksReviewProbe extends PersonalLinkTestCase {

	public void testAContactSessionManagesNothing() throws Exception {
		ShareLinkCreated link = created(ZOO, email("Tante Petra", PETRA));
		String token = tokenOf(link, "Tante Petra");
		String credential = credential(token);

		assertEquals(200, getAs("/", "json", token, credential).status());

		for (String type : new String[] { "contacts", "users", "shares", "invitations", "devices", "people" }) {
			FakeResponse response = getAs("/", type, token, credential);
			assertTrue(type + " answered " + response.status() + ": " + response.body(),
				response.status() == 401 || response.status() == 403 || response.status() == 404);
			assertFalse(type + " leaks the register", response.body().toLowerCase().contains("petra@gmx.de"));
		}
		String[][] actions = {
			{ "share", personalBody("Mine", email("Someone", "x@y.de")) },
			{ "resend", "{\"link\":\"" + link.getLink().getId() + "\",\"contact\":\"" + contactOf(link, "Tante Petra") + "\"}" },
			{ "block-contact", "{\"contact\":\"" + contactOf(link, "Tante Petra") + "\"}" },
			{ "delete", "{\"target\":\"\",\"names\":[{\"name\":\"public.jpg\"}]}" },
			{ "invite", "{\"role\":\"view\"}" },
			{ "device-code", "{}" },
		};
		for (String[] action : actions) {
			FakeResponse response = postAs("/", action[0], action[1], token, credential);
			assertTrue(action[0] + " answered " + response.status() + ": " + response.body(),
				response.status() >= 400);
		}
		// Still in afterwards: nothing above changed the session.
		assertEquals(200, getAs("/", "json", token, credential).status());
	}

	public void testAWithdrawnLinkShutsItsContactsOutAtOnce() throws Exception {
		ShareLinkCreated link = created(ZOO, email("Tante Petra", PETRA));
		String token = tokenOf(link, "Tante Petra");
		String credential = credential(token);
		assertEquals(200, getAs("/", "json", token, credential).status());

		FakeResponse unshare = postAs(ZOO, "unshare", "{\"id\":\"" + link.getLink().getId() + "\"}", SharingFixture.ALICE, null);
		assertEquals(unshare.body(), 200, unshare.status());

		for (String type : new String[] { "json", "auth" }) {
			FakeResponse response = getAs("/", type, token, credential);
			assertEquals(type + ": " + response.body(), 410, response.status());
		}
		FakeResponse again = identify(token, true);
		assertTrue("identify after withdrawal: " + again.status(), again.status() >= 400);
	}
}
