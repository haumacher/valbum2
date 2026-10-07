/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.Totp;
import de.haumacher.imageServer.auth.TotpSignIns;
import de.haumacher.imageServer.mail.TestEmailProofs.TestClock;
import de.haumacher.imageServer.shared.model.Contact;
import de.haumacher.imageServer.shared.model.ContactCredential;
import de.haumacher.imageServer.shared.model.ContactSignIns;
import de.haumacher.imageServer.shared.model.IdentifyRequired;
import de.haumacher.imageServer.shared.model.ProofMethod;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import de.haumacher.imageServer.shared.model.TotpSetup;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.List;

/**
 * Test case for an authenticator app on personal share links, see issue #208: set up by a contact
 * in their session, offered only where somebody set one up, signing in on another browser, removed
 * by the contact or by a member.
 */
@SuppressWarnings("javadoc")
public class TestTotpLinks extends PersonalLinkTestCase {

	private TestClock _clock;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_clock = new TestClock();
	}

	@Override
	protected ImageServlet servlet() throws Exception {
		ImageServlet result = super.servlet();
		result.getAuth().getTotp().setClock(_clock);
		return result;
	}

	// --- Setting up. ---

	public void testAContactSetsUpAnAuthenticatorWithOneCode() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA));
		String token = tokenOf(created, "Tante Petra");
		String credential = credential(token);

		assertEquals("", auth(getAs("/", "auth", token, credential)).getShare().getSignIns().getAuthenticator());

		TotpSetup setup = setUp(token, credential);
		assertEquals(32, setup.getSecret().length());
		assertEquals("VAlbum", setup.getIssuer());
		assertEquals("Tante Petra", setup.getAccount());
		assertEquals("otpauth://totp/VAlbum:Tante%20Petra?secret=" + setup.getSecret()
			+ "&issuer=VAlbum&algorithm=SHA1&digits=6&period=30", setup.getUri());

		// Not active before a code of it was confirmed.
		assertEquals("", auth(getAs("/", "auth", token, credential)).getShare().getSignIns().getAuthenticator());
		assertFalse(methods(laterOpen(token)).contains(TotpSignIns.METHOD));

		FakeResponse wrong = postAs("/", "totp-confirm", code("000000"), token, credential);
		if (wrong.status() == 200) {
			// One in a million: the wrong code was right.
			return;
		}
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, wrong.status());
		assertEquals(TotpSignIns.CODE_WRONG, errorMessage(wrong));

		FakeResponse confirmed = postAs("/", "totp-confirm", code(now(setup)), token, credential);
		assertEquals(confirmed.body(), 200, confirmed.status());
		ContactSignIns signIns = ContactSignIns.readContactSignIns(reader(body(confirmed)));
		assertEquals(_clock.instant().toString(), signIns.getAuthenticator());
		assertEquals(signIns.getAuthenticator(),
			auth(getAs("/", "auth", token, credential)).getShare().getSignIns().getAuthenticator());

		// The members see that she has one, and never the secret.
		FakeResponse contacts = get("/", "contacts", SharingFixture.ALICE);
		assertTrue(contacts.body().contains(signIns.getAuthenticator()));
		assertFalse(contacts.body().contains(setup.getSecret()));
		assertEquals(signIns.getAuthenticator(), contact(contactOf(created, "Tante Petra")).getAuthenticator());
	}

	public void testOnlyARecognisedContactSetsOneUp() throws Exception {
		// A signed-in member sets up their own since issue #233, see TestMemberSignIns.
		FakeResponse link = post("/", "{}", zooToken("view"), java.util.Map.of("action", "totp-setup"));
		assertEquals(HttpServletResponse.SC_FORBIDDEN, link.status());
		assertEquals(SignInActions.SIGN_INS_REFUSED, errorMessage(link));

		FakeResponse nobody = post("/", "{}", null, java.util.Map.of("action", "totp-setup"));
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, nobody.status());

		// A personal link that does not know who asks answers its own refusal.
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA));
		FakeResponse unknown = postAs("/", "totp-setup", "{}", tokenOf(created, "Tante Petra"), null);
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, unknown.status());
		assertTrue(identifyRequired(unknown).isFirstOpen());
	}

	// --- Signing in on a recipient's own link. ---

	public void testARecipientSignsInWithACodeOnAnotherBrowser() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA), phone("Klaus", KLAUS));
		String token = tokenOf(created, "Klaus");
		String credential = credential(token);
		IdentifyRequired before = identifyRequired(laterOpen(token));
		assertTrue("Never offered to somebody who has none.", before.getMethods().isEmpty());

		TotpSetup setup = confirmedSetup(token, credential);
		IdentifyRequired after = identifyRequired(laterOpen(token));
		assertEquals(List.of(TotpSignIns.METHOD), names(after.getMethods()));

		_clock.advance(Duration.ofMinutes(1));
		FakeResponse signedIn = postAs("/", "totp-verify",
			"{\"code\":\"" + now(setup) + "\",\"remember\":false}", token, null);
		assertEquals(signedIn.body(), 200, signedIn.status());
		ContactCredential second = ContactCredential.readContactCredential(reader(body(signedIn)));
		assertEquals(contactOf(created, "Klaus"), second.getContact().getId());
		assertFalse(second.isRemember());
		assertFalse(second.getCredential().equals(credential));
		assertEquals(200, getAs("/", "json", token, second.getCredential()).status());

		// The same code again is refused, within its window.
		FakeResponse replayed = postAs("/", "totp-verify", code(now(setup)), token, null);
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, replayed.status());
		assertEquals(TotpSignIns.CODE_WRONG, errorMessage(replayed));
	}

	public void testACodeOfAnotherContactIsRefusedOnTheRecipientsLink() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA), phone("Klaus", KLAUS));
		String petra = tokenOf(created, "Tante Petra");
		String klaus = tokenOf(created, "Klaus");
		TotpSetup petras = confirmedSetup(petra, credential(petra));
		confirmedSetup(klaus, credential(klaus));
		_clock.advance(Duration.ofMinutes(1));

		FakeResponse refused = postAs("/", "totp-verify", code(now(petras)), klaus, null);
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, refused.status());
		assertEquals(TotpSignIns.CODE_WRONG, errorMessage(refused));
		assertEquals(200, postAs("/", "totp-verify", code(now(petras)), petra, null).status());
	}

	public void testFiveWrongCodesLockTheRecipientsLink() throws Exception {
		ShareLinkCreated created = created(ZOO, phone("Klaus", KLAUS));
		String token = tokenOf(created, "Klaus");
		TotpSetup setup = confirmedSetup(token, credential(token));
		_clock.advance(Duration.ofMinutes(1));
		String wrong = wrong(setup);
		for (int n = 0; n < TotpSignIns.FAILURES; n++) {
			assertEquals(HttpServletResponse.SC_BAD_REQUEST, postAs("/", "totp-verify", code(wrong), token, null).status());
		}
		FakeResponse locked = postAs("/", "totp-verify", code(now(setup)), token, null);
		assertEquals(429, locked.status());
		assertEquals(TotpSignIns.LOCKED, errorMessage(locked));
		assertEquals(Long.toString(TotpSignIns.LOCK.toSeconds()), locked.header("Retry-After"));
		_clock.advance(TotpSignIns.LOCK);
		assertEquals(200, postAs("/", "totp-verify", code(now(setup)), token, null).status());
	}

	// --- The group link and an open link: a typed address, one answer. ---

	public void testTheGroupLinkAnswersEveryFailureAlike() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA), email("Onkel Hans", "hans@web.de"));
		String group = created.getToken();
		assertFalse(methods(get("/", "json", group)).contains(TotpSignIns.METHOD));

		String petra = tokenOf(created, "Tante Petra");
		TotpSetup setup = confirmedSetup(petra, credential(petra));
		IdentifyRequired required = identifyRequired(get("/", "json", group));
		assertTrue(required.isGroup());
		assertTrue(names(required.getMethods()).contains(TotpSignIns.METHOD));
		_clock.advance(Duration.ofMinutes(1));

		String wrong = wrong(setup);
		FakeResponse stranger = postAs("/", "totp-verify", addressed("vera@web.de", now(setup)), group, null);
		FakeResponse noApp = postAs("/", "totp-verify", addressed("hans@web.de", now(setup)), group, null);
		FakeResponse wrongCode = postAs("/", "totp-verify", addressed(PETRA, wrong), group, null);
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, stranger.status());
		assertEquals(stranger.body(), noApp.body());
		assertEquals(stranger.status(), noApp.status());
		assertEquals(stranger.body(), wrongCode.body());
		assertEquals(stranger.status(), wrongCode.status());

		FakeResponse signedIn = postAs("/", "totp-verify", addressed(PETRA, now(setup)), group, null);
		assertEquals(signedIn.body(), 200, signedIn.status());
		ContactCredential answer = ContactCredential.readContactCredential(reader(body(signedIn)));
		assertEquals(contactOf(created, "Tante Petra"), answer.getContact().getId());
		assertEquals(200, getAs("/", "json", group, answer.getCredential()).status());
	}

	public void testTheGroupLinkLocksAStrangersAddressLikeARecipients() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA));
		String petra = tokenOf(created, "Tante Petra");
		TotpSetup setup = confirmedSetup(petra, credential(petra));
		_clock.advance(Duration.ofMinutes(1));
		String wrong = wrong(setup);
		for (int n = 0; n < TotpSignIns.FAILURES; n++) {
			postAs("/", "totp-verify", addressed("vera@web.de", wrong), created.getToken(), null);
			postAs("/", "totp-verify", addressed(PETRA, wrong), created.getToken(), null);
		}
		FakeResponse stranger = postAs("/", "totp-verify", addressed("vera@web.de", wrong), created.getToken(), null);
		FakeResponse recipient = postAs("/", "totp-verify", addressed(PETRA, now(setup)), created.getToken(), null);
		assertEquals(429, stranger.status());
		assertEquals(stranger.body(), recipient.body());
		assertEquals(stranger.status(), recipient.status());
	}

	public void testAnOpenLinkLetsAContactInByAddressAndCode() throws Exception {
		ShareLinkCreated addressed = created(ZOO, email("Tante Petra", PETRA));
		String petra = tokenOf(addressed, "Tante Petra");
		TotpSetup setup = confirmedSetup(petra, credential(petra));
		ShareLinkCreated open = created("/" + SharingFixture.PUBLIC + "/");
		assertTrue(methods(get("/", "json", open.getToken())).contains(TotpSignIns.METHOD));
		_clock.advance(Duration.ofMinutes(1));

		FakeResponse signedIn = postAs("/", "totp-verify", addressed("Petra@gmx.de", now(setup)), open.getToken(),
			null);
		assertEquals(signedIn.body(), 200, signedIn.status());
		assertEquals(contactOf(addressed, "Tante Petra"),
			ContactCredential.readContactCredential(reader(body(signedIn))).getContact().getId());
	}

	public void testNoCodeOutsideAPersonalLinkThatAsks() throws Exception {
		FakeResponse member = post("/", code("123456"), SharingFixture.ALICE, java.util.Map.of("action", "totp-verify"));
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, member.status());
		assertEquals(AddressProof.TOTP_NOT_HERE, errorMessage(member));

		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA));
		String token = tokenOf(created, "Tante Petra");
		FakeResponse first = postAs("/", "totp-verify", code("123456"), token, null);
		assertEquals("A first open needs no code.", HttpServletResponse.SC_BAD_REQUEST, first.status());
		assertEquals(AddressProof.TOTP_NOT_HERE, errorMessage(first));
	}

	// --- Removing. ---

	public void testTheContactRemovesTheirOwn() throws Exception {
		ShareLinkCreated created = created(ZOO, phone("Klaus", KLAUS));
		String token = tokenOf(created, "Klaus");
		String credential = credential(token);
		confirmedSetup(token, credential);

		FakeResponse removed = postAs("/", "remove-sign-in", "{\"method\":\"totp\"}", token, credential);
		assertEquals(removed.body(), 200, removed.status());
		assertEquals("", ContactSignIns.readContactSignIns(reader(body(removed))).getAuthenticator());
		assertTrue(methods(laterOpen(token)).isEmpty());

		FakeResponse again = postAs("/", "remove-sign-in", "{\"method\":\"totp\"}", token, credential);
		assertEquals(HttpServletResponse.SC_NOT_FOUND, again.status());
		assertEquals(SignInActions.NO_SIGN_IN, errorMessage(again));
		FakeResponse unknown = postAs("/", "remove-sign-in", "{\"method\":\"pigeon\"}", token, credential);
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, unknown.status());
		assertEquals(SignInActions.methodUnknown("pigeon"), errorMessage(unknown));
	}

	public void testAMemberRemovesAContactsAuthenticator() throws Exception {
		ShareLinkCreated created = created(ZOO, phone("Klaus", KLAUS));
		String token = tokenOf(created, "Klaus");
		confirmedSetup(token, credential(token));
		String klaus = contactOf(created, "Klaus");
		String body = "{\"contact\":\"" + klaus + "\",\"method\":\"totp\"}";

		FakeResponse refused = post("/", body, SharingFixture.DAVE, java.util.Map.of("action", "remove-contact-sign-in"));
		assertEquals(HttpServletResponse.SC_FORBIDDEN, refused.status());
		assertEquals(PersonalLinks.CONTACTS_MANAGE_REFUSED, errorMessage(refused));
		FakeResponse byLink = postAs("/", "remove-contact-sign-in", body, token, credential(tokenOf(
			created(ZOO, phone("Klaus", KLAUS)), "Klaus")));
		assertEquals(HttpServletResponse.SC_FORBIDDEN, byLink.status());

		FakeResponse removed = post("/", body, SharingFixture.ALICE, java.util.Map.of("action", "remove-contact-sign-in"));
		assertEquals(removed.body(), 200, removed.status());
		assertEquals("", Contact.readContact(reader(body(removed))).getAuthenticator());
		assertTrue(methods(laterOpen(token)).isEmpty());

		FakeResponse unknown = post("/", "{\"contact\":\"nobody\",\"method\":\"totp\"}", SharingFixture.ALICE,
			java.util.Map.of("action", "remove-contact-sign-in"));
		assertEquals(HttpServletResponse.SC_NOT_FOUND, unknown.status());
	}

	// --- Helpers. ---

	private TotpSetup setUp(String token, String credential) throws Exception {
		FakeResponse response = postAs("/", "totp-setup", "{}", token, credential);
		assertEquals(response.body(), 200, response.status());
		return TotpSetup.readTotpSetup(reader(body(response)));
	}

	private TotpSetup confirmedSetup(String token, String credential) throws Exception {
		TotpSetup setup = setUp(token, credential);
		FakeResponse confirmed = postAs("/", "totp-confirm", code(now(setup)), token, credential);
		assertEquals(confirmed.body(), 200, confirmed.status());
		return setup;
	}

	/** The refusal of a recipient's own link opened before, in a browser that holds nothing. */
	private FakeResponse laterOpen(String token) throws Exception {
		FakeResponse response = get("/", "json", token);
		assertEquals(response.body(), HttpServletResponse.SC_UNAUTHORIZED, response.status());
		assertEquals(AuthService.IDENTIFY_REQUIRED, errorMessage(response));
		return response;
	}

	private String now(TotpSetup setup) {
		return Totp.code(Totp.base32Decode(setup.getSecret()), Totp.step(_clock.instant()));
	}

	private String wrong(TotpSetup setup) {
		for (int n = 0;; n++) {
			String candidate = String.format("%06d", Integer.valueOf(n));
			if (Totp.match(setup.getSecret(), candidate, Totp.step(_clock.instant())) < 0) {
				return candidate;
			}
		}
	}

	private static String code(String code) {
		return "{\"code\":\"" + code + "\"}";
	}

	private static String addressed(String address, String code) {
		return "{\"address\":\"" + address + "\",\"code\":\"" + code + "\",\"remember\":true}";
	}

	private static List<String> methods(FakeResponse refusal) throws Exception {
		return names(identifyRequired(refusal).getMethods());
	}

	private static List<String> names(List<ProofMethod> methods) {
		return methods.stream().map(ProofMethod::getName).toList();
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
}
