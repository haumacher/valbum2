/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.shared.model.FolderKind;
import de.haumacher.imageServer.shared.model.FolderMatch;
import de.haumacher.imageServer.shared.model.FolderMatches;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test case for the search field of the folder picker, issue #261: <code>?type=folders&amp;q=</code>
 * answers the albums and folders of the space by title, folder name and date, best first.
 *
 * <p>
 * The library is the {@link SharingFixture} with these folders more:
 * </p>
 *
 * <pre>
 * 2019/2019-07-14 Rom Urlaub   no sidecar, one photo: described by its name
 * 2024/2024-05-20 Romantik     "Romantik am See"
 * Archive/Bromberg             "Bromberg"
 * Trips/2021 Lake              "Summer trip"
 * Inbox                        the space's inbox, "Rom inbox"
 * </pre>
 *
 * <p>
 * No background work runs in a test: nothing is recorded, so every folder without a sidecar is
 * described by what its name and its contents say.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestFolderFinder extends ShareTestCase {

	static final String ROM_URLAUB = "2019/2019-07-14 Rom Urlaub";

	static final String ROMANTIK = "2024/2024-05-20 Romantik";

	static final String BROMBERG = "Archive/Bromberg";

	static final String LAKE = "Trips/2021 Lake";

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		Path urlaub = _base.resolve(ROM_URLAUB);
		Files.createDirectories(urlaub);
		Files.copy(_base.resolve(SharingFixture.PUBLIC).resolve("open.jpg"), urlaub.resolve("rom.jpg"));
		SharingFixture.album(_base, ROMANTIK, "Romantik am See",
			"[\"ImagePart\",{\"name\":\"see.jpg\",\"width\":4,\"height\":3}]", "see.jpg");
		SharingFixture.album(_base, BROMBERG, "Bromberg",
			"[\"ImagePart\",{\"name\":\"b.jpg\",\"width\":4,\"height\":3}]", "b.jpg");
		SharingFixture.album(_base, LAKE, "Summer trip",
			"[\"ImagePart\",{\"name\":\"lake.jpg\",\"width\":4,\"height\":3}]", "lake.jpg");
		Files.createDirectories(_base.resolve("Inbox"));
		Files.write(_base.resolve("Inbox/index.json"),
			"[\"AlbumInfo\",{\"kind\":\"INBOX\",\"title\":\"Rom inbox\",\"parts\":[]}]".getBytes(StandardCharsets.UTF_8));
		restartServer();
	}

	// --- Matching. ---

	public void testATitleWordBestFirst() throws Exception {
		assertEquals("The start of a word before a piece of one, then the newest first; never the inbox.",
			Arrays.asList(ROMANTIK, ROM_URLAUB, BROMBERG), paths(find("rom", SharingFixture.ALICE)));
	}

	public void testAFolderName() throws Exception {
		FolderMatches found = find("lake", SharingFixture.ALICE);
		assertEquals(Arrays.asList(LAKE), paths(found));
		FolderMatch lake = found.getFolders().get(0);
		assertEquals("The tile as the listing shows it.", "Summer trip", lake.getFolder().getTitle());
		assertEquals("2021 Lake", lake.getFolder().getName());
		assertEquals(FolderKind.ALBUM, lake.getFolder().getKind());
		assertNull("No picture: nothing of a photograph is answered.", lake.getFolder().getIndexPicture());
	}

	public void testADateFragment() throws Exception {
		assertEquals("May 2024, the newest first.", Arrays.asList(ROMANTIK, SharingFixture.ZOO),
			paths(find("2024-05", SharingFixture.ALICE)));
		assertEquals("A day.", Arrays.asList(ROM_URLAUB), paths(find("2019-07-14", SharingFixture.ALICE)));
	}

	public void testEveryWordMustMatch() throws Exception {
		assertEquals(Arrays.asList(ROM_URLAUB), paths(find("2019 rom", SharingFixture.ALICE)));
		assertEquals("Ignoring case and accents.", Arrays.asList(ROM_URLAUB),
			paths(find("  RÖM   urlaub ", SharingFixture.ALICE)));
		assertTrue(paths(find("rom nowhere", SharingFixture.ALICE)).isEmpty());
	}

	public void testAFolderAboveMatchesLast() throws Exception {
		assertEquals("The folder itself before what lies in it.", Arrays.asList("Archive", BROMBERG),
			paths(find("archive", SharingFixture.ALICE)));
	}

	public void testNothingTypedFindsNothing() throws Exception {
		assertTrue(paths(find("   ", SharingFixture.ALICE)).isEmpty());
		assertTrue(paths(find(null, SharingFixture.ALICE)).isEmpty());
	}

	public void testBelowAFolderPathsAreRelativeToIt() throws Exception {
		assertEquals(Arrays.asList("2024-05-20 Romantik"), paths(find("/2024/", "romantik", null, SharingFixture.ALICE)));
	}

	// --- Without the background. ---

	public void testAFolderNobodyRecordedIsDescribedByItsName() throws Exception {
		FolderMatches found = find("urlaub", SharingFixture.ALICE);
		assertEquals(Arrays.asList(ROM_URLAUB), paths(found));
		assertEquals("The title its name gives.", "Rom Urlaub", found.getFolders().get(0).getFolder().getTitle());
		assertEquals("It holds a photo.", FolderKind.ALBUM, found.getFolders().get(0).getFolder().getKind());

		FolderMatches year = find("2019", SharingFixture.ALICE);
		assertEquals("The newer first.", Arrays.asList(ROM_URLAUB, "2019"), paths(year));
		assertEquals("Holding no photo, it is no album.", FolderKind.FOLDER, year.getFolders().get(1).getFolder().getKind());
	}

	public void testAChangedSidecarIsReadAgain() throws Exception {
		assertEquals(Arrays.asList(BROMBERG), paths(find("bromberg", SharingFixture.ALICE)));
		File sidecar = _base.resolve(BROMBERG).resolve("index.json").toFile();
		long before = sidecar.lastModified();
		Files.write(sidecar.toPath(), "[\"AlbumInfo\",{\"title\":\"Danzig\",\"parts\":[]}]".getBytes(StandardCharsets.UTF_8));
		assertTrue(sidecar.setLastModified(before + 2000));
		assertEquals("The same server, the new title.", Arrays.asList(BROMBERG), paths(find("danzig", SharingFixture.ALICE)));
		assertEquals("Its name still says Bromberg.", Arrays.asList(BROMBERG), paths(find("bromberg", SharingFixture.ALICE)));

		Path added = _base.resolve("Archive/Thorn");
		Files.createDirectories(added);
		File archive = _base.resolve("Archive").toFile();
		assertTrue(archive.setLastModified(archive.lastModified() + 2000));
		assertEquals("A folder added by hand.", Arrays.asList("Archive/Thorn"), paths(find("thorn", SharingFixture.ALICE)));
	}

	// --- Limits. ---

	public void testTheMatchesAreCapped() throws Exception {
		FolderMatches all = find("o", SharingFixture.ALICE);
		assertTrue(all.getFolders().size() > 2);
		assertFalse(all.isMore());

		FolderMatches two = find("/", "o", "2", SharingFixture.ALICE);
		assertEquals(paths(all).subList(0, 2), paths(two));
		assertTrue("It says that there is more.", two.isMore());

		assertEquals("Never more than the server's own cap, never fewer than one.", 1,
			find("/", "o", "0", SharingFixture.ALICE).getFolders().size());
		FakeResponse unreadable = request("/", "o", "many", SharingFixture.ALICE);
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, unreadable.status());
		assertEquals(ImageServlet.FOLDERS_BAD_LIMIT, errorMessage(unreadable));
	}

	public void testTheServerCapHolds() {
		FolderFinder finder = new FolderFinder();
		FolderMatches found = finder.find(new PathInfo(_base), "o", FolderFinder.MAX_LIMIT + 100, path -> true);
		assertTrue(found.getFolders().size() <= FolderFinder.MAX_LIMIT);
	}

	// --- Who may ask, and what they are answered. ---

	public void testAFolderTheCallerMayNotSeeIsNeverAnswered() {
		FolderFinder finder = new FolderFinder();
		PathInfo root = new PathInfo(_base);
		PathInfo archive = root.child("Archive");
		FolderMatches found = finder.find(root, "bromberg", 50, path -> !path.equals(archive));
		assertTrue("Nothing below a folder the caller may not see, whose path would name it.",
			paths(found).isEmpty());
		assertTrue(paths(finder.find(root, "archive", 50, path -> !path.equals(archive))).isEmpty());
		assertEquals(Arrays.asList("Archive", BROMBERG), paths(finder.find(root, "archive", 50, path -> true)));
	}

	public void testEveryMemberIsAnsweredTheFolders() throws Exception {
		for (String member : Arrays.asList(SharingFixture.BOB, SharingFixture.CAROL, SharingFixture.DAVE,
			SharingFixture.EVE)) {
			assertEquals("Titles are no secret, see the listing: " + member,
				Arrays.asList(ROMANTIK, ROM_URLAUB, BROMBERG), paths(find("rom", member)));
		}
	}

	public void testAnAnonymousCallerIsRefused() throws Exception {
		FakeResponse anonymous = request("/", "rom", null, null);
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, anonymous.status());
		assertEquals(PhotoSearch.MEMBERS_ONLY, errorMessage(anonymous));
	}

	public void testAShareLinkIsRefused() throws Exception {
		String token = issue("alice", "2024", "Grandma", "", Privacy.PUBLIC, 0);
		FakeResponse link = request("/", "romantik", null, token);
		assertEquals("A link sees what it was made for and looks for nothing.", HttpServletResponse.SC_FORBIDDEN,
			link.status());
		assertEquals(PhotoSearch.MEMBERS_ONLY, errorMessage(link));
	}

	public void testOnlyBelowAFolder() throws Exception {
		FakeResponse photo = request("/" + LAKE + "/lake.jpg", "lake", null, SharingFixture.ALICE);
		assertEquals(HttpServletResponse.SC_NOT_FOUND, photo.status());
		assertEquals(ImageServlet.FOLDERS_NOT_A_FOLDER, errorMessage(photo));
	}

	public void testNothingIsWritten() throws Exception {
		// What the server writes when it starts is not the search's doing.
		find("lake", SharingFixture.ALICE);
		String before = fingerprint(_base);
		find("rom", SharingFixture.ALICE);
		find("2024", SharingFixture.ALICE);
		assertEquals(before, fingerprint(_base));
	}

	// --- The album a move suggests, issue #279. ---

	public void testTheAlbumNearestToADay() throws Exception {
		assertEquals(Arrays.asList(SharingFixture.ZOO), paths(near("2024-05-03", null, SharingFixture.ALICE)));
		assertEquals("Not the album the photos come from.", Arrays.asList(ROMANTIK),
			paths(near("2024-05-03", SharingFixture.ZOO, SharingFixture.ALICE)));
	}

	public void testNothingIsSuggestedToWhoMayNotAdd() throws Exception {
		assertTrue("Dave may only look.", paths(near("2024-05-03", null, SharingFixture.DAVE)).isEmpty());
	}

	public void testAnUnreadableDayIsRefused() throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", ImageServlet.FOLDERS_TYPE);
		parameters.put(ImageServlet.FOLDERS_NEAR_PARAMETER, "yesterday");
		FakeResponse response = getWithParameters("/", SharingFixture.ALICE, parameters);
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status());
		assertEquals(ImageServlet.FOLDERS_BAD_NEAR, errorMessage(response));
	}

	private FolderMatches near(String day, String source, String token) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", ImageServlet.FOLDERS_TYPE);
		parameters.put(ImageServlet.FOLDERS_NEAR_PARAMETER, Long.toString(
			java.time.LocalDate.parse(day).atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()));
		parameters.put(ImageServlet.FOLDERS_LIMIT_PARAMETER, "1");
		if (source != null) {
			parameters.put(ImageServlet.FOLDERS_SOURCE_PARAMETER, source);
		}
		return FolderMatches.readFolderMatches(reader(body(getWithParameters("/", token, parameters))));
	}

	// --- Helpers. ---

	// --- Probes. ---

	public void testProbeAYearAndATitleWordTogether() throws Exception {
		assertEquals("The year folder above and the title of the album, typed together.",
			Arrays.asList(ROM_URLAUB), paths(find("2019 rom", SharingFixture.ALICE)));
	}

	public void testProbeCharactersOfPatternsAreText() throws Exception {
		for (String text : new String[] { "%", "(", ".*", "\\", "[a-z]", "rom)", "_" }) {
			assertTrue(text, find(text, SharingFixture.ALICE).getFolders().isEmpty());
		}
	}

	private FolderMatches find(String text, String token) throws Exception {
		return find("/", text, null, token);
	}

	private FolderMatches find(String folder, String text, String limit, String token) throws Exception {
		return FolderMatches.readFolderMatches(reader(body(request(folder, text, limit, token))));
	}

	private FakeResponse request(String folder, String text, String limit, String token) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", ImageServlet.FOLDERS_TYPE);
		if (text != null) {
			parameters.put(ImageServlet.FOLDERS_TEXT_PARAMETER, text);
		}
		if (limit != null) {
			parameters.put(ImageServlet.FOLDERS_LIMIT_PARAMETER, limit);
		}
		return getWithParameters(folder, token, parameters);
	}

	private static List<String> paths(FolderMatches matches) {
		List<String> result = new ArrayList<>();
		for (FolderMatch match : matches.getFolders()) {
			result.add(match.getPath());
		}
		return result;
	}
}
