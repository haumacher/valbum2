/*
 * Copyright (c) 2026 Bernhard Haumacher et al. All Rights Reserved.
 */
package de.haumacher.imageServer;

import de.haumacher.imageServer.TestImageServletPut.FakeResponse;
import de.haumacher.imageServer.auth.AuthMode;
import de.haumacher.imageServer.auth.AuthService;
import de.haumacher.imageServer.shared.model.AlbumInfo;
import de.haumacher.imageServer.shared.model.AlbumPart;
import de.haumacher.imageServer.shared.model.ImagePart;
import de.haumacher.imageServer.shared.model.MoveResult;
import de.haumacher.imageServer.shared.model.PresentFile;
import de.haumacher.imageServer.shared.model.Resource;
import de.haumacher.imageServer.shared.model.UploadCheckResult;
import de.haumacher.imageServer.upload.HashCache;
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
 * Probe for issue #47, composing the move with what existed before it: folders that never had a
 * sidecar (the album is made up from the files, #25-style), the idempotent upload's pre-check and
 * its <code>.hashes.json</code> (#29), and the privacy filter (#46) at the new place.
 */
@SuppressWarnings("javadoc")
public class TestMoveProbe extends TestCase {

	private static final String SECRET = "let-me-in";

	private Path _base;

	private final List<ImageServlet> _servlets = new ArrayList<>();

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_base = Files.createTempDirectory("valbum-move-probe");
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

	public void testMovingBetweenFoldersThatNeverHadASidecarAndTheUploadPreCheckFollows() throws Exception {
		byte[] x = jpeg(6, 4, Color.RED);
		write("A/x.jpg", x);
		write("B/y.jpg", jpeg(4, 6, Color.BLUE));
		String owner = signIn();
		ImageServlet servlet = servlet();

		// Before: the made-up albums list what the folders hold; the pre-check knows x in A -- and
		// since issue #118 it says so wherever it is asked, naming the path the photo is at.
		assertEquals(Collections.singletonList("x.jpg"), names(album(get(servlet, "/A/", "json", owner))));
		assertEquals(Collections.singletonList("x.jpg"), present(servlet, "/A/", owner, x));
		assertEquals(Collections.singletonList("A/x.jpg"), present(servlet, "/B/", owner, x));

		MoveResult result = move(servlet, "/A/", "B", owner, "x.jpg");
		assertEquals(1, result.getOutcomes().size());
		assertEquals("x.jpg", result.getOutcomes().get(0).getNewName());
		assertEquals("", result.getOutcomes().get(0).getMessage());

		// After: files and albums agree, and both folders now have a sidecar of the one format.
		assertFalse(Files.exists(_base.resolve("A/x.jpg")));
		assertTrue(Files.exists(_base.resolve("B/x.jpg")));
		assertTrue(Files.exists(_base.resolve("A/index.json")));
		assertTrue(Files.exists(_base.resolve("B/index.json")));
		AlbumInfo target = album(get(servlet, "/B/", "json", owner));
		assertEquals(Arrays.asList("y.jpg", "x.jpg"), names(target));
		ImagePart moved = image(target, "x.jpg");
		assertEquals("The analysed size travels with the part.", 6, moved.getWidth());
		assertEquals(4, moved.getHeight());
		assertEquals(Collections.emptyList(), names(album(get(servlet, "/A/", "json", owner))));

		// The idempotent upload now knows x at B; asked at A, it says where the photo went -- the
		// gap issue #118 closed, and the reason a re-scanning app does not upload it a second time.
		assertEquals(Collections.singletonList("x.jpg"), present(servlet, "/B/", owner, x));
		assertEquals(Collections.singletonList("B/x.jpg"), present(servlet, "/A/", owner, x));

		// A fresh server reads the same truth from disk.
		ImageServlet restarted = servlet();
		assertEquals(Arrays.asList("y.jpg", "x.jpg"), names(album(get(restarted, "/B/", "json", owner))));
		assertEquals(Collections.singletonList("x.jpg"), present(restarted, "/B/", owner, x));
	}

	public void testAPrivateImageStaysPrivateWhereItLands() throws Exception {
		write("C/p.jpg", jpeg(6, 4, Color.GREEN));
		write("C/q.jpg", jpeg(6, 4, Color.YELLOW));
		write("C/index.json", ("[\"AlbumInfo\",{\"title\":\"C\",\"parts\":["
			+ "[\"ImagePart\",{\"name\":\"p.jpg\",\"width\":6,\"height\":4,\"privacy\":2,\"rating\":1,\"comment\":\"mine\"}],"
			+ "[\"ImagePart\",{\"name\":\"q.jpg\",\"width\":6,\"height\":4}]]}]").getBytes(StandardCharsets.UTF_8));
		write("D/r.jpg", jpeg(4, 6, Color.BLUE));
		String owner = signIn();
		ImageServlet servlet = servlet();

		// Anonymous callers had a filtered look at C before the move (the cache holds C).
		assertEquals(Collections.singletonList("q.jpg"), names(album(get(servlet, "/C/", "json", null))));

		MoveResult result = move(servlet, "/C/", "D", owner, "p.jpg");
		assertEquals("", result.getOutcomes().get(0).getMessage());

		AlbumInfo ownersD = album(get(servlet, "/D/", "json", owner));
		ImagePart p = image(ownersD, "p.jpg");
		assertEquals(2, p.getPrivacy());
		assertEquals(1, p.getRating());
		assertEquals("mine", p.getComment());

		assertEquals("The public does not see the private image at its new place either.",
			Collections.singletonList("r.jpg"), names(album(get(servlet, "/D/", "json", null))));
		assertEquals(HttpServletResponse.SC_UNAUTHORIZED, get(servlet, "/D/p.jpg", "tn", null).status());
		assertEquals(HttpServletResponse.SC_OK, get(servlet, "/D/p.jpg", "tn", owner).status());
		assertEquals("Gone from where it was, for everybody.", HttpServletResponse.SC_NOT_FOUND,
			get(servlet, "/C/p.jpg", "tn", owner).status());
		assertEquals(Collections.singletonList("q.jpg"), names(album(get(servlet, "/C/", "json", owner))));
		assertEquals(Collections.singletonList("q.jpg"), names(album(get(servlet, "/C/", "json", null))));

		// The sidecar at C keeps nothing of p, and the one at D carries p's level.
		String c = new String(Files.readAllBytes(_base.resolve("C/index.json")), StandardCharsets.UTF_8);
		assertFalse(c.contains("p.jpg"));
		String d = new String(Files.readAllBytes(_base.resolve("D/index.json")), StandardCharsets.UTF_8);
		assertTrue(d.contains("\"privacy\":2"));
	}

	// --- Helpers. ---

	// --- Probes of issues #152 and #153, composing the cover with the trash and the purge with the index. ---

	/**
	 * A photograph rated -2 is shown nowhere (#152), so it can stand for no album: neither when it
	 * is the only thing that lands in an album without a cover (#153), nor when it is all that is
	 * left after the cover moved away.
	 */
	public void testATrashedPhotographNeverBecomesACover() throws Exception {
		write("A/a.jpg", jpeg(6, 4, Color.RED));
		write("A/b.jpg", jpeg(6, 4, Color.GREEN));
		write("A/index.json", ("[\"AlbumInfo\",{\"title\":\"A\","
			+ "\"indexPicture\":{\"image\":\"b.jpg\",\"scale\":1.5},\"parts\":["
			+ "[\"ImagePart\",{\"name\":\"b.jpg\",\"width\":6,\"height\":4,\"rating\":0}],"
			+ "[\"ImagePart\",{\"name\":\"a.jpg\",\"width\":6,\"height\":4,\"rating\":-2}]]}]")
			.getBytes(StandardCharsets.UTF_8));
		write("B/y.jpg", jpeg(4, 6, Color.BLUE));
		write("C/z.jpg", jpeg(4, 6, Color.BLUE));
		String owner = signIn();
		ImageServlet servlet = servlet();

		move(servlet, "/A/", "B", owner, "b.jpg");
		assertNull("The cover left and only a trashed photograph stayed: no cover, rather than the trash.",
			stored("A").getIndexPicture());
		assertEquals("The landed photograph covers the album that had none.", "b.jpg",
			stored("B").getIndexPicture().getImage());

		move(servlet, "/A/", "C", owner, "a.jpg");
		assertNull("What lands as trash covers nothing.", stored("C").getIndexPicture());
	}

	/**
	 * Among equally rated photographs the cover is the first of them in the order they land in,
	 * which is the order the request named them in — "the first best-rated image from the
	 * selection" (the author, #153).
	 */
	public void testEquallyRatedPhotographsAreChosenByTheOrderTheyLandIn() throws Exception {
		write("A/later.jpg", jpeg(6, 4, Color.RED));
		write("A/earlier.jpg", jpeg(6, 4, Color.GREEN));
		write("A/index.json", ("[\"AlbumInfo\",{\"title\":\"A\",\"parts\":["
			+ "[\"ImagePart\",{\"name\":\"earlier.jpg\",\"width\":6,\"height\":4,\"rating\":1,\"date\":1000}],"
			+ "[\"ImagePart\",{\"name\":\"later.jpg\",\"width\":6,\"height\":4,\"rating\":1,\"date\":2000}]]}]")
			.getBytes(StandardCharsets.UTF_8));
		write("B/y.jpg", jpeg(4, 6, Color.BLUE));
		String owner = signIn();
		ImageServlet servlet = servlet();

		move(servlet, "/A/", "B", owner, "later.jpg", "earlier.jpg");

		AlbumInfo target = stored("B");
		List<String> landed = new ArrayList<>(names(target));
		landed.remove("y.jpg");
		assertEquals("They land in the order the request named them.", Arrays.asList("later.jpg", "earlier.jpg"),
			landed);
		assertEquals("The cover is the first of the landed.", "later.jpg", target.getIndexPicture().getImage());
	}

	/**
	 * A purge (#152) removes the photograph from disk; the space-wide index of #118 must forget it
	 * too, or a re-scanning device would be told the photograph is still there and skip it.
	 */
	public void testAPurgedPhotographIsForgottenByTheHashIndex() throws Exception {
		byte[] a = jpeg(6, 4, Color.RED);
		write("A/a.jpg", a);
		write("A/b.jpg", jpeg(6, 4, Color.GREEN));
		write("A/index.json", ("[\"AlbumInfo\",{\"title\":\"A\",\"parts\":["
			+ "[\"ImagePart\",{\"name\":\"a.jpg\",\"width\":6,\"height\":4,\"rating\":-2}],"
			+ "[\"ImagePart\",{\"name\":\"b.jpg\",\"width\":6,\"height\":4,\"rating\":0}]]}]")
			.getBytes(StandardCharsets.UTF_8));
		write("B/y.jpg", jpeg(4, 6, Color.BLUE));
		String owner = signIn();
		ImageServlet servlet = servlet();

		assertEquals(Collections.singletonList("a.jpg"), present(servlet, "/A/", owner, a));
		assertEquals(Collections.singletonList("A/a.jpg"), present(servlet, "/B/", owner, a));

		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "purge");
		FakeResponse response = new FakeResponse();
		servlet.doPost(request("/A/", null, "", owner, parameters), response.response());
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());

		assertFalse(Files.exists(_base.resolve("A/a.jpg")));
		assertEquals("The folder itself no longer knows the photograph.", Collections.emptyList(),
			present(servlet, "/A/", owner, a));
		assertEquals("Nor does the index of the space.", Collections.emptyList(),
			present(servlet, "/B/", owner, a));
		assertEquals(Collections.singletonList("b.jpg"), names(stored("A")));

		ImageServlet restarted = servlet();
		assertEquals("A fresh server reads the same truth from disk.", Collections.emptyList(),
			present(restarted, "/B/", owner, a));
	}

	/** The album as its sidecar stores it (the answered album may carry a derived cover, the sidecar never). */
	private AlbumInfo stored(String folder) throws IOException {
		String contents = new String(Files.readAllBytes(_base.resolve(folder + "/index.json")), StandardCharsets.UTF_8);
		return (AlbumInfo) Resource.readResource(reader(contents));
	}

	private List<String> present(ImageServlet servlet, String folder, String token, byte[] contents) throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "check");
		FakeResponse response = new FakeResponse();
		String body = "{\"hashes\":[{\"hash\":\"" + HashCache.sha256(contents) + "\"}]}";
		servlet.doPost(request(folder, "application/json", body, token, parameters), response.response());
		assertEquals(response.body(), HttpServletResponse.SC_OK, response.status());
		UploadCheckResult result = UploadCheckResult.readUploadCheckResult(reader(response.body()));
		List<String> names = new ArrayList<>();
		for (PresentFile file : result.getPresent()) {
			names.add(file.getName());
		}
		return names;
	}

	private static MoveResult move(ImageServlet servlet, String pathInfo, String target, String token,
			String... names) throws Exception {
		String body = "{\"target\":\"" + target + "\",\"names\":["
			+ Arrays.stream(names).map(n -> "{\"name\":\"" + n + "\"}").collect(Collectors.joining(",")) + "]}";
		Map<String, String> parameters = new HashMap<>();
		parameters.put("action", "move");
		FakeResponse response = new FakeResponse();
		servlet.doPost(request(pathInfo, "application/json", body, token, parameters), response.response());
		assertEquals("The move failed: " + response.body(), HttpServletResponse.SC_OK, response.status());
		return MoveResult.readMoveResult(reader(response.body()));
	}

	private ImageServlet servlet() throws IOException {
		ImageServlet servlet = new ImageServlet(_base.toFile(), new AuthService(AuthMode.WRITES, _base));
		_servlets.add(servlet);
		return servlet;
	}

	private String signIn() throws Exception {
		return Codes.signInAdmin(new AuthService(AuthMode.WRITES, _base), "Phone", "haui").getToken();
	}

	private void write(String relativePath, byte[] contents) throws IOException {
		Path path = _base.resolve(relativePath);
		Files.createDirectories(path.getParent());
		Files.write(path, contents);
	}

	private static byte[] jpeg(int width, int height, Color color) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		graphics.setColor(color);
		graphics.fillRect(0, 0, width, height);
		graphics.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "jpg", out);
		return out.toByteArray();
	}

	private static List<String> names(AlbumInfo album) {
		List<String> names = new ArrayList<>();
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart) {
				names.add(((ImagePart) part).getName());
			}
		}
		return names;
	}

	private static ImagePart image(AlbumInfo album, String name) {
		for (AlbumPart part : album.getParts()) {
			if (part instanceof ImagePart && ((ImagePart) part).getName().equals(name)) {
				return (ImagePart) part;
			}
		}
		fail("No image '" + name + "' in the album.");
		return null;
	}

	private static AlbumInfo album(FakeResponse response) throws IOException {
		assertEquals("Expected a successful request, got: " + response.body(), HttpServletResponse.SC_OK,
			response.status());
		Resource resource = Resource.readResource(reader(response.body()));
		assertTrue("Expected an album, got: " + resource, resource instanceof AlbumInfo);
		return (AlbumInfo) resource;
	}

	private static JsonReader reader(String contents) {
		return new JsonReader(new ReaderAdapter(new StringReader(contents)));
	}

	private static FakeResponse get(ImageServlet servlet, String pathInfo, String type, String token)
			throws Exception {
		Map<String, String> parameters = new HashMap<>();
		parameters.put("type", type);
		FakeResponse response = new FakeResponse();
		servlet.doGet(request(pathInfo, null, "", token, parameters), response.response());
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
