/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.Clearances;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.auth.UserStore.Device;
import de.haumacher.imageServer.auth.UserStore.User;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import javax.imageio.ImageIO;

/**
 * The shared library the sharing tests are written against, on the space model of #83.
 *
 * <p>
 * One space with five users of different roles: alice administers it, carol may change everything,
 * bob may only add (and may share), dave may only look, and eve may only look and sees nothing
 * restricted. The folders are the
 * ones the tests before #83 used, so that what an album holds stays comparable; they are ordinary
 * folders of the one space now, not spaces of their own.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
class SharingFixture {

	/** The pairing secret of a server serving this library. */
	static final String SECRET = "let-me-in";

	/** The token of the admin, who owns the shared albums. */
	static final String ALICE = "alice-token";

	/** The token of the user who may add to an album but change nothing (role <code>contribute</code>). */
	static final String BOB = "bob-token";

	/** The token of the user who may change every album (role <code>edit</code>). */
	static final String CAROL = "carol-token";

	/** The token of the user who may only look (role <code>view</code>). */
	static final String DAVE = "dave-token";

	/** The token of the user who may only look and sees nothing restricted (clearance <code>public</code>). */
	static final String EVE = "eve-token";

	/** The year folder bob may look at. */
	static final String YEAR = "2024";

	/** The album the group may contribute to, below {@link #YEAR}. */
	static final String ZOO = "2024/2024-05-01 Zoo";

	/** The folder open to the anonymous caller. */
	static final String PUBLIC = "Public";

	/** The folder nothing is granted on. */
	static final String PRIVATE = "Private";

	/** The album a photo is moved into the zoo from. */
	static final String CAROLS_ALBUM = "Inbox";

	private SharingFixture() {
		// Fixtures only.
	}

	/** Creates the library described by {@link SharingFixture} below the given base folder. */
	static void create(Path base) throws IOException {
		UserStore users = new UserStore(base);
		User alice = users.nameOwner("alice");
		alice.addDevice(new Device("Alice's phone", UserStore.hash(ALICE), Instant.now().toString()));
		users.addUser(user("bob", BOB, Roles.CONTRIBUTE, Clearances.ALL, true));
		users.addUser(user("carol", CAROL, Roles.EDIT, Clearances.NON_PRIVATE, false));
		users.addUser(user("dave", DAVE, Roles.VIEW, Clearances.NON_PRIVATE, false));
		users.addUser(user("eve", EVE, Roles.VIEW, Clearances.PUBLIC, false));
		users.store();

		album(base, ZOO, "Zoo",
			"[\"ImagePart\",{\"name\":\"public.jpg\",\"width\":4,\"height\":3}],"
				+ "[\"ImagePart\",{\"name\":\"members.jpg\",\"width\":4,\"height\":3,\"privacy\":1}],"
				+ "[\"ImagePart\",{\"name\":\"private.jpg\",\"width\":4,\"height\":3,\"privacy\":2}]",
			"public.jpg", "members.jpg", "private.jpg");
		album(base, PUBLIC, "Public",
			"[\"ImagePart\",{\"name\":\"open.jpg\",\"width\":4,\"height\":3}]", "open.jpg");
		album(base, PRIVATE, "Private",
			"[\"ImagePart\",{\"name\":\"secret.jpg\",\"width\":4,\"height\":3}]", "secret.jpg");
		album(base, CAROLS_ALBUM, "Inbox",
			"[\"ImagePart\",{\"name\":\"carols.jpg\",\"width\":4,\"height\":3}]", "carols.jpg");
	}

	private static User user(String name, String token, String role, String clearance, boolean share) {
		User result = new User(name, role, "", Instant.now().toString(), clearance, share);
		result.addDevice(new Device(name + "'s device", UserStore.hash(token), Instant.now().toString()));
		return result;
	}

	/** An album folder with the given sidecar parts and a tiny JPEG for each of the given names. */
	static void album(Path base, String path, String title, String parts, String... images) throws IOException {
		Path folder = base.resolve(path);
		Files.createDirectories(folder);
		Files.write(folder.resolve("index.json"),
			("[\"AlbumInfo\",{\"title\":\"" + title + "\",\"parts\":[" + parts + "]}]")
				.getBytes(StandardCharsets.UTF_8));
		for (String image : images) {
			// Every photo must have contents of its own, or a move would find a duplicate.
			BufferedImage contents = new BufferedImage(4, 3, BufferedImage.TYPE_3BYTE_BGR);
			contents.setRGB(0, 0, (path + "/" + image).hashCode() & 0xFFFFFF);
			ImageIO.write(contents, "jpg", folder.resolve(image).toFile());
		}
	}
}
