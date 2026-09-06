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
import de.haumacher.imageServer.shared.model.PairRequest;
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

		// Before: the made-up albums list what the folders hold; the pre-check knows x in A only.
		assertEquals(Collections.singletonList("x.jpg"), names(album(get(servlet, "/A/", "json", owner))));
		assertEquals(Collections.singletonList("x.jpg"), present(servlet, "/A/", owner, x));
		assertEquals(Collections.emptyList(), present(servlet, "/B/", owner, x));

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

		// The idempotent upload now knows x at B and no longer at A.
		assertEquals(Collections.singletonList("x.jpg"), present(servlet, "/B/", owner, x));
		assertEquals(Collections.emptyList(), present(servlet, "/A/", owner, x));

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
		ImageServlet servlet = new ImageServlet(_base.toFile(), new AuthService(AuthMode.WRITES, SECRET, _base));
		_servlets.add(servlet);
		return servlet;
	}

	private String signIn() throws Exception {
		return new AuthService(AuthMode.WRITES, SECRET, _base)
			.pair(PairRequest.create().setSecret(SECRET).setDeviceName("Phone").setUserName("haui")).getToken();
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
