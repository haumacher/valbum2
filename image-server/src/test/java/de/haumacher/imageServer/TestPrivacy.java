/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.auth.Roles;
import de.haumacher.imageServer.auth.UserStore;
import de.haumacher.imageServer.auth.UserStore.Device;
import de.haumacher.imageServer.auth.UserStore.User;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.ImageGroup;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.PairRequest;
import de.haumacher.imageServer.shared.model.PairResponse;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Test case for the privacy levels of issue #46: what a request may see is decided by the server,
 * on the way out, from who the caller is.
 *
 * <p>
 * The servlet is driven headlessly on a temporary base folder with the request and response fakes
 * of {@link TestImageServletPut}, exactly as {@link TestImageServletUsers} does.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestPrivacy extends TestCase {

	private static final String SECRET = "let-me-in";

	private static final String ALICE_TOKEN = "alice-token";

	private static final String TRIP = "2020 Trip";

	/**
	 * The album under test, as it lies in <code>index.json</code>.
	 *
	 * <p>
	 * It holds a public, a members-only and a private image, a group whose representative is
	 * private, a group that is private altogether, a group in which two visible members have the
	 * same rating, and a heading. Its index picture is the private image.
	 * </p>
	 */
	private static final String TRIP_JSON = "[\"AlbumInfo\",{"
		+ "\"title\":\"Trip\","
		+ "\"indexPicture\":{\"image\":\"a.jpg\",\"scale\":1.3333333333333333,\"ty\":0.0},"
		+ "\"parts\":["
		+ "[\"ImagePart\",{\"name\":\"a.jpg\",\"width\":4,\"height\":3,\"privacy\":2}],"
		+ "[\"ImagePart\",{\"name\":\"b.jpg\",\"width\":4,\"height\":3,\"privacy\":1}],"
		+ "[\"ImagePart\",{\"name\":\"c.jpg\",\"width\":4,\"height\":3}],"
		+ "[\"ImageGroup\",{\"representative\":0,\"images\":["
		+ "{\"name\":\"g-private.jpg\",\"width\":4,\"height\":3,\"privacy\":2,\"rating\":2},"
		+ "{\"name\":\"g-low.jpg\",\"width\":4,\"height\":3,\"rating\":0},"
		+ "{\"name\":\"g-best.jpg\",\"width\":4,\"height\":3,\"rating\":1}]}],"
		+ "[\"ImageGroup\",{\"representative\":0,\"images\":["
		+ "{\"name\":\"h1.jpg\",\"width\":4,\"height\":3,\"privacy\":2},"
		+ "{\"name\":\"h2.jpg\",\"width\":4,\"height\":3,\"privacy\":2}]}],"
		+ "[\"ImageGroup\",{\"representative\":0,\"images\":["
		+ "{\"name\":\"p.jpg\",\"width\":4,\"height\":3,\"privacy\":2,\"rating\":2},"
		+ "{\"name\":\"t1.jpg\",\"width\":4,\"height\":3,\"rating\":1},"
		+ "{\"name\":\"t2.jpg\",\"width\":4,\"height\":3,\"rating\":1}]}],"
		+ "[\"Heading\",{\"text\":\"Later\"}]"
		+ "]}]";

	/** An album nobody but its owner may see anything of; its cover is private, too. */
	private static final String SECRET_JSON = "[\"AlbumInfo\",{"
		+ "\"title\":\"Secret\","
		+ "\"indexPicture\":{\"image\":\"s1.jpg\",\"scale\":1.0,\"ty\":0.0},"
		+ "\"parts\":["
		+ "[\"ImagePart\",{\"name\":\"s1.jpg\",\"width\":4,\"height\":3,\"privacy\":2}],"
		+ "[\"ImagePart\",{\"name\":\"s2.jpg\",\"width\":4,\"height\":3,\"privacy\":2}]"
		+ "]}]";

	/** An album written before issue #46: no part carries a privacy field at all. */
	private static final String OLD_JSON = "[\"AlbumInfo\",{"
		+ "\"title\":\"Old\","
		+ "\"indexPicture\":{\"image\":\"old.jpg\",\"scale\":1.3333333333333333,\"ty\":0.0},"
		+ "\"parts\":[[\"ImagePart\",{\"name\":\"old.jpg\",\"width\":4,\"height\":3}]]}]";

	private Path _base;

	private final List<ImageServlet> _servlets = new ArrayList<>();

	private ImageServlet _servlet;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-privacy-test");
	}

	@Override
	protected void tearDown() throws Exception {
		// Every servlet holds a directory watcher; the operating system grants only so many.
		for (ImageServlet servlet : _servlets) {
			servlet.destroy();
		}
		_servlets.clear();
		_servlet = null;
		if (_base != null) {
			try (Stream<Path> files = Files.walk(_base)) {
				files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
		super.tearDown();
	}

	// --- What a listing shows. ---

	public void testTheOwnerSeesEveryPart() throws Exception {
		library();
		String token = signIn();

		assertNames(album(get(servlet(), "/" + TRIP + "/", "json", token)),
			"a.jpg", "b.jpg", "c.jpg", "g-private.jpg", "g-low.jpg", "g-best.jpg", "h1.jpg", "h2.jpg", "p.jpg",
			"t1.jpg", "t2.jpg");
	}

	public void testAnAnonymousCallerSeesOnlyPublicParts() throws Exception {
		library();

		assertNames(album(get(servlet(), "/" + TRIP + "/", "json", null)),
			"c.jpg", "g-low.jpg", "g-best.jpg", "t1.jpg", "t2.jpg");
	}

	public void testTheOwnerViewingAsAMember() throws Exception {
		library();
		String token = signIn();

		assertNames(album(get(servlet(), "/" + TRIP + "/", "json", token, "members")),
			"b.jpg", "c.jpg", "g-low.jpg", "g-best.jpg", "t1.jpg", "t2.jpg");
	}

	public void testTheOwnerViewingAsThePublic() throws Exception {
		library();
		String token = signIn();

		assertNames(album(get(servlet(), "/" + TRIP + "/", "json", token, "public")),
			"c.jpg", "g-low.jpg", "g-best.jpg", "t1.jpg", "t2.jpg");
	}

	public void testViewAsCanOnlyLower() throws Exception {
		library();

		assertNames("An anonymous caller asking to see more is still the public.",
			album(get(servlet(), "/" + TRIP + "/", "json", null, "members")),
			"c.jpg", "g-low.jpg", "g-best.jpg", "t1.jpg", "t2.jpg");
	}

	public void testAnUnknownViewAsIsRefused() throws Exception {
		library();

		FakeResponse response = get(servlet(), "/" + TRIP + "/", "json", null, "everything");

		assertEquals(HttpServletResponse.SC_BAD_REQUEST, response.status());
		assertEquals(ImageServlet.VIEW_AS_REFUSED, errorMessage(response));
	}

	public void testAnAlbumWithoutPrivacyFieldsShowsEverything() throws Exception {
		library();

		AlbumInfo album = album(get(servlet(), "/Old/", "json", null));

		assertNames("An album from before issue #46 loads as it always did.", album, "old.jpg");
		assertEquals("old.jpg", album.getIndexPicture().getImage());
	}

	// --- Groups. ---

	public void testAGroupIsReHeadedByItsBestVisibleMember() throws Exception {
		library();

		AlbumInfo album = album(get(servlet(), "/" + TRIP + "/", "json", null));

		ImageGroup group = group(album, 0);
		assertEquals("The private representative is gone.", Arrays.asList("g-low.jpg", "g-best.jpg"), names(group));
		assertEquals("The highest rating heads the group.", "g-best.jpg",
			group.getImages().get(group.getRepresentative()).getName());
	}

	public void testEqualRatingsKeepTheStoredOrder() throws Exception {
		library();

		ImageGroup group = group(album(get(servlet(), "/" + TRIP + "/", "json", null)), 1);

		assertEquals(Arrays.asList("t1.jpg", "t2.jpg"), names(group));
		assertEquals("The stored order breaks a tie.", "t1.jpg",
			group.getImages().get(group.getRepresentative()).getName());
	}

	public void testAGroupWithoutAVisibleMemberIsOmitted() throws Exception {
		library();

		AlbumInfo album = album(get(servlet(), "/" + TRIP + "/", "json", null));

		assertEquals("Only the two groups that kept a member are left.", 2, groups(album).size());
	}

	public void testTheOwnerSeesTheGroupsUntouched() throws Exception {
		library();

		String token = signIn();
		AlbumInfo album = album(get(servlet(), "/" + TRIP + "/", "json", token));

		assertEquals(3, groups(album).size());
		assertEquals("g-private.jpg", groups(album).get(0).getImages().get(0).getName());
		assertEquals(0, groups(album).get(0).getRepresentative());
	}

	// --- Covers in a listing. ---

	public void testAPrivateCoverIsReplacedByAVisibleImage() throws Exception {
		library();

		FolderInfo folder = folder(listing(get(servlet(), "/", "json", null)), TRIP);

		assertNotNull("The album is listed with a cover the caller may see.", folder.getIndexPicture());
		assertEquals("c.jpg", folder.getIndexPicture().getImage());
	}

	public void testACoverAMemberMaySeeIsShownToAMemberOnly() throws Exception {
		library();
		String token = signIn();

		assertEquals("a.jpg", folder(listing(get(servlet(), "/", "json", token)), TRIP).getIndexPicture().getImage());
		assertEquals("b.jpg",
			folder(listing(get(servlet(), "/", "json", token, "members")), TRIP).getIndexPicture().getImage());
	}

	public void testAFullyPrivateAlbumIsListedWithoutACover() throws Exception {
		library();

		FolderInfo folder = folder(listing(get(servlet(), "/", "json", null)), "Secret");

		assertNull("The album has no image this caller may see.", folder.getIndexPicture());
		assertEquals("Its title is not a secret; the album stays in the listing.", "Secret", folder.getTitle());
	}

	public void testTheAlbumsOwnCoverIsFilteredToo() throws Exception {
		library();

		assertEquals("c.jpg", album(get(servlet(), "/" + TRIP + "/", "json", null)).getIndexPicture().getImage());
		assertNull(album(get(servlet(), "/Secret/", "json", null)).getIndexPicture());
	}

	// --- The image endpoints. ---

	public void testAnAnonymousThumbnailOfAPrivateImageIsRefused() throws Exception {
		library();

		FakeResponse response = get(servlet(), "/" + TRIP + "/a.jpg", "tn", null);

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status());
		assertEquals("Bearer", response.header("WWW-Authenticate"));
		assertEquals(ImageServlet.IMAGE_REFUSED, errorMessage(response));
	}

	public void testAnAnonymousOriginalOfAPrivateImageIsRefused() throws Exception {
		library();

		FakeResponse response = get(servlet(), "/" + TRIP + "/a.jpg", null, null);

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status());
		assertEquals(ImageServlet.IMAGE_REFUSED, errorMessage(response));
	}

	public void testAnAnonymousDescriptionOfAPrivateImageIsRefused() throws Exception {
		library();

		FakeResponse response = get(servlet(), "/" + TRIP + "/a.jpg", "json", null);

		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status());
		assertEquals(ImageServlet.IMAGE_REFUSED, errorMessage(response));
	}

	public void testAPublicImageStaysReadableForEverybody() throws Exception {
		library();

		assertEquals(HttpServletResponse.SC_OK, get(servlet(), "/" + TRIP + "/c.jpg", "tn", null).status());
	}

	public void testLoweringOneselfRefusesOnesOwnImage() throws Exception {
		library();
		String token = signIn();

		FakeResponse response = get(servlet(), "/" + TRIP + "/a.jpg", "tn", token, "public");

		assertEquals("A signed-in caller cannot get further by signing in again.",
			HttpServletResponse.SC_FORBIDDEN, response.status());
		assertEquals(ImageServlet.IMAGE_REFUSED, errorMessage(response));
	}

	public void testTheOwnerReadsTheirOwnPrivateImage() throws Exception {
		library();

		String token = signIn();
		assertEquals(HttpServletResponse.SC_OK, get(servlet(), "/" + TRIP + "/a.jpg", "tn", token).status());
	}

	public void testAMemberSeesThePrivateImagesOfTheirOwnSpace() throws Exception {
		member();

		ImageServlet servlet = servlet();
		assertEquals("The owner of the space sees everything in it.",
			HttpServletResponse.SC_OK, get(servlet, "/Album/private.jpg", "tn", ALICE_TOKEN).status());
		assertNames(album(get(servlet, "/Album/", "json", ALICE_TOKEN)), "private.jpg");
	}

	// --- Nothing of this is cached or written back. ---

	public void testTheCacheKeepsTheWholeAlbum() throws Exception {
		library();
		String token = signIn();
		ImageServlet servlet = servlet();

		assertNames(album(get(servlet, "/" + TRIP + "/", "json", null)),
			"c.jpg", "g-low.jpg", "g-best.jpg", "t1.jpg", "t2.jpg");

		assertNames("A filtered answer must not become what the cache holds.",
			album(get(servlet, "/" + TRIP + "/", "json", token)),
			"a.jpg", "b.jpg", "c.jpg", "g-private.jpg", "g-low.jpg", "g-best.jpg", "h1.jpg", "h2.jpg", "p.jpg",
			"t1.jpg", "t2.jpg");

		assertEquals("The listing above is unfiltered for the owner, too.", "a.jpg",
			folder(listing(get(servlet, "/", "json", token)), TRIP).getIndexPicture().getImage());
	}

	public void testAFilteredAnswerNeverTouchesTheSidecar() throws Exception {
		library();
		Path sidecar = _base.resolve(TRIP).resolve("index.json");
		byte[] before = Files.readAllBytes(sidecar);

		ImageServlet servlet = servlet();
		get(servlet, "/" + TRIP + "/", "json", null);
		get(servlet, "/", "json", null);
		get(servlet, "/Secret/", "json", null);

		assertTrue("Reading never writes.", Arrays.equals(before, Files.readAllBytes(sidecar)));
	}

	public void testTheOwnerSavesTheAlbumBackAfterAnAnonymousListing() throws Exception {
		library();
		String token = signIn();
		ImageServlet servlet = servlet();

		// Somebody looks at the album anonymously first; the owner then saves what they see.
		get(servlet, "/" + TRIP + "/", "json", null);
		String owned = body(get(servlet, "/" + TRIP + "/", "json", token));
		FakeResponse stored = put(servlet, "/" + TRIP + "/", owned, token);

		assertEquals(HttpServletResponse.SC_OK, stored.status());
		AlbumInfo saved = (AlbumInfo) Resource
			.readResource(reader(new String(Files.readAllBytes(_base.resolve(TRIP).resolve("index.json")),
				StandardCharsets.UTF_8)));
		assertNames("A save by the owner never loses a hidden part.", saved,
			"a.jpg", "b.jpg", "c.jpg", "g-private.jpg", "g-low.jpg", "g-best.jpg", "h1.jpg", "h2.jpg", "p.jpg",
			"t1.jpg", "t2.jpg");
		assertEquals("a.jpg", saved.getIndexPicture().getImage());
	}

	// --- Without authentication nothing is hidden. ---

	public void testAuthOffShowsEverythingToEverybody() throws Exception {
		library();
		ImageServlet servlet = new ImageServlet(_base.toFile());
		_servlets.add(servlet);

		assertNames(album(get(servlet, "/" + TRIP + "/", "json", null)),
			"a.jpg", "b.jpg", "c.jpg", "g-private.jpg", "g-low.jpg", "g-best.jpg", "h1.jpg", "h2.jpg", "p.jpg",
			"t1.jpg", "t2.jpg");
		assertEquals("a.jpg", folder(listing(get(servlet, "/", "json", null)), TRIP).getIndexPicture().getImage());
		assertEquals(HttpServletResponse.SC_OK, get(servlet, "/" + TRIP + "/a.jpg", "tn", null).status());
	}

	// --- Fixtures. ---

	/**
	 * A library of three albums at the base folder, as a single-user server serves it in mode
	 * {@link AuthMode#WRITES}.
	 */
	private void library() throws IOException {
		album(TRIP, TRIP_JSON, "a.jpg", "c.jpg");
		album("Secret", SECRET_JSON);
		album("Old", OLD_JSON, "old.jpg");
	}

	/** A library whose owner has a space and a member "alice" with a private image in hers. */
	private void member() throws IOException {
		Files.createDirectories(_base.resolve("haui"));
		UserStore store = new UserStore(_base);
		User owner = store.nameOwner("haui");
		owner.setSpace("haui");
		User alice = new User("alice", Roles.MEMBER, "alice", Instant.now().toString());
		alice.addDevice(new Device("Alice's tablet", UserStore.hash(ALICE_TOKEN), Instant.now().toString()));
		store.addUser(alice);
		store.store();

		album("alice/Album", "[\"AlbumInfo\",{\"title\":\"Alice\",\"parts\":["
			+ "[\"ImagePart\",{\"name\":\"private.jpg\",\"width\":4,\"height\":3,\"privacy\":2}]]}]",
			"private.jpg");
	}

	/** An album folder with the given sidecar and a tiny JPEG for each of the given names. */
	private void album(String name, String index, String... images) throws IOException {
		Path folder = _base.resolve(name);
		Files.createDirectories(folder);
		Files.write(folder.resolve("index.json"), index.getBytes(StandardCharsets.UTF_8));
		for (String image : images) {
			ImageIO.write(new BufferedImage(4, 3, BufferedImage.TYPE_3BYTE_BGR), "jpg", folder.resolve(image).toFile());
		}
	}

	/**
	 * The servlet under test.
	 *
	 * <p>
	 * There is one per test, created when it is first asked for: it must see the users that were
	 * signed in before, and it holds a directory watcher that is given back in
	 * {@link #tearDown()}.
	 * </p>
	 */
	private ImageServlet servlet() throws IOException {
		if (_servlet == null) {
			_servlet = new ImageServlet(_base.toFile(), new AuthService(AuthMode.WRITES, SECRET, _base));
			_servlets.add(_servlet);
		}
		return _servlet;
	}

	/** Signs the library owner in and answers the token of their device. */
	private String signIn() throws Exception {
		PairResponse response = new AuthService(AuthMode.WRITES, SECRET, _base).pair(PairRequest.create()
			.setSecret(SECRET).setDeviceName("Phone").setUserName("haui"));
		return response.getToken();
	}

	// --- Assertions and accessors. ---

	private static void assertNames(AlbumInfo album, String... expected) {
		assertNames("Unexpected images.", album, expected);
	}

	private static void assertNames(String message, AlbumInfo album, String... expected) {
		assertEquals(message, Arrays.asList(expected), names(album));
	}

	/** The names of the images the given album shows, groups flattened, in stored order. */
	private static List<String> names(AlbumInfo album) {
		List<String> result = new ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				result.add(((ImagePart) part).getName());
			} else if (part instanceof ImageGroup) {
				result.addAll(names((ImageGroup) part));
			}
		}
		return result;
	}

	private static List<String> names(ImageGroup group) {
		List<String> result = new ArrayList<>();
		for (ImagePart image : group.getImages()) {
			result.add(image.getName());
		}
		return result;
	}

	private static List<ImageGroup> groups(AlbumInfo album) {
		List<ImageGroup> result = new ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImageGroup) {
				result.add((ImageGroup) part);
			}
		}
		return result;
	}

	private static ImageGroup group(AlbumInfo album, int index) {
		return groups(album).get(index);
	}

	private static FolderInfo folder(ListingInfo listing, String name) {
		for (FolderInfo folder : listing.getFolders()) {
			if (folder.getName().equals(name)) {
				return folder;
			}
		}
		fail("No folder '" + name + "' in the listing.");
		return null;
	}

	private static AlbumInfo album(FakeResponse response) throws IOException {
		Resource resource = Resource.readResource(reader(body(response)));
		assertTrue("Expected an album, got: " + resource, resource instanceof AlbumInfo);
		return (AlbumInfo) resource;
	}

	private static ListingInfo listing(FakeResponse response) throws IOException {
		Resource resource = Resource.readResource(reader(body(response)));
		assertTrue("Expected a listing, got: " + resource, resource instanceof ListingInfo);
		return (ListingInfo) resource;
	}

	private static String body(FakeResponse response) {
		assertEquals("Expected a successful request, got: " + response.body(), HttpServletResponse.SC_OK,
			response.status());
		return response.body();
	}

	private static String errorMessage(FakeResponse response) throws IOException {
		Resource resource = Resource.readResource(reader(response.body()));
		assertTrue("Expected an ErrorInfo body, got: " + response.body(), resource instanceof ErrorInfo);
		return ((ErrorInfo) resource).getMessage();
	}

	private static JsonReader reader(String contents) {
		return new JsonReader(new ReaderAdapter(new StringReader(contents)));
	}

	// --- Driving the servlet. ---

	private static FakeResponse get(ImageServlet servlet, String pathInfo, String type, String token)
			throws Exception {
		return get(servlet, pathInfo, type, token, null);
	}

	private static FakeResponse get(ImageServlet servlet, String pathInfo, String type, String token, String viewAs)
			throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", type);
		if (viewAs != null) {
			parameters.put("viewAs", viewAs);
		}
		FakeResponse response = new FakeResponse();
		servlet.doGet(request(pathInfo, null, "", token, parameters), response.response());
		return response;
	}

	private static FakeResponse put(ImageServlet servlet, String pathInfo, String body, String token)
			throws Exception {
		FakeResponse response = new FakeResponse();
		servlet.doPut(request(pathInfo, "application/json", body, token, Collections.emptyMap()),
			response.response());
		return response;
	}

	private static HttpServletRequest request(String pathInfo, String contentType, String body, String token,
			Map<String, String> parameters) {
		Map<String, String> headers = new HashMap<>();
		if (token != null) {
			headers.put("Authorization", "Bearer " + token);
		}
		return TestImageServletPut.request(pathInfo, contentType, body.getBytes(StandardCharsets.UTF_8), headers,
			parameters);
	}
}
