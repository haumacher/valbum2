/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.mail;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import junit.framework.TestCase;

/**
 * Test case for {@link EmailProofs}: the code, its lifetime, its attempts and the rate limits of
 * issue #199, against a real (fake) SMTP server.
 */
@SuppressWarnings("javadoc")
public class TestEmailProofs extends TestCase {

	/** A clock a test turns forward. */
	public static final class TestClock extends Clock {

		private Instant _now = Instant.parse("2026-10-03T12:00:00Z");

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public synchronized Instant instant() {
			return _now;
		}

		public synchronized void advance(Duration duration) {
			_now = _now.plus(duration);
		}
	}

	/** A mailer that keeps what it was given. */
	public static final class CapturingMailer implements Mailer {

		private final List<String[]> _sent = new ArrayList<>();

		@Override
		public synchronized void send(String to, String subject, String text) {
			_sent.add(new String[] { to, subject, text });
		}

		public synchronized List<String[]> sent() {
			return new ArrayList<>(_sent);
		}

		/** The code of the last mail to the given address. */
		public synchronized String lastCode(String to) {
			for (int n = _sent.size() - 1; n >= 0; n--) {
				if (_sent.get(n)[0].equals(to)) {
					return codeIn(_sent.get(n)[2]);
				}
			}
			throw new AssertionError("No mail to " + to);
		}
	}

	private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");

	public static String codeIn(String text) {
		Matcher matcher = CODE.matcher(text);
		assertTrue(text, matcher.find());
		return matcher.group(1);
	}

	private TestClock _clock;

	private FakeSmtpServer _smtp;

