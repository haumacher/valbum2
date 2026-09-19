/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.DeviceCodeStore;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.shared.model.DeviceCodeCreated;
import de.haumacher.imageServer.shared.model.DeviceEntry;
import de.haumacher.imageServer.shared.model.DeviceList;
import de.haumacher.imageServer.shared.model.PairResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test case for the backup code of issue #92: the way back from signing out of one's last device.
 *
 * <p>
 * Everything about it is the device code of issue #65 said once more, which is the point — it is a
 * code, not a second credential system. Two values differ, and each of them is a test here: it has
 * no expiry, and it does not die with the device that made it, because the device that made it is
 * precisely the one that will be gone. It is sixteen characters instead of eight because it is
 * kept, it works once like every code, and there is one per user at a time.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestBackupCode extends InviteTestCase {

	// --- Making one. ---

	public void testABackupCodeIsSixteenCharactersOfTheSameAlphabetAndNeverRunsOut() throws Exception {
		String owner = signInOwner();

		FakeResponse response = backupCode(owner);

		assertEquals(HttpServletResponse.SC_OK, response.status());
		DeviceCodeCreated created = code(response);
		String alphabet = "[" + DeviceCodeStore.ALPHABET + "]{4}";
		assertTrue("A backup code is shown as XXXX-XXXX-XXXX-XXXX: " + created.getCode(),
			created.getCode().matches(alphabet + "-" + alphabet + "-" + alphabet + "-" + alphabet));
		assertEquals("Sixteen characters, eighty bits: it is kept, not typed within ten minutes.",
			DeviceCodeStore.BACKUP_CODE_LENGTH, DeviceCodeStore.normalise(created.getCode()).length());
		assertEquals("An empty expiry is the whole point: it is written down for a day nobody knows.",
			"", created.getExpires());
	}

	public void testTheCodeItselfIsNeverStoredAndIsAnsweredExactlyOnce() throws Exception {
		String owner = signInOwner();
		DeviceCodeCreated created = code(backupCode(owner));

		String contents = new String(Files.readAllBytes(new DeviceCodeStore(_base).getFile()), StandardCharsets.UTF_8);

		assertFalse("The code itself is never stored: " + contents,
			contents.contains(DeviceCodeStore.normalise(created.getCode())));
		assertTrue(contents.contains(UserStore.hash(DeviceCodeStore.normalise(created.getCode()))));
		assertTrue("And it is marked as what it is: " + contents, contents.contains("\"kind\":\"backup\""));

		assertFalse("Nothing shows the code again; ?type=devices says only that there is one.",
			body(get("/", "devices", owner)).contains(DeviceCodeStore.normalise(created.getCode())));
	}

	public void testAnswerIsNeitherALinkNorAToken() throws Exception {
		String owner = signInOwner();

		String body = body(backupCode(owner));

		assertFalse("A backup code is no link: " + body, body.contains("url"));
		assertFalse("And it is no invitation: " + body, body.toLowerCase().contains("invit"));
		assertFalse(body, body.contains("token"));
	}

	// --- Redeeming it. ---

	public void testTheBackupCodePairsADeviceDashedOrPlain() throws Exception {
		String owner = signInOwner();
		String theCode = code(backupCode(owner)).getCode();

		FakeResponse response = pairWithCode(theCode, "Tablet", "");

		assertEquals(HttpServletResponse.SC_OK, response.status());
		PairResponse paired = paired(response);
		assertEquals("alice", paired.getUserName());
		assertEquals(Roles.ADMIN, paired.getRole());
		assertEquals("Tablet", paired.getDeviceName());
	}

	public void testTheBackupCodePairsHoweverItIsSpelled() throws Exception {
		String owner = signInOwner();
		String theCode = code(backupCode(owner)).getCode();

		String body = "{\"deviceCode\":\"" + DeviceCodeStore.normalise(theCode).toLowerCase()
			+ "\",\"deviceName\":\"Tablet\"}";
		FakeResponse response = post("/", body, null, pairParameters());

		assertEquals("The store looks up by hash; the length and the dashes say nothing.",
			HttpServletResponse.SC_OK, response.status());
		assertEquals("alice", paired(response).getUserName());
	}

	public void testTheBackupCodeWorksOnce() throws Exception {
		String owner = signInOwner();
		String theCode = code(backupCode(owner)).getCode();
		assertEquals(HttpServletResponse.SC_OK, pairWithCode(theCode, "Tablet", "").status());

		FakeResponse again = pairWithCode(theCode, "Another tablet", "");

		assertEquals(HttpServletResponse.SC_GONE, again.status());
		assertEquals(AuthService.DEVICE_CODE_USED, errorMessage(again));
	}

	public void testTheBackupCodeStillWorksAYearLater() throws Exception {
		String owner = signInOwner();
		String theCode = code(backupCode(owner)).getCode();

		// A store reading the same file a year from now; it is the record that decides, and the
		// record says "no expiry".
		Instant inAYear = Instant.now().plus(Duration.ofDays(365));
		DeviceCodeStore.Code record =
			new DeviceCodeStore(_base, Clock.fixed(inAYear, ZoneOffset.UTC)).lookup(theCode);

		assertNotNull(record);
		assertTrue(record.isBackup());
		assertFalse("A backup code has no expiry, so a year is no time at all.",
			record.isExpired(inAYear));
		assertFalse(record.isDead(inAYear));
		assertEquals("And it still pairs today.",
			HttpServletResponse.SC_OK, pairWithCode(theCode, "Tablet", "").status());
	}

	public void testTheBackupCodeSurvivesTheDeviceThatMadeIt() throws Exception {
		String laptop = signInOwner();
		String theCode = code(backupCode(laptop)).getCode();

		// Every device of alice goes, the one that made the code included: exactly the situation
		// the backup code exists for.
		for (DeviceEntry device : devices(laptop).getDevices()) {
			unpair(laptop, device.getId());
		}

		FakeResponse response = pairWithCode(theCode, "A new phone", "");

		assertEquals("The way back: the code outlives every device of its user.",
			HttpServletResponse.SC_OK, response.status());
		PairResponse paired = paired(response);
		assertEquals("alice", paired.getUserName());
		assertEquals(Arrays.asList("A new phone"), names(devices(paired.getToken())));
	}

	public void testAnOrdinaryCodeOfTheSameDeviceStillDiesWithIt() throws Exception {
		String laptop = signInOwner();
		String backup = code(backupCode(laptop)).getCode();
		String ordinary = code(deviceCode(laptop)).getCode();

		unpair(laptop, idOf(devices(laptop), "Alice's laptop"));

		assertEquals("The exemption is the backup code's alone.",
			HttpServletResponse.SC_GONE, pairWithCode(ordinary, "Tablet", "").status());
		assertEquals(HttpServletResponse.SC_OK, pairWithCode(backup, "Tablet", "").status());
	}

	// --- One per user. ---

	public void testASecondBackupCodeWithdrawsTheFirst() throws Exception {
		String owner = signInOwner();
		String first = code(backupCode(owner)).getCode();

		String second = code(backupCode(owner)).getCode();

		assertFalse(first.equals(second));
		FakeResponse refused = pairWithCode(first, "Tablet", "");
		assertEquals("Never two pieces of paper of which only one works.",
			HttpServletResponse.SC_GONE, refused.status());
		assertEquals("And the refusal says what happened, not that a device is gone.",
			AuthService.BACKUP_CODE_REVOKED, errorMessage(refused));
		assertEquals(HttpServletResponse.SC_OK, pairWithCode(second, "Tablet", "").status());
	}

	// --- Withdrawing one. ---

	public void testWithdrawingTheBackupCodeKillsIt() throws Exception {
		String owner = signInOwner();
		String theCode = code(backupCode(owner)).getCode();

		FakeResponse response = revokeBackupCode(owner);

		assertEquals(HttpServletResponse.SC_OK, response.status());
		DeviceList left = DeviceList.readDeviceList(reader(body(response)));
		assertEquals("The answer is what is left, and there is no backup code left.",
			"", left.getBackupCodeCreated());
		FakeResponse refused = pairWithCode(theCode, "Tablet", "");
		assertEquals(HttpServletResponse.SC_GONE, refused.status());
		assertEquals(AuthService.BACKUP_CODE_REVOKED, errorMessage(refused));
	}

	public void testWithdrawingWhatIsNotThereSaysSo() throws Exception {
		String owner = signInOwner();

		FakeResponse response = revokeBackupCode(owner);

		assertEquals(HttpServletResponse.SC_NOT_FOUND, response.status());
		assertEquals(AuthService.NO_BACKUP_CODE, errorMessage(response));
	}

	// --- What the device list says. ---

	public void testTheDeviceListSaysWhetherThereIsABackupCodeAndSinceWhen() throws Exception {
		String owner = signInOwner();
		assertEquals("Nobody made one yet.", "", devices(owner).getBackupCodeCreated());

		code(backupCode(owner));

		String created = devices(owner).getBackupCodeCreated();
		assertFalse("Since when, so that the devices section can say it.", created.isEmpty());
		assertTrue(created, Instant.parse(created).isAfter(Instant.now().minus(Duration.ofMinutes(1))));
	}

	public void testARedeemedBackupCodeIsGoneFromTheDeviceList() throws Exception {
		String owner = signInOwner();
		String theCode = code(backupCode(owner)).getCode();
		String tablet = paired(pairWithCode(theCode, "Tablet", "")).getToken();

		assertEquals("Used up is gone: the drawer is empty and the list says so.",
			"", devices(tablet).getBackupCodeCreated());
	}

	// --- Who may ask. ---

	public void testAnAnonymousCallerMakesNoBackupCode() throws Exception {
		FakeResponse response = backupCode(null);

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status());
		assertFalse(errorMessage(response).isEmpty());
		assertTrue("Nothing was recorded.", new DeviceCodeStore(_base).getCodes().isEmpty());
	}

	public void testAShareLinkMakesNoBackupCode() throws Exception {
		String share = shareToken();

		FakeResponse response = backupCode(share);

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(AuthService.BACKUP_CODE_REFUSED, errorMessage(response));
		assertTrue(new DeviceCodeStore(_base).getCodes().isEmpty());
	}

	public void testAShareLinkWithdrawsNoBackupCode() throws Exception {
		String share = shareToken();

		FakeResponse response = revokeBackupCode(share);

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(AuthService.BACKUP_CODE_REFUSED, errorMessage(response));
	}

	// --- The file format. ---

	public void testAStoreWrittenBeforeIssue92ReadsAsOrdinaryCodes() throws Exception {
		String laptop = signInOwner();
		String deviceId = idOf(devices(laptop), "Alice's laptop");
		// A record exactly as the build before this issue wrote it: no "kind" at all.
		Instant now = Instant.now();
		String legacy = "{\"version\":1,\"codes\":[{"
			+ "\"id\":\"legacy01\","
			+ "\"codeHash\":\"" + UserStore.hash("ABCD2345") + "\","
			+ "\"user\":\"alice\","
			+ "\"issuedBy\":\"" + deviceId + "\","
			+ "\"created\":\"" + now + "\","
			+ "\"expires\":\"" + now.plus(Duration.ofMinutes(10)) + "\","
			+ "\"used\":\"\",\"usedBy\":\"\",\"revoked\":\"\"}]}";
		Files.write(new DeviceCodeStore(_base).getFile(), legacy.getBytes(StandardCharsets.UTF_8));
		restartServer();

		DeviceCodeStore.Code read = new DeviceCodeStore(_base).lookup("ABCD-2345");

		assertNotNull("An old record is still a code.", read);
		assertEquals("And it is an ordinary one: no field means no exemption.",
			DeviceCodeStore.KIND_DEVICE, read.getKind());
		assertFalse(read.isBackup());
		assertEquals("It still pairs, exactly as it did.",
			HttpServletResponse.SC_OK, pairWithCode("ABCD-2345", "Tablet", "").status());
	}

	public void testALegacyRecordWithoutAnExpiryIsStillTreatedAsExpired() throws Exception {
		String laptop = signInOwner();
		String deviceId = idOf(devices(laptop), "Alice's laptop");
		// A damaged record, not a backup code: an empty expiry without the mark grants nothing.
		String damaged = "{\"version\":1,\"codes\":[{"
			+ "\"id\":\"damaged1\","
			+ "\"codeHash\":\"" + UserStore.hash("ABCD2345") + "\","
			+ "\"user\":\"alice\","
			+ "\"issuedBy\":\"" + deviceId + "\","
			+ "\"created\":\"" + Instant.now() + "\","
			+ "\"expires\":\"\",\"used\":\"\",\"usedBy\":\"\",\"revoked\":\"\"}]}";
		Files.write(new DeviceCodeStore(_base).getFile(), damaged.getBytes(StandardCharsets.UTF_8));
		restartServer();

		FakeResponse response = pairWithCode("ABCD-2345", "Tablet", "");

		assertEquals(HttpServletResponse.SC_GONE, response.status());
		assertEquals(AuthService.DEVICE_CODE_EXPIRED, errorMessage(response));
	}

	public void testPruningTheLongDeadKeepsALiveBackupCode() throws Exception {
		String owner = signInOwner();
		String theCode = code(backupCode(owner)).getCode();

		// A store whose "now" is long past KEEP_DEAD_HOURS writes the file again; a code that is
		// not dead is never dropped, however old it is.
		DeviceCodeStore later = new DeviceCodeStore(_base,
			Clock.fixed(Instant.now().plus(Duration.ofHours(DeviceCodeStore.KEEP_DEAD_HOURS + 48)), ZoneOffset.UTC));
		later.store();
		restartServer();

		assertNotNull("A backup code is kept as long as it works.", new DeviceCodeStore(_base).lookup(theCode));
		assertEquals(HttpServletResponse.SC_OK, pairWithCode(theCode, "Tablet", "").status());
	}

	// --- Helpers. ---

	/** Sends <code>&lt;data&gt;/?action=backup-code</code> with the given bearer. */
	private FakeResponse backupCode(String token) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "backup-code");
		return post("/", "", token, parameters);
	}

	/** Sends <code>&lt;data&gt;/?action=revoke-backup-code</code> with the given bearer. */
	private FakeResponse revokeBackupCode(String token) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "revoke-backup-code");
		return post("/", "", token, parameters);
	}

	/** Sends <code>&lt;data&gt;/?action=device-code</code> with the given bearer. */
	private FakeResponse deviceCode(String token) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "device-code");
		return post("/", "", token, parameters);
	}

	private FakeResponse pairWithCode(String deviceCode, String deviceName, String userName) throws Exception {
		return post("/", Codes.pairRequest(deviceCode, deviceName, userName), null, pairParameters());
	}

	private String signInOwner() throws Exception {
		FakeResponse response =
			pairWithCode(Codes.forUser(servlet().auth(), "alice"), "Alice's laptop", "alice");
		assertEquals(body(response), HttpServletResponse.SC_OK, response.status());
		return paired(response).getToken();
	}

	private static Map<String, String> pairParameters() {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "pair");
		return parameters;
	}

	private void unpair(String token, String id) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "unpair");
		FakeResponse response = post("/", "{\"id\":\"" + id + "\"}", token, parameters);
		assertEquals(body(response), HttpServletResponse.SC_OK, response.status());
	}

	private DeviceList devices(String token) throws Exception {
		FakeResponse response = get("/", "devices", token);
		assertEquals(body(response), HttpServletResponse.SC_OK, response.status());
		return DeviceList.readDeviceList(reader(body(response)));
	}

	/** A live share link on alice's zoo album, for the tests that need a link caller. */
	private String shareToken() throws Exception {
		de.haumacher.imageServer.auth.ShareStore shares = new de.haumacher.imageServer.auth.ShareStore(_base);
		String token = shares.create("alice", SharingFixture.ZOO, "Grandma", "", 0, 0,
			Arrays.asList(de.haumacher.imageServer.auth.Rights.VIEW)).getToken();
		restartServer();
		return token;
	}

	private static DeviceCodeCreated code(FakeResponse response) throws IOException {
		return DeviceCodeCreated.readDeviceCodeCreated(reader(body(response)));
	}

	private static List<String> names(DeviceList devices) {
		List<String> result = new ArrayList<>();
		for (DeviceEntry device : devices.getDevices()) {
			result.add(device.getName());
		}
		return result;
	}

	private static String idOf(DeviceList devices, String name) {
		for (DeviceEntry device : devices.getDevices()) {
			if (device.getName().equals(name)) {
				return device.getId();
			}
		}
		fail("No device '" + name + "' in " + devices.getDevices());
		return null;
	}
}
