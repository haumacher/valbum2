/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.GrantStore;
import de.haumacher.imageServer.auth.GroupStore;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.Subjects;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.shared.model.DeviceEntry;
import de.haumacher.imageServer.shared.model.DeviceList;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Review probe of the server half of issue #55: device identity and group renames composed with
 * what hangs off them — a share link and an invitation issued from a device that is unpaired
 * afterwards, a group whose grant a member's link tile (#50) depends on, and a guest's devices
 * across a promotion (#52).
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestManagementProbe extends InviteTestCase {

	private static final String ZOO = "/" + SharingFixture.ZOO + "/";

	private FakeResponse devices(String token) throws Exception {
		return get("/", "devices", token);
	}

	private FakeResponse unpair(String token, String id) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "unpair");
		return post("/", "{\"id\":\"" + id + "\"}", token, parameters);
	}

	private FakeResponse regroup(String token, String name, String newName) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "regroup");
		return post("/", "{\"name\":\"" + name + "\",\"newName\":\"" + newName + "\"}", token, parameters);
	}

	private static DeviceList deviceList(FakeResponse response) throws Exception {
		assertEquals(body(response), HttpServletResponse.SC_OK, response.status());
		return DeviceList.readDeviceList(reader(body(response)));
	}

	private static DeviceEntry current(DeviceList list) {
		for (DeviceEntry device : list.getDevices()) {
			if (device.isCurrent()) {
				return device;
			}
		}
		fail("No current device in " + list.getDevices());
		return null;
	}

	/** A second device of the given user, paired directly in the store; answers its token. */
	private String secondDevice(String user) throws Exception {
		UserStore users = new UserStore(_base);
		String token = users.addDevice(users.getUser(user), "Tablet");
		restartServer();
		return token;
	}

	// --- What an unpaired device leaves behind. ---

	public void testAShareLinkOutlivesTheDeviceThatMadeIt() throws Exception {
		String tablet = secondDevice("alice");
		Map<String, String> share = new HashMap<>();
		share.put("action", "share");
		FakeResponse created = post(ZOO, "{\"label\":\"Party\",\"rights\":[{\"name\":\"view\"}]}", tablet, share);
		assertEquals(body(created), HttpServletResponse.SC_OK, created.status());
		String link = ShareLinkCreated.readShareLinkCreated(reader(body(created))).getToken();

		String tabletId = current(deviceList(devices(tablet))).getId();
		assertEquals(HttpServletResponse.SC_OK, unpair(tablet, tabletId).status());

		assertEquals("The tablet is gone.", HttpServletResponse.SC_UNAUTHORIZED, devices(tablet).status());
		assertEquals("The link it made is alice's, not the tablet's.", HttpServletResponse.SC_OK,
			get("/", "json", link).status());
	}

	public void testAnInvitationOutlivesTheDeviceThatIssuedIt() throws Exception {
		String tablet = secondDevice("alice");
		String invitation = created(invite(tablet, Roles.GUEST)).getToken();
		String tabletId = current(deviceList(devices(tablet))).getId();
		assertEquals(HttpServletResponse.SC_OK, unpair(tablet, tabletId).status());

		FakeResponse accepted = accept(invitation, "fred");
		assertEquals(body(accepted), HttpServletResponse.SC_OK, accepted.status());
		assertEquals("alice", store().lookup(invitation).getInvitedBy());
	}

	// --- A rename under a link tile. ---

	public void testARenamedGroupKeepsItsMembersLinkTileWorking() throws Exception {
		new GroupStore(_base).put("family", "alice", Collections.singletonList("bob"));
		new GrantStore(_base).grant("alice", SharingFixture.ZOO, Subjects.group("family"),
			Collections.singletonList(Rights.VIEW));
		restartServer();
		assertTrue("The grant to the group puts the zoo into bob's root.",
			entryNames(listing(get("/", "json", SharingFixture.BOB))).contains("2024-05-01 Zoo"));

		assertEquals(HttpServletResponse.SC_OK, regroup(SharingFixture.ALICE, "family", "fam").status());

		assertTrue("The tile is still there after the rename.",
			entryNames(listing(get("/", "json", SharingFixture.BOB))).contains("2024-05-01 Zoo"));
		FakeResponse album = get("/2024-05-01 Zoo/", "json", SharingFixture.BOB);
		assertEquals("The link still resolves: the grant followed the group's name.", HttpServletResponse.SC_OK,
			album.status());
		assertNull(new GroupStore(_base).getGroup("family"));
		assertNotNull(new GroupStore(_base).getGroup("fam"));
	}

	public void testAGroupIsNeverNamedLikeAUserByCreationNorByRename() throws Exception {
		// A user may not take a group's name (issue #52), so a group may not take a user's: the two
		// are spelled apart on the wire, but nowhere a person reads them.
		new GroupStore(_base).put("family", "alice", Collections.singletonList("bob"));
		restartServer();
		Map<String, String> group = new HashMap<>();
		group.put("action", "group");

		FakeResponse createdAsUser = post("/", "{\"name\":\"carol\",\"members\":[{\"name\":\"bob\"}]}",
			SharingFixture.ALICE, group);
		assertEquals(HttpServletResponse.SC_CONFLICT, createdAsUser.status());
		assertFalse(errorMessage(createdAsUser).isEmpty());
		assertNull(new GroupStore(_base).getGroup("carol"));

		FakeResponse renamedToUser = regroup(SharingFixture.ALICE, "family", "carol");
		assertEquals(HttpServletResponse.SC_CONFLICT, renamedToUser.status());
		assertEquals(errorMessage(createdAsUser), errorMessage(renamedToUser));
		assertNotNull("The group kept its name.", new GroupStore(_base).getGroup("family"));
	}

	// --- A guest's devices across a promotion. ---

	public void testAGuestsDeviceIdsSurviveThePromotion() throws Exception {
		String phone = SharingFixture.EVE;
		String tablet = secondDevice("eve");
		DeviceList before = deviceList(devices(phone));
		assertEquals(2, before.getDevices().size());
		String phoneId = current(before).getId();
		assertFalse(phoneId.equals(current(deviceList(devices(tablet))).getId()));

		assertEquals(HttpServletResponse.SC_OK, promote(SharingFixture.ALICE, "eve").status());
		restartServer();

		DeviceList after = deviceList(devices(phone));
		assertEquals(2, after.getDevices().size());
		assertEquals("Ids are stable; the promotion changed the user, not the devices.", phoneId,
			current(after).getId());
		assertEquals(HttpServletResponse.SC_OK, unpair(phone, current(deviceList(devices(tablet))).getId()).status());
		assertEquals(1, deviceList(devices(phone)).getDevices().size());
	}

	// --- Nothing is cached about who is calling. ---

	public void testTheDeviceListIsNeverCached() throws Exception {
		FakeResponse response = devices(SharingFixture.BOB);
		assertEquals("no-store", response.header("Cache-Control"));
	}

	@SuppressWarnings("unused")
	private static String tomorrow() {
		return Instant.now().plus(1, ChronoUnit.DAYS).toString();
	}
}
