/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.Clearances;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.auth.UserStore.Device;
import de.haumacher.imageServer.auth.UserStore.User;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumKind;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.FolderKind;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.MoveResult;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Test case for the inbox, see issue #131 and issue #135.
 *
 * <p>
 * An inbox is a kind of album: the same folder, the same <code>index.json</code>, the same parts.
 * What is tested here is everything that follows from the one stored flag — the round trip through
 * the sidecar, the derived order, the date it has not got, who may see it, and the single-image
 * delete that makes an inbox usable at all.
 * </p>
 *
 * <p>
 * The servlet is driven headlessly on a temporary base folder with the request and response fakes
 * of {@link TestImageServletPut}, exactly as {@link TestImageServletDelete} does.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestInbox extends TestCase {

	private static final String ALICE_TOKEN = "alice-token";

	private static final String BOB_TOKEN = "bob-token";

	private static final String DAVE_TOKEN = "dave-token";

	/** How issue #53 names the contributions of the user <code>bob</code>. */
	private static final String BOB = "user:bob";

	private Path _base;

	private ImageServlet _servlet;

	private final List<ImageServlet> _servlets = new ArrayList<>();

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-inbox-test");
		_servlet = servlet(AuthMode.OFF);
	}

	@Override
	protected void tearDown() throws Exception {
		for (ImageServlet servlet : _servlets) {
			servlet.destroy();
		}
		_servlets.clear();
		_servlet = null;
		if (_base != null) {
			removeTree(_base);
		}
		super.tearDown();
	}

	// --- What an inbox is: one stored flag. ---

	public void testAStoredFlagSaysThatAFolderIsAnInbox() throws Exception {
		image("Inbox/a.jpg", Color.RED);
		sidecar("Inbox", "[\"AlbumInfo\",{\"kind\":\"INBOX\",\"title\":\"Inbox\",\"parts\":["
			+ part("a.jpg", 0) + "]}]");

		AlbumInfo album = album("/Inbox/");
		assertEquals(AlbumKind.INBOX, album.getKind());
		assertEquals("Inbox", album.getTitle());
	}

	public void testASidecarWithoutTheFieldIsAnAlbum() throws Exception {
		image("Trip/a.jpg", Color.RED);
		sidecar("Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[" + part("a.jpg", 0) + "]}]");

		assertEquals("Every sidecar written before this field existed is the album it always was.",
			AlbumKind.ALBUM, album("/Trip/").getKind());
	}

	public void testTheFlagSurvivesAPropertiesWrite() throws Exception {
		image("Inbox/a.jpg", Color.RED);
		sidecar("Inbox", "[\"AlbumInfo\",{\"kind\":\"INBOX\",\"title\":\"Inbox\",\"parts\":["
			+ part("a.jpg", 0) + "]}]");

		AlbumInfo read = album("/Inbox/");
		assertEquals(HttpServletResponse.SC_OK, put("/Inbox/", write(read)).status());

		assertTrue("The flag is stored, not derived.",
			read("Inbox/index.json").contains("\"kind\":\"INBOX\""));
		assertEquals(AlbumKind.INBOX, album("/Inbox/").getKind());
	}

	public void testTheListingEntryOfAnInboxSaysWhatItIsAndHasNoDate() throws Exception {
		image("2026-05-01 Trip/a.jpg", Color.RED);
		sidecar("2026-05-01 Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[" + part("a.jpg", 0) + "]}]");
		image("Inbox/b.jpg", Color.GREEN);
		// A date stored on it and a date in its name: neither makes a day of an inbox.
		sidecar("Inbox", "[\"AlbumInfo\",{\"kind\":\"INBOX\",\"title\":\"Inbox\",\"date\":1700000000000,"
			+ "\"parts\":[" + part("b.jpg", 0) + "]}]");

		ListingInfo listing = listing("/");
		assertEquals("An inbox stands first: it is what needs doing.",
			Arrays.asList("Inbox", "2026-05-01 Trip"), names(listing));
		assertEquals(Arrays.asList(FolderKind.INBOX, FolderKind.ALBUM), kinds(listing));
		assertEquals("An inbox has no date, whatever is written on it.", 0L, entry(listing, "Inbox").getEffectiveDate());

		assertEquals("Not even the album itself derives one.", 0L, album("/Inbox/").getEffectiveDate());
	}

	public void testAnInboxIsNamedByItsTitleAlone() throws Exception {
		AlbumInfo properties = AlbumInfo.create().setKind(AlbumKind.INBOX).setTitle("Inbox").setDate(1700000000000L);
		assertEquals("An inbox has no date, so its folder name carries none.",
			"Inbox", FolderNames.of(properties, "2023-11-14 Inbox"));
		assertEquals("An album of the same properties is named by its date and its title.",
			"2023-11-14 Inbox", FolderNames.of(AlbumInfo.create().setTitle("Inbox").setDate(1700000000000L), ""));
	}

	public void testTurningAnAlbumIntoAnInboxRenamesTheFolder() throws Exception {
		image("2023-11-14 Inbox/a.jpg", Color.RED);
		sidecar("2023-11-14 Inbox", "[\"AlbumInfo\",{\"title\":\"Inbox\",\"date\":1700000000000,\"parts\":["
			+ part("a.jpg", 0) + "]}]");

		AlbumInfo properties = album("/2023-11-14 Inbox/").setKind(AlbumKind.INBOX);
		assertEquals(HttpServletResponse.SC_OK, put("/2023-11-14 Inbox/", write(properties)).status());

		assertTrue("The folder is named by what it is, see issue #130.", _base.resolve("Inbox").toFile().isDirectory());
		assertFalse(_base.resolve("2023-11-14 Inbox").toFile().exists());
		assertTrue("The photograph rode along.", _base.resolve("Inbox/a.jpg").toFile().isFile());
	}

	// --- The order of an inbox is the date. ---

	/** The acceptance example: a stored order and a group, answered flat and by date. */
	public void testAnInboxIsAnsweredFlatAndByDate() throws Exception {
		Color[] colors = { Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW };
		List<String> names = Arrays.asList("a.jpg", "b1.jpg", "b2.jpg", "c.jpg");
		for (int n = 0; n < names.size(); n++) {
			image("Inbox/" + names.get(n), colors[n]);
		}
		String contents = "[\"AlbumInfo\",{\"kind\":\"INBOX\",\"title\":\"Inbox\",\"parts\":["
			+ part("c.jpg", day("2026-03-02")) + ","
			+ part("a.jpg", day("2026-03-01")) + ","
			+ group(image("b1.jpg", day("2026-02-01")), image("b2.jpg", day("2026-03-03")))
			+ "]}]";
		sidecar("Inbox", contents);

		assertEquals("The group is dissolved and everything stands on its own date.",
			Arrays.asList("b1.jpg", "a.jpg", "c.jpg", "b2.jpg"), imageNames(album("/Inbox/")));

		assertEquals("The order is derived: the sidecar says what its author said.",
			contents, read("Inbox/index.json"));
	}

	public void testAnUndatedPhotographStandsAtTheEnd() throws Exception {
		image("Inbox/dated.jpg", Color.RED);
		image("Inbox/undated1.jpg", Color.GREEN);
		image("Inbox/undated2.jpg", Color.BLUE);
		sidecar("Inbox", "[\"AlbumInfo\",{\"kind\":\"INBOX\",\"title\":\"Inbox\",\"parts\":["
			+ part("undated2.jpg", 0) + "," + part("undated1.jpg", 0) + ","
			+ part("dated.jpg", day("2026-03-01")) + "]}]");

		assertEquals("Nothing is dated 1970; what says no time stands at the end, by name.",
			Arrays.asList("dated.jpg", "undated1.jpg", "undated2.jpg"), imageNames(album("/Inbox/")));
	}

	/**
	 * The round trip: what an inbox answers must not become what the sidecar stores.
	 *
	 * <p>
	 * The app reads the inbox flat and writes the album back when its properties are edited —
	 * here, to turn it into an album again. The stored order and the group are the author's and
	 * must be there when the flag is gone.
	 * </p>
	 */
	public void testTurningAnInboxBackIntoAnAlbumRestoresTheStoredOrder() throws Exception {
		Color[] colors = { Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW };
		List<String> names = Arrays.asList("a.jpg", "b1.jpg", "b2.jpg", "c.jpg");
		for (int n = 0; n < names.size(); n++) {
			image("Inbox/" + names.get(n), colors[n]);
		}
		sidecar("Inbox", "[\"AlbumInfo\",{\"kind\":\"INBOX\",\"title\":\"Inbox\",\"parts\":["
			+ part("c.jpg", day("2026-03-02")) + ","
			+ part("a.jpg", day("2026-03-01")) + ","
			+ group(image("b1.jpg", day("2026-02-01")), image("b2.jpg", day("2026-03-03")))
			+ "]}]");

		// Exactly what the app does: read, change one property, write back.
		AlbumInfo flat = album("/Inbox/");
		assertEquals(Arrays.asList("b1.jpg", "a.jpg", "c.jpg", "b2.jpg"), imageNames(flat));
		assertEquals(HttpServletResponse.SC_OK, put("/Inbox/", write(flat.setKind(AlbumKind.ALBUM))).status());

		AlbumInfo album = album("/Inbox/");
		assertEquals(AlbumKind.ALBUM, album.getKind());
		assertEquals("The author's order is back, because it was never overwritten.",
			Arrays.asList("c.jpg", "a.jpg", "b1.jpg", "b2.jpg"), imageNames(album));
		assertEquals("The group is intact.", 3, album.getParts().size());
		assertTrue(album.getParts().get(2) instanceof ImageGroup);
	}

	/** An edit of an image in an inbox is stored, while the order stays the author's. */
	public void testWhatIsWrittenInAnInboxIsKeptAndTheOrderIsNot() throws Exception {
		image("Inbox/a.jpg", Color.RED);
		image("Inbox/c.jpg", Color.GREEN);
		sidecar("Inbox", "[\"AlbumInfo\",{\"kind\":\"INBOX\",\"title\":\"Inbox\",\"parts\":["
			+ part("c.jpg", day("2026-03-02")) + "," + part("a.jpg", day("2026-03-01")) + "]}]");

		AlbumInfo flat = album("/Inbox/");
		assertEquals(Arrays.asList("a.jpg", "c.jpg"), imageNames(flat));
		((ImagePart) flat.getParts().get(0)).setRating(2);
		assertEquals(HttpServletResponse.SC_OK, put("/Inbox/", write(flat)).status());

		AlbumInfo stored = (AlbumInfo) Resource.readResource(reader(read("Inbox/index.json")));
		assertEquals("The stored order is untouched.", Arrays.asList("c.jpg", "a.jpg"), imageNames(stored));
		assertEquals("The rating was written where it belongs.", 2,
			((ImagePart) stored.getParts().get(1)).getRating());
	}

	/** A part the client added or removed is a statement, not a round trip: it is stored. */
	public void testAChangedSetOfImagesIsStoredAsItComes() throws Exception {
		image("Inbox/a.jpg", Color.RED);
		image("Inbox/c.jpg", Color.GREEN);
		sidecar("Inbox", "[\"AlbumInfo\",{\"kind\":\"INBOX\",\"title\":\"Inbox\",\"parts\":["
			+ part("c.jpg", day("2026-03-02")) + "," + part("a.jpg", day("2026-03-01")) + "]}]");

		AlbumInfo flat = album("/Inbox/");
		flat.getParts().remove(1);
		assertEquals(HttpServletResponse.SC_OK, put("/Inbox/", write(flat)).status());

		AlbumInfo stored = (AlbumInfo) Resource.readResource(reader(read("Inbox/index.json")));
		assertEquals(Arrays.asList("a.jpg"), imageNames(stored));
	}

	// --- Who may see an inbox. ---

	public void testAnEditorSeesAllOfTheInbox() throws Exception {
		sharedInbox();

		ImageServlet servlet = servlet(AuthMode.WRITES);
		AlbumInfo inbox = album(servlet, "/Inbox/", ALICE_TOKEN);
		assertEquals(Arrays.asList("bob1.jpg", "bob2.jpg", "other1.jpg", "other2.jpg"), imageNames(inbox));
		assertEquals(Arrays.asList("Inbox", "2026-05-01 Trip"), names(listing(servlet, "/", ALICE_TOKEN)));
	}

	public void testAContributorSeesTheirOwnContributionsAndNothingElse() throws Exception {
		sharedInbox();

		ImageServlet servlet = servlet(AuthMode.WRITES);
		AlbumInfo inbox = album(servlet, "/Inbox/", BOB_TOKEN);
		assertEquals("A device's owner sorts what that device uploaded, see issue #53.",
			Arrays.asList("bob1.jpg", "bob2.jpg"), imageNames(inbox));
		assertEquals("The tile is there: they may put something in it.",
			Arrays.asList("Inbox", "2026-05-01 Trip"), names(listing(servlet, "/", BOB_TOKEN)));

		assertEquals(HttpServletResponse.SC_OK, get(servlet, "/Inbox/bob1.jpg", BOB_TOKEN, "tn").status());
		FakeResponse foreign = get(servlet, "/Inbox/other1.jpg", BOB_TOKEN, "tn");
		assertEquals("Somebody else's photograph is not there for them.",
			HttpServletResponse.SC_NOT_FOUND, foreign.status());
		assertEquals(Inboxes.NOT_FOUND, errorMessage(foreign));
	}

	public void testTheTileOfAnInboxShowsAContributorNoForeignCover() throws Exception {
		sharedInbox();
		// The inbox is shown by a photograph of somebody else's.
		sidecar("Inbox", read("Inbox/index.json").replace("\"parts\":",
			"\"indexPicture\":{\"image\":\"other1.jpg\",\"scale\":1.0},\"parts\":"));

		ImageServlet servlet = servlet(AuthMode.WRITES);
		assertNotNull("The editor sees the cover the album carries.",
			entry(listing(servlet, "/", ALICE_TOKEN), "Inbox").getIndexPicture());
		assertNull("A contributor keeps the tile and loses a cover that is not theirs.",
			entry(listing(servlet, "/", BOB_TOKEN), "Inbox").getIndexPicture());
	}

	public void testAViewerDoesNotSeeTheInboxAtAll() throws Exception {
		sharedInbox();

		ImageServlet servlet = servlet(AuthMode.WRITES);
		FakeResponse album = get(servlet, "/Inbox/", DAVE_TOKEN, "json");
		assertEquals(HttpServletResponse.SC_NOT_FOUND, album.status());
		assertEquals(Inboxes.NOT_FOUND, errorMessage(album));

		assertEquals(HttpServletResponse.SC_NOT_FOUND, get(servlet, "/Inbox/bob1.jpg", DAVE_TOKEN, "tn").status());
		assertEquals("The entry is dropped from the listing above it.",
			Arrays.asList("2026-05-01 Trip"), names(listing(servlet, "/", DAVE_TOKEN)));
	}

	public void testAnAnonymousCallerDoesNotSeeTheInboxAtAll() throws Exception {
		sharedInbox();

		ImageServlet servlet = servlet(AuthMode.WRITES);
		assertEquals(HttpServletResponse.SC_NOT_FOUND, get(servlet, "/Inbox/", null, "json").status());
		assertEquals(Arrays.asList("2026-05-01 Trip"), names(listing(servlet, "/", null)));
	}

	public void testViewAsPublicAnswersWhatAPublicCallerSees() throws Exception {
		sharedInbox();

		ImageServlet servlet = servlet(AuthMode.WRITES);
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "json");
		parameters.put("viewAs", "public");
		FakeResponse response = new FakeResponse();
		servlet.doGet(request("/Inbox/", null, "", ALICE_TOKEN, parameters), response.response());
		assertEquals("The owner asking what a visitor sees is answered what a visitor sees.",
			HttpServletResponse.SC_NOT_FOUND, response.status());

		FakeResponse listing = new FakeResponse();
		servlet.doGet(request("/", null, "", ALICE_TOKEN, parameters), listing.response());
		assertEquals(Arrays.asList("2026-05-01 Trip"),
			names((ListingInfo) Resource.readResource(reader(listing.body()))));
	}

	public void testAShareLinkNeverShowsAnInbox() throws Exception {
		sharedInbox();

		ImageServlet servlet = servlet(AuthMode.WRITES);
		String token = shareToken(servlet, "/");

		assertEquals("A link on the folder above lists everything but the inbox.",
			Arrays.asList("2026-05-01 Trip"), names(listing(servlet, "/", token)));
		assertEquals(HttpServletResponse.SC_NOT_FOUND, get(servlet, "/Inbox/", token, "json").status());
		assertEquals(HttpServletResponse.SC_NOT_FOUND, get(servlet, "/Inbox/bob1.jpg", token, "tn").status());
	}

	public void testAnInboxCannotBeShared() throws Exception {
		sharedInbox();

		ImageServlet servlet = servlet(AuthMode.WRITES);
		FakeResponse response = shareResponse(servlet, "/Inbox/");

		assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status());
		assertEquals(Inboxes.INBOX_NOT_SHARED, errorMessage(response));
	}

	// --- Deleting a single photograph. ---

	public void testAPhotographIsMovedIntoTheAlbumItHasInTheTrash() throws Exception {
		image("Trip/a.jpg", Color.RED);
		image("Trip/b.jpg", Color.GREEN);
		image("Trip/c.jpg", Color.BLUE);
		sidecar("Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":["
			+ part("a.jpg", 0) + "," + part("b.jpg", 0) + "," + part("c.jpg", 0) + "]}]");
		byte[] original = Files.readAllBytes(_base.resolve("Trip/a.jpg"));

		MoveResult result = delete("/Trip/", "a.jpg");

		assertEquals("a.jpg", result.getOutcomes().get(0).getNewName());
		assertEquals(DeleteService.trashed("a.jpg"), result.getOutcomes().get(0).getMessage());

		File trashed = new File(trash(), "Trip/a.jpg");
		assertTrue("The photograph is in the album this one has in the trash.", trashed.isFile());
		assertTrue("Byte for byte what it was.", Arrays.equals(original, Files.readAllBytes(trashed.toPath())));
		assertFalse(_base.resolve("Trip/a.jpg").toFile().exists());

		AlbumInfo album = album("/Trip/");
		assertEquals(Arrays.asList("b.jpg", "c.jpg"), imageNames(album));
		assertEquals("The hash entry left with the file.", 2, hashCount(_base.resolve("Trip").toFile()));
		assertEquals("And landed in the trash album.", 1, hashCount(new File(trash(), "Trip")));
		assertTrue("The trash album describes itself, so that it reads from disk.",
			new File(trash(), "Trip/index.json").isFile());
	}

	public void testASecondPhotographOfTheSameNameGetsAFreeName() throws Exception {
		image("A/Trip/a.jpg", Color.RED);
		sidecar("A/Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[" + part("a.jpg", 0) + "]}]");
		image("B/Trip/a.jpg", Color.GREEN);
		sidecar("B/Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[" + part("a.jpg", 0) + "]}]");

		assertEquals("a.jpg", delete("/A/Trip/", "a.jpg").getOutcomes().get(0).getNewName());
		String second = delete("/B/Trip/", "a.jpg").getOutcomes().get(0).getNewName();

		assertFalse("Nothing is ever overwritten in the trash either.", "a.jpg".equals(second));
		assertTrue(new File(trash(), "Trip/a.jpg").isFile());
		assertTrue(new File(trash(), "Trip/" + second).isFile());
	}

	public void testAContributorThrowsAwayTheirOwnPhotographAndNobodyElses() throws Exception {
		sharedInbox();
		ImageServlet servlet = servlet(AuthMode.WRITES);

		FakeResponse own = deleteResponse(servlet, "/Inbox/", BOB_TOKEN, "bob1.jpg");
		assertEquals(own.body(), HttpServletResponse.SC_OK, own.status());
		assertTrue(new File(trash(), "Inbox/bob1.jpg").isFile());

		FakeResponse foreign = deleteResponse(servlet, "/Inbox/", BOB_TOKEN, "other1.jpg");
		assertEquals(HttpServletResponse.SC_FORBIDDEN, foreign.status());
		assertEquals(MoveService.CONTRIBUTION_REFUSED, errorMessage(foreign));
		assertTrue("Nothing of somebody else's moved.", _base.resolve("Inbox/other1.jpg").toFile().isFile());
	}

	public void testAViewerMayNotThrowAPhotographAway() throws Exception {
		sharedInbox();
		ImageServlet servlet = servlet(AuthMode.WRITES);

		FakeResponse response = deleteResponse(servlet, "/2026-05-01 Trip/", DAVE_TOKEN, "trip.jpg");
		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(MoveService.CONTRIBUTION_REFUSED, errorMessage(response));
		assertTrue(_base.resolve("2026-05-01 Trip/trip.jpg").toFile().isFile());
	}

	public void testAShareLinkMayNotThrowAPhotographAway() throws Exception {
		sharedInbox();
		ImageServlet servlet = servlet(AuthMode.WRITES);
		String token = shareToken(servlet, "/2026-05-01 Trip/");

		FakeResponse response = deleteResponse(servlet, "/", token, "trip.jpg");
		assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(DeleteService.SHARE_DELETE_REFUSED, errorMessage(response));
		assertTrue(_base.resolve("2026-05-01 Trip/trip.jpg").toFile().isFile());
	}

	/**
	 * The p0 of issue #109, for the single photograph: nothing is ever unlinked.
	 *
	 * <p>
	 * Every byte below the base folder is counted before the delete and after it. A delete is a
	 * rename, so the sum can only grow (by the sidecars the server writes) and never shrink.
	 * </p>
	 */
	public void testNoPhotographIsEverUnlinked() throws Exception {
		image("Trip/a.jpg", Color.RED);
		image("Trip/b.jpg", Color.GREEN);
		image("Trip/c.jpg", Color.BLUE);
		sidecar("Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":["
			+ part("a.jpg", 0) + "," + part("b.jpg", 0) + "," + part("c.jpg", 0) + "]}]");
		Map<String, Long> before = pictures(_base);

		delete("/Trip/", "a.jpg", "b.jpg");

		Map<String, Long> after = pictures(_base);
		assertEquals("Every photograph is still there, byte for byte.", before, after);
	}

	public void testANameThatIsNoEntryIsStillRefused() throws Exception {
		image("Trip/a.jpg", Color.RED);
		sidecar("Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[" + part("a.jpg", 0) + "]}]");
		write("Trip/notes.txt", "my notes".getBytes(StandardCharsets.UTF_8));

		MoveResult result = delete("/Trip/", "notes.txt", "index.json", "nowhere");

		assertEquals(DeleteService.notAnEntry("notes.txt"), result.getOutcomes().get(0).getMessage());
		assertEquals(DeleteService.notAnEntry("index.json"), result.getOutcomes().get(1).getMessage());
		assertEquals(DeleteService.notFound("nowhere"), result.getOutcomes().get(2).getMessage());
	}

	public void testAlbumsAndPhotographsInOneRequestKeepTheirOrder() throws Exception {
		image("A/Trip/a.jpg", Color.RED);
		sidecar("A/Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[" + part("a.jpg", 0) + "]}]");
		image("A/loose.jpg", Color.GREEN);
		sidecar("A", "[\"AlbumInfo\",{\"title\":\"A\",\"parts\":[" + part("loose.jpg", 0) + "]}]");

		MoveResult result = delete("/A/", "Trip", "loose.jpg");

		assertEquals(Arrays.asList("Trip", "loose.jpg"),
			result.getOutcomes().stream().map(o -> o.getName()).collect(Collectors.toList()));
		assertEquals(DeleteService.trashed("Trip"), result.getOutcomes().get(0).getMessage());
		assertEquals(DeleteService.trashed("loose.jpg"), result.getOutcomes().get(1).getMessage());
		assertTrue(new File(trash(), "Trip/a.jpg").isFile());
		assertTrue(new File(trash(), "A/loose.jpg").isFile());
	}

	// --- A group the sidecar still remembers behind the flat answer. ---

	/**
	 * Throwing away one photograph of a remembered group throws away that one, see issue #131.
	 *
	 * <p>
	 * What the album does with the group is what it already does when a member that is not the
	 * representative is taken out (issue #47): the member leaves and a group of one is replaced by
	 * that one as a plain part, because a group of one is not a group.
	 * </p>
	 */
	public void testDeletingOneOfARememberedGroupLeavesTheOtherWhereItIs() throws Exception {
		groupedInbox();

		MoveResult result = delete("/Inbox/", "b1.jpg");

		assertEquals(1, result.getOutcomes().size());
		assertEquals(DeleteService.trashed("b1.jpg"), result.getOutcomes().get(0).getMessage());
		assertTrue(new File(trash(), "Inbox/b1.jpg").isFile());
		assertFalse("A photograph nobody named never moves.", new File(trash(), "Inbox/b2.jpg").exists());
		assertTrue(_base.resolve("Inbox/b2.jpg").toFile().isFile());

		AlbumInfo stored = (AlbumInfo) Resource.readResource(reader(read("Inbox/index.json")));
		assertEquals(Arrays.asList("a.jpg", "b2.jpg"), imageNames(stored));
		for (AlbumPart part : stored.getParts()) {
			assertFalse("A group of one is not a group.", part instanceof ImageGroup);
		}

		AlbumInfo trashed = (AlbumInfo) Resource.readResource(reader(
			new String(Files.readAllBytes(new File(trash(), "Inbox/index.json").toPath()), StandardCharsets.UTF_8)));
		assertEquals(Arrays.asList("b1.jpg"), imageNames(trashed));
		assertTrue("It arrives as one photograph.", trashed.getParts().get(0) instanceof ImagePart);
	}

	/** In an album the rule of issue #47 is untouched: the representative carries its group. */
	public void testInAnAlbumTheRepresentativeStillCarriesItsGroup() throws Exception {
		groupedInbox();
		// The very same folder, as an album.
		sidecar("Inbox", read("Inbox/index.json").replace("\"kind\":\"INBOX\",", ""));

		delete("/Inbox/", "b1.jpg");

		assertTrue("An album shows a group by its representative; naming it names the group.",
			new File(trash(), "Inbox/b2.jpg").isFile());
		assertTrue(new File(trash(), "Inbox/b1.jpg").isFile());
	}

	/** The duplicate sweep of issue #118 acts per photograph, in an inbox as anywhere. */
	public void testTheDuplicateSweepTakesOnePhotographOutOfARememberedGroup() throws Exception {
		groupedInbox();
		// The same picture as b1.jpg, in an album of its own: that is what makes it a duplicate.
		Files.createDirectories(_base.resolve("Trip"));
		Files.copy(_base.resolve("Inbox/b1.jpg"), _base.resolve("Trip/kept.jpg"));
		sidecar("Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[" + part("kept.jpg", 0) + "]}]");
		_servlet.index().indexNow();

		MoveResult result = post("find-duplicates", "/Inbox/", null);

		assertEquals(1, result.getOutcomes().size());
		assertEquals("b1.jpg", result.getOutcomes().get(0).getName());
		assertFalse(_base.resolve("Inbox/b1.jpg").toFile().exists());
		assertTrue("The other member of the remembered group stays.", _base.resolve("Inbox/b2.jpg").toFile().isFile());

		AlbumInfo stored = (AlbumInfo) Resource.readResource(reader(read("Inbox/index.json")));
		assertEquals(Arrays.asList("a.jpg", "b2.jpg"), imageNames(stored));
	}

	/** An inbox whose sidecar remembers a group of two behind its flat answer. */
	private void groupedInbox() throws IOException {
		image("Inbox/a.jpg", Color.RED);
		image("Inbox/b1.jpg", Color.GREEN);
		image("Inbox/b2.jpg", Color.BLUE);
		sidecar("Inbox", "[\"AlbumInfo\",{\"kind\":\"INBOX\",\"title\":\"Inbox\",\"parts\":["
			+ part("a.jpg", day("2026-03-01")) + ","
			+ group(image("b1.jpg", day("2026-02-01")), image("b2.jpg", day("2026-03-03")))
			+ "]}]");
	}

	private MoveResult post(String action, String pathInfo, String token) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", action);
		FakeResponse response = new FakeResponse();
		_servlet.doPost(request(pathInfo, "application/json", "{}", token, parameters), response.response());
		assertEquals("The " + action + " failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return MoveResult.readMoveResult(reader(response.body()));
	}

	// --- Helpers. ---

	/** A space with an inbox holding two photographs of bob's and two of somebody else's. */
	private void sharedInbox() throws IOException {
		users();
		Color[] colors = { Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW };
		List<String> names = Arrays.asList("bob1.jpg", "bob2.jpg", "other1.jpg", "other2.jpg");
		for (int n = 0; n < names.size(); n++) {
			image("Inbox/" + names.get(n), colors[n]);
		}
		sidecar("Inbox", "[\"AlbumInfo\",{\"kind\":\"INBOX\",\"title\":\"Inbox\",\"parts\":["
			+ part("bob1.jpg", day("2026-03-01")) + "," + part("bob2.jpg", day("2026-03-02")) + ","
			+ part("other1.jpg", day("2026-03-03")) + "," + part("other2.jpg", day("2026-03-04")) + "]}]");
		hashes("Inbox", "bob1.jpg", BOB, "bob2.jpg", BOB, "other1.jpg", "user:alice", "other2.jpg", "user:alice");

		image("2026-05-01 Trip/trip.jpg", Color.BLUE);
		sidecar("2026-05-01 Trip",
			"[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[" + part("trip.jpg", 0) + "]}]");
	}

	/** An administrator, a contributor and a viewer of the one space. */
	private void users() throws IOException {
		UserStore store = new UserStore(_base);
		User alice = store.nameOwner("alice");
		alice.addDevice(new Device("Alice's phone", UserStore.hash(ALICE_TOKEN), Instant.now().toString()));
		store.addUser(user("bob", BOB_TOKEN, Roles.CONTRIBUTE));
		store.addUser(user("dave", DAVE_TOKEN, Roles.VIEW));
		store.store();
	}

	private static User user(String name, String token, String role) {
		User result = new User(name, role, "", Instant.now().toString(), Clearances.ALL, true);
		result.addDevice(new Device(name + "'s device", UserStore.hash(token), Instant.now().toString()));
		return result;
	}

	private ImageServlet servlet(AuthMode mode) throws Exception {
		ImageServlet servlet = new ImageServlet(_base.toFile(), new AuthService(mode, _base));
		servlet.init();
		_servlets.add(servlet);
		return servlet;
	}

	private File trash() {
		return _base.resolve(UserStore.DIRECTORY_NAME).resolve(DeleteService.TRASH_FOLDER).toFile();
	}

	private AlbumInfo album(String pathInfo) throws Exception {
		return album(_servlet, pathInfo, null);
	}

	private static AlbumInfo album(ImageServlet servlet, String pathInfo, String token) throws Exception {
		FakeResponse response = get(servlet, pathInfo, token, "json");
		assertEquals("Reading failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return (AlbumInfo) Resource.readResource(reader(response.body()));
	}

	private ListingInfo listing(String pathInfo) throws Exception {
		return listing(_servlet, pathInfo, null);
	}

	private static ListingInfo listing(ImageServlet servlet, String pathInfo, String token) throws Exception {
		FakeResponse response = get(servlet, pathInfo, token, "json");
		assertEquals("Reading failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return (ListingInfo) Resource.readResource(reader(response.body()));
	}

	private static FakeResponse get(ImageServlet servlet, String pathInfo, String token, String type)
			throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", type);
		FakeResponse response = new FakeResponse();
		servlet.doGet(request(pathInfo, null, "", token, parameters), response.response());
		return response;
	}

	private FakeResponse put(String pathInfo, String body) throws Exception {
		FakeResponse response = new FakeResponse();
		_servlet.doPut(request(pathInfo, "application/json", body, null, new HashMap<>()), response.response());
		return response;
	}

	private MoveResult delete(String pathInfo, String... names) throws Exception {
		FakeResponse response = deleteResponse(_servlet, pathInfo, null, names);
		assertEquals("The delete failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return MoveResult.readMoveResult(reader(response.body()));
	}

	private static FakeResponse deleteResponse(ImageServlet servlet, String pathInfo, String token, String... names)
			throws Exception {
		String body = "{\"target\":\"\",\"names\":["
			+ Arrays.stream(names).map(n -> "{\"name\":\"" + n + "\"}").collect(Collectors.joining(",")) + "]}";
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "delete");
		FakeResponse response = new FakeResponse();
		servlet.doPost(request(pathInfo, "application/json", body, token, parameters), response.response());
		return response;
	}

	/** A share link on the given folder that even allows contributing. */
	private static FakeResponse shareResponse(ImageServlet servlet, String pathInfo) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "share");
		String body = "{\"label\":\"Grandma\",\"expires\":\"\",\"maxPrivacy\":0,\"minRating\":0,"
			+ "\"rights\":[{\"name\":\"view\"},{\"name\":\"download\"},{\"name\":\"contribute\"}]}";
		FakeResponse response = new FakeResponse();
		servlet.doPost(request(pathInfo, "application/json", body, ALICE_TOKEN, parameters), response.response());
		return response;
	}

	private static String shareToken(ImageServlet servlet, String pathInfo) throws Exception {
		FakeResponse response = shareResponse(servlet, pathInfo);
		assertEquals("Cannot create a share link: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return ShareLinkCreated.readShareLinkCreated(reader(response.body())).getToken();
	}

	private static HttpServletRequest request(String pathInfo, String contentType, String body, String token,
			Map<String, String> parameters) {
		Map<String, String> headers = new HashMap<>();
		if (contentType != null) {
			headers.put("Content-Type", contentType);
		}
		if (token != null) {
			headers.put("Authorization", "Bearer " + token);
		}
		return TestImageServletPut.request(pathInfo, contentType, body.getBytes(StandardCharsets.UTF_8), headers,
			parameters);
	}

	private static String errorMessage(FakeResponse response) throws IOException {
		Resource resource = Resource.readResource(reader(response.body()));
		assertTrue("Expected an ErrorInfo body, got: " + response.body(), resource instanceof ErrorInfo);
		return ((ErrorInfo) resource).getMessage();
	}

	private static JsonReader reader(String contents) {
		return new JsonReader(new ReaderAdapter(new StringReader(contents)));
	}

	private static String write(Resource resource) throws IOException {
		StringWriter buffer = new StringWriter();
		try (JsonWriter json = new JsonWriter(new WriterAdapter(buffer))) {
			resource.writeTo(json);
		}
		return buffer.toString();
	}

	/** The names of the images the given album answers, in the order it answers them. */
	private static List<String> imageNames(AlbumInfo album) {
		List<String> result = new ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				result.add(((ImagePart) part).getName());
			} else if (part instanceof ImageGroup) {
				for (ImagePart image : ((ImageGroup) part).getImages()) {
					result.add(image.getName());
				}
			}
		}
		return result;
	}

	private static List<String> names(ListingInfo listing) {
		return listing.getFolders().stream().map(f -> f.getName()).collect(Collectors.toList());
	}

	private static List<FolderKind> kinds(ListingInfo listing) {
		return listing.getFolders().stream().map(f -> f.getKind()).collect(Collectors.toList());
	}

	private static FolderInfo entry(ListingInfo listing, String name) {
		for (FolderInfo folder : listing.getFolders()) {
			if (folder.getName().equals(name)) {
				return folder;
			}
		}
		throw new AssertionError("No entry '" + name + "' in " + names(listing) + ".");
	}

	/** How many files the hash sidecar of the given folder describes. */
	private static int hashCount(File folder) throws IOException {
		File file = new File(folder, ".hashes.json");
		if (!file.isFile()) {
			return 0;
		}
		String contents = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
		int result = 0;
		for (String name : Arrays.asList(".jpg\":")) {
			int index = contents.indexOf(name);
			while (index >= 0) {
				result++;
				index = contents.indexOf(name, index + 1);
			}
		}
		return result;
	}

	/** Every picture below the given root: its relative path, and a fingerprint of its bytes. */
	private static Map<String, Long> pictures(Path root) throws IOException {
		Map<String, Long> result = new HashMap<>();
		try (Stream<Path> walk = Files.walk(root)) {
			for (Path file : walk.filter(Files::isRegularFile).collect(Collectors.toList())) {
				if (!file.getFileName().toString().endsWith(".jpg")) {
					continue;
				}
				byte[] contents = Files.readAllBytes(file);
				long fingerprint = 1L;
				for (byte b : contents) {
					fingerprint = fingerprint * 31L + b;
				}
				result.put(file.getFileName().toString() + ":" + contents.length, Long.valueOf(fingerprint));
			}
		}
		return result;
	}

	/** An <code>ImagePart</code> for a hand-written sidecar. */
	private static String part(String name, long date) {
		return "[\"ImagePart\"," + image(name, date) + "]";
	}

	/** The properties of an image, as a member of an {@link ImageGroup} carries them. */
	private static String image(String name, long date) {
		return "{\"name\":\"" + name + "\",\"kind\":\"IMAGE\",\"date\":" + date + ",\"width\":8,\"height\":6}";
	}

	/** An <code>ImageGroup</code> of the given images, the first one its representative. */
	private static String group(String... images) {
		return "[\"ImageGroup\",{\"representative\":0,\"images\":[" + String.join(",", images) + "]}]";
	}

	/** The given day at midnight UTC, in milliseconds since the epoch. */
	private static long day(String isoDate) {
		return java.time.LocalDate.parse(isoDate).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
	}

	private void sidecar(String folder, String contents) throws IOException {
		Path path = _base.resolve(folder);
		Files.createDirectories(path);
		Files.write(path.resolve("index.json"), contents.getBytes(StandardCharsets.UTF_8));
	}

	/** A hash sidecar attributing the named files, given as pairs of name and subject. */
	private void hashes(String folder, String... nameAndSubject) throws IOException {
		StringBuilder json = new StringBuilder("{\"version\":1,\"files\":{");
		for (int n = 0; n < nameAndSubject.length; n += 2) {
			File file = _base.resolve(folder).resolve(nameAndSubject[n]).toFile();
			if (n > 0) {
				json.append(",");
			}
			json.append("\"").append(nameAndSubject[n]).append("\":{\"size\":").append(file.length())
				.append(",\"modified\":").append(file.lastModified())
				.append(",\"sha256\":\"").append(sha256(file)).append("\",\"contributor\":\"")
				.append(nameAndSubject[n + 1]).append("\",\"contributorLabel\":\"")
				.append(nameAndSubject[n + 1].replace("user:", "")).append("\"}");
		}
		json.append("}}");
		write(folder + "/.hashes.json", json.toString().getBytes(StandardCharsets.UTF_8));
	}

	private static String sha256(File file) throws IOException {
		return de.haumacher.imageServer.upload.HashCache.sha256(file);
	}

	private String read(String relativePath) throws IOException {
		return new String(Files.readAllBytes(_base.resolve(relativePath)), StandardCharsets.UTF_8);
	}

	private void image(String relativePath, Color color) throws IOException {
		write(relativePath, jpeg(color));
	}

	private void write(String relativePath, byte[] contents) throws IOException {
		Path path = _base.resolve(relativePath);
		Files.createDirectories(path.getParent());
		Files.write(path, contents);
	}

	/** A real (tiny) JPEG; different colours give different content hashes. */
	private static byte[] jpeg(Color color) throws IOException {
		BufferedImage image = new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		graphics.setColor(color);
		graphics.fillRect(0, 0, 8, 6);
		graphics.dispose();
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		ImageIO.write(image, "jpg", buffer);
		return buffer.toByteArray();
	}

	private static void removeTree(Path root) throws IOException {
		if (!Files.exists(root)) {
			return;
		}
		try (Stream<Path> files = Files.walk(root)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
	}

}
