/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.index.SpaceIndex;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.NewsEntry;
import de.haumacher.imageServer.shared.model.NewsInfo;
import de.haumacher.imageServer.shared.model.NewsPhoto;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import de.haumacher.imageServer.upload.HashCache;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The bell of issue #268: what arrived from others, read per entry.
 *
 * <p>
 * The library of {@link SharingFixture}: alice administers the space, bob contributes, carol edits
 * and sees nothing private, dave looks and sees nothing private, eve looks and sees only what is
 * public. A contact uploads through a personal link, bob moves photographs out of the inbox.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestNews extends PersonalLinkTestCase {

	private static final String ALICE = SharingFixture.ALICE;

	private static final String BOB = SharingFixture.BOB;

	private static final String CAROL = SharingFixture.CAROL;

	private static final String DAVE = SharingFixture.DAVE;

	private static final String EVE = SharingFixture.EVE;

	// --- Who has an entry. ---

	public void testAContactsUploadIsOneEntryForEveryMemberWhoMaySeeTheAlbum() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA));
		String token = tokenOf(created, "Tante Petra");
		String credential = credential(token);
		assertEquals(200, uploadAs("/", token, credential, "petra1.jpg", photo("p1")).status());
		assertEquals(200, uploadAs("/", token, credential, "petra2.jpg", photo("p2")).status());

		for (String member : Arrays.asList(ALICE, BOB, CAROL, DAVE, EVE)) {
			NewsInfo news = news(member);
			assertEquals("One entry for " + member + ": " + news, 1, news.getEntries().size());
			NewsEntry entry = news.getEntries().get(0);
			assertEquals(1, news.getCount());
			assertTrue(entry.isUnread());
			assertEquals("Tante Petra", entry.getWho());
			assertEquals(SharingFixture.ZOO, entry.getAlbum());
			assertEquals("Zoo", entry.getTitle());
			assertEquals(Arrays.asList("petra1.jpg", "petra2.jpg"), names(entry));
		}

		FakeResponse refused = getAs("/", ImageServlet.NEWS_TYPE, token, credential);
		assertEquals("The uploader is no member: no bell for them.", HttpServletResponse.SC_FORBIDDEN, refused.status());
		assertEquals(ImageServlet.NEWS_REFUSED, errorMessage(refused));
		assertEquals(HttpServletResponse.SC_FORBIDDEN, get("/", ImageServlet.NEWS_TYPE, null).status());
	}

	public void testOnesOwnUploadIsNoEntry() throws Exception {
		assertEquals(200, upload(ZOO, BOB, "bobs.jpg", photo("bobs")).status());

		assertEquals("Bob's own upload rings nobody's bell but the others'.", 0, news(BOB).getEntries().size());
		assertEquals(1, news(ALICE).getCount());
	}

	public void testAMoveOutOfTheInboxIsOneEntryForTheOthers() throws Exception {
		List<String> names = inbox(BOB, 30);
		assertEquals("Nothing arrived for the others while it is in the inbox.", 0, news(ALICE).getEntries().size());

		FakeResponse moved = move("/" + SpaceStoreInbox.NAME + "/", SharingFixture.ZOO, BOB, names.toArray(new String[0]));
		assertEquals(moved.body(), 200, moved.status());

		assertEquals(0, news(BOB).getEntries().size());
		for (String member : Arrays.asList(ALICE, CAROL, DAVE)) {
			NewsInfo news = news(member);
			assertEquals("One entry for 30 photographs: " + news, 1, news.getEntries().size());
			NewsEntry entry = news.getEntries().get(0);
			assertEquals("bob", entry.getWho());
			assertEquals(30, entry.getPhotos().size());
			assertEquals("In the order of the album.", inAlbumOrder(entry.getAlbum(), names(entry)), names(entry));
		}

		String hashes = new String(Files.readAllBytes(_base.resolve(SharingFixture.ZOO).resolve(HashCache.FILE_NAME)),
			StandardCharsets.UTF_8);
		assertTrue("The move is recorded in the attribution: " + hashes,
			hashes.contains("\"movedBy\":\"user:bob\"") && hashes.contains("\"moved\":"));
	}

	public void testAMoveBetweenAlbumsIsNoNews() throws Exception {
		assertEquals(200, upload("/" + SharingFixture.CAROLS_ALBUM + "/", ALICE, "a.jpg", photo("a")).status());
		NewsInfo before = news(CAROL);
		assertEquals(1, before.getEntries().size());

		FakeResponse moved = move("/" + SharingFixture.CAROLS_ALBUM + "/", SharingFixture.ZOO, ALICE, "a.jpg");
		assertEquals(moved.body(), 200, moved.status());

		NewsInfo after = news(CAROL);
		assertEquals("The upload travels with its photograph; no move entry is added.", 1, after.getEntries().size());
		assertEquals(SharingFixture.ZOO, after.getEntries().get(0).getAlbum());
	}

	// --- Privacy. ---

	public void testALowerClearanceNeverSeesNorCountsWhatIsHiddenFromIt() throws Exception {
		List<String> names = inbox(BOB, 4);
		assertEquals(200, move("/" + SpaceStoreInbox.NAME + "/", SharingFixture.ZOO, BOB,
			names.toArray(new String[0])).status());
		privacy(SharingFixture.ZOO, Privacy.MEMBERS, names.get(0), names.get(1));
		privacy(SharingFixture.ZOO, Privacy.PRIVATE, names.get(2));

		assertEquals(4, news(ALICE).getEntries().get(0).getPhotos().size());
		assertEquals("Nothing private for dave.", 3, news(DAVE).getEntries().get(0).getPhotos().size());
		assertFalse(names(news(DAVE).getEntries().get(0)).contains(names.get(2)));
		assertEquals("Only what is public for eve.", Arrays.asList(names.get(3)), names(news(EVE).getEntries().get(0)));

		privacy(SharingFixture.ZOO, Privacy.MEMBERS, names.get(3));
		assertEquals("An entry of hidden photographs alone is no entry.", 0, news(EVE).getEntries().size());
		assertEquals(0, news(EVE).getCount());
		assertEquals(1, news(DAVE).getCount());
	}

	public void testTheTrashIsNoNews() throws Exception {
		assertEquals(200, upload(ZOO, BOB, "trash.jpg", photo("trash")).status());
		rating(SharingFixture.ZOO, -2, "trash.jpg");

		assertEquals("Not even for alice, who may edit the album.", 0, news(ALICE).getEntries().size());
	}

	// --- Read state. ---

	public void testOpeningTheMenuMarksNothingVisitingMarksOneAndMarkAllMarksAll() throws Exception {
		assertEquals(200, upload(ZOO, BOB, "zoo.jpg", photo("zoo")).status());
		assertEquals(200, upload("/" + SharingFixture.PUBLIC + "/", BOB, "public.jpg", photo("pub")).status());
		assertEquals(200, upload("/" + SharingFixture.CAROLS_ALBUM + "/", BOB, "carol.jpg", photo("car")).status());

		NewsInfo news = news(ALICE);
		assertEquals(3, news.getCount());
		news = news(ALICE);
		assertEquals("Opening the list marks nothing.", 3, news.getCount());

		String zoo = entry(news, SharingFixture.ZOO).getId();
		NewsInfo answered = markRead(ALICE, false, zoo);
		assertEquals("The answer is the bell as it is now.", 2, answered.getCount());
		assertFalse(entry(answered, SharingFixture.ZOO).isUnread());
		assertTrue(entry(answered, SharingFixture.PUBLIC).isUnread());
		assertEquals("Read entries stay, below the unread.", SharingFixture.ZOO,
			answered.getEntries().get(2).getAlbum());

		String second = secondDevice("alice");
		assertEquals("Another device of alice's knows the same.", 2, news(second).getCount());
		assertFalse(entry(news(second), SharingFixture.ZOO).isUnread());
		assertEquals("Nobody else's state changed.", 3, news(CAROL).getCount());

		NewsInfo all = markRead(second, true);
		assertEquals(0, all.getCount());
		assertEquals(3, all.getEntries().size());
		assertEquals(0, news(ALICE).getCount());
	}

	public void testAPhotographArrivingLaterInTheSameBurstIsUnreadAgain() throws Exception {
		assertEquals(200, upload(ZOO, BOB, "one.jpg", photo("one")).status());
		NewsEntry entry = news(ALICE).getEntries().get(0);
		markRead(ALICE, false, entry.getId());
		assertEquals(0, news(ALICE).getCount());

		assertEquals(200, upload(ZOO, BOB, "two.jpg", photo("two")).status());

		NewsInfo news = news(ALICE);
		assertEquals("One burst, one entry.", 1, news.getEntries().size());
		NewsEntry again = news.getEntries().get(0);
		assertEquals("The same entry.", entry.getId(), again.getId());
		assertTrue(again.isUnread());
		Map<String, Boolean> unread = new LinkedHashMap<>();
		for (NewsPhoto photo : again.getPhotos()) {
			unread.put(photo.getName(), Boolean.valueOf(photo.isUnread()));
		}
		assertEquals(Boolean.FALSE, unread.get("one.jpg"));
		assertEquals(Boolean.TRUE, unread.get("two.jpg"));
	}

	public void testABurstEndsAfterTwoHoursOfQuiet() throws Exception {
		long[] now = { System.currentTimeMillis() };
		servlet().setNewsClock(() -> now[0]);
		assertEquals(200, upload(ZOO, BOB, "morning.jpg", photo("m")).status());
		// The upload records the wall clock; a later one is simulated in the sidecar.
		shiftArrival(SharingFixture.ZOO, "morning.jpg", -3L * 60 * 60 * 1000);
		assertEquals(200, upload(ZOO, BOB, "evening.jpg", photo("e")).status());

		assertEquals(2, news(ALICE).getEntries().size());
	}

	public void testWhatIsOlderThanTheWindowIsGone() throws Exception {
		assertEquals(200, upload(ZOO, BOB, "old.jpg", photo("old")).status());
		long[] now = { System.currentTimeMillis() + Arrivals.WINDOW + 60_000 };
		servlet().setNewsClock(() -> now[0]);

		assertEquals(0, news(ALICE).getEntries().size());
	}

	// --- Rebuild and write-through. ---

	public void testARebuildFindsTheSameEntriesAndCountsWhatCameBeforeItAsRead() throws Exception {
		assertEquals(200, upload(ZOO, BOB, "zoo.jpg", photo("zoo")).status());
		List<String> names = inbox(BOB, 3);
		assertEquals(200, move("/" + SpaceStoreInbox.NAME + "/", SharingFixture.PUBLIC, BOB,
			names.toArray(new String[0])).status());
		NewsInfo before = news(ALICE);
		assertEquals(2, before.getCount());
		markRead(CAROL, true);

		servlet().destroy();
		restartServer();
		deleteTree(SpaceIndex.directoryOf(_base));
		Thread.sleep(2);
		servlet().index().indexNow();

		NewsInfo after = news(ALICE);
		assertEquals("The same entries, read from the attribution.", keys(before), keys(after));
		assertEquals("What arrived before the rebuild counts as read.", 0, after.getCount());
		for (NewsEntry entry : after.getEntries()) {
			assertFalse(entry.isUnread());
		}

		assertEquals(200, upload(ZOO, BOB, "later.jpg", photo("later")).status());
		assertEquals("What arrives after it rings again.", 1, news(ALICE).getCount());
	}

	public void testAnUploadIsInTheBellAtOnceWithoutTheBackground() throws Exception {
		// The production behaviour: requests are pure reads, and nothing runs in the background here.
		servlet().cache().setBackground(true);

		assertEquals(200, upload(ZOO, BOB, "now.jpg", photo("now")).status());

		NewsInfo news = news(ALICE);
		assertEquals(1, news.getCount());
		assertEquals(Arrays.asList("now.jpg"), names(news.getEntries().get(0)));
	}

	public void testTheCountAloneIsAnswered() throws Exception {
		assertEquals(200, upload(ZOO, BOB, "a.jpg", photo("a")).status());

		FakeResponse response = get("/", ImageServlet.NEWS_COUNT_TYPE, ALICE);
		assertEquals(response.body(), 200, response.status());
		NewsInfo count = NewsInfo.readNewsInfo(reader(body(response)));
		assertEquals(1, count.getCount());
		assertTrue(count.getEntries().isEmpty());
	}

	// --- Helpers. ---

	/** The name of the inbox of the fixture's space. */
	private static final class SpaceStoreInbox {
		static final String NAME = de.haumacher.imageServer.auth.SpaceStore.DEFAULT_INBOX;
	}

	/** Uploads the given number of photographs into the inbox as the given member, answers their names. */
	private List<String> inbox(String token, int count) throws Exception {
		LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
		List<String> names = new ArrayList<>();
		for (int n = 0; n < count; n++) {
			String name = String.format("IMG_%04d.jpg", Integer.valueOf(n));
			files.put(name, photo("inbox-" + token + "-" + n));
			names.add(name);
		}
		FakeResponse response = upload("/" + SpaceStoreInbox.NAME + "/", token, files);
		assertEquals(response.body(), 200, response.status());
		return names;
	}

	private NewsInfo news(String token) throws Exception {
		FakeResponse response = get("/", ImageServlet.NEWS_TYPE, token);
		assertEquals(response.body(), 200, response.status());
		return NewsInfo.readNewsInfo(reader(body(response)));
	}

	private NewsInfo markRead(String token, boolean all, String... keys) throws Exception {
		StringBuilder body = new StringBuilder("{\"all\":").append(all).append(",\"keys\":[");
		for (int n = 0; n < keys.length; n++) {
			body.append(n == 0 ? "" : ",").append("{\"id\":\"").append(keys[n]).append("\"}");
		}
		body.append("]}");
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", ImageServlet.NEWS_READ_ACTION);
		FakeResponse response = post("/", body.toString(), token, parameters);
		assertEquals(response.body(), 200, response.status());
		return NewsInfo.readNewsInfo(reader(body(response)));
	}

	/** Pairs a second device of the given member and answers its token. */
	private String secondDevice(String name) throws Exception {
		UserStore users = new UserStore(_base);
		String token = users.addDevice(users.getUser(name), "second device");
		restartServer();
		return token;
	}

	private static NewsEntry entry(NewsInfo news, String album) {
		for (NewsEntry entry : news.getEntries()) {
			if (entry.getAlbum().equals(album)) {
				return entry;
			}
		}
		fail("No entry for '" + album + "' in " + news);
		return null;
	}

	private static List<String> names(NewsEntry entry) {
		List<String> result = new ArrayList<>();
		for (NewsPhoto photo : entry.getPhotos()) {
			result.add(photo.getName());
		}
		return result;
	}

	private static List<String> keys(NewsInfo news) {
		List<String> result = new ArrayList<>();
		for (NewsEntry entry : news.getEntries()) {
			result.add(entry.getId());
		}
		result.sort(Comparator.naturalOrder());
		return result;
	}

	/** The given names in the order the album shows them to alice. */
	private List<String> inAlbumOrder(String album, List<String> names) throws Exception {
		List<String> result = new ArrayList<>();
		for (AlbumPart part : album(get("/" + album + "/", "json", ALICE)).getParts()) {
			if (part instanceof ImagePart && names.contains(((ImagePart) part).getName())) {
				result.add(((ImagePart) part).getName());
			}
		}
		return result;
	}

	/** Sets the privacy of the named photographs of the album, as its editor would. */
	private void privacy(String album, int privacy, String... names) throws Exception {
		AlbumInfo info = album(get("/" + album + "/", "json", ALICE));
		for (AlbumPart part : info.getParts()) {
			if (part instanceof ImagePart && Arrays.asList(names).contains(((ImagePart) part).getName())) {
				((ImagePart) part).setPrivacy(privacy);
			}
		}
		FakeResponse stored = put("/" + album + "/", write(info), ALICE);
		assertEquals(stored.body(), 200, stored.status());
	}

	/** Sets the rating of the named photographs of the album, as its editor would. */
	private void rating(String album, int rating, String... names) throws Exception {
		AlbumInfo info = album(get("/" + album + "/", "json", ALICE));
		for (AlbumPart part : info.getParts()) {
			if (part instanceof ImagePart && Arrays.asList(names).contains(((ImagePart) part).getName())) {
				((ImagePart) part).setRating(rating);
			}
		}
		FakeResponse stored = put("/" + album + "/", write(info), ALICE);
		assertEquals(stored.body(), 200, stored.status());
	}

	/** Moves the recorded upload time of the named photograph, and tells the index. */
	private void shiftArrival(String album, String name, long by) throws Exception {
		Path file = _base.resolve(album).resolve(HashCache.FILE_NAME);
		String contents = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
		int at = contents.indexOf("\"" + name + "\"");
		int start = contents.indexOf("\"uploaded\":", at) + "\"uploaded\":".length();
		int end = start;
		while (Character.isDigit(contents.charAt(end))) {
			end++;
		}
		long uploaded = Long.parseLong(contents.substring(start, end));
		contents = contents.substring(0, start) + (uploaded + by) + contents.substring(end);
		Files.write(file, contents.getBytes(StandardCharsets.UTF_8));
		servlet().index().folderChanged(file.getParent().toFile());
	}

	private static String write(AlbumInfo album) throws Exception {
		StringWriter buffer = new StringWriter();
		try (JsonWriter json = new JsonWriter(new WriterAdapter(buffer))) {
			album.writeTo(json);
		}
		return buffer.toString();
	}

	private static void deleteTree(Path root) throws Exception {
		if (!Files.exists(root)) {
			return;
		}
		try (Stream<Path> files = Files.walk(root)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
	}
}
