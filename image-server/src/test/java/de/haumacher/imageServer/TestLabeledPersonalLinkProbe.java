/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;

/**
 * Review probe of #213 composed with #198: the author's case — a personal link to friends showing
 * the photos of one label. A recipient sees the labeled photos only, and an unlabeled photo does
 * not exist for them.
 */
@SuppressWarnings("javadoc")
public class TestLabeledPersonalLinkProbe extends PersonalLinkTestCase {

	public void testARecipientOfALabeledPersonalLinkSeesTheLabelOnly() throws Exception {
		String album = get(ZOO, "json", SharingFixture.ALICE).body();
		String labeled = album.replaceFirst("(\"name\":\\s*\"public\\.jpg\")", "$1,\"labels\":[{\"name\":\"Friends\"}]");
		assertFalse("the fixture names public.jpg", labeled.equals(album));
		FakeResponse put = put(ZOO, labeled, SharingFixture.ALICE);
		assertEquals(put.body(), 200, put.status());

		String body = personalBody("Party", email("Tante Petra", PETRA)).replaceFirst("\\{", "{\"photoLabel\":\"Friends\",").replace("\"maxPrivacy\":0", "\"maxPrivacy\":1");
		FakeResponse made = share(ZOO, SharingFixture.ALICE, body);
		assertEquals(made.body(), 200, made.status());
		ShareLinkCreated created = created(made);
		// Without the label the members' photo would be shown: the link reaches the members' level.
		String unlabeledBody = personalBody("Control", email("Onkel Klaus", "klaus@web.de")).replace("\"maxPrivacy\":0", "\"maxPrivacy\":1");
		ShareLinkCreated control = created(share(ZOO, SharingFixture.ALICE, unlabeledBody));
		String klaus = tokenOf(control, "Onkel Klaus");
		assertTrue(getAs("/", "json", klaus, credential(klaus)).body().contains("members.jpg"));
		String token = tokenOf(created, "Tante Petra");
		String credential = credential(token);

		FakeResponse seen = getAs("/", "json", token, credential);
		assertEquals(seen.body(), 200, seen.status());
		assertTrue(seen.body(), seen.body().contains("public.jpg"));
		assertFalse(seen.body(), seen.body().contains("members.jpg"));

		FakeResponse hidden = getAs("/members.jpg", "tn", token, credential);
		assertEquals(hidden.body(), 404, hidden.status());
		assertEquals(200, getAs("/public.jpg", "tn", token, credential).status());
	}
}
