/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.InboxMigration.Decision;
import de.haumacher.imageServer.InboxMigration.Flagged;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.InviteMode;
import de.haumacher.imageServer.auth.SpaceStore;
import de.haumacher.imageServer.auth.Spaces;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import junit.framework.TestCase;

/**
 * Test case for {@link InboxMigration}: which of the albums an older build flagged becomes the one
 * inbox of a space, see issue #226.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestInboxMigration extends TestCase {

	private Path _base;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-inbox-migration");
	}

	@Override
	protected void tearDown() throws Exception {
		try (Stream<Path> files = Files.walk(_base)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
		}
		super.tearDown();
	}

	public void testNothingFlaggedIsTheDefault() throws Exception {
		album("2024 Trip", false, 1000L);

		Decision decision = InboxMigration.settle(_base, SpaceStore.DEFAULT_INBOX);
		assertEquals("Inbox", decision.getInbox());
		assertTrue(decision.getDemoted().isEmpty());
		assertEquals("Written once, so that the next start does not decide again.",
			"Inbox", SpaceStore.load(_base, "").getInbox());
		assertTrue(SpaceStore.load(_base, "").hasStoredInbox());
		assertFalse("Nothing created the folder: the first upload does.", Files.exists(_base.resolve("Inbox")));
	}

	public void testTheFlaggedInboxAtTheRootNamedInboxWins() throws Exception {
		album("Inbox", true, 1000L);
		album("Phone", true, 5000L);

		Decision decision = InboxMigration.settle(_base, SpaceStore.DEFAULT_INBOX);
		assertEquals("Even against one written later.", "Inbox", decision.getInbox());
		assertEquals(Arrays.asList("Phone"), paths(decision.getDemoted()));
	}

	public void testTheOnlyFlaggedAlbumWins() throws Exception {
		album("Family/Sync", false, 1000L);
		album("Family/Phone Uploads", true, 1000L);
		album("Inbox", false, 9000L);

		Decision decision = InboxMigration.settle(_base, SpaceStore.DEFAULT_INBOX);
		assertEquals("The one the sync used, wherever it lies and whatever it is called.",
			"Family/Phone Uploads", decision.getInbox());
		assertEquals("Family/Phone Uploads", SpaceStore.load(_base, "").getInbox());
		assertTrue(decision.getDemoted().isEmpty());
	}

	/** The acceptance case of issue #226: an old sidecar with two flagged albums. */
	public void testOfSeveralTheMostRecentlyWrittenWins() throws Exception {
		album("A Phone", true, 1000L);
		album("2023/B Phone", true, 3000L);
		album("C Phone", true, 2000L);

		Decision decision = InboxMigration.settle(_base, SpaceStore.DEFAULT_INBOX);
		assertEquals("2023/B Phone", decision.getInbox());
		assertEquals(Arrays.asList("A Phone", "C Phone"), paths(decision.getDemoted()));
		String line = decision.describe();
		assertTrue(line, line.contains("A Phone, C Phone"));
		assertTrue(line, line.contains("ordinary album"));

		assertTrue("Nothing is rewritten: the flag stays on disk until the album's next write.",
			read("A Phone/index.json").contains("INBOX"));
		assertTrue("No photograph moved.", Files.isRegularFile(_base.resolve("A Phone/a.jpg")));
	}

	public void testIgnoredFoldersAndOtherSpacesAreNotLookedInto() throws Exception {
		album(".valbum/trash/Old", true, 9000L);
		album("@eaDir/Thumbs", true, 9000L);
		album("Other/Inbox", true, 9000L);
		Files.createDirectories(_base.resolve("Other/.valbum"));
		Files.write(_base.resolve("Other/.valbum/space.json"), "{}".getBytes(StandardCharsets.UTF_8));

		assertTrue(InboxMigration.flagged(_base).isEmpty());
	}

	public void testAStoredInboxIsNotDecidedAgain() throws Exception {
		SpaceStore.storeInbox(_base, "Somewhere");
		album("Phone", true, 1000L);
		Spaces spaces = Spaces.detect(_base, null, AuthMode.OFF, InviteMode.MEMBERS);

		assertTrue("Nothing to settle.", InboxMigration.settleAll(spaces).isEmpty());
		assertEquals("Somewhere", spaces.single().getConfig().getInbox());
	}

	public void testSettlingHandsTheInboxToTheSpace() throws Exception {
		album("Phone", true, 1000L);
		Spaces spaces = Spaces.detect(_base, null, AuthMode.OFF, InviteMode.MEMBERS);

		List<String> lines = InboxMigration.settleAll(spaces);
		assertEquals(1, lines.size());
		assertTrue(lines.get(0), lines.get(0).startsWith("This library: the inbox is 'Phone'"));
		assertEquals("Phone", spaces.single().getConfig().getInbox());
	}

	public void testEverySpaceOfAServerIsSettled() throws Exception {
		for (String space : Arrays.asList("anna", "ben")) {
			Files.createDirectories(_base.resolve(space).resolve(".valbum"));
			Files.write(_base.resolve(space).resolve(".valbum/space.json"),
				"{\"name\":\"N\",\"anonymous\":\"public\",\"mapUrl\":\"https://m/{lat}/{lon}\",\"faces\":\"on\"}"
					.getBytes(StandardCharsets.UTF_8));
		}
		album("ben/Sync", true, 1000L);
		Spaces spaces = Spaces.detect(_base, null, AuthMode.OFF, InviteMode.MEMBERS);

		List<String> lines = InboxMigration.settleAll(spaces);
		assertEquals(Arrays.asList("Space 'anna': the inbox is 'Inbox' (written into .valbum/space.json).",
			"Space 'ben': the inbox is 'Sync' (written into .valbum/space.json)."), lines);
		SpaceStore.Config ben = SpaceStore.load(_base.resolve("ben"), "ben");
		assertEquals("Sync", ben.getInbox());
		assertEquals("Everything else the file said is kept.", "N", ben.getName());
		assertTrue(ben.isAnonymousAllowed());
		assertTrue(ben.isFacesEnabled());
		assertEquals("https://m/{lat}/{lon}", ben.getMapUrl());
	}

	public void testTheInboxSurvivesAMoveIntoASpace() throws Exception {
		SpaceStore.storeInbox(_base, "Phone");
		SpaceStore.rewrite(_base, "Family", SpaceStore.ANONYMOUS_PUBLIC);
		assertEquals("Phone", SpaceStore.load(_base, "").getInbox());
		assertEquals("Family", SpaceStore.load(_base, "").getName());
	}

	public void testAnInboxOutsideTheSpaceIsRefused() throws Exception {
		for (String bad : Arrays.asList("", "../x", ".valbum/trash", "a//b", "/abs", "@eaDir", "a/./b")) {
			assertNotNull(bad, SpaceStore.checkInbox(bad));
		}
		assertNull(SpaceStore.checkInbox("Family/Phone Uploads"));
		Files.createDirectories(_base.resolve(".valbum"));
		Files.write(_base.resolve(".valbum/space.json"), "{\"inbox\":\"../elsewhere\"}".getBytes(StandardCharsets.UTF_8));
		assertEquals("A file naming no folder of the space is read as naming none.",
			"Inbox", SpaceStore.load(_base, "").getInbox());
		assertFalse(SpaceStore.load(_base, "").hasStoredInbox());
	}

	// --- Helpers. ---

	private void album(String path, boolean flagged, long written) throws IOException {
		Path folder = _base.resolve(path);
		Files.createDirectories(folder);
		Path sidecar = folder.resolve("index.json");
		Files.write(sidecar, ("[\"AlbumInfo\",{" + (flagged ? "\"kind\":\"INBOX\"," : "") + "\"title\":\"x\","
			+ "\"parts\":[[\"ImagePart\",{\"name\":\"a.jpg\"}]]}]").getBytes(StandardCharsets.UTF_8));
		Files.write(folder.resolve("a.jpg"), new byte[] { 1, 2, 3 });
		Files.setLastModifiedTime(sidecar, FileTime.fromMillis(written));
	}

	private String read(String path) throws IOException {
		return new String(Files.readAllBytes(_base.resolve(path)), StandardCharsets.UTF_8);
	}

	private static List<String> paths(List<Flagged> flagged) {
		return flagged.stream().map(Flagged::getPath).collect(Collectors.toList());
	}
}
