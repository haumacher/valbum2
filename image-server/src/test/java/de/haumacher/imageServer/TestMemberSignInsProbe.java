/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.Totp;
import de.haumacher.imageServer.auth.TotpSignIns;
import de.haumacher.imageServer.mail.EmailProofs;
import de.haumacher.imageServer.mail.TestEmailProofs.CapturingMailer;
import de.haumacher.imageServer.mail.TestEmailProofs.TestClock;
import de.haumacher.imageServer.passkeys.Passkeys;
import de.haumacher.imageServer.passkeys.SoftwareAuthenticator;
import de.haumacher.imageServer.shared.model.PasskeyOptions;
import de.haumacher.imageServer.shared.model.TotpSetup;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Probe of issue #233 against the removal of a member: whatever a member set up to sign in with
 * leaves with them, and an address they proved is free for a contact again.
 */
@SuppressWarnings("javadoc")
public class TestMemberSignInsProbe extends PersonalLinkTestCase {

	private static final String ORIGIN = "http://localhost:9100";

	private static final String BOBS = "bob@example.org";

	private TestClock _clock;

	private CapturingMailer _mailer;

	private Passkeys _passkeys;

	private EmailProofs _proofs;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_clock = new TestClock();
		_mailer = new CapturingMailer();
		_passkeys = new Passkeys(ORIGIN + "/valbum", _clock);
		_proofs = new EmailProofs(_mailer, _clock, Runnable::run);
	}

	@Override
	protected ImageServlet servlet() throws Exception {
		ImageServlet result = super.servlet();
		result.getAuth().getTotp().setClock(_clock);
		result.setPasskeys(_passkeys);
		result.setEmailProofs(_proofs);
		return result;
	}

	public void testARemovedMembersWaysInLeaveWithThem() throws Exception {
		FakeResponse setUp = postAs("/", "totp-setup", "{}", SharingFixture.BOB, null);
		assertEquals(setUp.body(), 200, setUp.status());
		TotpSetup totp = TotpSetup.readTotpSetup(reader(body(setUp)));
		assertEquals(200, postAs("/", "totp-confirm", "{\"code\":\"" + code(totp) + "\"}", SharingFixture.BOB, null)
			.status());

		SoftwareAuthenticator phone = new SoftwareAuthenticator(ORIGIN);
		PasskeyOptions register = options(postAs("/", "passkey-register-start", "{}", SharingFixture.BOB, null));
		FakeResponse registered = postAs("/", "passkey-register", "{\"ticket\":\"" + register.getTicket()
			+ "\",\"response\":" + quote(phone.create(register.getOptions())) + "}", SharingFixture.BOB, null);
		assertEquals(registered.body(), 200, registered.status());

		assertEquals(200, postAs("/", "prove-email", "{\"address\":\"" + BOBS + "\"}", SharingFixture.BOB, null)
			.status());
		assertEquals(200, postAs("/", "verify-email", "{\"address\":\"" + BOBS + "\",\"code\":\""
			+ _mailer.lastCode(BOBS) + "\"}", SharingFixture.BOB, null).status());

		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "remove-user");
		FakeResponse removed = post("/", "{\"name\":\"bob\"}", SharingFixture.ALICE, parameters);
		assertEquals(removed.body(), HttpServletResponse.SC_OK, removed.status());

		_clock.advance(Duration.ofMinutes(1));
		FakeResponse byCode = postAs("/", "pair", "{\"userName\":\"bob\",\"totpCode\":\"" + code(totp) + "\"}", null,
			null);
		assertEquals(byCode.body(), HttpServletResponse.SC_BAD_REQUEST, byCode.status());
		assertEquals(TotpSignIns.CODE_WRONG, errorMessage(byCode));
		FakeResponse byAddress = postAs("/", "pair", "{\"userName\":\"" + BOBS + "\",\"totpCode\":\"" + code(totp)
			+ "\"}", null, null);
		assertEquals(TotpSignIns.CODE_WRONG, errorMessage(byAddress));

		PasskeyOptions signIn = options(postAs("/", "passkey-start", "{}", null, null));
		FakeResponse byPasskey = postAs("/", "pair", "{\"deviceName\":\"X\",\"passkey\":{\"ticket\":\""
			+ signIn.getTicket() + "\",\"response\":" + quote(phone.get(signIn.getOptions())) + "}}", null, null);
		assertTrue(byPasskey.body(), byPasskey.status() >= 400);

		// The address is nobody's any more: a contact may have it.
		FakeResponse share = sharePersonal(ZOO, SharingFixture.ALICE, email("Bob", BOBS));
		assertEquals(share.body(), 200, share.status());
	}

	private String code(TotpSetup setup) {
		return Totp.code(Totp.base32Decode(setup.getSecret()), Totp.step(_clock.instant()));
	}

	private static PasskeyOptions options(FakeResponse response) throws Exception {
		assertEquals(response.body(), 200, response.status());
		return PasskeyOptions.readPasskeyOptions(reader(body(response)));
	}

	private static String quote(String text) {
		return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}
}
