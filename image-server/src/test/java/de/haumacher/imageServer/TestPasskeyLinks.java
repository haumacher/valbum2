/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.ContactStore;
import de.haumacher.imageServer.mail.TestEmailProofs.TestClock;
import de.haumacher.imageServer.passkeys.Passkeys;
import de.haumacher.imageServer.passkeys.SoftwareAuthenticator;
import de.haumacher.imageServer.shared.model.Contact;
import de.haumacher.imageServer.shared.model.ContactCredential;
import de.haumacher.imageServer.shared.model.ContactSignIns;
import de.haumacher.imageServer.shared.model.PasskeyOptions;
import de.haumacher.imageServer.shared.model.ProofMethod;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Test case for passkeys on personal share links, see issue #204: registered by a contact in their
 * session, offered only where somebody has one and the server has a public address, signing in on
 * another browser, never across contacts or spaces, removed by the contact or by a member.
 *
 * <p>
 * The browser is the software authenticator of webauthn4j's test support
 * ({@link SoftwareAuthenticator}).
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestPasskeyLinks extends PersonalLinkTestCase {

	private static final String PUBLIC_URL = "http://localhost:9100/valbum";

	private static final String ORIGIN = "http://localhost:9100";

	private TestClock _clock;

	private Passkeys _passkeys;

	private Path _otherBase;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_clock = new TestClock();
		_passkeys = new Passkeys(PUBLIC_URL, _clock);
	}

	@Override
	protected void tearDown() throws Exception {
		if (_otherBase != null) {
			try (Stream<Path> files = Files.walk(_otherBase)) {
				files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
			}
		}
		super.tearDown();
	}

	@Override
	protected ImageServlet servlet() throws Exception {
		ImageServlet result = super.servlet();
		result.setPasskeys(_passkeys);
		return result;
	}

	// --- The relying party. ---

	public void testTheRelyingPartyIsTheHostOfThePublicAddress() {
		assertEquals("localhost", _passkeys.getRpId());
		assertEquals(ORIGIN, _passkeys.getOrigin());
		Passkeys https = new Passkeys("https://Photos.Example.org:443/valbum", _clock);
		assertEquals("photos.example.org", https.getRpId());
		assertEquals("https://photos.example.org", https.getOrigin());
		assertEquals("https://photos.example.org:8443",
			new Passkeys("https://photos.example.org:8443/valbum", _clock).getOrigin());
		assertFalse(Passkeys.NONE.isAvailable());
	}

	public void testWithoutAPublicAddressNothingIsOffered() throws Exception {
		_passkeys = Passkeys.NONE;
		ShareLinkCreated created = created(ZOO, phone("Klaus", KLAUS));
		String token = tokenOf(created, "Klaus");
		String credential = credential(token);
		assertFalse(auth(getAs("/", "auth", token, credential)).getShare().getSignIns().isPasskeysOffered());
		FakeResponse start = postAs("/", "passkey-register-start", "{}", token, credential);
		assertEquals(HttpServletResponse.SC_NOT_IMPLEMENTED, start.status());
		assertEquals(Passkeys.NOT_CONFIGURED, errorMessage(start));
		FakeResponse signIn = postAs("/", "passkey-start", "{}", token, null);
		assertEquals(HttpServletResponse.SC_NOT_IMPLEMENTED, signIn.status());
	}

	// --- Registering and signing in. ---

	public void testARecipientRegistersAPasskeyAndSignsInWithItElsewhere() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA), phone("Klaus", KLAUS));
		String token = tokenOf(created, "Klaus");
		String credential = credential(token);
		assertTrue(auth(getAs("/", "auth", token, credential)).getShare().getSignIns().isPasskeysOffered());
		assertTrue("Never offered to somebody who has none.", methods(laterOpen(token)).isEmpty());

		SoftwareAuthenticator phone = new SoftwareAuthenticator(ORIGIN);
		ContactSignIns signIns = register(phone, token, credential);
		assertEquals(1, signIns.getPasskeys().size());
		assertEquals(_clock.instant().toString(), signIns.getPasskeys().get(0).getCreated());
		assertEquals("", signIns.getPasskeys().get(0).getLastUsed());
		assertEquals(List.of(Passkeys.METHOD), methods(laterOpen(token)));

		_clock.advance(Duration.ofMinutes(1));
		FakeResponse signedIn = signIn(phone, token, null);
		assertEquals(signedIn.body(), 200, signedIn.status());
		ContactCredential second = ContactCredential.readContactCredential(reader(body(signedIn)));
		assertEquals(contactOf(created, "Klaus"), second.getContact().getId());
		assertFalse(second.getCredential().equals(credential));
		assertEquals(200, getAs("/", "json", token, second.getCredential()).status());

		// The members see the passkey, when it was made and last used, and nothing of its key.
		Contact klaus = contact(contactOf(created, "Klaus"));
		assertEquals(1, klaus.getPasskeys().size());
		assertEquals(_clock.instant().toString(), klaus.getPasskeys().get(0).getLastUsed());
		String stored = Files.readString(_base.resolve(".valbum").resolve(ContactStore.FILE_NAME));
		assertTrue(stored, stored.contains("\"passkeys\""));
	}

	public void testATicketIsTakenOnceAndOnlyWhereItWasMade() throws Exception {
		ShareLinkCreated created = created(ZOO, phone("Klaus", KLAUS), email("Tante Petra", PETRA));
		String klaus = tokenOf(created, "Klaus");
		String petra = tokenOf(created, "Tante Petra");
		SoftwareAuthenticator phone = new SoftwareAuthenticator(ORIGIN);
		register(phone, klaus, credential(klaus));
		credential(petra);

		PasskeyOptions options = options(postAs("/", "passkey-start", "{}", klaus, null));
		String answer = phone.get(options.getOptions());
		FakeResponse elsewhere = verify(petra, null, options.getTicket(), answer);
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, elsewhere.status());
		assertEquals(Passkeys.TICKET_UNKNOWN, errorMessage(elsewhere));
		FakeResponse again = verify(klaus, null, options.getTicket(), answer);
		assertEquals("Taken by the first answer, whatever came of it.", Passkeys.TICKET_UNKNOWN, errorMessage(again));

		PasskeyOptions late = options(postAs("/", "passkey-start", "{}", klaus, null));
		String lateAnswer = phone.get(late.getOptions());
		_clock.advance(Passkeys.LIFETIME.plusSeconds(1));
		assertEquals(Passkeys.TICKET_UNKNOWN, errorMessage(verify(klaus, null, late.getTicket(), lateAnswer)));
	}

	public void testAPasskeyOfAnotherOriginIsRefused() throws Exception {
		ShareLinkCreated created = created(ZOO, phone("Klaus", KLAUS));
		String token = tokenOf(created, "Klaus");
		String credential = credential(token);
		SoftwareAuthenticator phishing = new SoftwareAuthenticator("http://localhost:9999");
		PasskeyOptions options = options(postAs("/", "passkey-register-start", "{}", token, credential));
		FakeResponse refused = postAs("/", "passkey-register", body(options.getTicket(), phishing.create(options.getOptions())),
			token, credential);
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, refused.status());
		assertEquals(Passkeys.REGISTRATION_FAILED, errorMessage(refused));

		SoftwareAuthenticator phone = new SoftwareAuthenticator(ORIGIN);
		register(phone, token, credential);
		phone.setOrigin("http://localhost:9999");
		FakeResponse signIn = signIn(phone, token, null);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, signIn.status());
		assertEquals(Passkeys.SIGN_IN_REFUSED, errorMessage(signIn));
	}

	public void testAPasskeyOfAnotherContactIsRefused() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA), phone("Klaus", KLAUS));
		String petra = tokenOf(created, "Tante Petra");
		String klaus = tokenOf(created, "Klaus");
		SoftwareAuthenticator petras = new SoftwareAuthenticator(ORIGIN);
		register(petras, petra, credential(petra));
		credential(klaus);

		FakeResponse refused = signIn(petras, klaus, null);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, refused.status());
		assertEquals(Passkeys.SIGN_IN_REFUSED, errorMessage(refused));
		assertEquals(200, signIn(petras, petra, null).status());
	}

	public void testTheGroupLinkAnswersEveryFailureAlike() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA), email("Onkel Hans", "hans@web.de"));
		String group = created.getToken();
		assertFalse(methods(get("/", "json", group)).contains(Passkeys.METHOD));

		String petra = tokenOf(created, "Tante Petra");
		SoftwareAuthenticator petras = new SoftwareAuthenticator(ORIGIN);
		register(petras, petra, credential(petra));
		assertTrue(methods(get("/", "json", group)).contains(Passkeys.METHOD));

		// Vera is a contact of the space, with a passkey, but no recipient of this link.
		ShareLinkCreated other = created("/" + SharingFixture.PUBLIC + "/", email("Vera", "vera@web.de"));
		String vera = tokenOf(other, "Vera");
		String verasCredential = credential(vera);
		SoftwareAuthenticator veras = new SoftwareAuthenticator(ORIGIN);
		register(veras, vera, verasCredential);
		// A passkey the authenticator made, which the server never stored.
		SoftwareAuthenticator stranger = new SoftwareAuthenticator(ORIGIN);
		stranger.create(options(postAs("/", "passkey-register-start", "{}", vera, verasCredential)).getOptions());

		FakeResponse notARecipient = signIn(veras, group, null);
		FakeResponse unknown = signIn(stranger, group, null);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, notARecipient.status());
		assertEquals(notARecipient.status(), unknown.status());
		assertEquals(notARecipient.body(), unknown.body());

		FakeResponse signedIn = signIn(petras, group, null);
		assertEquals(signedIn.body(), 200, signedIn.status());
		assertEquals(contactOf(created, "Tante Petra"),
			ContactCredential.readContactCredential(reader(body(signedIn))).getContact().getId());
	}

	public void testAnOpenLinkLetsAnyContactWithAPasskeyIn() throws Exception {
		ShareLinkCreated addressed = created(ZOO, email("Tante Petra", PETRA));
		String petra = tokenOf(addressed, "Tante Petra");
		SoftwareAuthenticator petras = new SoftwareAuthenticator(ORIGIN);
		register(petras, petra, credential(petra));
		ShareLinkCreated open = created("/" + SharingFixture.PUBLIC + "/");
		assertTrue(methods(get("/", "json", open.getToken())).contains(Passkeys.METHOD));
		FakeResponse signedIn = signIn(petras, open.getToken(), null);
		assertEquals(signedIn.body(), 200, signedIn.status());
	}

	public void testAPasskeyOfAnotherSpaceIsRefused() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA));
		String petra = tokenOf(created, "Tante Petra");
		SoftwareAuthenticator petras = new SoftwareAuthenticator(ORIGIN);
		register(petras, petra, credential(petra));

		// The same server hosts a second space, with a Petra of its own; one relying party serves both.
		_otherBase = Files.createTempDirectory("valbum-passkey-other");
		SharingFixture.create(_otherBase);
		ImageServlet other = new ImageServlet(_otherBase.toFile(),
			new AuthService(_authMode, _otherBase, _inviteMode), "other", null);
		other.init();
		other.setPasskeys(_passkeys);
		try {
			FakeResponse share = new FakeResponse();
			other.doPost(TestImageServletPut.request(ZOO, "application/json",
				personalBody("Party", email("Tante Petra", PETRA)).getBytes(java.nio.charset.StandardCharsets.UTF_8),
				headers(SharingFixture.ALICE, null), Map.of("action", "share")), share.response());
			assertEquals(share.body(), 200, share.status());
			String token = tokenOf(created(share), "Tante Petra");
			assertEquals(200, post(other, "identify", "{\"remember\":true}", token).status());

			FakeResponse start = post(other, "passkey-start", "{}", token);
			assertEquals("Nobody of that space has a passkey: it is not even offered.", HttpServletResponse.SC_OK,
				start.status());
			PasskeyOptions options = options(start);
			FakeResponse refused = post(other, "passkey-verify", body(options.getTicket(), petras.get(options.getOptions())),
				token);
			assertEquals(HttpServletResponse.SC_FORBIDDEN, refused.status());
			assertEquals(Passkeys.SIGN_IN_REFUSED, errorMessage(refused));

			// A ticket of the first space opens nothing in the second.
			PasskeyOptions first = options(postAs("/", "passkey-start", "{}", petra, null));
			FakeResponse crossed = post(other, "passkey-verify", body(first.getTicket(), petras.get(first.getOptions())),
				token);
			assertEquals(Passkeys.TICKET_UNKNOWN, errorMessage(crossed));
		} finally {
			other.destroy();
		}
	}

	public void testOnlyARecognisedContactRegisters() throws Exception {
		// A signed-in member registers their own since issue #233, see TestMemberSignIns.
		FakeResponse link = post("/", "{}", zooToken("view"), Map.of("action", "passkey-register-start"));
		assertEquals(HttpServletResponse.SC_FORBIDDEN, link.status());
		assertEquals(SignInActions.SIGN_INS_REFUSED, errorMessage(link));
		FakeResponse nobody = post("/", "{}", null, Map.of("action", "passkey-register-start"));
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, nobody.status());
		FakeResponse notHere = post("/", "{}", SharingFixture.ALICE, Map.of("action", "passkey-start"));
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, notHere.status());
		assertEquals(AddressProof.PASSKEY_NOT_HERE, errorMessage(notHere));
	}

	// --- Removing. ---

	public void testTheContactAndAMemberRemovePasskeys() throws Exception {
		ShareLinkCreated created = created(ZOO, phone("Klaus", KLAUS));
		String token = tokenOf(created, "Klaus");
		String credential = credential(token);
		SoftwareAuthenticator phone = new SoftwareAuthenticator(ORIGIN);
		SoftwareAuthenticator laptop = new SoftwareAuthenticator(ORIGIN);
		register(phone, token, credential);
		ContactSignIns two = register(laptop, token, credential);
		assertEquals(2, two.getPasskeys().size());

		String first = two.getPasskeys().get(0).getId();
		FakeResponse removed = postAs("/", "remove-sign-in", "{\"method\":\"passkey\",\"id\":\"" + first + "\"}", token,
			credential);
		assertEquals(removed.body(), 200, removed.status());
		assertEquals(1, ContactSignIns.readContactSignIns(reader(body(removed))).getPasskeys().size());
		assertEquals(HttpServletResponse.SC_FORBIDDEN, signIn(phone, token, null).status());

		String second = two.getPasskeys().get(1).getId();
		String body = "{\"contact\":\"" + contactOf(created, "Klaus") + "\",\"method\":\"passkey\",\"id\":\"" + second
			+ "\"}";
		FakeResponse byMember = post("/", body, SharingFixture.ALICE, Map.of("action", "remove-contact-sign-in"));
		assertEquals(byMember.body(), 200, byMember.status());
		assertTrue(Contact.readContact(reader(body(byMember))).getPasskeys().isEmpty());
		assertTrue(methods(laterOpen(token)).isEmpty());
		FakeResponse again = post("/", body, SharingFixture.ALICE, Map.of("action", "remove-contact-sign-in"));
		assertEquals(HttpServletResponse.SC_NOT_FOUND, again.status());
	}

	// --- Helpers. ---

	private ContactSignIns register(SoftwareAuthenticator authenticator, String token, String credential)
			throws Exception {
		PasskeyOptions options = options(postAs("/", "passkey-register-start", "{}", token, credential));
		FakeResponse registered = postAs("/", "passkey-register",
			body(options.getTicket(), authenticator.create(options.getOptions())), token, credential);
		assertEquals(registered.body(), 200, registered.status());
		return ContactSignIns.readContactSignIns(reader(body(registered)));
	}

	private FakeResponse signIn(SoftwareAuthenticator authenticator, String token, String credential)
			throws Exception {
		PasskeyOptions options = options(postAs("/", "passkey-start", "{}", token, credential));
		return verify(token, credential, options.getTicket(), authenticator.get(options.getOptions()));
	}

	private FakeResponse verify(String token, String credential, String ticket, String answer) throws Exception {
		return postAs("/", "passkey-verify", body(ticket, answer), token, credential);
	}

	private static String body(String ticket, String answer) {
		return "{\"ticket\":\"" + ticket + "\",\"response\":" + quote(answer) + ",\"remember\":true}";
	}

	private static String quote(String text) {
		return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	private static PasskeyOptions options(FakeResponse response) throws Exception {
		assertEquals(response.body(), 200, response.status());
		return PasskeyOptions.readPasskeyOptions(reader(body(response)));
	}

	private FakeResponse post(ImageServlet servlet, String action, String body, String token) throws Exception {
		FakeResponse response = new FakeResponse();
		servlet.doPost(TestImageServletPut.request("/", "application/json",
			body.getBytes(java.nio.charset.StandardCharsets.UTF_8), headers(token, null), Map.of("action", action)),
			response.response());
		return response;
	}

	private FakeResponse laterOpen(String token) throws Exception {
		FakeResponse response = get("/", "json", token);
		assertEquals(response.body(), HttpServletResponse.SC_UNAUTHORIZED, response.status());
		return response;
	}

	private static List<String> methods(FakeResponse refusal) throws Exception {
		return identifyRequired(refusal).getMethods().stream().map(ProofMethod::getName).toList();
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
