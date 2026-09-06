/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.CreateResult;
import de.haumacher.imageServer.shared.model.FolderInfo;
import de.haumacher.imageServer.shared.model.FolderResource;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.ListingInfo;
import de.haumacher.imageServer.shared.model.MoveOutcome;
import de.haumacher.imageServer.shared.model.MoveResult;
import de.haumacher.imageServer.shared.model.PairRequest;
import de.haumacher.imageServer.shared.model.PairResponse;
import de.haumacher.imageServer.shared.model.Placement;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.msgbuf.json.JsonReader;
import de.haumacher.msgbuf.server.io.ReaderAdapter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import junit.framework.TestCase;

/**
 * Probe for issue #48, composing the album date and the placement rule with what was there before
 * them: the sidecar a move rewrites (#47), the privacy filter (#46), the listing a restarted server
 * reads from disk, and the folders a year rule creates, which are ordinary folders with no rule of
 * their own.
 */
@SuppressWarnings("javadoc")
public class TestPlacementProbe extends TestCase {

	private static final String SECRET = "let-me-in";

	private Path _base;

	private final List<ImageServlet> _servlets = new ArrayList<>();

	private String _token;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-placement-probe");
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

	/**
	 * A library is filed by year, and a restarted server reads the same truth from the disk: the
	 * rule is in the folder's sidecar, the year folder is an ordinary folder, and the listing shows
	 * the newest year first.
	 */
	public void testAFiledLibraryReadsTheSameAfterARestart() throws Exception {
		rule("", Placement.BY_YEAR);
		ImageServlet servlet = servlet();

		assertEquals("2018/2018-03-04 Ski", create(servlet, "/2018-03-04 Ski").getPath());
		assertEquals("2021/2021-07-01 Lake", create(servlet, "/2021-07-01 Lake").getPath());

		// A second album of the same year joins the year folder that is already there.
		assertEquals("2021/2021-09-09 Fair", create(servlet, "/2021-09-09 Fair").getPath());

		ImageServlet restarted = freshServlet();
		ListingInfo root = listing(restarted, "/");
		assertEquals("The newest year first.", Arrays.asList("2021", "2018"), names(root));
		assertEquals("The rule is read from the folder's own sidecar.", Placement.BY_YEAR, root.getPlacement());

		ListingInfo year = listing(restarted, "/2021/");
		assertEquals("A year folder is an ordinary folder with no rule of its own.", Placement.NONE,
			year.getPlacement());
		assertEquals(Arrays.asList("2021-09-09 Fair", "2021-07-01 Lake"), names(year));

		// The rule places, it does not police: an album created inside the year folder stays there,
		// however it is dated.
		assertEquals("2021/1999-01-01 Old scan", create(restarted, "/2021/1999-01-01 Old scan").getPath());
	}

	/**
	 * Applying the rule to a folder moves whole album folders, and everything the albums say
	 * survives it: the images, the privacy level that hides one of them, and the date the tile is
	 * sorted by.
	 */
	public void testWhatIsFiledKeepsItsImagesItsPrivacyAndItsDate() throws Exception {
		rule("", Placement.BY_YEAR_MONTH);
		album("2020-05-01 Trip", "[\"AlbumInfo\",{\"title\":\"Trip\","
			+ "\"indexPicture\":{\"image\":\"secret.jpg\",\"scale\":1.0,\"ty\":0.0},\"parts\":["
			+ "[\"ImagePart\",{\"name\":\"secret.jpg\",\"width\":6,\"height\":4,\"privacy\":2}],"
			+ "[\"ImagePart\",{\"name\":\"public.jpg\",\"width\":6,\"height\":4}]]}]",
			"secret.jpg", "public.jpg");

		ImageServlet servlet = servlet();
		assertEquals(Collections.singletonList("2020/2020-05/2020-05-01 Trip"),
			newNames(place(servlet, "/")));

		String path = "/2020/2020-05/2020-05-01 Trip/";
		AlbumInfo owner = album(get(servlet, path, token()));
		assertEquals("Nothing of the album changed by being filed.", Arrays.asList("secret.jpg", "public.jpg"),
			imageNames(owner));
		assertEquals("The date the album was filed by is the date it answers with.",
			AlbumDate.ofFolderName("2020-05-01").millis(), owner.getEffectiveDate());

		AlbumInfo anonymous = album(get(servlet, path, null));
		assertEquals("The privacy level travelled with the album.", Collections.singletonList("public.jpg"),
			imageNames(anonymous));
		assertEquals("The filtered copy carries the date, too.", owner.getEffectiveDate(),
			anonymous.getEffectiveDate());

		ListingInfo month = listing(servlet, "/2020/2020-05/");
		FolderInfo tile = month.getFolders().get(0);
		assertEquals(AlbumDate.ofFolderName("2020-05-01").millis(), tile.getEffectiveDate());
	}