	private EmailProofs _proofs;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_clock = new TestClock();
		_smtp = new FakeSmtpServer();
		_proofs = new EmailProofs(new SmtpMailer(new MailSettings("localhost", _smtp.getPort(), "", "",
			"album@example.org", MailSettings.Security.NONE)), _clock);
	}

	@Override
	protected void tearDown() throws Exception {
		_smtp.close();
		super.tearDown();
	}

	private EmailProofs.Sent send(String address, String link, String client) throws EmailProofs.Refused {
		return _proofs.send("open:" + link, address, link, client,
			(code, minutes) -> CodeMail.of(CodeMail.ENGLISH, "Family", code, minutes));
	}

	private String lastCode() throws Exception {
		List<FakeSmtpServer.Received> received = _smtp.getReceived();
		return codeIn(received.get(received.size() - 1).getText());
	}

	public void testACodeArrivesAndVerifiesOnce() throws Exception {
		EmailProofs.Sent sent = send("petra@gmx.de", "L", "1.2.3.4");
		assertEquals(_clock.instant().plus(Duration.ofMinutes(10)), sent.getExpires());
		assertEquals(1, _smtp.getReceived().size());
		assertEquals("Your code for Family: " + lastCode(), _smtp.getReceived().get(0).getSubject());
		String code = lastCode();
		assertTrue(_proofs.verify("open:L", "petra@gmx.de", code, "1.2.3.4"));
		assertRefused(EmailProofs.CODE_VOID, () -> _proofs.verify("open:L", "petra@gmx.de", code, "1.2.3.4"));
	}

	public void testASixthAttemptFindsTheCodeVoid() throws Exception {
		send("petra@gmx.de", "L", "1.2.3.4");
		String code = lastCode();
		String wrong = code.equals("000000") ? "111111" : "000000";
		for (int n = 0; n < EmailProofs.ATTEMPTS; n++) {
			assertRefused(EmailProofs.CODE_WRONG, () -> _proofs.verify("open:L", "petra@gmx.de", wrong, "1.2.3.4"));
		}
		assertRefused(EmailProofs.CODE_VOID, () -> _proofs.verify("open:L", "petra@gmx.de", code, "1.2.3.4"));
	}

	public void testTheFifthAttemptMayStillBeRight() throws Exception {
		send("petra@gmx.de", "L", "1.2.3.4");
		String code = lastCode();
		String wrong = code.equals("000000") ? "111111" : "000000";
		for (int n = 0; n < EmailProofs.ATTEMPTS - 1; n++) {
			assertRefused(EmailProofs.CODE_WRONG, () -> _proofs.verify("open:L", "petra@gmx.de", wrong, "1.2.3.4"));
		}
		assertTrue(_proofs.verify("open:L", "petra@gmx.de", code, "1.2.3.4"));
	}

	public void testAfterTenMinutesTheCodeIsVoid() throws Exception {
		send("petra@gmx.de", "L", "1.2.3.4");
		String code = lastCode();
		_clock.advance(Duration.ofMinutes(10));
		assertRefused(EmailProofs.CODE_VOID, () -> _proofs.verify("open:L", "petra@gmx.de", code, "1.2.3.4"));
	}

	public void testACodeCountsInItsScopeOnly() throws Exception {
		send("petra@gmx.de", "L", "1.2.3.4");
		String code = lastCode();
		assertRefused(EmailProofs.CODE_VOID, () -> _proofs.verify("open:M", "petra@gmx.de", code, "1.2.3.4"));
		assertRefused(EmailProofs.CODE_VOID, () -> _proofs.verify("open:L", "paul@gmx.de", code, "1.2.3.4"));
		assertTrue(_proofs.verify("open:L", "petra@gmx.de", " " + code.substring(0, 3) + "-" + code.substring(3), "5.6.7.8"));
	}

	public void testANewCodeReplacesTheOldOne() throws Exception {
		send("petra@gmx.de", "L", "1.2.3.4");
		String first = lastCode();
		send("petra@gmx.de", "L", "1.2.3.4");
		String second = lastCode();
		if (!first.equals(second)) {
			assertRefused(EmailProofs.CODE_WRONG, () -> _proofs.verify("open:L", "petra@gmx.de", first, "1.2.3.4"));
		}
		assertTrue(_proofs.verify("open:L", "petra@gmx.de", second, "1.2.3.4"));
	}

	public void testCodesPerAddressAreLimited() throws Exception {
		for (int n = 0; n < EmailProofs.PER_ADDRESS; n++) {
			send("petra@gmx.de", "L" + n, "10.0.0." + n);
		}
		EmailProofs.Refused refused = assertRefused(EmailProofs.RATE_LIMITED, () -> send("petra@gmx.de", "X", "10.0.1.1"));
		assertEquals(429, refused.getStatus());
		assertTrue(refused.getRetryAfter() > 0 && refused.getRetryAfter() <= 3600);
		assertEquals("Nothing was mailed for the refusal.", EmailProofs.PER_ADDRESS, _smtp.getReceived().size());
		_clock.advance(EmailProofs.WINDOW);
		send("petra@gmx.de", "X", "10.0.1.1");
	}

	public void testCodesPerClientAreLimited() throws Exception {
		for (int n = 0; n < EmailProofs.PER_CLIENT; n++) {
			send("visitor" + n + "@gmx.de", "L" + n, "1.2.3.4");
		}
		assertRefused(EmailProofs.RATE_LIMITED, () -> send("another@gmx.de", "Y", "1.2.3.4"));
		send("another@gmx.de", "Y", "1.2.3.5");
	}

	public void testCodesPerLinkAreLimited() throws Exception {
		for (int n = 0; n < EmailProofs.PER_LINK; n++) {
			send("visitor" + n + "@gmx.de", "L", "10.0." + (n / 200) + "." + (n % 200));
		}
		assertRefused(EmailProofs.RATE_LIMITED, () -> send("another@gmx.de", "L", "10.1.0.1"));
		send("another@gmx.de", "M", "10.1.0.1");
	}

	public void testWrongCodesPerClientAreLimited() throws Exception {
		int wrong = 0;
		int link = 0;
		while (wrong < EmailProofs.WRONG_PER_CLIENT) {
			send("v" + link + "@gmx.de", "L" + link, "10.9.9." + link);
			String bad = lastCode().equals("000000") ? "111111" : "000000";
			for (int n = 0; n < EmailProofs.ATTEMPTS && wrong < EmailProofs.WRONG_PER_CLIENT; n++, wrong++) {
				String scope = "open:L" + link;
				String address = "v" + link + "@gmx.de";
				assertRefused(EmailProofs.CODE_WRONG, () -> _proofs.verify(scope, address, bad, "6.6.6.6"));
			}
			link++;
		}
		send("last@gmx.de", "Z", "10.9.8.1");
		String code = lastCode();
		assertRefused(EmailProofs.TOO_MANY_WRONG, () -> _proofs.verify("open:Z", "last@gmx.de", code, "6.6.6.6"));
		assertTrue("Another client is not held back.", _proofs.verify("open:Z", "last@gmx.de", code, "6.6.6.7"));
	}

	public void testAMailThatCannotBeSentLeavesNoCode() throws Exception {
		_smtp.close();
		assertRefused(EmailProofs.MAIL_FAILED, () -> send("petra@gmx.de", "L", "1.2.3.4"));
		assertRefused(EmailProofs.CODE_VOID, () -> _proofs.verify("open:L", "petra@gmx.de", "123456", "1.2.3.4"));
	}

	public void testWithoutAMailerNothingIsConfigured() {
		assertFalse(EmailProofs.NONE.isAvailable());
		EmailProofs.Refused refused = assertRefused(EmailProofs.NOT_CONFIGURED,
			() -> EmailProofs.NONE.send("s", "a@b.de", "L", "1", (code, minutes) -> null));
		assertEquals(501, refused.getStatus());
	}

	interface Action {
		void run() throws Exception;
	}

	private static EmailProofs.Refused assertRefused(String message, Action action) {
		try {
			action.run();
		} catch (EmailProofs.Refused ex) {
			assertEquals(message, ex.getMessage());
			return ex;
		} catch (Exception ex) {
			throw new AssertionError(ex);
		}
		fail("Not refused, expected: " + message);
		return null;
	}
}
