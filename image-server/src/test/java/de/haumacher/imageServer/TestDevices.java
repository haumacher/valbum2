/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.InvitationStore;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.shared.model.DeviceEntry;
import de.haumacher.imageServer.shared.model.DeviceList;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Test case for the device list and the signing out of a device, issue #55, driven headlessly
 * through {@link ImageServlet}.
 *
 * <p>
 * The library is the {@link SharingFixture} of issue #49 with a second device for bob: two devices
 * is the case the whole gap is about, one of them being the one that asks.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestDevices extends ShareTestCase {

	/** The token of bob's second device. */
	private static final String BOB_TABLET = "bob-tablet-token";

	/** The name of bob's second device. */
	private static final String TABLET = "Bob's tablet";

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		UserStore users = new UserStore(_base);
		users.getUser("bob")
			.addDevice(new UserStore.Device(TABLET, UserStore.hash(BOB_TABLET), "2026-09-07T08:09:12Z"));
		users.store();
		restartServer();
	}

	// --- Who sees what. ---

	public void testBobSeesHisOwnDevices() throws Exception {
		DeviceList devices = devices(SharingFixture.BOB);

		assertEquals(Arrays.asList("bob's device", TABLET), names(devices));

		Set<String> ids = new HashSet<>();
		for (DeviceEntry device : devices.getDevices()) {
			assertFalse("Every device is named: " + device.getName(), device.getId().isEmpty());
			ids.add(device.getId());
		}
		assertEquals("The two devices are told apart by their ids.", 2, ids.size());
	}

	public void testExactlyTheAskingDeviceIsTheCurrentOne() throws Exception {
		assertEquals(Arrays.asList("bob's device"), current(devices(SharingFixture.BOB)));
		assertEquals(Arrays.asList(TABLET), current(devices(BOB_TABLET)));
	}

	public void testTheDeviceListNeverCarriesAToken() throws Exception {
		String body = body(get("/", "devices", SharingFixture.BOB));

		assertFalse(body, body.contains("tokenHash"));
		assertFalse(body, body.contains(UserStore.hash(SharingFixture.BOB)));
		assertFalse(body, body.contains(SharingFixture.BOB));
	}

	public void testAliceSeesHerOwnDevicesAndNobodyElses() throws Exception {
		DeviceList devices = devices(SharingFixture.ALICE);

		assertEquals("The admin manages users, not other people's phones.",
			Arrays.asList("Alice's phone"), names(devices));
	}

	public void testAnAnonymousCallerIsAskedToSignIn() throws Exception {
		FakeResponse response = get("/", "devices", null);

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status());
		assertEquals(AuthService.LIBRARY_REFUSED, errorMessage(response));
	}

	public void testAShareLinkIsToldThatItIsNoDevice() throws Exception {
		FakeResponse response = get("/", "devices", zooToken());

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(AuthService.DEVICES_REFUSED, errorMessage(response));
	}

	public void testAnInvitationIsAskedToSignIn() throws Exception {
		// An invitation is anonymous everywhere but "?type=auth": it is no sign-in, so the answer
		// is the one an unpaired caller gets, and pairing is exactly what its holder should do.
		FakeResponse response = get("/", "devices", invitation());

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status());
		assertEquals(AuthService.LIBRARY_REFUSED, errorMessage(response));
	}

	// --- Signing a device out. ---

	public void testBobSignsHisOtherDeviceOut() throws Exception {
		String tablet = idOf(SharingFixture.BOB, TABLET);

		DeviceList left = DeviceList.readDeviceList(reader(body(unpair(SharingFixture.BOB, tablet))));

		assertEquals(Arrays.asList("bob's device"), names(left));
		assertEquals("The store on disk forgot it.", 1, stored("bob").size());
		assertEquals("Its token opens nothing any more.", HttpServletResponse.SC_UNAUTHORIZED,
			get("/", "json", BOB_TABLET).status());
		assertEquals("The device that asked still works.", HttpServletResponse.SC_OK,
			get("/", "json", SharingFixture.BOB).status());
	}

	public void testBobSignsHimselfOutHere() throws Exception {
		String own = idOf(SharingFixture.BOB, "bob's device");

		FakeResponse response = unpair(SharingFixture.BOB, own);

		assertEquals(HttpServletResponse.SC_OK, response.status());
		assertEquals("What is left is the other device, and it is not the current one.",
			Arrays.asList(TABLET), names(DeviceList.readDeviceList(reader(body(response)))));
		assertTrue(current(DeviceList.readDeviceList(reader(body(response)))).isEmpty());
		assertEquals("The next request with that token is refused.", HttpServletResponse.SC_UNAUTHORIZED,
			get("/", "json", SharingFixture.BOB).status());
		assertEquals("The other device still works.", HttpServletResponse.SC_OK,
			get("/", "json", BOB_TABLET).status());
	}

	public void testBobMayNotSignAlicesDeviceOut() throws Exception {
		String alices = idOf(SharingFixture.ALICE, "Alice's phone");

		FakeResponse response = unpair(SharingFixture.BOB, alices);

		assertEquals(HttpServletResponse.SC_NOT_FOUND, response.status());
		assertEquals("The answer does not say whose device that is.", AuthService.DEVICE_UNKNOWN,
			errorMessage(response));
		assertEquals("Alice's device is untouched.", 1, stored("alice").size());
		assertEquals(HttpServletResponse.SC_OK, get("/", "json", SharingFixture.ALICE).status());
		assertEquals("And bob still has both of his.", 2, stored("bob").size());
	}

	public void testAnIdNobodyHasIsRefusedTheSameWay() throws Exception {
		FakeResponse response = unpair(SharingFixture.BOB, "nosuchid");

		assertEquals(HttpServletResponse.SC_NOT_FOUND, response.status());
		assertEquals(AuthService.DEVICE_UNKNOWN, errorMessage(response));
		assertEquals(2, stored("bob").size());
	}

	public void testAnEmptyIdIsRefusedTheSameWay() throws Exception {
		FakeResponse response = unpair(SharingFixture.BOB, "");

		assertEquals(HttpServletResponse.SC_NOT_FOUND, response.status());
		assertEquals(AuthService.DEVICE_UNKNOWN, errorMessage(response));
	}

	public void testAShareLinkSignsNothingOut() throws Exception {
		FakeResponse response = unpair(zooToken(), idOf(SharingFixture.BOB, TABLET));

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(AuthService.DEVICES_REFUSED, errorMessage(response));
		assertEquals(2, stored("bob").size());
	}

	public void testAnAnonymousCallerSignsNothingOut() throws Exception {
		FakeResponse response = unpair(null, idOf(SharingFixture.BOB, TABLET));

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status());
		assertEquals(2, stored("bob").size());
	}

	public void testAnUnreadableRequestIsRefusedWithTheReason() throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "unpair");
		FakeResponse response = post("/", "not json at all", SharingFixture.BOB, parameters);

		assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status());
		assertEquals(ImageServlet.DEVICE_UNREADABLE, errorMessage(response));
	}

	// --- Helpers. ---

	private DeviceList devices(String token) throws Exception {
		return DeviceList.readDeviceList(reader(body(get("/", "devices", token))));
	}

	private FakeResponse unpair(String token, String id) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "unpair");
		return post("/", "{\"id\":\"" + id + "\"}", token, parameters);
	}

	/** The id of the given user's device of the given name, read from the store on disk. */
	private String idOf(String token, String deviceName) {
		String user = token.equals(SharingFixture.ALICE) ? "alice" : "bob";
		for (UserStore.Device device : stored(user)) {
			if (device.getName().equals(deviceName)) {
				return device.getId();
			}
		}
		fail("No device '" + deviceName + "' of '" + user + "'.");
		return null;
	}

	/** The devices of the given user as the store on disk holds them. */
	private List<UserStore.Device> stored(String user) {
		return new UserStore(_base).getUser(user).getDevices();
	}

	/** An invitation token, which is a token this server issued but no sign-in. */
	private String invitation() throws IOException {
		InvitationStore.Issued issued =
			new InvitationStore(_base).create(Roles.MEMBER, "alice", "", Instant.now().plusSeconds(3600).toString());
		return issued.getToken();
	}

	private static List<String> names(DeviceList devices) {
		List<String> result = new ArrayList<>();
		for (DeviceEntry device : devices.getDevices()) {
			result.add(device.getName());
		}
		return result;
	}

	/** The names of the devices a listing marks as the current one. */
	private static List<String> current(DeviceList devices) {
		List<String> result = new ArrayList<>();
		for (DeviceEntry device : devices.getDevices()) {
			if (device.isCurrent()) {
				result.add(device.getName());
			}
		}
		return result;
	}
}
