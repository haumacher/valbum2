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
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Probe for the device codes of issue #65, composing them with what was there before: unpairing
 * (#55), promotion (#52), a server restart, and codes issued by a device that was itself added by
 * a code.
 *
 * <p>
 * The issue's own safety argument is that a stranger who used a code is <em>visible</em> in the
 * device list and can be unpaired. That argument only holds if unpairing the stranger's device also
 * kills whatever that device issued meanwhile — otherwise the stranger asks for a code before being
 * thrown out and walks straight back in.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestDeviceCodeProbe extends InviteTestCase {

	private static final String ALBUM_JSON = "[\"AlbumInfo\",{\"title\":\"Trip\"}]";


	public void testAUsedCodeStaysUsedAcrossARestart() throws Exception {
		String theCode = code(deviceCode(SharingFixture.BOB)).getCode();
		assertEquals(HttpServletResponse.SC_OK, pairWithCode(theCode, "Bob's tablet").status());

		restartServer();

		FakeResponse response = pairWithCode(theCode, "Bob's other tablet");
		assertEquals(HttpServletResponse.SC_GONE, response.status());
		assertEquals("Used, not unknown: the store is persisted.", AuthService.DEVICE_CODE_USED, errorMessage(response));
	}

	public void testAnUnusedCodeSurvivesARestartAndTheUseOfAnotherOne() throws Exception {
		String first = code(deviceCode(SharingFixture.BOB)).getCode();
		String second = code(deviceCode(SharingFixture.BOB)).getCode();

		restartServer();
		assertEquals(HttpServletResponse.SC_OK, pairWithCode(second, "Bob's tablet").status());

		PairResponse paired = paired(pairWithCode(first, "Bob's laptop"));
		assertEquals("bob", paired.getUserName());
		assertEquals(3, devices(SharingFixture.BOB).getDevices().size());
	}

	public void testADeviceAddedByCodeAddsTheNextOneAndTheChainIsNoHierarchy() throws Exception {
		String laptop = paired(pairWithCode(code(deviceCode(SharingFixture.ALICE)).getCode(), "Alice's laptop")).getToken();
		String tablet = paired(pairWithCode(code(deviceCode(laptop)).getCode(), "Alice's tablet")).getToken();
		String pending = code(deviceCode(laptop)).getCode();

		unpair(SharingFixture.ALICE, idOf(devices(SharingFixture.ALICE), "Alice's laptop"));

		assertEquals("The tablet is an ordinary device; it does not hang off the laptop.",
			HttpServletResponse.SC_OK, put("/2025-05-01 Trip/", ALBUM_JSON, tablet).status());
		assertEquals("But the laptop's pending code went with the laptop.",
			HttpServletResponse.SC_GONE, pairWithCode(pending, "Whoever").status());
		DeviceList devices = devices(tablet);
		assertEquals(2, devices.getDevices().size());
		for (DeviceEntry device : devices.getDevices()) {
			assertEquals(device.getName().equals("Alice's tablet"), device.isCurrent());
		}
	}

	// --- Helpers. ---

	private FakeResponse deviceCode(String token) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "device-code");
		return post("/", "", token, parameters);
	}

	private FakeResponse pairWithCode(String deviceCode, String deviceName) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "pair");
		return post("/", "{\"deviceCode\":\"" + deviceCode + "\",\"deviceName\":\"" + deviceName + "\"}", null, parameters);
	}

	private String pairWithSecret(String deviceName) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "pair");
		FakeResponse response = post("/", "{\"secret\":\"" + SharingFixture.SECRET + "\",\"deviceName\":\"" + deviceName
			+ "\",\"userName\":\"alice\"}", null, parameters);
		assertEquals(body(response), HttpServletResponse.SC_OK, response.status());
		return paired(response).getToken();
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

	private static DeviceCodeCreated code(FakeResponse response) throws IOException {
		assertEquals(body(response), HttpServletResponse.SC_OK, response.status());
		return DeviceCodeCreated.readDeviceCodeCreated(reader(body(response)));
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
