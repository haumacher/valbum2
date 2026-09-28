/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.Privacy;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A share link an earlier build made on what is no part of the library now, see issue #173.
 *
 * <p>
 * Before the NAS litter was known, Synology's <code>@eaDir</code> was listed as an album of its
 * own, so a link could be made on it and stored in <code>shares.json</code>. Upgraded, that link
 * opens nothing — neither the folder nor anything below it — and it stays in the store, where
 * its owner can still withdraw it by its id.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestLibraryLitterShares extends ShareTestCase {

	private static final String LITTER = SharingFixture.ZOO + "/@eaDir";

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		Path folder = _base.resolve(LITTER);
		Files.createDirectories(folder);
		Files.copy(_base.resolve(SharingFixture.ZOO).resolve("public.jpg"), folder.resolve("SYNOPHOTO_THUMB_M.jpg"));
	}

	public void testALinkOnLitterOpensNothing() throws Exception {
		String token = issue("alice", LITTER, "DSM", "", Privacy.PUBLIC, 0);

		for (String path : new String[] { "/", "/SYNOPHOTO_THUMB_M.jpg" }) {
			for (String type : new String[] { "json", "tn" }) {
				FakeResponse response = get(path, type, token);
				assertEquals(path + "?type=" + type + ": " + response.body(), HttpServletResponse.SC_NOT_FOUND,
					response.status());
				assertEquals(AuthService.PATH_ESCAPED, errorMessage(response));
			}
		}
	}

	public void testItsOwnerSeesAndWithdrawsItAtTheAlbumAbove() throws Exception {
		String token = issue("alice", LITTER, "DSM", "", Privacy.PUBLIC, 0);
		String id = idOf(token);
		String album = "/" + SharingFixture.ZOO + "/";

		assertNotNull("Listed at the deepest folder above it that is part of the library.",
			link(links(shares(album, SharingFixture.ALICE)), id));

		FakeResponse response = unshare(album, SharingFixture.ALICE, id);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		assertEquals("Withdrawn, it is gone like any withdrawn link.", HttpServletResponse.SC_GONE,
			get("/", "json", token).status());
	}

	public void testALinkOnTheAlbumItselfIsListedAsBefore() throws Exception {
		String token = issue("alice", SharingFixture.ZOO, "Zoo", "", Privacy.PUBLIC, 0);
		String id = idOf(token);

		assertNotNull(link(links(shares("/" + SharingFixture.ZOO + "/", SharingFixture.ALICE)), id));
		assertNull("A link on an album is not listed at the folder above it.",
			link(links(shares("/" + SharingFixture.YEAR + "/", SharingFixture.ALICE)), id));
	}

	public void testTheLibraryPrefix() {
		assertEquals("2024/Zoo", LibraryFiles.libraryPrefix("2024/Zoo"));
		assertEquals("2024/Zoo", LibraryFiles.libraryPrefix("2024/Zoo/@eaDir/x"));
		assertEquals("", LibraryFiles.libraryPrefix("#recycle/old"));
		assertEquals("", LibraryFiles.libraryPrefix(""));
	}
}
