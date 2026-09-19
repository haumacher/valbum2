/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.Clearances;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.SpaceMode;
import de.haumacher.imageServer.auth.SpaceStore;
import de.haumacher.imageServer.auth.Spaces;
import de.haumacher.imageServer.auth.SpacesMigration;
import de.haumacher.imageServer.auth.UserStore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Test case for <code>--migrate-to-spaces</code>, see {@link SpacesMigration} and issue #82.
 *
 * <p>
 * Both old layouts are built from hand-written files in their documented formats, so the test also
 * says that a store written before this package reads unchanged.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestSpacesMigration extends TestCase {

	/** A device token hash, as the user store records one. */
	private static final String ALICE_TOKEN_HASH =
		"1111111111111111111111111111111111111111111111111111111111111111";

	private static final String BOB_TOKEN_HASH =
		"2222222222222222222222222222222222222222222222222222222222222222";

	private Path _base;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-spaces-migration");
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

	/** A library that was never migrated per user is the one space it always was. */
	public void testUnmigratedLibraryIsOneSpace() throws Exception {
		String album = album("2024 Trip");
		String users = "{\"version\":1,\"users\":[{\"name\":\"haui\",\"role\":\"admin\",\"space\":\"\","
			+ "\"created\":\"2026-09-06T10:11:12Z\",\"devices\":[{\"id\":\"abcdefgh\",\"name\":\"Phone\","
			+ "\"tokenHash\":\"" + ALICE_TOKEN_HASH + "\",\"created\":\"2026-09-06T10:11:12Z\"}]}]}";
		serverState("users.json", users);

		SpacesMigration.Report report = SpacesMigration.migrate(_base);

		assertTrue(report.toString(), report.mentions("never migrated per user"));
		assertTrue(report.toString(), report.mentions("Nothing was moved"));
		assertTrue("A single-space server has no space segments.", report.getSpaces().isEmpty());
		assertEquals("The user store stays exactly where and what it was.", users,
			read(_base.resolve(".valbum").resolve("users.json")));
		assertFalse("Nothing is marked as a space.",
			Files.exists(_base.resolve(".valbum").resolve(SpaceStore.FILE_NAME)));
		assertEquals("An album sidecar is never touched.", album, read(_base.resolve("2024 Trip").resolve("index.json")));

		assertEquals(SpaceMode.SINGLE, Spaces.detect(_base, null, de.haumacher.imageServer.auth.AuthMode.WRITES, de.haumacher.imageServer.auth.InviteMode.MEMBERS).getMode());
	}

	/** A library migrated per user becomes a multi-space server, one space per user folder. */
	public void testPerUserLibraryBecomesSpaces() throws Exception {
		String aliceAlbum = album("alice/2024 Alice");
		album("bob/2024 Bob");
		perUserUsers();
		serverState("grants.json", "{\"version\":1,\"grants\":[{\"owner\":\"alice\",\"path\":\"2024 Alice\","
			+ "\"subject\":\"user:bob\",\"rights\":[\"view\"],\"created\":\"2026-09-06T10:11:12Z\"}]}");
		serverState("groups.json", "{\"version\":1,\"groups\":[]}");
		serverState("shares.json", "{\"version\":1,\"shares\":[]}");
		serverState("invitations.json", "{\"version\":1,\"invitations\":[]}");
		serverState("device-codes.json", "{\"version\":1,\"codes\":[]}");
		Files.createDirectories(_base.resolve(".valbum").resolve("guests").resolve("carl"));
		Files.write(_base.resolve("alice").resolve("2024 Alice").resolve(".links.json"),
			"{\"links\":[]}".getBytes(StandardCharsets.UTF_8));
		Files.createDirectories(_base.resolve("bob").resolve(".valbum"));
		Files.write(_base.resolve("bob").resolve(".valbum").resolve("share-registry.json"),
			"{\"entries\":[]}".getBytes(StandardCharsets.UTF_8));

		SpacesMigration.Report report = SpacesMigration.migrate(_base);

		assertEquals(java.util.Arrays.asList("alice", "bob"), report.getSpaces());

		// Every user folder is a space now, and its owner is its admin.
		for (String space : new String[] { "alice", "bob" }) {
			assertTrue("No space marker in '" + space + "'.", SpaceStore.isSpace(_base.resolve(space)));
			assertEquals(space, SpaceStore.load(_base.resolve(space), space).getName());
			assertEquals("A migrated space is closed until somebody opens it.",
				SpaceStore.ANONYMOUS_NONE, SpaceStore.load(_base.resolve(space), space).getAnonymous());

			UserStore users = new UserStore(_base.resolve(space));
			UserStore.User admin = users.getOwner();
			assertNotNull("No admin in '" + space + "'.", admin);
			assertEquals(space, admin.getName());
			assertEquals(Roles.ADMIN, admin.getRole());
			assertEquals("The admin of a space is rooted at it.", "", admin.getSpace());
			assertEquals(Clearances.ALL, admin.getClearance());
			assertTrue(admin.isShare());
			assertEquals("Their device rides along.", 1, admin.getDevices().size());
		}
		assertEquals("The token that worked keeps working, in this space.", ALICE_TOKEN_HASH,
			new UserStore(_base.resolve("alice")).getOwner().getDevices().get(0).getTokenHash());
		assertEquals(BOB_TOKEN_HASH,
			new UserStore(_base.resolve("bob")).getOwner().getDevices().get(0).getTokenHash());

		// What the space model cannot represent is set aside, never deleted, and named.
		Path retired = retired();
		for (String file : new String[] { "users.json", "grants.json", "groups.json", "shares.json",
			"invitations.json", "device-codes.json", "guests" }) {
			assertFalse("'" + file + "' must not stay at the base folder.",
				Files.exists(_base.resolve(".valbum").resolve(file)));
			assertTrue("'" + file + "' must be set aside, not deleted.", Files.exists(retired.resolve(file)));
			assertTrue("The report must name '" + file + "': " + report,
				report.mentions("Set aside '" + file + "'"));
		}
		assertTrue("The report must say what a grant was: " + report,
			report.mentions("invite them into that space"));
		assertTrue("The report must say what a group was: " + report,
			report.mentions("invite the members individually"));
		assertTrue("The report must say what a share link was: " + report,
			report.mentions("share again from within the space"));
		assertTrue("The report must say what a guest root was: " + report,
			report.mentions("a guest is a user with a low clearance now"));

		assertFalse("A link sidecar must not stay in the tree.",
			Files.exists(_base.resolve("alice").resolve("2024 Alice").resolve(".links.json")));
		assertTrue(Files.exists(retired.resolve("tree").resolve("alice").resolve("2024 Alice").resolve(".links.json")));
		assertFalse(Files.exists(_base.resolve("bob").resolve(".valbum").resolve("share-registry.json")));
		assertTrue(Files.exists(retired.resolve("tree").resolve("bob").resolve(".valbum").resolve("share-registry.json")));
		assertTrue("The report must name the link sidecar: " + report, report.mentions(".links.json"));

		assertEquals("An album sidecar is never touched.", aliceAlbum,
			read(_base.resolve("alice").resolve("2024 Alice").resolve("index.json")));

		// And the server comes up as a multi-space server afterwards.
		Spaces spaces = Spaces.detect(_base, null, de.haumacher.imageServer.auth.AuthMode.WRITES, de.haumacher.imageServer.auth.InviteMode.MEMBERS);
		assertEquals(SpaceMode.MULTI, spaces.getMode());
		assertEquals(java.util.Arrays.asList("alice", "bob"), spaces.segments());
	}

	/** A library that is already a multi-space server is not migrated again. */
	public void testSecondMigrationRefused() throws Exception {
		album("alice/2024 Alice");
		perUserUsers();
		SpacesMigration.migrate(_base);

		try {
			SpacesMigration.migrate(_base);
			fail("Expected a refusal.");
		} catch (SpacesMigration.MigrationRefused expected) {
			assertEquals(SpacesMigration.alreadyMigrated("alice"), expected.getMessage());
		}
	}

	/** A user whose folder is gone stops the migration before anything is moved. */
	public void testMissingSpaceFolderRefused() throws Exception {
		album("alice/2024 Alice");
		perUserUsers();
		try (Stream<Path> files = Files.walk(_base.resolve("bob"))) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
		String users = read(_base.resolve(".valbum").resolve("users.json"));

		try {
			SpacesMigration.migrate(_base);
			fail("Expected a refusal.");
		} catch (SpacesMigration.MigrationRefused expected) {
			assertEquals(SpacesMigration.spaceMissing("bob", "bob"), expected.getMessage());
		}
		assertEquals("A refused migration moves nothing.", users,
			read(_base.resolve(".valbum").resolve("users.json")));
		assertFalse(SpaceStore.isSpace(_base.resolve("alice")));
	}

	private Path retired() throws Exception {
		Path parent = _base.resolve(".valbum").resolve(SpacesMigration.RETIRED_DIRECTORY_NAME);
		assertTrue("Nothing was set aside.", Files.isDirectory(parent));
		try (Stream<Path> runs = Files.list(parent)) {
			List<Path> all = runs.sorted().toList();
			assertEquals("Expected exactly one migration run.", 1, all.size());
			return all.get(0);
		}
	}

	/** The user store of a library migrated per user (issue #45), in its documented format. */
	private void perUserUsers() throws Exception {
		Files.createDirectories(_base.resolve("alice"));
		Files.createDirectories(_base.resolve("bob"));
		serverState("users.json", "{\"version\":1,\"users\":["
			+ "{\"name\":\"alice\",\"role\":\"admin\",\"space\":\"alice\",\"created\":\"2026-09-06T10:11:12Z\","
			+ "\"devices\":[{\"id\":\"aaaaaaaa\",\"name\":\"Phone\",\"tokenHash\":\"" + ALICE_TOKEN_HASH
			+ "\",\"created\":\"2026-09-06T10:11:12Z\"}]},"
			+ "{\"name\":\"bob\",\"role\":\"member\",\"space\":\"bob\",\"created\":\"2026-09-07T10:11:12Z\","
			+ "\"devices\":[{\"id\":\"bbbbbbbb\",\"name\":\"Tablet\",\"tokenHash\":\"" + BOB_TOKEN_HASH
			+ "\",\"created\":\"2026-09-07T10:11:12Z\"}]}]}");
	}

	private void serverState(String name, String contents) throws Exception {
		Path directory = _base.resolve(".valbum");
		Files.createDirectories(directory);
		Files.write(directory.resolve(name), contents.getBytes(StandardCharsets.UTF_8));
	}

	/** An album folder with a sidecar, whose bytes must survive every migration. */
	private String album(String path) throws Exception {
		Path folder = _base.resolve(path);
		Files.createDirectories(folder);
		String index = "[\"AlbumInfo\",{\"title\":\"" + folder.getFileName() + "\",\"parts\":[]}]";
		Files.write(folder.resolve("index.json"), index.getBytes(StandardCharsets.UTF_8));
		return index;
	}

	private static String read(Path file) throws Exception {
		return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
	}

}
