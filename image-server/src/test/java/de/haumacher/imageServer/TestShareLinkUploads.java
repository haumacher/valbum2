/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import de.haumacher.imageServer.upload.HashCache;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An upload through a share link is stored so that the link shows it, see issue #214.
 *
 * <p>
 * The link's floor and privacy level are recorded beside the upload's attribution in the hash
 * sidecar, and the loader describes the new photograph by them until the album's
 * <code>index.json</code> lists it.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestShareLinkUploads extends PersonalLinkTestCase {

	/** "At least Good". */
	private static final int GOOD = 1;

	// --- The rating. ---

	public void testAnUploadThroughAGoodLinkIsRatedGoodAndShownAtOnce() throws Exception {
		String token = issue("alice", SharingFixture.ZOO, "Party", "", Privacy.PUBLIC, GOOD, Rights.VIEW,
			Rights.CONTRIBUTE);
		byte[] contents = photo("guest");
		assertStored(upload("/", token, "guest.jpg", contents));

		assertEquals("The uploader sees it through the link at once.", GOOD,
			image(album(get("/", "json", token)), "guest.jpg").getRating());

		// Another visitor of the link, on a server that has just started.
		restartServer();
		assertEquals("Another visitor sees it, after a restart too.", GOOD,
			image(album(get("/", "json", token)), "guest.jpg").getRating());
		assertEquals("The owner sees the rating the upload was given.", GOOD,
			image(album(get(ZOO, "json", SharingFixture.ALICE)), "guest.jpg").getRating());

		assertTrue("The original is stored as it came.",
			Arrays.equals(contents, Files.readAllBytes(zoo().resolve("guest.jpg"))));
	}

	public void testALinkWithTheDefaultFloorLeavesTheUploadUnrated() throws Exception {
		String token = issue("alice", SharingFixture.ZOO, "Party", "", Privacy.PUBLIC, 0, Rights.VIEW,
			Rights.CONTRIBUTE);
		assertStored(upload("/", token, "guest.jpg", photo("guest")));

		assertEquals(0, image(album(get(ZOO, "json", SharingFixture.ALICE)), "guest.jpg").getRating());
		assertNotNull(image(album(get("/", "json", token)), "guest.jpg"));
	}

	public void testALinkShowingEveryPhotoNeverLowersTheRating() throws Exception {
		String token = issue("alice", SharingFixture.ZOO, "Party", "", Privacy.PUBLIC, NO_RATING_LIMIT,
			Rights.VIEW, Rights.CONTRIBUTE);
		assertStored(upload("/", token, "guest.jpg", photo("guest")));

		assertEquals(0, image(album(get(ZOO, "json", SharingFixture.ALICE)), "guest.jpg").getRating());
	}

	public void testAMembersUploadIsUnchanged() throws Exception {
		assertStored(upload(ZOO, SharingFixture.BOB, "bobs.jpg", photo("bobs")));

		ImagePart image = image(album(get(ZOO, "json", SharingFixture.ALICE)), "bobs.jpg");
		assertEquals(0, image.getRating());
		assertEquals(Privacy.PUBLIC, image.getPrivacy());
		String hashes = hashes();
		assertFalse(hashes, hashes.contains("linkMinRating"));
		assertFalse(hashes, hashes.contains("linkMaxPrivacy"));
	}

	public void testADuplicateIsNotRerated() throws Exception {
		byte[] contents = photo("twice");
		assertStored(upload(ZOO, SharingFixture.BOB, "bobs.jpg", contents));
		String token = issue("alice", SharingFixture.ZOO, "Party", "", Privacy.PUBLIC, GOOD, Rights.VIEW,
			Rights.CONTRIBUTE);

		FakeResponse again = upload("/", token, "guests.jpg", contents);
		assertEquals(again.body(), HttpServletResponse.SC_OK, again.status());
		assertTrue(again.body(), again.body().contains("\"status\":\"" + ImageServlet.PRESENT + "\""));

		assertEquals("Nothing was written, so nothing is re-rated.", 0,
			image(album(get(ZOO, "json", SharingFixture.ALICE)), "bobs.jpg").getRating());
	}

	public void testTheOwnerChangesTheRatingAfterwards() throws Exception {
		String token = issue("alice", SharingFixture.ZOO, "Party", "", Privacy.PUBLIC, GOOD, Rights.VIEW,
			Rights.CONTRIBUTE);
		assertStored(upload("/", token, "guest.jpg", photo("guest")));

		AlbumInfo album = album(get(ZOO, "json", SharingFixture.ALICE));
		image(album, "guest.jpg").setRating(-1);
		assertEquals(HttpServletResponse.SC_OK, put(ZOO, json(album), SharingFixture.ALICE).status());

		restartServer();
		assertEquals("Applied once, at the upload: the owner's rating stands.", -1,
			image(album(get(ZOO, "json", SharingFixture.ALICE)), "guest.jpg").getRating());
	}

	public void testTheRaisedRatingIsStoredByTheFirstSidecarWrite() throws Exception {
		String token = issue("alice", SharingFixture.ZOO, "Party", "", Privacy.PUBLIC, GOOD, Rights.VIEW,
			Rights.CONTRIBUTE);
		assertStored(upload("/", token, "guest.jpg", photo("guest")));

		// The owner writes the album without touching the photograph.
		AlbumInfo album = album(get(ZOO, "json", SharingFixture.ALICE));
		album.setSubTitle("Edited");
		assertEquals(HttpServletResponse.SC_OK, put(ZOO, json(album), SharingFixture.ALICE).status());

		String sidecar = new String(Files.readAllBytes(zoo().resolve("index.json")), StandardCharsets.UTF_8);
		assertTrue(sidecar, sidecar.contains("\"guest.jpg\""));
		AlbumInfo stored = (AlbumInfo) Resource.readResource(reader(sidecar));
		assertEquals(GOOD, image(stored, "guest.jpg").getRating());
	}

	public void testARawPairThroughTheLinkIsOneEntryRatedGood() throws Exception {
		String token = issue("alice", SharingFixture.ZOO, "Party", "", Privacy.PUBLIC, GOOD, Rights.VIEW,
			Rights.CONTRIBUTE);
		LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
		files.put("IMG_1.JPG", RawFixtures.withExif(RawFixtures.stored(120, 80, 1), RawFixtures.exifTiff(1)));
		files.put("IMG_1.CR2", RawFixtures.cr2(120, 80, 1));
		assertStored(upload("/", token, files));

		AlbumInfo album = album(get("/", "json", token));
		ImagePart pair = image(album, "IMG_1.JPG");
		assertEquals("IMG_1.CR2", pair.getRaw());
		assertEquals(GOOD, pair.getRating());
		assertEquals("One entry.", 1, count(album, "IMG_1"));
	}

	// --- The privacy level. ---

	public void testAnUploadThroughAPublicLinkIsShownByIt() throws Exception {
		String token = issue("alice", SharingFixture.ZOO, "Party", "", Privacy.PUBLIC, 0, Rights.VIEW,
			Rights.CONTRIBUTE);
		assertStored(upload("/", token, "guest.jpg", photo("guest")));

		assertEquals(Privacy.PUBLIC, image(album(get("/", "json", token)), "guest.jpg").getPrivacy());
		assertTrue(hashes(), hashes().contains("\"linkMaxPrivacy\":0"));
	}

	public void testTheLimitsRaiseTheRatingAndCapThePrivacyOfANewPart() throws Exception {
		Path folder = zoo();
		HashCache hashes = new HashCache(folder.toFile());
		File file = folder.resolve("public.jpg").toFile();
		hashes.put(file, HashCache.sha256(file), new HashCache.Attribution("token:x", "Party", GOOD, Privacy.PUBLIC));
		hashes.flush();

		ImagePart low = ImagePart.create().setName("public.jpg").setRating(-1).setPrivacy(Privacy.PRIVATE);
		Contributors.applyLinkLimits(Arrays.asList(low), folder.toFile());
		assertEquals(GOOD, low.getRating());
		assertEquals(Privacy.PUBLIC, low.getPrivacy());

		ImagePart high = ImagePart.create().setName("public.jpg").setRating(2);
		Contributors.applyLinkLimits(Arrays.asList(high), folder.toFile());
		assertEquals("Never lowered.", 2, high.getRating());

		ImagePart other = ImagePart.create().setName("members.jpg").setPrivacy(Privacy.MEMBERS);
		Contributors.applyLinkLimits(Arrays.asList(other), folder.toFile());
		assertEquals("A file not uploaded through a link is untouched.", Privacy.MEMBERS, other.getPrivacy());
		assertEquals(0, other.getRating());
	}

	// --- A personal link, and an inbox. ---

	public void testAPersonalLinksUploadIsShownToTheOtherRecipient() throws Exception {
		FakeResponse response = share(ZOO, SharingFixture.ALICE,
			personalBody("Party", email("Tante Petra", PETRA), phone("Klaus", KLAUS))
				.replace("\"minRating\":-2", "\"minRating\":" + GOOD));
		assertEquals(response.body(), 200, response.status());
		ShareLinkCreated created = created(response);

		String petra = tokenOf(created, "Tante Petra");
		assertStored(uploadAs("/", petra, credential(petra), "petras.jpg", photo("petras")));

		String klaus = tokenOf(created, "Klaus");
		FakeResponse seen = getAs("/", "json", klaus, credential(klaus));
		assertEquals(seen.body(), 200, seen.status());
		assertEquals(GOOD, image(album(seen), "petras.jpg").getRating());
	}

	// --- An inbox does not exist for a link, for writing either (issue #215). ---

	public void testALinkCannotUploadIntoAnInbox() throws Exception {
		Path inbox = inbox();
		String token = yearToken();

		FakeResponse response = upload("/Box/", token, "guest.jpg", photo("guest"));
		assertHidden(response);
		assertUntouched(inbox);

		assertEquals("A read answers the same.", HttpServletResponse.SC_NOT_FOUND,
			get("/Box/", "json", token).status());
	}

	public void testALinkCannotUploadASingleImageIntoAnInbox() throws Exception {
		Path inbox = inbox();
		String token = yearToken();

		assertHidden(upload("/Box/guest.jpg", token, "guest.jpg", photo("guest")));
		assertUntouched(inbox);
	}

	public void testAPersonalLinkCannotUploadIntoAnInbox() throws Exception {
		Path inbox = inbox();
		FakeResponse response = share("/" + SharingFixture.YEAR + "/", SharingFixture.ALICE,
			personalBody("Party", email("Tante Petra", PETRA)));
		assertEquals(response.body(), 200, response.status());
		String petra = tokenOf(created(response), "Tante Petra");
		String credential = credential(petra);
		assertStored(uploadAs("/", petra, credential, "petras.jpg", photo("petras")));

		assertHidden(uploadAs("/Box/", petra, credential, "petras.jpg", photo("petras")));
		assertHidden(uploadAs("/Box/petras.jpg", petra, credential, "petras.jpg", photo("petras")));
		assertUntouched(inbox);
	}

	public void testALinkMadeOnAFolderThatBecameAnInboxCannotUploadIntoIt() throws Exception {
		Path inbox = _base.resolve(SharingFixture.YEAR).resolve("Box");
		Files.createDirectories(inbox);
		Files.write(inbox.resolve("index.json"),
			"[\"AlbumInfo\",{\"title\":\"Box\",\"parts\":[]}]".getBytes(StandardCharsets.UTF_8));
		String token = issue("alice", SharingFixture.YEAR + "/Box", "Party", "", Privacy.PUBLIC, GOOD,
			Rights.VIEW, Rights.CONTRIBUTE);
		makeInbox();

		assertHidden(upload("/", token, "guest.jpg", photo("guest")));
		assertUntouched(inbox);
	}

	public void testTheHashCheckOfALinkDoesNotRevealAnInbox() throws Exception {
		Path inbox = inbox();
		byte[] contents = photo("sorted");
		assertStored(upload("/" + SharingFixture.YEAR + "/Box/", SharingFixture.ALICE, "sorted.jpg", contents));
		String token = yearToken();

		FakeResponse response = check("/Box/", token, HashCache.sha256(contents));
		assertHidden(response);
		assertFalse(response.body(), response.body().contains("sorted.jpg"));
		assertTrue("The owner's photograph is still there.", Files.exists(inbox.resolve("sorted.jpg")));
	}

	public void testALinkCannotCreateAnAlbumOrWriteASidecarInAnInbox() throws Exception {
		Path inbox = inbox();
		String token = yearToken();

		assertHidden(put("/Box/New/", "[\"AlbumInfo\",{\"title\":\"New\",\"parts\":[]}]", token));
		assertFalse(Files.exists(inbox.resolve("New")));

		assertHidden(put("/Box/", "[\"AlbumInfo\",{\"title\":\"Box\",\"parts\":[]}]", token));
		assertHidden(put("/Box/@eaDir/", "[\"AlbumInfo\",{\"title\":\"x\",\"parts\":[]}]", token));
		assertUntouched(inbox);
	}

	public void testALinkCannotMoveIntoOrOutOfAnInbox() throws Exception {
		Path inbox = inbox();
		String token = yearToken();
		assertStored(upload("/" + SharingFixture.YEAR + "/Box/", SharingFixture.ALICE, "sorted.jpg",
			photo("sorted")));

		// Into it: a link may move nothing anywhere (#53), so the target is never looked at, and
		// the answer is the one a target that does not exist gets.
		FakeResponse into = move("/", "Box", token, "2024-05-01 Zoo");
		FakeResponse nowhere = move("/", "Nope", token, "2024-05-01 Zoo");
		assertEquals(into.body(), nowhere.status(), into.status());
		assertEquals(nowhere.body(), into.body());
		assertTrue(Files.isDirectory(zoo()));

		// Out of it, and every other action addressed at it: not there.
		assertHidden(move("/Box/", "", token, "sorted.jpg"));
		Map<String, String> delete = new HashMap<>();
		delete.put("action", "delete");
		assertHidden(post("/Box/", "{\"target\":\"\",\"names\":[{\"name\":\"sorted.jpg\"}]}", token, delete));
		assertTrue(Files.exists(inbox.resolve("sorted.jpg")));
	}

	public void testALinkCannotMakeTheInboxByAFirstUpload() throws Exception {
		makeInbox();
		String token = yearToken();
		Path inbox = _base.resolve(SharingFixture.YEAR).resolve("Box");

		assertHidden(upload("/Box/", token, "guest.jpg", photo("guest")));
		assertHidden(upload("/Box/guest.jpg", token, "guest.jpg", photo("guest")));
		assertHidden(check("/Box/", token, HashCache.sha256(photo("guest"))));
		assertHidden(get("/Box/", "json", token));
		assertFalse("The inbox is made by the first upload of a member, never of a link.", Files.exists(inbox));

		assertStored(upload("/" + SharingFixture.YEAR + "/Box/", SharingFixture.BOB, "bobs.jpg", photo("bobs")));
		assertTrue(Files.exists(inbox.resolve("bobs.jpg")));
	}

	public void testTheRefusalIsTheAnswerOfAFolderThatDoesNotExist() throws Exception {
		inbox();
		String token = yearToken();
		Files.createDirectories(_base.resolve(SharingFixture.YEAR).resolve("Empty"));

		assertSameShape(upload("/Box/", token, "guest.jpg", photo("guest")),
			upload("/Nope/", token, "guest.jpg", photo("guest")));
		assertSameShape(upload("/Box/guest.jpg", token, "guest.jpg", photo("guest")),
			upload("/Nope/guest.jpg", token, "guest.jpg", photo("guest")));
		assertSameShape(check("/Box/", token, HashCache.sha256(photo("guest"))),
			check("/Nope/", token, HashCache.sha256(photo("guest"))));
		assertSameShape(put("/Box/New/", "[\"AlbumInfo\",{\"title\":\"New\",\"parts\":[]}]", token),
			put("/Nope/New/", "[\"AlbumInfo\",{\"title\":\"New\",\"parts\":[]}]", token));
		assertSameShape(get("/Box/", "json", token), get("/Nope/", "json", token));
	}

	public void testAContributingMemberStillUploadsIntoAnInbox() throws Exception {
		Path inbox = inbox();

		assertStored(upload("/" + SharingFixture.YEAR + "/Box/", SharingFixture.BOB, "bobs.jpg", photo("bobs")));
		assertTrue(Files.exists(inbox.resolve("bobs.jpg")));
		assertNotNull(image(album(get("/" + SharingFixture.YEAR + "/Box/", "json", SharingFixture.BOB)),
			"bobs.jpg"));
	}

	// --- Helpers. ---

	/** The inbox of the space below the year folder, as its space.json may name one (issue #226). */
	private Path inbox() throws Exception {
		Path inbox = _base.resolve(SharingFixture.YEAR).resolve("Box");
		Files.createDirectories(inbox);
		Files.write(inbox.resolve("index.json"),
			"[\"AlbumInfo\",{\"title\":\"Box\",\"parts\":[]}]".getBytes(StandardCharsets.UTF_8));
		makeInbox();
		return inbox;
	}

	/** Names the year folder's <code>Box</code> the space's inbox and starts the server anew. */
	private void makeInbox() throws Exception {
		de.haumacher.imageServer.auth.SpaceStore.storeInbox(_base, SharingFixture.YEAR + "/Box");
		restartServer();
	}

	/** A link on the year folder, which may contribute. */
	private String yearToken() throws Exception {
		return issue("alice", SharingFixture.YEAR, "Party", "", Privacy.PUBLIC, GOOD, Rights.VIEW,
			Rights.CONTRIBUTE);
	}

	private FakeResponse check(String pathInfo, String token, String hash) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "check");
		return post(pathInfo, "{\"hashes\":[{\"hash\":\"" + hash + "\"}]}", token, parameters);
	}

	/** The answer every address of an inbox gives a link: there is nothing. */
	private static void assertHidden(FakeResponse response) throws Exception {
		assertEquals(response.body(), HttpServletResponse.SC_NOT_FOUND, response.status());
		assertEquals(Inboxes.NOT_FOUND, errorMessage(response));
	}

	/** Nothing but the sidecar the test wrote is in the inbox. */
	private static void assertUntouched(Path inbox) throws Exception {
		try (java.util.stream.Stream<Path> files = Files.list(inbox)) {
			assertEquals(Arrays.asList("index.json"),
				files.map(f -> f.getFileName().toString()).filter(n -> !n.startsWith(".vacache")).sorted()
					.collect(java.util.stream.Collectors.toList()));
		}
		String sidecar = new String(Files.readAllBytes(inbox.resolve("index.json")), StandardCharsets.UTF_8);
		assertTrue(sidecar, sidecar.contains("\"parts\":[]"));
	}

	/** Two refusals a caller cannot tell apart but by the words of the message. */
	private static void assertSameShape(FakeResponse inbox, FakeResponse missing) throws Exception {
		assertEquals("inbox: " + inbox.body() + ", missing: " + missing.body(), missing.status(), inbox.status());
		assertEquals(HttpServletResponse.SC_NOT_FOUND, inbox.status());
		assertNotNull(errorMessage(inbox));
		assertNotNull(errorMessage(missing));
	}

	private Path zoo() {
		return _base.resolve(SharingFixture.ZOO);
	}

	private String hashes() throws Exception {
		return new String(Files.readAllBytes(zoo().resolve(HashCache.FILE_NAME)), StandardCharsets.UTF_8);
	}

	private static void assertStored(FakeResponse response) {
		assertEquals("Expected a stored upload, got: " + response.body(), HttpServletResponse.SC_OK,
			response.status());
		assertTrue("Expected a stored upload, got: " + response.body(),
			response.body().contains("\"status\":\"" + ImageServlet.STORED + "\""));
	}

	private static ImagePart image(AlbumInfo album, String name) {
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart && ((ImagePart) part).getName().equals(name)) {
				return (ImagePart) part;
			}
			if (part instanceof ImageGroup) {
				for (ImagePart member : ((ImageGroup) part).getImages()) {
					if (member.getName().equals(name)) {
						return member;
					}
				}
			}
		}
		fail("No image '" + name + "' in " + album.getParts());
		return null;
	}

	private static int count(AlbumInfo album, String prefix) {
		int result = 0;
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart && ((ImagePart) part).getName().startsWith(prefix)) {
				result++;
			}
		}
		return result;
	}

	private static String json(AlbumInfo album) throws Exception {
		StringWriter out = new StringWriter();
		try (de.haumacher.msgbuf.json.JsonWriter json =
			new de.haumacher.msgbuf.json.JsonWriter(new de.haumacher.msgbuf.server.io.WriterAdapter(out))) {
			album.writeTo(json);
		}
		return out.toString();
	}
}
