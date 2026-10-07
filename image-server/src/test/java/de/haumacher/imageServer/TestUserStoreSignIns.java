/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.ContactStore;
import de.haumacher.imageServer.auth.SignIns;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.auth.UserStore.User;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Test case for the ways a member signs in that <code>users.json</code> keeps since issue #233:
 * the authenticator app, the passkeys and the proven addresses, as optional fields &mdash; a store
 * the release before wrote reads, and writes back, unchanged.
 */
@SuppressWarnings("javadoc")
public class TestUserStoreSignIns extends TestCase {

	/** A <code>users.json</code> exactly as release 2.10 writes it. */
	private static final Path FIXTURE = Paths.get("src/test/fixtures/users-store/users-2.10.json");

	private Path _base;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-user-sign-ins");
	}

	@Override
	protected void tearDown() throws Exception {
		try (Stream<Path> files = Files.walk(_base)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		super.tearDown();
	}

	public void testAStoreOfTheReleaseBeforeReadsAndWritesUnchanged() throws Exception {
		String before = Files.readString(FIXTURE, StandardCharsets.UTF_8);
		Path file = _base.resolve(UserStore.DIRECTORY_NAME).resolve(UserStore.FILE_NAME);
		Files.createDirectories(file.getParent());
		Files.writeString(file, before, StandardCharsets.UTF_8);

		UserStore store = new UserStore(_base);
		assertEquals(3, store.getUsers().size());
		User haui = store.getUser("haui");
		assertEquals("admin", haui.getRole());
		assertEquals("dev1", haui.getDevices().get(0).getId());
		assertNotNull(store.lookup("haui-token"));
		User bob = store.getUser("bob");
		assertEquals("inv1", bob.getInvitation());
		assertEquals("Bob B.", bob.getRecipient());
		assertTrue(store.getInvited("inv2").isPending());
		for (User user : store.getUsers()) {
			assertNull(user.getSignIns().getAuthenticator());
			assertNull(user.getSignIns().getPendingAuthenticator());
			assertTrue(user.getSignIns().getPasskeys().isEmpty());
			assertTrue(user.getAddresses().isEmpty());
		}

		store.store();
		assertEquals("Written back byte for byte.", before, Files.readString(file, StandardCharsets.UTF_8));
	}

	public void testTheWaysToSignInRoundTrip() throws Exception {
		Files.createDirectories(_base.resolve(UserStore.DIRECTORY_NAME));
		Files.copy(FIXTURE, _base.resolve(UserStore.DIRECTORY_NAME).resolve(UserStore.FILE_NAME));
		UserStore store = new UserStore(_base);
		User haui = store.getUser("haui");
		Instant now = Instant.parse("2026-10-07T10:00:00Z");
		assertTrue(store.change(haui, signIns -> signIns.startTotp("JBSWY3DPEHPK3PXP", now)));
		assertTrue(store.change(haui, signIns -> signIns.confirmTotp("JBSWY3DPEHPK3PXP", 42, now)));
		assertTrue(store.addPasskey(haui, new SignIns.Passkey("cred-1", now.toString(), "", "data", 3, true, true,
			false, List.of("internal"))));
		assertNull(store.addAddress(haui, "haui@example.org", now));
		String written = Files.readString(store.getFile());
		assertTrue("The secret is readable to the server, which makes the file sensitive.",
			written.contains("JBSWY3DPEHPK3PXP"));

		UserStore reread = new UserStore(_base);
		User again = reread.getUser("haui");
		assertEquals("JBSWY3DPEHPK3PXP", again.getSignIns().getAuthenticator().getSecret());
		assertEquals(42, again.getSignIns().getAuthenticator().getLastStep());
		assertEquals("cred-1", again.getSignIns().getPasskeys().get(0).getId());
		assertEquals(List.of("internal"), again.getSignIns().getPasskeys().get(0).getTransports());
		ContactStore.Address address = again.getAddresses().get(0);
		assertEquals("haui@example.org", address.getValue());
		assertTrue(address.isProven());
		assertEquals(now.toString(), address.getSince());
		assertSame(again, reread.byEmail("haui@example.org"));
		assertSame(again, reread.byPasskey("cred-1"));

		reread.store();
		assertEquals("read, write, read is equal", written, Files.readString(reread.getFile()));
		// Bob, who has none, is written exactly as before.
		assertFalse(written.substring(written.indexOf("\"name\":\"bob\"")).contains("totp"));
	}

	public void testAPendingInvitationHoldsNoWayToSignIn() throws Exception {
		Files.createDirectories(_base.resolve(UserStore.DIRECTORY_NAME));
		Files.copy(FIXTURE, _base.resolve(UserStore.DIRECTORY_NAME).resolve(UserStore.FILE_NAME));
		UserStore store = new UserStore(_base);
		User pending = store.getInvited("inv2");
		assertFalse(store.change(pending, signIns -> signIns.startTotp("JBSWY3DPEHPK3PXP", Instant.now())));
		assertNull(pending.getSignIns().getPendingAuthenticator());
	}
}
