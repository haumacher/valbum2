/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.auth.Subjects;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.CreateResult;
import de.haumacher.imageServer.shared.model.ListingInfo;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Test case for a request path resolving through a link entry, see issue #50.
 *
 * <p>
 * The one rule everything here checks: a link is a path segment that resolves elsewhere, and what
 * the caller may do there is decided by the grant on the <em>target</em>, never by the link.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestLinks extends LinkTestCase {

	private static final String ALBUM_JSON = "[\"AlbumInfo\",{\"title\":\"Changed\",\"parts\":[]}]";

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		// Bob keeps alice's zoo album in his own tree under a name of his own.
		link("bob", "Zoo", "alice", SharingFixture.ZOO);
	}

	public void testBobOpensAlicesAlbumThroughHisOwnPath() throws Exception {
		FakeResponse response = get("/Zoo/", "json", SharingFixture.BOB);

		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		AlbumInfo album = album(response);
		assertEquals("Alice's album, reached through bob's own tree.", "Zoo", album.getTitle());
		assertEquals("The rights are the ones on the target: bob's own grant on the year folder and the "
			+ "contribute of the group 'family' on this very album.",
			Arrays.asList(Rights.VIEW, Rights.DOWNLOAD, Rights.CONTRIBUTE), rightsOf(album));
		assertEquals("A grant makes a member of the album, not its owner.",
			Arrays.asList("public.jpg", "members.jpg"), names(album));
	}

	public void testTheImagesOfALinkedAlbumAreServedAndRefusedAsOnTheOwnersPath() throws Exception {
		assertEquals("Looking through a link is the view right on the target.", HttpServletResponse.SC_OK,
			get("/Zoo/public.jpg", "tn", SharingFixture.BOB).status());
		assertEquals("Taking a copy is the download right on the target.", HttpServletResponse.SC_OK,
			get("/Zoo/public.jpg", null, SharingFixture.BOB).status());

		FakeResponse refused = get("/Zoo/private.jpg", "tn", SharingFixture.BOB);
		assertEquals("A private image stays private through a link.", HttpServletResponse.SC_FORBIDDEN,
			refused.status());
		assertEquals(ImageServlet.IMAGE_REFUSED, errorMessage(refused));
	}

	public void testAStrangerReachesNothingThroughSomebodyElsesLink() throws Exception {
		// Dave asks for the same path in his own space: there is no link of his there.
		assertEquals(HttpServletResponse.SC_NOT_FOUND, get("/Zoo/", "json", SharingFixture.DAVE).status());
	}

	public void testBobMayNotChangeWhatHeOnlyLinks() throws Exception {
		FakeResponse response = put("/Zoo/", ALBUM_JSON, SharingFixture.BOB);

		assertEquals("The edit right is asked for on the target, which bob does not hold.",
			HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(AuthService.EDIT_REFUSED, errorMessage(response));
		assertFalse("Nothing was written into alice's album.",
			read(_base.resolve("alice/" + SharingFixture.ZOO + "/index.json")).contains("Changed"));
	}

	public void testCarolContributesThroughHerOwnLink() throws Exception {
		link("carol", "Shared zoo", "alice", SharingFixture.ZOO);

		FakeResponse response = upload("/Shared zoo/", SharingFixture.CAROL, "carols-upload.jpg");

		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		assertTrue("The photo landed in alice's folder, where the album really is.",
			Files.exists(_base.resolve("alice/" + SharingFixture.ZOO + "/carols-upload.jpg")));
		assertFalse("Nothing was created in carol's space.",
			Files.exists(_base.resolve("carol/Shared zoo")));
	}

	public void testARevokedGrantClosesTheLinkAndHidesTheTile() throws Exception {
		// The year folder is bob's by his own grant alone; the zoo album inside it would stay open
		// to him through the group 'family'.
		link("bob", "Alices year", "alice", SharingFixture.YEAR);
		assertEquals(HttpServletResponse.SC_OK, get("/Alices year/", "json", SharingFixture.BOB).status());

		assertEquals(HttpServletResponse.SC_OK,
			grant("/" + SharingFixture.YEAR + "/", SharingFixture.ALICE, "revoke", Subjects.user("bob"), null)
				.status());

		FakeResponse refused = get("/Alices year/", "json", SharingFixture.BOB);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, refused.status());
		assertEquals(AuthService.VIEW_REFUSED, errorMessage(refused));

		ListingInfo root = listing(get("/", "json", SharingFixture.BOB));
		assertNull("The tile of a link nobody may view is omitted.", entry(root, "Alices year"));
		assertNotNull("The record stays, so re-granting shows it again.", links("bob").get("Alices year"));
	}

	public void testAnAnswerThroughALinkIsSpelledInTheViewersOwnPath() throws Exception {
		// Bob may edit alice's year folder and keeps it in his own tree; what he creates there is
		// answered with his path to it, never with alice's.
		link("bob", "Alices year", "alice", SharingFixture.YEAR);
		grant("/" + SharingFixture.YEAR + "/", SharingFixture.ALICE, "grant", Subjects.user("bob"), Rights.EDIT);

		FakeResponse response = put("/Alices year/2024-08-01 Trip/", "[\"AlbumInfo\",{\"title\":\"Trip\"}]",
			SharingFixture.BOB);

		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		CreateResult created = CreateResult.readCreateResult(reader(body(response)));
		assertEquals("The path is spelled the way the request spells paths.", "Alices year/2024-08-01 Trip",
			created.getPath());
		assertTrue("And the album really lies in alice's folder.",
			Files.isDirectory(_base.resolve("alice/" + SharingFixture.YEAR + "/2024-08-01 Trip")));
		assertEquals("Following the answered path leads to it.", HttpServletResponse.SC_OK,
			get("/" + created.getPath() + "/", "json", SharingFixture.BOB).status());
	}

	public void testALinkWhoseTargetIsGoneIsRemovedWhenTheListingFindsIt() throws Exception {
		delete(_base.resolve("alice/" + SharingFixture.ZOO));

		ListingInfo root = listing(get("/", "json", SharingFixture.BOB));

		assertNull(entry(root, "Zoo"));
		assertNull("The dangling record was dropped from the sidecar.", links("bob").get("Zoo"));
		assertEquals(HttpServletResponse.SC_NOT_FOUND, get("/Zoo/", "json", SharingFixture.BOB).status());
	}

	public void testALinkToALinkResolvesTwoHops() throws Exception {
		// Carol shares her inbox with alice, alice keeps it in her own root, and bob holds a link
		// to alice's whole library.
		grant("/" + SharingFixture.CAROLS_ALBUM + "/", SharingFixture.CAROL, "grant", Subjects.user("alice"),
			Rights.VIEW);
		link("alice", "Carols inbox", "carol", SharingFixture.CAROLS_ALBUM);
		grant("/", SharingFixture.ALICE, "grant", Subjects.user("bob"), Rights.VIEW);
		link("bob", "Alice", "alice", "");

		FakeResponse response = get("/Alice/Carols inbox/", "json", SharingFixture.BOB);

		assertEquals("Two hops: bob's link into alice's space, hers into carol's album.",
			HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals("Bob holds nothing in carol's space: the second hop is checked on carol's grant.",
			AuthService.VIEW_REFUSED, errorMessage(response));

		grant("/" + SharingFixture.CAROLS_ALBUM + "/", SharingFixture.CAROL, "grant", Subjects.user("bob"),
			Rights.VIEW);
		assertEquals("Carol's album, reached through two links.", "Inbox",
			album(get("/Alice/Carols inbox/", "json", SharingFixture.BOB)).getTitle());
	}

	public void testACircleOfLinksIsRefusedWithAMessage() throws Exception {
		// Bob links alice's library, alice links bob's, and both may look: following the two forever
		// is what the depth limit is for.
		grant("/", SharingFixture.ALICE, "grant", Subjects.user("bob"), Rights.VIEW);
		grant("/", SharingFixture.BOB, "grant", Subjects.user("alice"), Rights.VIEW);
		link("bob", "Alice", "alice", "");
		link("alice", "Bob", "bob", "");

		FakeResponse response = get("/Alice/Bob/Alice/Bob/Alice/Bob/Alice/Bob/Alice/", "json", SharingFixture.BOB);

		assertEquals(HttpServletResponse.SC_NOT_FOUND, response.status());
		assertEquals(AuthService.LINK_LOOP, errorMessage(response));
	}

	public void testALinkWhoseOwnerIsGoneSaysSo() throws Exception {
		link("bob", "Nobody", "mallory", "2024");

		FakeResponse response = get("/Nobody/", "json", SharingFixture.BOB);

		assertEquals(HttpServletResponse.SC_NOT_FOUND, response.status());
		assertEquals(AuthService.unknownSpace("mallory"), errorMessage(response));
	}

	public void testALinkPointingOutOfTheLibraryIsRefused() throws Exception {
		link("bob", "Escape", "alice", "../../etc");

		FakeResponse response = get("/Escape/", "json", SharingFixture.BOB);

		assertEquals(HttpServletResponse.SC_NOT_FOUND, response.status());
		assertEquals(AuthService.LINK_ESCAPED, errorMessage(response));
	}

	public void testARealFolderOfOnesOwnWinsTheNameBack() throws Exception {
		SharingFixture.album(_base, "bob/Zoo", "Bob's own zoo",
			"[\"ImagePart\",{\"name\":\"bobs.jpg\",\"width\":4,\"height\":3}]", "bobs.jpg");

		assertEquals("The disk is asked first; the link is shadowed.", "Bob's own zoo",
			album(get("/Zoo/", "json", SharingFixture.BOB)).getTitle());
	}

	private static java.util.List<String> names(AlbumInfo album) {
		java.util.List<String> result = new java.util.ArrayList<>();
		for (de.haumacher.imageServer.shared.model.AlbumPart part : album.getParts()) {
			if (part instanceof de.haumacher.imageServer.shared.model.ImagePart) {
				result.add(((de.haumacher.imageServer.shared.model.ImagePart) part).getName());
			}
		}
		return result;
	}

	private static String read(Path file) throws Exception {
		return new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
	}

	private static void delete(Path root) throws Exception {
		try (Stream<Path> files = Files.walk(root)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
		}
	}
}
