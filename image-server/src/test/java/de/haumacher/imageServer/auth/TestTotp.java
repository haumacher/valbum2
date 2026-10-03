/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.auth;

import de.haumacher.imageServer.mail.TestEmailProofs.TestClock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Test case for the codes of an authenticator app, see issue #208: the algorithm against RFC 6238,
 * and what {@link TotpSignIns} lets a code do, against a clock the test moves.
 */
@SuppressWarnings("javadoc")
public class TestTotp extends TestCase {

	/** The SHA-1 seed of RFC 6238, appendix B. */
	private static final byte[] RFC_KEY = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

	private Path _base;

	private ContactStore _contacts;

	private TestClock _clock;

	private TotpSignIns _totp;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-totp");
		_contacts = new ContactStore(_base);
		_clock = new TestClock();
		_totp = new TotpSignIns(_contacts, _clock);
	}

	@Override
	protected void tearDown() throws Exception {
		try (Stream<Path> paths = Files.walk(_base)) {
			paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
		}
		super.tearDown();
	}

	// --- The algorithm. ---

	public void testTheTestVectorsOfRfc6238() {
		long[] times = { 59L, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L };
		String[] codes = { "94287082", "07081804", "14050471", "89005924", "69279037", "65353130" };
		for (int n = 0; n < times.length; n++) {
			long step = Totp.step(Instant.ofEpochSecond(times[n]));
			assertEquals("T = " + times[n], codes[n], Totp.code(RFC_KEY, step, 8, "HmacSHA1"));
			// Six digits are the same number taken modulo a million.
			assertEquals("T = " + times[n], codes[n].substring(2), Totp.code(RFC_KEY, step));
		}
	}

	public void testBase32IsRfc4648() {
		assertEquals("", Totp.base32(new byte[0]));
		assertEquals("MY", Totp.base32("f".getBytes(StandardCharsets.US_ASCII)));
		assertEquals("MZXW6YTBOI", Totp.base32("foobar".getBytes(StandardCharsets.US_ASCII)));
		assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", Totp.base32(RFC_KEY));
		assertEquals("foobar", new String(Totp.base32Decode("mzxw 6ytb-oi=="), StandardCharsets.US_ASCII));
		String secret = Totp.newSecret(new java.security.SecureRandom());
		assertEquals("160 bits are 32 characters.", 32, secret.length());
		assertEquals(Totp.SECRET_BYTES, Totp.base32Decode(secret).length);
	}

	public void testANeighbouringStepIsAcceptedAndTwoStepsAwayIsNot() {
		String secret = Totp.base32(RFC_KEY);
		long now = Totp.step(_clock.instant());
		assertEquals(now, Totp.match(secret, Totp.code(RFC_KEY, now), now));
		assertEquals(now - 1, Totp.match(secret, Totp.code(RFC_KEY, now - 1), now));
		assertEquals(now + 1, Totp.match(secret, Totp.code(RFC_KEY, now + 1), now));
		assertEquals(-1, Totp.match(secret, Totp.code(RFC_KEY, now - 2), now));
		assertEquals(-1, Totp.match(secret, Totp.code(RFC_KEY, now + 2), now));
		String spaced = Totp.code(RFC_KEY, now).substring(0, 3) + " " + Totp.code(RFC_KEY, now).substring(3);
		assertEquals("The blank an app shows is no part of the code.", now, Totp.match(secret, spaced, now));
		assertEquals(-1, Totp.match(secret, "", now));
		assertEquals(-1, Totp.match(secret, "12345", now));
	}

	// --- What a code may do. ---

	public void testAnUnconfirmedSecretSignsNobodyIn() throws Exception {
		ContactStore.Contact petra = contact("Petra", "petra@gmx.de");
		String secret = _totp.setUp(petra.getId());
		assertNull(petra.getAuthenticator());
		assertRefused(TotpSignIns.CODE_WRONG, petra, code(secret, 0));

		_totp.confirm(petra.getId(), code(secret, 0), "client");
		assertNotNull(petra.getAuthenticator());
		assertNull(petra.getPendingAuthenticator());

		_clock.advance(Duration.ofSeconds(Totp.PERIOD_SECONDS));
		_totp.verify(petra, null, code(secret, 0), "client");
	}

	public void testSettingUpAgainKeepsTheOldAppUntilTheNewOneIsConfirmed() throws Exception {
		ContactStore.Contact petra = contact("Petra", "petra@gmx.de");
		String old = confirmed(petra);
		String fresh = _totp.setUp(petra.getId());
		_clock.advance(Duration.ofSeconds(Totp.PERIOD_SECONDS));
		_totp.verify(petra, null, code(old, 0), "client");
		assertRefused(TotpSignIns.CODE_WRONG, petra, code(fresh, 0));

		_clock.advance(Duration.ofSeconds(Totp.PERIOD_SECONDS));
		_totp.confirm(petra.getId(), code(fresh, 0), "client");
		_clock.advance(Duration.ofSeconds(Totp.PERIOD_SECONDS));
		assertRefused(TotpSignIns.CODE_WRONG, petra, code(old, 0));
		_totp.verify(petra, null, code(fresh, 0), "client");
	}

	public void testAConfirmationNeedsSomethingToConfirm() throws Exception {
		ContactStore.Contact petra = contact("Petra", "petra@gmx.de");
		try {
			_totp.confirm(petra.getId(), "123456", "client");
			fail("Nothing was set up.");
		} catch (TotpSignIns.Refused ex) {
			assertEquals(409, ex.getStatus());
			assertEquals(TotpSignIns.NOTHING_TO_CONFIRM, ex.getMessage());
		}
	}

	public void testTheStepToleranceHoldsForASignIn() throws Exception {
		ContactStore.Contact petra = contact("Petra", "petra@gmx.de");
		String secret = confirmed(petra);
		_clock.advance(Duration.ofMinutes(5));
		_totp.verify(petra, null, code(secret, -1), "client");
		_clock.advance(Duration.ofMinutes(5));
		_totp.verify(petra, null, code(secret, 1), "client");
		_clock.advance(Duration.ofMinutes(5));
		assertRefused(TotpSignIns.CODE_WRONG, petra, code(secret, -2));
		assertRefused(TotpSignIns.CODE_WRONG, petra, code(secret, 2));
	}

	public void testAReplayedCodeIsRefused() throws Exception {
		ContactStore.Contact petra = contact("Petra", "petra@gmx.de");
		String secret = confirmed(petra);
		_clock.advance(Duration.ofSeconds(Totp.PERIOD_SECONDS));
		String code = code(secret, 0);
		_totp.verify(petra, null, code, "client");
		assertRefused(TotpSignIns.CODE_WRONG, petra, code);

		// Still refused within its window, half a step later, and after a restart of the server.
		_clock.advance(Duration.ofSeconds(Totp.PERIOD_SECONDS / 2));
		assertRefused(TotpSignIns.CODE_WRONG, petra, code);
		ContactStore reread = new ContactStore(_base);
		TotpSignIns restarted = new TotpSignIns(reread, _clock);
		try {
			restarted.verify(reread.get(petra.getId()), null, code, "client");
			fail("A used code signed in after a restart.");
		} catch (TotpSignIns.Refused ex) {
			assertEquals(TotpSignIns.CODE_WRONG, ex.getMessage());
		}

		// An older code of the window is used up with it.
		_clock.advance(Duration.ofSeconds(Totp.PERIOD_SECONDS));
		_totp.verify(petra, null, code(secret, 0), "client");
		assertRefused(TotpSignIns.CODE_WRONG, petra, code(secret, -1));
	}

	public void testFiveWrongCodesLockForFifteenMinutes() throws Exception {
		ContactStore.Contact petra = contact("Petra", "petra@gmx.de");
		String secret = confirmed(petra);
		_clock.advance(Duration.ofMinutes(1));
		String wrong = wrong(secret);
		for (int n = 0; n < TotpSignIns.FAILURES; n++) {
			assertRefused(TotpSignIns.CODE_WRONG, petra, wrong);
		}
		try {
			_totp.verify(petra, null, code(secret, 0), "client");
			fail("A right code passed the lock.");
		} catch (TotpSignIns.Refused ex) {
			assertEquals(429, ex.getStatus());
			assertEquals(TotpSignIns.LOCKED, ex.getMessage());
			assertEquals(TotpSignIns.LOCK.toSeconds(), ex.getRetryAfter());
		}
		_clock.advance(TotpSignIns.LOCK.minusSeconds(Totp.PERIOD_SECONDS));
		assertRefused(TotpSignIns.LOCKED, petra, code(secret, 0));
		_clock.advance(Duration.ofSeconds(Totp.PERIOD_SECONDS));
		_totp.verify(petra, null, code(secret, 0), "client");
	}

	public void testFourWrongCodesAndARightOneLockNothing() throws Exception {
		ContactStore.Contact petra = contact("Petra", "petra@gmx.de");
		String secret = confirmed(petra);
		_clock.advance(Duration.ofMinutes(1));
		for (int n = 0; n < TotpSignIns.FAILURES - 1; n++) {
			assertRefused(TotpSignIns.CODE_WRONG, petra, wrong(secret));
		}
		_totp.verify(petra, null, code(secret, 0), "client");
		_clock.advance(Duration.ofMinutes(1));
		for (int n = 0; n < TotpSignIns.FAILURES - 1; n++) {
			assertRefused(TotpSignIns.CODE_WRONG, petra, wrong(secret));
		}
		_totp.verify(petra, null, code(secret, 0), "client");
	}

	public void testACodeOfOneContactNeverIdentifiesAnother() throws Exception {
		ContactStore.Contact petra = contact("Petra", "petra@gmx.de");
		ContactStore.Contact hans = contact("Hans", "hans@web.de");
		String petras = confirmed(petra);
		confirmed(hans);
		_clock.advance(Duration.ofMinutes(1));
		assertRefused(TotpSignIns.CODE_WRONG, hans, code(petras, 0));
		// Petra's code is still hers to use.
		_totp.verify(petra, null, code(petras, 0), "client");
	}

	public void testAStrangersAddressIsAnsweredLikeAWrongCodeAndLockedAlike() throws Exception {
		ContactStore.Contact petra = contact("Petra", "petra@gmx.de");
		String secret = confirmed(petra);
		_clock.advance(Duration.ofMinutes(1));
		String stranger = TotpSignIns.addressKey("link", "nobody@web.de");
		String own = TotpSignIns.addressKey("link", "petra@gmx.de");
		for (int n = 0; n < TotpSignIns.FAILURES; n++) {
			assertRefused(TotpSignIns.CODE_WRONG, null, stranger, wrong(secret), "a");
			assertRefused(TotpSignIns.CODE_WRONG, petra, own, wrong(secret), "b");
		}
		assertRefused(TotpSignIns.LOCKED, null, stranger, wrong(secret), "a");
		assertRefused(TotpSignIns.LOCKED, petra, own, code(secret, 0), "b");
		// Guessed through her address, Petra's authenticator is locked on her own link too.
		assertRefused(TotpSignIns.LOCKED, petra, null, code(secret, 0), "c");
	}

	public void testOneClientTryingManyAddressesIsStopped() throws Exception {
		ContactStore.Contact petra = contact("Petra", "petra@gmx.de");
		String secret = confirmed(petra);
		_clock.advance(Duration.ofMinutes(1));
		for (int n = 0; n < TotpSignIns.WRONG_PER_CLIENT; n++) {
			assertRefused(TotpSignIns.CODE_WRONG, null, TotpSignIns.addressKey("link", "x" + n + "@web.de"),
				wrong(secret), "spray");
		}
		assertRefused(TotpSignIns.LOCKED, petra, TotpSignIns.addressKey("link", "petra@gmx.de"), code(secret, 0),
			"spray");
		_totp.verify(petra, null, code(secret, 0), "elsewhere");
	}

	public void testRemovingSignsNobodyInAnyMore() throws Exception {
		ContactStore.Contact petra = contact("Petra", "petra@gmx.de");
		String secret = confirmed(petra);
		assertTrue(_totp.remove(petra.getId()));
		assertFalse(_totp.remove(petra.getId()));
		_clock.advance(Duration.ofMinutes(1));
		assertRefused(TotpSignIns.CODE_WRONG, petra, code(secret, 0));
		assertFalse("Nothing of it is written any more.",
			Files.readString(_contacts.getFile()).contains(secret));
	}

	public void testTheSecretIsStoredReadablyAndReadBack() throws Exception {
		ContactStore.Contact petra = contact("Petra", "petra@gmx.de");
		String secret = confirmed(petra);
		String stored = Files.readString(_contacts.getFile());
		assertTrue(stored, stored.contains("\"totp\""));
		assertTrue("The server computes codes from it, so it cannot be hashed.", stored.contains(secret));
		ContactStore.Contact reread = new ContactStore(_base).get(petra.getId());
		assertEquals(secret, reread.getAuthenticator().getSecret());
		assertEquals(petra.getAuthenticator().getSince(), reread.getAuthenticator().getSince());
	}

	// --- Helpers. ---

	private ContactStore.Contact contact(String name, String email) throws IOException, ContactStore.Refused {
		return _contacts.enter(_contacts.read("", name,
			Collections.singletonList(new String[] { ContactStore.EMAIL, email })), "alice");
	}

	/** Sets up and confirms an authenticator for the given contact; answers its secret. */
	private String confirmed(ContactStore.Contact contact) throws Exception {
		String secret = _totp.setUp(contact.getId());
		_totp.confirm(contact.getId(), code(secret, 0), "client");
		return secret;
	}

	/** The code of the given secret for the step the given number of steps from now. */
	private String code(String secret, int offset) {
		return Totp.code(Totp.base32Decode(secret), Totp.step(_clock.instant()) + offset);
	}

	/** A code that is none of the window's. */
	private String wrong(String secret) {
		for (int n = 0;; n++) {
			String candidate = String.format("%06d", Integer.valueOf(n));
			if (Totp.match(secret, candidate, Totp.step(_clock.instant())) < 0) {
				return candidate;
			}
		}
	}

	private void assertRefused(String message, ContactStore.Contact contact, String code) throws IOException {
		assertRefused(message, contact, null, code, "client");
	}

	private void assertRefused(String message, ContactStore.Contact contact, String key, String code, String client)
			throws IOException {
		try {
			_totp.verify(contact, key, code, client);
			fail("Expected a refusal: " + message);
		} catch (TotpSignIns.Refused ex) {
			assertEquals(message, ex.getMessage());
		}
	}
}
