/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.ContactStore;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.auth.ShareStore;
import de.haumacher.imageServer.mail.EmailProofs;
import de.haumacher.imageServer.mail.TestEmailProofs.CapturingMailer;
import de.haumacher.imageServer.mail.TestEmailProofs.TestClock;
import de.haumacher.imageServer.oidc.MockOidcIssuer;
import de.haumacher.imageServer.oidc.OidcLogins;
import de.haumacher.imageServer.shared.model.AuthInfo;
import de.haumacher.imageServer.shared.model.Contact;
import de.haumacher.imageServer.shared.model.ContactCredential;
import de.haumacher.imageServer.shared.model.IdentifyRequired;
import de.haumacher.imageServer.shared.model.OidcStarted;
import de.haumacher.imageServer.shared.model.OverviewLink;
import de.haumacher.imageServer.shared.model.RightName;
import de.haumacher.imageServer.shared.model.ShareLink;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import de.haumacher.imageServer.shared.model.SharedAlbum;
import de.haumacher.imageServer.shared.model.SharedWithYou;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test case for the overview of everything shared with a contact, see issue #267.
 *
 * <p>
 * The overview is asked with the contact credential alone, beside <code>X-VAlbum-Shared: all</code>;
 * each of its albums with the credential beside <code>X-VAlbum-Shared: &lt;link id&gt;</code>, and is
 * then exactly that link's view. A contact's own overview link is a token of its own, which
 * identifies them like a recipient's own link.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestSharedOverview extends PersonalLinkTestCase {

	private static final String OIDC_URL = "https://photos.example.org/valbum";

	/** Alice's address, for a member's proof at the overview. */
	private static final String ALICES = "alice@web.de";

	private CapturingMailer _mailer;

	private TestClock _clock;

	private EmailProofs _proofs;

	private MockOidcIssuer _issuer;

	private OidcLogins _oidc;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_mailer = new CapturingMailer();
		_clock = new TestClock();
		_proofs = new EmailProofs(_mailer, _clock, Runnable::run);
		_issuer = new MockOidcIssuer();
		_oidc = new OidcLogins(OIDC_URL, List.of(_issuer.provider("Google")), _clock);
	}

	@Override
	protected void tearDown() throws Exception {
		_issuer.close();
		super.tearDown();
	}

	@Override
	protected ImageServlet servlet() throws Exception {
		ImageServlet result = super.servlet();
		result.setEmailProofs(_proofs);
		result.setOidcLogins(_oidc);
		return result;
	}

	// --- Which links. ---

	public void testExactlyTheAddressedAndTheOpenedOpenLinks() throws Exception {
		ShareLinkCreated addressed = created(ZOO, email("Tante Petra", PETRA));
		String petra = contactOf(addressed, "Tante Petra");
		String credential = credential(tokenOf(addressed, "Tante Petra"));

		ShareLinkCreated opened = created("/" + SharingFixture.PUBLIC + "/");
		assertEquals("She opens the open link with her credential.", 200,
			getAs("/", "json", opened.getToken(), credential).status());
		ShareLinkCreated unopened = created("/" + SharingFixture.PRIVATE + "/");

		ShareLinkCreated shut = created("/" + SharingFixture.YEAR + "/", known(petra));
		FakeResponse shutOut = postAs("/" + SharingFixture.YEAR + "/", "shut-out", "{\"link\":\""
			+ shut.getLink().getId() + "\",\"contact\":\"" + petra + "\",\"shutOut\":true}", SharingFixture.ALICE, null);
		assertEquals(shutOut.body(), 200, shutOut.status());

		String expired = new ShareStore(_base).createPersonal("alice", SharingFixture.CAROLS_ALBUM, "Old",
			Instant.now().minusSeconds(60).toString(), 0, -2, List.of(Rights.VIEW), "alice", List.of(petra))
			.getIssued().getLink().getId();
		restartServer();

		SharedWithYou overview = overview(credential);
		assertEquals(Arrays.asList(opened.getLink().getId(), addressed.getLink().getId()), ids(overview));
		assertEquals(petra, overview.getContact().getId());
		assertFalse(ids(overview).contains(unopened.getLink().getId()));
		assertFalse(ids(overview).contains(shut.getLink().getId()));
		assertFalse(ids(overview).contains(expired));

		SharedAlbum zoo = album(overview, addressed.getLink().getId());
		assertEquals("Zoo", zoo.getFolder().getTitle());
		assertEquals("alice", zoo.getSharedBy());
		assertEquals(List.of(Rights.VIEW, Rights.DOWNLOAD, Rights.CONTRIBUTE), names(zoo.getRights()));
		assertNotNull("The album is shown with its picture.", zoo.getFolder().getIndexPicture());

		// Each excluded link says why when opened through the overview anyway.
		assertEquals(AuthService.SHARED_NOT_YOURS, errorMessage(sharedGet("/", "json", unopened.getLink().getId(),
			credential)));
		assertEquals(AuthService.CONTACT_SHUT_OUT, errorMessage(sharedGet("/", "json", shut.getLink().getId(),
			credential)));
		assertEquals(AuthService.LINK_EXPIRED, errorMessage(sharedGet("/", "json", expired, credential)));
	}

	public void testTheCoverIsTheLinksOwn() throws Exception {
		// A link showing only the members' photos above public: the cover is one the link shows.
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA));
		String credential = credential(tokenOf(created, "Tante Petra"));
		SharedAlbum zoo = album(overview(credential), created.getLink().getId());
		String image = zoo.getFolder().getIndexPicture().getImage();
		FakeResponse thumbnail = sharedGet("/" + image, "tn", created.getLink().getId(), credential);
		assertEquals("The cover is one the link itself serves.", 200, thumbnail.status());
		assertFalse("A public link never shows the private picture.", image.equals("private.jpg"));
	}

	// --- Each album is exactly its link. ---

	public void testNestedLinksStayTwoWithTheirOwnRights() throws Exception {
		ShareLinkCreated outer = created("/" + SharingFixture.YEAR + "/", email("Tante Petra", PETRA));
		String petra = contactOf(outer, "Tante Petra");
		String credential = credential(tokenOf(outer, "Tante Petra"));
		// The outer link looks only; the inner one may also download and contribute.
		FakeResponse viewOnly = share("/" + SharingFixture.YEAR + "/", SharingFixture.ALICE, "{\"label\":\"Year\","
			+ "\"type\":\"PERSONAL\",\"maxPrivacy\":0,\"minRating\":-2,\"rights\":[{\"name\":\"view\"}],"
			+ "\"recipients\":[" + known(petra) + "]}");
		assertEquals(viewOnly.body(), 200, viewOnly.status());
		String year = created(viewOnly).getLink().getId();
		ShareLinkCreated inner = created(ZOO, known(petra));
		String zoo = inner.getLink().getId();

		SharedWithYou overview = overview(credential);
		assertTrue(ids(overview).containsAll(List.of(year, zoo)));
		assertEquals(List.of(Rights.VIEW), names(album(overview, year).getRights()));
		assertEquals(List.of(Rights.VIEW, Rights.DOWNLOAD, Rights.CONTRIBUTE), names(album(overview, zoo).getRights()));

		// The year link: its root is the year, and it looks but never downloads or contributes.
		FakeResponse yearRoot = sharedGet("/", "json", year, credential);
		assertEquals(yearRoot.body(), 200, yearRoot.status());
		assertEquals(List.of(Rights.VIEW), rightsOf(folder(yearRoot)));
		String zooInYear = "/" + SharingFixture.ZOO.substring(SharingFixture.YEAR.length() + 1) + "/";
		assertEquals(200, sharedGet(zooInYear, "json", year, credential).status());
		assertEquals(HttpServletResponse.SC_FORBIDDEN, sharedGet(zooInYear + "public.jpg", null, year, credential)
			.status());
		assertEquals(HttpServletResponse.SC_FORBIDDEN,
			sharedUpload(zooInYear, year, credential, "petra.jpg", photo("year")).status());

		// The zoo link: its root is the zoo, and it downloads and contributes there.
		FakeResponse zooRoot = sharedGet("/", "json", zoo, credential);
		assertEquals(zooRoot.body(), 200, zooRoot.status());
		assertEquals("Zoo", album(zooRoot).getTitle());
		assertEquals(200, sharedGet("/public.jpg", null, zoo, credential).status());
		FakeResponse upload = sharedUpload("/", zoo, credential, "petra.jpg", photo("zoo"));
		assertEquals(upload.body(), 200, upload.status());

		// Nothing above either folder.
		for (String id : List.of(year, zoo)) {
			assertEquals(HttpServletResponse.SC_NOT_FOUND, sharedGet("/../", "json", id, credential).status());
			FakeResponse canonical = sharedGet("/~alice/", "json", id, credential);
			assertEquals(HttpServletResponse.SC_NOT_FOUND, canonical.status());
			assertEquals(AuthService.SHARE_CONFINED, errorMessage(canonical));
		}
	}

	public void testNoTokenOfAnyLinkIsAnswered() throws Exception {
		ShareLinkCreated a = created(ZOO, email("Tante Petra", PETRA), phone("Klaus", KLAUS));
		String credential = credential(tokenOf(a, "Tante Petra"));
		ShareLinkCreated b = created("/" + SharingFixture.PUBLIC + "/", known(contactOf(a, "Tante Petra")));
		FakeResponse answer = sharedGet("/", SharedOverview.TYPE, AuthService.SHARED_ALL, credential);
		assertEquals(answer.body(), 200, answer.status());
		String shares = new String(Files.readAllBytes(new ShareStore(_base).getFile()), StandardCharsets.UTF_8);
		List<String> secrets = new ArrayList<>(List.of(a.getToken(), tokenOf(a, "Tante Petra"), tokenOf(a, "Klaus"),
			b.getToken(), tokenOf(b, "Tante Petra")));
		for (ShareStore.Link link : new ShareStore(_base).getLinks()) {
			secrets.add(link.getTokenHash());
		}
		assertTrue(shares.contains(new ShareStore(_base).getLinks().get(0).getTokenHash()));
		for (String secret : secrets) {
			assertFalse("The overview never answers a token or its hash: " + secret, answer.body().contains(secret));
		}
	}

	public void testOpeningThroughTheOverviewOpensTheLink() throws Exception {
		ShareLinkCreated a = created(ZOO, email("Tante Petra", PETRA));
		String petra = contactOf(a, "Tante Petra");
		String credential = credential(tokenOf(a, "Tante Petra"));
		ShareLinkCreated b = created("/" + SharingFixture.PUBLIC + "/", known(petra));
		String own = tokenOf(b, "Tante Petra");

		ShareLink before = links(shares("/" + SharingFixture.PUBLIC + "/", SharingFixture.ALICE)).getLinks().get(0);
		assertTrue(before.getRecipients().get(0).getOpened().isEmpty());

		assertEquals(200, sharedGet("/", "json", b.getLink().getId(), credential).status());

		ShareLink after = links(shares("/" + SharingFixture.PUBLIC + "/", SharingFixture.ALICE)).getLinks().get(0);
		assertFalse("The sharer sees it opened.", after.getRecipients().get(0).getOpened().isEmpty());
		assertFalse("And when she came.", after.getRecipients().get(0).getLastSeen().isEmpty());
		FakeResponse ownToken = get("/", "json", own);
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, ownToken.status());
		assertFalse("Her own token identifies nobody any more.", identifyRequired(ownToken).isFirstOpen());

		// An open link she opens through the overview is a visit like any other.
		ShareLinkCreated open = created("/" + SharingFixture.PRIVATE + "/");
		assertEquals(200, getAs("/", "json", open.getToken(), credential).status());
		ShareLink visited = links(shares("/" + SharingFixture.PRIVATE + "/", SharingFixture.ALICE)).getLinks().get(0);
		assertEquals(petra, visited.getVisitors().get(0).getContact());
	}

	// --- Changes while the overview is open. ---

	public void testADeletedLinkLeavesTheOverviewAndSaysWhy() throws Exception {
		// Signed in through A, the link deleted next; B still admits her.
		ShareLinkCreated a = created(ZOO, email("Tante Petra", PETRA));
		String petra = contactOf(a, "Tante Petra");
		String credential = credential(tokenOf(a, "Tante Petra"));
		ShareLinkCreated b = created("/" + SharingFixture.PUBLIC + "/", known(petra));
		String id = a.getLink().getId();
		assertEquals(List.of(b.getLink().getId(), id), ids(overview(credential)));

		assertEquals(200, unshare(ZOO, SharingFixture.ALICE, id).status());

		assertEquals("Her browser stays signed in: the space still admits her.", List.of(b.getLink().getId()),
			ids(overview(credential)));
		assertEquals(1, new ContactStore(_base).get(petra).getSessions().size());
		FakeResponse opened = sharedGet("/", "json", id, credential);
		assertEquals(HttpServletResponse.SC_GONE, opened.status());
		assertEquals(AuthService.SHARED_NOT_YOURS, errorMessage(opened));
		// Its own token is one nobody issued, as after any deletion (#217).
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, getAs("/", "json", a.getToken(), credential).status());
		assertEquals(200, sharedGet("/", "json", b.getLink().getId(), credential).status());
	}

	public void testDeletingTheOnlyLinkSignsTheContactOut() throws Exception {
		ShareLinkCreated a = created(ZOO, email("Tante Petra", PETRA));
		String petra = contactOf(a, "Tante Petra");
		String credential = credential(tokenOf(a, "Tante Petra"));
		// An open link she never opened admits everybody, and keeps nobody signed in.
		created("/" + SharingFixture.PUBLIC + "/");

		assertEquals(200, unshare(ZOO, SharingFixture.ALICE, a.getLink().getId()).status());

		assertTrue("Nothing else admits her: signed out, as #217 says.",
			new ContactStore(_base).get(petra).getSessions().isEmpty());
		FakeResponse refused = sharedGet("/", SharedOverview.TYPE, AuthService.SHARED_ALL, credential);
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, refused.status());
		assertTrue(identifyRequired(refused).isShared());
	}

	public void testAnOverviewLinkKeepsTheContactSignedIn() throws Exception {
		ShareLinkCreated a = created(ZOO, email("Tante Petra", PETRA));
		String petra = contactOf(a, "Tante Petra");
		String credential = credential(tokenOf(a, "Tante Petra"));
		overviewLink(petra, false);

		assertEquals(200, unshare(ZOO, SharingFixture.ALICE, a.getLink().getId()).status());

		assertEquals("Her own overview link still admits her.", List.of(), ids(overview(credential)));
	}

	public void testAnExpiringLinkLeavesTheOverviewAndSaysWhy() throws Exception {
		ShareLinkCreated a = created(ZOO, email("Tante Petra", PETRA));
		String petra = contactOf(a, "Tante Petra");
		String credential = credential(tokenOf(a, "Tante Petra"));
		String id = new ShareStore(_base).createPersonal("alice", SharingFixture.PUBLIC, "Soon",
			Instant.now().plusMillis(1500).toString(), 0, -2, List.of(Rights.VIEW), "alice", List.of(petra))
			.getIssued().getLink().getId();
		restartServer();
		assertTrue(ids(overview(credential)).contains(id));
		assertEquals(200, sharedGet("/", "json", id, credential).status());

		Thread.sleep(1600);

		assertFalse(ids(overview(credential)).contains(id));
		FakeResponse opened = sharedGet("/", "json", id, credential);
		assertEquals(HttpServletResponse.SC_GONE, opened.status());
		assertEquals(AuthService.LINK_EXPIRED, errorMessage(opened));
	}

	// --- Who may ask. ---

	public void testWithoutACredentialTheSignInOffer() throws Exception {
		created(ZOO, email("Tante Petra", PETRA));
		for (FakeResponse refusal : List.of(sharedGet("/", SharedOverview.TYPE, AuthService.SHARED_ALL, null),
			getAs("/", SharedOverview.TYPE, null, null), sharedGet("/", "json", "someLink", null),
			sharedGet("/", SharedOverview.TYPE, AuthService.SHARED_ALL, "not-a-credential"))) {
			assertEquals(refusal.body(), HttpServletResponse.SC_UNAUTHORIZED, refusal.status());
			IdentifyRequired identify = identifyRequired(refusal);
			assertTrue(identify.isShared());
			assertNull("It names nobody.", identify.getContact());
			assertEquals("", identify.getSharedBy());
			assertEquals(List.of(EmailProofs.METHOD, "oidc:google"),
				identify.getMethods().stream().map(method -> method.getName()).toList());
			assertEquals(AuthService.IDENTIFY_SHARED, errorMessage(refusal));
		}
	}

	public void testAContactsProofAtTheOverviewRecognisesThem() throws Exception {
		ShareLinkCreated a = created(ZOO, email("Tante Petra", PETRA));
		String petra = contactOf(a, "Tante Petra");
		String contacts = contactsFile();

		FakeResponse stranger = sharedPost("/", "prove-email", "{\"address\":\"ahmed@web.de\"}", null);
		FakeResponse sent = sharedPost("/", "prove-email", "{\"address\":\"" + PETRA + "\"}", null);
		assertEquals(sent.body(), 200, sent.status());
		assertEquals(200, stranger.status());
		assertEquals("Mailed to Petra alone.", 1, _mailer.sent().size());
		assertTrue(_mailer.sent().get(0)[2].contains("everything shared with you"));

		FakeResponse verified = sharedPost("/", "verify-email", "{\"address\":\"" + PETRA + "\",\"code\":\""
			+ _mailer.lastCode("petra@gmx.de") + "\",\"remember\":true}", null);
		assertEquals(verified.body(), 200, verified.status());
		ContactCredential answer = ContactCredential.readContactCredential(reader(body(verified)));
		assertEquals(petra, answer.getContact().getId());
		assertEquals(List.of(a.getLink().getId()), ids(overview(answer.getCredential())));
		assertEquals("The session was opened through no link.", "",
			new ContactStore(_base).get(petra).getSessions().get(0).getLink());
		assertTrue("Nobody new was entered.", contactsFile().contains(petra));
		assertEquals(1, new ContactStore(_base).getContacts().size());
		assertFalse(contacts.isEmpty());
	}

	public void testAMembersProofAtTheOverviewSignsTheMemberIn() throws Exception {
		created(ZOO, email("Tante Petra", PETRA));
		FakeResponse added = postAs("/", "prove-email", "{\"address\":\"" + ALICES + "\"}", SharingFixture.ALICE, null);
		assertEquals(added.body(), 200, added.status());
		assertEquals(200, postAs("/", "verify-email", "{\"address\":\"" + ALICES + "\",\"code\":\""
			+ _mailer.lastCode(ALICES) + "\"}", SharingFixture.ALICE, null).status());
		_clock.advance(java.time.Duration.ofMinutes(1));
		String before = contactsFile();

		assertEquals(200, sharedPost("/", "prove-email", "{\"address\":\"" + ALICES + "\"}", null).status());
		FakeResponse verified = sharedPost("/", "verify-email", "{\"address\":\"" + ALICES + "\",\"code\":\""
			+ _mailer.lastCode(ALICES) + "\",\"deviceName\":\"Tablet\"}", null);
		assertEquals(verified.body(), 200, verified.status());
		ContactCredential answer = ContactCredential.readContactCredential(reader(body(verified)));
		assertEquals("", answer.getCredential());
		assertEquals("alice", answer.getMember().getUserName());
		assertEquals("alice", auth(get("/", "auth", answer.getMember().getToken())).getUserName());
		assertEquals("Nobody was entered as a contact.", before, contactsFile());

		// The member's device at the overview is told where they belong.
		FakeResponse member = getAs("/", SharedOverview.TYPE, answer.getMember().getToken(), null);
		assertEquals(HttpServletResponse.SC_CONFLICT, member.status());
		assertEquals(AuthService.SHARED_MEMBER, errorMessage(member));
	}

	public void testASignInWithAProviderReturnsToTheOverview() throws Exception {
		created(ZOO, email("Tante Petra", PETRA));
		FakeResponse start = sharedPost("/", "oidc-start", "{\"provider\":\"google\"}", null);
		assertEquals(start.body(), 200, start.status());
		assertEquals(OIDC_URL + "/shared/", OidcStarted.readOidcStarted(reader(body(start))).getReturnUrl());
	}

	public void testALinkTokenIsRefusedAtTheOverview() throws Exception {
		String anonymous = zooToken();
		FakeResponse refused = getAs("/", SharedOverview.TYPE, anonymous, null);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, refused.status());
		assertEquals(AuthService.SHARED_LINK_REFUSED, errorMessage(refused));

		FakeResponse member = getAs("/", SharedOverview.TYPE, SharingFixture.ALICE, null);
		assertEquals(HttpServletResponse.SC_CONFLICT, member.status());
		assertEquals(AuthService.SHARED_MEMBER, errorMessage(member));
	}

	public void testTheOverviewOpensNoAlbumItself() throws Exception {
		ShareLinkCreated a = created(ZOO, email("Tante Petra", PETRA));
		String credential = credential(tokenOf(a, "Tante Petra"));
		FakeResponse refused = sharedGet("/", "json", AuthService.SHARED_ALL, credential);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, refused.status());
		assertEquals(AuthService.OVERVIEW_ONLY, errorMessage(refused));
		FakeResponse upload = sharedUpload("/", AuthService.SHARED_ALL, credential, "x.jpg", photo("x"));
		assertTrue(upload.body(), upload.status() >= 400);
		FakeResponse action = sharedPost("/", "delete", "{}", credential);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, action.status());
		// Its own sessions it may end.
		assertEquals(200, sharedPost("/", "end-other-sessions", "{}", credential).status());
	}

	// --- A contact's own overview link. ---

	public void testAnOverviewLinkIdentifiesAtItsFirstOpenAndLandsOnTheOverview() throws Exception {
		ShareLinkCreated a = created(ZOO, email("Tante Petra", PETRA), phone("Klaus", KLAUS));
		String petra = contactOf(a, "Tante Petra");
		OverviewLink issued = overviewLink(petra, false);
		assertTrue(issued.getUrl(), issued.getUrl().contains("/s/"));
		String token = tokenIn(issued);
		assertFalse(issued.getContact().getOverviewIssued().isEmpty());
		assertTrue(issued.getContact().getOverviewOpened().isEmpty());

		FakeResponse first = get("/", "auth", token);
		assertEquals(first.body(), HttpServletResponse.SC_UNAUTHORIZED, first.status());
		IdentifyRequired identify = identifyRequired(first);
		assertTrue(identify.isFirstOpen());
		assertTrue(identify.isShared());
		assertEquals(petra, identify.getContact().getId());

		FakeResponse identified = identify(token, true);
		assertEquals(identified.body(), 200, identified.status());
		String credential = ContactCredential.readContactCredential(reader(body(identified))).getCredential();

		AuthInfo info = auth(getAs("/", "auth", token, credential));
		assertTrue("The app leaves for the overview.", info.isOverview());
		assertNull(info.getShare());
		assertEquals(List.of(a.getLink().getId()), ids(overview(credential)));
		assertFalse(contact(petra).getOverviewOpened().isEmpty());

		// The token opens no album itself.
		FakeResponse album = getAs("/", "json", token, credential);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, album.status());
		assertEquals(AuthService.OVERVIEW_ONLY, errorMessage(album));

		// Opened, it asks who this is; somebody else's credential is refused.
		FakeResponse again = get("/", "auth", token);
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, again.status());
		assertFalse(identifyRequired(again).isFirstOpen());
		assertEquals(AuthService.IDENTIFY_REQUIRED, errorMessage(again));
		String klaus = credential(tokenOf(a, "Klaus"));
		FakeResponse other = getAs("/", "auth", token, klaus);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, other.status());
		assertEquals(AuthService.OVERVIEW_NOT_YOURS, errorMessage(other));

		// Neither the answer nor the overview reveals a link's token.
		for (String secret : List.of(a.getToken(), tokenOf(a, "Tante Petra"), tokenOf(a, "Klaus"))) {
			assertFalse(issued.toString().contains(secret));
			assertFalse(sharedGet("/", SharedOverview.TYPE, AuthService.SHARED_ALL, credential).body().contains(secret));
		}
		assertFalse("The token is no link's.", new ShareStore(_base).getFile().toFile().exists()
			&& new String(Files.readAllBytes(new ShareStore(_base).getFile()), StandardCharsets.UTF_8)
				.contains(de.haumacher.imageServer.auth.UserStore.hash(token)));
		assertFalse("Stored as its hash only.", contactsFile().contains(token));
	}

	public void testAVoidedOrBlockedOverviewLinkIsGone() throws Exception {
		ShareLinkCreated a = created(ZOO, email("Tante Petra", PETRA));
		String petra = contactOf(a, "Tante Petra");
		String first = tokenIn(overviewLink(petra, false));
		String second = tokenIn(overviewLink(petra, false));

		FakeResponse replaced = get("/", "auth", first);
		assertEquals(HttpServletResponse.SC_GONE, replaced.status());
		assertEquals(AuthService.RECIPIENT_LINK_REPLACED, errorMessage(replaced));
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, get("/", "auth", second).status());

		OverviewLink withdrawn = overviewLink(petra, true);
		assertEquals("", withdrawn.getUrl());
		assertEquals("", withdrawn.getContact().getOverviewIssued());
		assertEquals(HttpServletResponse.SC_GONE, get("/", "auth", second).status());

		String third = tokenIn(overviewLink(petra, false));
		assertEquals(200, postAs("/", "block-contact", "{\"contact\":\"" + petra + "\",\"shutOut\":true}",
			SharingFixture.ALICE, null).status());
		FakeResponse blocked = get("/", "auth", third);
		assertEquals(HttpServletResponse.SC_GONE, blocked.status());
		assertEquals(AuthService.CONTACT_SHUT_OUT, errorMessage(blocked));
	}

	public void testOnlyAMemberWhoManagesContactsIssuesOverviewLinks() throws Exception {
		ShareLinkCreated a = created(ZOO, email("Tante Petra", PETRA));
		String petra = contactOf(a, "Tante Petra");
		String credential = credential(tokenOf(a, "Tante Petra"));
		String body = "{\"contact\":\"" + petra + "\"}";
		assertEquals(HttpServletResponse.SC_FORBIDDEN,
			postAs("/", SharedOverview.LINK_ACTION, body, tokenOf(a, "Tante Petra"), credential).status());
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, postAs("/", SharedOverview.LINK_ACTION, body, null, null)
			.status());
		FakeResponse unknown = postAs("/", SharedOverview.LINK_ACTION, "{\"contact\":\"nobody\"}", SharingFixture.ALICE,
			null);
		assertEquals(HttpServletResponse.SC_NOT_FOUND, unknown.status());
		assertEquals(ContactStore.unknownContact("nobody"), errorMessage(unknown));
	}

	// --- Helpers. ---

	private SharedWithYou overview(String credential) throws Exception {
		FakeResponse response = sharedGet("/", SharedOverview.TYPE, AuthService.SHARED_ALL, credential);
		assertEquals(response.body(), 200, response.status());
		return SharedWithYou.readSharedWithYou(reader(body(response)));
	}

	private static List<String> ids(SharedWithYou overview) {
		return overview.getAlbums().stream().map(SharedAlbum::getLink).toList();
	}

	private static SharedAlbum album(SharedWithYou overview, String id) {
		for (SharedAlbum album : overview.getAlbums()) {
			if (album.getLink().equals(id)) {
				return album;
			}
		}
		fail("No album of the link " + id);
		return null;
	}

	private static List<String> names(List<RightName> rights) {
		return rights.stream().map(RightName::getName).toList();
	}

	private OverviewLink overviewLink(String contact, boolean withdraw) throws Exception {
		FakeResponse response = postAs("/", SharedOverview.LINK_ACTION, "{\"contact\":\"" + contact
			+ "\",\"withdraw\":" + withdraw + "}", SharingFixture.ALICE, null);
		assertEquals(response.body(), 200, response.status());
		return OverviewLink.readOverviewLink(reader(body(response)));
	}

	private static String tokenIn(OverviewLink link) {
		String url = link.getUrl();
		return url.substring(url.lastIndexOf("/s/") + "/s/".length(), url.length() - 1);
	}

	private Contact contact(String id) throws Exception {
		for (Contact contact : contactList(get("/", "contacts", SharingFixture.ALICE)).getContacts()) {
			if (contact.getId().equals(id)) {
				return contact;
			}
		}
		fail("No contact " + id);
		return null;
	}

	private String contactsFile() throws Exception {
		java.nio.file.Path file = new ContactStore(_base).getFile();
		return Files.exists(file) ? new String(Files.readAllBytes(file), StandardCharsets.UTF_8) : "";
	}

	private static Map<String, String> sharedHeaders(String value, String credential) {
		Map<String, String> headers = new HashMap<>();
		headers.put(AuthService.SHARED_HEADER, value);
		if (credential != null) {
			headers.put(AuthService.CONTACT_HEADER, credential);
		}
		return headers;
	}

	private FakeResponse sharedGet(String pathInfo, String type, String value, String credential) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		if (type != null) {
			parameters.put("type", type);
		}
		FakeResponse response = new FakeResponse();
		servlet().doGet(TestImageServletPut.request(pathInfo, null, new byte[0], sharedHeaders(value, credential),
			parameters), response.response());
		return response;
	}

	private FakeResponse sharedPost(String pathInfo, String action, String body, String credential) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", action);
		FakeResponse response = new FakeResponse();
		servlet().doPost(TestImageServletPut.request(pathInfo, "application/json", body.getBytes(StandardCharsets.UTF_8),
			sharedHeaders(AuthService.SHARED_ALL, credential), parameters), response.response());
		return response;
	}

	private FakeResponse sharedUpload(String pathInfo, String value, String credential, String fileName,
			byte[] contents) throws Exception {
		Map<String, String> headers = sharedHeaders(value, credential);
		headers.put("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
		LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
		files.put(fileName, contents);
		FakeResponse response = new FakeResponse();
		servlet().doPut(TestImageServletPut.request(pathInfo, "multipart/form-data; boundary=" + BOUNDARY,
			multipart(files), headers, new HashMap<>()), response.response());
		return response;
	}
}
