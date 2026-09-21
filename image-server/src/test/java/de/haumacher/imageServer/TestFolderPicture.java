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
import de.haumacher.imageServer.shared.model.CreateResult;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.FolderKind;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.Orientation;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.ThumbnailInfo;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.json.JsonWriter;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import de.haumacher.msgbuf.server.io.WriterAdapter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.image.BufferedImage;
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
 * Test case for the picture a folder of folders is shown with, see issue #110.
 *
 * <p>
 * A folder holds no photograph of its own. Which one stands for it is a statement of the author —
 * {@link ListingInfo#getIndex()}, stored in the folder's own <code>index.json</code> — and the
 * server derives the cover from it through the sidecars alone, never by opening an image. A folder
 * that chose nothing, or whose choice leads nowhere, keeps the folder icon it always had.
 * </p>
 *
 * <p>
 * The servlet is driven headlessly on a temporary base folder with the request and response fakes
 * of {@link TestImageServletPut}, exactly as {@link TestFolderKind} does.
 * </p>
 *
 * @author <a href="mailto:haui@haumacher.de">Bernhard Haumacher</a>
 */
@SuppressWarnings("javadoc")
public class TestFolderPicture extends TestCase {

	private static final String ALICE_TOKEN = "alice-token";

	private static final String DAVE_TOKEN = "dave-token";

	private Path _base;

	private ImageServlet _servlet;

	private final List<ImageServlet> _servlets = new ArrayList<>();

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-folder-picture-test");
		_servlet = new ImageServlet(_base.toFile());
		_servlets.add(_servlet);
	}

	@Override
	protected void tearDown() throws Exception {
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

	// --- What the choice answers. ---

	/** The picture of the chosen album, with the path down to it and the album's own crop. */
	public void testAFolderIsShownByTheChildItChose() throws Exception {
		image("F/A/a.jpg");
		sidecar("F/A", album("A", "\"indexPicture\":{\"image\":\"a.jpg\",\"scale\":1.75,\"tx\":11.0,"
			+ "\"ty\":22.0,\"orientation\":\"ROT_L\"},", "a.jpg"));
		sidecar("F", "[\"ListingInfo\",{\"title\":\"F\",\"index\":\"A\"}]");

		FolderInfo entry = byName(listing("/"), "F");
		assertEquals("It stays a folder: what it shows does not change what it is.",
			FolderKind.FOLDER, entry.getKind());

		ThumbnailInfo cover = entry.getIndexPicture();
		assertNotNull("The folder is shown by the picture of the album it chose.", cover);
		assertEquals("A/a.jpg", cover.getImage());
		assertEquals(1.75, cover.getScale(), 0.0);
		assertEquals(11.0, cover.getTx(), 0.0);
		assertEquals(22.0, cover.getTy(), 0.0);
		assertEquals("The frame the crop was made in rides along, see issue #115.",
			Orientation.ROT_L, cover.getOrientation());
	}

	/** A folder may choose a folder, which is asked the same question again. */
	public void testTheChoiceIsFollowedThroughAFurtherFolder() throws Exception {
		image("F/G/A/a.jpg");
		sidecar("F/G/A", album("A", "\"indexPicture\":{\"image\":\"a.jpg\",\"scale\":1.33},", "a.jpg"));
		sidecar("F/G", "[\"ListingInfo\",{\"title\":\"G\",\"index\":\"A\"}]");
		sidecar("F", "[\"ListingInfo\",{\"title\":\"F\",\"index\":\"G\"}]");

		assertEquals("G/A/a.jpg", byName(listing("/"), "F").getIndexPicture().getImage());
		assertEquals("The folder in between is shown by the same album.",
			"A/a.jpg", byName(listing("/F/"), "G").getIndexPicture().getImage());
	}

	/** Without a choice nothing is invented: the folder icon stays. */
	public void testAFolderThatChoseNothingShowsNoPicture() throws Exception {
		image("F/A/a.jpg");
		sidecar("F/A", album("A", "\"indexPicture\":{\"image\":\"a.jpg\",\"scale\":1.0},", "a.jpg"));
		sidecar("F", "[\"ListingInfo\",{\"title\":\"F\"}]");

		assertNull(byName(listing("/"), "F").getIndexPicture());
	}

	/** A choice naming a child that is not there is no picture, and no failure. */
	public void testAChoiceNamingNothingIsNoPicture() throws Exception {
		image("F/A/a.jpg");
		sidecar("F/A", album("A", "\"indexPicture\":{\"image\":\"a.jpg\",\"scale\":1.0},", "a.jpg"));
		sidecar("F", "[\"ListingInfo\",{\"title\":\"F\",\"index\":\"Gone\"}]");

		assertNull("The choice is left standing; the tile simply shows no picture.",
			byName(listing("/"), "F").getIndexPicture());
	}

	/** A child the server described by itself made no choice, and no choice is no picture. */
	public void testAChosenChildWithoutASidecarIsNoPicture() throws Exception {
		image("F/A/a.jpg");
		sidecar("F", "[\"ListingInfo\",{\"title\":\"F\",\"index\":\"A\"}]");

		assertNull(byName(listing("/"), "F").getIndexPicture());
	}

	/** A chosen album that shows no picture of its own passes none on. */
	public void testAChosenAlbumWithoutAPictureIsNoPicture() throws Exception {
		image("F/A/a.jpg");
		sidecar("F/A", album("A", "", "a.jpg"));
		sidecar("F", "[\"ListingInfo\",{\"title\":\"F\",\"index\":\"A\"}]");

		assertNull(byName(listing("/"), "F").getIndexPicture());
	}

	/** An album naming a photograph that is gone shows none. */
	public void testAPictureThatIsGoneIsNoPicture() throws Exception {
		image("F/A/a.jpg");
		sidecar("F/A", album("A", "\"indexPicture\":{\"image\":\"missing.jpg\",\"scale\":1.0},", "a.jpg"));
		sidecar("F", "[\"ListingInfo\",{\"title\":\"F\",\"index\":\"A\"}]");

		assertNull(byName(listing("/"), "F").getIndexPicture());
	}

	/**
	 * The choice names a child and is never an address.
	 *
	 * <p>
	 * A <code>..</code> or a path would be a way out of the folder, and a way out is exactly what
	 * this field is not: it names one of the entries the listing itself shows.
	 * </p>
	 */
	public void testAnAddressIsNoChoice() throws Exception {
		List<String> addresses = Arrays.asList("..", "../F/A", "A/..", ".", ".hidden");
		for (int n = 0; n < addresses.size(); n++) {
			String folder = "F" + n;
			image(folder + "/A/a.jpg");
			sidecar(folder + "/A", album("A", "\"indexPicture\":{\"image\":\"a.jpg\",\"scale\":1.0},",
				"a.jpg"));
			sidecar(folder, "[\"ListingInfo\",{\"title\":\"F\",\"index\":\""
				+ addresses.get(n).replace("\\", "\\\\") + "\"}]");
		}

		ListingInfo listing = listing("/");
		for (int n = 0; n < addresses.size(); n++) {
			assertNull("An address is no choice: " + addresses.get(n),
				byName(listing, "F" + n).getIndexPicture());
		}
	}

	/** A chain of folders is followed only so far, whatever a link on disk may have made of it. */
	public void testTheChoiceIsFollowedOnlySoFar() throws Exception {
		StringBuilder path = new StringBuilder("F");
		for (int n = 0; n <= FolderCover.MAX_DEPTH + 1; n++) {
			Files.createDirectories(_base.resolve(path.toString()));
			sidecar(path.toString(), "[\"ListingInfo\",{\"title\":\"F\",\"index\":\"G\"}]");
			path.append("/G");
		}
		image(path + "/A/a.jpg");
		sidecar(path + "/A", album("A", "\"indexPicture\":{\"image\":\"a.jpg\",\"scale\":1.0},", "a.jpg"));
		sidecar(path.toString(), "[\"ListingInfo\",{\"title\":\"G\",\"index\":\"A\"}]");

		assertNull("Giving up is the answer; the request is still answered.",
			byName(listing("/"), "F").getIndexPicture());
	}

	// --- The address of the picture. ---

	/** The address the app builds for a folder's cover is an image address like any other. */
	public void testTheThumbnailOfAFolderCoverIsServed() throws Exception {
		image("F/A/a.jpg");
		sidecar("F/A", album("A", "\"indexPicture\":{\"image\":\"a.jpg\",\"scale\":1.0},", "a.jpg"));
		sidecar("F", "[\"ListingInfo\",{\"title\":\"F\",\"index\":\"A\"}]");

		FolderInfo entry = byName(listing("/"), "F");
		// Exactly what listing_view.dart composes: "$baseUrl/${folder.name}/${indexPicture.image}".
		String url = "/" + entry.getName() + "/" + entry.getIndexPicture().getImage();
		assertEquals("/F/A/a.jpg", url);

		FakeResponse thumbnail = get(_servlet, url, null, "tn");
		assertEquals(thumbnail.body(), HttpServletResponse.SC_OK, thumbnail.status());
		assertEquals("image/jpeg", thumbnail.contentType());
	}

	// --- What the caller may see. ---

	/** A cover the caller may not see is no cover; the tile keeps its place. */
	public void testAPrivateCoverIsNoCover() throws Exception {
		image("F/A/a.jpg");
		image("F/A/b.jpg");
		sidecar("F/A", "[\"AlbumInfo\",{\"title\":\"A\",\"indexPicture\":{\"image\":\"a.jpg\",\"scale\":1.0},"
			+ "\"parts\":[[\"ImagePart\",{\"name\":\"a.jpg\",\"width\":4,\"height\":3,\"privacy\":2}],"
			+ "[\"ImagePart\",{\"name\":\"b.jpg\",\"width\":4,\"height\":3}]]}]");
		sidecar("F", "[\"ListingInfo\",{\"title\":\"F\",\"index\":\"A\"}]");

		assertEquals("A/a.jpg", byName(listing("/"), "F").getIndexPicture().getImage());

		FolderInfo hidden = byName(listing("/", "public"), "F");
		assertEquals("The folder stays in the listing: its name is no secret.", "F", hidden.getName());
		assertNull("A folder is shown by the picture its author chose and by no other.",
			hidden.getIndexPicture());
	}

	/** An inbox is never the picture of a folder for somebody who may not see the inbox. */
	public void testAnInboxIsNoPictureForAViewer() throws Exception {
		users();
		image("F/Inbox/a.jpg");
		sidecar("F/Inbox", "[\"AlbumInfo\",{\"kind\":\"INBOX\",\"title\":\"Inbox\","
			+ "\"indexPicture\":{\"image\":\"a.jpg\",\"scale\":1.0},"
			+ "\"parts\":[[\"ImagePart\",{\"name\":\"a.jpg\",\"width\":4,\"height\":3}]]}]");
		sidecar("F", "[\"ListingInfo\",{\"title\":\"F\",\"index\":\"Inbox\"}]");

		ImageServlet servlet = servlet(AuthMode.WRITES);
		assertEquals("Whoever may sort the inbox sees what it holds.",
			"Inbox/a.jpg", byName(listing(servlet, "/", ALICE_TOKEN), "F").getIndexPicture().getImage());

		FolderInfo hidden = byName(listing(servlet, "/", DAVE_TOKEN), "F");
		assertEquals("The folder itself is no inbox and stays.", "F", hidden.getName());
		assertNull("A photograph nobody has sorted yet is nobody's folder picture, see issue #135.",
			hidden.getIndexPicture());
	}

	// --- The round trip. ---

	/** The choice is stored; the entries derived from it are not. */
	public void testTheChoiceIsStoredAndTheCoverIsNot() throws Exception {
		image("F/A/a.jpg");
		sidecar("F/A", album("A", "\"indexPicture\":{\"image\":\"a.jpg\",\"scale\":1.0},", "a.jpg"));
		sidecar("F", "[\"ListingInfo\",{\"title\":\"F\",\"index\":\"A\"}]");

		// The listing as the app read it, written back as the app writes it.
		ListingInfo read = listing("/F/");
		assertEquals("A", read.getIndex());
		FakeResponse stored = put("/F/", write(read));
		assertEquals(stored.body(), HttpServletResponse.SC_OK, stored.status());

		String index = read("F/index.json");
		assertTrue("The choice is a statement and is kept: " + index, index.contains("\"index\":\"A\""));
		assertFalse("A derived cover must never be frozen into a sidecar: " + index,
			index.contains("indexPicture"));
		assertTrue("Nor the entries it was derived for: " + index, index.contains("\"folders\":[]"));

		assertEquals("Read, written and read again says the same thing.",
			"A/a.jpg", byName(listing("/"), "F").getIndexPicture().getImage());
	}

	// --- The rename of issue #130. ---

	/** A folder renamed by its own properties is still the folder above it chose. */
	public void testARenamedChildIsStillTheChosenOne() throws Exception {
		image("F/A/a.jpg");
		sidecar("F/A", album("A", "\"indexPicture\":{\"image\":\"a.jpg\",\"scale\":1.0},", "a.jpg"));
		sidecar("F", "[\"ListingInfo\",{\"title\":\"F\",\"index\":\"A\"}]");
		assertEquals("A/a.jpg", byName(listing("/"), "F").getIndexPicture().getImage());

		long date = AlbumDate.ofFolderName("2026-05-01").millis();
		FakeResponse renamed = put("/F/A/", "[\"AlbumInfo\",{\"title\":\"A2\",\"date\":" + date + ","
			+ "\"indexPicture\":{\"image\":\"a.jpg\",\"scale\":1.0},"
			+ "\"parts\":[[\"ImagePart\",{\"name\":\"a.jpg\",\"width\":4,\"height\":3}]]}]");
		assertEquals(renamed.body(), HttpServletResponse.SC_OK, renamed.status());
		assertEquals("F/2026-05-01 A2",
			CreateResult.readCreateResult(reader(renamed.body())).getPath());

		String index = read("F/index.json");
		assertTrue("The choice follows the name: " + index, index.contains("\"index\":\"2026-05-01 A2\""));
		assertEquals("2026-05-01 A2/a.jpg", byName(listing("/"), "F").getIndexPicture().getImage());
	}

	/** A folder above that named somebody else's child is left alone. */
	public void testARenameRewritesNoOtherChoice() throws Exception {
		image("F/A/a.jpg");
		image("F/B/b.jpg");
		sidecar("F/A", album("A", "\"indexPicture\":{\"image\":\"a.jpg\",\"scale\":1.0},", "a.jpg"));
		sidecar("F/B", album("B", "\"indexPicture\":{\"image\":\"b.jpg\",\"scale\":1.0},", "b.jpg"));
		sidecar("F", "[\"ListingInfo\",{\"title\":\"F\",\"index\":\"B\"}]");

		long date = AlbumDate.ofFolderName("2026-05-01").millis();
		FakeResponse renamed = put("/F/A/", "[\"AlbumInfo\",{\"title\":\"A2\",\"date\":" + date + ","
			+ "\"parts\":[[\"ImagePart\",{\"name\":\"a.jpg\",\"width\":4,\"height\":3}]]}]");
		assertEquals(renamed.body(), HttpServletResponse.SC_OK, renamed.status());

		assertTrue("Only the child that was named is followed.", read("F/index.json").contains("\"index\":\"B\""));
		assertEquals("B/b.jpg", byName(listing("/"), "F").getIndexPicture().getImage());
	}

	// --- Helpers. ---

	private static List<String> names(ListingInfo listing) {
		return listing.getFolders().stream().map(FolderInfo::getName).collect(Collectors.toList());
	}

	private static FolderInfo byName(ListingInfo listing, String name) {
		for (FolderInfo folder : listing.getFolders()) {
			if (name.equals(folder.getName())) {
				return folder;
			}
		}
		fail("No folder '" + name + "' in " + names(listing));
		return null;
	}

	/** An {@link de.haumacher.imageServer.shared.model.AlbumInfo} sidecar of the given title. */
	private static String album(String title, String indexPicture, String... images) {
		StringBuilder parts = new StringBuilder();
		for (String image : images) {
			if (parts.length() > 0) {
				parts.append(",");
			}
			parts.append("[\"ImagePart\",{\"name\":\"").append(image).append("\",\"width\":4,\"height\":3}]");
		}
		return "[\"AlbumInfo\",{\"title\":\"" + title + "\"," + indexPicture + "\"parts\":[" + parts + "]}]";
	}

	/** An administrator and a viewer of the one space. */
	private void users() throws IOException {
		UserStore store = new UserStore(_base);
		User alice = store.nameOwner("alice");
		alice.addDevice(new Device("Alice's phone", UserStore.hash(ALICE_TOKEN), Instant.now().toString()));
		User dave = new User("dave", Roles.VIEW, "", Instant.now().toString(), Clearances.ALL, true);
		dave.addDevice(new Device("Dave's phone", UserStore.hash(DAVE_TOKEN), Instant.now().toString()));
		store.addUser(dave);
		store.store();
	}

	private ImageServlet servlet(AuthMode mode) throws Exception {
		ImageServlet servlet = new ImageServlet(_base.toFile(), new AuthService(mode, _base));
		servlet.init();
		_servlets.add(servlet);
		return servlet;
	}

	/** A tiny JPEG at the given (possibly nested) path below the base folder. */
	private void image(String path) throws IOException {
		Path file = _base.resolve(path);
		Files.createDirectories(file.getParent());
		ImageIO.write(new BufferedImage(4, 3, BufferedImage.TYPE_3BYTE_BGR), "jpg", file.toFile());
	}

	private void sidecar(String folder, String json) throws IOException {
		Path dir = _base.resolve(folder);
		Files.createDirectories(dir);
		Files.write(dir.resolve("index.json"), json.getBytes(StandardCharsets.UTF_8));
	}

	private String read(String path) throws IOException {
		return new String(Files.readAllBytes(_base.resolve(path)), StandardCharsets.UTF_8);
	}

	private ListingInfo listing(String pathInfo) throws Exception {
		return listing(pathInfo, null);
	}

	private ListingInfo listing(String pathInfo, String viewAs) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "json");
		if (viewAs != null) {
			parameters.put("viewAs", viewAs);
		}
		FakeResponse response = new FakeResponse();
		_servlet.doGet(request(pathInfo, null, "", null, parameters), response.response());
		assertEquals("Reading failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return (ListingInfo) Resource.readResource(reader(response.body()));
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
}
