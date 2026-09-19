/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.DeviceCodeStore;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.shared.model.AuthInfo;
import de.haumacher.imageServer.shared.model.DeviceCodeCreated;
import de.haumacher.imageServer.shared.model.DeviceEntry;
import de.haumacher.imageServer.shared.model.DeviceList;
import de.haumacher.imageServer.shared.model.PairResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test case for the device codes of issue #65: adding a further device of one's own, driven
 * headlessly through {@link ImageServlet}.
 *
 * <p>
 * The point of the whole mechanism is that it is <em>not</em> an invitation: what a code creates is
 * another device of the asking user, it is never a link and never a bearer, it lives ten minutes
 * and it works once. Every one of those is a test here.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestDeviceCode extends InviteTestCase {

	private static final String ALBUM_JSON = "[\"AlbumInfo\",{\"title\":\"Trip\"}]";

	// --- Asking for a code. ---

	public void testTheOwnerSignsInAndThenAsksForADeviceCode() throws Exception {
		String owner = signInOwner();

		FakeResponse response = deviceCode(owner);

		assertEquals(HttpServletResponse.SC_OK, response.status());
		DeviceCodeCreated created = code(response);
		assertTrue("A code is shown as XXXX-XXXX: " + created.getCode(),
			created.getCode().matches("[" + DeviceCodeStore.ALPHABET + "]{4}-[" + DeviceCodeStore.ALPHABET + "]{4}"));

		Instant expires = Instant.parse(created.getExpires());
		Instant now = Instant.now();
		assertTrue("A code lives ten minutes: " + created.getExpires(),
			expires.isAfter(now.plus(Duration.ofMinutes(9))) && expires.isBefore(now.plus(Duration.ofMinutes(11))));

		String contents = new String(Files.readAllBytes(new DeviceCodeStore(_base).getFile()), "UTF-8");
		assertFalse("The code itself is never stored: " + contents,
			contents.contains(DeviceCodeStore.normalise(created.getCode())));
		assertTrue(contents.contains(UserStore.hash(DeviceCodeStore.normalise(created.getCode()))));
	}

	public void testTheAnswerIsNeitherALinkNorAToken() throws Exception {
		String owner = signInOwner();

		String body = body(deviceCode(owner));

		assertFalse("A device code is no link: " + body, body.contains("url"));
		assertFalse("And it is no invitation: " + body, body.toLowerCase().contains("invit"));
		assertFalse(body, body.contains("token"));
	}

	public void testACodeIsNoBearerAnywhere() throws Exception {
		String owner = signInOwner();
		String theCode = code(deviceCode(owner)).getCode();

		FakeResponse response = authOf(theCode);

		assertEquals("A device code reaches no endpoint at all.", HttpServletResponse.SC_OK, response.status());
		AuthInfo info = auth(response);
		assertEquals("", info.getUserName());
		assertEquals("", info.getRole());
		assertEquals("", info.getDeviceName());
		assertNull("A device code is nothing ?type=auth knows about.", info.getInvitation());
		assertNull(info.getShare());
	}

	// --- Pairing with it. ---

	public void testACodePairsAFurtherDeviceOfTheSameUser() throws Exception {
		String owner = signInOwner();
		String theCode = code(deviceCode(owner)).getCode();

		FakeResponse response = pairWithCode(theCode, "Tablet", "");

		assertEquals(HttpServletResponse.SC_OK, response.status());
		PairResponse paired = paired(response);
		assertEquals("alice", paired.getUserName());
		assertEquals(Roles.ADMIN, paired.getRole());
		assertEquals("One space, and it is the served tree; a user owns no folder of their own.",
			"", paired.getSpace());
		assertEquals("Tablet", paired.getDeviceName());
		assertFalse("The new device gets a token of its own.", paired.getToken().isEmpty());
		assertFalse("And it is not the code.", paired.getToken().contains(DeviceCodeStore.normalise(theCode)));
	}

	public void testTheCodePairsHoweverItIsSpelled() throws Exception {
		String owner = signInOwner();
		String theCode = code(deviceCode(owner)).getCode();

		// The dash left out and shouted in lower case.
		String body = "{\"deviceCode\":\"" + DeviceCodeStore.normalise(theCode).toLowerCase()
			+ "\",\"deviceName\":\"Tablet\"}";
		FakeResponse response = post("/", body, null, pairParameters());

		assertEquals(HttpServletResponse.SC_OK, response.status());
		assertEquals("alice", paired(response).getUserName());
	}

	public void testBothDevicesSeeEachOtherAndExactlyTheAskingOneIsCurrent() throws Exception {
		String owner = signInOwner();
		String tablet = paired(pairWithCode(code(deviceCode(owner)).getCode(), "Tablet", "")).getToken();

		DeviceList fromOwner = devices(owner);
		DeviceList fromTablet = devices(tablet);

		assertTrue("The new device is an ordinary device of the user: " + names(fromOwner),
			names(fromOwner).contains("Tablet"));
		assertEquals(names(fromOwner), names(fromTablet));
		assertEquals(Collections.singletonList("Alice's laptop"), current(fromOwner));
		assertEquals(Collections.singletonList("Tablet"), current(fromTablet));
	}

	public void testTheNewDeviceWritesWhatItsUserMayWriteUntilItIsUnpaired() throws Exception {
		String owner = signInOwner();
		String tablet = paired(pairWithCode(code(deviceCode(owner)).getCode(), "Tablet", "")).getToken();

		assertEquals("The new token is worth what the user's other tokens are worth.",
			HttpServletResponse.SC_OK, put("/2025-01-01 Trip/", ALBUM_JSON, tablet).status());

		String id = idOf(devices(owner), "Tablet");
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "unpair");
		assertEquals(HttpServletResponse.SC_OK,
			post("/", "{\"id\":\"" + id + "\"}", owner, parameters).status());

		FakeResponse refused = put("/2025-02-01 Trip/", ALBUM_JSON, tablet);
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, refused.status());
		assertEquals(AuthService.TOKEN_REFUSED, errorMessage(refused));
	}

	// --- Once, and only for its own user. ---

	public void testACodeWorksExactlyOnce() throws Exception {
		String owner = signInOwner();
		String theCode = code(deviceCode(owner)).getCode();
		assertEquals(HttpServletResponse.SC_OK, pairWithCode(theCode, "Tablet", "").status());

		FakeResponse again = pairWithCode(theCode, "Somebody's phone", "");

		assertEquals(HttpServletResponse.SC_GONE, again.status());
		assertEquals(AuthService.DEVICE_CODE_USED, errorMessage(again));
		assertEquals("The second attempt pairs nothing.",
			Arrays.asList("Alice's phone", "Alice's laptop", "Tablet"), names(devices(owner)));
	}

	public void testACodeDiesWithTheDeviceThatIssuedIt() throws Exception {
		String laptop = signInOwner();
		String theCode = code(deviceCode(laptop)).getCode();

		// Alice spots a device she never paired and throws it out from her phone.
		unpair(SharingFixture.ALICE, idOf(devices(SharingFixture.ALICE), "Alice's laptop"));

		FakeResponse response = pairWithCode(theCode, "Stranger's tablet", "");

		assertEquals("A code outliving its device would undo the whole device list.",
			HttpServletResponse.SC_GONE, response.status());
		assertEquals(AuthService.DEVICE_CODE_ISSUER_GONE, errorMessage(response));
		assertEquals("Nothing was added.",
			Collections.singletonList("Alice's phone"), names(devices(SharingFixture.ALICE)));
	}

	public void testTheIssuerGoneRefusalIsItsOwnStory() throws Exception {
		String laptop = signInOwner();
		String theCode = code(deviceCode(laptop)).getCode();
		unpair(SharingFixture.ALICE, idOf(devices(SharingFixture.ALICE), "Alice's laptop"));

		String said = errorMessage(pairWithCode(theCode, "Tablet", ""));

		assertFalse("Not 'used': nobody typed it.", said.equals(AuthService.DEVICE_CODE_USED));
		assertFalse("Not 'expired': its ten minutes are not up.", said.equals(AuthService.DEVICE_CODE_EXPIRED));
		assertFalse("Not 'unknown': the server knows exactly what this code was.",
			said.equals(AuthService.DEVICE_CODE_UNKNOWN));
		assertEquals(AuthService.DEVICE_CODE_ISSUER_GONE, said);
	}

	public void testSigningOutHereWithdrawsWhatThisDeviceHandedOut() throws Exception {
		String laptop = signInOwner();
		String theCode = code(deviceCode(laptop)).getCode();

		// "Sign out here": the device names itself, which is allowed and is the point.
		unpair(laptop, idOf(devices(laptop), "Alice's laptop"));

		assertTrue("The record is marked rather than removed, so that the refusal can speak.",
			new DeviceCodeStore(_base).lookup(theCode).isRevoked());
		assertEquals(HttpServletResponse.SC_GONE, pairWithCode(theCode, "Tablet", "").status());
	}

	public void testADeviceAddedByACodeIsNoChildOfTheDeviceThatAddedIt() throws Exception {
		String laptop = signInOwner();
		String tablet = paired(pairWithCode(code(deviceCode(laptop)).getCode(), "Tablet", "")).getToken();
		String pending = code(deviceCode(laptop)).getCode();

		unpair(SharingFixture.ALICE, idOf(devices(SharingFixture.ALICE), "Alice's laptop"));

		assertEquals("The tablet is an ordinary device; it does not hang off its maker.",
			HttpServletResponse.SC_OK, put("/2025-06-01 Trip/", ALBUM_JSON, tablet).status());
		assertEquals("But the pending code went with the device that made it.",
			HttpServletResponse.SC_GONE, pairWithCode(pending, "Whoever", "").status());
		assertEquals(Arrays.asList("Alice's phone", "Tablet"), names(devices(tablet)));
	}

	public void testACodeOfAnIssuerThatWasNeverADeviceSignsNobodyIn() throws Exception {
		// Fail-closed by construction: the user's device list decides, not a flag in the store.
		new DeviceCodeStore(_base).create("alice", "");
		String theCode = new DeviceCodeStore(_base).create("alice", "never-a-device").getCode();
		restartServer();

		FakeResponse response = pairWithCode(theCode, "Tablet", "");

		assertEquals(HttpServletResponse.SC_GONE, response.status());
		assertEquals(AuthService.DEVICE_CODE_ISSUER_GONE, errorMessage(response));
	}

	public void testAWrongCodeIsRefused() throws Exception {
		signInOwner();

		FakeResponse response = pairWithCode("ZZZZ-ZZZZ", "Tablet", "");

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status());
		assertEquals(AuthService.DEVICE_CODE_UNKNOWN, errorMessage(response));
	}

	public void testACodeUnderSomebodyElsesNameIsRefused() throws Exception {
		String owner = signInOwner();
		String theCode = code(deviceCode(owner)).getCode();

		FakeResponse response = pairWithCode(theCode, "Tablet", "bob");

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status());
		assertEquals(AuthService.DEVICE_CODE_OTHER_USER, errorMessage(response));
		assertFalse("A refused name says nothing about whose code it is.",
			errorMessage(response).contains("alice"));
		assertEquals("Nothing was paired.",
			Arrays.asList("Alice's phone", "Alice's laptop"), names(devices(owner)));

		assertEquals("The code is untouched and still works for its own user.",
			HttpServletResponse.SC_OK, pairWithCode(theCode, "Tablet", "alice").status());
	}

	public void testAnExpiredCodeIsRefused() throws Exception {
		signInOwner();
		// Issued eleven minutes ago, which no endpoint would do; the store's clock makes it so.
		DeviceCodeStore past =
			new DeviceCodeStore(_base, Clock.fixed(Instant.now().minus(Duration.ofMinutes(11)), java.time.ZoneOffset.UTC));
		String theCode = past.create("alice", "dev-old").getCode();
		restartServer();

		FakeResponse response = pairWithCode(theCode, "Tablet", "");

		assertEquals(HttpServletResponse.SC_GONE, response.status());
		assertEquals(AuthService.DEVICE_CODE_EXPIRED, errorMessage(response));
	}

	public void testACodeOfAUserWhoIsGoneIsRefused() throws Exception {
		DeviceCodeStore codes = new DeviceCodeStore(_base);
		String theCode = codes.create("mallory", "dev-old").getCode();
		restartServer();

		FakeResponse response = pairWithCode(theCode, "Tablet", "");

		assertEquals(HttpServletResponse.SC_GONE, response.status());
		assertEquals(AuthService.DEVICE_CODE_USER_GONE, errorMessage(response));
	}

	// --- Who may ask. ---

	public void testAnAnonymousCallerAsksForNoDeviceCode() throws Exception {
		FakeResponse response = deviceCode(null);

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status());
		assertFalse(errorMessage(response).isEmpty());
		assertTrue("Nothing was recorded.", new DeviceCodeStore(_base).getCodes().isEmpty());
	}

	public void testAnInvitationBearerAsksForNoDeviceCode() throws Exception {
		String invitation = issue(Roles.EDIT, "alice", "");

		FakeResponse response = deviceCode(invitation);

		assertEquals("An invitation is no sign-in; there is no 'oneself' to add a device to.",
			HttpServletResponse.SC_UNAUTHORIZED, response.status());
		assertTrue(new DeviceCodeStore(_base).getCodes().isEmpty());
	}

	/** A live share link on alice's zoo album, for the tests that need a link caller. */
	private String shareToken() throws Exception {
		de.haumacher.imageServer.auth.ShareStore shares = new de.haumacher.imageServer.auth.ShareStore(_base);
		String token = shares.create("alice", SharingFixture.ZOO, "Grandma", "", 0, 0,
			java.util.Arrays.asList(de.haumacher.imageServer.auth.Rights.VIEW)).getToken();
		restartServer();
		return token;
	}

	public void testAShareLinkAsksForNoDeviceCode() throws Exception {
		String share = shareToken();

		FakeResponse response = deviceCode(share);

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(AuthService.DEVICE_CODE_REFUSED, errorMessage(response));
		assertTrue(new DeviceCodeStore(_base).getCodes().isEmpty());
	}



	// --- Helpers. ---

	/** Sends <code>&lt;data&gt;/?action=device-code</code> with the given bearer. */
	private FakeResponse deviceCode(String token) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "device-code");
		return post("/", "", token, parameters);
	}

	/** Sends <code>&lt;data&gt;/?action=pair</code> with a device code. */
	private FakeResponse pairWithCode(String deviceCode, String deviceName, String userName) throws Exception {
		return post("/", Codes.pairRequest(deviceCode, deviceName, userName), null, pairParameters());
	}

	/** Signs a laptop of the library owner in with a code of their phone, answering its token. */
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

	/** Sends <code>&lt;data&gt;/?action=unpair</code> naming the device of the given id. */
	private void unpair(String token, String id) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "unpair");
		FakeResponse response = post("/", "{\"id\":\"" + id + "\"}", token, parameters);
		assertEquals(body(response), HttpServletResponse.SC_OK, response.status());
	}

	/** Sends <code>&lt;data&gt;/?type=devices</code> with the given bearer. */
	private DeviceList devices(String token) throws Exception {
		FakeResponse response = get("/", "devices", token);
		assertEquals(body(response), HttpServletResponse.SC_OK, response.status());
		return DeviceList.readDeviceList(reader(body(response)));
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

	private static List<String> current(DeviceList devices) {
		List<String> result = new ArrayList<>();
		for (DeviceEntry device : devices.getDevices()) {
			if (device.isCurrent()) {
				result.add(device.getName());
			}
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
