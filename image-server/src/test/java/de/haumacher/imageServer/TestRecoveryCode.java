/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.shared.model.DeviceCodeCreated;
import de.haumacher.imageServer.shared.model.DeviceEntry;
import de.haumacher.imageServer.shared.model.DeviceList;
import de.haumacher.imageServer.shared.model.PairResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;

/**
 * Test case for the recovery code of issue #89.
 *
 * <p>
 * People lose their sign-ins by clearing a browser or reinstalling an app, and nobody should have
 * to be invited a second time for that. An administrator makes a code for an existing user of the
 * space — the same single-use code, with the same ten minutes, issued by the administrator's own
 * device and dying with it. Only an administrator may name somebody else.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestRecoveryCode extends SpaceTestCase {

	/** The administrator makes a code for carol, and carol's next device signs in with it. */
	public void testAnAdminMakesACodeForSomebodyElse() throws Exception {
		DeviceCodeCreated code = recoveryCode(SharingFixture.ALICE, "carol");

		assertFalse(code.getCode().isEmpty());
		assertFalse(code.getExpires().isEmpty());

		FakeResponse paired = post("/", Codes.pairRequest(code.getCode(), "Carol's new phone", ""), null,
			parameters("action", "pair"));
		assertEquals(body(paired), HttpServletResponse.SC_OK, paired.status());
		PairResponse response = PairResponse.readPairResponse(reader(body(paired)));
		assertEquals("The code says whom it signs in.", "carol", response.getUserName());
		assertEquals("edit", response.getRole());

		DeviceList devices = DeviceList.readDeviceList(reader(body(get("/", "devices", response.getToken()))));
		assertEquals("The device carol had, and the one that just arrived.", 2, devices.getDevices().size());
		boolean found = false;
		for (DeviceEntry device : devices.getDevices()) {
			found |= "Carol's new phone".equals(device.getName());
		}
		assertTrue("The new device is an ordinary device of carol.", found);
	}

	/** Naming oneself is the ordinary device code of issue #65 and needs no role. */
	public void testNamingOneselfIsTheOrdinaryDeviceCode() throws Exception {
		DeviceCodeCreated code = recoveryCode(SharingFixture.DAVE, "dave");

		FakeResponse paired = post("/", Codes.pairRequest(code.getCode(), "Dave's tablet", ""), null,
			parameters("action", "pair"));
		assertEquals(body(paired), HttpServletResponse.SC_OK, paired.status());
		assertEquals("dave", PairResponse.readPairResponse(reader(body(paired))).getUserName());
	}

	/** Nobody but an administrator makes a code for somebody else. */
	public void testAViewUserMayNotMakeACodeForAnybodyElse() throws Exception {
		FakeResponse response = deviceCode(SharingFixture.DAVE, "alice");

		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(AuthService.RECOVERY_REFUSED, errorMessage(response));
	}

	/** A user this space does not know is said to be unknown, not quietly ignored. */
	public void testACodeForAnUnknownUserIsRefused() throws Exception {
		FakeResponse response = deviceCode(SharingFixture.ALICE, "mallory");

		assertEquals(HttpServletResponse.SC_NOT_FOUND, response.status());
		assertEquals(AuthService.unknownUser("mallory"), errorMessage(response));
	}

	/**
	 * A recovery code dies with the device that made it, like every device-issued code.
	 *
	 * <p>
	 * What signing a device out has to mean: whatever it handed out and nobody spent goes with it,
	 * see issue #65. An administrator's device is no exception.
	 * </p>
	 */
	public void testARecoveryCodeDiesWithTheAdminDeviceThatMadeIt() throws Exception {
		DeviceCodeCreated code = recoveryCode(SharingFixture.ALICE, "carol");

		DeviceList mine = DeviceList.readDeviceList(reader(body(get("/", "devices", SharingFixture.ALICE))));
		String id = mine.getDevices().get(0).getId();
		FakeResponse out = post("/", "{\"id\":\"" + id + "\"}", SharingFixture.ALICE, parameters("action", "unpair"));
		assertEquals(body(out), HttpServletResponse.SC_OK, out.status());

		FakeResponse paired = post("/", Codes.pairRequest(code.getCode(), "Carol's new phone", ""), null,
			parameters("action", "pair"));
		assertEquals(HttpServletResponse.SC_GONE, paired.status());
		assertEquals(AuthService.DEVICE_CODE_ISSUER_GONE, errorMessage(paired));
	}

	/** An empty body is what it always was: a code for the caller themselves. */
	public void testAnEmptyBodyIsACodeForOneself() throws Exception {
		FakeResponse response = post("/", "", SharingFixture.BOB, parameters("action", "device-code"));

		assertEquals(body(response), HttpServletResponse.SC_OK, response.status());
		DeviceCodeCreated code = DeviceCodeCreated.readDeviceCodeCreated(reader(body(response)));
		FakeResponse paired = post("/", Codes.pairRequest(code.getCode(), "Bob's tablet", ""), null,
			parameters("action", "pair"));
		assertEquals("bob", PairResponse.readPairResponse(reader(body(paired))).getUserName());
	}

	/** A body that is not a request says so, rather than being read as "for myself". */
	public void testAnUnreadableBodyIsRefused() throws Exception {
		FakeResponse response =
			post("/", "this is not JSON", SharingFixture.ALICE, parameters("action", "device-code"));

		assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status());
		assertEquals(ImageServlet.DEVICE_CODE_UNREADABLE, errorMessage(response));
	}

	// --- Helpers. ---

	private DeviceCodeCreated recoveryCode(String token, String userName) throws Exception {
		FakeResponse response = deviceCode(token, userName);
		assertEquals(body(response), HttpServletResponse.SC_OK, response.status());
		return DeviceCodeCreated.readDeviceCodeCreated(reader(body(response)));
	}

	private FakeResponse deviceCode(String token, String userName) throws Exception {
		return post("/", "{\"userName\":\"" + userName + "\"}", token, parameters("action", "device-code"));
	}

	private static Map<String, String> parameters(String name, String value) {
		Map<String, String> result = new HashMap<>();
		result.put(name, value);
		return result;
	}
}
