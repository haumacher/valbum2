/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.ListingInfo;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;

/**
 * Test case for the tile a link entry is shown with in a listing, see issue #50.
 */
@SuppressWarnings("javadoc")
public class TestLinkListing extends LinkTestCase {

	/** Midnight of the day alice's zoo album is named after, in the server's time zone. */
	private static final long ZOO_DATE =
		LocalDate.of(2024, 5, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		link("bob/" + TRIPS, "Zoo", "alice", SharingFixture.ZOO);
	}

	public void testTheTileOfALinkIsTheTargetsTile() throws Exception {
		ListingInfo listing = listing(get("/" + TRIPS + "/", "json", SharingFixture.BOB));

		FolderInfo tile = entry(listing, "Zoo");
		assertNotNull("The link is an entry of the listing.", tile);
		assertEquals("The canonical target says whose album this is.", "~alice/" + SharingFixture.ZOO,
			tile.getLink());
		assertEquals("The title comes from the target's own sidecar.", "Zoo", tile.getTitle());
		assertEquals("The date is the target's, read from its folder name.", ZOO_DATE, tile.getEffectiveDate());
	}

	public void testAFolderOfOnesOwnCarriesNoLink() throws Exception {
		SharingFixture.album(_base, "bob/" + TRIPS + "/2023-01-01 Ski", "Ski",
			"[\"ImagePart\",{\"name\":\"snow.jpg\",\"width\":4,\"height\":3}]", "snow.jpg");

		ListingInfo listing = listing(get("/" + TRIPS + "/", "json", SharingFixture.BOB));

		assertEquals("An ordinary folder is no link.", "", entry(listing, "2023-01-01 Ski").getLink());
	}

	public void testLinksAndFoldersAreOrderedTogetherByDate() throws Exception {
		SharingFixture.album(_base, "bob/" + TRIPS + "/2023-01-01 Ski", "Ski",
			"[\"ImagePart\",{\"name\":\"snow.jpg\",\"width\":4,\"height\":3}]", "snow.jpg");
		SharingFixture.album(_base, "bob/" + TRIPS + "/2025-07-01 Sea", "Sea",
			"[\"ImagePart\",{\"name\":\"waves.jpg\",\"width\":4,\"height\":3}]", "waves.jpg");

		ListingInfo listing = listing(get("/" + TRIPS + "/", "json", SharingFixture.BOB));

		assertEquals("The newest first, the shared album among one's own.",
			Arrays.asList("2025-07-01 Sea", "Zoo", "2023-01-01 Ski"), entryNames(listing));
	}

	public void testTheCoverOfATileComesFromTheTarget() throws Exception {
		cover("public.jpg");

		ListingInfo listing = listing(get("/" + TRIPS + "/", "json", SharingFixture.BOB));

		assertEquals("The cover is the target's own, so the tile looks like the album it points at.",
			"public.jpg", entry(listing, "Zoo").getIndexPicture().getImage());
	}

	public void testTheCoverUrlOfATileResolvesThroughTheLink() throws Exception {
		cover("public.jpg");
		ListingInfo listing = listing(get("/" + TRIPS + "/", "json", SharingFixture.BOB));
		FolderInfo tile = entry(listing, "Zoo");

		// Exactly what the app builds from the tile: <listing>/<name>/<image>.
		String url = "/" + TRIPS + "/" + tile.getName() + "/" + tile.getIndexPicture().getImage();

		assertEquals(HttpServletResponse.SC_OK, get(url, "tn", SharingFixture.BOB).status());
	}

	public void testALinkInTheRootIsShownThere() throws Exception {
		link("bob", "Zoo", "alice", SharingFixture.ZOO);

		ListingInfo root = listing(get("/", "json", SharingFixture.BOB));

		assertEquals("~alice/" + SharingFixture.ZOO, entry(root, "Zoo").getLink());
		assertEquals(HttpServletResponse.SC_OK, get("/Zoo/public.jpg", "tn", SharingFixture.BOB).status());
	}

	public void testACoverAboveTheCallersClearanceIsReplaced() throws Exception {
		// Alice covers her album with the photo only she may see: the tile of the link must find
		// another one for bob, who is a member of the album and not its owner.
		cover("private.jpg");

		ListingInfo listing = listing(get("/" + TRIPS + "/", "json", SharingFixture.BOB));

		FolderInfo tile = entry(listing, "Zoo");
		assertEquals("The cover of a link is filtered by the clearance on its target.", "public.jpg",
			tile.getIndexPicture().getImage());
		assertEquals("It stays a shared tile.", "~alice/" + SharingFixture.ZOO, tile.getLink());
	}

	/** Makes the given image the cover of alice's zoo album. */
	private void cover(String image) throws Exception {
		java.nio.file.Path sidecar = _base.resolve("alice/" + SharingFixture.ZOO).resolve("index.json");
		String contents = new String(java.nio.file.Files.readAllBytes(sidecar),
			java.nio.charset.StandardCharsets.UTF_8);
		contents = contents.replace("{\"title\":\"Zoo\"",
			"{\"title\":\"Zoo\",\"indexPicture\":{\"image\":\"" + image + "\",\"scale\":1.0}");
		java.nio.file.Files.write(sidecar, contents.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}
}
