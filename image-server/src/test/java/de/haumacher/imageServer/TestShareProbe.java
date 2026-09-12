/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.auth.Subjects;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Review probe for the share links of issue #51, composing them with what the server already had: the
 * idempotent upload of issue #29 through a contribute link, the album creation of issue #48 that a
 * link must not have, the grant API of issue #49 acting on a link's own grant, two links on one album
 * with different limits, and an expired contribute link that must land nothing.
 */
@SuppressWarnings("javadoc")
public class TestShareProbe extends ShareTestCase {

	public void testAContributeLinkUploadsAPhotoOnceHoweverOftenItIsSent() throws Exception {
		String token = zooToken(Rights.CONTRIBUTE);
		File zoo = _base.resolve("alice/" + SharingFixture.ZOO).toFile();
		int before = imageCount(zoo);

		assertEquals(HttpServletResponse.SC_OK, upload("/", token, "party.jpg").status());
		FakeResponse again = upload("/", token, "party.jpg");
		assertEquals(again.body(), HttpServletResponse.SC_OK, again.status());

		assertEquals("The same pixels are stored once.", before + 1, imageCount(zoo));
		assertTrue(new File(zoo, "party.jpg").exists());
	}

	public void testAContributeLinkMayNeitherCreateAnAlbumNorApplyARule() throws Exception {
		String token = zooToken(Rights.CONTRIBUTE);
		String owners = albumFingerprint("alice");

		FakeResponse created = put("/Party/", "[\"AlbumInfo\",{\"title\":\"Party\",\"parts\":[]}]", token);
		assertEquals(created.body(), HttpServletResponse.SC_FORBIDDEN, created.status());
		assertFalse(errorMessage(created).isEmpty());

		FakeResponse placed = place("/", token);
		assertTrue(placed.body(), placed.status() >= 400);
		assertFalse(errorMessage(placed).isEmpty());

		assertEquals("Nothing of alice's changed.", owners, albumFingerprint("alice"));
	}

	public void testTheGrantOfALinkIsWithdrawnWithTheLinkNotThroughTheGrantApi() throws Exception {
		String token = zooToken(Rights.VIEW);
		assertEquals(HttpServletResponse.SC_OK, get("/", "json", token).status());

		FakeResponse revoked = grant("/" + SharingFixture.ZOO + "/", SharingFixture.ALICE, "revoke",
			Subjects.token(idOf(token)), null);
		assertEquals(revoked.body(), HttpServletResponse.SC_BAD_REQUEST, revoked.status());
		assertEquals("The refusal points at the one route that keeps the two stores in step.",
			ImageServlet.SHARE_GRANT_REFUSED, errorMessage(revoked));

		FakeResponse granted = grant("/" + SharingFixture.ZOO + "/", SharingFixture.ALICE, "grant",
			Subjects.token(idOf(token)), Rights.EDIT);
		assertEquals(granted.body(), HttpServletResponse.SC_BAD_REQUEST, granted.status());

		assertEquals("The link works as before; nothing was half-changed.", HttpServletResponse.SC_OK,
			get("/", "json", token).status());
		assertEquals(Arrays.asList(Rights.VIEW), rightsOf(folder(get("/", "json", token))));
	}

	public void testTwoLinksOnOneAlbumKeepTheirOwnLimits() throws Exception {
		ShareLinkCreated family = created(share("/" + SharingFixture.ZOO + "/", SharingFixture.ALICE,
			shareBody("Family", "", Privacy.MEMBERS, -2, Rights.VIEW)));
		ShareLinkCreated neighbours = created(share("/" + SharingFixture.ZOO + "/", SharingFixture.ALICE,
			shareBody("Neighbours", "", Privacy.PUBLIC, 0, Rights.VIEW)));

		assertEquals(Arrays.asList("public.jpg", "members.jpg", REJECTED),
			names(album(get("/", "json", family.getToken()))));
		assertEquals(Arrays.asList("public.jpg"), names(album(get("/", "json", neighbours.getToken()))));

		assertEquals(HttpServletResponse.SC_OK,
			unshare("/" + SharingFixture.ZOO + "/", SharingFixture.ALICE, neighbours.getLink().getId()).status());

		assertEquals(HttpServletResponse.SC_GONE, get("/", "json", neighbours.getToken()).status());
		assertEquals("The other link is untouched.", HttpServletResponse.SC_OK,
			get("/", "json", family.getToken()).status());
	}

	public void testAnExpiredContributeLinkLandsNothing() throws Exception {
		String token = issue("alice", SharingFixture.ZOO, "Late", "2000-01-01T00:00:00Z", Privacy.PUBLIC, -2,
			Rights.CONTRIBUTE);
		String owners = albumFingerprint("alice");

		FakeResponse response = upload("/", token, "late.jpg");

		assertEquals(response.body(), HttpServletResponse.SC_GONE, response.status());
		assertEquals(AuthService.LINK_EXPIRED, errorMessage(response));
		assertEquals(owners, albumFingerprint("alice"));
	}

	private static int imageCount(File folder) {
		int result = 0;
		for (String name : folder.list()) {
			if (name.endsWith(".jpg")) {
				result++;
			}
		}
		return result;
	}

	private static List<String> names(AlbumInfo album) {
		List<String> result = new ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				result.add(((ImagePart) part).getName());
			}
		}
		return result;
	}
}
