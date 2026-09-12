/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.GrantStore;
import de.haumacher.imageServer.auth.GroupStore;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.Subjects;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.auth.UserStore.Device;
import de.haumacher.imageServer.auth.UserStore.User;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import javax.imageio.ImageIO;

/**
 * The shared library the grant tests of issue #49 are written against.
 *
 * <p>
 * A migrated library with four spaces and one group: alice is the admin and owns the albums that
 * are shared, bob is granted a look at her year folder, the group <code>family</code> may
 * contribute to one album in it, and the folder <code>Public</code> is open to anybody. dave holds
 * nothing, eve is a guest without a space of her own.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
class SharingFixture {

	/** The pairing secret of a server serving this library. */
	static final String SECRET = "let-me-in";

	/** The token of the admin, who owns the shared albums. */
	static final String ALICE = "alice-token";

	/** The token of the member granted <code>view</code> and <code>download</code> on 2024. */
	static final String BOB = "bob-token";

	/** The token of the member who may contribute to the zoo album through the group. */
	static final String CAROL = "carol-token";

	/** The token of the member nothing is granted to. */
	static final String DAVE = "dave-token";

	/** The token of the guest, who has no space of her own and holds nothing. */
	static final String EVE = "eve-token";

	/** The year folder bob may look at. */
	static final String YEAR = "2024";

	/** The album the group may contribute to, below {@link #YEAR}. */
	static final String ZOO = "2024/2024-05-01 Zoo";

	/** The folder open to the anonymous caller. */
	static final String PUBLIC = "Public";

	/** The folder nothing is granted on. */
	static final String PRIVATE = "Private";

	/** The album in carol's own space, from which she moves a photo into the zoo. */
	static final String CAROLS_ALBUM = "Inbox";

	private SharingFixture() {
		// Fixtures only.
	}

	/** Creates the library described by {@link SharingFixture} below the given base folder. */
	static void create(Path base) throws IOException {
		UserStore users = new UserStore(base);
		User alice = users.nameOwner("alice");
		alice.setSpace("alice");
		alice.addDevice(new Device("Alice's phone", UserStore.hash(ALICE), Instant.now().toString()));
		users.addUser(member(users, "bob", BOB));
		users.addUser(member(users, "carol", CAROL));
		users.addUser(member(users, "dave", DAVE));
		User eve = new User("eve", Roles.GUEST, "", Instant.now().toString());
		eve.addDevice(new Device("Eve's phone", UserStore.hash(EVE), Instant.now().toString()));
		users.addUser(eve);
		users.store();

		new GroupStore(base).put("family", "alice", Arrays.asList("bob", "carol"));

		GrantStore grants = new GrantStore(base);
		grants.grant("alice", YEAR, Subjects.user("bob"), Arrays.asList(Rights.VIEW, Rights.DOWNLOAD));
		grants.grant("alice", ZOO, Subjects.group("family"), Collections.singletonList(Rights.CONTRIBUTE));
		grants.grant("alice", PUBLIC, Subjects.ANONYMOUS, Collections.singletonList(Rights.VIEW));

		album(base, "alice/" + ZOO, "Zoo",
			"[\"ImagePart\",{\"name\":\"public.jpg\",\"width\":4,\"height\":3}],"
				+ "[\"ImagePart\",{\"name\":\"members.jpg\",\"width\":4,\"height\":3,\"privacy\":1}],"
				+ "[\"ImagePart\",{\"name\":\"private.jpg\",\"width\":4,\"height\":3,\"privacy\":2}]",
			"public.jpg", "members.jpg", "private.jpg");
		album(base, "alice/" + PUBLIC, "Public",
			"[\"ImagePart\",{\"name\":\"open.jpg\",\"width\":4,\"height\":3}]", "open.jpg");
		album(base, "alice/" + PRIVATE, "Private",
			"[\"ImagePart\",{\"name\":\"secret.jpg\",\"width\":4,\"height\":3}]", "secret.jpg");
		album(base, "carol/" + CAROLS_ALBUM, "Inbox",
			"[\"ImagePart\",{\"name\":\"carols.jpg\",\"width\":4,\"height\":3}]", "carols.jpg");
		Files.createDirectories(base.resolve("bob"));
		Files.createDirectories(base.resolve("dave"));
	}

	private static User member(UserStore users, String name, String token) {
		User result = new User(name, Roles.MEMBER, name, Instant.now().toString());
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
