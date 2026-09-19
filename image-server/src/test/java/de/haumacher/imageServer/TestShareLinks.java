/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.Clearances;
import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.ShareStore;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.shared.model.AuthInfo;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ShareLink;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import de.haumacher.imageServer.shared.model.ShareLinkList;
import de.haumacher.imageServer.shared.model.UserList;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The share links of issue #84: what may be handed out, what a link may do, and who manages it.
 *
 * <p>
 * A link is its own permission — there is no grant beside it — and it is cut at the moment it is
 * made: what it shows is frozen then, so that what happens to its maker afterwards neither widens
 * nor narrows it.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestShareLinks extends ShareTestCase {

	private static final String ZOO = "/" + SharingFixture.ZOO + "/";

	// --- Who may hand out a link, and what it may say. ---

	public void testTheShareFlagDecides() throws Exception {
		assertEquals(HttpServletResponse.SC_OK, create(SharingFixture.ALICE, 0, Rights.VIEW).status());
		assertEquals("bob holds the flag though he may only contribute.", HttpServletResponse.SC_OK,
			create(SharingFixture.BOB, 0, Rights.VIEW).status());

		FakeResponse refused = create(SharingFixture.CAROL, 0, Rights.VIEW);
		assertEquals("carol may change every album and still hands out no link.",
			HttpServletResponse.SC_FORBIDDEN, refused.status());
		assertEquals(AuthService.SHARING_REFUSED, errorMessage(refused));
		assertEquals(HttpServletResponse.SC_FORBIDDEN, create(SharingFixture.DAVE, 0, Rights.VIEW).status());
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, create(null, 0, Rights.VIEW).status());
	}

	public void testALinkNeverAllowsEditing() throws Exception {
		FakeResponse refused = create(SharingFixture.ALICE, 0, Rights.VIEW, Rights.EDIT);
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, refused.status());
		assertEquals(AuthService.SHARE_EDIT_REFUSED, errorMessage(refused));
		assertTrue("Nothing may have been recorded.", new ShareStore(_base).getLinks().isEmpty());
	}

	public void testALinkNeverShowsMoreThanItsMakerMaySee() throws Exception {
		// bob's clearance is 'all', so he may cut a link anywhere.
		assertEquals(HttpServletResponse.SC_OK,
			create(SharingFixture.BOB, Privacy.MEMBERS, Rights.VIEW).status());

		// An administrator's clearance is everything, and a link still never shows the private
		// ones: what it is cut to is said, not silently trimmed.
		assertEquals(HttpServletResponse.SC_OK,
			create(SharingFixture.ALICE, Privacy.PRIVATE, Rights.VIEW).status());

		// Somebody who may share but sees less cannot hand out more than they see.
		setClearance("bob", Clearances.PUBLIC);
		FakeResponse refused = create(SharingFixture.BOB, Privacy.MEMBERS, Rights.VIEW);
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, refused.status());
		assertEquals(AuthService.shareAboveClearance(Privacy.PUBLIC), errorMessage(refused));
		assertEquals("It is refused, never quietly trimmed.", 2, new ShareStore(_base).getLinks().size());
	}

	public void testWhatALinkShowsIsFrozenWhenItIsMade() throws Exception {
		String token = token(create(SharingFixture.BOB, Privacy.MEMBERS, Rights.VIEW));
		assertEquals(names("public.jpg", "members.jpg"), namesOf(token));

		// bob may see less afterwards; the link he handed out is what it was.
		setClearance("bob", Clearances.PUBLIC);
		assertEquals("A later demotion neither widens nor narrows an old link.",
			names("public.jpg", "members.jpg"), namesOf(token));

		// And more afterwards changes nothing either.
		setClearance("bob", Clearances.ALL);
		assertEquals(names("public.jpg", "members.jpg"), namesOf(token));
	}

	// --- What a link caller may do. ---

	public void testTheLinksRootIsTheSharedFolder() throws Exception {
		String token = token(create(SharingFixture.ALICE, 0, Rights.VIEW));

		assertEquals("Zoo", album(get("/", "json", token)).getTitle());
		assertEquals("Nothing above the shared folder exists for this caller.",
			HttpServletResponse.SC_NOT_FOUND, get("/../", "json", token).status());
		assertEquals(AuthService.SHARE_CONFINED, errorMessage(get("/../", "json", token)));
		assertEquals(HttpServletResponse.SC_NOT_FOUND,
			get("/" + SharingFixture.PUBLIC + "/", "json", token).status());
	}

	public void testALinkReadsEvenWhereNobodyElseMay() throws Exception {
		String token = token(create(SharingFixture.ALICE, 0, Rights.VIEW));
		_authMode = AuthMode.ALL;
		restartServer();

		assertEquals("An anonymous caller is refused here.", HttpServletResponse.SC_UNAUTHORIZED,
			get("/", "json", null).status());
		assertEquals("The link was handed out on purpose and still opens.", HttpServletResponse.SC_OK,
			get("/", "json", token).status());
		assertEquals(HttpServletResponse.SC_OK, get("/public.jpg", "tn", token).status());
	}

	public void testALinkContributesOnlyWhenItMay() throws Exception {
		String looking = token(create(SharingFixture.ALICE, 0, Rights.VIEW));
		assertEquals(HttpServletResponse.SC_FORBIDDEN, upload("/", looking, "nope.jpg").status());

		String contributing =
			token(create(SharingFixture.ALICE, 0, Rights.VIEW, Rights.CONTRIBUTE));
		// A real photo, because the album lists what the server could analyse.
		assertEquals(HttpServletResponse.SC_OK,
			upload("/", contributing, "guests.jpg", photo("guests")).status());

		ImagePart image = null;
		for (de.haumacher.imageServer.shared.model.AlbumPart part
				: album(get(ZOO, "json", SharingFixture.ALICE)).getParts()) {
			if (part instanceof ImagePart && ((ImagePart) part).getName().equals("guests.jpg")) {
				image = (ImagePart) part;
			}
		}
		assertNotNull("The contribution must be in the album.", image);
		assertEquals("A contribution through a link is named by the link.",
			"token:" + idOf(contributing), image.getContributor());
	}

	// --- Expiry, withdrawal and removal. ---

	public void testAnExpiredLinkIsGoneOnEveryEndpoint() throws Exception {
		String token = issue("alice", SharingFixture.ZOO, "Yesterday", "2020-01-01T00:00:00Z",
			Privacy.PUBLIC, 0, Rights.VIEW);
		assertGoneEverywhere(token, AuthService.LINK_EXPIRED);
	}

	public void testAWithdrawnLinkIsGoneOnEveryEndpoint() throws Exception {
		String token = token(create(SharingFixture.ALICE, 0, Rights.VIEW));
		assertEquals(HttpServletResponse.SC_OK, get("/", "json", token).status());

		assertEquals(HttpServletResponse.SC_OK, unshare(ZOO, SharingFixture.ALICE, idOf(token)).status());
		restartServer();

		assertGoneEverywhere(token, AuthService.LINK_REVOKED);
	}

	public void testRemovingAUserWithdrawsTheirLinks() throws Exception {
		String bobs = token(create(SharingFixture.BOB, 0, Rights.VIEW));
		String alices = token(create(SharingFixture.ALICE, 0, Rights.VIEW));
		assertEquals(HttpServletResponse.SC_OK, get("/", "json", bobs).status());

		FakeResponse response = removeUser(SharingFixture.ALICE, "bob");
		assertEquals(body(response), HttpServletResponse.SC_OK, response.status());
		assertEquals("The answer says how many doors were closed.", 1,
			UserList.readUserList(reader(body(response))).getRevokedLinks());

		restartServer();
		assertEquals("What bob handed out goes with bob.", HttpServletResponse.SC_GONE,
			get("/", "json", bobs).status());
		assertEquals(AuthService.LINK_REVOKED, errorMessage(get("/", "json", bobs)));
		assertEquals("Nobody else's link is touched.", HttpServletResponse.SC_OK,
			get("/", "json", alices).status());
	}

	// --- Managing the links of a folder. ---

	public void testAUserSeesTheirOwnLinksAndAnAdminSeesThemAll() throws Exception {
		create(SharingFixture.BOB, 0, Rights.VIEW);
		create(SharingFixture.ALICE, 0, Rights.VIEW);

		assertEquals("bob sees the one he handed out.", 1, listed(SharingFixture.BOB).size());
		assertEquals("bob", listed(SharingFixture.BOB).get(0).getCreatedBy());
		assertEquals("The administrator keeps the space in order and sees them all.", 2,
			listed(SharingFixture.ALICE).size());
	}

	public void testOnlyTheMakerOrAnAdminWithdrawsALink() throws Exception {
		String bobs = idOf(token(create(SharingFixture.BOB, 0, Rights.VIEW)));

		// Another user with the flag is told there is no such link, not whose it is.
		setPermissionOf("dave", Roles.VIEW, Clearances.NON_PRIVATE, true);
		FakeResponse refused = unshare(ZOO, SharingFixture.DAVE, bobs);
		assertEquals(HttpServletResponse.SC_NOT_FOUND, refused.status());
		assertEquals(AuthService.SHARE_UNKNOWN, errorMessage(refused));

		assertEquals("Its maker withdraws it.", HttpServletResponse.SC_OK,
			unshare(ZOO, SharingFixture.BOB, bobs).status());

		String alices = idOf(token(create(SharingFixture.BOB, 0, Rights.VIEW)));
		assertEquals("So does an administrator.", HttpServletResponse.SC_OK,
			unshare(ZOO, SharingFixture.ALICE, alices).status());
	}

	// --- One space's link is nothing in another. ---

	public void testALinkNeverReachesAnotherSpace() throws Exception {
		String token = token(create(SharingFixture.ALICE, 0, Rights.VIEW));

		java.nio.file.Path other = java.nio.file.Files.createTempDirectory("valbum-other-space");
		try {
			SharingFixture.album(other, "Elsewhere", "Elsewhere",
				"[\"ImagePart\",{\"name\":\"theirs.jpg\",\"width\":4,\"height\":3}]", "theirs.jpg");
			ImageServlet elsewhere = new ImageServlet(other.toFile(),
				new AuthService(AuthMode.WRITES, other), "other");
			try {
				elsewhere.init(TestSpaces.config());
				FakeResponse response = new FakeResponse();
				Map<String, String> parameters = new HashMap<>();
				parameters.put("type", "json");
				Map<String, String> headers = new HashMap<>();
				headers.put("Authorization", "Bearer " + token);
				elsewhere.doGet(TestSpaces.request("GET", "/Elsewhere/", parameters, headers, new byte[0], null),
					response.response());

				// The other space's store knows nothing of this token, and a bearer this server
				// does not know is refused rather than waved through as anonymous.
				assertEquals("A token of another space opens nothing here.",
					HttpServletResponse.SC_UNAUTHORIZED, response.status());

				FakeResponse asked = auth(elsewhere, token);
				assertEquals(HttpServletResponse.SC_OK, asked.status());
				AuthInfo info = AuthInfo.readAuthInfo(reader(body(asked)));
				assertNull("A link of another space is no link here.", info.getShare());
				assertEquals("", info.getUserName());
			} finally {
				elsewhere.destroy();
			}
		} finally {
			try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(other)) {
				files.sorted(java.util.Comparator.reverseOrder()).map(java.nio.file.Path::toFile)
					.forEach(java.io.File::delete);
			}
		}
	}

	// --- Helpers. ---

	private FakeResponse auth(ImageServlet servlet, String token) throws Exception {
		FakeResponse response = new FakeResponse();
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "auth");
		Map<String, String> headers = new HashMap<>();
		headers.put("Authorization", "Bearer " + token);
		servlet.doGet(TestSpaces.request("GET", "/", parameters, headers, new byte[0], null),
			response.response());
		return response;
	}

	/** Every endpoint a dead link may be asked, thumbnails and renditions included. */
	private void assertGoneEverywhere(String token, String message) throws Exception {
		assertEquals(HttpServletResponse.SC_GONE, get("/", "json", token).status());
		assertEquals(message, errorMessage(get("/", "json", token)));
		assertEquals(HttpServletResponse.SC_GONE, get("/", "auth", token).status());
		assertEquals(HttpServletResponse.SC_GONE, get("/public.jpg", "tn", token).status());
		assertEquals(HttpServletResponse.SC_GONE, get("/public.jpg", null, token).status());
		assertEquals("A rendition is refused like everything else.", HttpServletResponse.SC_GONE,
			get("/public.jpg", "video", token).status());
		assertEquals(HttpServletResponse.SC_GONE, get("/public.jpg", "teaser", token).status());
		assertEquals(HttpServletResponse.SC_GONE, upload("/", token, "late.jpg").status());
	}

	/** A tiny JPEG with contents of its own, so that no upload finds a duplicate. */
	private static byte[] photo(String seed) throws Exception {
		java.util.Random random = new java.util.Random(("share/" + seed).hashCode());
		java.awt.image.BufferedImage image =
			new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_3BYTE_BGR);
		for (int x = 0; x < 16; x++) {
			for (int y = 0; y < 16; y++) {
				image.setRGB(x, y, random.nextInt(0xFFFFFF));
			}
		}
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		javax.imageio.ImageIO.write(image, "jpg", out);
		return out.toByteArray();
	}

	private FakeResponse create(String token, int maxPrivacy, String... rights) throws Exception {
		return share(ZOO, token, shareBody("Grandma", "", maxPrivacy, 0, rights));
	}

	private static String token(FakeResponse response) throws Exception {
		assertEquals(body(response), HttpServletResponse.SC_OK, response.status());
		return ShareLinkCreated.readShareLinkCreated(reader(body(response))).getToken();
	}

	private List<ShareLink> listed(String token) throws Exception {
		FakeResponse response = shares(ZOO, token);
		assertEquals(body(response), HttpServletResponse.SC_OK, response.status());
		return ShareLinkList.readShareLinkList(reader(body(response))).getLinks();
	}

	private List<String> namesOf(String token) throws Exception {
		List<String> result = new ArrayList<>();
		album(get("/", "json", token)).getParts().forEach(part -> {
			if (part instanceof ImagePart) {
				result.add(((ImagePart) part).getName());
			}
		});
		return result;
	}

	private static List<String> names(String... names) {
		return java.util.Arrays.asList(names);
	}

	private void setClearance(String user, String clearance) throws Exception {
		UserStore users = new UserStore(_base);
		users.getUser(user).setClearance(clearance);
		users.store();
		restartServer();
	}

	private void setPermissionOf(String user, String role, String clearance, boolean share) throws Exception {
		UserStore users = new UserStore(_base);
		users.getUser(user).setRole(role);
		users.getUser(user).setClearance(clearance);
		users.getUser(user).setShare(share);
		users.store();
		restartServer();
	}

	private FakeResponse removeUser(String token, String name) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "remove-user");
		return post("/", "{\"name\":\"" + name + "\"}", token, parameters);
	}

}
