/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.links.ShareRegistry;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.MoveOutcome;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Review probe for the link entries of issue #50, composing them with what the server already had:
 * the "view as" of issue #46 through a link, a move of issue #47 whose <em>target</em> is a link,
 * the same album reached through the viewer's link and through the canonical form, a link
 * materialised next to a real folder of the same name, and the grant that outlives a declined link.
 */
@SuppressWarnings("javadoc")
public class TestLinksProbe extends LinkTestCase {

	public void testViewAsLowersTheClearanceThroughALinkToo() throws Exception {
		link("bob", "Zoo", "alice", SharingFixture.ZOO);

		AlbumInfo asMember = album(get("/Zoo/", "json", SharingFixture.BOB));
		assertEquals(names(asMember).toString(), 2, names(asMember).size());

		AlbumInfo asPublic = album(get("/Zoo/", "json", SharingFixture.BOB, "public"));
		assertEquals(Collections.singletonList("public.jpg"), names(asPublic));
	}

	public void testAMoveWhoseTargetIsALinkLandsInTheOwnersAlbum() throws Exception {
		link("carol", "Zoo", "alice", SharingFixture.ZOO);
		String before = fingerprint(_base.resolve("alice"));

		FakeResponse response = move("/" + SharingFixture.CAROLS_ALBUM + "/", "Zoo", SharingFixture.CAROL, "carols.jpg");

		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		MoveOutcome outcome = outcome(moveResult(response), "carols.jpg");
		assertEquals("Nothing to say: it moved. " + outcome, "", outcome.getMessage());
		assertTrue("The photo is in alice's album now.",
			Files.exists(_base.resolve("alice/" + SharingFixture.ZOO + "/carols.jpg")));
		assertFalse("... and no longer in carol's.",
			Files.exists(_base.resolve("carol/" + SharingFixture.CAROLS_ALBUM + "/carols.jpg")));
		assertFalse("Alice's library changed by exactly the contribution, not by the link.",
			before.equals(fingerprint(_base.resolve("alice"))));

		AlbumInfo zoo = album(get("/Zoo/", "json", SharingFixture.CAROL));
		assertTrue(names(zoo).toString(), names(zoo).contains("carols.jpg"));
	}

	public void testTheLinkAndTheCanonicalFormShowTheSameAlbum() throws Exception {
		// Bob's first look at his root links alice's year folder for him.
		ListingInfo root = listing(get("/", "json", SharingFixture.BOB));
		FolderInfo year = entry(root, SharingFixture.YEAR);
		assertNotNull(entryNames(root).toString(), year);
		assertEquals("~alice/" + SharingFixture.YEAR, year.getLink());

		AlbumInfo viaLink = album(get("/" + SharingFixture.ZOO + "/", "json", SharingFixture.BOB));
		AlbumInfo viaOwner = album(get("/~alice/" + SharingFixture.ZOO + "/", "json", SharingFixture.BOB));

		assertEquals(viaOwner.getTitle(), viaLink.getTitle());
		assertEquals(names(viaOwner), names(viaLink));
		assertEquals(rightsOf(viaOwner), rightsOf(viaLink));
		assertEquals(viaOwner.getEffectiveDate(), viaLink.getEffectiveDate());
	}

	public void testALinkMaterialisedNextToARealFolderOfTheSameNameKeepsBoth() throws Exception {
		Files.createDirectories(_base.resolve("bob/" + SharingFixture.YEAR));
		Files.write(_base.resolve("bob/" + SharingFixture.YEAR + "/index.json"),
			"[\"ListingInfo\",{\"title\":\"Bobs own year\"}]".getBytes(StandardCharsets.UTF_8));

		ListingInfo root = listing(get("/", "json", SharingFixture.BOB));

		FolderInfo own = entry(root, SharingFixture.YEAR);
		assertNotNull(entryNames(root).toString(), own);
		assertEquals("The real folder keeps its name.", "", own.getLink());

		FolderInfo linked = null;
		for (FolderInfo folder : root.getFolders()) {
			if (("~alice/" + SharingFixture.YEAR).equals(folder.getLink())) {
				linked = folder;
			}
		}
		assertNotNull("The link is there under another name: " + entryNames(root), linked);
		assertFalse(SharingFixture.YEAR.equals(linked.getName()));

		ListingInfo ownYear = listing(get("/" + SharingFixture.YEAR + "/", "json", SharingFixture.BOB));
		assertEquals("Bobs own year", ownYear.getTitle());
		ListingInfo alicesYear = listing(get("/" + linked.getName() + "/", "json", SharingFixture.BOB));
		assertTrue(entryNames(alicesYear).toString(), entryNames(alicesYear).contains("2024-05-01 Zoo"));
	}

	public void testDecliningALinkLeavesTheGrantAndTheCanonicalPathOpen() throws Exception {
		link("carol", "Zoo", "alice", SharingFixture.ZOO);
		registry("carol").link("alice", SharingFixture.ZOO);

		FakeResponse removed = unlink("/", SharingFixture.CAROL, "Zoo");
		assertEquals(removed.body(), HttpServletResponse.SC_OK, removed.status());
		assertEquals(ShareRegistry.DECLINED, registry("carol").get("alice", SharingFixture.ZOO).getState());

		FakeResponse gone = get("/Zoo/", "json", SharingFixture.CAROL);
		assertEquals(HttpServletResponse.SC_NOT_FOUND, gone.status());

		FakeResponse canonical = get("/~alice/" + SharingFixture.ZOO + "/", "json", SharingFixture.CAROL);
		assertEquals("The grant is alice's decision, the link was carol's.", HttpServletResponse.SC_OK,
			canonical.status());
		assertEquals(HttpServletResponse.SC_OK,
			upload("/~alice/" + SharingFixture.ZOO + "/", SharingFixture.CAROL, "late.jpg").status());
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
