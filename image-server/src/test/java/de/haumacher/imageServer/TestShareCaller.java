/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ListingInfo;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a caller holding a share link may see and do, see issue #51.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestShareCaller extends ShareTestCase {

	public void testTheLinksTargetIsTheCallersRoot() throws Exception {
		String token = zooToken(Rights.VIEW, Rights.DOWNLOAD);

		AlbumInfo album = album(get("/", "json", token));
		assertEquals("Zoo", album.getTitle());
		assertEquals(Arrays.asList(Rights.VIEW, Rights.DOWNLOAD), rightsOf(album));
		// The link's limits are applied on the way out: public photos rated 0 or better.
		assertEquals(Arrays.asList("public.jpg"), imageNames(album));
	}

	public void testViewingAndDownloading() throws Exception {
		String token = zooToken(Rights.VIEW, Rights.DOWNLOAD);

		assertEquals(HttpServletResponse.SC_OK, get("/public.jpg", "tn", token).status());
		assertEquals(HttpServletResponse.SC_OK, get("/public.jpg", null, token).status());
		assertEquals(HttpServletResponse.SC_OK, get("/public.jpg", "json", token).status());
	}

	public void testAViewOnlyLinkShowsButDoesNotHandOut() throws Exception {
		String token = zooToken(Rights.VIEW);

		assertEquals(HttpServletResponse.SC_OK, get("/public.jpg", "tn", token).status());
		FakeResponse original = get("/public.jpg", null, token);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, original.status());
		assertEquals(AuthService.DOWNLOAD_REFUSED, errorMessage(original));
	}

	public void testAPhotoAboveTheLinksPrivacyLimitIsHidden() throws Exception {
		String token = zooToken(Rights.VIEW, Rights.DOWNLOAD);

		assertFalse(imageNames(album(get("/", "json", token))).contains("members.jpg"));
		FakeResponse thumbnail = get("/members.jpg", "tn", token);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, thumbnail.status());
		assertEquals(ImageServlet.IMAGE_REFUSED, errorMessage(thumbnail));
		assertEquals(HttpServletResponse.SC_FORBIDDEN, get("/members.jpg", null, token).status());
	}

	public void testAPhotoBelowTheLinksRatingLimitIsHidden() throws Exception {
		String token = zooToken(Rights.VIEW, Rights.DOWNLOAD);

		assertFalse(imageNames(album(get("/", "json", token))).contains(REJECTED));
		FakeResponse thumbnail = get("/" + REJECTED, "tn", token);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, thumbnail.status());
		assertEquals(ImageServlet.RATING_REFUSED, errorMessage(thumbnail));
		assertEquals(HttpServletResponse.SC_FORBIDDEN, get("/" + REJECTED, null, token).status());
	}

	public void testALinkWithoutARatingLimitShowsTheRejectedPhoto() throws Exception {
		String token = issue("alice", SharingFixture.ZOO, "Everything", "", Privacy.MEMBERS, NO_RATING_LIMIT,
			Rights.VIEW);

		List<String> names = imageNames(album(get("/", "json", token)));
		assertTrue(names.toString(), names.contains(REJECTED));
		assertTrue(names.toString(), names.contains("members.jpg"));
		assertFalse("The owner's private photos are never a link's business.",
			names.contains("private.jpg"));
		assertEquals(HttpServletResponse.SC_OK, get("/" + REJECTED, "tn", token).status());
	}

	public void testViewAsOnlyEverLowers() throws Exception {
		String token = issue("alice", SharingFixture.ZOO, "Members", "", Privacy.MEMBERS, NO_RATING_LIMIT,
			Rights.VIEW);

		assertTrue(imageNames(album(get("/", "json", token))).contains("members.jpg"));
		assertFalse("'viewAs=public' composes with the link's own limit.",
			imageNames(album(get("/", "json", token, Privacy.VIEW_AS_PUBLIC))).contains("members.jpg"));
		assertFalse("'viewAs=members' cannot lift a link above its limit.",
			imageNames(album(get("/", "json", zooToken(Rights.VIEW), Privacy.VIEW_AS_MEMBERS)))
				.contains("members.jpg"));
	}

	public void testThereIsNothingOutsideTheLink() throws Exception {
		String token = zooToken(Rights.VIEW, Rights.DOWNLOAD);

		FakeResponse up = get("/../" + SharingFixture.PRIVATE + "/", "json", token);
		assertEquals(HttpServletResponse.SC_NOT_FOUND, up.status());
		assertEquals(AuthService.SHARE_CONFINED, errorMessage(up));

		FakeResponse canonical = get("/~alice/" + SharingFixture.PRIVATE + "/", "json", token);
		assertEquals(HttpServletResponse.SC_NOT_FOUND, canonical.status());
		assertEquals(AuthService.SHARE_CONFINED, errorMessage(canonical));

		FakeResponse ownSpace = get("/~bob/", "json", token);
		assertEquals(HttpServletResponse.SC_NOT_FOUND, ownSpace.status());
		assertEquals(AuthService.SHARE_CONFINED, errorMessage(ownSpace));
	}

	public void testALinkOnAFolderBrowsesDownwardsOnly() throws Exception {
		String token = issue("alice", SharingFixture.YEAR, "The year", "", Privacy.PUBLIC, 0, Rights.VIEW);

		ListingInfo root = listing(get("/", "json", token));
		assertEquals(Arrays.asList("2024-05-01 Zoo"), entryNames(root));

		AlbumInfo album = album(get("/2024-05-01 Zoo/", "json", token));
		assertEquals("Zoo", album.getTitle());
		assertEquals(Arrays.asList("public.jpg"), imageNames(album));

		assertEquals(HttpServletResponse.SC_NOT_FOUND,
			get("/../" + SharingFixture.PUBLIC + "/", "json", token).status());
	}

	/**
	 * A link entry of issue #50 inside the shared folder that points out of it: a 404 for the link
	 * caller, and the album it points at for bob, who reaches it through his own grant.
	 */
	public void testALinkEntryLeadingOutOfTheSubtreeIsNotThere() throws Exception {
		link("alice/" + SharingFixture.YEAR, "Elsewhere", "alice", SharingFixture.PUBLIC);
		String token = issue("alice", SharingFixture.YEAR, "The year", "", Privacy.PUBLIC, 0, Rights.VIEW);

		FakeResponse escaped = get("/Elsewhere/", "json", token);
		assertEquals(HttpServletResponse.SC_NOT_FOUND, escaped.status());
		assertEquals(AuthService.SHARE_CONFINED, errorMessage(escaped));
		assertFalse("A tile leading nowhere is not shown either.",
			entryNames(listing(get("/", "json", token))).contains("Elsewhere"));

		// bob holds view and download on the year folder and reaches the very same entry.
		new de.haumacher.imageServer.auth.GrantStore(_base).grant("alice", SharingFixture.PUBLIC,
			de.haumacher.imageServer.auth.Subjects.user("bob"), Collections.singletonList(Rights.VIEW));
		restartServer();
		assertEquals("Public",
			album(get("/~alice/" + SharingFixture.YEAR + "/Elsewhere/", "json", SharingFixture.BOB)).getTitle());
	}

	public void testALinkEntryInsideTheSubtreeIsFollowed() throws Exception {
		link("alice/" + SharingFixture.YEAR, "Also the zoo", "alice", SharingFixture.ZOO);
		String token = issue("alice", SharingFixture.YEAR, "The year", "", Privacy.PUBLIC, 0, Rights.VIEW);

		assertEquals("Zoo", album(get("/Also the zoo/", "json", token)).getTitle());
	}

	public void testALinkNeverManagesAnything() throws Exception {
		String token = zooToken(Rights.VIEW, Rights.DOWNLOAD);

		FakeResponse grants = get("/", "grants", token);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, grants.status());
		assertRefusalSpoken(grants);

		FakeResponse listed = shares("/", token);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, listed.status());

		FakeResponse sidecar = put("/", "[\"AlbumInfo\",{\"title\":\"Mine\",\"parts\":[]}]", token);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, sidecar.status());
		assertRefusalSpoken(sidecar);

		FakeResponse moved = move("/", "/", token, "public.jpg");
		assertEquals(HttpServletResponse.SC_FORBIDDEN, moved.status());
		assertRefusalSpoken(moved);

		FakeResponse placed = place("/", token);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, placed.status());

		FakeResponse granting = grant("/", token, "grant", "user:bob", Rights.VIEW);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, granting.status());

		FakeResponse sharing = share("/", token, shareBody("Onwards", "", 0, 0, Rights.VIEW));
		assertEquals(HttpServletResponse.SC_FORBIDDEN, sharing.status());

		Map<String, String> pairing = new HashMap<>();
		pairing.put("action", "pair");
		FakeResponse paired = post("/", "{\"secret\":\"a link is not a secret\"}", token, pairing);
		assertEquals("A link buys nothing at the pairing endpoint; the secret does.",
			HttpServletResponse.SC_FORBIDDEN, paired.status());
		assertEquals(AuthService.SECRET_REFUSED, errorMessage(paired));
	}

	public void testAViewOnlyLinkMayNotUpload() throws Exception {
		String token = zooToken(Rights.VIEW, Rights.DOWNLOAD);

		FakeResponse response = upload("/", token, "guest.jpg");
		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(AuthService.SHARE_WRITE_REFUSED, errorMessage(response));
		assertFalse(Files.exists(_base.resolve("alice/" + SharingFixture.ZOO).resolve("guest.jpg")));
	}

	public void testAContributeLinkUploadsIntoTheOwnersAlbum() throws Exception {
		String token = zooToken(Rights.VIEW, Rights.CONTRIBUTE);

		FakeResponse response = upload("/", token, "guest.jpg");
		assertEquals(HttpServletResponse.SC_OK, response.status());
		assertTrue("The photo lands in alice's album.",
			Files.exists(_base.resolve("alice/" + SharingFixture.ZOO).resolve("guest.jpg")));

		Map<String, String> check = new HashMap<>();
		check.put("action", "check");
		assertEquals(HttpServletResponse.SC_OK,
			post("/", "{\"hashes\":[]}", token, check).status());
	}

	/**
	 * The message of {@link AuthService#LIBRARY_REFUSED} tells people to open a share link; opening
	 * one is what this caller did, so it reads while a caller without a token still may not.
	 */
	public void testALinkReadsInAMigratedLibrary() throws Exception {
		String token = zooToken(Rights.VIEW, Rights.DOWNLOAD);
		// The fixture library is migrated: every user has a space folder of their own.
		assertTrue(new AuthService(de.haumacher.imageServer.auth.AuthMode.WRITES, SharingFixture.SECRET, _base)
			.isLibraryMigrated());

		assertEquals("Zoo", album(get("/", "json", token)).getTitle());

		FakeResponse anonymous = get("/~alice/" + SharingFixture.PRIVATE + "/", "json", null);
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, anonymous.status());
		assertEquals(AuthService.LIBRARY_REFUSED, errorMessage(anonymous));
	}

	public void testBrowsingWritesNothingIntoTheOwnersLibrary() throws Exception {
		String token = zooToken(Rights.VIEW, Rights.DOWNLOAD);
		String before = albumFingerprint("alice");

		get("/", "json", token);
		get("/public.jpg", "json", token);
		get("/public.jpg", "tn", token);
		get("/public.jpg", null, token);
		get("/members.jpg", "tn", token);
		get("/" + REJECTED, "tn", token);
		get("/../" + SharingFixture.PRIVATE + "/", "json", token);

		assertEquals("A link caller never changes a photo or a sidecar.", before, albumFingerprint("alice"));
	}

	/** The names of the images an album answer shows, groups included. */
	static List<String> imageNames(AlbumInfo album) {
		List<String> result = new ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				result.add(((ImagePart) part).getName());
			} else if (part instanceof de.haumacher.imageServer.shared.model.ImageGroup) {
				for (ImagePart image : ((de.haumacher.imageServer.shared.model.ImageGroup) part).getImages()) {
					result.add(image.getName());
				}
			}
		}
		return result;
	}

	/** A refusal must say something, and never ask a link holder to pair a device. */
	private static void assertRefusalSpoken(FakeResponse response) throws Exception {
		String message = errorMessage(response);
		assertFalse("A refusal must speak: " + response.body(), message.isEmpty());
		assertFalse("A link holder is never told to sign in: " + message,
			message.equals(AuthService.LIBRARY_REFUSED));
	}
}
