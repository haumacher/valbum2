/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer.auth;

import de.haumacher.imageServer.shared.model.PairRequest;
import de.haumacher.imageServer.shared.model.PairResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Probe for issue #89 (step one): the seat code composed with what the space already knows — a
 * named administrator who lost every device, a fixed admin code after the first sign-in, a second
 * space, and an old app still sending the secret beside a code.
 */
@SuppressWarnings("javadoc")
public class TestSeatCodeProbe extends TestCase {

	private Path _base;

	private Path _other;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-seat-probe");
		_other = Files.createTempDirectory("valbum-seat-probe-other");
	}

	@Override
	protected void tearDown() throws Exception {
		delete(_base);
		delete(_other);
		super.tearDown();
	}

	/**
	 * An administrator who is named but has no device any more (they signed every device out, or
	 * an old library was written that way) gets a seat code at the next start, and redeeming it
	 * asks for no name: the name is already there.
	 */
	public void testANamedAdminWithoutDevicesGetsASeatCodeAndNeedsNoName() throws Exception {
		Path file = _base.resolve(UserStore.DIRECTORY_NAME).resolve(UserStore.FILE_NAME);
		Files.createDirectories(file.getParent());
		Files.write(file, ("{\"version\":1,\"users\":[{\"name\":\"owner\",\"role\":\"admin\",\"space\":\"\","
			+ "\"created\":\"2026-09-06T10:11:12Z\",\"devices\":[]}]}").getBytes(StandardCharsets.UTF_8));

		AuthService auth = new AuthService(AuthMode.WRITES, _base);
		DeviceCodeStore.Issued seat = auth.issueSeatCode(null);
		assertNotNull("A named administrator without a device is locked out without a seat code.", seat);
		assertEquals("owner", seat.getRecord().getUser());

		// Somebody else's name on the request is not a rename; it is a different user, refused.
		try {
			auth.pair(PairRequest.create().setDeviceCode(seat.getCode()).setDeviceName("Phone").setUserName("mallory"));
			fail("A code of 'owner' must not sign in 'mallory'.");
		} catch (AuthService.PairRefused expected) {
			assertEquals(HttpServletResponse.SC_UNAUTHORIZED, expected.getStatus());
			assertEquals(AuthService.DEVICE_CODE_OTHER_USER, expected.getMessage());
		}
		PairResponse paired = auth.pair(PairRequest.create().setDeviceCode(seat.getCode()).setDeviceName("Phone"));
		assertEquals("owner", paired.getUserName());
		assertEquals("admin", paired.getRole());
		assertEquals("The refused attempt must not have consumed the code.", 1,
			new UserStore(_base).getOwner().getDevices().size());
	}

	/** A fixed admin code is no standing master key: once the administrator is signed in, it is dead. */
	public void testAFixedAdminCodeStopsWorkingOnceTheAdminIsSignedIn() throws Exception {
		AuthService first = new AuthService(AuthMode.WRITES, _base);
		assertNotNull(first.issueSeatCode("ABCD-EFGH"));
		first.pair(PairRequest.create().setDeviceCode("ABCDEFGH").setDeviceName("Phone").setUserName("owner"));

		// The next start with the same fixed code: nothing is issued...
		AuthService second = new AuthService(AuthMode.WRITES, _base);
		assertNull("The administrator has a device; no seat code is issued.", second.issueSeatCode("ABCD-EFGH"));
		// ...and the code does not sign anybody in, dashed or not.
		for (String spelled : new String[] { "ABCD-EFGH", "abcdefgh" }) {
			try {
				second.pair(PairRequest.create().setDeviceCode(spelled).setDeviceName("Laptop").setUserName("owner"));
				fail("The fixed code '" + spelled + "' must not work after the first sign-in.");
			} catch (AuthService.PairRefused expected) {
				assertTrue(expected.getMessage(), expected.getStatus() == HttpServletResponse.SC_GONE
					|| expected.getStatus() == HttpServletResponse.SC_UNAUTHORIZED);
			}
		}
		assertEquals(1, new UserStore(_base).getOwner().getDevices().size());
	}

	/** A seat code belongs to its space: the other space does not know it. */
	public void testASeatCodeOfOneSpaceIsUnknownInAnother() throws Exception {
		AuthService a = new AuthService(AuthMode.WRITES, _base);
		AuthService b = new AuthService(AuthMode.WRITES, _other);
		String codeOfA = a.issueSeatCode(null).getCode();
		assertNotNull(b.issueSeatCode(null));

		try {
			b.pair(PairRequest.create().setDeviceCode(codeOfA).setDeviceName("Phone").setUserName("owner"));
			fail("A code of space A must be nobody in space B.");
		} catch (AuthService.PairRefused expected) {
			assertEquals(HttpServletResponse.SC_UNAUTHORIZED, expected.getStatus());
			assertEquals(AuthService.DEVICE_CODE_UNKNOWN, expected.getMessage());
		}
		assertTrue("Space B's administrator is untouched.", new UserStore(_other).getOwner().getDevices().isEmpty());
		// And the code still works where it belongs.
		assertEquals("owner", a.pair(PairRequest.create().setDeviceCode(codeOfA).setDeviceName("Phone")
			.setUserName("owner")).getUserName());
	}

	/** An app from before this build that still fills the secret beside the code is signed in by the code. */
	public void testTheCodeWinsOverAStaleSecret() throws Exception {
		AuthService auth = new AuthService(AuthMode.WRITES, _base);
		String code = auth.issueSeatCode(null).getCode();

		PairResponse paired = auth.pair(PairRequest.create().setSecret("demo").setDeviceCode(code)
			.setDeviceName("Phone").setUserName("owner"));
		assertEquals("owner", paired.getUserName());

		// Without a code the secret alone is answered with the retirement, and nothing is created.
		try {
			auth.pair(PairRequest.create().setSecret("demo").setDeviceName("Tablet").setUserName("owner"));
			fail("The secret is retired.");
		} catch (AuthService.PairRefused expected) {
			assertEquals(HttpServletResponse.SC_GONE, expected.getStatus());
			assertEquals(AuthService.SECRET_RETIRED, expected.getMessage());
		}
		assertEquals(1, new UserStore(_base).getOwner().getDevices().size());
	}

	/** A recovery code for a user who does not exist creates nobody, and the admin's own codes stay. */
	public void testARecoveryCodeNamesAnExistingUserOnly() throws Exception {
		AuthService auth = new AuthService(AuthMode.WRITES, _base);
		PairResponse admin = auth.pair(PairRequest.create().setDeviceCode(auth.issueSeatCode(null).getCode())
			.setDeviceName("Phone").setUserName("owner"));
		UserStore.User owner = auth.getUsers().getOwner();
		AuthService.Caller caller = AuthService.Caller.signedIn(owner, owner.getDevices().get(0));

		try {
			auth.deviceCode(caller, "nobody");
			fail("There is no such user.");
		} catch (AuthService.Refused expected) {
			assertEquals(HttpServletResponse.SC_NOT_FOUND, expected.getStatus());
		}
		assertEquals("No user was created by the attempt.", 1, auth.getUsers().getUsers().size());
		// A code for oneself by name is the ordinary device code.
		DeviceCodeStore.Issued own = auth.deviceCode(caller, "owner");
		assertEquals("owner", own.getRecord().getUser());
		assertEquals(owner.getDevices().get(0).getId(), own.getRecord().getIssuedBy());
		assertFalse(admin.getToken().isEmpty());
	}

	private static void delete(Path dir) throws Exception {
		if (dir == null || !Files.exists(dir)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(dir)) {
			walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		}
	}

}
