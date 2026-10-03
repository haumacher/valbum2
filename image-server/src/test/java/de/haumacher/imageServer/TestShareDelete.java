/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.ContactStore;
import de.haumacher.imageServer.auth.ShareStore;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ShareLink;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import de.haumacher.imageServer.shared.model.ShareLinkList;
import de.haumacher.imageServer.shared.model.UserList;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Deleting a share link deletes it, as if it had never been made, see issue #217.
 *
 * <p>
 * The card and the media signatures of a deleted link are pinned in {@link TestSharePreview},
 * {@link TestMediaUrls} and {@link TestPersonalLinksProbe}.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestShareDelete extends PersonalLinkTestCase {

	public void testTheAnswerIsTheLinkAsItWasAndTheListNoLongerShowsIt() throws Exception {
		String token = zooToken();
		String id = idOf(token);

		FakeResponse deleted = unshare(ZOO, SharingFixture.ALICE, id);
		assertEquals(deleted.body(), HttpServletResponse.SC_OK, deleted.status());
		ShareLinkList answer = links(deleted);
		assertEquals("The wire name and the answer stay, for every app since #51.", 1, answer.getLinks().size());
		assertEquals(id, answer.getLinks().get(0).getId());
		assertEquals("Nothing says 'withdrawn' any more.", "", answer.getLinks().get(0).getRevoked());

		assertNull(link(links(shares(ZOO, SharingFixture.ALICE)), id));
		String file = shareStoreContents();
		assertFalse("Removed from shares.json, not marked: " + file, file.contains(id));
	}

	public void testAPersonalLinkTakesItsRecipientsTokensAndSessionsAlongAndLeavesItsContacts() throws Exception {
		ShareLinkCreated party = created(ZOO, email("Tante Petra", PETRA), phone("Klaus", KLAUS));
		String petra = contactOf(party, "Tante Petra");
		String petrasToken = tokenOf(party, "Tante Petra");
		String throughParty = credential(petrasToken);
		String klausToken = tokenOf(party, "Klaus");

		// A second link to Petra, opened in a second browser: a session opened through another link.
		ShareLinkCreated other = created(ZOO, known(petra));
		String otherToken = tokenOf(other, "Tante Petra");
		String throughOther = credential(otherToken);
		assertEquals(200, getAs("/", "json", petrasToken, throughParty).status());
		assertEquals(200, getAs("/", "json", otherToken, throughOther).status());

		FakeResponse uploaded = uploadAs("/", petrasToken, throughParty, "petras.jpg", photo("petras"));
		assertEquals(uploaded.body(), 200, uploaded.status());

		FakeResponse deleted = unshare(ZOO, SharingFixture.ALICE, party.getLink().getId());
		assertEquals(deleted.body(), HttpServletResponse.SC_OK, deleted.status());

		// Every token of the link is one nobody ever issued.
		for (String token : new String[] { party.getToken(), petrasToken, klausToken }) {
			for (String type : new String[] { "json", "auth" }) {
				FakeResponse response = getAs("/", type, token, throughParty);
				FakeResponse never = getAs("/", type, "never-issued", throughParty);
				assertEquals(type + ": " + response.body(), never.status(), response.status());
				assertEquals(type, never.body(), response.body());
			}
		}
		assertTrue(identify(petrasToken, true).status() >= 400);

		// The session opened through it has ended; the one opened through the other link has not.
		ContactStore contacts = new ContactStore(_base);
		assertNull("Signed out of the deleted link.", contacts.recognize(throughParty));
		assertNotNull("Still in through the other link.", contacts.recognize(throughOther));
		assertEquals(200, getAs("/", "json", otherToken, throughOther).status());

		// The contacts stay contacts of the space.
		assertNotNull(contacts.get(petra));
		assertNotNull(contacts.get(contactOf(party, "Klaus")));
		assertEquals(2, contactList(get("/", "contacts", SharingFixture.ALICE)).getContacts().size());

		// And what came in through it keeps saying who brought it (#53).
		restartServer();
		ImagePart image = image(get(ZOO, "json", SharingFixture.ALICE), "petras.jpg");
		assertEquals("contact:" + petra, image.getContributor());
		assertEquals("Tante Petra", image.getContributorLabel());
	}

	public void testRemovingAUserDeletesTheirPersonalLinksTheSameWay() throws Exception {
		FakeResponse response = sharePersonal(ZOO, SharingFixture.BOB, email("Tante Petra", PETRA));
		assertEquals(response.body(), 200, response.status());
		ShareLinkCreated bobs = created(response);
		String petrasToken = tokenOf(bobs, "Tante Petra");
		String credential = credential(petrasToken);
		assertEquals(200, getAs("/", "json", petrasToken, credential).status());

		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "remove-user");
		FakeResponse removed = post("/", "{\"name\":\"bob\"}", SharingFixture.ALICE, parameters);
		assertEquals(removed.body(), HttpServletResponse.SC_OK, removed.status());
		assertEquals(1, UserList.readUserList(reader(body(removed))).getRevokedLinks());

		assertNull(new ShareStore(_base).get(bobs.getLink().getId()));
		assertEquals(getAs("/", "json", "never-issued", credential).status(),
			getAs("/", "json", petrasToken, credential).status());
		ContactStore contacts = new ContactStore(_base);
		assertNull(contacts.recognize(credential));
		assertNotNull("Petra stays a contact of the space.", contacts.get(contactOf(bobs, "Tante Petra")));
	}

	public void testARecordAnOlderBuildWithdrewIsNeitherListedNorKept() throws Exception {
		Path file = new ShareStore(_base).getFile();
		Files.createDirectories(file.getParent());
		Files.copy(new File("src/test/fixtures/personal-links/shares-v1.json").toPath(), file);
		restartServer();

		ShareStore store = new ShareStore(_base);
		assertNotNull(store.get("oldLink1"));
		assertNull("Read as deleted.", store.get("oldLink2"));
		for (ShareStore.Link link : store.getLinks()) {
			assertFalse(link.getId(), "oldLink2".equals(link.getId()));
		}
		assertTrue("Nothing is written on load.", shareStoreContents().contains("oldLink2"));

		// Listed nowhere, not even at the space root where everything is listed to an administrator.
		ShareLinkList listed = links(shares("/", SharingFixture.ALICE));
		for (ShareLink link : listed.getLinks()) {
			assertFalse(link.getId(), "oldLink2".equals(link.getId()));
		}

		// Gone from the file with the next write.
		assertEquals(200, sharePersonal(ZOO, SharingFixture.ALICE, email("Tante Petra", PETRA)).status());
		String contents = shareStoreContents();
		assertFalse(contents, contents.contains("oldLink2"));
		assertFalse(contents, contents.contains("\"revoked\""));
		assertTrue(contents, contents.contains("oldLink1"));
	}

	private static ImagePart image(FakeResponse response, String name) throws Exception {
		for (AlbumPart part : album(response).getParts()) {
			if (part instanceof ImagePart && ((ImagePart) part).getName().equals(name)) {
				return (ImagePart) part;
			}
		}
		fail("No image '" + name + "' in " + response.body());
		return null;
	}
}
