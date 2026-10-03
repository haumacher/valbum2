/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.Clearances;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.SpaceStore;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.auth.UserStore.Device;
import de.haumacher.imageServer.auth.UserStore.User;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumKind;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.AuthInfo;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.FolderKind;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.MoveResult;
import de.haumacher.imageServer.shared.model.PresentFile;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import de.haumacher.imageServer.shared.model.UploadCheckResult;
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
 * Test case for the inbox, see issues #131, #135 and #226.
 *
 * <p>
 * An inbox is a kind of album: the same folder, the same <code>index.json</code>, the same parts.
 * Since issue #226 a space has one, the folder its <code>space.json</code> names. What is tested
 * here is everything that follows from that — the round trip through
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

	private static final String CAROL_TOKEN = "carol-token";

	private static final String TRIP = "/2026-05-01 Trip/";

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

	// --- What the inbox is: the one folder the space names, see issue #226. ---

	public void testTheFolderTheSpaceNamesIsTheInbox() throws Exception {
		image("Inbox/a.jpg", Color.RED);
		// No flag in the sidecar: the place decides.
		sidecar("Inbox", "[\"AlbumInfo\",{\"title\":\"Inbox\",\"parts\":[" + part("a.jpg", 0) + "]}]");

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

	public void testAnAlbumAnOlderBuildFlaggedIsAnAlbum() throws Exception {
		image("2024 Phone/a.jpg", Color.RED);
		sidecar("2024 Phone", "[\"AlbumInfo\",{\"kind\":\"INBOX\",\"title\":\"Phone\",\"parts\":["
			+ part("a.jpg", day("2024-03-01")) + "]}]");
		image("Old/Inbox/b.jpg", Color.GREEN);
		sidecar("Old/Inbox", "[\"AlbumInfo\",{\"kind\":\"INBOX\",\"title\":\"Inbox\",\"parts\":["
			+ part("b.jpg", day("2024-04-01")) + "]}]");

		AlbumInfo phone = album("/2024 Phone/");
		assertEquals("One inbox per space; a second flag is no inbox, see issue #226.",
			AlbumKind.ALBUM, phone.getKind());
		assertTrue("It has the date of an album again.", phone.getEffectiveDate() > 0L);
		assertEquals(AlbumKind.ALBUM, album("/Old/Inbox/").getKind());
		assertEquals("And it is listed like any album.", Arrays.asList("2024 Phone", "Old"), names(listing("/")));
		assertEquals(Arrays.asList(FolderKind.ALBUM, FolderKind.FOLDER), kinds(listing("/")));

		assertTrue("Reading never writes: the flag is still on disk.",
			read("2024 Phone/index.json").contains("\"kind\":\"INBOX\""));
		AlbumInfo read = album("/2024 Phone/");
		assertEquals(HttpServletResponse.SC_OK, put("/2024 Phone/", write(read)).status());
		assertFalse("The next ordinary write drops it.", read("2024 Phone/index.json").contains("INBOX"));
		assertTrue("The photograph is untouched.", _base.resolve("2024 Phone/a.jpg").toFile().isFile());
	}

	public void testAnotherFolderMayBeTheInbox() throws Exception {
		image("Family/Unsorted/a.jpg", Color.RED);
		image("Inbox/b.jpg", Color.GREEN);
		ImageServlet servlet = new ImageServlet(_base.toFile(), AuthService.disabled(), "",
			new SpaceStore.Config("", SpaceStore.ANONYMOUS_NONE, "", SpaceStore.FACES_OFF, "", "Family/Unsorted"));
		servlet.init();
		_servlets.add(servlet);

		assertEquals(AlbumKind.INBOX, album(servlet, "/Family/Unsorted/", null).getKind());
		assertEquals("A folder of that name elsewhere is an album.",
			AlbumKind.ALBUM, album(servlet, "/Inbox/", null).getKind());
		assertEquals(Arrays.asList(), names(listing(servlet, "/Family/", null)));
		assertEquals("Family/Unsorted", auth(servlet, null).getInbox());
	}

	public void testTheKindIsDerivedAndNeverStored() throws Exception {
		image("Inbox/a.jpg", Color.RED);
		sidecar("Inbox", "[\"AlbumInfo\",{\"kind\":\"INBOX\",\"title\":\"Inbox\",\"parts\":["
			+ part("a.jpg", 0) + "]}]");

		AlbumInfo read = album("/Inbox/");
		assertEquals(HttpServletResponse.SC_OK, put("/Inbox/", write(read)).status());

		assertFalse("The kind is derived from the place, never stored: " + read("Inbox/index.json"),
			read("Inbox/index.json").contains("INBOX"));
		assertEquals(AlbumKind.INBOX, album("/Inbox/").getKind());
	}

	public void testTheListingNeverShowsTheInbox() throws Exception {
		image("2026-05-01 Trip/a.jpg", Color.RED);
		sidecar("2026-05-01 Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"parts\":[" + part("a.jpg", 0) + "]}]");
		image("Inbox/b.jpg", Color.GREEN);
		// A date stored on it and a date in its name: neither makes a day of an inbox.
		sidecar("Inbox", "[\"AlbumInfo\",{\"title\":\"Inbox\",\"date\":1700000000000,"
			+ "\"parts\":[" + part("b.jpg", 0) + "]}]");

		assertEquals("Not a tile, not even for whoever may edit it, see issue #226.",
			Arrays.asList("2026-05-01 Trip"), names(listing("/")));
		assertEquals("The inbox itself has no date.", 0L, album("/Inbox/").getEffectiveDate());
	}

	public void testTheAuthAnswerSaysWhereTheInboxIsAndHowMuchWaits() throws Exception {
		image("Inbox/a.jpg", Color.RED);
		image("Inbox/b.jpg", Color.GREEN);
		image("Inbox/c.jpg", Color.BLUE);
		sidecar("Inbox", "[\"AlbumInfo\",{\"title\":\"Inbox\",\"parts\":["
			+ part("a.jpg", 0) + "," + part("b.jpg", 0) + "," + part("c.jpg", 0) + "]}]");

		AuthInfo info = auth(_servlet, null);
		assertEquals("Inbox", info.getInbox());
		assertEquals(3, info.getInboxCount());
	}

	public void testAnInboxCountsTheMembersOfAGroup() throws Exception {
		image("Inbox/a.jpg", Color.RED);
		image("Inbox/b1.jpg", Color.GREEN);
		image("Inbox/b2.jpg", Color.BLUE);
		// An inbox stores no group, but an album an older build turned into one may hold some.
		sidecar("Inbox", "[\"AlbumInfo\",{\"title\":\"Inbox\",\"parts\":["
			+ part("a.jpg", day("2026-03-01")) + ","
			+ group(image("b1.jpg", day("2026-02-01")), image("b2.jpg", day("2026-03-03"))) + "]}]");

		assertEquals("Three pictures are waiting, not two bundles.", 3, auth(_servlet, null).getInboxCount());
	}

	public void testTheCountFollowsWhatArrivesAndWhatIsThrownAway() throws Exception {
		image("Inbox/a.jpg", Color.RED);
		image("Inbox/b.jpg", Color.GREEN);
		sidecar("Inbox", "[\"AlbumInfo\",{\"title\":\"Inbox\",\"parts\":["
			+ part("a.jpg", 0) + "," + part("b.jpg", 0) + "]}]");
		assertEquals(2, auth(_servlet, null).getInboxCount());

		image("Inbox/c.jpg", Color.BLUE);
		assertEquals("A photograph that arrived is waiting.", 3, auth(_servlet, null).getInboxCount());

		AlbumInfo inbox = album("/Inbox/");
		((ImagePart) inbox.getParts().get(0)).setRating(-2);
		assertEquals(HttpServletResponse.SC_OK, put("/Inbox/", write(inbox)).status());
		assertEquals("One rated as trash is not waiting any more: the inbox hides it.",
			2, auth(_servlet, null).getInboxCount());
	}

	public void testTheCountIsWhatTheCallerIsShownThere() throws Exception {
		sharedInbox();

		ImageServlet servlet = servlet(AuthMode.WRITES);
		AuthInfo alice = auth(servlet, ALICE_TOKEN);
		assertEquals("Inbox", alice.getInbox());
		assertEquals(4, alice.getInboxCount());
		AuthInfo bob = auth(servlet, BOB_TOKEN);
		assertEquals("A contributor is told where it is: the camera roll uploads there.", "Inbox", bob.getInbox());
		assertEquals("And how many of their own wait, which is what they are shown there (issue #135).",
			2, bob.getInboxCount());

		AuthInfo dave = auth(servlet, DAVE_TOKEN);
		assertEquals("A member who may only look is told nothing.", "", dave.getInbox());
		assertEquals(0, dave.getInboxCount());
		assertEquals("Nor is an anonymous caller.", "", auth(servlet, null).getInbox());
		AuthInfo link = auth(servlet, shareToken(servlet, "/"));
		assertEquals("Nor a share link, which never reaches the inbox.", "", link.getInbox());
		assertEquals(0, link.getInboxCount());
	}

	public void testTheInboxIsNotThereUntilTheFirstUploadMakesIt() throws Exception {
		image("2026-05-01 Trip/a.jpg", Color.RED);
		assertFalse(_base.resolve("Inbox").toFile().exists());

		AlbumInfo empty = album("/Inbox/");
		assertEquals("Before the first upload the inbox is empty.", AlbumKind.INBOX, empty.getKind());
		assertTrue(empty.getParts().isEmpty());
		assertEquals(0, auth(_servlet, null).getInboxCount());
		assertTrue("A hash check may ask it.", checkUnmade().getPresent().isEmpty());
		assertFalse("Reading and checking write nothing.", _base.resolve("Inbox").toFile().exists());

		FakeResponse upload = upload(_servlet, "/Inbox/", null, "new.jpg", Color.BLUE);
		assertEquals(upload.body(), HttpServletResponse.SC_OK, upload.status());
		assertTrue("The first upload made it.", _base.resolve("Inbox/new.jpg").toFile().isFile());
		assertEquals(Arrays.asList("new.jpg"), imageNames(album("/Inbox/")));
		assertEquals(1, auth(_servlet, null).getInboxCount());
		assertEquals("And it is still no tile.", Arrays.asList("2026-05-01 Trip"), names(listing("/")));
	}

	public void testAViewerCannotMakeTheInbox() throws Exception {
		users();
		ImageServlet servlet = servlet(AuthMode.WRITES);

		FakeResponse refused = upload(servlet, "/Inbox/", DAVE_TOKEN, "new.jpg", Color.BLUE);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, refused.status());
		assertFalse(_base.resolve("Inbox").toFile().exists());

		FakeResponse made = upload(servlet, "/Inbox/", BOB_TOKEN, "new.jpg", Color.BLUE);
		assertEquals(made.body(), HttpServletResponse.SC_OK, made.status());
		assertEquals(1, auth(servlet, BOB_TOKEN).getInboxCount());
	}

	public void testTheInboxIsNeverRenamedAfterItsTitle() throws Exception {
		image("Inbox/a.jpg", Color.RED);
		sidecar("Inbox", "[\"AlbumInfo\",{\"title\":\"Inbox\",\"parts\":[" + part("a.jpg", 0) + "]}]");

		AlbumInfo properties = album("/Inbox/").setTitle("Eingang").setDate(1700000000000L);
		FakeResponse response = put("/Inbox/", write(properties));
		assertEquals(HttpServletResponse.SC_OK, response.status());

		assertTrue("The space names its inbox; a title does not move it.", _base.resolve("Inbox").toFile().isDirectory());
		assertEquals("Eingang", album("/Inbox/").getTitle());
		assertEquals(AlbumKind.INBOX, album("/Inbox/").getKind());
	}

	public void testTheKindAClientSendsIsIgnored() throws Exception {
		image("2023-11-14 Trip/a.jpg", Color.RED);
		sidecar("2023-11-14 Trip", "[\"AlbumInfo\",{\"title\":\"Trip\",\"date\":1700000000000,\"parts\":["
			+ part("a.jpg", 0) + "]}]");

		AlbumInfo properties = album("/2023-11-14 Trip/").setKind(AlbumKind.INBOX);
		assertEquals(HttpServletResponse.SC_OK, put("/2023-11-14 Trip/", write(properties)).status());

		assertTrue("Nothing renamed it as an inbox.", _base.resolve("2023-11-14 Trip").toFile().isDirectory());
		assertEquals("No request makes a second inbox, see issue #226.",
			AlbumKind.ALBUM, album("/2023-11-14 Trip/").getKind());
		assertFalse(read("2023-11-14 Trip/index.json").contains("INBOX"));
	}

	// --- The order of an inbox is the date. ---

	/**
	 * The acceptance example: a stored order and a group, answered flat and by date, the newest
	 * first — the inbox is where the latest arrivals are sorted, so they stand at the top.
	 */
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

		assertEquals("The group is dissolved and everything stands on its own date, the newest first.",
			Arrays.asList("b2.jpg", "c.jpg", "a.jpg", "b1.jpg"), imageNames(album("/Inbox/")));

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

		assertEquals("Nothing is dated 1970; what says no time stands at the end even newest-first, by name.",
			Arrays.asList("dated.jpg", "undated1.jpg", "undated2.jpg"), imageNames(album("/Inbox/")));
	}

	/**
	 * The round trip: what an inbox answers must not become what the sidecar stores.
	 *
	 * <p>
	 * The app reads the inbox flat and writes it back. An app older than issue #226 could send the
	 * kind <code>ALBUM</code> to turn it into an album again; the place decides now, so it stays the
	 * inbox, and the stored order and the group are the author's all the same.
	 * </p>
	 */
	public void testTheInboxStaysTheInboxAndKeepsItsStoredOrder() throws Exception {
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

		// Exactly what an older app did: read, change the kind, write back.
		AlbumInfo flat = album("/Inbox/");
		assertEquals(Arrays.asList("b2.jpg", "c.jpg", "a.jpg", "b1.jpg"), imageNames(flat));
		assertEquals(HttpServletResponse.SC_OK, put("/Inbox/", write(flat.setKind(AlbumKind.ALBUM))).status());

		assertEquals(AlbumKind.INBOX, album("/Inbox/").getKind());
		AlbumInfo stored = (AlbumInfo) Resource.readResource(reader(read("Inbox/index.json")));
		assertEquals("The author's order was never overwritten.",
			Arrays.asList("c.jpg", "a.jpg", "b1.jpg", "b2.jpg"), imageNames(stored));
		assertEquals("The group is intact.", 3, stored.getParts().size());
		assertTrue(stored.getParts().get(2) instanceof ImageGroup);
	}

	/** An edit of an image in an inbox is stored, while the order stays the author's. */
	public void testWhatIsWrittenInAnInboxIsKeptAndTheOrderIsNot() throws Exception {
		image("Inbox/a.jpg", Color.RED);
		image("Inbox/c.jpg", Color.GREEN);
		sidecar("Inbox", "[\"AlbumInfo\",{\"kind\":\"INBOX\",\"title\":\"Inbox\",\"parts\":["
			+ part("a.jpg", day("2026-03-01")) + "," + part("c.jpg", day("2026-03-02")) + "]}]");

		AlbumInfo flat = album("/Inbox/");
		assertEquals("Newest first.", Arrays.asList("c.jpg", "a.jpg"), imageNames(flat));
		((ImagePart) flat.getParts().get(0)).setRating(2);
		assertEquals(HttpServletResponse.SC_OK, put("/Inbox/", write(flat)).status());

		AlbumInfo stored = (AlbumInfo) Resource.readResource(reader(read("Inbox/index.json")));
		assertEquals("The stored order is untouched.", Arrays.asList("a.jpg", "c.jpg"), imageNames(stored));
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
		flat.getParts().remove(1); // a.jpg, the older one, answered second
		assertEquals(HttpServletResponse.SC_OK, put("/Inbox/", write(flat)).status());

		AlbumInfo stored = (AlbumInfo) Resource.readResource(reader(read("Inbox/index.json")));
		assertEquals(Arrays.asList("c.jpg"), imageNames(stored));
	}

	// --- Who may see an inbox. ---

	public void testAnEditorSeesAllOfTheInbox() throws Exception {
		sharedInbox();

		ImageServlet servlet = servlet(AuthMode.WRITES);
		AlbumInfo inbox = album(servlet, "/Inbox/", ALICE_TOKEN);
		assertEquals("Newest first, whoever contributed.",
			Arrays.asList("other2.jpg", "other1.jpg", "bob2.jpg", "bob1.jpg"), imageNames(inbox));
		assertEquals("Reached through ?type=auth, never as a tile (issue #226).",
			Arrays.asList("2026-05-01 Trip"), names(listing(servlet, "/", ALICE_TOKEN)));
	}

	public void testAContributorSeesTheirOwnContributionsAndNothingElse() throws Exception {
		sharedInbox();

		ImageServlet servlet = servlet(AuthMode.WRITES);
		AlbumInfo inbox = album(servlet, "/Inbox/", BOB_TOKEN);
		assertEquals("A device's owner sorts what that device uploaded, see issue #53.",
			Arrays.asList("bob2.jpg", "bob1.jpg"), imageNames(inbox));
		assertEquals("No tile for them either (issue #226).",
			Arrays.asList("2026-05-01 Trip"), names(listing(servlet, "/", BOB_TOKEN)));

		assertEquals(HttpServletResponse.SC_OK, get(servlet, "/Inbox/bob1.jpg", BOB_TOKEN, "tn").status());
		FakeResponse foreign = get(servlet, "/Inbox/other1.jpg", BOB_TOKEN, "tn");
		assertEquals("Somebody else's photograph is not there for them.",
			HttpServletResponse.SC_NOT_FOUND, foreign.status());
		assertEquals(Inboxes.NOT_FOUND, errorMessage(foreign));
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

	// --- What the hash check says about an inbox, see issue #216. ---

	public void testTheSpaceWideCheckNamesTheInboxToAnEditor() throws Exception {
		ImageServlet servlet = indexedInbox();
		assertEquals(present("bob1.jpg", "Inbox/bob1.jpg", "other2.jpg", "Inbox/other2.jpg"),
			check(servlet, TRIP, ALICE_TOKEN, "bob1.jpg", "other2.jpg"));
		assertEquals("At the inbox itself, bare names as ever.",
			present("bob1.jpg", "bob1.jpg", "other2.jpg", "other2.jpg"),
			check(servlet, "/Inbox/", ALICE_TOKEN, "bob1.jpg", "other2.jpg"));
	}

	public void testAContributorIsNamedTheirOwnAndToldOnlyThatTheRestIsThere() throws Exception {
		ImageServlet servlet = indexedInbox();
		assertEquals("Somebody else's photograph is present without a path: not uploaded again, not located.",
			present("bob1.jpg", "Inbox/bob1.jpg", "other2.jpg", ""),
			check(servlet, TRIP, BOB_TOKEN, "bob1.jpg", "other2.jpg"));
		assertEquals(present("bob1.jpg", "bob1.jpg", "other2.jpg", ""),
			check(servlet, "/Inbox/", BOB_TOKEN, "bob1.jpg", "other2.jpg"));
	}

	public void testAContributorWithNothingInTheInboxIsNamedNothingThere() throws Exception {
		ImageServlet servlet = indexedInbox();
		assertEquals(present("bob1.jpg", "", "other2.jpg", ""),
			check(servlet, TRIP, CAROL_TOKEN, "bob1.jpg", "other2.jpg"));
		assertEquals("The inbox may be added to, so it may be asked.",
			present("bob1.jpg", "", "other2.jpg", ""),
			check(servlet, "/Inbox/", CAROL_TOKEN, "bob1.jpg", "other2.jpg"));
	}

	public void testAViewerLearnsNoPathInAnInboxAndCannotAskTheInbox() throws Exception {
		ImageServlet servlet = indexedInbox();
		assertEquals(present("bob1.jpg", "", "other2.jpg", ""),
			check(servlet, TRIP, DAVE_TOKEN, "bob1.jpg", "other2.jpg"));

		FakeResponse inbox = checkResponse(servlet, "/Inbox/", DAVE_TOKEN, "bob1.jpg");
		assertEquals("Asked like a read: there is nothing at this address.",
			HttpServletResponse.SC_NOT_FOUND, inbox.status());
		assertEquals(Inboxes.NOT_FOUND, errorMessage(inbox));
	}

	public void testAnAnonymousCallerLearnsNoPathInAnInboxAndCannotAskTheInbox() throws Exception {
		ImageServlet servlet = indexedInbox();
		assertEquals(present("bob1.jpg", "", "other2.jpg", ""),
			check(servlet, TRIP, null, "bob1.jpg", "other2.jpg"));
		assertEquals(HttpServletResponse.SC_NOT_FOUND, checkResponse(servlet, "/Inbox/", null, "bob1.jpg").status());
	}

	public void testAShareLinkLearnsNothingOfAnInbox() throws Exception {
		ImageServlet servlet = indexedInbox();
		String token = shareToken(servlet, "/");
		assertEquals("A link is confined to its folder, which holds none of these.",
			present(), check(servlet, "/", token, "bob1.jpg", "other2.jpg"));

		FakeResponse inbox = checkResponse(servlet, "/Inbox/", token, "bob1.jpg");
		assertEquals(HttpServletResponse.SC_NOT_FOUND, inbox.status());
		assertEquals(Inboxes.NOT_FOUND, errorMessage(inbox));
	}

	/** The inbox of {@link #sharedInbox()}, indexed by a servlet that asks for sign-in to write. */
	private ImageServlet indexedInbox() throws Exception {
		sharedInbox();
		ImageServlet servlet = servlet(AuthMode.WRITES);
		servlet.index().indexNow();
		return servlet;
	}

	/** What the check answers for the given photographs of the inbox, by their names in the inbox. */
	private Map<String, String> check(ImageServlet servlet, String pathInfo, String token, String... inboxNames)
			throws Exception {
		FakeResponse response = checkResponse(servlet, pathInfo, token, inboxNames);
		assertEquals("The check failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		Map<String, String> byHash = new HashMap<>();
		for (String name : inboxNames) {
			byHash.put(sha256(_base.resolve("Inbox").resolve(name).toFile()), name);
		}
		Map<String, String> result = new HashMap<>();
		for (PresentFile present : UploadCheckResult
			.readUploadCheckResult(reader(response.body())).getPresent()) {
			result.put(byHash.get(present.getHash()), present.getName());
		}
		return result;
	}

	private FakeResponse checkResponse(ImageServlet servlet, String pathInfo, String token, String... inboxNames)
			throws Exception {
		StringBuilder body = new StringBuilder("{\"hashes\":[");
		for (int n = 0; n < inboxNames.length; n++) {
			body.append(n == 0 ? "" : ",").append("{\"hash\":\"")
				.append(sha256(_base.resolve("Inbox").resolve(inboxNames[n]).toFile())).append("\"}");
		}
		body.append("]}");
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "check");
		FakeResponse response = new FakeResponse();
		servlet.doPost(request(pathInfo, "application/json", body.toString(), token, parameters), response.response());
		return response;
	}

	/** The expected answer of {@link #check(ImageServlet, String, String, String...)}: pairs of name and answer. */
	private static Map<String, String> present(String... nameAndAnswer) {
		Map<String, String> result = new HashMap<>();
		for (int n = 0; n < nameAndAnswer.length; n += 2) {
			result.put(nameAndAnswer[n], nameAndAnswer[n + 1]);
		}
		return result;
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
		// The very same contents, as an album.
		for (String name : Arrays.asList("a.jpg", "b1.jpg", "b2.jpg")) {
			write("Trip/" + name, Files.readAllBytes(_base.resolve("Inbox").resolve(name)));
		}
		sidecar("Trip", read("Inbox/index.json").replace("\"kind\":\"INBOX\",", ""));

		delete("/Trip/", "b1.jpg");

		assertTrue("An album shows a group by its representative; naming it names the group.",
			new File(trash(), "Trip/b2.jpg").isFile());
		assertTrue(new File(trash(), "Trip/b1.jpg").isFile());
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
		store.addUser(user("carol", CAROL_TOKEN, Roles.CONTRIBUTE));
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

	/** What <code>?type=auth</code> answers the given caller. */
	private static AuthInfo auth(ImageServlet servlet, String token) throws Exception {
		FakeResponse response = get(servlet, "/", token, "auth");
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		return AuthInfo.readAuthInfo(reader(response.body()));
	}

	/** The hash check of the inbox before it exists, asking for one photograph it does not hold. */
	private UploadCheckResult checkUnmade() throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "check");
		FakeResponse response = new FakeResponse();
		_servlet.doPost(request("/Inbox/", "application/json",
			"{\"hashes\":[{\"hash\":\"" + "0".repeat(64) + "\"}]}", null, parameters), response.response());
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		return UploadCheckResult.readUploadCheckResult(reader(response.body()));
	}

	private static final String BOUNDARY = "----valbumInboxBoundary";

	/** Uploads one photograph of the given colour into the given folder, as the app does. */
	private static FakeResponse upload(ImageServlet servlet, String pathInfo, String token, String name, Color color)
			throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\""
			+ name + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
		out.write(jpeg(color));
		out.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
		Map<String, String> headers = new HashMap<>();
		headers.put("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
		if (token != null) {
			headers.put("Authorization", "Bearer " + token);
		}
		FakeResponse response = new FakeResponse();
		servlet.doPut(TestImageServletPut.request(pathInfo, "multipart/form-data; boundary=" + BOUNDARY,
			out.toByteArray(), headers, new HashMap<>()), response.response());
		return response;
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
