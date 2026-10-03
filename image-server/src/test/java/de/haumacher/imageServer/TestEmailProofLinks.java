/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.ContactStore;
import de.haumacher.imageServer.mail.EmailProofs;
import de.haumacher.imageServer.mail.TestEmailProofs.CapturingMailer;
import de.haumacher.imageServer.mail.TestEmailProofs.TestClock;
import de.haumacher.imageServer.shared.model.AuthInfo;
import de.haumacher.imageServer.shared.model.Contact;
import de.haumacher.imageServer.shared.model.ContactAddress;
import de.haumacher.imageServer.shared.model.ContactCredential;
import de.haumacher.imageServer.shared.model.EmailProofSent;
import de.haumacher.imageServer.shared.model.IdentifyRequired;
import de.haumacher.imageServer.shared.model.ProofMethod;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test case for the proof of an e-mail address on a personal share link, see issue #199.
 *
 * <p>
 * The servlet is given {@link EmailProofs} that mail through a {@link CapturingMailer}, so a test
 * reads the code the way a person reads their mail. The SMTP side itself is pinned by
 * <code>TestEmailProofs</code> and <code>TestSmtpMailer</code> against a fake SMTP server.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestEmailProofLinks extends PersonalLinkTestCase {

	private CapturingMailer _mailer;

	private TestClock _clock;

	private EmailProofs _proofs;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_mailer = new CapturingMailer();
		_clock = new TestClock();
		// The mail of the group link goes out in the background; here at once, to be read.
		_proofs = new EmailProofs(_mailer, _clock, Runnable::run);
	}

	/** Every servlet of the test, including one made afresh by {@link #restartServer()}, mails through the same proofs. */
	@Override
	protected ImageServlet servlet() throws Exception {
		ImageServlet result = super.servlet();
		result.setEmailProofs(_proofs);
		return result;
	}

	// --- No mail account. ---

	public void testWithoutAMailAccountNothingIsOffered() throws Exception {
		_proofs = null;
		ShareLinkCreated open = created(ZOO);
		FakeResponse refusal = get("/", "json", open.getToken());
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, refusal.status());
		assertTrue(identifyRequired(refusal).getMethods().isEmpty());

		FakeResponse prove = prove(open.getToken(), null, "{\"address\":\"vera@web.de\"}");
		assertEquals(prove.body(), HttpServletResponse.SC_NOT_IMPLEMENTED, prove.status());
		assertEquals(EmailProofs.NOT_CONFIGURED, errorMessage(prove));
		FakeResponse verify = verify(open.getToken(), null, "vera@web.de", 0, "123456");
		assertEquals(HttpServletResponse.SC_NOT_IMPLEMENTED, verify.status());
		assertEquals(EmailProofs.NOT_CONFIGURED, errorMessage(verify));
		assertTrue(_mailer.sent().isEmpty());
	}

	// --- An open personal link, end to end. ---

	public void testAnOpenLinkIsEnteredByProvingAnAddress() throws Exception {
		ShareLinkCreated open = created(ZOO);
		String token = open.getToken();

		FakeResponse refusal = get("/", "json", token);
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, refusal.status());
		assertEquals(AuthService.IDENTIFY_OPEN, errorMessage(refusal));
		assertEquals(List.of(EmailProofs.METHOD), names(identifyRequired(refusal).getMethods()));

		FakeResponse sent = prove(token, null, "{\"address\":\"Vera <Vera@Web.DE>\"}");
		assertEquals(sent.body(), 200, sent.status());
		EmailProofSent answer = EmailProofSent.readEmailProofSent(reader(body(sent)));
		assertEquals("v•••@web.de", answer.getAddress());
		assertEquals(EmailProofs.ATTEMPTS, answer.getAttempts());
		assertEquals(_clock.instant().plus(EmailProofs.LIFETIME).toString(), answer.getExpires());
		assertEquals("vera@web.de", _mailer.sent().get(0)[0]);

		FakeResponse verified = verify(token, null, "Vera <Vera@Web.DE>", 0, _mailer.lastCode("vera@web.de"),
			true, "Vera");
		assertEquals(verified.body(), 200, verified.status());
		ContactCredential credential = ContactCredential.readContactCredential(reader(body(verified)));
		assertFalse(credential.getCredential().isEmpty());
		assertTrue(credential.isRemember());
		assertEquals("Vera", credential.getContact().getDisplayName());

		FakeResponse album = getAs("/", "json", token, credential.getCredential());
		assertEquals(album.body(), 200, album.status());

		Contact vera = contact(credential.getContact().getId());
		assertEquals("The visitor's own name names the new contact.", "Vera", vera.getName());
		assertEquals("link:" + open.getLink().getId(), vera.getCreatedBy());
		assertEquals(1, vera.getAddresses().size());
		assertEquals("vera@web.de", vera.getAddresses().get(0).getValue());
		assertTrue(vera.getAddresses().get(0).isProven());

		// A second visitor with the same address, in another browser, is the same person.
		_clock.advance(Duration.ofMinutes(1));
		assertEquals(200, prove(token, null, "{\"address\":\"vera@web.de\"}").status());
		FakeResponse again = verify(token, null, "vera@web.de", 0, _mailer.lastCode("vera@web.de"), false, "");
		assertEquals(again.body(), 200, again.status());
		ContactCredential second = ContactCredential.readContactCredential(reader(body(again)));
		assertEquals(vera.getId(), second.getContact().getId());
		assertFalse(second.getCredential().equals(credential.getCredential()));
		assertEquals("No duplicate by address.", 1, contacts().size());

		// An upload through the open link is hers.
		FakeResponse upload = uploadAs("/", token, second.getCredential(), "vera.jpg", photo("vera"));
		assertEquals(upload.body(), 200, upload.status());
		assertTrue(java.nio.file.Files.readString(_base.resolve(SharingFixture.ZOO).resolve(".hashes.json"))
			.contains("contact:" + vera.getId()));
	}

	public void testAnAddressOfAKnownContactIsThatContact() throws Exception {
		ShareLinkCreated addressed = created(ZOO, email("Tante Petra", PETRA));
		String petra = contactOf(addressed, "Tante Petra");
		ShareLinkCreated open = created("/" + SharingFixture.PUBLIC + "/");

		assertEquals(200, prove(open.getToken(), null, "{\"address\":\"petra@gmx.de\"}").status());
		FakeResponse verified = verify(open.getToken(), null, "petra@gmx.de", 0, _mailer.lastCode("petra@gmx.de"),
			true, "");
		assertEquals(verified.body(), 200, verified.status());
		ContactCredential credential = ContactCredential.readContactCredential(reader(body(verified)));
		assertEquals(petra, credential.getContact().getId());
		assertEquals("Tante Petra", contact(petra).getName());
		assertTrue("Proven now.", contact(petra).getAddresses().get(0).isProven());
		assertEquals(1, contacts().size());
		assertEquals("Her credential opens her addressed link as well.", 200,
			getAs("/", "json", addressed.getToken(), credential.getCredential()).status());
	}

	public void testAContactShutOutOfTheOpenLinkStaysOut() throws Exception {
		ShareLinkCreated addressed = created(ZOO, email("Tante Petra", PETRA));
		String petra = contactOf(addressed, "Tante Petra");
		ShareLinkCreated open = created("/" + SharingFixture.PUBLIC + "/");
		FakeResponse shut = post("/" + SharingFixture.PUBLIC + "/", "{\"link\":\"" + open.getLink().getId()
			+ "\",\"contact\":\"" + petra + "\",\"shutOut\":true}", SharingFixture.ALICE, Map.of("action", "shut-out"));
		assertEquals(shut.body(), 200, shut.status());

		assertEquals("The code goes out like any other.", 200,
			prove(open.getToken(), null, "{\"address\":\"petra@gmx.de\"}").status());
		FakeResponse verified = verify(open.getToken(), null, "petra@gmx.de", 0, _mailer.lastCode("petra@gmx.de"),
			true, "");
		assertEquals(verified.body(), HttpServletResponse.SC_GONE, verified.status());
		assertEquals(AuthService.CONTACT_SHUT_OUT, errorMessage(verified));
		assertTrue(contact(petra).getSessions().isEmpty());
	}

	// --- An addressed link: the contact's own addresses only. ---

	/** Review probe: a contact blocked in the space cannot come back through an open link. */
	public void testAContactBlockedInTheSpaceCannotProveTheirWayIn() throws Exception {
		ShareLinkCreated addressed = created(ZOO, email("Tante Petra", PETRA));
		String petra = contactOf(addressed, "Tante Petra");
		FakeResponse block = post("/", "{\"contact\":\"" + petra + "\",\"shutOut\":true}", SharingFixture.ALICE,
			Map.of("action", "block-contact"));
		assertEquals(block.body(), 200, block.status());

		ShareLinkCreated open = created("/" + SharingFixture.PUBLIC + "/");
		prove(open.getToken(), null, "{\"address\":\"petra@gmx.de\"}");
		FakeResponse verified = verify(open.getToken(), null, "petra@gmx.de", 0, _mailer.lastCode("petra@gmx.de"),
			true, "");
		assertTrue("blocked: " + verified.status() + " " + verified.body(), verified.status() >= 400);
		assertTrue(contact(petra).getSessions().isEmpty());
	}

	/** Review probe: what a visitor types never reaches a mail. */
	public void testNothingTheVisitorTypedIsMailed() throws Exception {
		ShareLinkCreated open = created(ZOO);
		String token = open.getToken();
		prove(token, null, "{\"address\":\"vera@web.de\"}");
		FakeResponse verified = verify(token, null, "vera@web.de", 0, _mailer.lastCode("vera@web.de"), true,
			"Click http://evil.example/ <b>now</b>");
		assertEquals(verified.body(), 200, verified.status());
		_clock.advance(Duration.ofMinutes(1));
		prove(token, null, "{\"address\":\"vera@web.de\"}");
		for (String[] mail : _mailer.sent()) {
			assertFalse(mail[1] + mail[2], (mail[1] + mail[2]).contains("evil.example"));
			assertFalse(mail[1] + mail[2], (mail[1] + mail[2]).contains("<b>"));
		}
	}

	public void testARecipientChoosesAmongTheMaskedAddresses() throws Exception {
		ShareLinkCreated created = created(ZOO, "{\"name\":\"Tante Petra\",\"addresses\":["
			+ "{\"kind\":\"PHONE\",\"value\":\"" + KLAUS + "\"},"
			+ "{\"kind\":\"EMAIL\",\"value\":\"" + PETRA + "\"},"
			+ "{\"kind\":\"EMAIL\",\"value\":\"tante@web.de\"}]}");
		String token = tokenOf(created, "Tante Petra");
		credential(token);

		FakeResponse refusal = get("/", "json", token);
		IdentifyRequired required = identifyRequired(refusal);
		assertEquals(AuthService.IDENTIFY_REQUIRED, errorMessage(refusal));
		assertEquals(List.of(EmailProofs.METHOD), names(required.getMethods()));
		assertEquals("t•••@web.de", required.getAddresses().get(2).getMasked());

		for (String free : new String[] { "{\"address\":\"petra@gmx.de\"}", "{\"address\":\"PETRA@gmx.de\"}",
			"{\"address\":\"mallory@evil.example\"}", "{\"choice\":1}", "{\"choice\":4}", "{}",
			"{\"address\":\"+49•••67\"}" }) {
			FakeResponse refused = prove(token, null, free);
			assertEquals(free + ": " + refused.body(), HttpServletResponse.SC_BAD_REQUEST, refused.status());
			assertEquals("The same refusal for the contact's own address as for a stranger's: " + free,
				AddressProof.ADDRESS_NOT_OFFERED, errorMessage(refused));
		}
		assertTrue("Nothing was mailed for a free address.", _mailer.sent().isEmpty());

		FakeResponse byMask = prove(token, null, "{\"address\":\"p•••@gmx.de\"}");
		assertEquals(byMask.body(), 200, byMask.status());
		assertEquals("petra@gmx.de", _mailer.sent().get(0)[0]);
		assertEquals("p•••@gmx.de", EmailProofSent.readEmailProofSent(reader(body(byMask))).getAddress());

		FakeResponse byChoice = prove(token, null, "{\"choice\":3}");
		assertEquals(byChoice.body(), 200, byChoice.status());
		assertEquals("tante@web.de", _mailer.sent().get(1)[0]);

		FakeResponse verified = verify(token, null, "", 3, _mailer.lastCode("tante@web.de"), true, "");
		assertEquals(verified.body(), 200, verified.status());
		ContactCredential credential = ContactCredential.readContactCredential(reader(body(verified)));
		assertEquals(contactOf(created, "Tante Petra"), credential.getContact().getId());
		assertEquals(200, getAs("/", "json", token, credential.getCredential()).status());
		Contact petra = contact(credential.getContact().getId());
		assertTrue(address(petra, "tante@web.de").isProven());
		assertFalse(address(petra, "petra@gmx.de").isProven());
	}

	public void testACodeOfOneAddressDoesNotProveAnother() throws Exception {
		ShareLinkCreated created = created(ZOO, "{\"name\":\"Tante Petra\",\"addresses\":["
			+ "{\"kind\":\"EMAIL\",\"value\":\"" + PETRA + "\"},{\"kind\":\"EMAIL\",\"value\":\"tante@web.de\"}]}");
		String token = tokenOf(created, "Tante Petra");
		credential(token);
		assertEquals(200, prove(token, null, "{\"choice\":1}").status());
		FakeResponse wrongAddress = verify(token, null, "", 2, _mailer.lastCode("petra@gmx.de"), true, "");
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, wrongAddress.status());
		assertEquals(EmailProofs.CODE_VOID, errorMessage(wrongAddress));
	}

	public void testAPhoneOnlyRecipientIsOfferedNoCode() throws Exception {
		ShareLinkCreated created = created(ZOO, phone("Klaus", KLAUS));
		String token = tokenOf(created, "Klaus");
		credential(token);
		assertTrue(identifyRequired(get("/", "json", token)).getMethods().isEmpty());
		FakeResponse refused = prove(token, null, "{\"choice\":1}");
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, refused.status());
		assertEquals(AddressProof.ADDRESS_NOT_OFFERED, errorMessage(refused));
	}

	public void testAFirstOpenNeedsNoProof() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA));
		String token = tokenOf(created, "Tante Petra");
		IdentifyRequired first = identifyRequired(get("/", "json", token));
		assertTrue(first.isFirstOpen());
		assertTrue(first.getMethods().isEmpty());
		FakeResponse refused = prove(token, null, "{\"address\":\"p•••@gmx.de\"}");
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, refused.status());
		assertTrue(identifyRequired(refused).isFirstOpen());
		assertTrue(_mailer.sent().isEmpty());
	}

	// --- The group link: the own token of an addressed link, issue #211. ---

	private ShareLinkCreated group() throws Exception {
		return created(ZOO, email("Tante Petra", PETRA), email("Onkel Hans", "hans@web.de"), phone("Klaus", KLAUS));
	}

	public void testTheGroupLinkNamesItsMethodsAndNoAddress() throws Exception {
		ShareLinkCreated created = group();
		FakeResponse refusal = get("/", "json", created.getToken());
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, refusal.status());
		assertEquals(AuthService.IDENTIFY_PERSONAL, errorMessage(refusal));
		IdentifyRequired required = identifyRequired(refusal);
		assertTrue(required.isGroup());
		assertFalse(required.isFirstOpen());
		assertNull(required.getContact());
		assertTrue("Naming them would reveal the group.", required.getAddresses().isEmpty());
		assertEquals(List.of(EmailProofs.METHOD), names(required.getMethods()));
		assertFalse(refusal.body(), refusal.body().contains("•••"));
		assertFalse(refusal.body().contains("gmx"));

		// A recipient's own token and an open link are no group link.
		String petra = tokenOf(created, "Tante Petra");
		assertFalse(identifyRequired(get("/", "json", petra)).isGroup());
		credential(petra);
		assertFalse(identifyRequired(get("/", "json", petra)).isGroup());
		assertFalse(identifyRequired(get("/", "json", created("/" + SharingFixture.PUBLIC + "/").getToken())).isGroup());
	}

	public void testARecipientsAddressOpensTheGroupLink() throws Exception {
		ShareLinkCreated created = group();
		String token = created.getToken();
		String petra = contactOf(created, "Tante Petra");

		FakeResponse sent = prove(token, null, "{\"address\":\"PETRA@gmx.de\"}");
		assertEquals(sent.body(), 200, sent.status());
		EmailProofSent answer = EmailProofSent.readEmailProofSent(reader(body(sent)));
		assertEquals("p•••@gmx.de", answer.getAddress());
		assertEquals(_clock.instant().plus(EmailProofs.LIFETIME).toString(), answer.getExpires());
		assertEquals(1, _mailer.sent().size());
		assertEquals("petra@gmx.de", _mailer.sent().get(0)[0]);

		FakeResponse verified = verify(token, null, "petra@gmx.de", 0, _mailer.lastCode("petra@gmx.de"), true,
			"Petra");
		assertEquals(verified.body(), 200, verified.status());
		ContactCredential credential = ContactCredential.readContactCredential(reader(body(verified)));
		assertEquals(petra, credential.getContact().getId());
		assertFalse(credential.getCredential().isEmpty());
		assertTrue(address(contact(petra), "petra@gmx.de").isProven());
		assertEquals("The name the space gives her stays.", "Tante Petra", contact(petra).getName());
		assertEquals("Nobody was created.", 3, contacts().size());

		FakeResponse album = getAs("/", "json", token, credential.getCredential());
		assertEquals(album.body(), 200, album.status());
		AuthInfo auth = auth(getAs("/", "auth", token, credential.getCredential()));
		assertEquals(petra, auth.getShare().getContact().getId());
		assertEquals("Her credential opens her own link too.", 200,
			getAs("/", "json", tokenOf(created, "Tante Petra"), credential.getCredential()).status());
	}

	public void testAStrangersAddressIsAnsweredAlikeAndOpensNothing() throws Exception {
		ShareLinkCreated created = group();
		String token = created.getToken();

		FakeResponse member = prove(token, null, "{\"address\":\"petra@gmx.de\"}");
		FakeResponse stranger = prove(token, null, "{\"address\":\"paul@gmx.de\"}");
		assertEquals(200, member.status());
		assertEquals(member.status(), stranger.status());
		assertEquals("Byte for byte the same answer.", member.body(), stranger.body());
		assertEquals(member.header("Content-Type"), stranger.header("Content-Type"));
		assertEquals(member.header("Cache-Control"), stranger.header("Cache-Control"));
		assertEquals("Only the recipient was mailed.", 1, _mailer.sent().size());
		assertEquals("petra@gmx.de", _mailer.sent().get(0)[0]);

		// A wrong code is answered alike as well, and no code is right for the stranger.
		String code = _mailer.lastCode("petra@gmx.de");
		String wrong = code.equals("000000") ? "111111" : "000000";
		FakeResponse wrongMember = verify(token, null, "petra@gmx.de", 0, wrong);
		FakeResponse wrongStranger = verify(token, null, "paul@gmx.de", 0, wrong);
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, wrongStranger.status());
		assertEquals(EmailProofs.CODE_WRONG, errorMessage(wrongStranger));
		assertEquals(wrongMember.body(), wrongStranger.body());
		for (int n = 0; n < 10; n++) {
			FakeResponse guess = verify(token, null, "paul@gmx.de", 0, String.format("%06d", n));
			assertTrue(guess.body(), guess.status() >= 400);
		}
		FakeResponse withPetrasCode = verify(token, null, "paul@gmx.de", 0, code);
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, withPetrasCode.status());
		assertEquals("Nobody was created.", 3, contacts().size());
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, get("/", "json", token).status());
	}

	public void testAPhoneOnlyRecipientCannotUseTheGroupLink() throws Exception {
		ShareLinkCreated created = group();
		String token = created.getToken();
		FakeResponse number = prove(token, null, "{\"address\":\"" + KLAUS + "\"}");
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, number.status());
		assertEquals(ContactStore.addressInvalid(KLAUS), errorMessage(number));
		FakeResponse guessed = prove(token, null, "{\"address\":\"klaus@web.de\"}");
		assertEquals(200, guessed.status());
		assertTrue("No address of his is saved, so nothing goes anywhere.", _mailer.sent().isEmpty());
		assertEquals(HttpServletResponse.SC_BAD_REQUEST,
			verify(token, null, "klaus@web.de", 0, "123456").status());
		assertEquals("His own link stays his.", 200,
			getAs("/", "json", tokenOf(created, "Klaus"), credential(tokenOf(created, "Klaus"))).status());
	}

	public void testABlockedOrShutOutRecipientStaysOutOfTheGroupLink() throws Exception {
		ShareLinkCreated created = group();
		String token = created.getToken();
		String petra = contactOf(created, "Tante Petra");
		String hans = contactOf(created, "Onkel Hans");
		FakeResponse block = post("/", "{\"contact\":\"" + petra + "\",\"shutOut\":true}", SharingFixture.ALICE,
			Map.of("action", "block-contact"));
		assertEquals(block.body(), 200, block.status());
		FakeResponse shut = post(ZOO, "{\"link\":\"" + created.getLink().getId() + "\",\"contact\":\"" + hans
			+ "\",\"shutOut\":true}", SharingFixture.ALICE, Map.of("action", "shut-out"));
		assertEquals(shut.body(), 200, shut.status());

		FakeResponse stranger = prove(token, null, "{\"address\":\"paul@gmx.de\"}");
		FakeResponse blocked = prove(token, null, "{\"address\":\"petra@gmx.de\"}");
		FakeResponse shutOut = prove(token, null, "{\"address\":\"hans@web.de\"}");
		assertEquals(200, blocked.status());
		assertEquals("Answered as a stranger is.", stranger.body(), blocked.body());
		assertEquals(200, shutOut.status());
		assertTrue("Neither was mailed.", _mailer.sent().isEmpty());
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, verify(token, null, "petra@gmx.de", 0, "123456").status());
		assertTrue(contact(petra).getSessions().isEmpty());
		assertTrue(contact(hans).getSessions().isEmpty());
	}

	public void testTheLimitsHoldOnTheGroupLink() throws Exception {
		String token = group().getToken();
		for (int n = 0; n < EmailProofs.PER_ADDRESS; n++) {
			assertEquals(200, prove(token, null, "{\"address\":\"petra@gmx.de\"}", "10.0.0." + n, null).status());
			assertEquals(200, prove(token, null, "{\"address\":\"paul@gmx.de\"}", "10.0.1." + n, null).status());
		}
		FakeResponse member = prove(token, null, "{\"address\":\"petra@gmx.de\"}", "10.0.2.1", null);
		FakeResponse stranger = prove(token, null, "{\"address\":\"paul@gmx.de\"}", "10.0.2.2", null);
		for (FakeResponse refused : new FakeResponse[] { member, stranger }) {
			assertEquals(429, refused.status());
			assertEquals(EmailProofs.RATE_LIMITED, errorMessage(refused));
			assertNotNull(refused.header("Retry-After"));
		}
		assertEquals(member.body(), stranger.body());
		assertEquals(EmailProofs.PER_ADDRESS, _mailer.sent().size());

		// Per client.
		for (int n = 0; n < EmailProofs.PER_CLIENT; n++) {
			assertEquals(200, prove(token, null, "{\"address\":\"c" + n + "@web.de\"}", "198.51.100.7", null).status());
		}
		assertEquals(429, prove(token, null, "{\"address\":\"hans@web.de\"}", "198.51.100.7", null).status());

		// Per link: every code asked for through it counts, a stranger's as much as a recipient's.
		int asked = 2 * EmailProofs.PER_ADDRESS + EmailProofs.PER_CLIENT;
		for (int n = asked; n < EmailProofs.PER_LINK; n++) {
			assertEquals(200, prove(token, null, "{\"address\":\"v" + n + "@web.de\"}", "10.1.0." + n, null).status());
		}
		FakeResponse full = prove(token, null, "{\"address\":\"hans@web.de\"}", "10.2.0.1", null);
		assertEquals(429, full.status());
		assertEquals(EmailProofs.RATE_LIMITED, errorMessage(full));
		assertFalse("Hans got no code.", _mailer.sent().stream().anyMatch(mail -> mail[0].equals("hans@web.de")));
	}

	public void testACredentialTheGroupLinkDoesNotAdmitMayProveARecipientsAddress() throws Exception {
		ShareLinkCreated created = group();
		ShareLinkCreated other = created("/" + SharingFixture.PUBLIC + "/", email("Vera", "vera@web.de"));
		String vera = credential(tokenOf(other, "Vera"));
		FakeResponse refused = getAs("/", "json", created.getToken(), vera);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, refused.status());
		assertTrue(identifyRequired(refused).isGroup());

		assertEquals(200, prove(created.getToken(), vera, "{\"address\":\"hans@web.de\"}").status());
		FakeResponse verified = verify(created.getToken(), vera, "hans@web.de", 0, _mailer.lastCode("hans@web.de"));
		assertEquals(verified.body(), 200, verified.status());
		assertEquals(contactOf(created, "Onkel Hans"),
			ContactCredential.readContactCredential(reader(body(verified))).getContact().getId());
	}

	/** Issue #211: the share dialog learns whether a link can be proven at all. */
	public void testAMemberIsToldTheProofMethods() throws Exception {
		assertEquals(List.of(EmailProofs.METHOD),
			names(auth(get("/", "auth", SharingFixture.ALICE)).getProofMethods()));
		assertTrue("Never to an anonymous caller.", auth(get("/", "auth", null)).getProofMethods().isEmpty());
		_proofs = null;
		assertTrue("Nothing where nothing can be proven.",
			auth(get("/", "auth", SharingFixture.ALICE)).getProofMethods().isEmpty());
	}

	public void testNobodyButAPersonalLinkProves() throws Exception {
		for (String token : new String[] { zooToken(), SharingFixture.ALICE, null }) {
			FakeResponse refused = prove(token, null, "{\"address\":\"vera@web.de\"}");
			assertEquals(refused.body(), HttpServletResponse.SC_BAD_REQUEST, refused.status());
			assertEquals(AddressProof.PROOF_NOT_HERE, errorMessage(refused));
		}
		assertTrue(_mailer.sent().isEmpty());
	}

	// --- A recognised contact adds an address. ---

	public void testARecognisedContactAddsAnAddress() throws Exception {
		ShareLinkCreated created = created(ZOO, phone("Klaus", KLAUS));
		String token = tokenOf(created, "Klaus");
		String credential = credential(token);

		AuthInfo auth = auth(getAs("/", "auth", token, credential));
		assertEquals("The app may offer to add an e-mail.", List.of(EmailProofs.METHOD),
			names(auth.getShare().getMethods()));
		assertFalse("He has none yet (#211).", auth.getShare().isContactHasEmail());

		assertEquals(200, prove(token, credential, "{\"address\":\"Klaus@Web.de\"}").status());
		FakeResponse verified = verify(token, credential, "klaus@web.de", 0, _mailer.lastCode("klaus@web.de"), true,
			"");
		assertEquals(verified.body(), 200, verified.status());
		ContactCredential answer = ContactCredential.readContactCredential(reader(body(verified)));
		assertEquals("The credential held stays.", "", answer.getCredential());
		assertEquals(contactOf(created, "Klaus"), answer.getContact().getId());
		assertTrue(address(contact(answer.getContact().getId()), "klaus@web.de").isProven());
		assertEquals("Still in.", 200, getAs("/", "json", token, credential).status());
		assertTrue("Now he has one.", auth(getAs("/", "auth", token, credential)).getShare().isContactHasEmail());

		// In another browser he is now offered the code to that address.
		IdentifyRequired required = identifyRequired(get("/", "json", token));
		assertEquals(List.of(EmailProofs.METHOD), names(required.getMethods()));
		assertEquals("k•••@web.de", required.getAddresses().get(1).getMasked());
		assertEquals(200, prove(token, null, "{\"choice\":2}").status());
		FakeResponse elsewhere = verify(token, null, "k•••@web.de", 0, _mailer.lastCode("klaus@web.de"), false, "");
		assertEquals(elsewhere.body(), 200, elsewhere.status());
	}

	public void testAnAddressOfSomebodyElseIsNotAdded() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA), phone("Klaus", KLAUS));
		String klaus = credential(tokenOf(created, "Klaus"));
		String token = tokenOf(created, "Klaus");
		assertEquals("Whether the address is known is not said here.", 200,
			prove(token, klaus, "{\"address\":\"petra@gmx.de\"}").status());
		FakeResponse verified = verify(token, klaus, "petra@gmx.de", 0, _mailer.lastCode("petra@gmx.de"), true, "");
		assertEquals(verified.body(), HttpServletResponse.SC_CONFLICT, verified.status());
		assertEquals(ContactStore.ADDRESS_ELSEWHERE, errorMessage(verified));
		assertEquals(1, contact(contactOf(created, "Klaus")).getAddresses().size());
	}

	// --- Codes, attempts, limits. ---

	public void testFiveWrongCodesVoidTheCode() throws Exception {
		String token = created(ZOO).getToken();
		assertEquals(200, prove(token, null, "{\"address\":\"vera@web.de\"}").status());
		String code = _mailer.lastCode("vera@web.de");
		String wrong = code.equals("000000") ? "111111" : "000000";
		for (int n = 0; n < EmailProofs.ATTEMPTS; n++) {
			FakeResponse refused = verify(token, null, "vera@web.de", 0, wrong);
			assertEquals(HttpServletResponse.SC_BAD_REQUEST, refused.status());
			assertEquals(EmailProofs.CODE_WRONG, errorMessage(refused));
		}
		FakeResponse sixth = verify(token, null, "vera@web.de", 0, code);
		assertEquals(EmailProofs.CODE_VOID, errorMessage(sixth));
		assertTrue(contacts().isEmpty());
	}

	public void testACodeIsVoidAfterTenMinutes() throws Exception {
		String token = created(ZOO).getToken();
		assertEquals(200, prove(token, null, "{\"address\":\"vera@web.de\"}").status());
		_clock.advance(EmailProofs.LIFETIME);
		FakeResponse late = verify(token, null, "vera@web.de", 0, _mailer.lastCode("vera@web.de"));
		assertEquals(EmailProofs.CODE_VOID, errorMessage(late));
	}

	public void testACodeCountsOnItsOwnLinkOnly() throws Exception {
		String a = created(ZOO).getToken();
		String b = created("/" + SharingFixture.PUBLIC + "/").getToken();
		assertEquals(200, prove(a, null, "{\"address\":\"vera@web.de\"}").status());
		FakeResponse other = verify(b, null, "vera@web.de", 0, _mailer.lastCode("vera@web.de"));
		assertEquals(EmailProofs.CODE_VOID, errorMessage(other));
	}

	public void testALimitSaysNothingAboutTheAddress() throws Exception {
		created(ZOO, email("Tante Petra", PETRA));
		String token = created("/" + SharingFixture.PUBLIC + "/").getToken();
		for (int n = 0; n < EmailProofs.PER_ADDRESS; n++) {
			assertEquals(200, prove(token, null, "{\"address\":\"petra@gmx.de\"}", "10.0.0." + n, null).status());
			assertEquals(200, prove(token, null, "{\"address\":\"nobody@gmx.de\"}", "10.0.1." + n, null).status());
		}
		FakeResponse known = prove(token, null, "{\"address\":\"petra@gmx.de\"}", "10.0.2.1", null);
		FakeResponse unknown = prove(token, null, "{\"address\":\"nobody@gmx.de\"}", "10.0.2.2", null);
		for (FakeResponse refused : new FakeResponse[] { known, unknown }) {
			assertEquals(429, refused.status());
			assertEquals(EmailProofs.RATE_LIMITED, errorMessage(refused));
			assertNotNull(refused.header("Retry-After"));
		}
		assertEquals("The known and the unknown address are answered alike.", known.body(), unknown.body());
	}

	public void testCodesPerClientAreLimited() throws Exception {
		String token = created(ZOO).getToken();
		for (int n = 0; n < EmailProofs.PER_CLIENT; n++) {
			assertEquals(200, prove(token, null, "{\"address\":\"v" + n + "@web.de\"}", "198.51.100.7", null).status());
		}
		assertEquals(429, prove(token, null, "{\"address\":\"w@web.de\"}", "198.51.100.7", null).status());
		assertEquals(200, prove(token, null, "{\"address\":\"w@web.de\"}", "198.51.100.8", null).status());
	}

	public void testTheMailSpeaksTheRequestersLanguageAndNamesTheSpace() throws Exception {
		String token = created(ZOO).getToken();
		assertEquals(200, prove(token, null, "{\"address\":\"vera@web.de\"}", "192.0.2.9", "de-DE,de;q=0.9").status());
		String[] mail = _mailer.sent().get(0);
		assertTrue(mail[1], mail[1].startsWith("Ihr Code für VAlbum: "));
		assertTrue(mail[2], mail[2].contains("10 Minuten"));
		assertEquals(200, prove(token, null, "{\"address\":\"vera@web.de\"}", "192.0.2.9", "en").status());
		assertTrue(_mailer.sent().get(1)[1].startsWith("Your code for VAlbum: "));
	}

	public void testAnAddressThatIsNoneIsRefused() throws Exception {
		String token = created(ZOO).getToken();
		FakeResponse refused = prove(token, null, "{\"address\":\"not an address\"}");
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, refused.status());
		assertEquals(ContactStore.addressInvalid("not an address"), errorMessage(refused));
		FakeResponse unreadable = prove(token, null, "{nonsense");
		assertEquals(AddressProof.PROOF_UNREADABLE, errorMessage(unreadable));
	}

	public void testARevokedLinkIsGone() throws Exception {
		ShareLinkCreated open = created(ZOO);
		FakeResponse revoked = post(ZOO, "{\"id\":\"" + open.getLink().getId() + "\"}", SharingFixture.ALICE,
			Map.of("action", "unshare"));
		assertEquals(revoked.body(), 200, revoked.status());
		FakeResponse gone = prove(open.getToken(), null, "{\"address\":\"vera@web.de\"}");
		assertEquals(HttpServletResponse.SC_GONE, gone.status());
	}

	// --- Helpers. ---

	private FakeResponse prove(String bearer, String credential, String body) throws Exception {
		return prove(bearer, credential, body, null, null);
	}

	private FakeResponse prove(String bearer, String credential, String body, String client, String language)
			throws Exception {
		return send("prove-email", bearer, credential, body, client, language);
	}

	private FakeResponse verify(String bearer, String credential, String address, int choice, String code)
			throws Exception {
		return verify(bearer, credential, address, choice, code, true, "");
	}

	private FakeResponse verify(String bearer, String credential, String address, int choice, String code,
			boolean remember, String displayName) throws Exception {
		String body = "{\"address\":\"" + address + "\",\"choice\":" + choice + ",\"code\":\"" + code
			+ "\",\"remember\":" + remember + ",\"displayName\":\"" + displayName + "\"}";
		return send("verify-email", bearer, credential, body, null, null);
	}

	private FakeResponse send(String action, String bearer, String credential, String body, String client,
			String language) throws Exception {
		Map<String, String> headers = headers(bearer, credential);
		if (client != null) {
			headers.put(TestImageServletPut.REMOTE_ADDR, client);
		}
		if (language != null) {
			headers.put("Accept-Language", language);
		}
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", action);
		FakeResponse response = new FakeResponse();
		servlet().doPost(TestImageServletPut.request("/", "application/json",
			body.getBytes(StandardCharsets.UTF_8), headers, parameters), response.response());
		return response;
	}

	private List<Contact> contacts() throws Exception {
		return contactList(get("/", "contacts", SharingFixture.ALICE)).getContacts();
	}

	private Contact contact(String id) throws Exception {
		for (Contact contact : contacts()) {
			if (contact.getId().equals(id)) {
				return contact;
			}
		}
		fail("No contact " + id);
		return null;
	}

	private static ContactAddress address(Contact contact, String value) {
		for (ContactAddress address : contact.getAddresses()) {
			if (address.getValue().equals(value)) {
				return address;
			}
		}
		fail("No address " + value + " with " + contact.getName());
		return null;
	}

	private static List<String> names(List<ProofMethod> methods) {
		return methods.stream().map(ProofMethod::getName).toList();
	}
}
