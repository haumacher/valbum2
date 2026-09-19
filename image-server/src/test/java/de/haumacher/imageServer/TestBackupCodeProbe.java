/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.shared.model.DeviceCodeCreated;
import jakarta.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;

/**
 * Probe for issue #92: a backup code composed with the life of its user — an invited member makes
 * one, the administrator removes the member, and the code that "never expires" must die with the
 * user while the administrator's own stays untouched.
 */
@SuppressWarnings("javadoc")
public class TestBackupCodeProbe extends InviteTestCase {

	public void testABackupCodeDiesWithItsUserAndNobodyElses() throws Exception {
		String alice = signIn("alice", Codes.forUser(servlet().auth(), "alice"));
		String carla = signIn("carla", invitationFor(alice));

		DeviceCodeCreated carlasCode = code(backupCode(carla));
		DeviceCodeCreated alicesCode = code(backupCode(alice));
		assertEquals(16, carlasCode.getCode().replace("-", "").length());

		Map<String, String> remove = new HashMap<>();
		remove.put("action", "remove-user");
		FakeResponse removed = post("/", "{\"name\":\"carla\"}", alice, remove);
		assertEquals(body(removed), HttpServletResponse.SC_OK, removed.status());
		assertNull("The removal must take the user out of the store the servlet uses (users left: " + servlet().auth().getUsers().getUsers().size() + ", body: " + body(removed) + ")", servlet().auth().getUsers().getUser("carla"));
		FakeResponse listed = get("/", "users", alice);
		assertFalse("The users list must not name the removed user: " + body(listed), body(listed).contains("carla"));

		FakeResponse revived = pair(carlasCode.getCode(), "Carla's new phone", "");
		assertTrue("A removed user's backup code must not sign anybody in: " + revived.body(),
			revived.status() >= 400 && revived.status() < 500);
		assertTrue("The refusal names the reason: " + revived.body(), revived.body().contains("no longer exists"));
		assertNull("No user came back through the code.", servlet().auth().getUsers().getUser("carla"));

		FakeResponse alicesSecond = pair(alicesCode.getCode(), "Alice's tablet", "");
		assertEquals(body(alicesSecond), HttpServletResponse.SC_OK, alicesSecond.status());
		assertEquals("alice", paired(alicesSecond).getUserName());
	}

	/** A recovery code for a user and that user's own backup code live side by side, each once. */
	public void testARecoveryCodeAndABackupCodeAreIndependent() throws Exception {
		String alice = signIn("alice", Codes.forUser(servlet().auth(), "alice"));
		String carla = signIn("carla", invitationFor(alice));

		DeviceCodeCreated backup = code(backupCode(carla));
		Map<String, String> recover = new HashMap<>();
		recover.put("action", "device-code");
		DeviceCodeCreated recovery = code(post("/", "{\"userName\":\"carla\"}", alice, recover));

		assertEquals(HttpServletResponse.SC_OK, pair(recovery.getCode(), "Carla's laptop", "").status());
		assertEquals("The recovery code did not consume the backup code.", HttpServletResponse.SC_OK,
			pair(backup.getCode(), "Carla's tablet", "").status());
		assertEquals(3, servlet().auth().getUsers().getUser("carla").getDevices().size());
	}

	private String invitationFor(String adminToken) throws Exception {
		FakeResponse response = invite(adminToken, "view");
		assertEquals(body(response), HttpServletResponse.SC_OK, response.status());
		String url = created(response).getUrl();
		String trimmed = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
		return trimmed.substring(trimmed.lastIndexOf('/') + 1);
	}

	/** Signs the given user in — through an invitation token or through a code — and answers the device token. */
	private String signIn(String userName, String secret) throws Exception {
		FakeResponse response = secret.length() > 20
			? accept(secret, userName)
			: pair(secret, userName + "'s phone", userName);
		assertEquals(body(response), HttpServletResponse.SC_OK, response.status());
		return paired(response).getToken();
	}

	private FakeResponse pair(String code, String deviceName, String userName) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "pair");
		return post("/", Codes.pairRequest(code, deviceName, userName), null, parameters);
	}

	private FakeResponse backupCode(String token) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "backup-code");
		FakeResponse response = post("/", "", token, parameters);
		assertEquals(body(response), HttpServletResponse.SC_OK, response.status());
		return response;
	}

	private static DeviceCodeCreated code(FakeResponse response) throws Exception {
		return DeviceCodeCreated.readDeviceCodeCreated(reader(body(response)));
	}

}
