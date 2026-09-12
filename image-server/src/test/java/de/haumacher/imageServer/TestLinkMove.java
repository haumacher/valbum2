/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.auth.Subjects;
import de.haumacher.imageServer.links.LinkService;
import de.haumacher.imageServer.shared.model.MoveOutcome;
import de.haumacher.imageServer.shared.model.MoveResult;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.file.Files;

/**
 * Test case for moving, filing and removing link entries, see issue #50.
 *
 * <p>
 * A link moves like a folder and speaks the vocabulary of the move of issue #47, but nothing on
 * disk is renamed: the record travels between two sidecars of the recipient's own.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestLinkMove extends LinkTestCase {

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		link("bob", "Zoo", "alice", SharingFixture.ZOO);
	}

	public void testBobMovesALinkIntoAFolderOfHisOwn() throws Exception {
		MoveResult result = moveResult(move("/", TRIPS, SharingFixture.BOB, "Zoo"));

		MoveOutcome outcome = outcome(result, "Zoo");
		assertEquals("Zoo", outcome.getNewName());
		assertEquals("", outcome.getMessage());
		assertNull("The record left the root.", links("bob").get("Zoo"));
		assertEquals("alice", links("bob/" + TRIPS).get("Zoo").getOwner());
		assertEquals("The link resolves at its new place.", HttpServletResponse.SC_OK,
			get("/" + TRIPS + "/Zoo/", "json", SharingFixture.BOB).status());
		assertEquals("And nowhere else.", HttpServletResponse.SC_NOT_FOUND,
			get("/Zoo/", "json", SharingFixture.BOB).status());
	}

	public void testANameTheTargetFolderUsesRenamesTheLink() throws Exception {
		Files.createDirectories(_base.resolve("bob/" + TRIPS + "/Zoo"));

		MoveResult result = moveResult(move("/", TRIPS, SharingFixture.BOB, "Zoo"));

		assertEquals("A link is a name and nothing else; a clash renames it.", "Zoo-2",
			outcome(result, "Zoo").getNewName());
		assertNotNull(links("bob/" + TRIPS).get("Zoo-2"));
	}

	public void testALinkCannotBeMovedIntoAnotherUsersLibrary() throws Exception {
		// Carol keeps alice's public folder in her tree and may contribute to alice's zoo album, so
		// the rights allow this move and it is refused for what it is.
		link("carol", "Open", "alice", SharingFixture.PUBLIC);

		MoveResult result = moveResult(move("/", "~alice/" + SharingFixture.ZOO, SharingFixture.CAROL, "Open"));

		assertEquals(MoveService.linkEscaped("Open"), outcome(result, "Open").getMessage());
		assertNotNull("Nothing moved.", links("carol").get("Open"));
		assertFalse("And nothing of alice's holds a link of carol's.",
			de.haumacher.imageServer.links.LinkStore.exists(_base.resolve("alice/" + SharingFixture.ZOO).toFile()));
	}

	public void testALinkCannotBeMovedIntoWhatItPointsAt() throws Exception {
		// Bob keeps both alice's year folder and the zoo album inside it; moving the one into the
		// other would file a link below what it points at.
		link("bob", "Alices year", "alice", SharingFixture.YEAR);
		grant("/" + SharingFixture.ZOO + "/", SharingFixture.ALICE, "grant", Subjects.user("bob"), Rights.EDIT);

		MoveResult result = moveResult(move("/", "Zoo", SharingFixture.BOB, "Alices year"));

		assertEquals(MoveService.linkIntoTarget("Alices year"), outcome(result, "Alices year").getMessage());
		assertNotNull("Nothing moved.", links("bob").get("Alices year"));
	}

	public void testAPlacementRuleFilesALinkByItsTargetsDate() throws Exception {
		filedByYear("bob/" + TRIPS);

		MoveResult result = moveResult(move("/", TRIPS, SharingFixture.BOB, "Zoo"));

		assertEquals("The link is filed by the date of the album it points at.", "2024/Zoo",
			outcome(result, "Zoo").getNewName());
		assertNotNull(links("bob/" + TRIPS + "/2024").get("Zoo"));
		assertEquals(HttpServletResponse.SC_OK,
			get("/" + TRIPS + "/2024/Zoo/", "json", SharingFixture.BOB).status());
	}

	public void testApplyingTheRuleFilesTheLinksThatAreAlreadyThere() throws Exception {
		filedByYear("bob");
		restartServer();

		MoveResult result = moveResult(place("/", SharingFixture.BOB));

		assertEquals("2024/Zoo", outcome(result, "Zoo").getNewName());
		assertTrue(links("bob").isEmpty());
		assertNotNull(links("bob/2024").get("Zoo"));
	}

	public void testAFolderWithoutARuleSaysSoForALinkToo() throws Exception {
		MoveResult result = moveResult(place("/", SharingFixture.BOB));

		assertEquals(MoveService.NO_RULE, outcome(result, "Zoo").getMessage());
	}

	public void testRemovingALinkIsNoMoveOfAnybodysFiles() throws Exception {
		FakeResponse response = unlink("/", SharingFixture.BOB, "Zoo", "not-there");

		MoveResult result = moveResult(response);
		assertEquals(LinkService.UNLINKED, outcome(result, "Zoo").getMessage());
		assertEquals(LinkService.notALink("not-there"),
			outcome(result, "not-there").getMessage());
		assertTrue(links("bob").isEmpty());
		assertTrue("Alice's album is where it always was.",
			Files.isDirectory(_base.resolve("alice/" + SharingFixture.ZOO)));
	}

	public void testOnlySomebodyWhoMayChangeTheFolderMayRemoveALink() throws Exception {
		link("carol", "Zoo", "alice", SharingFixture.ZOO);

		FakeResponse response = unlink("/", SharingFixture.DAVE, "Zoo");

		assertEquals("Dave asks in his own space, where there is no such link.", HttpServletResponse.SC_OK,
			response.status());
		assertEquals(LinkService.notALink("Zoo"),
			outcome(moveResult(response), "Zoo").getMessage());
		assertNotNull("Carol's link is untouched.", links("carol").get("Zoo"));
	}

	public void testMovingALinkNeverTouchesTheOwnersLibrary() throws Exception {
		String before = fingerprint(_base.resolve("alice"));

		move("/", TRIPS, SharingFixture.BOB, "Zoo");
		move("/" + TRIPS + "/", "", SharingFixture.BOB, "Zoo");

		assertEquals(before, fingerprint(_base.resolve("alice")));
		assertNotNull("The link came home.", links("bob").get("Zoo"));
		assertTrue("And the sidecar it left is empty.", links("bob/" + TRIPS).isEmpty());
	}

	public void testAnUnknownNameIsRefusedAsItAlwaysWas() throws Exception {
		MoveResult result = moveResult(move("/", TRIPS, SharingFixture.BOB, "nothing-here"));

		assertEquals(MoveService.notFound("nothing-here"), outcome(result, "nothing-here").getMessage());
	}
}
