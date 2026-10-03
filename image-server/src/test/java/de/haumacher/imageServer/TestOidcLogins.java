/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.mail.TestEmailProofs.TestClock;
import de.haumacher.imageServer.oidc.MockOidcIssuer;
import de.haumacher.imageServer.oidc.OidcLogins;
import de.haumacher.imageServer.shared.model.AuthInfo;
import de.haumacher.imageServer.shared.model.Contact;
import de.haumacher.imageServer.shared.model.ContactCredential;
import de.haumacher.imageServer.shared.model.IdentifyRequired;
import de.haumacher.imageServer.shared.model.OidcStarted;
import de.haumacher.imageServer.shared.model.ProofMethod;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test case for "Continue with Google": proving an address through OpenID Connect, see issue #200.
 *
 * <p>
 * The provider is a {@link MockOidcIssuer} on the loopback interface (discovery, keys, token
 * endpoint, RS256-signed ID tokens), so pac4j does over HTTP what it does with Google. The browser
 * is the test: it starts the sign-in at the servlet, visits the provider with
 * {@link MockOidcIssuer#authorize(String)}, hands what comes back to the callback and finishes with
 * the code it finds behind <code>#oidc=</code>.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestOidcLogins extends PersonalLinkTestCase {

	private static final String PUBLIC_URL = "https://photos.example.org/valbum";

	private MockOidcIssuer _issuer;

	private TestClock _clock;

	private OidcLogins _oidc;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_issuer = new MockOidcIssuer();
		_clock = new TestClock();
		_oidc = new OidcLogins(PUBLIC_URL, List.of(_issuer.provider("Google")), _clock);
	}

	@Override
	protected void tearDown() throws Exception {
		_issuer.close();
		super.tearDown();
	}

	@Override
	protected ImageServlet servlet() throws Exception {
		ImageServlet result = super.servlet();
		result.setOidcLogins(_oidc);
		return result;
	}

	// --- Not configured. ---

	public void testWithoutAProviderNothingIsOffered() throws Exception {
		_oidc = OidcLogins.NONE;
		assertNoProviderIsOffered();
	}

	public void testWithoutAPublicAddressNothingIsOffered() throws Exception {
		_oidc = new OidcLogins(null, List.of(_issuer.provider("Google")), _clock);
		assertNoProviderIsOffered();
	}

	private void assertNoProviderIsOffered() throws Exception {
		ShareLinkCreated open = created(ZOO);
		FakeResponse refusal = get("/", "json", open.getToken());
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, refusal.status());
		assertTrue(identifyRequired(refusal).getMethods().isEmpty());

		FakeResponse start = start(open.getToken(), null, "{\"provider\":\"google\"}");
		assertEquals(start.body(), HttpServletResponse.SC_NOT_IMPLEMENTED, start.status());
		assertEquals(OidcLogins.NOT_CONFIGURED, errorMessage(start));

		FakeResponse callback = callbackServlet(Map.of("code", new String[] { "c" }, "state", new String[] { "s" }), "/callback");
		assertEquals(HttpServletResponse.SC_NOT_FOUND, callback.status());
		assertEquals(0, _issuer.tokenRequests());
	}

	// --- An open personal link. ---

	public void testAnOpenLinkCreatesAContact() throws Exception {
		ShareLinkCreated open = created(ZOO);
		String token = open.getToken();

		IdentifyRequired refusal = identifyRequired(get("/", "json", token));
		assertEquals(1, refusal.getMethods().size());
		ProofMethod method = refusal.getMethods().get(0);
		assertEquals("oidc:google", method.getName());
		assertEquals("Google", method.getLabel());

		_issuer.next("Vera@Web.DE", Boolean.TRUE, "Vera Visitor");
		ContactCredential credential = credential(signIn(token, null, true, ""));
		assertFalse(credential.getCredential().isEmpty());
		assertTrue(credential.isRemember());
		assertEquals("The provider's name pre-fills the display name.", "Vera Visitor",
			credential.getContact().getDisplayName());

		assertEquals(200, getAs("/", "json", token, credential.getCredential()).status());
		Contact vera = contact(credential.getContact().getId());
		assertEquals("Vera Visitor", vera.getName());
		assertEquals("link:" + open.getLink().getId(), vera.getCreatedBy());
		assertEquals("vera@web.de", vera.getAddresses().get(0).getValue());
		assertTrue(vera.getAddresses().get(0).isProven());

		// The same address once more is the same person, and a name of their own wins.
		_clock.advance(Duration.ofMinutes(1));
		ContactCredential again = credential(signIn(token, null, false, "Vera"));
		assertEquals(vera.getId(), again.getContact().getId());
		assertFalse(again.isRemember());
		assertEquals("Vera", again.getContact().getDisplayName());
		assertEquals(1, contacts().size());
	}

	public void testAnUnverifiedAddressIsRefused() throws Exception {
		ShareLinkCreated open = created(ZOO);
		_issuer.next("mallory@example.org", Boolean.FALSE, "Mallory");
		FakeResponse refused = signIn(open.getToken(), null, true, "");
		assertEquals(refused.body(), HttpServletResponse.SC_FORBIDDEN, refused.status());
		assertEquals(OidcLogins.notVerified("Google"), errorMessage(refused));

		_issuer.next("mallory@example.org", null, "Mallory");
		FakeResponse silent = signIn(open.getToken(), null, true, "");
		assertEquals("No claim is no confirmation.", HttpServletResponse.SC_FORBIDDEN, silent.status());

		_issuer.next("mallory@example.org", "yes", "Mallory");
		assertEquals("Only true is true.", HttpServletResponse.SC_FORBIDDEN,
			signIn(open.getToken(), null, true, "").status());
		assertTrue("Nothing was created.", contacts().isEmpty());
	}

	public void testATokenNotSignedByTheProviderIsRefused() throws Exception {
		ShareLinkCreated open = created(ZOO);
		_issuer.signWithForeignKey(true);
		FakeResponse refused = signIn(open.getToken(), null, true, "");
		assertEquals(refused.body(), HttpServletResponse.SC_FORBIDDEN, refused.status());
		assertEquals(OidcLogins.failed("Google"), errorMessage(refused));

		_issuer.signWithForeignKey(false);
		_issuer.audience("somebody-else");
		assertEquals("A token for another client is refused.", HttpServletResponse.SC_FORBIDDEN,
			signIn(open.getToken(), null, true, "").status());

		_issuer.audience(MockOidcIssuer.CLIENT_ID);
		_issuer.nonce("the-nonce-of-another-sign-in");
		assertEquals("A token of another sign-in is refused.", HttpServletResponse.SC_FORBIDDEN,
			signIn(open.getToken(), null, true, "").status());
		assertTrue(contacts().isEmpty());
	}

	public void testACancelledSignInSaysSo() throws Exception {
		ShareLinkCreated open = created(ZOO);
		OidcStarted started = started(start(open.getToken(), null, "{\"provider\":\"google\"}"));
		Map<String, String[]> back = new HashMap<>();
		back.put("error", new String[] { "access_denied" });
		back.put("state", new String[] { MockOidcIssuer.state(started.getUrl()) });
		FakeResponse refused = exchange(open.getToken(), null, codeOf(_oidc.callback(back)), started.getBinding());
		assertEquals(HttpServletResponse.SC_FORBIDDEN, refused.status());
		assertEquals(OidcLogins.cancelled("Google"), errorMessage(refused));
		assertEquals(0, _issuer.tokenRequests());
	}

	// --- An addressed link. ---

	public void testAVerifiedAddressIdentifiesTheMatchingContact() throws Exception {
		ShareLinkCreated addressed = created(ZOO, email("Tante Petra", PETRA));
		String own = tokenOf(addressed, "Tante Petra");
		String petra = contactOf(addressed, "Tante Petra");
		credential(own);

		// Another browser: the link was opened before, so it asks.
		FakeResponse later = get("/", "json", own);
		assertEquals(AuthService.IDENTIFY_REQUIRED, errorMessage(later));
		assertTrue(names(identifyRequired(later).getMethods()).contains("oidc:google"));

		_issuer.next("petra@gmx.de", Boolean.TRUE, "Petra Müller");
		ContactCredential credential = credential(signIn(own, null, true, ""));
		assertEquals(petra, credential.getContact().getId());
		assertTrue("Proven now.", contact(petra).getAddresses().get(0).isProven());
		assertEquals("The name the space gives her stays.", "Tante Petra", contact(petra).getName());
		assertEquals(200, getAs("/", "json", own, credential.getCredential()).status());
		assertEquals(1, contacts().size());
	}

	public void testAnAddressedLinkRefusesAnAddressNotAmongItsRecipients() throws Exception {
		ShareLinkCreated addressed = created(ZOO, email("Tante Petra", PETRA), email("Onkel Hans", "hans@web.de"));
		String linkToken = addressed.getToken();
		String petraToken = tokenOf(addressed, "Tante Petra");
		credential(petraToken);

		// The link's own token: any recipient's address, and nobody else's.
		FakeResponse personal = get("/", "json", linkToken);
		assertEquals(AuthService.IDENTIFY_PERSONAL, errorMessage(personal));
		assertEquals(List.of("oidc:google"), names(identifyRequired(personal).getMethods()));

		_issuer.next("stranger@example.org", Boolean.TRUE, "Stranger");
		FakeResponse stranger = signIn(linkToken, null, true, "");
		assertEquals(stranger.body(), HttpServletResponse.SC_FORBIDDEN, stranger.status());
		assertEquals(AddressProof.SHARED_WITH_SOMEONE_ELSE, errorMessage(stranger));

		_issuer.next("hans@web.de", Boolean.TRUE, "Hans");
		ContactCredential hans = credential(signIn(linkToken, null, true, ""));
		assertEquals(contactOf(addressed, "Onkel Hans"), hans.getContact().getId());

		// Petra's own link stays hers: Hans's address is refused there.
		_issuer.next("hans@web.de", Boolean.TRUE, "Hans");
		FakeResponse other = signIn(petraToken, null, true, "");
		assertEquals(HttpServletResponse.SC_FORBIDDEN, other.status());
		assertEquals(AddressProof.SHARED_WITH_SOMEONE_ELSE, errorMessage(other));

		assertEquals("Nothing was created.", 2, contacts().size());
	}

	/** Issue #211: a recipient blocked in the space does not come in through the group link either. */
	public void testABlockedRecipientStaysOutOfTheGroupLink() throws Exception {
		ShareLinkCreated addressed = created(ZOO, email("Tante Petra", PETRA), email("Onkel Hans", "hans@web.de"));
		String petra = contactOf(addressed, "Tante Petra");
		assertTrue(identifyRequired(get("/", "json", addressed.getToken())).isGroup());
		FakeResponse block = post("/", "{\"contact\":\"" + petra + "\",\"shutOut\":true}", SharingFixture.ALICE,
			Map.of("action", "block-contact"));
		assertEquals(block.body(), 200, block.status());

		_issuer.next("petra@gmx.de", Boolean.TRUE, "Petra");
		FakeResponse refused = signIn(addressed.getToken(), null, true, "");
		assertEquals(refused.body(), HttpServletResponse.SC_GONE, refused.status());
		assertEquals(AuthService.CONTACT_SHUT_OUT, errorMessage(refused));
		assertTrue(contact(petra).getSessions().isEmpty());
	}

	public void testAFirstOpenNeedsNoProvider() throws Exception {
		ShareLinkCreated addressed = created(ZOO, email("Tante Petra", PETRA));
		String own = tokenOf(addressed, "Tante Petra");
		FakeResponse first = get("/", "json", own);
		assertTrue(identifyRequired(first).isFirstOpen());
		assertTrue(identifyRequired(first).getMethods().isEmpty());
		FakeResponse start = start(own, null, "{\"provider\":\"google\"}");
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, start.status());
		assertEquals(AuthService.IDENTIFY_FIRST, errorMessage(start));
	}

	public void testARecognisedContactAddsAnAddress() throws Exception {
		ShareLinkCreated addressed = created(ZOO, email("Tante Petra", PETRA));
		String own = tokenOf(addressed, "Tante Petra");
		String credential = credential(own);
		AuthInfo info = auth(getAs("/", "auth", own, credential));
		assertTrue(names(info.getShare().getMethods()).contains("oidc:google"));
		assertTrue("She has an e-mail address already (#211).", info.getShare().isContactHasEmail());
		assertTrue("A link caller is no member.", info.getProofMethods().isEmpty());

		_issuer.next("petra.mueller@gmail.com", Boolean.TRUE, "Petra");
		ContactCredential added = credential(signIn(own, credential, true, ""));
		assertEquals("The credential she holds stays.", "", added.getCredential());
		assertEquals(2, contact(contactOf(addressed, "Tante Petra")).getAddresses().size());
	}

	/** Issue #211: the share dialog learns which proofs the server offers. */
	public void testAMemberIsToldTheProofMethods() throws Exception {
		AuthInfo member = auth(get("/", "auth", SharingFixture.ALICE));
		assertEquals(List.of("oidc:google"), names(member.getProofMethods()));
		assertEquals("Google", member.getProofMethods().get(0).getLabel());
		assertTrue("Never to an anonymous caller.", auth(get("/", "auth", null)).getProofMethods().isEmpty());
	}

	// --- The state. ---

	public void testAWrongOrReplayedStateIsRefused() throws Exception {
		ShareLinkCreated open = created(ZOO);
		OidcStarted started = started(start(open.getToken(), null, "{\"provider\":\"google\"}"));
		Map<String, String[]> back = _issuer.authorize(started.getUrl());
		String state = back.get("state")[0];

		assertRefused(withState(back, state.substring(0, state.length() - 2) + "xx"));
		assertRefused(withState(back, "not-a-state"));
		assertRefused(withState(back, null));
		String[] parts = state.split("\\.");
		assertRefused("Another space, the same nonce and signature.",
			withState(back, base64("other") + "." + parts[1] + "." + parts[2]));

		String location = _oidc.callback(back);
		assertTrue(location, location.startsWith(PUBLIC_URL + "/s/" + open.getToken() + "/#oidc="));
		assertRefused("Replayed.", back);
		assertEquals("The provider was asked once.", 1, _issuer.tokenRequests());

		FakeResponse page = callbackServlet(withState(back, "<script>alert(1)</script>"), "/callback");
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, page.status());
		assertFalse("The page repeats nothing of the request.", page.body().contains("<script"));
		assertTrue(page.body(), page.body().contains("This sign-in cannot be finished here"));
	}

	public void testAStateThatRanOutIsRefused() throws Exception {
		ShareLinkCreated open = created(ZOO);
		OidcStarted started = started(start(open.getToken(), null, "{\"provider\":\"google\"}"));
		Map<String, String[]> back = _issuer.authorize(started.getUrl());
		_clock.advance(OidcLogins.LIFETIME.plusSeconds(1));
		assertRefused(back);
	}

	public void testTheCallbackRedirectsToTheLink() throws Exception {
		ShareLinkCreated open = created(ZOO);
		OidcStarted started = started(start(open.getToken(), null, "{\"provider\":\"google\"}"));
		assertEquals(PUBLIC_URL + "/oidc/callback", MockOidcIssuer.redirectUri(started.getUrl()));
		FakeResponse answer = callbackServlet(_issuer.authorize(started.getUrl()), "/callback");
		assertEquals(HttpServletResponse.SC_SEE_OTHER, answer.status());
		assertTrue(answer.header("Location"), answer.header("Location").startsWith(PUBLIC_URL + "/s/"
			+ open.getToken() + "/#oidc="));
		assertEquals("no-store", answer.header("Cache-Control"));
		assertEquals(HttpServletResponse.SC_NOT_FOUND, callbackServlet(Map.of(), "/other").status());
	}

	// --- The exchange. ---

	public void testTheExchangeIsSingleUseAndBoundToItsStart() throws Exception {
		ShareLinkCreated open = created(ZOO);
		String token = open.getToken();
		OidcStarted started = started(start(token, null, "{\"provider\":\"google\",\"remember\":true}"));
		String code = codeOf(_oidc.callback(_issuer.authorize(started.getUrl())));

		FakeResponse first = exchange(token, null, code, started.getBinding());
		assertEquals(first.body(), 200, first.status());
		FakeResponse second = exchange(token, null, code, started.getBinding());
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, second.status());
		assertEquals(OidcLogins.EXCHANGE_UNKNOWN, errorMessage(second));

		// Without the binding of its start the code is worth nothing, and is gone.
		OidcStarted another = started(start(token, null, "{\"provider\":\"google\"}"));
		String stolen = codeOf(_oidc.callback(_issuer.authorize(another.getUrl())));
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, exchange(token, null, stolen, "guessed").status());
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, exchange(token, null, stolen, another.getBinding()).status());

		// A code waits a few minutes only.
		OidcStarted late = started(start(token, null, "{\"provider\":\"google\"}"));
		String lateCode = codeOf(_oidc.callback(_issuer.authorize(late.getUrl())));
		_clock.advance(OidcLogins.EXCHANGE_LIFETIME.plusSeconds(1));
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, exchange(token, null, lateCode, late.getBinding()).status());
	}

	public void testAnExchangeOnAnotherLinkIsRefused() throws Exception {
		ShareLinkCreated open = created(ZOO);
		ShareLinkCreated other = created("/" + SharingFixture.PUBLIC + "/");
		OidcStarted started = started(start(open.getToken(), null, "{\"provider\":\"google\"}"));
		String code = codeOf(_oidc.callback(_issuer.authorize(started.getUrl())));
		FakeResponse refused = exchange(other.getToken(), null, code, started.getBinding());
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, refused.status());
		assertTrue(contacts().isEmpty());
	}

	public void testAStateOfOneSpaceCannotBeUsedInAnother() throws Exception {
		ShareLinkCreated open = created(ZOO);
		OidcStarted started = started(start(open.getToken(), null, "{\"provider\":\"google\"}"));
		String code = codeOf(_oidc.callback(_issuer.authorize(started.getUrl())));

		ImageServlet otherSpace = new ImageServlet(_base.toFile(), new AuthService(_authMode, _base, _inviteMode), "other");
		otherSpace.init();
		try {
			otherSpace.setOidcLogins(_oidc);
			FakeResponse refused = new FakeResponse();
			otherSpace.doPost(TestImageServletPut.request("/", "application/json",
				("{\"code\":\"" + code + "\",\"binding\":\"" + started.getBinding() + "\"}").getBytes(StandardCharsets.UTF_8),
				headers(open.getToken(), null), Map.of("action", "oidc-exchange")), refused.response());
			assertEquals(refused.body(), HttpServletResponse.SC_BAD_REQUEST, refused.status());
			assertEquals(OidcLogins.EXCHANGE_UNKNOWN, errorMessage(refused));
		} finally {
			otherSpace.destroy();
		}
		assertEquals("Used up in the other space.", HttpServletResponse.SC_BAD_REQUEST,
			exchange(open.getToken(), null, code, started.getBinding()).status());
	}

	// --- What the provider says is text. ---

	public void testTheProvidersNameIsPlainText() throws Exception {
		ShareLinkCreated open = created(ZOO);
		_issuer.next("vera@web.de", Boolean.TRUE, "<b>Vera</b>\u0007\n<script>x</script>");
		ContactCredential credential = credential(signIn(open.getToken(), null, true, ""));
		assertEquals("Control characters out, the rest as it stands, for whoever shows it to escape.",
			"<b>Vera</b> <script>x</script>", credential.getContact().getDisplayName());

		_oidc = new OidcLogins(PUBLIC_URL, List.of(_issuer.provider("<i>Corp</i> & Co")), _clock);
		restartServer();
		ShareLinkCreated second = created(ZOO);
		assertEquals("<i>Corp</i> & Co", identifyRequired(get("/", "json", second.getToken())).getMethods().get(0)
			.getLabel());
	}

	public void testAnUnknownProviderIsRefused() throws Exception {
		ShareLinkCreated open = created(ZOO);
		FakeResponse refused = start(open.getToken(), null, "{\"provider\":\"facebook\"}");
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, refused.status());
		assertEquals(OidcLogins.providerUnknown("facebook"), errorMessage(refused));
	}

	public void testAnAnonymousLinkHasNothingToProve() throws Exception {
		FakeResponse refused = start(zooToken(), null, "{\"provider\":\"google\"}");
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, refused.status());
		assertEquals(AddressProof.PROOF_NOT_HERE, errorMessage(refused));
	}

	// --- Helpers. ---

	/** The whole sign-in, as a browser does it: start, provider, callback, exchange. */
	private FakeResponse signIn(String token, String credential, boolean remember, String displayName)
			throws Exception {
		FakeResponse start = start(token, credential,
			"{\"provider\":\"google\",\"remember\":" + remember + ",\"displayName\":\"" + displayName + "\"}");
		assertEquals(start.body(), 200, start.status());
		OidcStarted started = started(start);
		String location = _oidc.callback(_issuer.authorize(started.getUrl()));
		return exchange(token, credential, codeOf(location), started.getBinding());
	}

	private FakeResponse start(String token, String credential, String body) throws Exception {
		return postAs("/", "oidc-start", body, token, credential);
	}

	private FakeResponse exchange(String token, String credential, String code, String binding) throws Exception {
		return postAs("/", "oidc-exchange", "{\"code\":\"" + code + "\",\"binding\":\"" + binding + "\"}", token,
			credential);
	}

	private static OidcStarted started(FakeResponse response) throws Exception {
		assertEquals(response.body(), 200, response.status());
		return OidcStarted.readOidcStarted(reader(body(response)));
	}

	private static ContactCredential credential(FakeResponse response) throws Exception {
		assertEquals(response.body(), 200, response.status());
		return ContactCredential.readContactCredential(reader(body(response)));
	}

	private static String codeOf(String location) {
		int index = location.indexOf("#" + OidcLogins.FRAGMENT + "=");
		assertTrue(location, index > 0);
		return location.substring(index + OidcLogins.FRAGMENT.length() + 2);
	}

	private void assertRefused(Map<String, String[]> back) {
		assertRefused("Refused.", back);
	}

	private void assertRefused(String message, Map<String, String[]> back) {
		try {
			_oidc.callback(back);
			fail(message);
		} catch (OidcLogins.Refused ex) {
			assertEquals(HttpServletResponse.SC_BAD_REQUEST, ex.getStatus());
			assertEquals(OidcLogins.STATE_REFUSED, ex.getMessage());
		}
	}

	private static Map<String, String[]> withState(Map<String, String[]> back, String state) {
		Map<String, String[]> result = new HashMap<>(back);
		if (state == null) {
			result.remove("state");
		} else {
			result.put("state", new String[] { state });
		}
		return result;
	}

	private static String base64(String text) {
		return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
	}

	/** The callback servlet, asked by a browser with the given parameters. */
	private FakeResponse callbackServlet(Map<String, String[]> parameters, String pathInfo) throws Exception {
		Map<String, String[]> copy = new HashMap<>(parameters);
		HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(getClass().getClassLoader(),
			new Class<?>[] { HttpServletRequest.class }, (proxy, method, args) -> {
				switch (method.getName()) {
					case "getPathInfo":
						return pathInfo;
					case "getParameterMap":
						return copy;
					case "toString":
						return "FakeCallback";
					default:
						throw new UnsupportedOperationException(method.getName());
				}
			});
		FakeResponse response = new FakeResponse();
		new OidcCallbackServlet(_oidc).doGet(request, response.response());
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

	private static List<String> names(List<ProofMethod> methods) {
		return methods.stream().map(ProofMethod::getName).toList();
	}
}
