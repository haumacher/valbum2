/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.ContactStore;
import de.haumacher.imageServer.auth.MediaSignatures;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.MediaUrl;
import de.haumacher.imageServer.shared.model.ShareLinkCreated;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Personal share links (issue #198) composed with what was there before them: signed media
 * addresses, renamed folders, moves, privacy limits, withdrawn and expired links, a removed member,
 * and a credential that renews itself.
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestPersonalLinksProbe extends PersonalLinkTestCase {

	private static final File VIDEO_FIXTURE =
		new File("src/test/fixtures/test-album/2005-08-24 Blumen und Fliegen/MVI_0450.mp4");

	// --- Signed media addresses, issue #185. ---

	public void testAContactSessionSignsMediaAsItself() throws Exception {
		Path zoo = _base.resolve(SharingFixture.ZOO);
		Files.write(zoo.resolve("index.json"), ("[\"AlbumInfo\",{\"title\":\"Zoo\",\"parts\":["
			+ "[\"ImagePart\",{\"name\":\"public.jpg\",\"width\":4,\"height\":3}],"
			+ "[\"ImagePart\",{\"name\":\"clip.mp4\",\"kind\":\"VIDEO\",\"width\":640,\"height\":480}]"
			+ "]}]").getBytes(StandardCharsets.UTF_8));
		Files.copy(VIDEO_FIXTURE.toPath(), zoo.resolve("clip.mp4"));
		restartServer();
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA));
		String token = tokenOf(created, "Tante Petra");
		String credential = credential(token);

		MediaUrl url = mediaUrl("/clip.mp4", token, credential);
		assertTrue(url.getMedia(), url.getMedia().startsWith(MediaSignatures.CONTACT + "."));
		assertFalse(url.getUrl().contains(token));
		assertFalse(url.getUrl().contains(credential));
		assertEquals(HttpServletResponse.SC_PARTIAL_CONTENT, played("/clip.mp4", url).status());

		FakeResponse withoutCredential = issue("/clip.mp4", token, null);
		assertEquals("Without the credential there is nothing to sign.", HttpServletResponse.SC_UNAUTHORIZED,
			withoutCredential.status());

		postAs(ZOO, "shut-out", "{\"link\":\"" + created.getLink().getId() + "\",\"contact\":\""
			+ contactOf(created, "Tante Petra") + "\",\"shutOut\":true}", SharingFixture.ALICE, null);
		FakeResponse shut = played("/clip.mp4", url);
		assertEquals("The session ended with the shut-out: " + shut.body(), HttpServletResponse.SC_UNAUTHORIZED,
			shut.status());
		assertEquals(AuthService.MEDIA_SIGNED_OUT, errorMessage(shut));
	}

	public void testAMediaSignatureOfAContactFollowsTheLinkAtOnce() throws Exception {
		Path zoo = _base.resolve(SharingFixture.ZOO);
		Files.write(zoo.resolve("index.json"), ("[\"AlbumInfo\",{\"title\":\"Zoo\",\"parts\":["
			+ "[\"ImagePart\",{\"name\":\"clip.mp4\",\"kind\":\"VIDEO\",\"width\":640,\"height\":480}]"
			+ "]}]").getBytes(StandardCharsets.UTF_8));
		Files.copy(VIDEO_FIXTURE.toPath(), zoo.resolve("clip.mp4"));
		restartServer();
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA));
		String token = tokenOf(created, "Tante Petra");
		MediaUrl url = mediaUrl("/clip.mp4", token, credential(token));

		assertEquals(HttpServletResponse.SC_OK, unshare(ZOO, SharingFixture.ALICE, created.getLink().getId()).status());
		FakeResponse deleted = played("/clip.mp4", url);
		assertEquals("A deleted link signs nothing any more (#217).", HttpServletResponse.SC_UNAUTHORIZED,
			deleted.status());
		assertEquals(AuthService.MEDIA_LINK_UNKNOWN, errorMessage(deleted));
	}

	// --- Folders, moves and limits. ---

	public void testARenamedAlbumKeepsItsRecipientsAndTheirSessions() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA));
		String token = tokenOf(created, "Tante Petra");
		String credential = credential(token);

		AlbumInfo album = album(get(ZOO, "json", SharingFixture.ALICE));
		album.setTitle("Zoo trip");
		StringWriter json = new StringWriter();
		try (JsonWriter out = new JsonWriter(new WriterAdapter(json))) {
			album.writeTo(out);
		}
		FakeResponse renamed = put(ZOO, json.toString(), SharingFixture.ALICE);
		assertEquals(renamed.body(), 200, renamed.status());

		FakeResponse shared = getAs("/", "json", token, credential);
		assertEquals(shared.body(), 200, shared.status());
		assertEquals("Zoo trip", album(shared).getTitle());
		assertEquals(1, links(shares("/2024/2024-05-01 Zoo trip/", SharingFixture.ALICE)).getLinks().get(0)
			.getRecipients().size());
	}

	public void testAContactsPhotoKeepsItsContributorWhenItIsMoved() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA));
		String token = tokenOf(created, "Tante Petra");
		String credential = credential(token);
		assertEquals(200, uploadAs("/", token, credential, "petras.jpg", photo("moved")).status());

		FakeResponse moved = move(ZOO, SharingFixture.PUBLIC, SharingFixture.ALICE, "petras.jpg");
		assertEquals(moved.body(), 200, moved.status());

		ImagePart image = image(album(get("/" + SharingFixture.PUBLIC + "/", "json", SharingFixture.ALICE)),
			"petras.jpg");
		assertEquals("contact:" + contactOf(created, "Tante Petra"), image.getContributor());
		assertEquals("Tante Petra", image.getContributorLabel());
	}

	public void testAContactSeesWhatTheLinkShowsAndNothingMore() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA));
		String token = tokenOf(created, "Tante Petra");
		String credential = credential(token);

		AlbumInfo album = album(getAs("/", "json", token, credential));
		assertNotNull(image(album, "public.jpg"));
		for (AlbumPart part : album.getParts()) {
			String name = ((ImagePart) part).getName();
			assertFalse("A public link shows no restricted photo: " + name,
				name.equals("members.jpg") || name.equals("private.jpg"));
		}
		assertEquals(HttpServletResponse.SC_NOT_FOUND, getAs("/../", "json", token, credential).status());
		FakeResponse edit = postAs("/", "place", "{}", token, credential);
		assertTrue(edit.body(), edit.status() == 403);
	}

	public void testAnExpiredPersonalLinkIdentifiesNobody() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA));
		String token = tokenOf(created, "Tante Petra");
		String store = shareStoreContents().replace("\"expires\":\"\"", "\"expires\":\"2020-01-01T00:00:00Z\"");
		restartServer();
		Files.write(new de.haumacher.imageServer.auth.ShareStore(_base).getFile(),
			store.getBytes(StandardCharsets.UTF_8));

		FakeResponse opened = identify(token, true);
		assertEquals(HttpServletResponse.SC_GONE, opened.status());
		assertEquals(AuthService.LINK_EXPIRED, errorMessage(opened));
		assertTrue(new ContactStore(_base).get(contactOf(created, "Tante Petra")).getSessions().isEmpty());
	}

	public void testRemovingTheSharerDeletesTheirPersonalLinks() throws Exception {
		FakeResponse response = sharePersonal(ZOO, SharingFixture.BOB, email("Tante Petra", PETRA));
		assertEquals(response.body(), 200, response.status());
		ShareLinkCreated created = created(response);
		String token = tokenOf(created, "Tante Petra");
		String credential = credential(token);

		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "remove-user");
		assertEquals(200, post("/", "{\"name\":\"bob\"}", SharingFixture.ALICE, parameters).status());

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, getAs("/", "json", token, credential).status());
		assertEquals("The contact stays; the register is the space's.", 1,
			contactList(get("/", "contacts", SharingFixture.ALICE)).getContacts().size());
	}

	public void testNoAnswerEverCarriesASecret() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA), phone("Klaus", KLAUS));
		String petra = tokenOf(created, "Tante Petra");
		String credential = credential(petra);

		String[] answers = { get(ZOO, "shares", SharingFixture.ALICE).body(),
			get("/", "contacts", SharingFixture.ALICE).body(), getAs("/", "auth", petra, credential).body(),
			get("/", "json", petra).body(), get("/", "json", tokenOf(created, "Klaus")).body() };
		for (String answer : answers) {
			assertFalse(answer, answer.contains(petra));
			assertFalse(answer, answer.contains(tokenOf(created, "Klaus")));
			assertFalse(answer, answer.contains(created.getToken()));
			assertFalse(answer, answer.contains(credential));
			assertFalse(answer, answer.contains("tokenHash"));
		}
		String contacts = new String(Files.readAllBytes(new ContactStore(_base).getFile()), StandardCharsets.UTF_8);
		assertFalse(contacts.contains(credential));
		assertFalse("Nothing of it lies outside .valbum.",
			Files.exists(_base.resolve(ContactStore.FILE_NAME)));
	}

	public void testARememberedCredentialIsRenewedOnUseAndADaysIsNot() throws Exception {
		ShareLinkCreated created = created(ZOO, email("Tante Petra", PETRA), phone("Klaus", KLAUS));
		String petra = tokenOf(created, "Tante Petra");
		String klaus = tokenOf(created, "Klaus");
		String remembered = credential(petra);
		FakeResponse day = identify(klaus, false);
		String forADay = de.haumacher.imageServer.shared.model.ContactCredential
			.readContactCredential(reader(body(day))).getCredential();
		restartServer();

		// Ten days pass for both, as the store tells it.
		Path file = new ContactStore(_base).getFile();
		String stored = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
		String earlier = java.time.Instant.now().minus(java.time.Duration.ofDays(10)).toString();
		stored = stored.replaceAll("\"lastUsed\":\"[^\"]*\"", "\"lastUsed\":\"" + earlier + "\"");
		Files.write(file, stored.getBytes(StandardCharsets.UTF_8));
		String klausExpires = new ContactStore(_base).get(contactOf(created, "Klaus")).getSessions().get(0)
			.getExpires();

		assertEquals(200, getAs("/", "json", petra, remembered).status());
		assertEquals(200, getAs("/", "json", klaus, forADay).status());

		ContactStore after = new ContactStore(_base);
		ContactStore.Session session = after.get(contactOf(created, "Tante Petra")).getSessions().get(0);
		long days = java.time.Duration.between(java.time.Instant.now(),
			java.time.Instant.parse(session.getExpires())).toDays();
		assertEquals("Renewed to ninety days.", 89, days);
		assertFalse(session.getLastUsed().equals(earlier));
		assertEquals("A day's credential is not renewed.", klausExpires,
			after.get(contactOf(created, "Klaus")).getSessions().get(0).getExpires());
	}

	public void testAnInboxIsNoPersonalLinkEither() throws Exception {
		Path inbox = _base.resolve("Box");
		Files.createDirectories(inbox);
		Files.write(inbox.resolve("index.json"),
			"[\"AlbumInfo\",{\"title\":\"Box\",\"kind\":\"INBOX\",\"parts\":[]}]".getBytes(StandardCharsets.UTF_8));
		FakeResponse response = sharePersonal("/Box/", SharingFixture.ALICE, email("Tante Petra", PETRA));
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status());
		assertEquals(Inboxes.INBOX_NOT_SHARED, errorMessage(response));
		assertTrue("Nobody was entered for a link that was not made.",
			new ContactStore(_base).getContacts().isEmpty());
	}

	// --- Helpers. ---

	private FakeResponse issue(String pathInfo, String bearer, String credential) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", ImageServlet.MEDIA_URL_TYPE);
		parameters.put(ImageServlet.MEDIA_FOR_PARAMETER, "original");
		FakeResponse response = new FakeResponse();
		servlet().doGet(TestImageServletPut.request(pathInfo, null, new byte[0], headers(bearer, credential),
			parameters), response.response());
		return response;
	}

	private MediaUrl mediaUrl(String pathInfo, String bearer, String credential) throws Exception {
		FakeResponse response = issue(pathInfo, bearer, credential);
		assertEquals(response.body(), 200, response.status());
		return MediaUrl.readMediaUrl(reader(body(response)));
	}

	private FakeResponse played(String pathInfo, MediaUrl url) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put(MediaSignatures.PARAMETER, url.getMedia());
		Map<String, String> headers = new HashMap<>();
		headers.put("Range", "bytes=0-99");
		FakeResponse response = new FakeResponse();
		servlet().doGet(TestImageServletPut.request(pathInfo, null, new byte[0], headers, parameters),
			response.response());
		return response;
	}

	private static ImagePart image(AlbumInfo album, String name) {
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart && ((ImagePart) part).getName().equals(name)) {
				return (ImagePart) part;
			}
		}
		fail("No image '" + name + "'.");
		return null;
	}
}
