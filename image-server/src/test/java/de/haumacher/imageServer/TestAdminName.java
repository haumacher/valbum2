/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.InviteMode;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.SpaceStore;
import de.haumacher.imageServer.auth.Spaces;
import de.haumacher.imageServer.auth.SpacesMigration;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.upload.HashCache;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * The administrator of a space has a name, see issue #86.
 *
 * <p>
 * A library written before Phase 6 could have a nameless owner — the one user of a library needed
 * no name. In a space the administrator is one user among several: their name is what the users
 * list shows, what an attribution records, and what a permission change addresses them by. So a
 * nameless one is named once, at start-up or by the migration, with their devices and token hashes
 * untouched.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestAdminName extends TestCase {

	/** The token of the device the nameless owner of the older library signed in on. */
	private static final String OLD_TOKEN = "old-token";

	private Path _base;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-admin-name");
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

	// --- Naming at start-up. ---

	/** On a single-space server there is no better name than {@link AuthService#DEFAULT_OWNER_NAME}. */
	public void testASingleSpaceServerNamesTheOwnerOwner() throws Exception {
		namelessOwner(_base);

		AuthService auth = new AuthService(AuthMode.WRITES, "secret", _base);
		assertEquals(AuthService.DEFAULT_OWNER_NAME, auth.nameNamelessOwner(""));

		UserStore users = new UserStore(_base);
		assertEquals(AuthService.DEFAULT_OWNER_NAME, users.getOwner().getName());
		assertEquals("The store is written once, not on every read.", null,
			new AuthService(AuthMode.WRITES, "secret", _base).nameNamelessOwner(""));
	}

	/** On a multi-space server the space's own name is the obvious one. */
	public void testASpaceNamesItsOwnerAfterItself() throws Exception {
		Path alice = _base.resolve("alice");
		Files.createDirectories(alice.resolve(".valbum"));
		Files.write(SpaceStore.file(alice), "{}".getBytes(StandardCharsets.UTF_8));
		namelessOwner(alice);

		Spaces spaces = Spaces.detect(_base, null, AuthMode.WRITES, "secret", InviteMode.MEMBERS);
		Spaces.Space space = spaces.bySegment("alice");
		assertEquals("alice", space.getAuth().nameNamelessOwner(space.getSegment()));
		assertEquals("alice", new UserStore(alice).getOwner().getName());
	}

	/** A name already taken by a folder of the space gets a number, and nothing is shadowed. */
	public void testAnOccupiedNameGetsANumber() throws Exception {
		namelessOwner(_base);
		Files.createDirectories(_base.resolve(AuthService.DEFAULT_OWNER_NAME));

		AuthService auth = new AuthService(AuthMode.WRITES, "secret", _base);
		assertEquals(AuthService.DEFAULT_OWNER_NAME + "-2", auth.nameNamelessOwner(""));
	}

	/** A name already taken by another user gets a number too. */
	public void testAnotherUsersNameGetsANumber() throws Exception {
		namelessOwner(_base);
		UserStore users = new UserStore(_base);
		users.addUser(new UserStore.User("alice", Roles.VIEW, "", "2026-09-06T10:11:12Z"));
		users.store();

		AuthService auth = new AuthService(AuthMode.WRITES, "secret", _base);
		assertEquals("alice-2", auth.nameNamelessOwner("alice"));
	}

	/** A folder name no user may carry falls back to the default. */
	public void testAnImpossibleSuggestionFallsBackToTheDefault() throws Exception {
		namelessOwner(_base);

		AuthService auth = new AuthService(AuthMode.WRITES, "secret", _base);
		assertEquals(AuthService.DEFAULT_OWNER_NAME, auth.nameNamelessOwner("~odd"));
	}

	/** An administrator who has a name is never renamed. */
	public void testANamedAdministratorIsLeftAlone() throws Exception {
		namelessOwner(_base);
		UserStore users = new UserStore(_base);
		users.getOwner().setName("haui");
		users.store();

		assertNull(new AuthService(AuthMode.WRITES, "secret", _base).nameNamelessOwner("alice"));
		assertEquals("haui", new UserStore(_base).getOwner().getName());
	}

	/** Their devices and token hashes are untouched, so every device keeps working. */
	public void testEveryDeviceKeepsWorking() throws Exception {
		namelessOwner(_base);
		String hashBefore = new UserStore(_base).getOwner().getDevices().get(0).getTokenHash();

		AuthService auth = new AuthService(AuthMode.WRITES, "secret", _base);
		auth.nameNamelessOwner("");

		UserStore.User owner = new UserStore(_base).getOwner();
		assertEquals(1, owner.getDevices().size());
		assertEquals("Old", owner.getDevices().get(0).getName());
		assertEquals(hashBefore, owner.getDevices().get(0).getTokenHash());
		assertEquals("The very token that worked before still names the administrator.",
			AuthService.DEFAULT_OWNER_NAME,
			new AuthService(AuthMode.WRITES, "secret", _base).caller(bearer(OLD_TOKEN)).getUserName());
	}

	/** A signed-in user always has a name, so their contribution is never filed as anonymous. */
	public void testASignedInUserIsNeverAnonymous() throws Exception {
		namelessOwner(_base);
		new AuthService(AuthMode.WRITES, "secret", _base).nameNamelessOwner("");

		AuthService.Caller caller =
			new AuthService(AuthMode.WRITES, "secret", _base).caller(bearer(OLD_TOKEN));
		assertTrue(caller.isPaired());
		assertEquals("user:" + AuthService.DEFAULT_OWNER_NAME, caller.subject());
	}

	/** What the renamed administrator uploads is filed under their new name, not as anonymous. */
	public void testAnUploadByTheRenamedAdministratorIsAttributedToThem() throws Exception {
		namelessOwner(_base);
		Path album = _base.resolve("2024 Trip");
		Files.createDirectories(album);
		Files.write(album.resolve("index.json"),
			"[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[]}]".getBytes(StandardCharsets.UTF_8));

		String name = new AuthService(AuthMode.WRITES, "secret", _base).nameNamelessOwner("");
		assertEquals(AuthService.DEFAULT_OWNER_NAME, name);

		ImageServlet servlet =
			new ImageServlet(_base.toFile(), new AuthService(AuthMode.WRITES, "secret", _base));
		try {
			servlet.init(TestSpaces.config());
			assertEquals(HttpServletResponse.SC_OK, upload(servlet, "/2024 Trip/", OLD_TOKEN).status());

			// The hash sidecar beside the photo is where an attribution lives, see issue #53.
			HashCache.Attribution attribution =
				HashCache.recorded(album.toFile()).get("uploaded.jpg");
			assertNotNull("The upload must be recorded.", attribution);
			assertEquals("user:" + name, attribution.getContributor());
			assertEquals(name, attribution.getLabel());
		} finally {
			servlet.destroy();
		}
	}

	private static FakeResponse upload(ImageServlet servlet, String pathInfo, String token) throws Exception {
		String boundary = "----valbumAdminNameBoundary";
		java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
		body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; "
			+ "filename=\"uploaded.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n")
				.getBytes(StandardCharsets.UTF_8));
		body.write(photo());
		body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

		FakeResponse response = new FakeResponse();
		servlet.doPut(TestSpaces.request("PUT", pathInfo, java.util.Map.of(),
			java.util.Map.of("Authorization", "Bearer " + token), body.toByteArray(),
			"multipart/form-data; boundary=" + boundary), response.response());
		return response;
	}

	/** A tiny JPEG the server can analyse. */
	private static byte[] photo() throws Exception {
		java.awt.image.BufferedImage image =
			new java.awt.image.BufferedImage(8, 6, java.awt.image.BufferedImage.TYPE_3BYTE_BGR);
		java.util.Random random = new java.util.Random(86);
		for (int x = 0; x < 8; x++) {
			for (int y = 0; y < 6; y++) {
				image.setRGB(x, y, random.nextInt(0xFFFFFF));
			}
		}
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		javax.imageio.ImageIO.write(image, "jpg", out);
		return out.toByteArray();
	}

	// --- Naming by the migration. ---

	/** An un-migrated library moves nothing and still names its owner, with a report line. */
	public void testTheMigrationNamesTheOwnerOfAnUnmigratedLibrary() throws Exception {
		namelessOwner(_base);
		Files.createDirectories(_base.resolve("2024 Trip"));

		SpacesMigration.Report report = SpacesMigration.migrate(_base);

		assertTrue(report.toString(), report.mentions("had no name and is now '"
			+ AuthService.DEFAULT_OWNER_NAME + "'"));
		assertTrue("The report says that nothing of theirs was disturbed: " + report,
			report.mentions("their devices and tokens are untouched"));
		assertEquals(AuthService.DEFAULT_OWNER_NAME, new UserStore(_base).getOwner().getName());
	}

	/** A per-user library names the owner of each space after the folder that becomes it. */
	public void testTheMigrationNamesTheOwnerOfEachUserFolder() throws Exception {
		Files.createDirectories(_base.resolve("alice"));
		Files.createDirectories(_base.resolve(UserStore.DIRECTORY_NAME));
		Files.write(_base.resolve(UserStore.DIRECTORY_NAME).resolve(UserStore.FILE_NAME),
			("{\"version\":1,\"users\":[{\"name\":\"\",\"role\":\"admin\",\"space\":\"alice\","
				+ "\"created\":\"2026-09-06T10:11:12Z\",\"devices\":[{\"id\":\"aaaaaaaa\",\"name\":\"Old\","
				+ "\"tokenHash\":\"" + UserStore.hash(OLD_TOKEN) + "\",\"created\":\"2026-09-06T10:11:12Z\"}]}]}")
					.getBytes(StandardCharsets.UTF_8));

		SpacesMigration.Report report = SpacesMigration.migrate(_base);

		assertTrue(report.toString(), report.mentions("had no name and is now 'alice'"));
		UserStore.User admin = new UserStore(_base.resolve("alice")).getOwner();
		assertEquals("alice", admin.getName());
		assertEquals(Roles.ADMIN, admin.getRole());
		assertEquals("Their device rides along.", UserStore.hash(OLD_TOKEN),
			admin.getDevices().get(0).getTokenHash());
	}

	// --- Helpers. ---

	/** A user store as an older build wrote it: one administrator, without a name. */
	private static void namelessOwner(Path root) throws Exception {
		Path file = root.resolve(UserStore.DIRECTORY_NAME).resolve(UserStore.FILE_NAME);
		Files.createDirectories(file.getParent());
		Files.write(file, ("{\"version\":1,\"users\":[{\"name\":\"\",\"role\":\"admin\",\"space\":\"\","
			+ "\"created\":\"2026-09-06T10:11:12Z\",\"devices\":[{\"id\":\"aaaaaaaa\",\"name\":\"Old\","
			+ "\"tokenHash\":\"" + UserStore.hash(OLD_TOKEN) + "\",\"created\":\"2026-09-06T10:11:12Z\"}]}]}")
				.getBytes(StandardCharsets.UTF_8));
	}

	/** A request presenting the given token and nothing else. */
	private static jakarta.servlet.http.HttpServletRequest bearer(String token) {
		return TestSpaces.request("GET", "/", java.util.Map.of(),
			java.util.Map.of("Authorization", "Bearer " + token), new byte[0], null);
	}

}
