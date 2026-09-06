/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.LibraryMigration;
import de.haumacher.imageServer.auth.LibraryMigration.MigrationRefused;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.auth.UserStore.User;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Test case for {@link LibraryMigration}, the explicit <code>--migrate-to-user</code> command of
 * issue #45.
 *
 * <p>
 * The command is the one sanctioned move of a user's files: a rename of whole entries into the
 * owner's space folder. Everything is checked before anything moves, so every refusal here also
 * asserts that the tree and the user store are untouched.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestLibraryMigration extends TestCase {

	private static final String ALBUM = "2020-01-01 Trip";

	private Path _base;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-library-migration");
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

	public void testTheLibraryMovesIntoTheOwnersFolder() throws Exception {
		String token = library();

		List<String> moved = LibraryMigration.migrate(_base, "haui");

		assertEquals(Arrays.asList(ALBUM, ".hashes.json", "index.json").stream().sorted()
			.collect(Collectors.toList()), moved);

		assertTrue(_base.resolve("haui").resolve(ALBUM).resolve("a.jpg").toFile().exists());
		assertTrue(_base.resolve("haui").resolve(ALBUM).resolve("index.json").toFile().exists());
		assertTrue(_base.resolve("haui").resolve("index.json").toFile().exists());
		assertTrue(_base.resolve("haui").resolve(".hashes.json").toFile().exists());

		assertFalse("The original entries must be gone from the base folder.",
			_base.resolve(ALBUM).toFile().exists());
		assertFalse(_base.resolve("index.json").toFile().exists());

		assertTrue("The server's own state stays at the base folder.",
			_base.resolve(UserStore.DIRECTORY_NAME).resolve(UserStore.FILE_NAME).toFile().exists());
		assertTrue("The upload staging area stays at the base folder.",
			_base.resolve(UserStore.UPLOAD_DIRECTORY_NAME).toFile().isDirectory());

		UserStore store = new UserStore(_base);
		User owner = store.getOwner();
		assertEquals("haui", owner.getName());
		assertEquals("haui", owner.getSpace());
		assertNotNull("The token issued before the migration must still authenticate.", store.lookup(token));
	}

	public void testTheContentsOfAMovedAlbumAreUntouched() throws Exception {
		library();
		String before = read(_base.resolve(ALBUM).resolve("a.jpg"));

		LibraryMigration.migrate(_base, "haui");

		assertEquals("An original is never rewritten by the migration.", before,
			read(_base.resolve("haui").resolve(ALBUM).resolve("a.jpg")));
	}

	public void testTheMigratedLibraryIsTheOwnersRoot() throws Exception {
		String token = library();
		LibraryMigration.migrate(_base, "haui");

		UserStore store = new UserStore(_base);
		assertEquals("haui", store.lookup(token).getUser().getSpace());
	}

	public void testMigrationOfAnEmptyLibrary() throws Exception {
		List<String> moved = LibraryMigration.migrate(_base, "haui");

		assertTrue(moved.isEmpty());
		assertTrue(_base.resolve("haui").toFile().isDirectory());
		assertEquals("haui", new UserStore(_base).getOwner().getSpace());
	}

	public void testMigrationIntoAnExistingEmptyFolder() throws Exception {
		library();
		Files.createDirectory(_base.resolve("haui"));

		List<String> moved = LibraryMigration.migrate(_base, "haui");

		assertTrue(moved.contains(ALBUM));
		assertTrue(_base.resolve("haui").resolve(ALBUM).resolve("a.jpg").toFile().exists());
	}

	// --- Refusals: nothing moves, the store is unchanged. ---

	public void testSecondMigrationRefused() throws Exception {
		library();
		LibraryMigration.migrate(_base, "haui");
		Files.createDirectory(_base.resolve("new album"));

		try {
			LibraryMigration.migrate(_base, "haui");
			fail("Expected the second migration to be refused.");
		} catch (MigrationRefused ex) {
			assertEquals(LibraryMigration.alreadyMigrated("haui"), ex.getMessage());
		}
		assertTrue("Nothing may move on a refusal.", _base.resolve("new album").toFile().isDirectory());
		assertEquals("haui", new UserStore(_base).getOwner().getSpace());
	}

	public void testMigrationUnderAnotherNameRefused() throws Exception {
		library();
		UserStore store = new UserStore(_base);
		store.nameOwner("haui");
		store.store();

		try {
			LibraryMigration.migrate(_base, "somebody-else");
			fail("Expected a name other than the owner's to be refused.");
		} catch (MigrationRefused ex) {
			assertEquals(LibraryMigration.nameMismatch("haui", "somebody-else"), ex.getMessage());
		}
		assertUntouched();
		assertEquals("", new UserStore(_base).getOwner().getSpace());
	}

	public void testInvalidNameRefused() throws Exception {
		library();

		for (String name : new String[] { "a/b", "..", ".hidden", "  " }) {
			try {
				LibraryMigration.migrate(_base, name);
				fail("Expected '" + name + "' to be refused as a folder name.");
			} catch (MigrationRefused ex) {
				assertTrue(ex.getMessage(), ex.getMessage().startsWith(UserStore.NAME_REFUSED));
			}
		}
		assertUntouched();
	}

	public void testNonEmptyTargetRefused() throws Exception {
		library();
		Files.createDirectory(_base.resolve("haui"));
		Files.write(_base.resolve("haui").resolve("stranger.jpg"), "x".getBytes(StandardCharsets.UTF_8));

		try {
			LibraryMigration.migrate(_base, "haui");
			fail("Expected an occupied target folder to be refused.");
		} catch (MigrationRefused ex) {
			assertEquals(LibraryMigration.targetNotEmpty("haui"), ex.getMessage());
		}
		assertUntouched();
	}

	public void testTargetThatIsAFileRefused() throws Exception {
		library();
		Files.write(_base.resolve("haui"), "not a folder".getBytes(StandardCharsets.UTF_8));

		try {
			LibraryMigration.migrate(_base, "haui");
			fail("Expected a target that is a file to be refused.");
		} catch (MigrationRefused ex) {
			assertEquals(LibraryMigration.targetNotAFolder("haui"), ex.getMessage());
		}
		assertUntouched();
	}

	public void testAnEntryThatWouldBeOverwrittenIsCaughtBeforeAnythingMoves() throws Exception {
		library();

		// The target already holds an entry of the same name as one that would move into it. It is
		// found while the entries are walked, and that walk happens before the first move.
		Files.createDirectories(_base.resolve("haui").resolve(ALBUM));

		try {
			LibraryMigration.migrate(_base, "haui");
			fail("Expected an overwriting move to be refused.");
		} catch (MigrationRefused ex) {
			assertEquals(LibraryMigration.targetNotEmpty("haui"), ex.getMessage());
		}
		assertUntouched();
	}

	// --- Helpers. ---

	/**
	 * A base folder in the pre-issue-#45 layout: albums and sidecars at the root, the server's own
	 * state beside them.
	 *
	 * @return A token issued before the migration.
	 */
	private String library() throws IOException {
		Path album = _base.resolve(ALBUM);
		Files.createDirectories(album);
		Files.write(album.resolve("a.jpg"), "the original bytes".getBytes(StandardCharsets.UTF_8));
		Files.write(album.resolve("index.json"),
			"[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[]}]".getBytes(StandardCharsets.UTF_8));
		Files.write(_base.resolve("index.json"),
			"[\"ListingInfo\",{\"title\":\"Library\"}]".getBytes(StandardCharsets.UTF_8));
		Files.write(_base.resolve(".hashes.json"), "{}".getBytes(StandardCharsets.UTF_8));
		Files.createDirectories(_base.resolve(UserStore.UPLOAD_DIRECTORY_NAME));

		UserStore store = new UserStore(_base);
		return store.addDevice(store.createOwner(), "Phone");
	}

	private void assertUntouched() {
		assertTrue("Nothing may move on a refusal.", _base.resolve(ALBUM).resolve("a.jpg").toFile().exists());
		assertTrue(_base.resolve("index.json").toFile().exists());
		assertTrue(_base.resolve(".hashes.json").toFile().exists());
	}

	private static String read(Path file) throws IOException {
		return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
	}
}
