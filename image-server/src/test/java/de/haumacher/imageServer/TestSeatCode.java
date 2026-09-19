/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.DeviceCodeStore;
import de.haumacher.imageServer.auth.InviteMode;
import de.haumacher.imageServer.auth.SpaceMode;
import de.haumacher.imageServer.auth.Spaces;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.shared.model.PairRequest;
import de.haumacher.imageServer.shared.model.PairResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Test case for the seat code of issue #89: how a space gets its administrator.
 *
 * <p>
 * The pairing secret is gone. A space has an administrator from the moment its folder does —
 * nameless, without devices — and the server prints one ordinary single-use device code for them
 * while that is still true. Whoever redeems it chooses the name they will be known by, and from
 * then on the space prints nothing.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestSeatCode extends TestCase {

	private Path _base;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-seat-code");
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

	// --- A fresh library. ---

	/** A space has its administrator the moment it exists: nameless, without devices. */
	public void testAFreshLibraryHasANamelessAdmin() throws Exception {
		Spaces spaces = detect(null);

		UserStore users = new UserStore(_base);
		assertEquals(1, users.getUsers().size());
		UserStore.User admin = users.getOwner();
		assertNotNull("The seat is never empty, only unnamed.", admin);
		assertEquals("", admin.getName());
		assertEquals("admin", admin.getRole());
		assertTrue(admin.getDevices().isEmpty());
		assertEquals(spaces.single().getAuth().getUsers().getOwner().getRole(), admin.getRole());
	}

	/** The start-up issues one code for that administrator and says so in one line. */
	public void testTheStartUpPrintsTheSeatCode() throws Exception {
		Spaces spaces = detect(null);

		List<String> lines = Main.reportSpace(spaces, spaces.single(), null);

		DeviceCodeStore codes = spaces.single().getAuth().getDeviceCodes();
		assertEquals(1, codes.getCodes().size());
		DeviceCodeStore.Code record = codes.getCodes().get(0);
		assertEquals("The server itself issued it; there is no device to die with.",
			DeviceCodeStore.SERVER_ISSUER, record.getIssuedBy());
		assertEquals("It signs in the administrator, who has no name yet.", "", record.getUser());
		assertFalse(record.isUsed());
		assertFalse(record.isRevoked());
		assertFalse(record.isExpired(Instant.now()));

		String line = lines.get(lines.size() - 1);
		assertTrue("The report must name the code: " + line, line.contains("sign the administrator in with the code"));
		assertTrue("The code is shown grouped: " + line, line.matches(".*[A-Z2-9]{4}-[A-Z2-9]{4}.*"));
		assertTrue("And how long it works: " + line, line.contains("10 minutes"));
	}

	/** A code for a user who has no name is refused until the pairing carries one. */
	public void testTheSeatCodeNeedsAName() throws Exception {
		AuthService auth = new AuthService(AuthMode.WRITES, _base);
		String code = auth.issueSeatCode(null).getCode();

		try {
			auth.pair(PairRequest.create().setDeviceCode(code).setDeviceName("Phone"));
			fail("A user without a name is nobody the space can talk about.");
		} catch (AuthService.PairRefused expected) {
			assertEquals(HttpServletResponse.SC_BAD_REQUEST, expected.getStatus());
			assertEquals(AuthService.NAME_REQUIRED, expected.getMessage());
		}

		PairResponse paired =
			auth.pair(PairRequest.create().setDeviceCode(code).setDeviceName("Phone").setUserName("owner"));
		assertEquals("owner", paired.getUserName());
		assertEquals("admin", paired.getRole());
		assertFalse(paired.getToken().isEmpty());
		assertEquals("owner", new UserStore(_base).getOwner().getName());
	}

	/** One code, one device: the second try is told that it was already used. */
	public void testTheSeatCodeWorksOnce() throws Exception {
		AuthService auth = new AuthService(AuthMode.WRITES, _base);
		String code = auth.issueSeatCode(null).getCode();
		auth.pair(PairRequest.create().setDeviceCode(code).setDeviceName("Phone").setUserName("owner"));

		try {
			auth.pair(PairRequest.create().setDeviceCode(code).setDeviceName("Tablet").setUserName("owner"));
			fail("A single-use code must not survive its own success.");
		} catch (AuthService.PairRefused expected) {
			assertEquals(HttpServletResponse.SC_GONE, expected.getStatus());
			assertEquals(AuthService.DEVICE_CODE_USED, expected.getMessage());
		}
	}

	/** Ten minutes, like every other code; the clock decides, not the store. */
	public void testTheSeatCodeExpires() throws Exception {
		AuthService auth = new AuthService(AuthMode.WRITES, _base);
		String code = auth.issueSeatCode(null).getCode();

		// Eleven minutes later, by a store that is handed a different "now".
		DeviceCodeStore later = new DeviceCodeStore(_base,
			Clock.fixed(Instant.now().plus(Duration.ofMinutes(11)), ZoneOffset.UTC));
		assertTrue(later.lookup(code).isExpired(later.getClock().instant()));
	}

	/** Once the administrator has a device, no code is printed any more. */
	public void testASecondStartUpPrintsNoCode() throws Exception {
		Spaces first = detect(null);
		Main.reportSpace(first, first.single(), null);
		AuthService auth = first.single().getAuth();
		auth.pair(PairRequest.create().setDeviceCode(seatCodeOf(auth)).setDeviceName("Phone").setUserName("owner"));

		Spaces second = detect(null);
		List<String> lines = Main.reportSpace(second, second.single(), null);

		assertNull("A space whose admin has a device needs no seat code.",
			second.single().getAuth().issueSeatCode(null));
		for (String line : lines) {
			assertFalse("Nothing may print a code here: " + line, line.contains("with the code"));
		}
	}

	/** A restart withdraws the code of the previous start: one live seat code at a time. */
	public void testARestartWithdrawsTheEarlierCode() throws Exception {
		AuthService first = new AuthService(AuthMode.WRITES, _base);
		String earlier = first.issueSeatCode(null).getCode();

		AuthService second = new AuthService(AuthMode.WRITES, _base);
		String later = second.issueSeatCode(null).getCode();
		assertFalse(earlier.equals(later));

		try {
			second.pair(PairRequest.create().setDeviceCode(earlier).setDeviceName("Phone").setUserName("owner"));
			fail("The code of the previous start is worth nothing.");
		} catch (AuthService.PairRefused expected) {
			assertEquals(HttpServletResponse.SC_GONE, expected.getStatus());
			assertEquals(AuthService.DEVICE_CODE_ISSUER_GONE, expected.getMessage());
		}
		assertEquals("owner",
			second.pair(PairRequest.create().setDeviceCode(later).setDeviceName("Phone").setUserName("owner"))
				.getUserName());
	}

	// --- Several spaces. ---

	/** One code per space, and a code of one space is simply unknown in another. */
	public void testEachSpaceHasItsOwnSeatCode() throws Exception {
		space("alice");
		space("bob");
		Spaces spaces = detect(SpaceMode.MULTI);
		Spaces.Space alice = spaces.bySegment("alice");
		Spaces.Space bob = spaces.bySegment("bob");

		String aliceCode = seatCodeOf(alice.getAuth());
		String bobCode = seatCodeOf(bob.getAuth());
		assertFalse(aliceCode.equals(bobCode));

		try {
			alice.getAuth()
				.pair(PairRequest.create().setDeviceCode(bobCode).setDeviceName("Phone").setUserName("owner"));
			fail("A code of another space is no code here.");
		} catch (AuthService.PairRefused expected) {
			assertEquals(AuthService.DEVICE_CODE_UNKNOWN, expected.getMessage());
		}

		// Names are per space: both administrators may be called the same.
		assertEquals("owner", alice.getAuth()
			.pair(PairRequest.create().setDeviceCode(aliceCode).setDeviceName("Phone").setUserName("owner"))
			.getUserName());
		assertEquals("owner", bob.getAuth()
			.pair(PairRequest.create().setDeviceCode(bobCode).setDeviceName("Phone").setUserName("owner"))
			.getUserName());
		assertEquals("owner", new UserStore(_base.resolve("alice")).getOwner().getName());
		assertEquals("owner", new UserStore(_base.resolve("bob")).getOwner().getName());
	}

	// --- The fixed code of --admin-code. ---

	/** What <code>--admin-code</code> fixes is the code that is issued, spelled either way. */
	public void testTheFixedAdminCodeIsTheSeatCode() throws Exception {
		Spaces spaces = detect(null);
		List<String> lines = Main.reportSpace(spaces, spaces.single(), "ABCD-EFGH");

		assertTrue(lines.get(lines.size() - 1).contains("ABCD-EFGH"));
		AuthService auth = spaces.single().getAuth();
		assertNotNull("The dash is decoration.", auth.getDeviceCodes().lookup("ABCDEFGH"));
		assertEquals("owner", auth
			.pair(PairRequest.create().setDeviceCode("ABCD-EFGH").setDeviceName("Phone").setUserName("owner"))
			.getUserName());
	}

	/** A fixed code nobody could type is refused before the server starts. */
	public void testAnUnusableAdminCodeRefusesTheStart() throws Exception {
		assertNull(Main.adminCodeProblem(null));
		assertNull(Main.adminCodeProblem("ABCD-EFGH"));
		assertNull(Main.adminCodeProblem("abcdefgh"));

		// 'O' and '0' are not in the alphabet, on purpose: nobody may have to guess.
		assertTrue(Main.adminCodeProblem("DEMO-CODE").contains("is no sign-in code"));
		assertTrue(Main.adminCodeProblem("ABCDEFG").contains("is no sign-in code"));
		assertTrue(Main.adminCodeProblem("ABCDEFGHI").contains("is no sign-in code"));
	}

	// --- The retired pairing secret. ---

	/** A pairing that still carries the secret is told what replaced it. */
	public void testTheRetiredSecretIsAnswered() throws Exception {
		AuthService auth = new AuthService(AuthMode.WRITES, _base);

		try {
			auth.pair(PairRequest.create().setSecret("let-me-in").setDeviceName("Phone").setUserName("haui"));
			fail("There is no pairing secret any more.");
		} catch (AuthService.PairRefused expected) {
			assertEquals(HttpServletResponse.SC_GONE, expected.getStatus());
			assertEquals(AuthService.SECRET_RETIRED, expected.getMessage());
			assertTrue(expected.getMessage(), expected.getMessage().contains("prints at start-up"));
		}
		assertTrue("Nobody was signed in.", new UserStore(_base).getOwner().getDevices().isEmpty());
	}

	/** The command line says it too, and the server does not start. */
	public void testTheRetiredOptionRefusesTheStart() {
		assertNull(Main.retiredSecret(null));
		assertNull(Main.retiredSecret(""));
		String message = Main.retiredSecret("my-secret");
		assertNotNull(message);
		assertTrue(message, message.contains("--pairing-secret is gone"));
		assertTrue(message, message.contains("--admin-code"));
	}

	// --- Libraries written by an earlier build. ---

	/** A named administrator with devices: nothing happens, and no code is printed. */
	public void testALegacyLibraryWithANamedAdminIsLeftAlone() throws Exception {
		users("haui");
		Spaces spaces = detect(null);
		List<String> lines = Main.reportSpace(spaces, spaces.single(), null);

		assertEquals("haui", new UserStore(_base).getOwner().getName());
		assertNull("A signed-in administrator needs no seat code.",
			spaces.single().getAuth().issueSeatCode(null));
		for (String line : lines) {
			assertFalse(line, line.contains("with the code"));
		}
		assertEquals("Every device keeps working.", "haui",
			spaces.single().getAuth().getUsers().lookup("old-token").getUser().getName());
	}

	/** A nameless administrator <em>with</em> devices is an old library and is named, see #86. */
	public void testALegacyNamelessAdminWithDevicesIsNamed() throws Exception {
		users("");
		Spaces spaces = detect(null);
		List<String> lines = Main.reportSpace(spaces, spaces.single(), null);

		assertEquals("owner", new UserStore(_base).getOwner().getName());
		assertNull(spaces.single().getAuth().issueSeatCode(null));
		assertTrue(lines.get(0), lines.get(0).contains("had no name"));
		for (String line : lines) {
			assertFalse(line, line.contains("with the code"));
		}
		assertEquals("Every device keeps working.", "owner",
			spaces.single().getAuth().getUsers().lookup("old-token").getUser().getName());
	}

	/**
	 * A nameless administrator <em>without</em> devices is the fresh seat of issue #89 and is
	 * <em>not</em> named — that choice belongs to whoever redeems the code.
	 */
	public void testAFreshSeatIsNotNamedByTheStartUp() throws Exception {
		Spaces spaces = detect(null);
		List<String> lines = Main.reportSpace(spaces, spaces.single(), null);

		assertEquals("", new UserStore(_base).getOwner().getName());
		for (String line : lines) {
			assertFalse(line, line.contains("had no name"));
		}
		assertTrue(lines.get(lines.size() - 1).contains("with the code"));
	}

	// --- Helpers. ---

	private Spaces detect(SpaceMode forced) throws Exception {
		return Spaces.detect(_base, forced, AuthMode.WRITES, InviteMode.MEMBERS);
	}

	private static String seatCodeOf(AuthService auth) throws Exception {
		DeviceCodeStore.Issued issued = auth.issueSeatCode(null);
		assertNotNull("This space has no administrator to sign in.", issued);
		return issued.getCode();
	}

	/** A folder below the base folder that is a space. */
	private void space(String name) throws Exception {
		Path root = _base.resolve(name).resolve(UserStore.DIRECTORY_NAME);
		Files.createDirectories(root);
		Files.write(root.resolve("space.json"), "{}".getBytes(StandardCharsets.UTF_8));
	}

	/** A user store as an earlier build wrote it: one administrator with one device. */
	private void users(String name) throws Exception {
		Path file = _base.resolve(UserStore.DIRECTORY_NAME).resolve(UserStore.FILE_NAME);
		Files.createDirectories(file.getParent());
		Files.write(file, ("{\"version\":1,\"users\":[{\"name\":\"" + name + "\",\"role\":\"admin\","
			+ "\"space\":\"\",\"created\":\"2026-09-06T10:11:12Z\",\"devices\":[{\"id\":\"aaaaaaaa\","
			+ "\"name\":\"Old phone\",\"tokenHash\":\"" + UserStore.hash("old-token")
			+ "\",\"created\":\"2026-09-06T10:11:12Z\"}]}]}").getBytes(StandardCharsets.UTF_8));
	}
}
