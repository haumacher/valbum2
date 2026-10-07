/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.ContactStore;
import de.haumacher.imageServer.auth.Totp;
import de.haumacher.imageServer.auth.TotpSignIns;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.mail.EmailProofs;
import de.haumacher.imageServer.mail.TestEmailProofs.CapturingMailer;
import de.haumacher.imageServer.mail.TestEmailProofs.TestClock;
import de.haumacher.imageServer.oidc.MockOidcIssuer;
import de.haumacher.imageServer.oidc.OidcLogins;
import de.haumacher.imageServer.passkeys.Passkeys;
import de.haumacher.imageServer.passkeys.SoftwareAuthenticator;
import de.haumacher.imageServer.shared.model.AuthInfo;
import de.haumacher.imageServer.shared.model.ContactCredential;
import de.haumacher.imageServer.shared.model.ContactSignIns;
import de.haumacher.imageServer.shared.model.DeviceList;
import de.haumacher.imageServer.shared.model.InvitationCreated;
import de.haumacher.imageServer.shared.model.OidcStarted;
import de.haumacher.imageServer.shared.model.PairResponse;
import de.haumacher.imageServer.shared.model.PasskeyOptions;
import de.haumacher.imageServer.shared.model.ProofMethod;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import de.haumacher.imageServer.shared.model.TotpSetup;
import de.haumacher.imageServer.shared.model.UserEntry;
import de.haumacher.imageServer.shared.model.UserList;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Test case for the ways a member signs a new browser in besides a code, and for one identity of a
 * member and a contact, see issue #233.
 *
 * <p>
 * A member sets up an authenticator app and passkeys with the requests a contact uses, and proves
 * e-mail addresses by a mailed code or a provider; each signs a browser that holds nothing in as
 * that member &mdash; on the sign-in form (<code>?action=pair</code>, and the proofs without a link)
 * and on any personal link, where a member's proof never creates or identifies a contact. An
 * address belongs to one principal at most.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestMemberSignIns extends PersonalLinkTestCase {

	private static final String PASSKEY_URL = "http://localhost:9100/valbum";

	private static final String ORIGIN = "http://localhost:9100";

	private static final String OIDC_URL = "https://photos.example.org/valbum";

	/** Alice's address; masked like every other address the group-link test types. */
	private static final String ALICES = "alice@web.de";

	private TestClock _clock;

	private Passkeys _passkeys;

	private CapturingMailer _mailer;

	private EmailProofs _proofs;

	private MockOidcIssuer _issuer;

	private OidcLogins _oidc;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_clock = new TestClock();
		_passkeys = new Passkeys(PASSKEY_URL, _clock);
		_mailer = new CapturingMailer();
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
		result.getAuth().getTotp().setClock(_clock);
		result.setPasskeys(_passkeys);
		result.setEmailProofs(_proofs);
		result.setOidcLogins(_oidc);
		return result;
	}

	// --- The sign-in form says what it may offer. ---

	public void testTheSignInFormIsToldTheWaysIn() throws Exception {
		AuthInfo anonymous = auth(get("/", "auth", null));
		assertEquals(List.of(TotpSignIns.METHOD, Passkeys.METHOD, EmailProofs.METHOD, "oidc:google"),
			anonymous.getSignInMethods().stream().map(ProofMethod::getName).toList());

		_passkeys = Passkeys.NONE;
		assertFalse("No passkey without a public address.", auth(get("/", "auth", null)).getSignInMethods()
			.stream().anyMatch(method -> method.getName().equals(Passkeys.METHOD)));
	}

	// --- An authenticator app. ---

	public void testAMemberSignsInWithTheirAuthenticatorApp() throws Exception {
		TotpSetup setup = setUpTotp(SharingFixture.ALICE);
		assertEquals("alice", setup.getAccount());
		ContactSignIns signIns = confirmTotp(SharingFixture.ALICE, setup);
		assertEquals(_clock.instant().toString(), signIns.getAuthenticator());
		assertEquals(signIns.getAuthenticator(), devices(SharingFixture.ALICE).getSignIns().getAuthenticator());

		_clock.advance(Duration.ofMinutes(1));
		FakeResponse signedIn = pair("{\"deviceName\":\"Laptop\",\"userName\":\"alice\",\"totpCode\":\""
			+ now(setup) + "\"}");
		assertEquals(signedIn.body(), 200, signedIn.status());
		PairResponse answer = PairResponse.readPairResponse(reader(body(signedIn)));
		assertEquals("alice", answer.getUserName());
		assertEquals("Laptop", answer.getDeviceName());

		AuthInfo info = auth(get("/", "auth", answer.getToken()));
		assertEquals("alice", info.getUserName());
		assertEquals(200, get("/", "json", answer.getToken()).status());
		assertTrue(devices(SharingFixture.ALICE).getDevices().stream()
			.anyMatch(device -> device.getName().equals("Laptop")));
	}

	public void testANameWithoutACodeIsToldWhatToEnter() throws Exception {
		for (String body : new String[] { "{\"userName\":\"nobody\",\"totpCode\":\"\"}",
			"{\"userName\":\"alice\"}" }) {
			FakeResponse refused = pair(body);
			assertEquals(body, HttpServletResponse.SC_BAD_REQUEST, refused.status());
			assertEquals(body, AuthService.MEMBER_CODE_REQUIRED, errorMessage(refused));
		}
		assertEquals("Nothing at all is told about the sign-in code, as before.", AuthService.CODE_REQUIRED,
			errorMessage(pair("{\"deviceName\":\"Phone\"}")));
	}

	public void testAnUnknownNameIsAnsweredLikeAWrongCode() throws Exception {
		TotpSetup setup = confirmedTotp(SharingFixture.ALICE);
		_clock.advance(Duration.ofMinutes(1));
		FakeResponse unknown = pair("{\"userName\":\"nobody\",\"totpCode\":\"" + now(setup) + "\"}");
		FakeResponse wrong = pair("{\"userName\":\"alice\",\"totpCode\":\"" + wrong(setup) + "\"}");
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, unknown.status());
		assertEquals(unknown.status(), wrong.status());
		assertEquals(unknown.body(), wrong.body());
		assertEquals(TotpSignIns.CODE_WRONG, errorMessage(unknown));
	}

	public void testCodesLockAreUsedOnceAndSignInOnlyTheirMember() throws Exception {
		TotpSetup pending = setUpTotp(SharingFixture.BOB);
		_clock.advance(Duration.ofMinutes(1));
		assertEquals("An unconfirmed app signs nobody in.", TotpSignIns.CODE_WRONG,
			errorMessage(pair("{\"userName\":\"bob\",\"totpCode\":\"" + now(pending) + "\"}")));

		TotpSetup alices = confirmedTotp(SharingFixture.ALICE);
		_clock.advance(Duration.ofMinutes(1));
		confirmTotp(SharingFixture.BOB, pending);
		_clock.advance(Duration.ofMinutes(1));
		assertEquals("Alice's code never signs Bob in.", TotpSignIns.CODE_WRONG,
			errorMessage(pair("{\"userName\":\"bob\",\"totpCode\":\"" + now(alices) + "\"}")));

		String code = now(alices);
		assertEquals(200, pair("{\"userName\":\"alice\",\"totpCode\":\"" + code + "\"}").status());
		assertEquals("Used once.", TotpSignIns.CODE_WRONG,
			errorMessage(pair("{\"userName\":\"alice\",\"totpCode\":\"" + code + "\"}")));

		_clock.advance(Duration.ofMinutes(1));
		for (int n = 0; n < TotpSignIns.FAILURES; n++) {
			pair("{\"userName\":\"alice\",\"totpCode\":\"" + wrong(alices) + "\"}");
		}
		FakeResponse locked = pair("{\"userName\":\"alice\",\"totpCode\":\"" + now(alices) + "\"}");
		assertEquals(429, locked.status());
		assertEquals(TotpSignIns.LOCKED, errorMessage(locked));
		assertEquals(Long.toString(TotpSignIns.LOCK.toSeconds()), locked.header("Retry-After"));
		// Her own app is locked by the guesses at her name.
		_clock.advance(Duration.ofMinutes(1));
		assertEquals(429, pair("{\"userName\":\"alice\",\"totpCode\":\"" + now(alices) + "\"}").status());
	}

	public void testTheAuthenticatorAppNamesTheMemberByAnAddressToo() throws Exception {
		proveOwn(SharingFixture.ALICE, ALICES);
		TotpSetup setup = confirmedTotp(SharingFixture.ALICE);
		_clock.advance(Duration.ofMinutes(1));
		FakeResponse signedIn = pair("{\"userName\":\"Alice@Web.de\",\"totpCode\":\"" + now(setup) + "\"}");
		assertEquals(signedIn.body(), 200, signedIn.status());
		assertEquals("alice", PairResponse.readPairResponse(reader(body(signedIn))).getUserName());
	}

	public void testAPendingInvitationSetsNothingUpAndSignsNothingIn() throws Exception {
		// An invitation nobody accepted: a pending user, with the inviter's memento as its only name.
		FakeResponse invited = postAs("/", "invite",
			"{\"role\":\"view\",\"expires\":\"\",\"note\":\"\",\"recipient\":\"Grandma\"}", SharingFixture.ALICE, null);
		assertEquals(invited.body(), 200, invited.status());
		InvitationCreated invitation = InvitationCreated.readInvitationCreated(reader(body(invited)));
		String id = invitation.getInvitation().getId();
		String token = invitation.getToken();
		UserStore.User pending = servlet().getAuth().getUsers().getInvited(id);
		assertTrue(pending.isPending());

		// Its code is no sign-in: it sets nothing up.
		for (String action : new String[] { "totp-setup", "passkey-register-start" }) {
			FakeResponse refused = postAs("/", action, "{}", token, null);
			assertEquals(action, HttpServletResponse.SC_UNAUTHORIZED, refused.status());
		}
		// Nor does the store let anything be set up for it.
		assertFalse(servlet().getAuth().getUsers().change(pending,
			signIns -> signIns.startTotp(Totp.newSecret(new java.security.SecureRandom()), _clock.instant())));

		// Even a pending seat that carries every way in (a store edited by hand) signs nobody in:
		// an authenticator app, the passkey Bob made, an address.
		SoftwareAuthenticator phone = new SoftwareAuthenticator(ORIGIN);
		registerPasskey(phone, SharingFixture.BOB, null);
		String secret = Totp.newSecret(new java.security.SecureRandom());
		handOver(id, secret, "grandma@web.de");
		restartServer();
		pending = servlet().getAuth().getUsers().getInvited(id);
		assertEquals(secret, pending.getSignIns().getAuthenticator().getSecret());
		assertEquals(1, pending.getSignIns().getPasskeys().size());
		_clock.advance(Duration.ofMinutes(1));
		String code = Totp.code(Totp.base32Decode(secret), Totp.step(_clock.instant()));

		for (String name : new String[] { "", "Grandma", "grandma@web.de" }) {
			FakeResponse totp = pair("{\"userName\":\"" + name + "\",\"totpCode\":\"" + code + "\"}");
			assertEquals(name, TotpSignIns.CODE_WRONG, errorMessage(totp));
		}
		assertEquals(Passkeys.MEMBER_SIGN_IN_REFUSED, errorMessage(passkeySignIn(phone, "Grandma's")));

		int mails = _mailer.sent().size();
		assertEquals(200, postAs("/", "prove-email", "{\"address\":\"grandma@web.de\"}", null, null).status());
		assertEquals("Nothing is mailed for a pending seat.", mails, _mailer.sent().size());
		FakeResponse mailed = postAs("/", "verify-email", "{\"address\":\"grandma@web.de\",\"code\":\"123456\"}",
			null, null);
		assertFalse(mailed.body(), mailed.status() == 200);

		_issuer.next("grandma@web.de", Boolean.TRUE, "Grandma");
		FakeResponse google = oidcSignIn(null, "");
		assertEquals(HttpServletResponse.SC_FORBIDDEN, google.status());
		assertEquals(AddressProof.NO_MEMBER_ADDRESS, errorMessage(google));

		ShareLinkCreated open = created("/" + SharingFixture.PUBLIC + "/");
		FakeResponse onLink = postAs("/", "totp-verify",
			"{\"code\":\"" + code + "\",\"address\":\"grandma@web.de\"}", open.getToken(), null);
		assertEquals(TotpSignIns.CODE_WRONG, errorMessage(onLink));

		assertTrue("No path made a device for the pending seat.",
			servlet().getAuth().getUsers().getInvited(id).getDevices().isEmpty());
		assertTrue(servlet().getAuth().getUsers().getInvited(id).isPending());
	}

	/**
	 * Writes into <code>users.json</code>, behind the server's back, an authenticator app of the
	 * given secret, the given address, and Bob's passkeys onto the pending user of the given
	 * invitation.
	 */
	private void handOver(String invitation, String secret, String address) throws Exception {
		Path file = _base.resolve(UserStore.DIRECTORY_NAME).resolve(UserStore.FILE_NAME);
		String json = Files.readString(file);
		int bob = json.indexOf("\"name\":\"bob\"");
		int start = json.indexOf("\"passkeys\":[", bob);
		int end = closing(json, start + "\"passkeys\":".length());
		String passkeys = json.substring(start, end + 1);
		json = json.substring(0, start - 1) + json.substring(end + 1);
		String marker = "\"invitation\":\"" + invitation + "\"";
		int seat = json.indexOf(marker);
		int devices = json.indexOf("\"devices\":[]", seat) + "\"devices\":[]".length();
		json = json.substring(0, devices) + ",\"addresses\":[{\"kind\":\"email\",\"value\":\"" + address
			+ "\",\"proven\":true}],\"totp\":{\"secret\":\"" + secret + "\",\"since\":\"\",\"lastStep\":0},"
			+ passkeys + json.substring(devices);
		Files.writeString(file, json);
	}

	/** The index of the bracket closing the one at the given index. */
	private static int closing(String text, int open) {
		int depth = 0;
		for (int n = open; n < text.length(); n++) {
			char c = text.charAt(n);
			if (c == '[' || c == '{') {
				depth++;
			} else if (c == ']' || c == '}') {
				depth--;
				if (depth == 0) {
					return n;
				}
			}
		}
		throw new AssertionError("Unbalanced: " + text);
	}

	public void testAMemberRemovesTheirAppAndTheAdministratorABobs() throws Exception {
		TotpSetup setup = confirmedTotp(SharingFixture.ALICE);
		FakeResponse removed = postAs("/", "remove-sign-in", "{\"method\":\"totp\"}", SharingFixture.ALICE, null);
		assertEquals(removed.body(), 200, removed.status());
		assertEquals("", ContactSignIns.readContactSignIns(reader(body(removed))).getAuthenticator());
		_clock.advance(Duration.ofMinutes(1));
		assertEquals(TotpSignIns.CODE_WRONG,
			errorMessage(pair("{\"userName\":\"alice\",\"totpCode\":\"" + now(setup) + "\"}")));

		confirmedTotp(SharingFixture.BOB);
		assertFalse(user("bob").getAuthenticator().isEmpty());
		String body = "{\"user\":\"bob\",\"method\":\"totp\"}";
		FakeResponse byBob = postAs("/", "remove-user-sign-in", body, SharingFixture.BOB, null);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, byBob.status());
		FakeResponse byAdmin = postAs("/", "remove-user-sign-in", body, SharingFixture.ALICE, null);
		assertEquals(byAdmin.body(), 200, byAdmin.status());
		assertEquals("", entry(UserList.readUserList(reader(body(byAdmin))), "bob").getAuthenticator());
		assertEquals(HttpServletResponse.SC_NOT_FOUND,
			postAs("/", "remove-user-sign-in", body, SharingFixture.ALICE, null).status());
	}

	// --- Passkeys. ---

	public void testAMemberSignsInWithAPasskeyWithoutAName() throws Exception {
		SoftwareAuthenticator phone = new SoftwareAuthenticator(ORIGIN);
		ContactSignIns signIns = registerPasskey(phone, SharingFixture.ALICE, null);
		assertEquals(1, signIns.getPasskeys().size());
		assertEquals(1, user("alice").getPasskeys().size());

		_clock.advance(Duration.ofMinutes(1));
		FakeResponse signedIn = passkeySignIn(phone, "Tablet");
		assertEquals(signedIn.body(), 200, signedIn.status());
		PairResponse answer = PairResponse.readPairResponse(reader(body(signedIn)));
		assertEquals("alice", answer.getUserName());
		assertEquals("alice", auth(get("/", "auth", answer.getToken())).getUserName());
		assertTrue(devices(SharingFixture.ALICE).getDevices().stream()
			.anyMatch(device -> device.getName().equals("Tablet")));

		String id = signIns.getPasskeys().get(0).getId();
		FakeResponse removed = postAs("/", "remove-sign-in", "{\"method\":\"passkey\",\"id\":\"" + id + "\"}",
			SharingFixture.ALICE, null);
		assertEquals(removed.body(), 200, removed.status());
		assertEquals(HttpServletResponse.SC_FORBIDDEN, passkeySignIn(phone, "Again").status());
	}

	public void testAContactsPasskeySignsNobodyInAsAMember() throws Exception {
		ShareLinkCreated created = created(ZOO, phone("Klaus", KLAUS));
		String token = tokenOf(created, "Klaus");
		SoftwareAuthenticator klaus = new SoftwareAuthenticator(ORIGIN);
		registerPasskey(klaus, token, credential(token));
		FakeResponse refused = passkeySignIn(klaus, "Phone");
		assertEquals(HttpServletResponse.SC_FORBIDDEN, refused.status());
		assertEquals(Passkeys.MEMBER_SIGN_IN_REFUSED, errorMessage(refused));
	}

	public void testAMembersPasskeySignsTheMemberInOnALink() throws Exception {
		ShareLinkCreated created = created(ZOO, phone("Klaus", KLAUS));
		String token = tokenOf(created, "Klaus");
		credential(token);
		SoftwareAuthenticator alices = new SoftwareAuthenticator(ORIGIN);
		registerPasskey(alices, SharingFixture.ALICE, null);
		String before = contactsFile();

		PasskeyOptions options = options(postAs("/", "passkey-start", "{}", token, null));
		FakeResponse verified = postAs("/", "passkey-verify", "{\"ticket\":\"" + options.getTicket()
			+ "\",\"response\":" + quote(alices.get(options.getOptions())) + ",\"deviceName\":\"Kiosk\"}", token, null);
		ContactCredential answer = credentialOf(verified);
		assertEquals("", answer.getCredential());
		assertNull(answer.getContact());
		assertEquals("alice", answer.getMember().getUserName());
		assertEquals("Kiosk", answer.getMember().getDeviceName());
		assertEquals("alice", auth(get("/", "auth", answer.getMember().getToken())).getUserName());
		assertEquals("No contact was made or identified.", before, contactsFile());
	}

	public void testARegistrationTicketNeverCrossesBetweenAContactAndAMember() throws Exception {
		ShareLinkCreated created = created(ZOO, phone("Klaus", KLAUS));
		String token = tokenOf(created, "Klaus");
		String credential = credential(token);
		SoftwareAuthenticator authenticator = new SoftwareAuthenticator(ORIGIN);

		PasskeyOptions contacts = options(postAs("/", "passkey-register-start", "{}", token, credential));
		FakeResponse asMember = postAs("/", "passkey-register", "{\"ticket\":\"" + contacts.getTicket()
			+ "\",\"response\":" + quote(authenticator.create(contacts.getOptions())) + "}", SharingFixture.ALICE, null);
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, asMember.status());
		assertEquals(Passkeys.TICKET_UNKNOWN, errorMessage(asMember));

		PasskeyOptions members = options(postAs("/", "passkey-register-start", "{}", SharingFixture.ALICE, null));
		FakeResponse asContact = postAs("/", "passkey-register", "{\"ticket\":\"" + members.getTicket()
			+ "\",\"response\":" + quote(authenticator.create(members.getOptions())) + "}", token, credential);
		assertEquals(Passkeys.TICKET_UNKNOWN, errorMessage(asContact));
	}

	// --- Proven addresses: one identity. ---

	public void testAMemberProvesAnAddressAndSignsInWithItOnAnOpenLink() throws Exception {
		proveOwn(SharingFixture.ALICE, ALICES);
		ContactSignIns signIns = devices(SharingFixture.ALICE).getSignIns();
		assertEquals(ALICES, signIns.getAddresses().get(0).getValue());
		assertTrue(signIns.getAddresses().get(0).isProven());
		assertEquals(ALICES, user("alice").getAddresses().get(0).getValue());

		ShareLinkCreated open = created("/" + SharingFixture.PUBLIC + "/");
		String before = contactsFile();
		assertEquals(200, postAs("/", "prove-email", "{\"address\":\"Alice@Web.de\"}", open.getToken(), null).status());
		FakeResponse verified = postAs("/", "verify-email", "{\"address\":\"alice@web.de\",\"code\":\""
			+ _mailer.lastCode(ALICES) + "\",\"remember\":true,\"deviceName\":\"Library PC\"}", open.getToken(), null);
		ContactCredential answer = credentialOf(verified);
		assertEquals("alice", answer.getMember().getUserName());
		assertEquals("Library PC", answer.getMember().getDeviceName());
		assertEquals("", answer.getCredential());
		assertEquals("contacts.json is unchanged: nobody was entered.", before, contactsFile());
	}

	public void testAMembersAddressOnTheGroupLinkSignsInTheMemberAndAnswersAlike() throws Exception {
		proveOwn(SharingFixture.ALICE, ALICES);
		ShareLinkCreated group = created(ZOO, email("Anke", "anke@web.de"), email("Onkel Hans", "hans@web.de"));
		String token = group.getToken();
		int mails = _mailer.sent().size();
		FakeResponse member = postAs("/", "prove-email", "{\"address\":\"" + ALICES + "\"}", token, null);
		FakeResponse recipient = postAs("/", "prove-email", "{\"address\":\"anke@web.de\"}", token, null);
		FakeResponse stranger = postAs("/", "prove-email", "{\"address\":\"ahmed@web.de\"}", token, null);
		assertEquals(member.body(), 200, member.status());
		assertEquals(member.body(), recipient.body());
		assertEquals(member.body(), stranger.body());
		assertEquals("The member and the recipient are mailed, the stranger is not.", mails + 2,
			_mailer.sent().size());

		ContactCredential answer = credentialOf(postAs("/", "verify-email", "{\"address\":\"" + ALICES
			+ "\",\"code\":\"" + _mailer.lastCode(ALICES) + "\"}", token, null));
		assertEquals("alice", answer.getMember().getUserName());
	}

	public void testAnAddressBelongsToOnePrincipal() throws Exception {
		// A contact's address is refused for a member.
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA));
		assertEquals(200, postAs("/", "prove-email", "{\"address\":\"" + PETRA + "\"}", SharingFixture.ALICE, null)
			.status());
		FakeResponse refused = postAs("/", "verify-email", "{\"address\":\"" + PETRA + "\",\"code\":\""
			+ _mailer.lastCode("petra@gmx.de") + "\"}", SharingFixture.ALICE, null);
		assertEquals(HttpServletResponse.SC_CONFLICT, refused.status());
		assertEquals(UserStore.contactAddress("petra@gmx.de"), errorMessage(refused));
		assertTrue(user("alice").getAddresses().isEmpty());
		assertNotNull(created);

		// A member's address is refused on a contact.
		proveOwn(SharingFixture.ALICE, ALICES);
		FakeResponse share = sharePersonal(ZOO, SharingFixture.BOB, email("Alice", ALICES));
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, share.status());
		assertEquals(ContactStore.memberAddress(ALICES, "alice"), errorMessage(share));

		// And another member's.
		assertEquals(200, postAs("/", "prove-email", "{\"address\":\"" + ALICES + "\"}", SharingFixture.BOB, null)
			.status());
		FakeResponse taken = postAs("/", "verify-email", "{\"address\":\"" + ALICES + "\",\"code\":\""
			+ _mailer.lastCode(ALICES) + "\"}", SharingFixture.BOB, null);
		assertEquals(HttpServletResponse.SC_CONFLICT, taken.status());
		assertEquals(UserStore.otherMemberAddress(ALICES), errorMessage(taken));

		// Removed, it is free again.
		FakeResponse removed = postAs("/", "remove-sign-in", "{\"method\":\"email\",\"id\":\"" + ALICES + "\"}",
			SharingFixture.ALICE, null);
		assertEquals(removed.body(), 200, removed.status());
		assertTrue(ContactSignIns.readContactSignIns(reader(body(removed))).getAddresses().isEmpty());
	}

	public void testTheSignInFormMailsACodeWithoutALink() throws Exception {
		proveOwn(SharingFixture.ALICE, ALICES);
		int mails = _mailer.sent().size();
		FakeResponse member = postAs("/", "prove-email", "{\"address\":\"" + ALICES + "\"}", null, null);
		FakeResponse nobody = postAs("/", "prove-email", "{\"address\":\"ahmed@web.de\"}", null, null);
		assertEquals(member.body(), 200, member.status());
		assertEquals("The same answer for nobody's address.", member.body(), nobody.body());
		assertEquals(mails + 1, _mailer.sent().size());

		FakeResponse wrongMember = postAs("/", "verify-email", "{\"address\":\"" + ALICES + "\",\"code\":\""
			+ otherThan(_mailer.lastCode(ALICES)) + "\"}", null, null);
		FakeResponse wrongNobody = postAs("/", "verify-email",
			"{\"address\":\"ahmed@web.de\",\"code\":\"123456\"}", null, null);
		assertEquals(wrongMember.status(), wrongNobody.status());
		assertEquals(errorMessage(wrongMember), errorMessage(wrongNobody));

		ContactCredential answer = credentialOf(postAs("/", "verify-email", "{\"address\":\"" + ALICES
			+ "\",\"code\":\"" + _mailer.lastCode(ALICES) + "\",\"deviceName\":\"Hotel\"}", null, null));
		assertEquals("alice", answer.getMember().getUserName());
		assertEquals("alice", auth(get("/", "auth", answer.getMember().getToken())).getUserName());
	}

	public void testAMemberLinksGoogleAndSignsInWithItAnywhere() throws Exception {
		_issuer.next("Alice@Web.DE", Boolean.TRUE, "Alice A.");
		FakeResponse linked = oidcSignIn(SharingFixture.ALICE, "");
		assertEquals(linked.body(), 200, linked.status());
		assertEquals(ALICES, user("alice").getAddresses().get(0).getValue());

		// Without a link, on the sign-in form.
		_issuer.next("alice@web.de", Boolean.TRUE, "Alice A.");
		ContactCredential form = credentialOf(oidcSignIn(null, "Train"));
		assertEquals("alice", form.getMember().getUserName());
		assertEquals("Train", form.getMember().getDeviceName());

		// On an open link: the member, and no contact.
		ShareLinkCreated open = created("/" + SharingFixture.PUBLIC + "/");
		String before = contactsFile();
		_issuer.next("alice@web.de", Boolean.TRUE, "Alice A.");
		ContactCredential onLink = credentialOf(oidcSignIn(open.getToken(), "Cafe"));
		assertEquals("alice", onLink.getMember().getUserName());
		assertEquals(before, contactsFile());

		// Nobody's address signs nobody in on the form.
		_issuer.next("ahmed@web.de", Boolean.TRUE, "Ahmed");
		FakeResponse nobody = oidcSignIn(null, "");
		assertEquals(HttpServletResponse.SC_FORBIDDEN, nobody.status());
		assertEquals(AddressProof.NO_MEMBER_ADDRESS, errorMessage(nobody));
	}

	public void testAMembersCodeOnAnOpenLinkSignsInTheMember() throws Exception {
		proveOwn(SharingFixture.ALICE, ALICES);
		TotpSetup setup = confirmedTotp(SharingFixture.ALICE);
		ShareLinkCreated open = created("/" + SharingFixture.PUBLIC + "/");
		String before = contactsFile();
		_clock.advance(Duration.ofMinutes(1));
		ContactCredential answer = credentialOf(postAs("/", "totp-verify", "{\"code\":\"" + now(setup)
			+ "\",\"address\":\"" + ALICES + "\"}", open.getToken(), null));
		assertEquals("alice", answer.getMember().getUserName());
		assertEquals(before, contactsFile());
	}

	// --- Helpers. ---

	private FakeResponse pair(String body) throws Exception {
		return postAs("/", "pair", body, null, null);
	}

	private TotpSetup setUpTotp(String token) throws Exception {
		FakeResponse response = postAs("/", "totp-setup", "{}", token, null);
		assertEquals(response.body(), 200, response.status());
		return TotpSetup.readTotpSetup(reader(body(response)));
	}

	private ContactSignIns confirmTotp(String token, TotpSetup setup) throws Exception {
		FakeResponse confirmed = postAs("/", "totp-confirm", "{\"code\":\"" + now(setup) + "\"}", token, null);
		assertEquals(confirmed.body(), 200, confirmed.status());
		return ContactSignIns.readContactSignIns(reader(body(confirmed)));
	}

	private TotpSetup confirmedTotp(String token) throws Exception {
		TotpSetup setup = setUpTotp(token);
		confirmTotp(token, setup);
		return setup;
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

	private static String otherThan(String code) {
		return code.equals("000000") ? "000001" : "000000";
	}

	private DeviceList devices(String token) throws Exception {
		FakeResponse response = get("/", "devices", token);
		assertEquals(response.body(), 200, response.status());
		return DeviceList.readDeviceList(reader(body(response)));
	}

	private UserEntry user(String name) throws Exception {
		return entry(UserList.readUserList(reader(body(get("/", "users", SharingFixture.ALICE)))), name);
	}

	private static UserEntry entry(UserList list, String name) {
		for (UserEntry user : list.getUsers()) {
			if (user.getName().equals(name)) {
				return user;
			}
		}
		fail("No user " + name);
		return null;
	}

	private ContactSignIns registerPasskey(SoftwareAuthenticator authenticator, String token, String credential)
			throws Exception {
		PasskeyOptions options = options(postAs("/", "passkey-register-start", "{}", token, credential));
		FakeResponse registered = postAs("/", "passkey-register", "{\"ticket\":\"" + options.getTicket()
			+ "\",\"response\":" + quote(authenticator.create(options.getOptions())) + "}", token, credential);
		assertEquals(registered.body(), 200, registered.status());
		return ContactSignIns.readContactSignIns(reader(body(registered)));
	}

	/** The sign-in form's passkey: a ceremony that names nobody, finished by the pairing. */
	private FakeResponse passkeySignIn(SoftwareAuthenticator authenticator, String deviceName) throws Exception {
		PasskeyOptions options = options(postAs("/", "passkey-start", "{}", null, null));
		return pair("{\"deviceName\":\"" + deviceName + "\",\"passkey\":{\"ticket\":\"" + options.getTicket()
			+ "\",\"response\":" + quote(authenticator.get(options.getOptions())) + "}}");
	}

	private static PasskeyOptions options(FakeResponse response) throws Exception {
		assertEquals(response.body(), 200, response.status());
		return PasskeyOptions.readPasskeyOptions(reader(body(response)));
	}

	private static String quote(String text) {
		return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	/** A member proves an address of their own: the mailed code, typed back. */
	private void proveOwn(String token, String address) throws Exception {
		FakeResponse sent = postAs("/", "prove-email", "{\"address\":\"" + address + "\"}", token, null);
		assertEquals(sent.body(), 200, sent.status());
		FakeResponse verified = postAs("/", "verify-email", "{\"address\":\"" + address + "\",\"code\":\""
			+ _mailer.lastCode(address) + "\"}", token, null);
		assertEquals(verified.body(), 200, verified.status());
		_clock.advance(Duration.ofMinutes(1));
	}

	/** The whole sign-in with the provider, as a browser does it: start, provider, callback, exchange. */
	private FakeResponse oidcSignIn(String token, String deviceName) throws Exception {
		FakeResponse start = postAs("/", "oidc-start", "{\"provider\":\"google\"}", token, null);
		assertEquals(start.body(), 200, start.status());
		OidcStarted started = OidcStarted.readOidcStarted(reader(body(start)));
		String location = _oidc.callback(_issuer.authorize(started.getUrl()));
		if (token == null || token.equals(SharingFixture.ALICE)) {
			assertTrue("A member's sign-in returns to the application: " + location,
				location.startsWith(OIDC_URL + "/#" + OidcLogins.FRAGMENT + "="));
		}
		String code = location.substring(location.indexOf("#" + OidcLogins.FRAGMENT + "=") + OidcLogins.FRAGMENT.length()
			+ 2);
		return postAs("/", "oidc-exchange", "{\"code\":\"" + code + "\",\"binding\":\"" + started.getBinding()
			+ "\",\"deviceName\":\"" + deviceName + "\"}", token, null);
	}

	private static ContactCredential credentialOf(FakeResponse response) throws Exception {
		assertEquals(response.body(), 200, response.status());
		return ContactCredential.readContactCredential(reader(body(response)));
	}

	private String contactsFile() throws Exception {
		Path file = _base.resolve(UserStore.DIRECTORY_NAME).resolve(ContactStore.FILE_NAME);
		return Files.exists(file) ? Files.readString(file) : "";
	}
}
