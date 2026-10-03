/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.ContactStore;
import de.haumacher.imageServer.auth.ShareStore;
import de.haumacher.imageServer.shared.model.AuthInfo;
import de.haumacher.imageServer.shared.model.ContactCredential;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.IdentifyRequired;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.ShareLink;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import de.haumacher.imageServer.shared.model.ShareType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Personal share links, see issue #198: per-recipient links that identify once, the contact
 * credential, and what a contact brings attributed to them.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestPersonalLinks extends PersonalLinkTestCase {

	// --- Anonymous links as before. ---

	public void testAnExistingShareStoreLoadsUnchanged() throws Exception {
		Path file = new ShareStore(_base).getFile();
		Files.createDirectories(file.getParent());
		Files.copy(new File("src/test/fixtures/personal-links/shares-v1.json").toPath(), file);
		restartServer();

		ShareStore store = new ShareStore(_base);
		ShareStore.Link old = store.get("oldLink1");
		assertEquals(ShareStore.ANONYMOUS, old.getType());
		assertFalse(old.isPersonal());
		assertTrue(old.getRecipients().isEmpty());
		assertEquals("Grandma", old.getLabel());
		assertTrue(store.get("oldLink2").isRevoked());

		FakeResponse album = get("/", "json", "an-old-share-link-token");
		assertEquals("An anonymous link opens what it opened: " + album.body(), 200, album.status());
		AuthInfo auth = auth(get("/", "auth", "an-old-share-link-token"));
		assertEquals(ShareType.ANONYMOUS, auth.getShare().getType());
		assertNull("An anonymous link is nobody.", auth.getShare().getContact());
	}

	public void testTheStoreRoundTripsReadAndWrite() throws Exception {
		Path file = new ShareStore(_base).getFile();
		Files.createDirectories(file.getParent());
		Files.copy(new File("src/test/fixtures/personal-links/shares-v1.json").toPath(), file);
		created(ZOO, email("Tante Petra", PETRA), phone("Klaus", KLAUS));

		new ShareStore(_base).store();
		String first = shareStoreContents();
		ShareStore again = new ShareStore(_base);
		again.store();
		assertEquals("What is read is what is written.", first, shareStoreContents());
		assertTrue(first, first.contains("\"oldLink1\""));
		assertTrue(first, first.contains("\"type\":\"personal\""));
		assertFalse("An anonymous link is written as before: " + first,
			first.substring(0, first.indexOf("oldLink2")).contains("\"type\""));
		assertEquals(2, again.getLinks().get(2).getRecipients().size());
	}

	public void testAnAnonymousLinkWithRecipientsIsRefused() throws Exception {
		FakeResponse response = share(ZOO, SharingFixture.ALICE, "{\"label\":\"x\",\"recipients\":["
			+ email("Tante Petra", PETRA) + "]}");

		assertEquals(response.body(), HttpServletResponse.SC_BAD_REQUEST, response.status());
		assertEquals(PersonalLinks.ANONYMOUS_RECIPIENTS, errorMessage(response));
		assertTrue("Nobody was entered.", new ContactStore(_base).getContacts().isEmpty());
	}

	public void testACredentialBesideAnAnonymousLinkIsIgnored() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA));
		String credential = credential(tokenOf(created, "Tante Petra"));
		String anonymous = zooToken();

		AuthInfo auth = auth(getAs("/", "auth", anonymous, credential));
		assertNull(auth.getShare().getContact());
		assertEquals(ShareType.ANONYMOUS, auth.getShare().getType());
	}

	// --- Addressed links identify once. ---

	public void testTwoRecipientsGetTwoLinks() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA), phone("Klaus", KLAUS));

		assertEquals(2, created.getRecipients().size());
		String petra = tokenOf(created, "Tante Petra");
		String klaus = tokenOf(created, "Klaus");
		assertFalse(petra.equals(klaus));
		assertTrue(created.getRecipients().get(0).getUrl(),
			created.getRecipients().get(0).getUrl().endsWith("/s/" + petra + "/"));
		assertEquals("petra@gmx.de", created.getRecipients().get(0).getAddresses().get(0).getValue());
		assertEquals("+491711234567", created.getRecipients().get(1).getAddresses().get(0).getValue());
		assertEquals(ShareType.PERSONAL, created.getLink().getType());
		assertEquals(2, created.getLink().getRecipients().size());
		assertEquals("", created.getLink().getRecipients().get(0).getOpened());

		String store = shareStoreContents();
		assertFalse("No token is stored: " + store, store.contains(petra) || store.contains(klaus));
	}

	public void testTheFirstOpenIdentifiesAndTheSecondAsksWhoItIs() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA), phone("Klaus", KLAUS));
		String petra = tokenOf(created, "Tante Petra");
		String klaus = tokenOf(created, "Klaus");

		FakeResponse before = get("/", "json", petra);
		assertEquals("Not let in before the open is confirmed: " + before.body(),
			HttpServletResponse.SC_UNAUTHORIZED, before.status());
		IdentifyRequired first = identifyRequired(before);
		assertTrue(first.isFirstOpen());
		assertEquals("Tante Petra", first.getContact().getDisplayName());
		assertEquals("alice", first.getSharedBy());
		assertTrue("A first open names no address.", first.getAddresses().isEmpty());

		FakeResponse opened = postAs("/", "identify", "{\"remember\":true,\"displayName\":\"Petra M.\"}", petra,
			null);
		assertEquals(opened.body(), 200, opened.status());
		ContactCredential petras = ContactCredential.readContactCredential(reader(body(opened)));
		assertTrue(petras.isRemember());
		assertEquals("Petra M.", petras.getContact().getDisplayName());
		assertEquals(contactOf(created, "Tante Petra"), petras.getContact().getId());

		String klauses = credential(klaus);
		assertFalse(klauses.equals(petras.getCredential()));

		for (String token : new String[] { petra, klaus }) {
			FakeResponse again = get("/", "json", token);
			assertEquals(again.body(), HttpServletResponse.SC_UNAUTHORIZED, again.status());
			IdentifyRequired required = identifyRequired(again);
			assertFalse(required.isFirstOpen());
			assertEquals(AuthService.IDENTIFY_REQUIRED, errorMessage(again));
			assertTrue("The album is never answered: " + again.body(), !again.body().contains("AlbumInfo"));
			FakeResponse auth = get("/", "auth", token);
			assertEquals(HttpServletResponse.SC_UNAUTHORIZED, auth.status());
			FakeResponse identifyAgain = identify(token, true);
			assertEquals(HttpServletResponse.SC_UNAUTHORIZED, identifyAgain.status());
			assertTrue(identifyRequired(identifyAgain).getMethods().isEmpty());
		}
		IdentifyRequired masked = identifyRequired(get("/", "json", petra));
		assertEquals("p•••@gmx.de", masked.getAddresses().get(0).getMasked());
		assertFalse(get("/", "json", petra).body().contains("petra@"));
		assertEquals("+49•••67", identifyRequired(get("/", "json", klaus)).getAddresses().get(0)
			.getMasked());

		FakeResponse album = getAs("/", "json", petra, petras.getCredential());
		assertEquals(album.body(), 200, album.status());
		AuthInfo auth = auth(getAs("/", "auth", petra, petras.getCredential()));
		assertEquals(ShareType.PERSONAL, auth.getShare().getType());
		assertEquals(petras.getContact().getId(), auth.getShare().getContact().getId());
		assertEquals("Petra M.", auth.getShare().getContact().getDisplayName());
		assertTrue(auth.isWriteAllowed());

		ShareLink listed = links(shares(ZOO, SharingFixture.ALICE)).getLinks().get(0);
		assertFalse("The listing says when it was opened.", listed.getRecipients().get(0).getOpened().isEmpty());
	}

	public void testALinksOwnTokenNeedsACredential() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA));

		FakeResponse plain = get("/", "json", created.getToken());
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, plain.status());
		IdentifyRequired required = identifyRequired(plain);
		assertEquals(AuthService.IDENTIFY_PERSONAL, errorMessage(plain));
		assertNull(required.getContact());
		FakeResponse identified = identify(created.getToken(), true);
		assertEquals("The link's own token identifies nobody.", HttpServletResponse.SC_UNAUTHORIZED,
			identified.status());
		identifyRequired(identified);
		assertEquals(AuthService.IDENTIFY_PERSONAL, errorMessage(identified));

		String credential = credential(tokenOf(created, "Tante Petra"));
		assertEquals(200, getAs("/", "json", created.getToken(), credential).status());
	}

	public void testASessionCredentialLivesADayAndIsNotRenewed() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA));
		FakeResponse response = identify(tokenOf(created, "Tante Petra"), false);
		ContactCredential credential = ContactCredential.readContactCredential(reader(body(response)));

		assertFalse(credential.isRemember());
		long hours = java.time.Duration.between(java.time.Instant.now(),
			java.time.Instant.parse(credential.getExpires())).toHours();
		assertEquals(23, hours);
		String store = new String(Files.readAllBytes(new ContactStore(_base).getFile()), StandardCharsets.UTF_8);
		assertFalse("The credential is stored as its hash: " + store, store.contains(credential.getCredential()));
	}

	public void testOnlyOneOfTwoConcurrentFirstOpensWins() throws Exception {
		for (int round = 0; round < 5; round++) {
			ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA));
			String token = tokenOf(created, "Tante Petra");
			servlet();
			int threads = 8;
			CountDownLatch start = new CountDownLatch(1);
			ExecutorService pool = Executors.newFixedThreadPool(threads);
			try {
				List<Future<Integer>> results = new ArrayList<>();
				for (int n = 0; n < threads; n++) {
					results.add(pool.submit((Callable<Integer>) () -> {
						start.await();
						return Integer.valueOf(identify(token, true).status());
					}));
				}
				start.countDown();
				int won = 0;
				for (Future<Integer> result : results) {
					int status = result.get().intValue();
					if (status == 200) {
						won++;
					} else {
						assertEquals(HttpServletResponse.SC_UNAUTHORIZED, status);
					}
				}
				assertEquals("Exactly one first open wins.", 1, won);
			} finally {
				pool.shutdownNow();
			}
		}
	}

	public void testTheNextPersonOnTheFamilyTabletIsTheNextPerson() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA), phone("Klaus", KLAUS));
		String petras = credential(tokenOf(created, "Tante Petra"));
		String klaus = tokenOf(created, "Klaus");

		FakeResponse tablet = getAs("/", "json", klaus, petras);
		assertEquals("Klaus's unopened link identifies Klaus, not Petra.", HttpServletResponse.SC_UNAUTHORIZED,
			tablet.status());
		assertTrue(identifyRequired(tablet).isFirstOpen());
		FakeResponse opened = identify(klaus, petras, true);
		assertEquals(200, opened.status());
		assertEquals(contactOf(created, "Klaus"),
			ContactCredential.readContactCredential(reader(body(opened))).getContact().getId());
	}

	// --- Recognised across the space. ---

	public void testACredentialOpensEveryPersonalLinkThatAdmitsTheContact() throws Exception {
		ShareLinkCreated a = created(ZOO, email("Tante Petra", PETRA));
		String contact = contactOf(a, "Tante Petra");
		String credential = credential(tokenOf(a, "Tante Petra"));

		ShareLinkCreated b = created("/" + SharingFixture.PUBLIC + "/", known(contact));
		assertEquals("B's own token opens for her.", 200,
			getAs("/", "json", b.getToken(), credential).status());
		assertEquals("So does her own link of B, which it opens on the way.", 200,
			getAs("/", "json", tokenOf(b, "Tante Petra"), credential).status());
		assertFalse(links(shares("/" + SharingFixture.PUBLIC + "/", SharingFixture.ALICE)).getLinks().get(0)
			.getRecipients().get(0).getOpened().isEmpty());

		ShareLinkCreated open = created("/" + SharingFixture.PRIVATE + "/");
		assertTrue("An open personal link has no recipients.", open.getRecipients().isEmpty());
		assertEquals(200, getAs("/", "json", open.getToken(), credential).status());

		ShareLinkCreated c = created("/" + SharingFixture.CAROLS_ALBUM + "/", phone("Klaus", KLAUS));
		FakeResponse refused = getAs("/", "json", c.getToken(), credential);
		assertEquals(refused.body(), HttpServletResponse.SC_FORBIDDEN, refused.status());
		identifyRequired(refused);
		assertEquals(AuthService.NOT_A_RECIPIENT, errorMessage(refused));
	}

	public void testACredentialIsUnknownInAnotherSpace() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA));
		String credential = credential(tokenOf(created, "Tante Petra"));

		Path other = Files.createTempDirectory("valbum-other-space");
		try {
			SharingFixture.create(other);
			AuthService otherAuth = new AuthService(AuthMode.WRITES, other);
			ShareStore.IssuedPersonal there = otherAuth.getShares().createPersonal("alice", SharingFixture.ZOO, "",
				"", 0, -2, java.util.Arrays.asList("view"), "alice", new ArrayList<>());
			ImageServlet servlet = new ImageServlet(other.toFile(), otherAuth);
			servlet.init();
			try {
				FakeResponse response = new FakeResponse();
				java.util.Map<String, String> parameters = new java.util.HashMap<>();
				parameters.put("type", "json");
				servlet.doGet(TestImageServletPut.request("/", null, new byte[0],
					headers(there.getIssued().getToken(), credential), parameters), response.response());
				assertEquals(response.body(), HttpServletResponse.SC_UNAUTHORIZED, response.status());
				identifyRequired(response);
				assertEquals(AuthService.IDENTIFY_PERSONAL, errorMessage(response));
			} finally {
				servlet.destroy();
			}
		} finally {
			deleteTree(other);
		}
	}

	// --- Attribution. ---

	public void testAnUploadIsTheContactsAndKeepsTheirNameAfterARename() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA));
		String contact = contactOf(created, "Tante Petra");
		String credential = credential(tokenOf(created, "Tante Petra"));

		FakeResponse uploaded = uploadAs("/", tokenOf(created, "Tante Petra"), credential, "petras.jpg",
			photo("petras"));
		assertEquals(uploaded.body(), 200, uploaded.status());

		ImagePart image = image(get(ZOO, "json", SharingFixture.ALICE), "petras.jpg");
		assertEquals("contact:" + contact, image.getContributor());
		assertEquals("Tante Petra", image.getContributorLabel());

		restartServer();
		new ContactStore(_base).rename(contact, "Petra Müller");
		image = image(get(ZOO, "json", SharingFixture.ALICE), "petras.jpg");
		assertEquals("The name was copied at the upload.", "Tante Petra", image.getContributorLabel());
		String hashes = new String(Files.readAllBytes(_base.resolve(SharingFixture.ZOO).resolve(".hashes.json")),
			StandardCharsets.UTF_8);
		assertTrue(hashes, hashes.contains("\"contact:" + contact + "\""));
	}

	// --- Sending again and shutting out. ---

	public void testResendVoidsTheOldLink() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA));
		String old = tokenOf(created, "Tante Petra");
		String contact = contactOf(created, "Tante Petra");
		credential(old);

		FakeResponse resent = postAs(ZOO, "resend",
			"{\"link\":\"" + created.getLink().getId() + "\",\"contact\":\"" + contact + "\"}", SharingFixture.ALICE,
			null);
		assertEquals(resent.body(), 200, resent.status());
		ShareLinkCreated fresh = created(resent);
		assertEquals(1, fresh.getRecipients().size());
		assertEquals("", fresh.getToken());
		String token = fresh.getRecipients().get(0).getToken();
		assertFalse(token.equals(old));

		FakeResponse voided = get("/", "json", old);
		assertEquals(HttpServletResponse.SC_GONE, voided.status());
		assertEquals(AuthService.RECIPIENT_LINK_REPLACED, errorMessage(voided));
		assertEquals(HttpServletResponse.SC_GONE, identify(old, true).status());
		assertTrue("The fresh link identifies once more.", identifyRequired(get("/", "json", token)).isFirstOpen());
		assertEquals(200, identify(token, true).status());
	}

	public void testResendNeedsTheLinksOwnerAndARecipient() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA));
		String body = "{\"link\":\"" + created.getLink().getId() + "\",\"contact\":\""
			+ contactOf(created, "Tante Petra") + "\"}";

		assertEquals("A link of somebody else's is none.", HttpServletResponse.SC_NOT_FOUND,
			postAs(ZOO, "resend", body, SharingFixture.BOB, null).status());
		assertEquals(HttpServletResponse.SC_FORBIDDEN, postAs(ZOO, "resend", body, SharingFixture.DAVE, null).status());
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, postAs(ZOO, "resend", body, null, null).status());
		assertEquals(HttpServletResponse.SC_NOT_FOUND, postAs(ZOO, "resend",
			"{\"link\":\"" + created.getLink().getId() + "\",\"contact\":\"nobody\"}", SharingFixture.ALICE, null)
				.status());
	}

	public void testShuttingOutOfALinkEndsTheCredentialThereAtOnce() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA));
		String token = tokenOf(created, "Tante Petra");
		String contact = contactOf(created, "Tante Petra");
		String credential = credential(token);
		assertEquals(200, getAs("/", "json", token, credential).status());

		FakeResponse shut = postAs(ZOO, "shut-out", "{\"link\":\"" + created.getLink().getId() + "\",\"contact\":\""
			+ contact + "\",\"shutOut\":true}", SharingFixture.ALICE, null);
		assertEquals(shut.body(), 200, shut.status());
		assertFalse(links(shut).getLinks().get(0).getRecipients().get(0).getShutOut().isEmpty());

		FakeResponse refused = getAs("/", "json", token, credential);
		assertEquals(HttpServletResponse.SC_GONE, refused.status());
		assertEquals(AuthService.CONTACT_SHUT_OUT, errorMessage(refused));
		assertEquals(HttpServletResponse.SC_GONE, get("/", "json", token).status());
		assertTrue("The sessions opened through the link ended.",
			new ContactStore(_base).get(contact).getSessions().isEmpty());

		postAs(ZOO, "shut-out", "{\"link\":\"" + created.getLink().getId() + "\",\"contact\":\"" + contact
			+ "\",\"shutOut\":false}", SharingFixture.ALICE, null);
		assertEquals("Let in again, she is asked who she is.", HttpServletResponse.SC_UNAUTHORIZED,
			get("/", "json", token).status());
	}

	public void testBlockingAContactEndsEveryCredentialAtOnce() throws Exception {
		ShareLinkCreated a = created(ZOO, email("Tante Petra", PETRA));
		String contact = contactOf(a, "Tante Petra");
		String credential = credential(tokenOf(a, "Tante Petra"));
		ShareLinkCreated open = created("/" + SharingFixture.PUBLIC + "/");
		assertEquals(200, getAs("/", "json", open.getToken(), credential).status());

		FakeResponse blocked = postAs("/", "block-contact", "{\"contact\":\"" + contact + "\",\"shutOut\":true}",
			SharingFixture.ALICE, null);
		assertEquals(blocked.body(), 200, blocked.status());

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, getAs("/", "json", open.getToken(), credential).status());
		assertEquals(HttpServletResponse.SC_GONE, getAs("/", "json", tokenOf(a, "Tante Petra"), credential).status());
		assertTrue(new ContactStore(_base).get(contact).getSessions().isEmpty());
		FakeResponse again = sharePersonal(ZOO, SharingFixture.ALICE, email("Petra", "petra@gmx.de"));
		assertEquals("A blocked contact is no new recipient.", HttpServletResponse.SC_BAD_REQUEST, again.status());
		assertEquals(HttpServletResponse.SC_FORBIDDEN, postAs("/", "block-contact",
			"{\"contact\":\"" + contact + "\",\"shutOut\":false}", SharingFixture.DAVE, null).status());
	}

	// --- A credential is never a member. ---

	public void testACredentialIsNoMembersBearer() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA));
		String token = tokenOf(created, "Tante Petra");
		String credential = credential(token);

		FakeResponse bearer = get("/", "json", credential);
		assertEquals("A credential as the bearer is a token nobody issued.", HttpServletResponse.SC_UNAUTHORIZED,
			bearer.status());
		assertEquals(AuthService.TOKEN_REFUSED, errorMessage(bearer));
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, getAs("/", "contacts", null, credential).status());

		for (String type : new String[] { "users", "devices", "invitations", "contacts", "people" }) {
			FakeResponse response = getAs("/", type, token, credential);
			assertTrue(type + ": " + response.status(), response.status() == 403 || response.status() == 401);
			assertTrue(type, Resource.readResource(reader(response.body())) instanceof ErrorInfo);
		}
		for (String action : new String[] { "invite", "device-code", "set-permission", "share", "block-contact",
			"refresh-cache" }) {
			FakeResponse response = postAs("/", action, "{}", token, credential);
			assertTrue(action + ": " + response.status() + " " + response.body(),
				response.status() == 403 || response.status() == 401);
		}
	}

	public void testEveryRefusalSpeaks() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA));
		String token = tokenOf(created, "Tante Petra");
		List<FakeResponse> refusals = new ArrayList<>();
		refusals.add(get("/", "json", token));
		refusals.add(get("/", "auth", created.getToken()));
		credential(token);
		refusals.add(get("/public.jpg", "tn", token));
		refusals.add(identify(token, true));
		refusals.add(identify(null, true));
		assertEquals(HttpServletResponse.SC_OK, unshare(ZOO, SharingFixture.ALICE, created.getLink().getId()).status());
		refusals.add(get("/", "json", token));
		for (FakeResponse refusal : refusals) {
			assertTrue(refusal.status() + "", refusal.status() >= 400);
			Resource resource = Resource.readResource(reader(refusal.body()));
			assertTrue(refusal.body(), resource instanceof ErrorInfo);
			assertFalse(refusal.body(), ((ErrorInfo) resource).getMessage().isEmpty());
		}
		assertEquals(AuthService.LINK_REVOKED, errorMessage(refusals.get(refusals.size() - 1)));
	}

	// --- Helpers. ---

	private static ImagePart image(FakeResponse response, String name) throws Exception {
		for (de.haumacher.imageServer.shared.model.AlbumPart part : album(response).getParts()) {
			if (part instanceof ImagePart && ((ImagePart) part).getName().equals(name)) {
				return (ImagePart) part;
			}
		}
		fail("No image '" + name + "' in " + response.body());
		return null;
	}

	static void deleteTree(Path root) throws Exception {
		try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
			walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
		}
	}
}
