/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.DeviceCodeStore;
import de.haumacher.imageServer.auth.DeviceCodeStore.Code;
import de.haumacher.imageServer.auth.DeviceCodeStore.Issued;
import de.haumacher.imageServer.auth.UserStore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Test case for the {@link DeviceCodeStore} of issue #65.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestDeviceCodeStore extends TestCase {

	/** A clock a test moves by hand, so that eleven minutes need not be waited for. */
	static final class MutableClock extends Clock {

		private Instant _now;

		MutableClock(Instant now) {
			_now = now;
		}

		/** Moves this clock forward by the given amount. */
		void advance(Duration amount) {
			_now = _now.plus(amount);
		}

		/** Sets this clock to the given moment. */
		void set(Instant now) {
			_now = now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return _now;
		}
	}

	private static final Instant START = Instant.parse("2026-09-15T10:00:00Z");

	private Path _base;

	private MutableClock _clock;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-device-code-store-test");
		_clock = new MutableClock(START);
	}

	@Override
	protected void tearDown() throws Exception {
		if (_base != null) {
			try (Stream<Path> files = Files.walk(_base)) {
				files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
		super.tearDown();
	}

	private DeviceCodeStore store() {
		return new DeviceCodeStore(_base, _clock);
	}

	// --- What is written. ---

	public void testACreatedCodeIsWrittenAsAHashAndNeverAsItself() throws Exception {
		DeviceCodeStore store = store();

		Issued issued = store.create("alice", "dev-1");

		String contents = read(store.getFile());
		assertTrue("The store must be versioned: " + contents, contents.contains("\"version\":1"));
		assertTrue("Every field of the documented format is written: " + contents,
			contents.contains("\"revoked\":\"\""));
		assertFalse("The code itself must never be stored: " + contents, contents.contains(issued.getCode()));
		assertTrue("The store must hold the code's hash: " + contents,
			contents.contains(UserStore.hash(issued.getCode())));
		assertEquals("A hash of SHA-256 is 64 hex characters.", 64, issued.getRecord().getCodeHash().length());
		assertTrue("The hash is lower-case hex: " + issued.getRecord().getCodeHash(),
			issued.getRecord().getCodeHash().matches("[0-9a-f]{64}"));
		assertEquals("The store lives beside the other stores of the server.",
			_base.resolve(UserStore.DIRECTORY_NAME).resolve(DeviceCodeStore.FILE_NAME), store.getFile());
		assertEquals(1, store.getCodes().size());
		assertEquals("alice", issued.getRecord().getUser());
		assertEquals("dev-1", issued.getRecord().getIssuedBy());
	}

	public void testACodeIsEightCharactersOfAnAlphabetWithoutLookAlikes() throws Exception {
		DeviceCodeStore store = store();

		for (int n = 0; n < 50; n++) {
			String code = store.create("alice", "dev-1").getCode();
			assertEquals("A code is " + DeviceCodeStore.CODE_LENGTH + " characters: " + code,
				DeviceCodeStore.CODE_LENGTH, code.length());
			for (int i = 0; i < code.length(); i++) {
				assertTrue("'" + code.charAt(i) + "' is not one of the code characters: " + code,
					DeviceCodeStore.ALPHABET.indexOf(code.charAt(i)) >= 0);
			}
			assertEquals("Nothing that can be read as something else: " + code, -1, code.indexOf('0'));
			assertEquals(-1, code.indexOf('O'));
			assertEquals(-1, code.indexOf('1'));
			assertEquals(-1, code.indexOf('I'));
		}
	}

	// --- Finding a code again. ---

	public void testACodeIsFoundHoweverItIsSpelled() throws Exception {
		DeviceCodeStore store = store();
		Issued issued = store.create("alice", "dev-1");
		String code = issued.getCode();
		String dashed = DeviceCodeStore.format(code);

		assertEquals("The code is shown grouped in two halves.", 9, dashed.length());
		assertEquals('-', dashed.charAt(4));

		assertSame(issued.getRecord(), store.lookup(code));
		assertSame("The dash is decoration.", issued.getRecord(), store.lookup(dashed));
		assertSame("Shouting is not required.", issued.getRecord(), store.lookup(dashed.toLowerCase()));
		assertSame("A space is as good as a dash.",
			issued.getRecord(), store.lookup(code.substring(0, 4) + " " + code.substring(4)));
		assertSame(issued.getRecord(), store.lookup("  " + code.toLowerCase() + "  "));
	}

	public void testTheNormalisedFormsOfTheSameCodeAgree() throws Exception {
		assertEquals("ABCD2345", DeviceCodeStore.normalise("abcd-2345"));
		assertEquals("ABCD2345", DeviceCodeStore.normalise("ABCD 2345"));
		assertEquals("ABCD2345", DeviceCodeStore.normalise("ABCD2345"));
		assertEquals("ABCD2345", DeviceCodeStore.normalise(" abcd 2345 "));
		assertEquals("", DeviceCodeStore.normalise(null));
		assertEquals("ABCD-2345", DeviceCodeStore.format("ABCD2345"));
	}

	public void testAWrongCodeFindsNothing() throws Exception {
		DeviceCodeStore store = store();
		store.create("alice", "dev-1");

		assertNull(store.lookup("ZZZZ-ZZZZ"));
		assertNull(store.lookup(""));
		assertNull(store.lookup(null));
		assertNull(store.get("no such id"));
	}

	// --- Ten minutes, once. ---

	public void testACodeLivesTenMinutes() throws Exception {
		DeviceCodeStore store = store();

		Code code = store.create("alice", "dev-1").getRecord();

		assertEquals(START.toString(), code.getCreated());
		assertEquals("A code lives ten minutes, never longer.",
			START.plus(Duration.ofMinutes(DeviceCodeStore.LIFETIME_MINUTES)).toString(), code.getExpires());
		assertFalse(code.isExpired(START.plus(Duration.ofMinutes(9))));
		assertTrue(code.isExpired(START.plus(Duration.ofMinutes(11))));
		assertFalse("A fresh code pairs a device.", code.isDead(START));
	}

	public void testAUsedCodeIsMarkedAndKept() throws Exception {
		DeviceCodeStore store = store();
		Issued issued = store.create("alice", "dev-1");

		_clock.advance(Duration.ofMinutes(2));
		Code used = store.markUsed(issued.getRecord().getId(), "dev-2");

		assertTrue(used.isUsed());
		assertEquals("dev-2", used.getUsedBy());
		assertEquals(START.plus(Duration.ofMinutes(2)).toString(), used.getUsed());
		assertTrue("A used code pairs nothing more.", used.isDead(START.plus(Duration.ofMinutes(2))));
		assertEquals("The record stays, so that the next attempt can be told why.", 1, store.getCodes().size());
		assertSame(used, store.lookup(issued.getCode()));
		assertNull(store.markUsed("no such id", "dev-3"));
	}

	public void testAUsedCodeIsNotUsedTwice() throws Exception {
		DeviceCodeStore store = store();
		Issued issued = store.create("alice", "dev-1");
		store.markUsed(issued.getRecord().getId(), "dev-2");

		_clock.advance(Duration.ofMinutes(1));
		Code again = store.markUsed(issued.getRecord().getId(), "dev-3");

		assertEquals("The first device keeps the code.", "dev-2", again.getUsedBy());
		assertEquals(START.toString(), again.getUsed());
	}

	public void testAUserMayHoldSeveralLiveCodes() throws Exception {
		DeviceCodeStore store = store();

		Issued first = store.create("alice", "dev-1");
		Issued second = store.create("alice", "dev-1");

		assertFalse("Two askings give two codes.", first.getCode().equals(second.getCode()));
		assertEquals(2, store.getCodes().size());
		assertFalse(first.getRecord().isDead(START));
		assertFalse(second.getRecord().isDead(START));
	}

	// --- A code is only as good as the device that made it. ---

	public void testTheCodesOfADeviceAreWithdrawnWithIt() throws Exception {
		DeviceCodeStore store = store();
		Issued pending = store.create("alice", "dev-1");
		Issued other = store.create("alice", "dev-2");
		Issued spent = store.create("alice", "dev-1");
		store.markUsed(spent.getRecord().getId(), "dev-9");

		_clock.advance(Duration.ofMinutes(1));
		assertEquals("Only the unspent codes of that device.", 1, store.revokeIssuedBy("dev-1"));

		assertTrue(pending.getRecord().isRevoked());
		assertEquals(START.plus(Duration.ofMinutes(1)).toString(), pending.getRecord().getRevoked());
		assertTrue("A withdrawn code pairs nothing.",
			pending.getRecord().isDead(START.plus(Duration.ofMinutes(1))));
		assertFalse("A code of another device is untouched.", other.getRecord().isRevoked());
		assertFalse("A spent code is history, not something to withdraw.", spent.getRecord().isRevoked());
		assertSame("The record stays, so that the refusal can say what happened.",
			pending.getRecord(), store.lookup(pending.getCode()));
	}

	public void testWithdrawingNothingWritesNothing() throws Exception {
		DeviceCodeStore store = store();
		store.create("alice", "dev-1");
		String before = read(store.getFile());

		assertEquals(0, store.revokeIssuedBy("dev-7"));
		assertEquals(0, store.revokeIssuedBy(""));
		assertEquals(0, store.revokeIssuedBy(null));

		assertEquals(before, read(store.getFile()));
	}

	public void testAWithdrawnCodeIsReadBackAsWithdrawn() throws Exception {
		DeviceCodeStore written = store();
		Issued pending = written.create("alice", "dev-1");
		written.revokeIssuedBy("dev-1");

		DeviceCodeStore read = new DeviceCodeStore(_base, _clock);

		assertTrue(read.lookup(pending.getCode()).isRevoked());
		assertEquals(fingerprint(written.getCodes()), fingerprint(read.getCodes()));
	}

	public void testAWithdrawnCodeIsForgottenADayAfterItWentAway() throws Exception {
		DeviceCodeStore store = store();
		Issued pending = store.create("alice", "dev-1");
		store.revokeIssuedBy("dev-1");

		_clock.set(START.plus(Duration.ofHours(23)));
		store.create("alice", "dev-2");
		assertNotNull(store.get(pending.getRecord().getId()));

		_clock.set(START.plus(Duration.ofHours(25)));
		store.create("alice", "dev-2");
		assertNull(store.get(pending.getRecord().getId()));
	}

	// --- Forgetting what died long ago. ---

	public void testACodeDeadForTwentyFiveHoursIsDroppedOnTheNextWrite() throws Exception {
		DeviceCodeStore store = store();
		String old = store.create("alice", "dev-1").getRecord().getId();

		// Dead since it ran out, ten minutes after it was issued.
		_clock.set(START.plus(Duration.ofMinutes(DeviceCodeStore.LIFETIME_MINUTES)).plus(Duration.ofHours(25)));
		store.create("alice", "dev-1");

		assertNull("What has been dead for a day is forgotten.", store.get(old));
		assertEquals(1, store.getCodes().size());
		assertNull("And it is gone from the file as well.", new DeviceCodeStore(_base, _clock).get(old));
	}

	public void testACodeDeadForTwentyThreeHoursIsKept() throws Exception {
		DeviceCodeStore store = store();
		String old = store.create("alice", "dev-1").getRecord().getId();

		_clock.set(START.plus(Duration.ofMinutes(DeviceCodeStore.LIFETIME_MINUTES)).plus(Duration.ofHours(23)));
		store.create("alice", "dev-1");

		assertNotNull("'Expired' must still be tellable from 'used' for a day.", store.get(old));
		assertEquals(2, store.getCodes().size());
	}

	public void testAUsedCodeIsForgottenADayAfterItWasTyped() throws Exception {
		DeviceCodeStore store = store();
		Issued issued = store.create("alice", "dev-1");
		// Typed right away, so that the expiry is not what makes it old.
		store.markUsed(issued.getRecord().getId(), "dev-2");

		_clock.set(START.plus(Duration.ofHours(23)));
		store.create("alice", "dev-1");
		assertNotNull("A day is a day.", store.get(issued.getRecord().getId()));

		_clock.set(START.plus(Duration.ofHours(25)));
		store.create("alice", "dev-1");
		assertNull(store.get(issued.getRecord().getId()));
	}

	// --- The file. ---

	public void testAMissingFileReadsAsAnEmptyStore() throws Exception {
		DeviceCodeStore store = store();

		assertTrue(store.getCodes().isEmpty());
		assertFalse("Reading must never create the file.", Files.exists(store.getFile()));
		assertNull(store.lookup("ABCD-2345"));
	}

	public void testAStoreIsReadBackAsItWasWritten() throws Exception {
		DeviceCodeStore written = store();
		Issued live = written.create("alice", "dev-1");
		Issued spent = written.create("bob", "dev-9");
		written.markUsed(spent.getRecord().getId(), "dev-10");

		DeviceCodeStore read = new DeviceCodeStore(_base, _clock);
		assertEquals(fingerprint(written.getCodes()), fingerprint(read.getCodes()));
		assertSame("A code written by this build is honoured by it.",
			read.lookup(live.getCode()), read.get(live.getRecord().getId()));
		assertTrue(read.get(spent.getRecord().getId()).isUsed());

		// read -> write -> read again is equal.
		read.store();
		DeviceCodeStore again = new DeviceCodeStore(_base, _clock);
		assertEquals(fingerprint(read.getCodes()), fingerprint(again.getCodes()));
		assertNotNull(again.lookup(live.getCode()));
	}

	/** Every field of every code, so that a round trip can be compared as a whole. */
	private static String fingerprint(List<Code> codes) {
		List<String> lines = new ArrayList<>();
		for (Code code : codes) {
			lines.add(code.getId() + "|" + code.getCodeHash() + "|" + code.getUser() + "|" + code.getIssuedBy()
				+ "|" + code.getCreated() + "|" + code.getExpires() + "|" + code.getUsed() + "|" + code.getUsedBy()
				+ "|" + code.getRevoked());
		}
		return String.join("\n", lines);
	}

	private static String read(Path file) throws Exception {
		return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
	}
}
