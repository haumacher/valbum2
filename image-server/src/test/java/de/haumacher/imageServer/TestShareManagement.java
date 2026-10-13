/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.Clearances;
import de.haumacher.imageServer.auth.ContactStore;
import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.auth.ShareStore;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.shared.model.LinkVisitor;
import de.haumacher.imageServer.shared.model.ShareLink;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import de.haumacher.imageServer.shared.model.ShareLinkList;
import de.haumacher.imageServer.shared.model.ShareRecipient;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The shares of a space in Manage: what is shared with whom, revoked and edited in place, see issue
 * #269.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestShareManagement extends PersonalLinkTestCase {

	private static final String OPEN = "/" + SharingFixture.PUBLIC + "/";

	// --- Who sees what. ---

	public void testAnAdministratorSeesEveryLinkAMemberTheirOwnAndAMemberWithoutTheFlagIsRefused()
			throws Exception {
		ShareLinkCreated alices = created(share(ZOO, SharingFixture.ALICE, shareBody("Family", "", 0, -2, "view")));
		ShareLinkCreated bobs = created(share(OPEN, SharingFixture.BOB, shareBody("Neighbours", "", 0, -2, "view")));

		assertEquals(ids(alices, bobs), ids(spaceShares(SharingFixture.ALICE)));
		assertEquals("A sharing member sees what they handed out, wherever they ask.", ids(bobs),
			ids(spaceShares(SharingFixture.BOB)));

		FakeResponse refused = get("/", ImageServlet.SPACE_SHARES_TYPE, SharingFixture.DAVE);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, refused.status());
		assertEquals("The reason is said.", AuthService.SHARING_REFUSED, errorMessage(refused));
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, get("/", ImageServlet.SPACE_SHARES_TYPE, null).status());
		assertEquals("A share link sees no list of links.", HttpServletResponse.SC_FORBIDDEN,
			get("/", ImageServlet.SPACE_SHARES_TYPE, alices.getToken()).status());
	}

	public void testEachLinkCarriesItsFolderTitleLabelsAndUploadsAndNeverAToken() throws Exception {
		ShareLinkCreated party = created(ZOO, email("Tante Petra", PETRA));
		String credential = credential(tokenOf(party, "Tante Petra"));
		assertEquals(200, uploadAs("/", tokenOf(party, "Tante Petra"), credential, "petras.jpg", photo("petras"))
			.status());
		ShareLinkCreated anonymous = created(share(OPEN, SharingFixture.ALICE,
			shareBody("Anyone", "", 0, -2, "view", "contribute")));
		assertEquals(200, upload("/", anonymous.getToken(), "anonymous.jpg").status());

		ShareLinkList listed = spaceShares(SharingFixture.ALICE);
		ShareLink zoo = link(listed, party.getLink().getId());
		assertEquals("Zoo", zoo.getTitle());
		assertEquals(SharingFixture.ZOO, zoo.getPath());
		assertEquals(1, zoo.getUploads());
		ShareLink open = link(listed, anonymous.getLink().getId());
		assertEquals("Public", open.getTitle());
		assertEquals("An upload with the link's own token counts for it.", 1, open.getUploads());

		String body = body(get("/", ImageServlet.SPACE_SHARES_TYPE, SharingFixture.ALICE));
		for (String token : new String[] { party.getToken(), tokenOf(party, "Tante Petra"), anonymous.getToken() }) {
			assertFalse(body.contains(token));
		}
		assertFalse(body.contains("tokenHash"));
	}

	public void testAnExpiredLinkIsListedAndAnOldStoreReadsUnchanged() throws Exception {
		Path file = new ShareStore(_base).getFile();
		Files.createDirectories(file.getParent());
		Files.copy(new File("src/test/fixtures/personal-links/shares-v1.json").toPath(), file);
		restartServer();
		String expired = idOf(issue("alice", SharingFixture.ZOO, "Last year", "2000-01-01T00:00:00Z", 0, -2));

		ShareLinkList listed = spaceShares(SharingFixture.ALICE);
		assertNotNull("A link of the file an earlier build wrote is listed.", link(listed, "oldLink1"));
		assertNull("A withdrawn one is not.", link(listed, "oldLink2"));
		assertEquals("2000-01-01T00:00:00Z", link(listed, expired).getExpires());
	}

	// --- By person: the rule of the overview of #267. ---

	public void testAContactIsAdmittedByEveryLinkTheOverviewListsAndByNoneTheyAreShutOutOf() throws Exception {
		ShareLinkCreated party = created(ZOO, email("Tante Petra", PETRA), phone("Klaus", KLAUS));
		String petra = contactOf(party, "Tante Petra");
		String klaus = contactOf(party, "Klaus");
		String credential = credential(tokenOf(party, "Tante Petra"));
		ShareLinkCreated open = created(OPEN);
		assertEquals(200, getAs("/", "json", open.getToken(), credential).status());
		ShareLinkCreated shut = created("/" + SharingFixture.CAROLS_ALBUM + "/", known(petra));
		FakeResponse shutOut = postAs("/", "shut-out", "{\"link\":\"" + shut.getLink().getId() + "\",\"contact\":\""
			+ petra + "\",\"shutOut\":true}", SharingFixture.ALICE, null);
		assertEquals(shutOut.body(), 200, shutOut.status());

		ShareLinkList listed = spaceShares(SharingFixture.ALICE);
		assertEquals(set(party.getLink().getId(), open.getLink().getId()), admitting(listed, petra));
		assertEquals(set(party.getLink().getId()), admitting(listed, klaus));

		// The very rule of the overview: the same links, for every contact.
		AuthService auth = servlet().auth();
		for (ContactStore.Contact contact : auth.getContacts().getContacts()) {
			Set<String> overview = new TreeSet<>();
			for (ShareStore.Link link : auth.sharedWith(contact)) {
				overview.add(link.getId());
			}
			assertEquals(contact.getName(), overview, admitting(listed, contact.getId()));
		}

		// Shut out of the whole space: admitted nowhere.
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "block-contact");
		assertEquals(200, post("/", "{\"contact\":\"" + petra + "\",\"shutOut\":true}", SharingFixture.ALICE,
			parameters).status());
		assertEquals(set(), admitting(spaceShares(SharingFixture.ALICE), petra));
	}

	// --- Editing in place. ---

	public void testDroppingDownloadRefusesTheDownloadAtTheNextRequestWithTheSameToken() throws Exception {
		String token = zooToken(Rights.VIEW, Rights.DOWNLOAD);
		String id = idOf(token);
		String hash = new ShareStore(_base).get(id).getTokenHash();
		assertEquals(200, get("/public.jpg", null, token).status());

		FakeResponse edited = edit("/", SharingFixture.ALICE, id, "Grandma", "", Privacy.PUBLIC, 0, "", "view");
		assertEquals(edited.body(), 200, edited.status());
		assertEquals(Arrays.asList(Rights.VIEW), rightsOf(links(edited).getLinks().get(0)));

		FakeResponse refused = get("/public.jpg", null, token);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, refused.status());
		assertEquals(AuthService.DOWNLOAD_REFUSED, errorMessage(refused));
		assertEquals("Still the same link.", 200, get("/public.jpg", "tn", token).status());
		assertEquals("The token stays the same.", hash, new ShareStore(_base).get(id).getTokenHash());
	}

	public void testLoweringThePrivacyCeilingAndRaisingTheFloorHidePhotosAtOnce() throws Exception {
		String token = issue("alice", SharingFixture.ZOO, "Family", "", Privacy.MEMBERS, -2, Rights.VIEW);
		String id = idOf(token);
		assertEquals(Arrays.asList("public.jpg", "members.jpg", REJECTED), names(album(get("/", "json", token))));

		assertEquals(200, edit(ZOO, SharingFixture.ALICE, id, "Family", "", Privacy.PUBLIC, -2, "", "view").status());
		assertEquals(Arrays.asList("public.jpg", REJECTED), names(album(get("/", "json", token))));
		assertEquals(HttpServletResponse.SC_FORBIDDEN, get("/members.jpg", "tn", token).status());

		assertEquals(200, edit(ZOO, SharingFixture.ALICE, id, "Family", "", Privacy.PUBLIC, 0, "", "view").status());
		assertEquals(Arrays.asList("public.jpg"), names(album(get("/", "json", token))));
	}

	public void testEveryOptionIsStoredAndReadBack() throws Exception {
		ShareLinkCreated party = created(ZOO, email("Tante Petra", PETRA));
		String id = party.getLink().getId();
		String expires = Instant.now().plus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();
		String before = new ShareStore(_base).get(id).getTokenHash();

		FakeResponse edited = edit("/", SharingFixture.ALICE, id, "Party 2", expires, Privacy.MEMBERS, 1, "",
			"view", "download");
		assertEquals(edited.body(), 200, edited.status());
		ShareLink answered = links(edited).getLinks().get(0);
		assertEquals("Party 2", answered.getLabel());
		assertEquals(expires, answered.getExpires());
		assertEquals(Privacy.MEMBERS, answered.getMaxPrivacy());
		assertEquals(1, answered.getMinRating());
		assertEquals(Arrays.asList(Rights.VIEW, Rights.DOWNLOAD), rightsOf(answered));
		assertEquals("Zoo", answered.getTitle());

		restartServer();
		ShareStore.Link stored = new ShareStore(_base).get(id);
		assertEquals("Party 2", stored.getLabel());
		assertEquals(expires, stored.getExpires());
		assertEquals(Privacy.MEMBERS, stored.getMaxPrivacy());
		assertEquals(1, stored.getMinRating());
		assertEquals(Set.of(Rights.VIEW, Rights.DOWNLOAD), stored.getRights());
		assertEquals(before, stored.getTokenHash());
		assertTrue("The type and the recipients stay.", stored.isAddressed());
		assertEquals(1, stored.getRecipients().size());
		ShareLink listed = link(spaceShares(SharingFixture.ALICE), id);
		assertEquals("Party 2", listed.getLabel());

		// The recipient's own link still opens it, with what it allows now.
		String credential = credential(tokenOf(party, "Tante Petra"));
		assertEquals(200, getAs("/", "json", tokenOf(party, "Tante Petra"), credential).status());
		assertEquals("Contributing was dropped.", HttpServletResponse.SC_FORBIDDEN,
			uploadAs("/", tokenOf(party, "Tante Petra"), credential, "late.jpg", photo("late")).status());

		// And no expiry again.
		assertEquals(200, edit("/", SharingFixture.ALICE, id, "Party 2", "", Privacy.MEMBERS, 1, "", "view").status());
		assertEquals("", new ShareStore(_base).get(id).getExpires());
	}

	public void testThePhotoLabelIsEditedToALabelOfTheAlbumOnly() throws Exception {
		String token = issue("alice", SharingFixture.ZOO, "Family", "", Privacy.PUBLIC, -2, Rights.VIEW);
		String id = idOf(token);
		FakeResponse unknown = edit("/", SharingFixture.ALICE, id, "Family", "", Privacy.PUBLIC, -2, "Wedding", "view");
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, unknown.status());
		assertEquals(Labels.unknownLabel("Wedding"), errorMessage(unknown));
		assertEquals("", new ShareStore(_base).get(id).getPhotoLabel());
	}

	// --- Refusals speak. ---

	public void testAnotherMembersLinkIsNoneOfTheirs() throws Exception {
		String alices = zooToken(Rights.VIEW);
		FakeResponse refused = edit("/", SharingFixture.BOB, idOf(alices), "Mine now", "", 0, -2, "", "view");
		assertEquals(HttpServletResponse.SC_NOT_FOUND, refused.status());
		assertEquals(AuthService.SHARE_UNKNOWN, errorMessage(refused));
		assertEquals("Grandma", new ShareStore(_base).get(idOf(alices)).getLabel());
		assertEquals(HttpServletResponse.SC_FORBIDDEN,
			edit("/", SharingFixture.DAVE, idOf(alices), "x", "", 0, -2, "", "view").status());
	}

	public void testARightTheEditorDoesNotHoldIsRefusedAndOneTheLinkHadIsKept() throws Exception {
		UserStore users = new UserStore(_base);
		users.getUser("dave").setShare(true);
		users.store();
		restartServer();
		FakeResponse made = share(ZOO, SharingFixture.DAVE, shareBody("Daves", "", 0, -2, "view"));
		String id = created(made).getLink().getId();

		FakeResponse refused = edit("/", SharingFixture.DAVE, id, "Daves", "", 0, -2, "", "view", "contribute");
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, refused.status());
		assertEquals(AuthService.shareRightNotHeld(Rights.CONTRIBUTE), errorMessage(refused));
		assertEquals(Set.of(Rights.VIEW), new ShareStore(_base).get(id).getRights());

		// An administrator gives it; dave may still edit the rest without taking it away.
		assertEquals(200, edit("/", SharingFixture.ALICE, id, "Daves", "", 0, -2, "", "view", "contribute").status());
		assertEquals(200,
			edit("/", SharingFixture.DAVE, id, "Renamed", "", 0, -2, "", "view", "contribute").status());
		assertEquals(Set.of(Rights.VIEW, Rights.CONTRIBUTE), new ShareStore(_base).get(id).getRights());

		// The privacy ceiling likewise: eve sees public photos only.
		users = new UserStore(_base);
		users.getUser("dave").setClearance(Clearances.PUBLIC);
		users.store();
		restartServer();
		FakeResponse above = edit("/", SharingFixture.DAVE, id, "Renamed", "", Privacy.MEMBERS, -2, "", "view");
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, above.status());
		assertEquals(AuthService.shareAboveClearance(Privacy.PUBLIC), errorMessage(above));
	}

	public void testAnExpiryInThePastIsRefused() throws Exception {
		String id = idOf(zooToken(Rights.VIEW));
		FakeResponse refused = edit("/", SharingFixture.ALICE, id, "Grandma", "2001-01-01T00:00:00Z", 0, 0, "", "view");
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, refused.status());
		assertEquals(AuthService.SHARE_EXPIRY_PAST, errorMessage(refused));
		assertEquals("", new ShareStore(_base).get(id).getExpires());

		FakeResponse unreadable = edit("/", SharingFixture.ALICE, id, "Grandma", "tomorrow", 0, 0, "", "view");
		assertEquals(AuthService.SHARE_EXPIRY_REFUSED, errorMessage(unreadable));
		assertEquals(HttpServletResponse.SC_NOT_FOUND,
			edit("/", SharingFixture.ALICE, "nonexistent", "x", "", 0, 0, "", "view").status());
	}

	// --- Revoking and recipients, from the root of the space. ---

	public void testRevokingFromTheRootClosesTheAddress() throws Exception {
		String token = zooToken(Rights.VIEW);
		assertEquals(200, get("/", "json", token).status());
		FakeResponse deleted = unshare("/", SharingFixture.ALICE, idOf(token));
		assertEquals(deleted.body(), 200, deleted.status());
		assertEquals(get("/", "json", "never-issued").status(), get("/", "json", token).status());
		assertTrue(spaceShares(SharingFixture.ALICE).getLinks().isEmpty());
	}

	public void testRemovingARecipientVoidsTheirTokenAndKeepsTheOthers() throws Exception {
		ShareLinkCreated party = created(ZOO, email("Tante Petra", PETRA), phone("Klaus", KLAUS));
		String id = party.getLink().getId();
		String petra = contactOf(party, "Tante Petra");
		String petrasToken = tokenOf(party, "Tante Petra");
		String petrasCredential = credential(petrasToken);
		String klausCredential = credential(tokenOf(party, "Klaus"));
		assertEquals(200, getAs("/", "json", petrasToken, petrasCredential).status());

		FakeResponse removed = postAs("/", "remove-recipient",
			"{\"link\":\"" + id + "\",\"contact\":\"" + petra + "\"}", SharingFixture.ALICE, null);
		assertEquals(removed.body(), 200, removed.status());
		ShareLink answered = links(removed).getLinks().get(0);
		assertEquals(1, answered.getRecipients().size());
		assertEquals("Klaus", answered.getRecipients().get(0).getName());
		assertTrue("Her visit goes with her.", answered.getVisitors().isEmpty());
		assertTrue(answered.isAddressed());

		// Her token is one nobody issued; her session through the link is over.
		for (String type : new String[] { "json", "auth" }) {
			assertEquals(type, getAs("/", type, "never-issued", null).status(),
				getAs("/", type, petrasToken, null).status());
		}
		assertTrue(getAs("/", "json", party.getToken(), petrasCredential).status() >= 400);
		assertNull(new ContactStore(_base).recognize(petrasCredential));
		assertNotNull("She stays a contact of the space.", new ContactStore(_base).get(petra));

		// Klaus still gets in.
		assertEquals(200, getAs("/", "json", tokenOf(party, "Klaus"), klausCredential).status());

		FakeResponse again = postAs("/", "remove-recipient",
			"{\"link\":\"" + id + "\",\"contact\":\"" + petra + "\"}", SharingFixture.ALICE, null);
		assertEquals(HttpServletResponse.SC_NOT_FOUND, again.status());
		assertEquals(PersonalLinks.NOT_A_RECIPIENT, errorMessage(again));
		assertEquals("Not hers to manage.", HttpServletResponse.SC_NOT_FOUND, postAs("/", "remove-recipient",
			"{\"link\":\"" + id + "\",\"contact\":\"" + contactOf(party, "Klaus") + "\"}", SharingFixture.BOB, null)
				.status());

		restartServer();
		ShareStore.Link stored = new ShareStore(_base).get(id);
		assertNull(stored.recipient(petra));
		assertTrue(stored.isAddressed());
	}

	public void testRenewingAndShuttingOutWorkFromTheRootToo() throws Exception {
		ShareLinkCreated party = created(ZOO, email("Tante Petra", PETRA));
		String petra = contactOf(party, "Tante Petra");
		FakeResponse resent = postAs("/", "resend",
			"{\"link\":\"" + party.getLink().getId() + "\",\"contact\":\"" + petra + "\"}", SharingFixture.ALICE, null);
		assertEquals(resent.body(), 200, resent.status());
		String fresh = created(resent).getRecipients().get(0).getToken();
		assertFalse(fresh.equals(tokenOf(party, "Tante Petra")));

		FakeResponse shut = postAs("/", "shut-out", "{\"link\":\"" + party.getLink().getId() + "\",\"contact\":\""
			+ petra + "\",\"shutOut\":true}", SharingFixture.ALICE, null);
		assertEquals(shut.body(), 200, shut.status());
		ShareRecipient recipient = link(spaceShares(SharingFixture.ALICE), party.getLink().getId()).getRecipients()
			.get(0);
		assertFalse(recipient.getShutOut().isEmpty());
		assertFalse(recipient.isAdmitted());
	}

	// --- Helpers. ---

	private ShareLinkList spaceShares(String token) throws Exception {
		FakeResponse response = get("/", ImageServlet.SPACE_SHARES_TYPE, token);
		assertEquals(response.body(), 200, response.status());
		return links(response);
	}

	private FakeResponse edit(String pathInfo, String token, String id, String label, String expires, int maxPrivacy,
			int minRating, String photoLabel, String... rights) throws Exception {
		StringBuilder body = new StringBuilder("{\"id\":\"").append(id).append("\",\"label\":\"").append(label)
			.append("\",\"expires\":\"").append(expires).append("\",\"maxPrivacy\":").append(maxPrivacy)
			.append(",\"minRating\":").append(minRating).append(",\"photoLabel\":\"").append(photoLabel)
			.append("\",\"rights\":[");
		for (int n = 0; n < rights.length; n++) {
			body.append(n == 0 ? "" : ",").append("{\"name\":\"").append(rights[n]).append("\"}");
		}
		body.append("]}");
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "edit-share");
		return post(pathInfo, body.toString(), token, parameters);
	}

	/** The ids of the links admitting the given contact, the expiry aside. */
	private static Set<String> admitting(ShareLinkList list, String contact) {
		Set<String> result = new TreeSet<>();
		for (ShareLink link : list.getLinks()) {
			for (ShareRecipient recipient : link.getRecipients()) {
				if (recipient.getContact().equals(contact) && recipient.isAdmitted()) {
					result.add(link.getId());
				}
			}
			for (LinkVisitor visitor : link.getVisitors()) {
				if (visitor.getContact().equals(contact) && visitor.isAdmitted()) {
					result.add(link.getId());
				}
			}
		}
		return result;
	}

	private static Set<String> ids(ShareLinkCreated... created) {
		Set<String> result = new TreeSet<>();
		for (ShareLinkCreated link : created) {
			result.add(link.getLink().getId());
		}
		return result;
	}

	private static Set<String> ids(ShareLinkList list) {
		Set<String> result = new TreeSet<>();
		for (ShareLink link : list.getLinks()) {
			result.add(link.getId());
		}
		return result;
	}

	private static Set<String> set(String... ids) {
		return new TreeSet<>(Arrays.asList(ids));
	}

	private static List<String> names(de.haumacher.imageServer.shared.model.AlbumInfo album) {
		List<String> result = new ArrayList<>();
		for (de.haumacher.imageServer.shared.model.AlbumPart part : album.getParts()) {
			if (part instanceof de.haumacher.imageServer.shared.model.ImagePart) {
				result.add(((de.haumacher.imageServer.shared.model.ImagePart) part).getName());
			}
		}
		return result;
	}
}
