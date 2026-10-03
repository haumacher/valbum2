/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.ContactStore;
import de.haumacher.imageServer.auth.ShareStore;
import de.haumacher.imageServer.shared.model.Contact;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.LinkVisitor;
import de.haumacher.imageServer.shared.model.OtherSessionsEnded;
import de.haumacher.imageServer.shared.model.ShareLink;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import de.haumacher.imageServer.shared.model.ShareRecipient;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The owner screens of issue #203 on the server: who came through a personal link and what they
 * added, and managing the contacts of the space &mdash; renaming, ending sessions, deleting
 * &mdash; plus a contact signing out their other browsers.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestContactManagement extends PersonalLinkTestCase {

	private static final String OPEN = "/" + SharingFixture.PUBLIC + "/";

	/** A second browser of the recipient: a fresh link of their own, sent again and opened. */
	private String secondBrowser(ShareLinkCreated created, String contact) throws Exception {
		FakeResponse resent = postAs(ZOO, "resend", "{\"link\":\"" + created.getLink().getId() + "\",\"contact\":\""
			+ contact + "\"}", SharingFixture.ALICE, null);
		assertEquals(resent.body(), 200, resent.status());
		return credential(created(resent).getRecipients().get(0).getToken());
	}

	private Contact contact(String id) throws Exception {
		for (Contact contact : contactList(get("/", "contacts", SharingFixture.ALICE)).getContacts()) {
			if (contact.getId().equals(id)) {
				return contact;
			}
		}
		return null;
	}

	private ShareLink listed(String path, String id) throws Exception {
		FakeResponse response = shares(path, SharingFixture.ALICE);
		assertEquals(response.body(), 200, response.status());
		return link(links(response), id);
	}

	// --- Sessions. ---

	public void testEndingASessionRevokesThatCredentialOnly() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA));
		String contact = contactOf(created, "Tante Petra");
		String first = credential(tokenOf(created, "Tante Petra"));
		String second = secondBrowser(created, contact);
		String link = created.getToken();
		assertEquals(200, getAs("/", "json", link, first).status());
		assertEquals(200, getAs("/", "json", link, second).status());

		Contact before = contact(contact);
		assertEquals(2, before.getSessions().size());
		String firstSession = before.getSessions().get(0).getId();
		assertEquals("The session says which link it came through.", "Party",
			before.getSessions().get(0).getLinkLabel());

		FakeResponse ended = postAs("/", "end-contact-session",
			"{\"contact\":\"" + contact + "\",\"session\":\"" + firstSession + "\"}", SharingFixture.ALICE, null);
		assertEquals(ended.body(), 200, ended.status());
		Contact after = Contact.readContact(reader(body(ended)));
		assertEquals(1, after.getSessions().size());
		assertFalse(after.getSessions().get(0).getId().equals(firstSession));

		assertEquals("The ended credential opens nothing.", HttpServletResponse.SC_UNAUTHORIZED,
			getAs("/", "json", link, first).status());
		assertEquals("The other browser stays signed in.", 200, getAs("/", "json", link, second).status());

		FakeResponse unknown = postAs("/", "end-contact-session",
			"{\"contact\":\"" + contact + "\",\"session\":\"" + firstSession + "\"}", SharingFixture.ALICE, null);
		assertEquals(HttpServletResponse.SC_NOT_FOUND, unknown.status());
		assertEquals(PersonalLinks.SESSION_UNKNOWN, errorMessage(unknown));

		FakeResponse all = postAs("/", "end-contact-session", "{\"contact\":\"" + contact + "\"}",
			SharingFixture.ALICE, null);
		assertEquals(all.body(), 200, all.status());
		assertTrue("No session names every session.", Contact.readContact(reader(body(all))).getSessions().isEmpty());
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, getAs("/", "json", link, second).status());
	}

	public void testBlockingEndsEveryCredentialAndRefusesNewOnes() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA));
		String contact = contactOf(created, "Tante Petra");
		String first = credential(tokenOf(created, "Tante Petra"));
		String second = secondBrowser(created, contact);

		FakeResponse blocked = postAs("/", "block-contact", "{\"contact\":\"" + contact + "\",\"shutOut\":true}",
			SharingFixture.BOB, null);
		assertEquals("A member with the share flag blocks: " + blocked.body(), 200, blocked.status());
		assertTrue(contact(contact).getSessions().isEmpty());
		assertFalse(contact(contact).getBlocked().isEmpty());
		String link = created.getToken();
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, getAs("/", "json", link, first).status());
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, getAs("/", "json", link, second).status());

		FakeResponse resent = postAs(ZOO, "resend", "{\"link\":\"" + created.getLink().getId() + "\",\"contact\":\""
			+ contact + "\"}", SharingFixture.ALICE, null);
		String fresh = created(resent).getRecipients().get(0).getToken();
		FakeResponse refused = get("/", "json", fresh);
		assertEquals("A fresh link of a blocked contact opens nothing.", HttpServletResponse.SC_GONE,
			refused.status());
		assertEquals(AuthService.CONTACT_SHUT_OUT, errorMessage(refused));
		assertEquals(HttpServletResponse.SC_GONE, identify(fresh, true).status());
	}

	// --- Renaming. ---

	public void testRenamingKeepsTheCopiedLabelAndNeedsTheShareFlag() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA));
		String contact = contactOf(created, "Tante Petra");
		String token = tokenOf(created, "Tante Petra");
		String credential = credential(token);
		assertEquals(200, uploadAs("/", token, credential, "petras.jpg", photo("petras")).status());
		String body = "{\"contact\":\"" + contact + "\",\"name\":\"Petra Müller\"}";

		FakeResponse carol = postAs("/", "rename-contact", body, SharingFixture.CAROL, null);
		assertEquals("Editing without the share flag manages no contacts.", HttpServletResponse.SC_FORBIDDEN,
			carol.status());
		assertEquals(PersonalLinks.CONTACTS_MANAGE_REFUSED, errorMessage(carol));
		FakeResponse viaLink = postAs("/", "rename-contact", body, token, credential);
		assertEquals("A contact does not rename themself.", HttpServletResponse.SC_FORBIDDEN, viaLink.status());
		assertEquals(PersonalLinks.CONTACTS_MANAGE_REFUSED, errorMessage(viaLink));
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, postAs("/", "rename-contact", body, null, null).status());

		FakeResponse renamed = postAs("/", "rename-contact", body, SharingFixture.BOB, null);
		assertEquals(renamed.body(), 200, renamed.status());
		assertEquals("Petra Müller", Contact.readContact(reader(body(renamed))).getName());
		assertEquals("Petra Müller", new ContactStore(_base).get(contact).getName());

		ImagePart image = image(get(ZOO, "json", SharingFixture.ALICE), "petras.jpg");
		assertEquals("The name was copied at the upload.", "Tante Petra", image.getContributorLabel());

		FakeResponse blank = postAs("/", "rename-contact", "{\"contact\":\"" + contact + "\",\"name\":\"  \"}",
			SharingFixture.ALICE, null);
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, blank.status());
		assertEquals(PersonalLinks.CONTACT_NAME_REQUIRED, errorMessage(blank));
		FakeResponse unknown = postAs("/", "rename-contact", "{\"contact\":\"nobody\",\"name\":\"X\"}",
			SharingFixture.ALICE, null);
		assertEquals(HttpServletResponse.SC_NOT_FOUND, unknown.status());
		assertEquals(ContactStore.unknownContact("nobody"), errorMessage(unknown));
		FakeResponse unreadable = postAs("/", "rename-contact", "{", SharingFixture.ALICE, null);
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, unreadable.status());
		assertEquals(PersonalLinks.UNREADABLE, errorMessage(unreadable));
	}

	// --- Deleting. ---

	public void testDeletingKeepsTheUploadLabelsAndForgetsTheContact() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA));
		String contact = contactOf(created, "Tante Petra");
		String token = tokenOf(created, "Tante Petra");
		String credential = credential(token);
		assertEquals(200, uploadAs("/", token, credential, "petras.jpg", photo("petras")).status());

		assertEquals(HttpServletResponse.SC_FORBIDDEN,
			postAs("/", "delete-contact", "{\"contact\":\"" + contact + "\"}", SharingFixture.DAVE, null).status());
		FakeResponse deleted = postAs("/", "delete-contact", "{\"contact\":\"" + contact + "\"}",
			SharingFixture.ALICE, null);
		assertEquals(deleted.body(), 200, deleted.status());
		Contact was = Contact.readContact(reader(body(deleted)));
		assertEquals("The answer is the contact as it was.", "Tante Petra", was.getName());
		assertEquals(1, was.getUploads());

		assertNull(contact(contact));
		assertNull(new ContactStore(_base).get(contact));
		ImagePart image = image(get(ZOO, "json", SharingFixture.ALICE), "petras.jpg");
		assertEquals("The copied name stays on the upload.", "Tante Petra", image.getContributorLabel());
		assertEquals("contact:" + contact, image.getContributor());

		FakeResponse own = get("/", "json", token);
		assertEquals("Her own link is a token nobody issued.", HttpServletResponse.SC_UNAUTHORIZED, own.status());
		assertEquals(AuthService.TOKEN_REFUSED, errorMessage(own));
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, getAs("/", "json", created.getToken(), credential)
			.status());

		ShareLink link = listed(ZOO, created.getLink().getId());
		assertTrue(link.getRecipients().isEmpty());
		assertTrue("A link that lost its recipients stays closed.", link.isAddressed());
		assertTrue(new ShareStore(_base).get(created.getLink().getId()).isAddressed());
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, get("/", "json", created.getToken()).status());

		FakeResponse again = postAs("/", "delete-contact", "{\"contact\":\"" + contact + "\"}",
			SharingFixture.ALICE, null);
		assertEquals(HttpServletResponse.SC_NOT_FOUND, again.status());
		assertEquals(ContactStore.unknownContact(contact), errorMessage(again));

		FakeResponse anew = sharePersonal(ZOO, SharingFixture.ALICE, email("Petra", PETRA));
		assertEquals("A deleted contact may be entered anew: " + anew.body(), 200, anew.status());
		assertFalse(contactOf(created(anew), "Petra").equals(contact));
	}

	// --- What the owner of a link sees. ---

	public void testTheLinkListShowsWhenEachRecipientCameAndWhatTheyAdded() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA), phone("Klaus", KLAUS));
		String petra = contactOf(created, "Tante Petra");
		String token = tokenOf(created, "Tante Petra");
		String credential = credential(token);
		assertEquals(200, getAs("/", "json", token, credential).status());
		assertEquals(200, uploadAs("/", token, credential, "petras.jpg", photo("petras")).status());
		assertEquals(200, uploadAs("/", token, credential, "petras2.jpg", photo("petras2")).status());

		ShareLink link = listed(ZOO, created.getLink().getId());
		assertTrue(link.isAddressed());
		ShareRecipient recipient = recipient(link, petra);
		assertFalse(recipient.getFirstOpened().isEmpty());
		assertFalse(recipient.getLastSeen().isEmpty());
		assertEquals(2, recipient.getUploads());
		ShareRecipient klaus = recipient(link, contactOf(created, "Klaus"));
		assertTrue("Klaus never came.", klaus.getFirstOpened().isEmpty());
		assertEquals(0, klaus.getUploads());
		assertTrue("Recipients are no visitors.", link.getVisitors().isEmpty());

		postAs(ZOO, "resend", "{\"link\":\"" + created.getLink().getId() + "\",\"contact\":\"" + petra + "\"}",
			SharingFixture.ALICE, null);
		ShareRecipient resent = recipient(listed(ZOO, created.getLink().getId()), petra);
		assertTrue("The fresh link was not opened yet.", resent.getOpened().isEmpty());
		assertEquals("When she first came stays.", recipient.getFirstOpened(), resent.getFirstOpened());

		assertEquals("The contacts count her uploads space-wide.", 2, contact(petra).getUploads());
		restartServer();
		assertEquals("The visit is stored.", recipient.getFirstOpened(),
			recipient(listed(ZOO, created.getLink().getId()), petra).getFirstOpened());
	}

	public void testAnOpenLinkListsItsVisitorsAndShutsOneOut() throws Exception {
		ShareLinkCreated addressed = created(ZOO, email("Tante Petra", PETRA));
		String petra = contactOf(addressed, "Tante Petra");
		String credential = credential(tokenOf(addressed, "Tante Petra"));
		ShareLinkCreated open = created(OPEN);
		assertFalse(open.getLink().isAddressed());
		assertEquals(200, getAs("/", "json", open.getToken(), credential).status());
		assertEquals(200, uploadAs("/", open.getToken(), credential, "via-open.jpg", photo("via-open")).status());

		ShareLink link = listed(OPEN, open.getLink().getId());
		assertEquals(1, link.getVisitors().size());
		LinkVisitor visitor = link.getVisitors().get(0);
		assertEquals(petra, visitor.getContact());
		assertEquals("Tante Petra", visitor.getName());
		assertFalse(visitor.getFirstSeen().isEmpty());
		assertEquals(1, visitor.getUploads());
		assertEquals("Uploads are counted per link.", 0,
			recipient(listed(ZOO, addressed.getLink().getId()), petra).getUploads());
		assertEquals(1, contact(petra).getUploads());

		FakeResponse shut = postAs(OPEN, "shut-out", "{\"link\":\"" + open.getLink().getId() + "\",\"contact\":\""
			+ petra + "\",\"shutOut\":true}", SharingFixture.ALICE, null);
		assertEquals(shut.body(), 200, shut.status());
		assertFalse(links(shut).getLinks().get(0).getVisitors().get(0).getShutOut().isEmpty());
		assertEquals(HttpServletResponse.SC_GONE, getAs("/", "json", open.getToken(), credential).status());
		assertEquals("Shut out of one link, she still opens the other.", 200,
			getAs("/", "json", addressed.getToken(), credential).status());
	}

	// --- The contact's own view. ---

	public void testAContactSignsOutOnlyTheirOwnOtherSessions() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA), phone("Klaus", KLAUS));
		String petra = contactOf(created, "Tante Petra");
		String first = credential(tokenOf(created, "Tante Petra"));
		String second = secondBrowser(created, petra);
		String klaus = credential(tokenOf(created, "Klaus"));
		String link = created.getToken();

		assertEquals(1, auth(getAs("/", "auth", link, second)).getShare().getOtherSessions());
		assertEquals(0, auth(getAs("/", "auth", link, klaus)).getShare().getOtherSessions());

		FakeResponse ended = postAs("/", "end-other-sessions", "{}", link, second);
		assertEquals(ended.body(), 200, ended.status());
		assertEquals(1, OtherSessionsEnded.readOtherSessionsEnded(reader(body(ended))).getEnded());
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, getAs("/", "json", link, first).status());
		assertEquals("This browser stays.", 200, getAs("/", "json", link, second).status());
		assertEquals("Somebody else's sessions are not hers.", 200, getAs("/", "json", link, klaus).status());
		assertEquals(0, auth(getAs("/", "auth", link, second)).getShare().getOtherSessions());

		FakeResponse member = postAs("/", "end-other-sessions", "{}", SharingFixture.ALICE, null);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, member.status());
		assertEquals(PersonalLinks.OTHER_SESSIONS_REFUSED, errorMessage(member));
		FakeResponse anonymousLink = postAs("/", "end-other-sessions", "{}", zooToken(), null);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, anonymousLink.status());
		assertEquals(PersonalLinks.OTHER_SESSIONS_REFUSED, errorMessage(anonymousLink));
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, postAs("/", "end-other-sessions", "{}", null, null).status());
		FakeResponse unidentified = postAs("/", "end-other-sessions", "{}", link, null);
		assertEquals("Nobody yet is asked who they are.", HttpServletResponse.SC_UNAUTHORIZED, unidentified.status());
		assertNotNull(identifyRequired(unidentified));
	}

	public void testNoLinkManagesContacts() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA));
		String contact = contactOf(created, "Tante Petra");
		String token = tokenOf(created, "Tante Petra");
		String credential = credential(token);
		for (String action : new String[] { "rename-contact", "end-contact-session", "delete-contact" }) {
			String body = "{\"contact\":\"" + contact + "\",\"name\":\"X\"}";
			FakeResponse contactCaller = postAs("/", action, body, token, credential);
			assertEquals(action, HttpServletResponse.SC_FORBIDDEN, contactCaller.status());
			assertEquals(action, PersonalLinks.CONTACTS_MANAGE_REFUSED, errorMessage(contactCaller));
			FakeResponse anonymousLink = postAs("/", action, body, zooToken(), null);
			assertEquals(action, HttpServletResponse.SC_FORBIDDEN, anonymousLink.status());
			assertEquals(action, HttpServletResponse.SC_UNAUTHORIZED, postAs("/", action, body, null, null).status());
			assertEquals(action, HttpServletResponse.SC_FORBIDDEN,
				postAs("/", action, body, SharingFixture.EVE, null).status());
		}
		assertNotNull("Nothing was changed.", new ContactStore(_base).get(contact));
		assertEquals("Tante Petra", new ContactStore(_base).get(contact).getName());
	}

	private static ShareRecipient recipient(ShareLink link, String contact) {
		for (ShareRecipient recipient : link.getRecipients()) {
			if (recipient.getContact().equals(contact)) {
				return recipient;
			}
		}
		fail("No recipient '" + contact + "' in " + link);
		return null;
	}
}
