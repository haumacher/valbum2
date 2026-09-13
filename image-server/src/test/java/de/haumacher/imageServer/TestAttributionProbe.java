/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.GrantStore;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.Subjects;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.PairResponse;
import de.haumacher.imageServer.upload.HashCache;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Random;
import javax.imageio.ImageIO;

/**
 * Review probe of the server half of issue #53: attribution composed with the features around
 * it — the link path of #50 as the contributor's way in and out, a guest promoted after
 * contributing (#52), the privacy filter (#46) on an attributed photo, the duplicate rule of
 * #47 when a contribution is taken home, and a user who joined by invitation.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestAttributionProbe extends InviteTestCase {

	private static final String ALICES_ZOO = "/~alice/" + SharingFixture.ZOO + "/";

	private static final String ZOO_TILE = "2024-05-01 Zoo";

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		Files.createDirectories(_base.resolve("bob").resolve("Inbox"));
	}

	private void shareZoo(String user, String right) throws Exception {
		new GrantStore(_base).grant("alice", SharingFixture.ZOO, Subjects.user(user),
			Collections.singletonList(right));
		restartServer();
	}

	// --- Through the link. ---

	public void testAContributorWorksThroughTheirOwnLinkPath() throws Exception {
		shareZoo("bob", Rights.CONTRIBUTE);
		assertTrue("The link is materialised in bob's root.",
			entryNames(listing(get("/", "json", SharingFixture.BOB))).contains(ZOO_TILE));

		assertUploaded(upload("/" + ZOO_TILE + "/", SharingFixture.BOB, "bobs.jpg", photo("bobs")));

		ImagePart mine = image(album(get("/" + ZOO_TILE + "/", "json", SharingFixture.BOB)), "bobs.jpg");
		assertEquals("user:bob", mine.getContributor());
		assertEquals("bob", mine.getContributorLabel());

		FakeResponse response = move("/" + ZOO_TILE + "/", "Inbox", SharingFixture.BOB, "bobs.jpg");
		assertEquals(body(response), HttpServletResponse.SC_OK, response.status());
		assertEquals("", outcome(moveResult(response), "bobs.jpg").getMessage());
		assertTrue(Files.exists(_base.resolve("bob/Inbox/bobs.jpg")));
		assertEquals("user:bob", HashCache.recorded(_base.resolve("bob/Inbox").toFile()).get("bobs.jpg").getContributor());
	}

	// --- A guest who becomes a member. ---

	public void testAPromotedGuestTakesTheirEarlierContributionHome() throws Exception {
		shareZoo("eve", Rights.CONTRIBUTE);
		assertUploaded(upload(ALICES_ZOO, SharingFixture.EVE, "eves.jpg", photo("eves")));

		assertEquals(HttpServletResponse.SC_OK, promote(SharingFixture.ALICE, "eve").status());
		restartServer();
		assertEquals(HttpServletResponse.SC_OK, put("/Mine/", "[\"AlbumInfo\",{\"title\":\"Mine\"}]", SharingFixture.EVE).status());

		FakeResponse response = move(ALICES_ZOO, "Mine", SharingFixture.EVE, "eves.jpg");
		assertEquals(body(response), HttpServletResponse.SC_OK, response.status());
		assertEquals("", outcome(moveResult(response), "eves.jpg").getMessage());
		assertTrue(Files.exists(_base.resolve("eve/Mine/eves.jpg")));
		ImagePart home = image(album(get("/Mine/", "json", SharingFixture.EVE)), "eves.jpg");
		assertEquals("The subject is the same person before and after the promotion.", "user:eve",
			home.getContributor());
		assertEquals("eve", home.getContributorLabel());
	}

	// --- Attribution and privacy. ---

	public void testAPrivatePhotoIsNotResurrectedByItsAttribution() throws Exception {
		shareZoo("bob", Rights.CONTRIBUTE);
		assertUploaded(upload(ALICES_ZOO, SharingFixture.BOB, "bobs.jpg", photo("bobs")));

		String body = "[\"AlbumInfo\",{\"title\":\"Zoo\",\"parts\":[[\"ImagePart\",{\"name\":\"bobs.jpg\","
			+ "\"width\":16,\"height\":16,\"privacy\":2}]]}]";
		assertEquals(HttpServletResponse.SC_OK, put("/" + SharingFixture.ZOO + "/", body, SharingFixture.ALICE).status());

		AlbumInfo asBob = album(get(ALICES_ZOO, "json", SharingFixture.BOB));
		assertNull("A member's clearance stops below private, their own upload or not.", find(asBob, "bobs.jpg"));
		ImagePart asAlice = image(album(get("/" + SharingFixture.ZOO + "/", "json", SharingFixture.ALICE)), "bobs.jpg");
		assertEquals("user:bob", asAlice.getContributor());
		assertEquals(2, asAlice.getPrivacy());
	}

	// --- Attribution and duplicates. ---

	public void testTakingBackAPhotoOneAlreadyHasSetsTheCopyAsideAndSaysSo() throws Exception {
		shareZoo("bob", Rights.CONTRIBUTE);
		byte[] photo = photo("twice");
		assertUploaded(upload("/Inbox/", SharingFixture.BOB, "twice.jpg", photo));
		assertUploaded(upload(ALICES_ZOO, SharingFixture.BOB, "twice.jpg", photo));

		FakeResponse response = move(ALICES_ZOO, "Inbox", SharingFixture.BOB, "twice.jpg");

		assertEquals(body(response), HttpServletResponse.SC_OK, response.status());
		assertFalse("The outcome speaks about the duplicate.", outcome(moveResult(response), "twice.jpg").getMessage().isEmpty());
		assertFalse("The photo left the shared album.", Files.exists(_base.resolve("alice/" + SharingFixture.ZOO + "/twice.jpg")));
		assertTrue("The one copy in the inbox stays.", Files.exists(_base.resolve("bob/Inbox/twice.jpg")));
		assertTrue("The duplicate was set aside, never deleted.", Files.isDirectory(
			_base.resolve(UserStore.DIRECTORY_NAME).resolve(MoveService.DUPLICATES_FOLDER)));
		assertEquals("user:bob", HashCache.recorded(_base.resolve("bob/Inbox").toFile()).get("twice.jpg").getContributor());
	}

	// --- A member who joined by invitation. ---

	public void testAMemberWhoJoinedByInvitationIsNamedByTheirChosenName() throws Exception {
		String token = issue(Roles.MEMBER, "alice", Instant.now().plus(1, ChronoUnit.DAYS).toString());
		PairResponse fred = paired(accept(token, "fred"));
		new GrantStore(_base).grant("alice", SharingFixture.ZOO, Subjects.user("fred"),
			Collections.singletonList(Rights.CONTRIBUTE));
		restartServer();

		assertUploaded(upload(ALICES_ZOO, fred.getToken(), "freds.jpg", photo("freds")));

		ImagePart part = image(album(get("/" + SharingFixture.ZOO + "/", "json", SharingFixture.ALICE)), "freds.jpg");
		assertEquals("user:fred", part.getContributor());
		assertEquals("fred", part.getContributorLabel());
	}

	// --- Helpers. ---

	private static byte[] photo(String seed) throws Exception {
		Random random = new Random(("probe/" + seed).hashCode());
		BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_3BYTE_BGR);
		for (int x = 0; x < 16; x++) {
			for (int y = 0; y < 16; y++) {
				image.setRGB(x, y, random.nextInt(0xFFFFFF));
			}
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "jpg", out);
		return out.toByteArray();
	}

	private static void assertUploaded(FakeResponse response) {
		assertEquals("Expected a stored upload, got: " + response.body(), HttpServletResponse.SC_OK,
			response.status());
		assertTrue("Expected a stored upload, got: " + response.body(),
			response.body().contains("\"status\":\"" + ImageServlet.STORED + "\""));
	}

	private static ImagePart find(AlbumInfo album, String name) {
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart && ((ImagePart) part).getName().equals(name)) {
				return (ImagePart) part;
			}
		}
		return null;
	}

	private static ImagePart image(AlbumInfo album, String name) {
		ImagePart part = find(album, name);
		assertNotNull("No image '" + name + "' in " + album.getParts(), part);
		return part;
	}
}
