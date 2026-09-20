/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.InviteMode;
import de.haumacher.imageServer.auth.Privacy;
import de.haumacher.imageServer.auth.Ratings;
import de.haumacher.imageServer.auth.ShareStore;
import de.haumacher.imageServer.auth.SpaceMode;
import de.haumacher.imageServer.auth.Spaces;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.ui.Settings;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import de.haumacher.util.servlet.ResourceServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Test case for issue #104: a share link posted in a messenger shows a card.
 *
 * <p>
 * The front door is driven headlessly, exactly as {@link TestInvitationRedirect} drives it: the
 * real {@link ResourceServlet} with the {@link SharePreview} the server wires into it, and the real
 * {@link ImageServlet} of the space beside it, so that a crawler is answered the way a crawler is
 * answered.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestSharePreview extends TestCase {

	/** An album whose stored index picture is one a members' link may show. */
	private static final String ZOO = "Zoo";

	/** An album whose stored index picture is private. */
	private static final String TRIP = "Trip";

	/** An album that holds nothing but a private photo. */
	private static final String VAULT = "Vault";

	/** A folder of albums. */
	private static final String HOLIDAYS = "Holidays";

	/** A title that has to be escaped before it may stand in an attribute. */
	private static final String AWKWARD = "Alice & <Bob> \"2024\"";

	/** The day the {@link #TRIP} album is dated, see the sidecar below. */
	private static final long TRIP_DATE = 1714514400000L;

	/** The day the newest album of {@link #HOLIDAYS} is dated. */
	private static final long ALPS_DATE = 1717192800000L;

	private Path _base;

	private Path _webRoot;

	private Spaces _spaces;

	private ResourceServlet _app;

	private final Map<String, ImageServlet> _data = new HashMap<>();

	private SpaceServlet _front;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-share-preview");
		_webRoot = Files.createTempDirectory("valbum-share-preview-web");
		Files.write(_webRoot.resolve("index.html"),
			("<html><head><base href=\"/\"><title>valbum_ui</title></head><body>app</body></html>")
				.getBytes(StandardCharsets.UTF_8));
		Files.write(_webRoot.resolve("main.dart.js"), "// app".getBytes(StandardCharsets.UTF_8));
	}

	@Override
	protected void tearDown() throws Exception {
		if (_front != null) {
			_front.destroy();
			_front = null;
		} else {
			for (ImageServlet servlet : _data.values()) {
				servlet.destroy();
			}
			if (_app != null) {
				_app.destroy();
			}
		}
		_data.clear();
		_app = null;
		_spaces = null;
		delete(_base);
		delete(_webRoot);
		super.tearDown();
	}

	// --- The tags. ---

	/** What a messenger reads when the link of an album is fetched. */
	public void testThePageOfAShareBaseCarriesTheCard() throws Exception {
		single();
		String token = share("", ZOO, Privacy.MEMBERS, Ratings.MIN);

		FakeResponse page = get("/s/" + token + "/");
		assertEquals(HttpServletResponse.SC_OK, page.status());
		String html = page.body();
		assertTrue("The base is rewritten exactly as before: " + html,
			html.contains("<base href=\"/valbum/s/" + token + "/\">"));
		assertTrue(html, html.contains("<title>Zoo</title>"));
		assertFalse("The application's own title says nothing about the album: " + html,
			html.contains("valbum_ui"));
		assertTrue(html, html.contains("<meta property=\"og:title\" content=\"Zoo\">"));
		assertTrue(html, html.contains("<meta property=\"og:description\" content=\"A day out\">"));
		assertTrue(html, html.contains(
			"<meta property=\"og:url\" content=\"http://example.org/valbum/s/" + token + "/\">"));
		assertTrue(html, html.contains("<meta property=\"og:image\" content=\"http://example.org/valbum/s/"
			+ token + "/cover.jpg\">"));
		assertTrue(html, html.contains("<meta name=\"twitter:card\" content=\"summary_large_image\">"));
	}

	/** The label is the maker's memento (issue #97); the card names the album. */
	public void testTheCardNamesTheAlbumAndNotTheLabel() throws Exception {
		single();
		String token = share("", ZOO, Privacy.MEMBERS, Ratings.MIN, "For the grandparents");

		String html = body(get("/s/" + token + "/"));
		assertTrue(html, html.contains("<meta property=\"og:title\" content=\"Zoo\">"));
		assertFalse("The label never leaves the place where links are managed: " + html,
			html.contains("grandparents"));
	}

	/** A page that carries no title of its own keeps the one the card gives it. */
	public void testAPageWithoutATitleGetsOne() throws Exception {
		Files.write(_webRoot.resolve("index.html"),
			"<html><head><base href=\"/\"></head><body>app</body></html>".getBytes(StandardCharsets.UTF_8));
		single();
		String token = share("", ZOO, Privacy.MEMBERS, Ratings.MIN);

		String html = body(get("/s/" + token + "/"));
		assertTrue(html, html.contains("<title>Zoo</title>"));
	}

	/** A title carrying markup is escaped before it stands in an attribute. */
	public void testAnAwkwardTitleIsEscaped() throws Exception {
		single();
		String token = share("", HOLIDAYS, Privacy.MEMBERS, Ratings.MIN);

		String html = body(get("/s/" + token + "/"));
		assertTrue(html, html.contains(
			"<meta property=\"og:title\" content=\"Alice &amp; &lt;Bob&gt; &quot;2024&quot;\">"));
		assertFalse("Nothing of the title is left unescaped: " + html, html.contains("<Bob>"));
	}

	/** An album without a subtitle is announced by the day it happened. */
	public void testAnAlbumWithoutASubtitleIsDescribedByItsDate() throws Exception {
		single();
		String token = share("", TRIP, Privacy.MEMBERS, Ratings.MIN);

		String html = body(get("/s/" + token + "/"));
		String day = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE.format(
			java.time.Instant.ofEpochMilli(TRIP_DATE).atZone(java.time.ZoneId.systemDefault()).toLocalDate());
		assertTrue("Expected the album's own date: " + html,
			html.contains("<meta property=\"og:description\" content=\"" + day + "\">"));
	}

	/** An invitation is private: it gets no card. */
	public void testAnInvitationBaseIsUntouched() throws Exception {
		single();
		String token = share("", ZOO, Privacy.MEMBERS, Ratings.MIN);

		String html = body(get("/i/" + token + "/"));
		assertFalse("An invitation never says what it opens: " + html, html.contains("og:"));
		assertTrue(html, html.contains("<base href=\"/valbum/i/" + token + "/\">"));
		assertTrue("The page is the one it always was: " + html, html.contains("valbum_ui"));
	}

	/** The context root is no session: the ordinary start page is untouched. */
	public void testTheOrdinaryStartPageIsUntouched() throws Exception {
		single();
		share("", ZOO, Privacy.MEMBERS, Ratings.MIN);

		String html = body(get("/"));
		assertFalse(html, html.contains("og:"));
		assertTrue(html, html.contains("valbum_ui"));
	}

	/** A link nobody issued opens nothing and says nothing; the page is the one it was. */
	public void testAnUnknownTokenGetsNoCard() throws Exception {
		single();

		String html = body(get("/s/nobody-issued-this/"));
		assertFalse(html, html.contains("og:"));
		assertTrue(html, html.contains("<base href=\"/valbum/s/nobody-issued-this/\">"));
	}

	// --- The cover. ---

	/** The cover is answered without any bearer: the token in the path is the authority. */
	public void testTheCoverIsServedAnonymously() throws Exception {
		single();
		String token = share("", ZOO, Privacy.MEMBERS, Ratings.MIN);

		FakeResponse cover = get("/s/" + token + "/cover.jpg");
		assertEquals(cover.body(), HttpServletResponse.SC_OK, cover.status());
		assertEquals("image/jpeg", cover.contentType());
		assertEquals("no-store", cover.header("Cache-Control"));
		assertEquals("Authorization", cover.header("Vary"));
		assertTrue("Expected the preview of the album's index picture.",
			Arrays.equals(preview(ZOO, "members.jpg"), cover.bodyBytes()));
	}

	/** An index picture the link may not show falls back to the first image it may show. */
	public void testAHiddenIndexPictureFallsBackToTheFirstVisibleImage() throws Exception {
		single();
		String token = share("", TRIP, Privacy.PUBLIC, Ratings.MIN);

		FakeResponse cover = get("/s/" + token + "/cover.jpg");
		assertEquals(cover.body(), HttpServletResponse.SC_OK, cover.status());
		assertTrue("The stored index picture is private; the public photo takes its place.",
			Arrays.equals(preview(TRIP, "public.jpg"), cover.bodyBytes()));
	}

	/** A link that may show nothing has no cover, and its page names none. */
	public void testALinkWithoutAVisibleImageHasNoCover() throws Exception {
		single();
		String token = share("", VAULT, Privacy.PUBLIC, Ratings.MIN);

		FakeResponse cover = get("/s/" + token + "/cover.jpg");
		assertEquals(HttpServletResponse.SC_NOT_FOUND, cover.status());
		assertEquals(SharePreview.COVER_NONE, error(cover).getMessage());

		String html = body(get("/s/" + token + "/"));
		assertFalse("Nothing to show, nothing to name: " + html, html.contains("og:image"));
		assertTrue("The rest of the card stands: " + html, html.contains("og:title"));
	}

	/** A rating limit hides a photo from the card exactly as it hides it from the album. */
	public void testARatingLimitDecidesTheCover() throws Exception {
		single();
		String token = share("", HOLIDAYS + "/Rejected", Privacy.MEMBERS, 0);

		FakeResponse cover = get("/s/" + token + "/cover.jpg");
		assertEquals(cover.body(), HttpServletResponse.SC_OK, cover.status());
		assertTrue("The rejected photo is the index picture and is hidden.",
			Arrays.equals(preview(HOLIDAYS + "/Rejected", "kept.jpg"), cover.bodyBytes()));
	}

	/** A shared folder is drawn with the picture of its first child that has one. */
	public void testAFolderIsDrawnWithItsFirstChild() throws Exception {
		single();
		String token = share("", HOLIDAYS, Privacy.MEMBERS, Ratings.MIN);

		FakeResponse cover = get("/s/" + token + "/cover.jpg");
		assertEquals(cover.body(), HttpServletResponse.SC_OK, cover.status());
		assertTrue("Expected the cover of the newest album of the folder.",
			Arrays.equals(preview(HOLIDAYS + "/Alps", "alps.jpg"), cover.bodyBytes()));
	}

	/** A withdrawn link is gone at the cover, too, and its page carries no card. */
	public void testAWithdrawnLinkIsGone() throws Exception {
		single();
		String token = share("", ZOO, Privacy.MEMBERS, Ratings.MIN);
		assertEquals(HttpServletResponse.SC_OK, get("/s/" + token + "/cover.jpg").status());

		auth("").getShares().revoke(auth("").getShares().lookup(token).getId());

		FakeResponse cover = get("/s/" + token + "/cover.jpg");
		assertEquals(HttpServletResponse.SC_GONE, cover.status());
		assertEquals(AuthService.LINK_REVOKED, error(cover).getMessage());

		FakeResponse page = get("/s/" + token + "/");
		assertEquals("The page still serves; the application says what became of the link.",
			HttpServletResponse.SC_OK, page.status());
		assertFalse(page.body(), page.body().contains("og:"));
	}

	/** An expired link is gone at the cover, too. */
	public void testAnExpiredLinkIsGone() throws Exception {
		single();
		String token = share("", ZOO, Privacy.MEMBERS, Ratings.MIN, "",
			java.time.Instant.now().minusSeconds(60).toString());

		FakeResponse cover = get("/s/" + token + "/cover.jpg");
		assertEquals(HttpServletResponse.SC_GONE, cover.status());
		assertEquals(AuthService.LINK_EXPIRED, error(cover).getMessage());
		assertFalse(body(get("/s/" + token + "/")).contains("og:"));
	}

	/** A token nobody issued opens no cover either. */
	public void testAnUnknownTokenHasNoCover() throws Exception {
		single();

		FakeResponse cover = get("/s/nobody-issued-this/cover.jpg");
		assertEquals(HttpServletResponse.SC_GONE, cover.status());
		assertEquals(SharePreview.COVER_UNKNOWN, error(cover).getMessage());
	}

	/** Below an invitation base the name is no cover but a route of the application. */
	public void testAnInvitationHasNoCover() throws Exception {
		single();
		String token = share("", ZOO, Privacy.MEMBERS, Ratings.MIN);

		FakeResponse response = get("/i/" + token + "/cover.jpg");
		assertEquals(HttpServletResponse.SC_OK, response.status());
		assertTrue("An invitation serves no cover; the name is a route of the application: "
			+ response.body(), response.body().contains("valbum_ui"));
	}

	/** The application's own files below a share base are served as they always were. */
	public void testAnAssetBelowAShareBaseIsServed() throws Exception {
		single();
		String token = share("", ZOO, Privacy.MEMBERS, Ratings.MIN);

		FakeResponse asset = get("/s/" + token + "/main.dart.js");
		assertEquals(HttpServletResponse.SC_OK, asset.status());
		assertEquals("// app", asset.body());
	}

	// --- A multi-space server. ---

	/** The card and the cover of a space's link are spelled under that space. */
	public void testASpaceSpellsItsOwnCardAndCover() throws Exception {
		multi("alice", "bob");
		String token = share("alice", ZOO, Privacy.MEMBERS, Ratings.MIN);

		String html = body(get("/alice/s/" + token + "/"));
		assertTrue(html, html.contains("<base href=\"/valbum/alice/s/" + token + "/\">"));
		assertTrue(html, html.contains(
			"<meta property=\"og:url\" content=\"http://example.org/valbum/alice/s/" + token + "/\">"));
		assertTrue(html, html.contains("<meta property=\"og:image\" content=\"http://example.org/valbum/alice/s/"
			+ token + "/cover.jpg\">"));

		FakeResponse cover = get("/alice/s/" + token + "/cover.jpg");
		assertEquals(cover.body(), HttpServletResponse.SC_OK, cover.status());
		assertEquals("image/jpeg", cover.contentType());
	}

	/** A space knows only its own links: another space's token opens nothing there. */
	public void testATokenOfAnotherSpaceIsUnknown() throws Exception {
		multi("alice", "bob");
		String token = share("alice", ZOO, Privacy.MEMBERS, Ratings.MIN);

		FakeResponse cover = get("/bob/s/" + token + "/cover.jpg");
		assertEquals(HttpServletResponse.SC_GONE, cover.status());
		assertEquals(SharePreview.COVER_UNKNOWN, error(cover).getMessage());
		assertFalse(body(get("/bob/s/" + token + "/")).contains("og:"));
	}

	// --- Nothing is written. ---

	/** A card and a cover read; they never write a sidecar and never touch an original. */
	public void testNothingOfTheLibraryIsWritten() throws Exception {
		single();
		String token = share("", ZOO, Privacy.MEMBERS, Ratings.MIN);
		String before = fingerprint(_base);

		assertEquals(HttpServletResponse.SC_OK, get("/s/" + token + "/").status());
		assertEquals(HttpServletResponse.SC_OK, get("/s/" + token + "/cover.jpg").status());
		assertEquals(HttpServletResponse.SC_OK, get("/s/" + token + "/cover.jpg").status());

		assertEquals("Only the generated previews may appear, and they are not the library.", before,
			fingerprint(_base));
	}

	// --- The harness. ---

	/** A single-space server on the base folder. */
	private void single() throws Exception {
		library(_base);
		_spaces = Spaces.detect(_base, SpaceMode.SINGLE, AuthMode.WRITES, InviteMode.MEMBERS);
		_app = new ResourceServlet(_webRoot, Settings.DATA_PREFIX, "s", "i");
		ImageServlet data = new ImageServlet(_base.toFile(), _spaces.single().getAuth());
		data.init(TestSpaces.config());
		_data.put("", data);
		SharePreview preview = new SharePreview(_spaces, _data::get);
		_app.setPageDecorator(preview);
		_app.setSessionResource(preview);
		_app.init(TestSpaces.config());
	}

	/** A multi-space server hosting the given spaces, each with a library of its own. */
	private void multi(String... segments) throws Exception {
		for (String segment : segments) {
			Path root = _base.resolve(segment);
			Files.createDirectories(root.resolve(".valbum"));
			Files.write(root.resolve(".valbum").resolve("space.json"),
				"{\"anonymous\":\"public\"}".getBytes(StandardCharsets.UTF_8));
			library(root);
		}
		_spaces = Spaces.detect(_base, null, AuthMode.WRITES, InviteMode.MEMBERS);
		assertEquals(SpaceMode.MULTI, _spaces.getMode());
		_app = new ResourceServlet(_webRoot, Settings.DATA_PREFIX, "s", "i");
		_app.setBaseSegments(_spaces.segments());
		_front = new SpaceServlet(_spaces, _app);
		SharePreview preview = new SharePreview(_spaces, _front::dataOf);
		_app.setPageDecorator(preview);
		_app.setSessionResource(preview);
		_front.init(TestSpaces.config());
	}

	/** The albums every space of this test holds. */
	private static void library(Path root) throws Exception {
		album(root, ZOO, "[\"AlbumInfo\",{\"title\":\"Zoo\",\"subTitle\":\"A day out\","
			+ "\"indexPicture\":{\"image\":\"members.jpg\"},\"parts\":["
			+ "[\"ImagePart\",{\"name\":\"public.jpg\",\"width\":4,\"height\":3}],"
			+ "[\"ImagePart\",{\"name\":\"members.jpg\",\"width\":4,\"height\":3,\"privacy\":1}]]}]",
			"public.jpg", "members.jpg");
		album(root, TRIP, "[\"AlbumInfo\",{\"title\":\"Trip\",\"date\":" + TRIP_DATE + ","
			+ "\"indexPicture\":{\"image\":\"private.jpg\"},\"parts\":["
			+ "[\"ImagePart\",{\"name\":\"public.jpg\",\"width\":4,\"height\":3}],"
			+ "[\"ImagePart\",{\"name\":\"private.jpg\",\"width\":4,\"height\":3,\"privacy\":2}]]}]",
			"public.jpg", "private.jpg");
		album(root, VAULT, "[\"AlbumInfo\",{\"title\":\"Vault\",\"parts\":["
			+ "[\"ImagePart\",{\"name\":\"secret.jpg\",\"width\":4,\"height\":3,\"privacy\":2}]]}]",
			"secret.jpg");
		Files.createDirectories(root.resolve(HOLIDAYS));
		Files.write(root.resolve(HOLIDAYS).resolve("index.json"),
			("[\"ListingInfo\",{\"title\":\"" + AWKWARD.replace("\"", "\\\"") + "\"}]")
				.getBytes(StandardCharsets.UTF_8));
		album(root, HOLIDAYS + "/Alps", "[\"AlbumInfo\",{\"title\":\"Alps\",\"date\":" + ALPS_DATE + ","
			+ "\"indexPicture\":{\"image\":\"alps.jpg\"},\"parts\":["
			+ "[\"ImagePart\",{\"name\":\"alps.jpg\",\"width\":4,\"height\":3}]]}]", "alps.jpg");
		album(root, HOLIDAYS + "/Rejected", "[\"AlbumInfo\",{\"title\":\"Rejected\",\"date\":" + TRIP_DATE + ","
			+ "\"indexPicture\":{\"image\":\"rejected.jpg\"},\"parts\":["
			+ "[\"ImagePart\",{\"name\":\"rejected.jpg\",\"width\":4,\"height\":3,\"rating\":-1}],"
			+ "[\"ImagePart\",{\"name\":\"kept.jpg\",\"width\":4,\"height\":3}]]}]",
			"rejected.jpg", "kept.jpg");
	}

	/** An album folder with the given sidecar and a tiny JPEG of its own for each name. */
	private static void album(Path root, String path, String sidecar, String... images) throws Exception {
		Path folder = root.resolve(path);
		Files.createDirectories(folder);
		Files.write(folder.resolve("index.json"), sidecar.getBytes(StandardCharsets.UTF_8));
		for (String image : images) {
			BufferedImage contents = new BufferedImage(4, 3, BufferedImage.TYPE_3BYTE_BGR);
			contents.setRGB(0, 0, (path + "/" + image).hashCode() & 0xFFFFFF);
			ImageIO.write(contents, "jpg", folder.resolve(image).toFile());
		}
	}

	/** The authentication of the given space. */
	private AuthService auth(String segment) {
		return _spaces.bySegment(segment).getAuth();
	}

	/** Issues a link on the given album of the given space and answers its token. */
	private String share(String segment, String path, int maxPrivacy, int minRating) throws Exception {
		return share(segment, path, maxPrivacy, minRating, "");
	}

	private String share(String segment, String path, int maxPrivacy, int minRating, String label)
			throws Exception {
		return share(segment, path, maxPrivacy, minRating, label, "");
	}

	private String share(String segment, String path, int maxPrivacy, int minRating, String label,
			String expires) throws Exception {
		ShareStore shares = auth(segment).getShares();
		return shares.create("admin", path, label, expires, maxPrivacy, minRating).getToken();
	}

	/** The preview the album itself would be served with. */
	private byte[] preview(String album, String image) throws Exception {
		File file = _base.resolve(album).resolve(image).toFile();
		return Files.readAllBytes(PreviewCache.createPreview(file).toPath());
	}

	private FakeResponse get(String pathInfo) throws Exception {
		HttpServletRequest request =
			TestSpaces.request("GET", pathInfo, new HashMap<>(), new HashMap<>(), new byte[0], null);
		FakeResponse response = new FakeResponse();
		if (_front != null) {
			_front.service(request, response.response());
		} else {
			_app.service(request, response.response());
		}
		return response;
	}

	private static String body(FakeResponse response) {
		assertEquals("Expected a page, got: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return response.body();
	}

	private static ErrorInfo error(FakeResponse response) throws Exception {
		Resource resource =
			Resource.readResource(new JsonReader(new ReaderAdapter(new StringReader(response.body()))));
		assertTrue("Expected an error, got: " + resource, resource instanceof ErrorInfo);
		return (ErrorInfo) resource;
	}

	/** A fingerprint of the library: every file that is not a generated preview. */
	private static String fingerprint(Path root) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		List<Path> files = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(root)) {
			walk.filter(Files::isRegularFile)
				.filter(file -> !file.toString().contains(PreviewCache.CACHE_DIRECTORY_NAME))
				.forEach(files::add);
		}
		files.sort(Comparator.comparing(Path::toString));
		for (Path file : files) {
			digest.update(root.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
			digest.update(Files.readAllBytes(file));
		}
		StringBuilder result = new StringBuilder();
		for (byte b : digest.digest()) {
			result.append(String.format("%02x", Byte.valueOf(b)));
		}
		return result.toString();
	}

	private static void delete(Path dir) throws Exception {
		if (dir == null || !Files.exists(dir)) {
			return;
		}
		try (Stream<Path> files = Files.walk(dir)) {
			files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		}
	}

	/** Probe: a deep link below the share base carries the card of the shared folder, not of the route. */
	public void testProbeADeepLinkBelowTheShareBaseCarriesTheCard() throws Exception {
		single();
		String token = share("", HOLIDAYS, Privacy.MEMBERS, Ratings.MIN);

		FakeResponse page = get("/s/" + token + "/Alps/");
		assertEquals(HttpServletResponse.SC_OK, page.status());
		String html = page.body();
		assertTrue(html, html.contains("og:title"));
		assertTrue("The card names the shared folder.", html.contains("Alice &amp; &lt;Bob&gt; &quot;2024&quot;"));
		assertTrue(html, html.contains("/s/" + token + "/cover.jpg"));
		assertTrue("The deep link keeps the session base: " + html, html.contains("/s/" + token + "/\">"));
	}

	/** Probe: a folder whose newest child has no picture is drawn with the next child that has one. */
	public void testProbeAFolderSkipsAChildWithoutAPicture() throws Exception {
		album(_base, HOLIDAYS + "/Empty", "[\"AlbumInfo\",{\"title\":\"Empty\",\"date\":" + (ALPS_DATE + 86400000L) + ",\"parts\":[]}]");
		single();
		String token = share("", HOLIDAYS, Privacy.MEMBERS, Ratings.MIN);

		FakeResponse cover = get("/s/" + token + "/cover.jpg");
		assertEquals(cover.body(), HttpServletResponse.SC_OK, cover.status());
		assertTrue("Expected the first child that has a picture.",
			Arrays.equals(preview(HOLIDAYS + "/Alps", "alps.jpg"), cover.bodyBytes()));
		assertTrue(get("/s/" + token + "/").body().contains("og:image"));
	}

	/** Probe: two links on one album are independent — withdrawing one leaves the other's card whole. */
	public void testProbeWithdrawingOneLinkLeavesTheOtherWhole() throws Exception {
		single();
		String first = share("", ZOO, Privacy.MEMBERS, Ratings.MIN);
		String second = share("", ZOO, Privacy.PUBLIC, Ratings.MIN);
		auth("").getShares().revoke(auth("").getShares().lookup(first).getId());

		assertEquals(HttpServletResponse.SC_GONE, get("/s/" + first + "/cover.jpg").status());
		FakeResponse cover = get("/s/" + second + "/cover.jpg");
		assertEquals(cover.body(), HttpServletResponse.SC_OK, cover.status());
		assertTrue("A public link shows the public image, not the members-only index picture.",
			Arrays.equals(preview(ZOO, "public.jpg"), cover.bodyBytes()));
		String html = get("/s/" + second + "/").body();
		assertTrue(html, html.contains("og:image") && html.contains("/s/" + second + "/cover.jpg"));
		assertFalse(get("/s/" + first + "/").body().contains("og:"));
	}

}
