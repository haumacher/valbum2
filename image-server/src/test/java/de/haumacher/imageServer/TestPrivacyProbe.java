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
import de.haumacher.imageServer.shared.model.AbstractImage;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ErrorInfo;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.PairRequest;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Probe for issue #46, composing the privacy filter with what existed before it: the owner's
 * sidecar save (the filtered answer must follow the saved truth at once), the spaces and the
 * anonymous refusal of a migrated library (#45), and a member's folder cover under
 * <code>viewAs</code>.
 */
@SuppressWarnings("javadoc")
public class TestPrivacyProbe extends TestCase {

	private static final String SECRET = "let-me-in";

	private static final String ALICE_TOKEN = "alice-token";

	private static final String TRIP = "2020-05-01 Trip";

	private static final String TRIP_JSON = "[\"AlbumInfo\",{\"title\":\"Trip\","
		+ "\"indexPicture\":{\"image\":\"a.jpg\",\"scale\":1.3333333333333333,\"ty\":0.0},"
		+ "\"parts\":["
		+ "[\"ImagePart\",{\"name\":\"a.jpg\",\"width\":4,\"height\":3,\"privacy\":2,\"rating\":2}],"
		+ "[\"ImagePart\",{\"name\":\"b.jpg\",\"width\":4,\"height\":3,\"privacy\":1,\"rating\":1}],"
		+ "[\"ImagePart\",{\"name\":\"c.jpg\",\"width\":4,\"height\":3,\"privacy\":0,\"rating\":0}]"
		+ "]}]";

	private Path _base;

	private final List<ImageServlet> _servlets = new ArrayList<>();

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-privacy-probe");
	}

	@Override
	protected void tearDown() throws Exception {
		for (ImageServlet servlet : _servlets) {
			servlet.destroy();
		}
		_servlets.clear();
		if (_base != null) {
			try (Stream<Path> files = Files.walk(_base)) {
				files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
			}
		}
		super.tearDown();
	}

	public void testTheOwnersSaveIsWhatTheNextAnonymousListingShows() throws Exception {
		album(TRIP, TRIP_JSON, "a.jpg", "b.jpg", "c.jpg");
		// Signed in before the servlet loads the user store.
		String owner = signIn();
		ImageServlet servlet = servlet();

		AlbumInfo anonymous = album(get(servlet, "/" + TRIP + "/", "json", null, null));
		assertEquals(Collections.singletonList("c.jpg"), names(anonymous));
		assertEquals("Trip", anonymous.getTitle());
		assertEquals("The private cover is replaced by the visible image.", "c.jpg",
			anonymous.getIndexPicture().getImage());
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, get(servlet, "/" + TRIP + "/b.jpg", "tn", null, null).status());

		// The owner sees the whole album and releases b.jpg to the public.
		AlbumInfo full = album(get(servlet, "/" + TRIP + "/", "json", owner, null));
		assertEquals(3, full.getParts().size());
		FakeResponse saved = put(servlet, "/" + TRIP + "/", TRIP_JSON.replace("\"privacy\":1", "\"privacy\":0"), owner);
		assertEquals(saved.body(), HttpServletResponse.SC_OK, saved.status());

		AlbumInfo released = album(get(servlet, "/" + TRIP + "/", "json", null, null));
		assertEquals("The saved truth is served at once, not a stale filtered copy.",
			List.of("b.jpg", "c.jpg"), names(released));
		assertEquals("b.jpg is public now, so it is the first visible image and the cover.", "b.jpg",
			released.getIndexPicture().getImage());
		assertEquals(HttpServletResponse.SC_OK, get(servlet, "/" + TRIP + "/b.jpg", "tn", null, null).status());
		assertEquals("Still private.", HttpServletResponse.SC_UNAUTHORIZED,
			get(servlet, "/" + TRIP + "/a.jpg", "tn", null, null).status());

		AlbumInfo ownersView = album(get(servlet, "/" + TRIP + "/", "json", owner, null));
		assertEquals("The owner's view is complete after the anonymous listings.", 3, ownersView.getParts().size());
		assertEquals("a.jpg", ownersView.getIndexPicture().getImage());
	}

	public void testAMigratedLibraryRefusesAnonymousCallersBeforeAnyFiltering() throws Exception {
		migratedLibraryWithAlice();
		ImageServlet servlet = servlet();

		FakeResponse root = get(servlet, "/", "json", null, null);
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, root.status());
		assertEquals(AuthService.LIBRARY_REFUSED, errorMessage(root));

		FakeResponse withViewAs = get(servlet, "/", "json", null, "public");
		assertEquals("Lowering oneself does not open a closed library.", HttpServletResponse.SC_UNAUTHORIZED,
			withViewAs.status());
	}

	public void testAMembersFolderCoverFollowsTheViewAsClearance() throws Exception {
		migratedLibraryWithAlice();
		ImageServlet servlet = servlet();

		ListingInfo own = listing(get(servlet, "/", "json", ALICE_TOKEN, null));
		FolderInfo album = folder(own, "Album");
		assertNotNull("Alice sees her private cover in her own root listing.", album.getIndexPicture());
		assertEquals("private.jpg", album.getIndexPicture().getImage());

		ListingInfo asMembers = listing(get(servlet, "/", "json", ALICE_TOKEN, "members"));
		assertEquals("Members see the public cover.", "public.jpg",
			folder(asMembers, "Album").getIndexPicture().getImage());

		ListingInfo asPublic = listing(get(servlet, "/", "json", ALICE_TOKEN, "public"));
		assertNotNull("The album is still listed.", folder(asPublic, "Album"));
		assertNull("Nothing in it is public, so the tile has no cover.", folder(asPublic, "Album").getIndexPicture());

		// Her own private image stays hers, but not when she looks as the public.
		assertEquals(HttpServletResponse.SC_OK, get(servlet, "/Album/private.jpg", "tn", ALICE_TOKEN, null).status());
		FakeResponse lowered = get(servlet, "/Album/private.jpg", "tn", ALICE_TOKEN, "public");
		assertEquals(HttpServletResponse.SC_FORBIDDEN, lowered.status());
		assertEquals(ImageServlet.IMAGE_REFUSED, errorMessage(lowered));

		// The sidecar was never touched by any of this.
		String sidecar = new String(Files.readAllBytes(_base.resolve("alice/Album/index.json")), StandardCharsets.UTF_8);
		assertTrue(sidecar.contains("\"privacy\":2"));
		assertTrue(sidecar.contains("\"privacy\":1"));
	}

	// --- Fixtures. ---

	/** A migrated library: owner "haui" with space "haui", member "alice" with an album of her own. */
	private void migratedLibraryWithAlice() throws IOException {
		Files.createDirectories(_base.resolve("haui"));
		UserStore store = new UserStore(_base);
		User owner = store.nameOwner("haui");
		owner.setSpace("haui");
		User alice = new User("alice", Roles.MEMBER, "alice", Instant.now().toString());
		alice.addDevice(new Device("Alice's tablet", UserStore.hash(ALICE_TOKEN), Instant.now().toString()));
		store.addUser(alice);
		store.store();

		album("alice/Album", "[\"AlbumInfo\",{\"title\":\"Alice\","
			+ "\"indexPicture\":{\"image\":\"private.jpg\",\"scale\":1.3333333333333333,\"ty\":0.0},"
			+ "\"parts\":["
			+ "[\"ImagePart\",{\"name\":\"private.jpg\",\"width\":4,\"height\":3,\"privacy\":2}],"
			+ "[\"ImagePart\",{\"name\":\"public.jpg\",\"width\":4,\"height\":3,\"privacy\":1}]]}]",
			"private.jpg", "public.jpg");
	}

	private void album(String name, String index, String... images) throws IOException {
		Path folder = _base.resolve(name);
		Files.createDirectories(folder);
		Files.write(folder.resolve("index.json"), index.getBytes(StandardCharsets.UTF_8));
		for (String image : images) {
			ImageIO.write(new BufferedImage(4, 3, BufferedImage.TYPE_3BYTE_BGR), "jpg", folder.resolve(image).toFile());
		}
	}

	private ImageServlet servlet() throws IOException {
		ImageServlet servlet = new ImageServlet(_base.toFile(), new AuthService(AuthMode.WRITES, SECRET, _base));
		_servlets.add(servlet);
		return servlet;
	}

	private String signIn() throws Exception {
		return new AuthService(AuthMode.WRITES, SECRET, _base)
			.pair(PairRequest.create().setSecret(SECRET).setDeviceName("Phone").setUserName("haui")).getToken();
	}

	// --- Accessors. ---

	private static List<String> names(AlbumInfo album) {
		List<String> names = new ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				names.add(((ImagePart) part).getName());
			} else if (part instanceof AbstractImage) {
				names.add(part.getClass().getSimpleName());
			}
		}
		return names;
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
