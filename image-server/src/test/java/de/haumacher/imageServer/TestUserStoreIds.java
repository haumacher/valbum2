/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.auth.UserStore.Device;
import de.haumacher.imageServer.auth.UserStore.User;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Test case for the device ids of issue #55 in the {@link UserStore}, and for what they do to a
 * store that was written before them.
 *
 * <p>
 * The fixture is the output of the writer of the build before issue #55, captured verbatim: two of
 * bob's devices are called <code>probe</code>, which is exactly the case an id has to tell apart.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestUserStoreIds extends TestCase {

	/** The token of the one device of the admin. */
	private static final String ALICE = "alice-token";

	/** The token of the first of bob's two devices, both called <code>probe</code>. */
	private static final String BOB_PHONE = "bob-token";

	/** The token of the second of them. */
	private static final String BOB_TABLET = "bob-tablet";

	/**
	 * A <code>users.json</code> as the build before issue #55 wrote it: no device carries an id.
	 */
	private static final String PRE_55_STORE = "{\"version\":1,\"users\":["
		+ "{\"name\":\"alice\",\"role\":\"admin\",\"space\":\"alice\",\"created\":\"2026-09-06T10:11:12Z\","
		+ "\"devices\":[{\"name\":\"probe\","
		+ "\"tokenHash\":\"9c220f200955d76c0a38d308225e0ef10c5f971acaf2f8d1d8f732affa5bd1dc\","
		+ "\"created\":\"2026-09-06T10:11:12Z\"}]},"
		+ "{\"name\":\"bob\",\"role\":\"member\",\"space\":\"bob\",\"created\":\"2026-09-07T08:09:10Z\","
		+ "\"devices\":[{\"name\":\"probe\","
		+ "\"tokenHash\":\"97dd3707015dcf069cf73022ed7173b1165db6eff24b441cb57fd069a8c4e525\","
		+ "\"created\":\"2026-09-07T08:09:11Z\"},"
		+ "{\"name\":\"probe\","
		+ "\"tokenHash\":\"7dc5684e263aa4e787775616ef49155692326a880d83a4c062b3011169ae77ac\","
		+ "\"created\":\"2026-09-07T08:09:12Z\"}]}]}";

	private Path _base;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-user-ids-test");
		Files.createDirectories(_base.resolve(UserStore.DIRECTORY_NAME));
		Files.write(_base.resolve(UserStore.DIRECTORY_NAME).resolve(UserStore.FILE_NAME),
			PRE_55_STORE.getBytes(StandardCharsets.UTF_8));
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

	// --- What the store before issue #55 becomes. ---

	public void testEveryTokenOfAStoreWrittenBeforeKeepsWorking() throws Exception {
		UserStore store = new UserStore(_base);

		assertEquals("alice", store.lookup(ALICE).getUser().getName());
		assertEquals("bob", store.lookup(BOB_PHONE).getUser().getName());
		assertEquals("bob", store.lookup(BOB_TABLET).getUser().getName());
		assertNull("A token nobody was issued opens nothing.", store.lookup("nonsense"));
	}

	public void testEveryDeviceGetsAnIdOfItsOwn() throws Exception {
		UserStore store = new UserStore(_base);

		List<Device> devices = allDevices(store);
		assertEquals(3, devices.size());
		Set<String> ids = new HashSet<>();
		for (Device device : devices) {
			assertFalse("Every device is named: " + device.getName(), device.getId().isEmpty());
			ids.add(device.getId());
		}
		assertEquals("Two devices of the same name are still two devices.", 3, ids.size());
	}

	public void testTheStoreIsWrittenBackOnceWithTheIds() throws Exception {
		new UserStore(_base);

		String written = contents();
		assertFalse("The file on disk carries the ids now.", written.equals(PRE_55_STORE));
		assertTrue(written, written.contains("\"id\":\""));
	}

	public void testTheIdsAreStable() throws Exception {
		List<String> first = ids(new UserStore(_base));
		String afterFirstLoad = contents();

		List<String> second = ids(new UserStore(_base));

		assertEquals("A second load assigns nothing new.", first, second);
		assertEquals("And writes nothing, there being nothing to write.", afterFirstLoad, contents());
	}

	public void testARoundTripIsEqual() throws Exception {
		UserStore store = new UserStore(_base);
		String afterLoad = contents();

		store.store();

		assertEquals("Reading and writing a store changes nothing.", afterLoad, contents());
		assertEquals(afterLoad, dump(new UserStore(_base)));
	}

	// --- A device built by a caller is named before it is written. ---

	public void testADeviceAddedWithoutAnIdIsNamedByTheStore() throws Exception {
		UserStore store = new UserStore(_base);
		User bob = store.getUser("bob");
		bob.addDevice(new Device("Laptop", UserStore.hash("bob-laptop"), "2026-09-13T00:00:00Z"));
		store.store();

		Device laptop = new UserStore(_base).lookup("bob-laptop").getDevice();
		assertFalse(laptop.getId().isEmpty());
		assertEquals(4, allDevices(new UserStore(_base)).size());
	}

	public void testSigningInGivesTheNewDeviceAnId() throws Exception {
		UserStore store = new UserStore(_base);
		String token = store.addDevice(store.getUser("bob"), "Watch");

		Device watch = new UserStore(_base).lookup(token).getDevice();
		assertEquals("Watch", watch.getName());
		assertFalse(watch.getId().isEmpty());
	}

	// --- Signing a device out. ---

	public void testRemovingADeviceForgetsItsToken() throws Exception {
		UserStore store = new UserStore(_base);
		User bob = store.getUser("bob");
		Device tablet = store.lookup(BOB_TABLET).getDevice();

		assertTrue(store.removeDevice(bob, tablet));

		UserStore reloaded = new UserStore(_base);
		assertNull("The token of a device that was signed out opens nothing.", reloaded.lookup(BOB_TABLET));
		assertEquals("bob", reloaded.lookup(BOB_PHONE).getUser().getName());
		assertEquals(1, reloaded.getUser("bob").getDevices().size());
	}

	// --- Helpers. ---

	private String contents() throws IOException {
		return new String(Files.readAllBytes(_base.resolve(UserStore.DIRECTORY_NAME).resolve(UserStore.FILE_NAME)),
			StandardCharsets.UTF_8);
	}

	/** The file the given store would write, without writing it where the fixture lies. */
	private static String dump(UserStore store) throws IOException {
		Path elsewhere = Files.createTempDirectory("valbum-user-ids-dump");
		try {
			UserStore copy = new UserStore(elsewhere);
			for (User user : store.getUsers()) {
				User added = copy.addUser(new User(user.getName(), user.getRole(), user.getSpace(), user.getCreated()));
				for (Device device : user.getDevices()) {
					added.addDevice(
						new Device(device.getId(), device.getName(), device.getTokenHash(), device.getCreated()));
				}
			}
			copy.store();
			return new String(Files.readAllBytes(copy.getFile()), StandardCharsets.UTF_8);
		} finally {
			try (Stream<Path> files = Files.walk(elsewhere)) {
				files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
	}

	private static List<Device> allDevices(UserStore store) {
		List<Device> result = new ArrayList<>();
		for (User user : store.getUsers()) {
			result.addAll(user.getDevices());
		}
		return result;
	}

	private static List<String> ids(UserStore store) {
		List<String> result = new ArrayList<>();
		for (Device device : allDevices(store)) {
			result.add(device.getId());
		}
		return result;
	}
}