	/**
	 * A move rewrites both sidecars, and it writes the one sidecar format: the explicit date stays,
	 * the derived one is never written down, whatever the album carried when it was read.
	 */
	public void testTheSidecarAMoveWritesCarriesNoDerivedDate() throws Exception {
		long explicit = AlbumDate.ofFolderName("1999-12-31").millis();
		image("A/a.jpg", Color.RED);
		image("B/b.jpg", Color.GREEN);
		sidecar("A", "[\"AlbumInfo\",{\"title\":\"A\",\"parts\":[" + part("a.jpg") + "]}]");
		sidecar("B", "[\"AlbumInfo\",{\"title\":\"B\",\"date\":" + explicit + ",\"parts\":[" + part("b.jpg")
			+ "]}]");

		ImageServlet servlet = servlet();
		// Read first, so that both albums are in the cache with their derived date filled in.
		assertEquals(explicit, album(get(servlet, "/B/", null)).getEffectiveDate());
		assertTrue(album(get(servlet, "/A/", null)).getEffectiveDate() >= 0);

		move(servlet, "/A/", "B", "a.jpg");

		AlbumInfo storedTarget = (AlbumInfo) sidecar("B");
		assertEquals("The date the author set is kept by a move.", explicit, storedTarget.getDate());
		assertEquals("The derived date is answered, never written.", 0L, storedTarget.getEffectiveDate());
		assertEquals(0L, ((AlbumInfo) sidecar("A")).getEffectiveDate());

		assertEquals("And it is derived again on the next read.", explicit,
			album(get(servlet, "/B/", null)).getEffectiveDate());
	}

	// --- Helpers. ---

	private static List<String> names(ListingInfo listing) {
		return listing.getFolders().stream().map(FolderInfo::getName).collect(Collectors.toList());
	}

	private static List<String> newNames(MoveResult result) {
		return result.getOutcomes().stream().map(MoveOutcome::getNewName).collect(Collectors.toList());
	}

