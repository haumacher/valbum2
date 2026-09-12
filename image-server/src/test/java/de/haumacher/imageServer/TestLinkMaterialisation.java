/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.auth.Subjects;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.links.LinkStore;
import de.haumacher.imageServer.links.ShareRegistry;
import de.haumacher.imageServer.shared.model.ListingInfo;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Test case for the lazy materialisation of link entries of issue #50.
 *
 * <p>
 * A grant becomes a link the first time the recipient lists their own space root, exactly once, and
 * a share the recipient declined never comes back.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestLinkMaterialisation extends LinkTestCase {

	public void testTheFirstRootListingLinksWhatWasGranted() throws Exception {
		ListingInfo root = listing(get("/", "json", SharingFixture.BOB));

		assertEquals("Bob's grant on alice's year folder, named after the folder it points at.",
			"~alice/" + SharingFixture.YEAR, entry(root, SharingFixture.YEAR).getLink());
		LinkStore.Link link = links("bob").get(SharingFixture.YEAR);
		assertEquals("alice", link.getOwner());
		assertEquals(SharingFixture.YEAR, link.getPath());
		assertEquals(ShareRegistry.LINKED, registry("bob").get("alice", SharingFixture.YEAR).getState());

		assertEquals("The link is a path: browsing into it reaches alice's folder.",
			HttpServletResponse.SC_OK, get("/" + SharingFixture.YEAR + "/", "json", SharingFixture.BOB).status());
	}

	public void testASecondListingCreatesNothingMore() throws Exception {
		get("/", "json", SharingFixture.BOB);
		byte[] after = Files.readAllBytes(links("bob").getFile().toPath());

		get("/", "json", SharingFixture.BOB);
		restartServer();
		get("/", "json", SharingFixture.BOB);

		assertTrue("The registry is what makes a link appear exactly once.",
			Arrays.equals(after, Files.readAllBytes(links("bob").getFile().toPath())));
		assertEquals("One link per grant: bob's own on the year folder and the group's on the zoo album.",
			2, links("bob").getLinks().size());
	}

	public void testAPlacementRuleFilesANewLinkIntoItsYearFolder() throws Exception {
		// Carol holds the zoo album through the group 'family' and files what lands in her root by
		// year.
		filedByYear("carol");

		ListingInfo root = listing(get("/", "json", SharingFixture.CAROL));

		assertNotNull("The year folder was created for the shared album.", entry(root, "2024"));
		assertTrue(links("carol").isEmpty());
		assertEquals("~alice/" + SharingFixture.ZOO, links("carol/2024").get("2024-05-01 Zoo").canonical());

		ListingInfo year = listing(get("/2024/", "json", SharingFixture.CAROL));
		assertEquals("Carol sees the shared album at her second hop.", Arrays.asList("2024-05-01 Zoo"),
			entryNames(year));
		assertEquals(HttpServletResponse.SC_OK,
			get("/2024/2024-05-01 Zoo/", "json", SharingFixture.CAROL).status());
	}

	public void testNobodyWithoutAGrantGetsALink() throws Exception {
		assertTrue("Dave holds nothing.", entryNames(listing(get("/", "json", SharingFixture.DAVE))).isEmpty());
		assertFalse(LinkStore.exists(_base.resolve("dave").toFile()));
		assertFalse("No registry for a space nothing was shared into.",
			Files.exists(_base.resolve("dave").resolve(UserStore.DIRECTORY_NAME)));
	}

	public void testAGuestAndAnAnonymousCallerGetNothing() throws Exception {
		// Eve is a guest: she has no space of her own, and the base folder is nobody's root.
		get("/", "json", SharingFixture.EVE);
		get("/", "json", null);

		assertFalse("A guest leaves no folder behind where her space would be.",
			Files.exists(_base.resolve("eve")));
		assertFalse("And nothing in the base folder either.",
			Files.exists(_base.resolve(UserStore.DIRECTORY_NAME).resolve(ShareRegistry.FILE_NAME)));
		assertFalse(LinkStore.exists(_base.toFile()));
	}

	public void testAGrantToEverybodyIsNobodysAlbum() throws Exception {
		// Alice's "Public" folder is granted to 'anonymous'; that is not a share with a person.
		ListingInfo root = listing(get("/", "json", SharingFixture.BOB));

		assertNull(entry(root, SharingFixture.PUBLIC));
		assertFalse(registry("bob").knows("alice", SharingFixture.PUBLIC));
	}

	public void testOnesOwnAlbumsAreNotLinkedIntoOnesOwnTree() throws Exception {
		grant("/" + SharingFixture.PRIVATE + "/", SharingFixture.ALICE, "grant", Subjects.user("alice"),
			Rights.VIEW);

		listing(get("/", "json", SharingFixture.ALICE));

		assertFalse("A grant in one's own space is no share with oneself.", LinkStore.exists(_base.resolve("alice")
			.toFile()));
	}

	public void testANewMemberOfAGroupSeesTheLinkAtTheirNextListing() throws Exception {
		assertNull("Dave is in no group yet.", entry(listing(get("/", "json", SharingFixture.DAVE)),
			"2024-05-01 Zoo"));

		// No fan-out write: the group gains a member and nothing is created anywhere.
		new de.haumacher.imageServer.auth.GroupStore(_base).put("family", "alice",
			Arrays.asList("bob", "carol", "dave"));
		restartServer();

		assertNotNull("At his next root listing the new member finds the shared album.",
			entry(listing(get("/", "json", SharingFixture.DAVE)), "2024-05-01 Zoo"));
	}

	public void testADeclinedShareDoesNotComeBack() throws Exception {
		listing(get("/", "json", SharingFixture.BOB));
		assertNotNull(links("bob").get(SharingFixture.YEAR));

		assertEquals(HttpServletResponse.SC_OK, unlink("/", SharingFixture.BOB, SharingFixture.YEAR).status());

		assertNull("The record is gone.", links("bob").get(SharingFixture.YEAR));
		assertEquals("The space remembers that this one was declined.", ShareRegistry.DECLINED,
			registry("bob").get("alice", SharingFixture.YEAR).getState());

		listing(get("/", "json", SharingFixture.BOB));
		assertNull("A further listing does not recreate it.", links("bob").get(SharingFixture.YEAR));

		// A new grant on the very same target is not a reason to overrule the recipient either.
		grant("/" + SharingFixture.YEAR + "/", SharingFixture.ALICE, "grant", Subjects.user("bob"), Rights.VIEW);
		restartServer();
		listing(get("/", "json", SharingFixture.BOB));

		assertNull("Declined stays declined until the recipient accepts again (issue #55).",
			links("bob").get(SharingFixture.YEAR));
	}

	public void testTheOwnersLibraryIsUntouchedByAllOfThis() throws Exception {
		String before = fingerprint(_base.resolve("alice"));

		listing(get("/", "json", SharingFixture.BOB));
		get("/" + SharingFixture.YEAR + "/", "json", SharingFixture.BOB);
		move("/", "" + TRIPS, SharingFixture.BOB, SharingFixture.YEAR);
		unlink("/" + TRIPS + "/", SharingFixture.BOB, SharingFixture.YEAR);

		assertEquals("Linking, browsing, moving and unlinking change nothing of the owner's.", before,
			fingerprint(_base.resolve("alice")));
	}
}
