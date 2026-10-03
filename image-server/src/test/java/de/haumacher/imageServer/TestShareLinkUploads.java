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
import java.util.LinkedHashMap;

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

	public void testAnInboxStaysHiddenFromTheLinkThatUploadsIntoIt() throws Exception {
		Path inbox = _base.resolve(SharingFixture.YEAR).resolve("Box");
		Files.createDirectories(inbox);
		Files.write(inbox.resolve("index.json"),
			"[\"AlbumInfo\",{\"kind\":\"INBOX\",\"title\":\"Box\",\"parts\":[]}]".getBytes(StandardCharsets.UTF_8));
		String token = issue("alice", SharingFixture.YEAR, "Party", "", Privacy.PUBLIC, GOOD, Rights.VIEW,
			Rights.CONTRIBUTE);

		// What an upload into an inbox may do is issue #135's, and unchanged here.
		assertStored(upload("/Box/", token, "guest.jpg", photo("guest")));

		FakeResponse shown = get("/Box/", "json", token);
		assertEquals("The inbox does not exist for a link (#135), whatever it uploaded.",
			HttpServletResponse.SC_NOT_FOUND, shown.status());
		assertEquals(GOOD,
			image(album(get("/" + SharingFixture.YEAR + "/Box/", "json", SharingFixture.ALICE)), "guest.jpg")
				.getRating());
	}

	// --- Helpers. ---

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