	private static List<String> imageNames(AlbumInfo album) {
		List<String> result = new ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				result.add(((ImagePart) part).getName());
			}
		}
		return result;
	}

	private static String part(String name) {
		return "[\"ImagePart\",{\"name\":\"" + name + "\",\"kind\":\"IMAGE\",\"width\":6,\"height\":4}]";
	}

	private void rule(String folder, Placement placement) throws IOException {
		Path path = folder.isEmpty() ? _base : _base.resolve(folder);
		Files.createDirectories(path);
		Files.write(path.resolve("index.json"),
			("[\"ListingInfo\",{\"title\":\"Folder\",\"placement\":\"" + placement.protocolName() + "\"}]")
				.getBytes(StandardCharsets.UTF_8));
	}

	private void album(String name, String index, String... images) throws IOException {
		sidecar(name, index);
		for (String image : images) {
			image(name + "/" + image, Color.BLUE);
		}
	}

	private void sidecar(String folder, String contents) throws IOException {
		Path path = _base.resolve(folder);
		Files.createDirectories(path);
		Files.write(path.resolve("index.json"), contents.getBytes(StandardCharsets.UTF_8));
	}

	private FolderResource sidecar(String folder) throws IOException {
		File index = _base.resolve(folder).resolve("index.json").toFile();
		assertTrue("Expected a sidecar at " + index, index.exists());
		return FolderResource.readFolderResource(
			reader(new String(Files.readAllBytes(index.toPath()), StandardCharsets.UTF_8)));
	}

	private void image(String relativePath, Color color) throws IOException {
		Path path = _base.resolve(relativePath);
		Files.createDirectories(path.getParent());
		BufferedImage image = new BufferedImage(6, 4, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		graphics.setColor(color);
		graphics.fillRect(0, 0, 6, 4);
		graphics.dispose();
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		ImageIO.write(image, "jpg", buffer);
		Files.write(path, buffer.toByteArray());
	}

	// --- Driving the servlet. ---

	private CreateResult create(ImageServlet servlet, String pathInfo) throws Exception {
		FakeResponse response = new FakeResponse();
		servlet.doPut(request(pathInfo, "application/json", "[\"AlbumInfo\",{\"title\":\"New\",\"parts\":[]}]",
			token(), Collections.emptyMap()), response.response());
		assertEquals("The creation failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return CreateResult.readCreateResult(reader(response.body()));
	}

	private MoveResult place(ImageServlet servlet, String pathInfo) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "place");
		FakeResponse response = new FakeResponse();
		servlet.doPost(request(pathInfo, "application/json", "", token(), parameters), response.response());
		assertEquals("Applying the rule failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return MoveResult.readMoveResult(reader(response.body()));
	}

	private void move(ImageServlet servlet, String pathInfo, String target, String... names) throws Exception {
		String body = "{\"target\":\"" + target + "\",\"names\":["
			+ Arrays.stream(names).map(n -> "{\"name\":\"" + n + "\"}").collect(Collectors.joining(",")) + "]}";
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "move");
		FakeResponse response = new FakeResponse();
		servlet.doPost(request(pathInfo, "application/json", body, token(), parameters), response.response());
		assertEquals("The move failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
	}

	private static FakeResponse get(ImageServlet servlet, String pathInfo, String token) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", "json");
		FakeResponse response = new FakeResponse();
		servlet.doGet(request(pathInfo, null, "", token, parameters), response.response());
		return response;
	}

	private static ListingInfo listing(ImageServlet servlet, String pathInfo) throws Exception {
		Resource resource = Resource.readResource(reader(body(get(servlet, pathInfo, null))));
		assertTrue("Expected a listing, got: " + resource, resource instanceof ListingInfo);
		return (ListingInfo) resource;
	}

	private static AlbumInfo album(FakeResponse response) throws IOException {
		Resource resource = Resource.readResource(reader(body(response)));
		assertTrue("Expected an album, got: " + resource, resource instanceof AlbumInfo);
		return (AlbumInfo) resource;
	}

	private static String body(FakeResponse response) {
		assertEquals("Expected a successful request, got: " + response.body(), HttpServletResponse.SC_OK,
			response.status());
		return response.body();
	}

	private static JsonReader reader(String contents) {
		return new JsonReader(new ReaderAdapter(new StringReader(contents)));
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

	private String token() throws Exception {
		if (_token == null) {
			PairResponse response = new AuthService(AuthMode.WRITES, SECRET, _base).pair(PairRequest.create()
				.setSecret(SECRET).setDeviceName("Phone").setUserName("haui"));
			_token = response.getToken();
		}
		return _token;
	}

	/** The servlet under test; the owner is signed in before it exists. */
	private ImageServlet servlet() throws Exception {
		token();
		return freshServlet();
	}

	/** Another servlet on the same folder: what a restarted server reads from the disk. */
	private ImageServlet freshServlet() throws IOException {
		ImageServlet servlet = new ImageServlet(_base.toFile(), new AuthService(AuthMode.WRITES, SECRET, _base));
		_servlets.add(servlet);
		return servlet;
	}
}
