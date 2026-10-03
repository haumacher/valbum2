/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.MediaSignatures;
import de.haumacher.imageServer.auth.Rights;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.shared.model.MediaUrl;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Test case for the signed media addresses of issue #185.
 *
 * <p>
 * On the web the <code>&lt;video&gt;</code> element fetches its address itself, without the
 * device's bearer. <code>&lt;video&gt;?type=media-url&amp;for=…</code> answers an address carrying a
 * short-lived signature instead, which opens exactly that one file of that one video to a request
 * without any <code>Authorization</code> header — in a space that shows nothing to anonymous
 * callers ({@link AuthMode#ALL}), which is where the report came from.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestMediaUrls extends ShareTestCase {

	private static final File VIDEO_FIXTURE =
		new File("src/test/fixtures/test-album/2005-08-24 Blumen und Fliegen/MVI_0450.mp4");

	private static final long TRANSCODE_TIMEOUT_MS = 120000;

	private static final String ZOO = "/" + SharingFixture.ZOO + "/";

	private static final String CLIP = ZOO + "clip.mp4";

	private static final String PRIVATE_CLIP = ZOO + "private.mp4";

	private static final String INBOX = "Family Inbox";

	@Override
	protected void setUp() throws Exception {
		_authMode = AuthMode.ALL;
		super.setUp();
		Path zoo = _base.resolve(SharingFixture.ZOO);
		Files.write(zoo.resolve("index.json"),
			("[\"AlbumInfo\",{\"title\":\"Zoo\",\"parts\":["
				+ "[\"ImagePart\",{\"name\":\"public.jpg\",\"width\":4,\"height\":3}],"
				+ "[\"ImagePart\",{\"name\":\"clip.mp4\",\"kind\":\"VIDEO\",\"width\":640,\"height\":480}],"
				+ "[\"ImagePart\",{\"name\":\"private.mp4\",\"kind\":\"VIDEO\",\"width\":640,\"height\":480,\"privacy\":2}]"
				+ "]}]").getBytes(StandardCharsets.UTF_8));
		Files.copy(VIDEO_FIXTURE.toPath(), zoo.resolve("clip.mp4"));
		Files.copy(VIDEO_FIXTURE.toPath(), zoo.resolve("private.mp4"));
		// Different contents, or the two would be duplicates of each other.
		Files.write(zoo.resolve("private.mp4"), new byte[] { 0 }, java.nio.file.StandardOpenOption.APPEND);
	}

	// --- Playing. ---

	public void testAMediaAddressPlaysARangeWithoutABearer() throws Exception {
		MediaUrl url = issued(CLIP, "original", SharingFixture.ALICE);
		assertTrue(url.getUrl(),
			url.getUrl().startsWith("/valbum/data/2024/2024-05-01%20Zoo/clip.mp4?media="));
		assertTrue(url.getUrl(), url.getUrl().endsWith(url.getMedia()));
		assertFalse("The device token never appears in the address.", url.getUrl().contains(SharingFixture.ALICE));
		assertFalse(url.getMedia().contains(SharingFixture.ALICE));
		long expires = Instant.parse(url.getExpires()).getEpochSecond() - Instant.now().getEpochSecond();
		assertTrue("Ten minutes: " + expires, expires > 590 && expires <= 600);

		FakeResponse played = media(CLIP, null, url.getMedia(), "bytes=0-99");

		assertEquals(played.body(), HttpServletResponse.SC_PARTIAL_CONTENT, played.status());
		assertEquals("video/mp4", played.contentType());
		long length = VIDEO_FIXTURE.length();
		assertEquals("bytes 0-99/" + length, played.header("Content-Range"));
		assertEquals("private, no-store", played.header("Cache-Control"));
		assertTrue(Arrays.equals(Arrays.copyOf(Files.readAllBytes(VIDEO_FIXTURE.toPath()), 100), played.bodyBytes()));

		// The address works as long as it lives: the browser keeps asking for ranges while it plays.
		FakeResponse later = media(CLIP, null, url.getMedia(), "bytes=1000-1999");
		assertEquals(HttpServletResponse.SC_PARTIAL_CONTENT, later.status());
	}

	public void testWithoutTheSignatureTheSameRequestIsRefused() throws Exception {
		FakeResponse plain = media(CLIP, null, null, "bytes=0-99");

		assertEquals("The report: a space closed to anonymous callers refuses the element's fetch.",
			HttpServletResponse.SC_UNAUTHORIZED, plain.status());
	}

	public void testTheRenditionPlaysWithRanges() throws Exception {
		MediaUrl url = issued(CLIP, "video", SharingFixture.DAVE);
		assertTrue(url.getUrl(), url.getUrl().contains("clip.mp4?type=video&media="));

		FakeResponse first = media(CLIP, "video", url.getMedia(), "bytes=0-0");
		assertEquals("The signed request is answered as the device would be: the rendition is being made.",
			HttpServletResponse.SC_ACCEPTED, first.status());
		assertTrue("The transcoder did not finish in time.", servlet().videos().awaitQueue(TRANSCODE_TIMEOUT_MS));

		FakeResponse played = media(CLIP, "video", url.getMedia(), "bytes=0-99");
		assertEquals(played.body(), HttpServletResponse.SC_PARTIAL_CONTENT, played.status());
		assertEquals("video/mp4", played.contentType());
		assertTrue(played.header("Content-Range"), played.header("Content-Range").startsWith("bytes 0-99/"));
	}

	// --- Nothing else. ---

	public void testItOpensNothingElse() throws Exception {
		String media = issued(CLIP, "original", SharingFixture.ALICE).getMedia();

		assertMediaRefused("another file", media(PRIVATE_CLIP, null, media, null));
		assertMediaRefused("a photograph", media(ZOO + "public.jpg", null, media, null));
		assertMediaRefused("the listing", media(ZOO, null, media, null));
		assertMediaRefused("the listing as JSON", media(ZOO, "json", media, null));
		assertMediaRefused("the description", media(CLIP, "json", media, null));
		assertMediaRefused("the thumbnail", media(CLIP, "tn", media, null));
		assertMediaRefused("another kind", media(CLIP, "video", media, null));
		assertMediaRefused("the teaser", media(CLIP, "teaser", media, null));
		assertMediaRefused("who am I", media("/", "auth", media, null));
		assertMediaRefused("the users", media("/", "users", media, null));
		assertMediaRefused("another address for the same file", media("/2024/../" + SharingFixture.ZOO + "/clip.mp4",
			null, media, null));
	}

	public void testAnotherKindIsSignedApart() throws Exception {
		String video = issued(CLIP, "video", SharingFixture.ALICE).getMedia();

		assertMediaRefused("the original with the rendition's signature", media(CLIP, null, video, null));
	}

	// --- Expired, signed out, tampered. ---

	public void testAnExpiredAddressIsGone() throws Exception {
		String media = issued(CLIP, "original", SharingFixture.ALICE).getMedia();
		String device = media.split("\\.")[1];
		// Signed with the very secret the server made, one second in the past.
		String expired = new MediaSignatures(_base).sign(CLIP, MediaSignatures.Kind.ORIGINAL, MediaSignatures.DEVICE,
			device, Instant.now().getEpochSecond() - 1);

		FakeResponse response = media(CLIP, null, expired, "bytes=0-99");

		assertEquals(HttpServletResponse.SC_GONE, response.status());
		assertEquals(AuthService.MEDIA_EXPIRED, errorMessage(response));
	}

	public void testAnAddressOfASignedOutDeviceIsRefused() throws Exception {
		String media = issued(CLIP, "original", SharingFixture.BOB).getMedia();
		assertEquals(HttpServletResponse.SC_PARTIAL_CONTENT, media(CLIP, null, media, "bytes=0-9").status());

		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "unpair");
		String id = new UserStore(_base).getUser("bob").getDevices().get(0).getId();
		FakeResponse unpaired = post("/", "{\"id\":\"" + id + "\"}", SharingFixture.BOB, parameters);
		assertEquals(unpaired.body(), HttpServletResponse.SC_OK, unpaired.status());

		FakeResponse response = media(CLIP, null, media, "bytes=0-9");
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status());
		assertEquals(AuthService.MEDIA_SIGNED_OUT, errorMessage(response));
	}

	public void testATamperedAddressIsRefused() throws Exception {
		String media = issued(CLIP, "original", SharingFixture.ALICE).getMedia();
		String[] parts = media.split("\\.");

		String mac = parts[3];
		char last = mac.charAt(0);
		String flipped = (last == 'A' ? 'B' : 'A') + mac.substring(1);
		assertMediaRefused("a changed mac", media(CLIP, null, parts[0] + "." + parts[1] + "." + parts[2] + "." + flipped, null));

		assertMediaRefused("a later expiry",
			media(CLIP, null, parts[0] + "." + parts[1] + "." + (Long.parseLong(parts[2]) + 3600) + "." + mac, null));

		String carol = new UserStore(_base).getUser("carol").getDevices().get(0).getId();
		assertMediaRefused("another device", media(CLIP, null, parts[0] + "." + carol + "." + parts[2] + "." + mac, null));

		assertMediaRefused("garbage", media(CLIP, null, "not-a-signature", null));
		assertMediaRefused("empty", media(CLIP, null, "", null));
	}

	public void testASignatureOfAnotherSpaceIsRefused() throws Exception {
		String media = issued(CLIP, "original", SharingFixture.ALICE).getMedia();
		Path other = Files.createTempDirectory("valbum-media-other");
		try {
			String device = media.split("\\.")[1];
			String foreign = new MediaSignatures(other).sign(CLIP, MediaSignatures.Kind.ORIGINAL,
				MediaSignatures.DEVICE, device, Instant.now().getEpochSecond() + 600);
			assertMediaRefused("another secret", media(CLIP, null, foreign, null));
		} finally {
			Files.walk(other).sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
	}

	// --- The rights at issue time. ---

	/**
	 * <code>view</code> without <code>download</code> gets the rendition and the teaser, never the
	 * original.
	 *
	 * <p>
	 * Since issue #83 no member of a space is such a caller — the role <code>view</code> holds
	 * <code>download</code> as well (see {@link de.haumacher.imageServer.auth.Roles}) — so the one
	 * caller that is, is a share link cut to <code>view</code> alone.
	 * </p>
	 */
	public void testViewWithoutDownloadGetsTheRenditionButNotTheOriginal() throws Exception {
		String token = zooToken(Rights.VIEW);

		FakeResponse video = issue("/clip.mp4", "video", token);
		assertEquals(video.body(), HttpServletResponse.SC_OK, video.status());
		FakeResponse teaser = issue("/clip.mp4", "teaser", token);
		assertEquals(teaser.body(), HttpServletResponse.SC_OK, teaser.status());

		FakeResponse original = issue("/clip.mp4", "original", token);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, original.status());
		assertEquals(AuthService.DOWNLOAD_REFUSED, errorMessage(original));
	}

	public void testAViewMemberHoldsDownloadAndGetsTheOriginal() throws Exception {
		assertEquals(HttpServletResponse.SC_OK, issue(CLIP, "video", SharingFixture.DAVE).status());
		assertEquals("The role 'view' holds 'download' (issue #83).", HttpServletResponse.SC_OK,
			issue(CLIP, "original", SharingFixture.DAVE).status());
	}

	public void testAPrivateVideoAboveTheClearanceIsRefused() throws Exception {
		FakeResponse refused = issue(PRIVATE_CLIP, "video", SharingFixture.CAROL);
		assertEquals(HttpServletResponse.SC_FORBIDDEN, refused.status());
		assertEquals(ImageServlet.IMAGE_REFUSED, errorMessage(refused));

		FakeResponse admin = issue(PRIVATE_CLIP, "video", SharingFixture.ALICE);
		assertEquals("The administrator sees everything.", HttpServletResponse.SC_OK, admin.status());

		FakeResponse preview = issue(PRIVATE_CLIP, "original", SharingFixture.ALICE, "public");
		assertEquals("The author's own preview of the public view is refused too.",
			HttpServletResponse.SC_FORBIDDEN, preview.status());
	}

	public void testAnInboxTheCallerMayNotSeeIsRefused() throws Exception {
		Path inbox = _base.resolve(INBOX);
		Files.createDirectories(inbox);
		Files.write(inbox.resolve("index.json"),
			("[\"AlbumInfo\",{\"kind\":\"INBOX\",\"title\":\"" + INBOX + "\",\"parts\":["
				+ "[\"ImagePart\",{\"name\":\"arrived.mp4\",\"kind\":\"VIDEO\",\"width\":640,\"height\":480}]"
				+ "]}]").getBytes(StandardCharsets.UTF_8));
		Files.copy(VIDEO_FIXTURE.toPath(), inbox.resolve("arrived.mp4"));
		String path = "/" + INBOX + "/arrived.mp4";

		FakeResponse viewer = issue(path, "video", SharingFixture.DAVE);
		assertEquals(HttpServletResponse.SC_NOT_FOUND, viewer.status());
		assertEquals(Inboxes.NOT_FOUND, errorMessage(viewer));

		FakeResponse editor = issue(path, "video", SharingFixture.CAROL);
		assertEquals("Whoever may edit the inbox sees all of it.", HttpServletResponse.SC_OK, editor.status());
	}

	public void testNobodySignedInGetsNoAddress() throws Exception {
		FakeResponse anonymous = issue(CLIP, "video", null);

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, anonymous.status());
	}

	public void testAnAnonymousCallerOfAnOpenSpaceGetsNoAddress() throws Exception {
		_authMode = AuthMode.WRITES;
		restartServer();
		// Where the space shows an anonymous caller something at all, it fetches the plain address.
		FakeResponse anonymous = issue(CLIP, "video", null);

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, anonymous.status());
		assertEquals(AuthService.MEDIA_URL_ANONYMOUS, errorMessage(anonymous));
	}

	public void testOnlyAVideoHasAPlaybackAddress() throws Exception {
		FakeResponse photo = issue(ZOO + "public.jpg", "teaser", SharingFixture.ALICE);
		assertEquals(HttpServletResponse.SC_NOT_FOUND, photo.status());
		assertEquals(ImageServlet.MEDIA_NOT_A_VIDEO, errorMessage(photo));

		FakeResponse unknown = issue(CLIP, "thumbnail", SharingFixture.ALICE);
		assertEquals(HttpServletResponse.SC_BAD_REQUEST, unknown.status());
		assertEquals(ImageServlet.MEDIA_KIND_UNKNOWN, errorMessage(unknown));
	}

	public void testAPhotographsOriginalIsDownloadedFromASignedAddress() throws Exception {
		// The browser's own download of one original, see issue #209.
		MediaUrl url = issued(ZOO + "public.jpg", "original", SharingFixture.ALICE);
		assertTrue(url.getUrl(), url.getUrl().startsWith("/valbum/data/2024/2024-05-01%20Zoo/public.jpg?media=d."));

		Map<String, String> parameters = new HashMap<>();
		parameters.put(MediaSignatures.PARAMETER, url.getMedia());
		parameters.put(ImageServlet.DOWNLOAD_PARAMETER, ImageServlet.DOWNLOAD_PARAMETER_VALUE);
		FakeResponse saved = new FakeResponse();
		servlet().doGet(TestImageServletPut.request(ZOO + "public.jpg", null, new byte[0], new HashMap<>(), parameters),
			saved.response());

		assertEquals(saved.body(), HttpServletResponse.SC_OK, saved.status());
		assertEquals("attachment; filename=\"public.jpg\"; filename*=UTF-8''public.jpg",
			saved.header("Content-Disposition"));
		assertTrue(Arrays.equals(Files.readAllBytes(_base.resolve(SharingFixture.ZOO).resolve("public.jpg")),
			saved.bodyBytes()));
		assertEquals("Without download=1 the original is answered as ever.", null,
			media(ZOO + "public.jpg", null, url.getMedia(), null).header("Content-Disposition"));
	}

	// --- Share links. ---

	public void testAShareLinkGetsAnAddressInsideItsFolder() throws Exception {
		String token = zooToken(Rights.VIEW);
		String id = idOf(token);

		MediaUrl url = issued("/clip.mp4", "teaser", token);
		assertTrue(url.getUrl(), url.getUrl().startsWith("/valbum/data/clip.mp4?type=teaser&media=s."));
		assertFalse("The link's token never appears in the address.", url.getUrl().contains(token));

		FakeResponse played = media("/clip.mp4", "teaser", url.getMedia(), "bytes=0-0");
		assertEquals("Served as the link would be: the teaser is being made.",
			HttpServletResponse.SC_ACCEPTED, played.status());

		FakeResponse original = issue("/clip.mp4", "original", token);
		assertEquals("A link without download gets no original.", HttpServletResponse.SC_FORBIDDEN, original.status());

		FakeResponse withdrawn = unshare(ZOO, SharingFixture.ALICE, id);
		assertEquals(withdrawn.body(), HttpServletResponse.SC_OK, withdrawn.status());
		assertEquals("A withdrawn link is gone, on the signed address too.", HttpServletResponse.SC_GONE,
			media("/clip.mp4", "teaser", url.getMedia(), "bytes=0-0").status());
	}

	// --- The secret. ---

	public void testTheSecretIsTheServersAlone() throws Exception {
		Path secret = _base.resolve(UserStore.DIRECTORY_NAME).resolve(MediaSignatures.FILE_NAME);
		assertFalse("Made at first use.", Files.exists(secret));

		MediaUrl url = issued(CLIP, "original", SharingFixture.ALICE);

		assertTrue(Files.exists(secret));
		String contents = new String(Files.readAllBytes(secret), StandardCharsets.US_ASCII).trim();
		assertFalse("Never answered.", url.getUrl().contains(contents) || url.getMedia().contains(contents));
		try {
			assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(secret)));
		} catch (UnsupportedOperationException ex) {
			// Not a POSIX file system.
		}

		// A restart reads it again, so an address outlives the process that made it.
		restartServer();
		assertEquals(HttpServletResponse.SC_PARTIAL_CONTENT, media(CLIP, null, url.getMedia(), "bytes=0-9").status());
		assertEquals(contents, new String(Files.readAllBytes(secret), StandardCharsets.US_ASCII).trim());
	}

	// --- Helpers. ---

	private FakeResponse issue(String pathInfo, String kind, String token) throws Exception {
		return issue(pathInfo, kind, token, null);
	}

	private FakeResponse issue(String pathInfo, String kind, String token, String viewAs) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", ImageServlet.MEDIA_URL_TYPE);
		parameters.put(ImageServlet.MEDIA_FOR_PARAMETER, kind);
		if (viewAs != null) {
			parameters.put("viewAs", viewAs);
		}
		Map<String, String> headers = new HashMap<>();
		if (token != null) {
			headers.put("Authorization", "Bearer " + token);
		}
		FakeResponse response = new FakeResponse();
		servlet().doGet(TestImageServletPut.request(pathInfo, null, new byte[0], headers, parameters),
			response.response());
		return response;
	}

	private MediaUrl issued(String pathInfo, String kind, String token) throws Exception {
		FakeResponse response = issue(pathInfo, kind, token);
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		assertEquals("no-store", response.header("Cache-Control"));
		return MediaUrl.readMediaUrl(reader(body(response)));
	}

	/** A request as the video element sends it: no bearer, the signature in the query. */
	private FakeResponse media(String pathInfo, String type, String media, String range) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		if (type != null) {
			parameters.put("type", type);
		}
		if (media != null) {
			parameters.put(MediaSignatures.PARAMETER, media);
		}
		Map<String, String> headers = new HashMap<>();
		if (range != null) {
			headers.put("Range", range);
		}
		FakeResponse response = new FakeResponse();
		servlet().doGet(TestImageServletPut.request(pathInfo, null, new byte[0], headers, parameters),
			response.response());
		return response;
	}

	private static void assertMediaRefused(String what, FakeResponse response) throws Exception {
		assertEquals(what + ": " + response.body(), HttpServletResponse.SC_UNAUTHORIZED, response.status());
		assertEquals(what, AuthService.MEDIA_REFUSED, errorMessage(response));
	}
}
